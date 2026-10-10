import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.RunInstancesMonitoringEnabled;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

public class AutoScaler {

    private static final double ALPHA = EnvConfig.getDouble("ALPHA_WEIGHT", 0.3);
    private static final double BETA  = EnvConfig.getDouble("BETA_WEIGHT",  0.7);

    private static final double LOAD_UPPER = EnvConfig.getDouble("CPU_UPPER_THRESHOLD", 80.0);
    private static final double LOAD_LOWER = EnvConfig.getDouble("CPU_LOWER_THRESHOLD", 20.0);

    private static final long MAX_COMPLEXITY_PER_WORKER = EnvConfig.getLong("SMART_LB_WORKER_COMPLEXITY_THRESHOLD", 1_900_000_000_000L);
    private static final int  SMART_LB_PORT             = EnvConfig.getInt("SMART_LB_PORT", 9000);
    private static final int  WORKER_PORT               = EnvConfig.getInt("WORKER_PORT", 8000);

    private static final long SCALE_OUT_COOLDOWN_SECONDS = EnvConfig.getLong("SCALE_OUT_COOLDOWN_SECONDS", 180);
    private static final long DRAIN_TIMEOUT_SECONDS = EnvConfig.getLong("DRAIN_TIMEOUT_SECONDS", 300);
    private static final long DRAIN_POLL_SECONDS = EnvConfig.getLong("DRAIN_POLL_SECONDS", 5);
    private static final int UNHEALTHY_TERMINATION_THRESHOLD = 2;

