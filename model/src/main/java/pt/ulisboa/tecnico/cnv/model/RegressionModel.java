package pt.ulisboa.tecnico.cnv.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RegressionModel {

    private final String workload;
    private final List<String> features;
    private final List<Double> coefficients;
    private final List<Double> featureMeans;
    private final List<Double> featureStdDevs;
    private final double intercept;
    private final double rSquared;
    private final int sampleCount;
    private final long trainedAtEpochMs;
    private final boolean logTarget;

    public RegressionModel(
            String workload,
            List<String> features,
            List<Double> coefficients,
            List<Double> featureMeans,
            List<Double> featureStdDevs,
            double intercept,
            double rSquared,
            int sampleCount,
            long trainedAtEpochMs) {
        this(workload, features, coefficients, featureMeans, featureStdDevs,
                intercept, rSquared, sampleCount, trainedAtEpochMs, false);
    }

    public RegressionModel(
            String workload,
            List<String> features,
            List<Double> coefficients,
            List<Double> featureMeans,
            List<Double> featureStdDevs,
            double intercept,
            double rSquared,
            int sampleCount,
            long trainedAtEpochMs,
            boolean logTarget) {
        this.workload = Objects.requireNonNull(workload, "workload");
        this.features = new ArrayList<>(Objects.requireNonNull(features, "features"));
        this.coefficients = new ArrayList<>(Objects.requireNonNull(coefficients, "coefficients"));
        this.featureMeans = new ArrayList<>(Objects.requireNonNull(featureMeans, "featureMeans"));
        this.featureStdDevs = new ArrayList<>(Objects.requireNonNull(featureStdDevs, "featureStdDevs"));
        if (this.features.size() != this.coefficients.size()) {
            throw new IllegalArgumentException("features and coefficients must have the same size");
        }
        if (this.features.size() != this.featureMeans.size()) {
            throw new IllegalArgumentException("features and featureMeans must have the same size");
        }
        if (this.features.size() != this.featureStdDevs.size()) {
            throw new IllegalArgumentException("features and featureStdDevs must have the same size");
        }
        this.intercept = intercept;
        this.rSquared = rSquared;
        this.sampleCount = sampleCount;
        this.trainedAtEpochMs = trainedAtEpochMs;
        this.logTarget = logTarget;
    }

    public String getWorkload() {
        return workload;
    }

    public List<String> getFeatures() {
        return Collections.unmodifiableList(features);
    }

    public List<Double> getCoefficients() {
        return Collections.unmodifiableList(coefficients);
    }

    public List<Double> getFeatureMeans() {
        return Collections.unmodifiableList(featureMeans);
    }

    public List<Double> getFeatureStdDevs() {
        return Collections.unmodifiableList(featureStdDevs);
    }

    public double getIntercept() {
        return intercept;
    }

    public double getRSquared() {
        return rSquared;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public long getTrainedAtEpochMs() {
        return trainedAtEpochMs;
    }

    public boolean isLogTarget() {
        return logTarget;
    }
}
