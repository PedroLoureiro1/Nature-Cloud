package pt.ulisboa.tecnico.cnv.modeltraining;

import pt.ulisboa.tecnico.cnv.model.DualRegressionModel;
import pt.ulisboa.tecnico.cnv.model.RegressionModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trains a {@link DualRegressionModel} by splitting the historical data on a
 * boolean feature and fitting a {@link LinearRegressionTrainer} on each partition.
 *
 * <p>The partition feature is read from {@link HistoricalMetric#getNormalizedInputs()}.
 * Any value that is {@code "true"} (case-insensitive) is routed to the <em>true</em>
 * partition; everything else (including missing values) is routed to the
 * <em>false</em> partition.</p>
 *
 * <p>Both partitions must independently satisfy the minimum-samples requirement of
 * {@link LinearRegressionTrainer} (i.e. samples &gt; features). A descriptive
 * {@link IllegalArgumentException} is thrown when either partition is too small.</p>
 */
public final class PartitionedRegressionTrainer {

    private final LinearRegressionTrainer delegate = new LinearRegressionTrainer();

    /**
     * @param workload          e.g. {@code "dna"}
     * @param partitionFeature  the boolean input key to split on, e.g. {@code "stopOnFirst"}
     * @param metrics           full training set (both partitions combined)
     * @return a {@link DualRegressionModel} containing one sub-model per partition
     * @throws IllegalArgumentException if either partition is too small or has no numeric features
     */
    public DualRegressionModel train(
            String workload,
            String partitionFeature,
            List<HistoricalMetric> metrics) {

        if (metrics == null || metrics.isEmpty()) {
            throw new IllegalArgumentException("No metrics available for training");
        }

        List<HistoricalMetric> falsePartition = new ArrayList<>();
        List<HistoricalMetric> truePartition = new ArrayList<>();

        for (HistoricalMetric metric : metrics) {
            boolean partitionValue = resolveBoolean(metric.getNormalizedInputs(), partitionFeature);
            if (partitionValue) {
                truePartition.add(metric);
            } else {
                falsePartition.add(metric);
            }
        }

        validatePartition(workload, partitionFeature, false, falsePartition);
        validatePartition(workload, partitionFeature, true, truePartition);

        RegressionModel falseModel = delegate.train(workload, falsePartition);
        RegressionModel trueModel  = delegate.train(workload, truePartition);

        return new DualRegressionModel(workload, partitionFeature, falseModel, trueModel);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static boolean resolveBoolean(Map<String, String> inputs, String feature) {
        String raw = inputs.get(feature);
        return "true".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    private static void validatePartition(
            String workload,
            String feature,
            boolean value,
            List<HistoricalMetric> partition) {

        if (partition.isEmpty()) {
            throw new IllegalArgumentException(
                    "No samples in partition " + feature + "=" + value
                            + " for workload=" + workload
                            + ". Cannot train a dual model without data in both partitions.");
        }
        // LinearRegressionTrainer requires samples > features; surfaces a clear message here
        // rather than waiting for the OLS library to throw an opaque error.
        if (partition.size() <= 1) {
            throw new IllegalArgumentException(
                    "Partition " + feature + "=" + value
                            + " has only " + partition.size() + " sample(s) for workload=" + workload
                            + ". Need at least 2 samples (samples must exceed feature count).");
        }
    }
}
