package pt.ulisboa.tecnico.cnv.smartbalancer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class TargetPool {

    private static final long MIN_COMPLEXITY = 1L;

    private static final int WORKER_HEALTH_TIMEOUT_MS = EnvConfig.getInt("WORKER_HEALTH_TIMEOUT_MS", 2000);

    private final int backendPort;
    private final long refreshSeconds;
    private final ConcurrentMap<String, BackendTarget> targets = new ConcurrentHashMap<>();
    private final List<String> staticTargets;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Queue<WaiterEntry> waitQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalQueuedComplexity = new AtomicLong(0);

    private static final class WaiterEntry {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final long complexity;
        WaiterEntry(long complexity) { this.complexity = complexity; }
    }

    TargetPool(int backendPort) {
        this.backendPort = backendPort;
        this.refreshSeconds = EnvConfig.getLong("SMART_LB_TARGET_REFRESH_SECONDS", 10L);
        this.staticTargets = parseStaticTargets(EnvConfig.getString("SMART_LB_STATIC_BACKENDS", ""));

        refreshTargets();
        scheduler.scheduleAtFixedRate(this::safeRefresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    Optional<BackendTarget> chooseTarget() {
        return chooseTarget(Collections.emptySet());
    }

    Optional<BackendTarget> chooseTarget(Set<String> excludeIds) {
        List<BackendTarget> available = new ArrayList<>(targets.values());
        if (available.isEmpty()) {
            return Optional.empty();
        }

        BackendTarget best = null;
        for (BackendTarget candidate : available) {
            if (candidate.retiring.get()) continue;
            if (excludeIds.contains(candidate.getId())) continue;
            if (best == null) {
                best = candidate;
                continue;
            }
            long currentLoad = candidate.inflightComplexity.get();
            long bestLoad = best.inflightComplexity.get();
            if (currentLoad < bestLoad) {
                best = candidate;
            } else if (currentLoad == bestLoad && candidate.inflightRequests.get() < best.inflightRequests.get()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    boolean hasTargets() {
        return !targets.isEmpty();
    }

    long getTotalInflightComplexity() {
        return targets.values().stream()
                .mapToLong(t -> t.inflightComplexity.get())
                .sum();
    }

    int getTargetCount() {
        return targets.size();
    }

    boolean retireByIp(String ip) {
        for (BackendTarget t : targets.values()) {
            if (t.host.equals(ip)) {
                t.retiring.set(true);
                return true;
            }
        }
        return false;
    }

    int getInflightRequestsForIp(String ip) {
        for (BackendTarget t : targets.values()) {
            if (t.host.equals(ip)) {
                return t.inflightRequests.get();
            }
        }
        return -1;
    }

    List<BackendTarget> getAllTargets() {
        return new ArrayList<>(targets.values());
    }

    void reserveLoad(BackendTarget target, long predictedComplexity) {
        long bounded = Math.max(predictedComplexity, MIN_COMPLEXITY);
        target.inflightComplexity.addAndGet(bounded);
        target.inflightRequests.incrementAndGet();
    }

    void releaseLoad(BackendTarget target, long predictedComplexity) {
        long bounded = Math.max(predictedComplexity, MIN_COMPLEXITY);
        target.inflightComplexity.addAndGet(-bounded);
        target.inflightRequests.decrementAndGet();
        WaiterEntry waiter = waitQueue.poll();
        if (waiter != null) {
            totalQueuedComplexity.addAndGet(-waiter.complexity);
            waiter.future.complete(null);
        }
    }

    CompletableFuture<Void> enqueueWaiter(long complexity) {
        WaiterEntry entry = new WaiterEntry(complexity);
        totalQueuedComplexity.addAndGet(complexity);
        waitQueue.add(entry);
        return entry.future;
    }

    long getTotalQueuedComplexity() {
        return totalQueuedComplexity.get();
    }

    private void safeRefresh() {
        try {
            refreshTargets();
        } catch (Exception e) {
            System.err.println("[SmartLB] Failed to refresh targets: " + e.getMessage());
        }
    }

    private void refreshTargets() {
        Map<String, BackendTarget> refreshed = new LinkedHashMap<>();
        int newTargets = 0;

        if (!staticTargets.isEmpty()) {
            for (String host : staticTargets) {
                String id = "static-" + host;
                BackendTarget existing = targets.get(id);
                if (existing != null) {
                    refreshed.put(id, existing);
                } else {
                    refreshed.put(id, new BackendTarget(id, host, backendPort));
                    newTargets++;
                }
            }
            targets.clear();
            targets.putAll(refreshed);
            signalWaiters(newTargets);
            return;
        }

        List<String> discoveredHosts = discoverAsgHosts();
        if (discoveredHosts == null) {
            // AWS CLI failed — keep current targets unchanged to avoid dropping known-good backends
            System.err.println("[SmartLB] Target discovery failed; keeping existing targets");
            return;
        }

        if (discoveredHosts.isEmpty()) {
            // Genuinely no running workers
            targets.clear();
            targets.put("fallback-localhost", new BackendTarget("fallback-localhost", "127.0.0.1", backendPort));
            return;
        }

        for (String host : discoveredHosts) {
            String id = "asg-" + host;
            BackendTarget existing = targets.get(id);
            if (existing != null) {
                refreshed.put(id, existing);
            } else if (isHealthy(host, backendPort)) {
                refreshed.put(id, new BackendTarget(id, host, backendPort));
                newTargets++;
            } else {
                System.out.printf("[SmartLB] %s not yet healthy, skipping until next refresh%n", host);
            }
        }

        targets.clear();
        targets.putAll(refreshed);
        signalWaiters(newTargets);
    }

    private void signalWaiters(int count) {
        for (int i = 0; i < count; i++) {
            WaiterEntry waiter = waitQueue.poll();
            if (waiter == null) break;
            totalQueuedComplexity.addAndGet(-waiter.complexity);
            waiter.future.complete(null);
        }
    }

    private boolean isHealthy(String host, int port) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://" + host + ":" + port + "/test").openConnection();
            conn.setConnectTimeout(WORKER_HEALTH_TIMEOUT_MS);
            conn.setReadTimeout(WORKER_HEALTH_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> discoverAsgHosts() {
        String ipsOutput = runCommand(
                "aws", "ec2", "describe-instances",
                "--filters",
                "Name=tag:CNV-Role,Values=worker",
                "Name=tag:CNV-Project,Values=cnv",
                "Name=instance-state-name,Values=running",
                "--query", "Reservations[].Instances[].PrivateIpAddress",
                "--output", "text");
        if (ipsOutput == null) {
            return null; // command failed — caller should keep existing state
        }
        return splitWhitespace(ipsOutput); // returns empty list if blank (no running instances)
    }

    private List<String> splitWhitespace(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String[] chunks = raw.trim().split("\\s+");
        List<String> values = new ArrayList<>();
        for (String chunk : chunks) {
            if (!chunk.isBlank()) {
                values.add(chunk.trim());
            }
        }
        return values;
    }

    private String runCommand(String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(' ');
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return null;
            }
            return output.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> parseStaticTargets(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }

        String[] chunks = raw.split(",");
        List<String> parsed = new ArrayList<>();
        for (String chunk : chunks) {
            String host = chunk.trim();
            if (!host.isBlank()) {
                parsed.add(host);
            }
        }
        return parsed;
    }

    static final class BackendTarget {
        private final String id;
        private final String host;
        private final int port;
        private final AtomicLong inflightComplexity = new AtomicLong(0L);
        private final AtomicInteger inflightRequests = new AtomicInteger(0);
        final AtomicBoolean retiring = new AtomicBoolean(false);

        private BackendTarget(String id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
        }

        String getId() { return id; }
        String getHost() { return host; }
        long getInflightComplexity() { return inflightComplexity.get(); }
        int getInflightRequests() { return inflightRequests.get(); }
        boolean isRetiring() { return retiring.get(); }

        String targetUrl(String path, String rawQuery) {
            StringBuilder builder = new StringBuilder();
            builder.append("http://").append(host).append(":").append(port).append(path);
            if (rawQuery != null && !rawQuery.isBlank()) {
                builder.append("?").append(rawQuery);
            }
            return builder.toString();
        }

        @Override
        public String toString() {
            return id + "(" + host + ":" + port + ")";
        }
    }
}
