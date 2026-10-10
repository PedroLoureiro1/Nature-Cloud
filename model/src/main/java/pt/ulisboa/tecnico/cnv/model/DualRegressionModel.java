package pt.ulisboa.tecnico.cnv.model;

import java.util.Objects;

/**
 * A composite model that partitions predictions by a boolean feature.
 *
 * <p>During training, the historical data is split into two groups based on whether
 * {@code partitionFeature} is {@code true} or {@code false}, and a separate
 * {@link RegressionModel} is fit on each group. At prediction time the correct
 * sub-model is selected by reading the same feature from the incoming request.</p>
 *
 * <p>Example usage for DNA workload:</p>
 * <pre>
 *   DualRegressionModel m = new DualRegressionModel("dna", "stopOnFirst", falseModel, trueModel);
 *   double complexity = DualRegressionFunction.predictComplexity(m, requestInputs);
 * </pre>
 */
public final class DualRegressionModel {

    private final String workload;
    private final String partitionFeature;
    private final RegressionModel falseModel;   // partitionFeature == false
    private final RegressionModel trueModel;    // partitionFeature == true

    public DualRegressionModel(
            String workload,
            String partitionFeature,
            RegressionModel falseModel,
            RegressionModel trueModel) {
        this.workload = Objects.requireNonNull(workload, "workload");
        this.partitionFeature = Objects.requireNonNull(partitionFeature, "partitionFeature");
        this.falseModel = Objects.requireNonNull(falseModel, "falseModel");
        this.trueModel = Objects.requireNonNull(trueModel, "trueModel");
    }

    public String getWorkload() {
        return workload;
    }

    public String getPartitionFeature() {
        return partitionFeature;
    }

    /** Sub-model trained on samples where {@code partitionFeature} was {@code false}. */
    public RegressionModel getFalseModel() {
        return falseModel;
    }

    /** Sub-model trained on samples where {@code partitionFeature} was {@code true}. */
    public RegressionModel getTrueModel() {
        return trueModel;
    }

    /** Convenience: return the sub-model for the given partition value. */
    public RegressionModel subModel(boolean partitionValue) {
        return partitionValue ? trueModel : falseModel;
    }
}
