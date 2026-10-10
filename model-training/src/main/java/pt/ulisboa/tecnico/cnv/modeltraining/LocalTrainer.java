package pt.ulisboa.tecnico.cnv.modeltraining;

import pt.ulisboa.tecnico.cnv.model.DualModelJsonCodec;
import pt.ulisboa.tecnico.cnv.model.DualRegressionModel;
import pt.ulisboa.tecnico.cnv.model.ModelJsonCodec;
import pt.ulisboa.tecnico.cnv.model.RegressionModel;
import pt.ulisboa.tecnico.cnv.modeltraining.receivers.CsvMetricsReceiver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI tool for training a model locally from a CSV file and writing a JSON model file.
 *
 * <p>For workloads that support a boolean partition (currently {@code dna} on
 * {@code stopOnFirst}), pass {@code --dual true} to produce a
 * {@link DualRegressionModel}. The output file is the same; the JSON format
 * inside changes to the dual-model format handled by {@link DualModelJsonCodec}.</p>
 *
 * Usage:
 * <pre>
 *   # plain model (fractals, grayscott, or dna without partition)
 *   java -cp model-training-jar-with-dependencies.jar \
 *        pt.ulisboa.tecnico.cnv.modeltraining.LocalTrainer \
 *        --workload fractals
 *
 *   # dual model for dna (splits on stopOnFirst)
 *   java -cp model-training-jar-with-dependencies.jar \
 *        pt.ulisboa.tecnico.cnv.modeltraining.LocalTrainer \
 *        --workload dna \
 *        --dual true \
 *        [--partitionFeature stopOnFirst]
 * </pre>
 */
public final class LocalTrainer {

    private static final String DEFAULT_COMPLEXITY_COLUMN = "weighted_instructions";

    // Default partition features per workload (used when --dual true is passed
    // without an explicit --partitionFeature).
    private static final Map<String, String> DEFAULT_PARTITION_FEATURES = Map.of(
            "dna", "stopOnFirst"
    );

    private LocalTrainer() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> parsed = parseArgs(args);

        String workload         = parsed.getOrDefault("workload", "dna");
        String csvPath          = parsed.getOrDefault("csv", defaultCsvPathFor(workload));
        String outPath          = parsed.getOrDefault("out",
                "model-training/output/models/" + workload + "_model.json");
        String complexityColumn = parsed.getOrDefault("complexityColumn", DEFAULT_COMPLEXITY_COLUMN);
        boolean dual            = "true".equalsIgnoreCase(parsed.get("dual"));
        String partitionFeature = parsed.getOrDefault(
                "partitionFeature", DEFAULT_PARTITION_FEATURES.get(workload));

        CsvMetricsReceiver receiver = new CsvMetricsReceiver(Path.of(csvPath), complexityColumn);
        List<HistoricalMetric> metrics = receiver.fetchAll(workload);

        Path output = Path.of(outPath);
        Files.createDirectories(output.getParent());

        if (dual) {
            if (partitionFeature == null) {
                throw new IllegalArgumentException(
                        "No default partition feature known for workload='" + workload
                                + "'. Pass --partitionFeature <featureName>.");
            }
            trainDual(workload, partitionFeature, metrics, output);
        } else {
            trainPlain(workload, metrics, output);
        }
    }

    // -------------------------------------------------------------------------

    private static void trainDual(
            String workload,
            String partitionFeature,
            List<HistoricalMetric> metrics,
            Path output) throws IOException {

        DualRegressionModel model =
                new PartitionedRegressionTrainer().train(workload, partitionFeature, metrics);

        Files.writeString(output, DualModelJsonCodec.toJson(model), StandardCharsets.UTF_8);

        System.out.println("Dual model written to: " + output.toAbsolutePath());
        System.out.println("Workload:           " + model.getWorkload());
        System.out.println("Partition feature:  " + model.getPartitionFeature());
        printSubModel("false", model.getFalseModel());
        printSubModel("true",  model.getTrueModel());
    }

    private static void printSubModel(String label, RegressionModel sub) {
        System.out.println("  [" + label + "] Samples:  " + sub.getSampleCount());
        System.out.println("  [" + label + "] R²:       " + String.format("%.4f", sub.getRSquared()));
        System.out.println("  [" + label + "] Features: " + sub.getFeatures());
        System.out.println("  [" + label + "] logTarget: " + sub.isLogTarget());
    }

    private static void trainPlain(
            String workload,
            List<HistoricalMetric> metrics,
            Path output) throws IOException {

        RegressionModel model = new LinearRegressionTrainer().train(workload, metrics);

        Files.writeString(output, ModelJsonCodec.toJson(model), StandardCharsets.UTF_8);

        System.out.println("Model written to: " + output.toAbsolutePath());
        System.out.println("Workload:   " + model.getWorkload());
        System.out.println("Samples:    " + model.getSampleCount());
        System.out.println("R²:         " + String.format("%.4f", model.getRSquared()));
        System.out.println("Features:   " + model.getFeatures());
        System.out.println("Log-space:  " + model.isLogTarget());
    }

    private static String defaultCsvPathFor(String workload) {
        switch (workload) {
            case "dna":
                return "research/data/weighted_instructions/train/dna_profiling_results.csv";
            case "fractals":
                return "research/data/weighted_instructions/train/fractals_profiling_results.csv";
            case "grayscott":
                return "research/data/weighted_instructions/train/grayscott_profiling_results.csv";
            default:
                throw new IllegalArgumentException("Unknown workload: " + workload);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) {
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for argument: " + key);
            }
            parsed.put(key.substring(2), args[++i]);
        }
        return parsed;
    }
}
