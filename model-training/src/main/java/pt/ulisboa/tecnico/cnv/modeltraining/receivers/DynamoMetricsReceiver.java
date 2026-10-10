package pt.ulisboa.tecnico.cnv.modeltraining.receivers;

import pt.ulisboa.tecnico.cnv.modeltraining.HistoricalMetric;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DynamoMetricsReceiver {

    private final DynamoDbClient dynamoClient;
    private final String tableName;

    public DynamoMetricsReceiver(String tableName) {
        this.tableName = tableName;
        this.dynamoClient = createClient();
    }

    /**
     * Fetches all metrics for a workload newer than sinceEpochMs (exclusive).
     * Pass 0 to fetch all available metrics.
     */
    public List<HistoricalMetric> fetchSince(String workload, long sinceEpochMs) {
        return fetchMostRecent(workload, sinceEpochMs, Integer.MAX_VALUE);
    }

    /**
     * Fetches metrics for a workload newer than sinceEpochMs (exclusive),
     * returning at most the top N most recent metrics.
     */
    public List<HistoricalMetric> fetchMostRecent(String workload, long sinceEpochMs, int limit) {
        if (dynamoClient == null) {
            throw new IllegalStateException("DynamoDB client unavailable");
        }

        List<HistoricalMetric> result = new ArrayList<>();
        Map<String, AttributeValue> lastEvaluatedKey = null;

        do {
            ScanRequest.Builder builder = ScanRequest.builder()
                    .tableName(tableName)
                    .filterExpression("#workload = :workload AND #ts > :since")
                    .expressionAttributeNames(Map.of(
                            "#workload", "workload",
                            "#ts", "requestEpochMs"))
                    .expressionAttributeValues(Map.of(
                            ":workload", AttributeValue.builder().s(workload).build(),
                            ":since", AttributeValue.builder().n(Long.toString(sinceEpochMs)).build()))
                    .exclusiveStartKey(lastEvaluatedKey);

            ScanResponse response = dynamoClient.scan(builder.build());
            for (Map<String, AttributeValue> item : response.items()) {
                result.add(toMetric(item, workload));
            }

            lastEvaluatedKey = response.lastEvaluatedKey();
        } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

        // Sort descending by timestamp to get the most recent items
        result.sort(Comparator.comparing(HistoricalMetric::getRequestEpochMs).reversed());

        if (result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    private HistoricalMetric toMetric(Map<String, AttributeValue> item, String workloadFallback) {
        String requestId = extractRequestId(item);
        String workload = extractString(item.get("workload"), workloadFallback);
        long requestEpochMs = extractLong(item.get("requestEpochMs"), 0L);
        long complexity = extractLong(item.get("complexity"), 0L);

        Map<String, String> inputs = new HashMap<>();
        AttributeValue rawInputs = item.get("inputs");
        if (rawInputs != null && rawInputs.hasM()) {
            for (Map.Entry<String, AttributeValue> entry : rawInputs.m().entrySet()) {
                AttributeValue value = entry.getValue();
                if (value.s() != null) {
                    inputs.put(entry.getKey(), value.s());
                } else if (value.n() != null) {
                    inputs.put(entry.getKey(), value.n());
                }
            }
        }

        return new HistoricalMetric(requestId, workload, requestEpochMs, complexity, inputs);
    }

    private String extractRequestId(Map<String, AttributeValue> item) {
        AttributeValue sk = item.get("sk");
        if (sk != null && sk.s() != null && sk.s().contains("#")) {
            String[] split = sk.s().split("#", 2);
            if (split.length == 2) {
                return split[1];
            }
        }
        return UUID.randomUUID().toString();
    }

    private String extractString(AttributeValue value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String content = value.s();
        return content == null || content.isBlank() ? fallback : content;
    }

    private long extractLong(AttributeValue value, long fallback) {
        if (value == null || value.n() == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.n());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private DynamoDbClient createClient() {
        try {
            String region = System.getenv("AWS_DEFAULT_REGION");
            if (region != null && !region.isBlank()) {
                return DynamoDbClient.builder().region(Region.of(region)).build();
            }
            return DynamoDbClient.create();
        } catch (SdkException e) {
            return null;
        }
    }
}
