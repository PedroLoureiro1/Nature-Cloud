package pt.ulisboa.tecnico.cnv.javassist.runtime;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import pt.ulisboa.tecnico.cnv.javassist.tools.WeightedInstructions;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public final class HhsMetricsRuntime {

    private static final String TABLE_ENV = "HHS_DDB_TABLE";
    private static final String REGION_ENV = "AWS_REGION";
    private static final String INSTANCE_ENV = "HHS_INSTANCE_ID";

    private static final String DEFAULT_TABLE = "hhs-workload-metrics";
    private static final long FLUSH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int DYNAMODB_BATCH_MAX = 25;
    private static final int MAX_UNPROCESSED_RETRY = 5;

    private static final Queue<RequestMetric> BUFFER = new ConcurrentLinkedQueue<>();
    private static final ScheduledExecutorService FLUSH_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new FlushThreadFactory());

    private static final String TABLE_NAME = readTableName();
    private static final String INSTANCE_ID = readInstanceId();
    private static final DynamoDbClient DYNAMODB_CLIENT = createDynamoClient();

    static {
        FLUSH_EXECUTOR.scheduleAtFixedRate(
                HhsMetricsRuntime::safeFlush,
                FLUSH_INTERVAL_MILLIS,
                FLUSH_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(HhsMetricsRuntime::safeFlush, "hhs-metrics-flush-shutdown"));
    }

    private HhsMetricsRuntime() {
    }

    public static void recordRequest(String workload, Map<String, String> normalizedInputs, long complexityDelta) {
        if (workload == null || workload.isBlank()) {
            return;
        }

        long requestEpochMs = Instant.now().toEpochMilli();
        long windowStartEpochMs = requestEpochMs - (requestEpochMs % FLUSH_INTERVAL_MILLIS);
        long safeComplexityDelta = Math.max(0L, complexityDelta);
        Map<String, String> safeInputs = normalizedInputs == null
                ? Collections.emptyMap()
                : new HashMap<>(normalizedInputs);

        BUFFER.add(new RequestMetric(
                UUID.randomUUID().toString(),
                workload,
                requestEpochMs,
                windowStartEpochMs,
                safeComplexityDelta,
                safeInputs));
    }

    public static void flushNow() {
        flushBufferedRequests();
    }

    public static long currentWeightedTotal() {
        return WeightedInstructions.getThreadWeighted();
    }

    private static synchronized void flushBufferedRequests() {
        if (BUFFER.isEmpty()) {
            return;
        }

        List<RequestMetric> drained = new ArrayList<>();
        RequestMetric metric;
        while ((metric = BUFFER.poll()) != null) {
            drained.add(metric);
        }

        if (drained.isEmpty()) {
            return;
        }

        try {
            batchWriteRequests(drained);
        } catch (Exception e) {
            System.err.println("[HhsMetricsRuntime] DynamoDB flush failed. Will retry next cycle.");
            e.printStackTrace();
            BUFFER.addAll(drained);
        }
    }

    private static void safeFlush() {
        try {
            flushBufferedRequests();
        } catch (Exception e) {
            System.err.println("[HhsMetricsRuntime] Unexpected flush exception.");
            e.printStackTrace();
        }
    }

    private static void batchWriteRequests(List<RequestMetric> requests) {
        if (DYNAMODB_CLIENT == null) {
            throw new IllegalStateException("DynamoDB client is unavailable.");
        }

        for (int index = 0; index < requests.size(); index += DYNAMODB_BATCH_MAX) {
            int upperBound = Math.min(index + DYNAMODB_BATCH_MAX, requests.size());
            List<RequestMetric> chunk = requests.subList(index, upperBound);
            List<WriteRequest> writeRequests = new ArrayList<>(chunk.size());

            for (RequestMetric requestMetric : chunk) {
                Map<String, AttributeValue> item = toDynamoItem(requestMetric);
                writeRequests.add(WriteRequest.builder()
                        .putRequest(builder -> builder.item(item))
                        .build());
            }

            Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(TABLE_NAME, writeRequests);

            BatchWriteItemRequest request = BatchWriteItemRequest.builder()
                    .requestItems(requestItems)
                    .build();
            BatchWriteItemResponse response = DYNAMODB_CLIENT.batchWriteItem(request);
            retryUnprocessedItems(response);
        }
    }

    private static void retryUnprocessedItems(BatchWriteItemResponse initialResponse) {
        Map<String, List<WriteRequest>> unprocessed = initialResponse.unprocessedItems();
        int retries = 0;

        while (unprocessed != null && !unprocessed.isEmpty() && retries < MAX_UNPROCESSED_RETRY) {
            BatchWriteItemRequest retryRequest = BatchWriteItemRequest.builder()
                    .requestItems(unprocessed)
                    .build();
            BatchWriteItemResponse retryResponse = DYNAMODB_CLIENT.batchWriteItem(retryRequest);
            unprocessed = retryResponse.unprocessedItems();
            retries++;
        }

        if (unprocessed != null && !unprocessed.isEmpty()) {
            throw new IllegalStateException("Unable to persist all DynamoDB items after retries.");
        }
    }

    private static Map<String, AttributeValue> toDynamoItem(RequestMetric requestMetric) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.builder().s(INSTANCE_ID).build());
        item.put("sk", AttributeValue.builder().s(String.format("%013d#%s", requestMetric.requestEpochMs, requestMetric.requestId)).build());
        item.put("workload", AttributeValue.builder().s(requestMetric.workload).build());
        item.put("windowStartEpochMs", AttributeValue.builder().n(Long.toString(requestMetric.windowStartEpochMs)).build());
        item.put("requestEpochMs", AttributeValue.builder().n(Long.toString(requestMetric.requestEpochMs)).build());
        item.put("complexity", AttributeValue.builder().n(Long.toString(requestMetric.complexityDelta)).build());
        item.put("inputs", AttributeValue.builder().m(toInputAttributes(requestMetric.normalizedInputs)).build());
        return item;
    }

    private static Map<String, AttributeValue> toInputAttributes(Map<String, String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, AttributeValue> attributes = new HashMap<>();
        for (Map.Entry<String, String> entry : inputs.entrySet()) {
            String value = Objects.toString(entry.getValue(), "");
            attributes.put(entry.getKey(), AttributeValue.builder().s(value).build());
        }
        return attributes;
    }

    private static DynamoDbClient createDynamoClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder();
        String region = System.getenv(REGION_ENV);
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }

        try {
            return builder.build();
        } catch (SdkException e) {
            System.err.println("[HhsMetricsRuntime] Failed to initialize DynamoDB client. Flush will retry when configuration is fixed.");
            e.printStackTrace();
            return null;
        }
    }

    private static String readTableName() {
        String table = System.getenv(TABLE_ENV);
        if (table == null || table.isBlank()) {
            return DEFAULT_TABLE;
        }
        return table;
    }

    private static String readInstanceId() {
        String envInstanceId = System.getenv(INSTANCE_ENV);
        if (envInstanceId != null && !envInstanceId.isBlank()) {
            return envInstanceId;
        }

        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return hostname + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "instance-" + UUID.randomUUID();
        }
    }

    private static final class FlushThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "hhs-metrics-flusher");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class RequestMetric {
        private final String requestId;
        private final String workload;
        private final long requestEpochMs;
        private final long windowStartEpochMs;
        private final long complexityDelta;
        private final Map<String, String> normalizedInputs;

        private RequestMetric(
                String requestId,
                String workload,
                long requestEpochMs,
                long windowStartEpochMs,
                long complexityDelta,
                Map<String, String> normalizedInputs) {
            this.requestId = requestId;
            this.workload = workload;
            this.requestEpochMs = requestEpochMs;
            this.windowStartEpochMs = windowStartEpochMs;
            this.complexityDelta = complexityDelta;
            this.normalizedInputs = new HashMap<>(normalizedInputs);
        }
    }
}