    int currentInstances;
    int maxInstances;
    int minInstances;
    List<Instance> instances;
    long checkerInterval; // in seconds
    Instant lastScaleOutTime = Instant.EPOCH;
    long lastQueuedComplexity = 0L;
    Map<String, Integer> consecutiveFailures = new HashMap<>();
    Set<String> drainingInstanceIds = ConcurrentHashMap.newKeySet();
    ExecutorService drainExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "autoscaler-drain");
        t.setDaemon(true);
        return t;
    });
    Ec2Client ec2Client = Ec2Client.builder().build();
    CloudWatchClient cloudWatchClient = CloudWatchClient.builder().build();
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String imageID = EnvConfig.getString("IMAGE_ID", "");

    public AutoScaler(int minInstances, int maxInstances, long checkerInterval) {
        this.minInstances = minInstances;
        this.maxInstances = maxInstances;
        this.checkerInterval = checkerInterval;
        this.currentInstances = minInstances;
        this.instances = new ArrayList<>();

    }

    public void start() {
        while (true) {
            try {
                Thread.sleep(checkerInterval * 1000);
                checkAndScale();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void checkAndScale() {
        currentInstances = describeEC2Instances(ec2Client);

        // Terminate instances whose worker JVM has died (OOM, crash, etc.)
        int terminated = 0;
        for (Instance instance : List.copyOf(instances)) {
            String id = instance.instanceId();
            if (drainingInstanceIds.contains(id)) continue;
            if (!isWorkerJvmAlive(instance)) {
                int failures = consecutiveFailures.merge(id, 1, Integer::sum);
                if (failures >= UNHEALTHY_TERMINATION_THRESHOLD) {
                    System.out.printf("[HealthCheck] Worker %s (%s) failed health check %d times in a row, terminating%n",
                            id, instance.privateIpAddress(), failures);
                    consecutiveFailures.remove(id);
                    terminateEC2(id);
                    terminated++;
                } else {
                    System.out.printf("[HealthCheck] Worker %s (%s) health check failed (%d/%d), waiting before terminating%n",
                            id, instance.privateIpAddress(), failures, UNHEALTHY_TERMINATION_THRESHOLD);
                }
            } else {
                consecutiveFailures.remove(id);
            }
        }
        if (terminated > 0) {
            currentInstances = Math.max(0, currentInstances - terminated);
        }

        // Exclude instances currently mid-drain from scaling decisions
        int effectiveInstances = currentInstances - drainingInstanceIds.size();

        // hard bounds: always enforce min/max
        if (effectiveInstances < minInstances) {
            System.out.println("Below minimum, scaling out (+1)");
            createEC2Instance(ec2Client, "CNV-Worker", imageID);
            return;
        }
        if (effectiveInstances > maxInstances) {
            System.out.println("Above maximum, scaling in (-1)");
            retireAndTerminate();
            return;
        }

        double avgCpu  = getAverageCPU(instances);
        double avgCplx = getAveragePoolComplexity();
        double systemLoad = ALPHA * avgCpu + BETA * avgCplx;

        System.out.printf("systemLoad=%.2f (cpu=%.2f×%.1f | cplx=%.2f×%.1f queue=%d) instances=%d (draining=%d)%n",
                systemLoad, avgCpu, ALPHA, avgCplx, BETA, lastQueuedComplexity, currentInstances, drainingInstanceIds.size());

        if (systemLoad > LOAD_UPPER * 2 && effectiveInstances + 1 < maxInstances) {
            System.out.printf("systemLoad %.2f > %.2f, scaling out (+2)%n", systemLoad, LOAD_UPPER * 2);
            createEC2Instance(ec2Client, "CNV-Worker", imageID);
            createEC2Instance(ec2Client, "CNV-Worker", imageID);
            lastScaleOutTime = Instant.now();
        } else if (systemLoad > LOAD_UPPER && effectiveInstances < maxInstances) {
            System.out.printf("systemLoad %.2f > %.2f, scaling out (+1)%n", systemLoad, LOAD_UPPER);
            createEC2Instance(ec2Client, "CNV-Worker", imageID);
            lastScaleOutTime = Instant.now();

        } else if (systemLoad < LOAD_LOWER && effectiveInstances > minInstances) {

            if (Instant.now().isAfter(lastScaleOutTime.plusSeconds(SCALE_OUT_COOLDOWN_SECONDS))) {
                System.out.printf("systemLoad %.2f < %.2f, scaling in (-1)%n", systemLoad, LOAD_LOWER);
                retireAndTerminate();
            } else {
                System.out.printf("systemLoad %.2f < %.2f but in cooldown, skipping scale-in%n", systemLoad, LOAD_LOWER);
            }
        }
    }

    // Returns the average CPU (%) across all instances, using a 3-minute rolling average per instance to smooth transient spikes
    private double getAverageCPU(List<Instance> instances) {
        if (instances.isEmpty()) return 0.0;

        Instant endTime = Instant.now();
        Instant startTime = endTime.minusSeconds(600); // 10 min covers basic monitoring (1/2min intervals)

        double total = 0.0;
        int counted = 0;

        for (Instance instance : instances) {
            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/EC2")
                    .metricName("CPUUtilization")
                    .dimensions(Dimension.builder()
                            .name("InstanceId")
                            .value(instance.instanceId())
                            .build())
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(60)
                    .statistics(Statistic.AVERAGE)
                    .build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);
            List<Datapoint> datapoints = response.datapoints();

            if (!datapoints.isEmpty()) {
                // average the 3 most recent datapoints
                double instanceAvg = datapoints.stream()
                        .sorted(Comparator.comparing(Datapoint::timestamp).reversed())
                        .limit(3)
                        .mapToDouble(Datapoint::average)
                        .average()
                        .orElse(0.0);
                total += instanceAvg;
                counted++;
            }
        }

        return counted > 0 ? total / counted : 0.0;
    }

    // Fetches inflight + queued complexity from the LB /metrics endpoint, normalized to 0-100.
    // Queue complexity is folded in so demand that exceeds current capacity pushes the metric above threshold.
    private double getAveragePoolComplexity() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + SMART_LB_PORT + "/metrics"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return 0.0;

            String body = response.body();
            long totalComplexity = parseLongField(body, "totalInflightComplexity");
            long workerCount     = parseLongField(body, "workerCount");
            lastQueuedComplexity = parseLongField(body, "queueComplexity");

            if (workerCount <= 0) return 0.0;

            double avgPerWorker = (double) (totalComplexity + lastQueuedComplexity) / workerCount;
            return (avgPerWorker / MAX_COMPLEXITY_PER_WORKER) * 100.0;
        } catch (Exception e) {
            System.err.println("Failed to fetch pool complexity from LB: " + e.getMessage());
            lastQueuedComplexity = 0L;
            return 0.0;
        }
    }

    private boolean isWorkerJvmAlive(Instance instance) {
        String ip = instance.privateIpAddress();
        if (ip == null || ip.isBlank()) return true;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + ip + ":" + WORKER_PORT + "/test"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof java.net.ConnectException) return false;
                cause = cause.getCause();
            }
            return true;
        }
    }

    private long parseLongField(String json, String field) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return obj.has(field) ? obj.get(field).getAsLong() : 0L;
    }

    private static class InstanceMetric {
        String ip;
        int inflightRequests;
        boolean retiring;
    }

    private void retireAndTerminate() {
        String metricsJson = fetchPerInstanceMetrics();
        if (metricsJson == null) {
            System.err.println("Could not fetch per-instance metrics, skipping scale-in");
            return;
        }

        List<InstanceMetric> metrics = parsePerInstanceMetrics(metricsJson);
        InstanceMetric candidate = metrics.stream()
                .filter(m -> !m.retiring)
                .min(Comparator.comparingInt(m -> m.inflightRequests))
                .orElse(null);
        if (candidate == null) {
            System.err.println("No non-retiring instance found to terminate");
            return;
        }

        String ip = candidate.ip;
        String instanceId = instances.stream()
                .filter(i -> ip.equals(i.privateIpAddress()))
                .map(Instance::instanceId)
                .findFirst()
                .orElse(null);
        if (instanceId == null) {
            System.err.printf("No EC2 instance matching IP %s, skipping%n", ip);
            return;
        }

        System.out.printf("Retiring instance %s (%s), inflightRequests=%d%n",
                instanceId, ip, candidate.inflightRequests);

        if (!signalRetire(ip)) {
            System.err.printf("LB did not acknowledge retire for %s, terminating immediately%n", ip);
            terminateEC2(instanceId);
            return;
        }

        drainingInstanceIds.add(instanceId);
        final String finalInstanceId = instanceId;
        final String finalIp = ip;
        drainExecutor.submit(() -> {
            try {
                Instant deadline = Instant.now().plusSeconds(DRAIN_TIMEOUT_SECONDS);
                while (Instant.now().isBefore(deadline)) {
                    int inflight = pollInflight(finalIp);
                    if (inflight <= 0) {
                        System.out.printf("Instance %s drained, terminating%n", finalIp);
                        break;
                    }
                    System.out.printf("Draining %s: %d inflight requests remaining%n", finalIp, inflight);
                    try {
                        Thread.sleep(DRAIN_POLL_SECONDS * 1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                terminateEC2(finalInstanceId);
            } finally {
                drainingInstanceIds.remove(finalInstanceId);
            }
        });
    }

    private String fetchPerInstanceMetrics() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + SMART_LB_PORT + "/metrics-per-instance"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            return response.body();
        } catch (Exception e) {
            System.err.println("Failed to fetch per-instance metrics: " + e.getMessage());
            return null;
        }
    }

    private boolean signalRetire(String ip) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + SMART_LB_PORT + "/retire?ip=" + ip))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("Failed to signal retire for " + ip + ": " + e.getMessage());
            return false;
        }
    }

    private int pollInflight(String ip) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + SMART_LB_PORT + "/retire-status?ip=" + ip))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return -1;
            return (int) parseLongField(response.body(), "inflightRequests");
        } catch (Exception e) {
            System.err.println("Failed to poll inflight for " + ip + ": " + e.getMessage());
            return -1;
        }
    }

    private List<InstanceMetric> parsePerInstanceMetrics(String json) {
        List<InstanceMetric> result = new ArrayList<>();
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonObject obj = array.get(i).getAsJsonObject();
            if (!obj.has("ip")) continue;
            InstanceMetric m  = new InstanceMetric();
            m.ip               = obj.get("ip").getAsString();
            m.inflightRequests = obj.has("inflightRequests") ? obj.get("inflightRequests").getAsInt() : 0;
            m.retiring         = obj.has("retiring") && obj.get("retiring").getAsBoolean();
            result.add(m);
        }
        return result;
    }

    public int describeEC2Instances(Ec2Client ec2){

        String nextToken = null;
        int count = 0;
        instances.clear();

        try {
            do {
                Filter filter = Filter.builder().name("tag:CNV-Role").values("worker").build();
                Filter filter2 = Filter.builder().name("tag:CNV-Project").values("cnv").build();
                Filter filter3 = Filter.builder().name("instance-state-name").values("pending", "running").build();
                
                DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(filter, filter2, filter3)
                    .maxResults(6)
                    .nextToken(nextToken)
                    .build();

                DescribeInstancesResponse response = ec2.describeInstances(request);

                for (Reservation reservation : response.reservations()) {
                    for (Instance instance : reservation.instances()) {
                        System.out.println("[DescInstanceLog] Instance Id is " + instance.instanceId());
                        count++;
                        instances.add(instance);
                    }
                }
                nextToken = response.nextToken();
            } while (nextToken != null);

        } catch (Ec2Exception e) {
            System.err.println(e.awsErrorDetails().errorMessage());
        }
        return count;
    }

    public String createEC2Instance(Ec2Client ec2,String name, String amiId ) {

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(amiId)
                .instanceType(InstanceType.T3_MICRO)
                .maxCount(1)
                .minCount(1)
                .keyName(EnvConfig.getString("AWS_KEYPAIR_NAME", ""))
                .securityGroupIds(EnvConfig.getString("AWS_SECURITY_GROUP", ""))
                .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                        .name(EnvConfig.getString("AWS_INSTANCE_PROFILE_NAME", "WorkerInstanceProfile"))
                        .build())
                .monitoring(RunInstancesMonitoringEnabled.builder().enabled(true).build())
                .build();

        RunInstancesResponse response = ec2.runInstances(runRequest);
        String instanceId = response.instances().get(0).instanceId();

        Tag tag = Tag.builder().key("Name").value(name).build();
        Tag tag2 = Tag.builder().key("CNV-Role").value("worker").build();
        Tag tag3 = Tag.builder().key("CNV-Project").value("cnv").build();

        CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(tag, tag2, tag3)
                .build();

        try {
            ec2.createTags(tagRequest);
            System.out.printf(
                    "Successfully started EC2 Instance %s based on AMI %s%n",
                    instanceId, amiId);

          return instanceId;

        } catch (Ec2Exception e) {
            System.err.println(e.awsErrorDetails().errorMessage());
        }

        return "";
    }

    public String terminateEC2(String instanceId) {
        TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
            .instanceIds(instanceId)
            .build();

        try {
            this.ec2Client.terminateInstances(terminateRequest);
            System.out.printf("Successfully terminated EC2 Instance %s%n", instanceId);

            return instanceId;

        } catch (Ec2Exception e) {
            System.err.println(e.awsErrorDetails().errorMessage());
        }
        return "";
    }
}
