package pt.ulisboa.tecnico.cnv.model;

import java.util.List;
import java.util.Map;

public final class RegressionFunction {

    private RegressionFunction() {
    }

    public static double predictComplexity(RegressionModel model, Map<String, String> requestInputs) {
        List<String> features = model.getFeatures();
        List<Double> coefficients = model.getCoefficients();
        List<Double> means = model.getFeatureMeans();
        List<Double> stdDevs = model.getFeatureStdDevs();

        double predicted = model.getIntercept();
        for (int i = 0; i < features.size(); i++) {
            String feature = features.get(i);
            String rawValue = readFeatureValue(model.getWorkload(), feature, requestInputs);
            double value = parseNumeric(rawValue);
            double stdDev = stdDevs.get(i);
            if (stdDev == 0.0 || Double.isNaN(stdDev) || Double.isInfinite(stdDev)) {
                stdDev = 1.0;
            }
            double normalizedValue = (value - means.get(i)) / stdDev;
            predicted += coefficients.get(i) * normalizedValue;
        }
        return model.isLogTarget() ? Math.exp(predicted) : predicted;
    }

    private static String readFeatureValue(String workload, String feature, Map<String, String> inputs) {
        String direct = inputs.get(feature);
        if (direct != null) {
            return direct;
        }

        if ("fractals".equals(workload)) {
            if ("w".equals(feature)) {
                return inputs.get("width");
            }
            if ("h".equals(feature)) {
                return inputs.get("height");
            }
            if ("width".equals(feature)) {
                return inputs.get("w");
            }
            if ("height".equals(feature)) {
                return inputs.get("h");
            }
        }

        if ("dna".equals(workload)) {
            if ("seq1Length".equals(feature) || "seq1_length".equals(feature)) {
                return Integer.toString(sequenceLength(inputs.getOrDefault("seq1", "seq1:ATGC")));
            }
            if ("seq2Length".equals(feature) || "seq2_length".equals(feature)) {
                return Integer.toString(sequenceLength(inputs.getOrDefault("seq2", "seq2:ATGC")));
            }
            if ("seq1xseq2".equals(feature)) {
                long len1 = sequenceLength(inputs.getOrDefault("seq1", "seq1:ATGC"));
                long len2 = sequenceLength(inputs.getOrDefault("seq2", "seq2:ATGC"));
                return Long.toString(len1 * len2);
            }
            if ("seq1LengthSq".equals(feature)) {
                long len1 = sequenceLength(inputs.getOrDefault("seq1", "seq1:ATGC"));
                return Double.toString((double) len1 * len1);
            }
            if ("seq2LengthSq".equals(feature)) {
                long len2 = sequenceLength(inputs.getOrDefault("seq2", "seq2:ATGC"));
                return Double.toString((double) len2 * len2);
            }
            if ("logSeq1Length".equals(feature)) {
                long len1 = sequenceLength(inputs.getOrDefault("seq1", "seq1:ATGC"));
                return Double.toString(Math.log(Math.max(len1, 1)));
            }
            if ("logSeq2Length".equals(feature)) {
                long len2 = sequenceLength(inputs.getOrDefault("seq2", "seq2:ATGC"));
                return Double.toString(Math.log(Math.max(len2, 1)));
            }
            if ("logMinLength".equals(feature)) {
                String ml = inputs.get("minLength");
                if (ml != null) {
                    try {
                        double v = Double.parseDouble(ml);
                        return Double.toString(Math.log(Math.max(v, 1.0)));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            }
        }

        return null;
    }

    private static int sequenceLength(String rawSequenceParam) {
        String raw = rawSequenceParam == null ? "" : rawSequenceParam;
        String sequence = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        return sequence.replaceAll("\\s+", "").length();
    }

    private static double parseNumeric(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            return 0.0;
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
            return 0.0;
        }
    }
}
