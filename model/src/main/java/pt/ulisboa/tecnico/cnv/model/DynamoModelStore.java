package pt.ulisboa.tecnico.cnv.model;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DynamoModelStore {

    private DynamoModelStore() {
    }

    public static void save(RegressionModel model, String tableName) {
        DynamoDbClient client = createClient();
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.builder().s(model.getWorkload()).build());
        item.put("sk", AttributeValue.builder().n(Long.toString(model.getTrainedAtEpochMs())).build());
        item.put("trainedAtEpochMs", AttributeValue.builder().n(Long.toString(model.getTrainedAtEpochMs())).build());
        item.put("sampleCount", AttributeValue.builder().n(Integer.toString(model.getSampleCount())).build());
        item.put("rSquared", AttributeValue.builder().n(Double.toString(model.getRSquared())).build());
        item.put("modelJson", AttributeValue.builder().s(ModelJsonCodec.toJson(model)).build());

        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    public static Optional<RegressionModel> loadLatest(String workload, String tableName) {
        DynamoDbClient client = createClient();
        QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :w")
                .expressionAttributeValues(Map.of(
                        ":w", AttributeValue.builder().s(workload).build()))
                .scanIndexForward(false)
                .limit(1)
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
            return Optional.of(ModelJsonCodec.fromJson(modelJsonAttr.s()));
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
