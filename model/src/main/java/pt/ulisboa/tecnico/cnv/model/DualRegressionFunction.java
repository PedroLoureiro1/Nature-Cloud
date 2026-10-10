package pt.ulisboa.tecnico.cnv.model;

import java.util.Map;

/**
 * Prediction entry point for a {@link DualRegressionModel}.
 *
 * <p>Reads {@link DualRegressionModel#getPartitionFeature()} from the incoming
 * request inputs, selects the appropriate sub-model, and delegates to
 * {@link RegressionFunction#predictComplexity(RegressionModel, Map)}.</p>
 *
 * <p>Usage example (DNA workload):</p>
 * <pre>
 *   DualRegressionModel model = DynamoDualModelStore.loadLatest("dna", tableName).orElseThrow();
 *   double est = DualRegressionFunction.predictComplexity(model, requestInputs);
 * </pre>
 */
public final class DualRegressionFunction {

    private DualRegressionFunction() {
    }

    /**
     * Predicts the complexity for the given request.
     *
     * @param model         the dual model (contains both partitions)
     * @param requestInputs the normalised inputs from the incoming HTTP request
     * @return predicted complexity (same unit as the training target)
     */
    public static double predictComplexity(
            DualRegressionModel model,
            Map<String, String> requestInputs) {

        boolean partitionValue = resolveBoolean(requestInputs, model.getPartitionFeature());
        RegressionModel subModel = model.subModel(partitionValue);
        return RegressionFunction.predictComplexity(subModel, requestInputs);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static boolean resolveBoolean(Map<String, String> inputs, String feature) {
        String raw = inputs == null ? null : inputs.get(feature);
        return "true".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }
}
