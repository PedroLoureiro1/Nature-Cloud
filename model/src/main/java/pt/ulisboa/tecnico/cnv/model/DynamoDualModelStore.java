package pt.ulisboa.tecnico.cnv.model;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB persistence for {@link DualRegressionModel}.
 *
 * <p>Uses the same table schema as {@link DynamoModelStore}:
 * <ul>
 *   <li>{@code pk} — workload string (e.g. {@code "dna"})</li>
 *   <li>{@code sk} — trainedAtEpochMs (number, sort key — newest first)</li>
 * </ul>
 * The full model is stored as a single JSON blob in {@code modelJson}, and a
 * {@code modelType} attribute is written as {@code "dual"} so consumers can
 * tell it apart from a plain {@link RegressionModel} row.</p>
 */
public final class DynamoDualModelStore {

    private DynamoDualModelStore() {
    }

    public static void save(DualRegressionModel model, String tableName) {
        long now = Instant.now().toEpochMilli();
        DynamoDbClient client = createClient();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk",               AttributeValue.builder().s(model.getWorkload()).build());
        item.put("sk",               AttributeValue.builder().n(Long.toString(now)).build());
        item.put("trainedAtEpochMs", AttributeValue.builder().n(Long.toString(now)).build());
        item.put("modelType",        AttributeValue.builder().s("dual").build());
        item.put("partitionFeature", AttributeValue.builder().s(model.getPartitionFeature()).build());

        // aggregate stats for observability (best sub-model R²)
        double bestR2 = Math.max(
                model.getFalseModel().getRSquared(),
                model.getTrueModel().getRSquared());
        int totalSamples = model.getFalseModel().getSampleCount()
                + model.getTrueModel().getSampleCount();
        item.put("rSquared",    AttributeValue.builder().n(Double.toString(bestR2)).build());
        item.put("sampleCount", AttributeValue.builder().n(Integer.toString(totalSamples)).build());

        item.put("modelJson", AttributeValue.builder().s(DualModelJsonCodec.toJson(model)).build());

        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    public static Optional<DualRegressionModel> loadLatest(String workload, String tableName) {
        DynamoDbClient client = createClient();
        QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :w")
                .filterExpression("modelType = :t")
                .expressionAttributeValues(Map.of(
                        ":w", AttributeValue.builder().s(workload).build(),
                        ":t", AttributeValue.builder().s("dual").build()))
                .scanIndexForward(false)
                .limit(10) // scan a few rows in case older plain-model rows sit at the top
                .build());

        List<Map<String, AttributeValue>> items = response.items();
        if (items.isEmpty()) {
            return Optional.empty();
        }

        AttributeValue modelJsonAttr = items.get(0).get("modelJson");
        if (modelJsonAttr == null || modelJsonAttr.s() == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(DualModelJsonCodec.fromJson(modelJsonAttr.s()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static DynamoDbClient createClient() {
        String region = System.getenv("AWS_DEFAULT_REGION");
        if (region != null && !region.isBlank()) {
            return DynamoDbClient.builder().region(Region.of(region)).build();
        }
        return DynamoDbClient.create();
    }
}
