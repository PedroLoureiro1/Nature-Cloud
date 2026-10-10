package pt.ulisboa.tecnico.cnv.modeltraining;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math3.linear.SingularMatrixException;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

import pt.ulisboa.tecnico.cnv.model.RegressionModel;

public final class LinearRegressionTrainer {

    public RegressionModel train(String workload, List<HistoricalMetric> metrics) {
        return trainInternal(workload, metrics, "dna".equals(workload));
    }

    private RegressionModel trainInternal(String workload, List<HistoricalMetric> metrics, boolean logSpace) {
        if (metrics == null || metrics.isEmpty()) {
            throw new IllegalArgumentException("No metrics available for training");
        }

        List<String> candidateFeatures = findNumericFeatures(workload, metrics, logSpace);
        if (candidateFeatures.isEmpty()) {
            throw new IllegalArgumentException("No numeric features available for regression");
        }

        int sampleCount = metrics.size();
        int featureCount = candidateFeatures.size();

        if (sampleCount <= featureCount) {
            throw new IllegalArgumentException(
                    "Insufficient samples for regression: samples must be greater than features");
        }

        double[] y = new double[sampleCount];
        double[][] rawX = new double[sampleCount][featureCount];

        for (int row = 0; row < sampleCount; row++) {
            HistoricalMetric metric = metrics.get(row);
            double complexity = metric.getComplexity();
            y[row] = logSpace ? Math.log(Math.max(complexity, 1.0)) : complexity;
            for (int col = 0; col < featureCount; col++) {
                String feature = candidateFeatures.get(col);
                rawX[row][col] = parseNumeric(readFeatureValue(workload, metric, feature));
            }
        }

        List<Double> featureMeans = computeMeans(rawX, featureCount);
        List<Double> featureStdDevs = computeStdDevs(rawX, featureMeans, featureCount);

        List<Integer> keptColumns = new ArrayList<>();
        List<String> keptFeatures = new ArrayList<>();
        List<Double> keptMeans = new ArrayList<>();
        List<Double> keptStdDevs = new ArrayList<>();
        for (int col = 0; col < featureCount; col++) {
            double stdDev = featureStdDevs.get(col);
            if (stdDev > 1.0e-9) {
                keptColumns.add(col);
                keptFeatures.add(candidateFeatures.get(col));
                keptMeans.add(featureMeans.get(col));
                keptStdDevs.add(stdDev);
            }
        }

        if (keptColumns.isEmpty()) {
            throw new IllegalArgumentException("No non-constant numeric features available for regression");
        }

        return fitWithFallback(workload, y, rawX, sampleCount, keptColumns, keptFeatures, keptMeans, keptStdDevs, logSpace);
    }

    private RegressionModel fitWithFallback(
            String workload,
            double[] y,
            double[][] rawX,
            int sampleCount,
            List<Integer> keptColumns,
            List<String> keptFeatures,
            List<Double> keptMeans,
            List<Double> keptStdDevs,
            boolean logSpace) {

        List<Integer> columns = new ArrayList<>(keptColumns);
        List<String> features = new ArrayList<>(keptFeatures);
        List<Double> means = new ArrayList<>(keptMeans);
        List<Double> stdDevs = new ArrayList<>(keptStdDevs);

        while (!columns.isEmpty()) {
            try {
                double[][] x = normalize(rawX, means, stdDevs, columns);

                OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();
                regression.setNoIntercept(false);
                regression.newSampleData(y, x);

                double[] beta = regression.estimateRegressionParameters();
                List<Double> coefficients = new ArrayList<>();
                for (int i = 1; i < beta.length; i++) {
                    coefficients.add(beta[i]);
                }

                return new RegressionModel(
                        workload,
                        features,
                        coefficients,
                        means,
                        stdDevs,
                        beta[0],
                        regression.calculateRSquared(),
                        sampleCount,
                        Instant.now().toEpochMilli(),
                        logSpace);
            } catch (SingularMatrixException ex) {
                if (columns.size() == 1) {
                    throw ex;
                }

                int removeIndex = indexOfSmallestStdDev(stdDevs);
                columns.remove(removeIndex);
                features.remove(removeIndex);
                means.remove(removeIndex);
                stdDevs.remove(removeIndex);
            }
        }

        throw new IllegalArgumentException("No usable numeric features available for regression");
    }

    private List<Double> computeMeans(double[][] rawX, int featureCount) {
        List<Double> means = new ArrayList<>(featureCount);
        for (int col = 0; col < featureCount; col++) {
            double sum = 0.0;
            for (double[] row : rawX) {
                sum += row[col];
            }
            means.add(sum / rawX.length);
        }
        return means;
    }

    private List<Double> computeStdDevs(double[][] rawX, List<Double> means, int featureCount) {
        List<Double> stdDevs = new ArrayList<>(featureCount);
        for (int col = 0; col < featureCount; col++) {
            double varianceSum = 0.0;
            for (double[] row : rawX) {
                double delta = row[col] - means.get(col);
                varianceSum += delta * delta;
            }
            double stdDev = Math.sqrt(varianceSum / rawX.length);
            if (stdDev == 0.0 || Double.isNaN(stdDev) || Double.isInfinite(stdDev)) {
                stdDev = 1.0;
            }
            stdDevs.add(stdDev);
        }
        return stdDevs;
    }

    private double[][] normalize(double[][] rawX, List<Double> means, List<Double> stdDevs, List<Integer> keptColumns) {
        double[][] normalized = new double[rawX.length][means.size()];
        for (int row = 0; row < rawX.length; row++) {
            for (int col = 0; col < keptColumns.size(); col++) {
                int sourceCol = keptColumns.get(col);
                normalized[row][col] = (rawX[row][sourceCol] - means.get(col)) / stdDevs.get(col);
            }
        }
        return normalized;
    }

    private int indexOfSmallestStdDev(List<Double> stdDevs) {
        int index = 0;
        double smallest = stdDevs.get(0);
        for (int i = 1; i < stdDevs.size(); i++) {
            double value = stdDevs.get(i);
            if (value < smallest) {
                smallest = value;
                index = i;
            }
        }
        return index;
    }

    private List<String> findNumericFeatures(String workload, List<HistoricalMetric> metrics, boolean logSpace) {
        Set<String> ordered = new LinkedHashSet<>();
        for (HistoricalMetric metric : metrics) {
            for (String rawFeature : metric.getNormalizedInputs().keySet()) {
                ordered.add(canonicalFeatureName(workload, rawFeature));
            }
        }
        if ("dna".equals(workload)) {
            if (logSpace) {
                ordered.add("logSeq1Length");
                ordered.add("logSeq2Length");
                ordered.add("logMinLength");
            } else {
                ordered.add("seq1xseq2");
                ordered.add("seq1LengthSq");
                ordered.add("seq2LengthSq");
            }
        }

        List<String> features = new ArrayList<>();
        for (String feature : ordered) {
            boolean allNumeric = true;
            for (HistoricalMetric metric : metrics) {
                String rawValue = readFeatureValue(workload, metric, feature);
                if (rawValue == null) {
                    allNumeric = false;
                    break;
                }
                try {
                    parseNumeric(rawValue);
                } catch (IllegalArgumentException ex) {
                    allNumeric = false;
                    break;
                }
            }
            if (allNumeric) {
                features.add(feature);
            }
        }

        Collections.sort(features);
        return features;
    }

    private String readFeatureValue(String workload, HistoricalMetric metric, String canonicalFeature) {
        Map<String, String> inputs = metric.getNormalizedInputs();
        String direct = inputs.get(canonicalFeature);
        if (direct != null) {
            return direct;
        }

        if ("fractals".equals(workload)) {
            if ("w".equals(canonicalFeature)) {
                return inputs.get("width");
            }
            if ("h".equals(canonicalFeature)) {
                return inputs.get("height");
            }
        }

        if ("dna".equals(workload)) {
            if ("seq1Length".equals(canonicalFeature)) {
                return inputs.get("seq1_length");
            }
            if ("seq2Length".equals(canonicalFeature)) {
                return inputs.get("seq2_length");
            }
            if ("seq1xseq2".equals(canonicalFeature)) {
                String s1 = inputs.containsKey("seq1_length") ? inputs.get("seq1_length") : inputs.get("seq1Length");
                String s2 = inputs.containsKey("seq2_length") ? inputs.get("seq2_length") : inputs.get("seq2Length");
                if (s1 != null && s2 != null) {
                    try {
                        long product = (long) Double.parseDouble(s1) * (long) Double.parseDouble(s2);
                        return String.valueOf(product);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
            if ("seq1LengthSq".equals(canonicalFeature)) {
                String s1 = inputs.containsKey("seq1_length") ? inputs.get("seq1_length") : inputs.get("seq1Length");
                if (s1 != null) {
                    try {
                        double v = Double.parseDouble(s1);
                        return String.valueOf(v * v);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
            if ("seq2LengthSq".equals(canonicalFeature)) {
                String s2 = inputs.containsKey("seq2_length") ? inputs.get("seq2_length") : inputs.get("seq2Length");
                if (s2 != null) {
                    try {
                        double v = Double.parseDouble(s2);
                        return String.valueOf(v * v);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
            if ("logSeq1Length".equals(canonicalFeature)) {
                String s1 = inputs.containsKey("seq1_length") ? inputs.get("seq1_length") : inputs.get("seq1Length");
                if (s1 != null) {
                    try {
                        double v = Double.parseDouble(s1);
                        return String.valueOf(Math.log(Math.max(v, 1.0)));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
            if ("logSeq2Length".equals(canonicalFeature)) {
                String s2 = inputs.containsKey("seq2_length") ? inputs.get("seq2_length") : inputs.get("seq2Length");
                if (s2 != null) {
                    try {
                        double v = Double.parseDouble(s2);
                        return String.valueOf(Math.log(Math.max(v, 1.0)));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
            if ("logMinLength".equals(canonicalFeature)) {
                String ml = inputs.get("minLength");
                if (ml != null) {
                    try {
                        double v = Double.parseDouble(ml);
                        return String.valueOf(Math.log(Math.max(v, 1.0)));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
        }

        return null;
    }

    private String canonicalFeatureName(String workload, String rawFeature) {
        if (rawFeature == null) {
            return null;
        }

        if ("fractals".equals(workload)) {
            if ("width".equals(rawFeature)) {
                return "w";
            }
            if ("height".equals(rawFeature)) {
                return "h";
            }
        }

        if ("dna".equals(workload)) {
            if ("seq1_length".equals(rawFeature)) {
                return "seq1Length";
            }
            if ("seq2_length".equals(rawFeature)) {
                return "seq2Length";
            }
        }

        return rawFeature;
    }

    private double parseNumeric(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty feature value");
        }

        if ("true".equalsIgnoreCase(value)) {
            return 1.0;
        }
        if ("false".equalsIgnoreCase(value)) {
            return 0.0;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Non-numeric feature value: " + value, e);
        }
    }
}
