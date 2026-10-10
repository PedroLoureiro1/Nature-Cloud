package pt.ulisboa.tecnico.cnv.modeltraining;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import pt.ulisboa.tecnico.cnv.model.DualRegressionModel;
import pt.ulisboa.tecnico.cnv.model.DynamoDualModelStore;
import pt.ulisboa.tecnico.cnv.model.DynamoModelStore;
import pt.ulisboa.tecnico.cnv.model.RegressionModel;
import pt.ulisboa.tecnico.cnv.modeltraining.receivers.DynamoMetricsReceiver;

/**
 * Systemd oneshot service triggered by cnv-model-training.timer every 5 minutes.
 *
 * <p>For workloads that use a boolean partition feature (currently {@code "dna"} on
 * {@code stopOnFirst}), a {@link DualRegressionModel} is trained and persisted via
 * {@link DynamoDualModelStore}. All other workloads keep using a plain
 * {@link RegressionModel} via {@link DynamoModelStore}.</p>
 */
public final class ModelTrainingService {

    private static final String[] WORKLOADS = {"dna", "fractals", "grayscott"};
    private static final int MIN_SAMPLES_TO_TRAIN = 5;
    private static final int NUMMAXROWS = 500;

    // Workloads that should be split on a boolean partition feature.
    // Key = workload name, Value = the boolean feature to partition on.
    private static final java.util.Map<String, String> DUAL_MODEL_WORKLOADS =
            java.util.Map.of("dna", "stopOnFirst");

    private static final String STATE_DIR_ENV    = "CNV_REGRESSION_STATE_DIR";
    private static final String MODEL_TABLE_ENV  = "HHS_MODEL_TABLE";
    private static final String METRICS_TABLE_ENV = "HHS_DDB_TABLE";

    private static final String DEFAULT_STATE_DIR    = "/var/lib/cnv-regression";
    private static final String DEFAULT_MODEL_TABLE  = "hhs-regression-models";
    private static final String DEFAULT_METRICS_TABLE = "hhs-workload-metrics";

    public static void main(String[] args) {
        String stateDir     = env(STATE_DIR_ENV,     DEFAULT_STATE_DIR);
        String modelTable   = env(MODEL_TABLE_ENV,   DEFAULT_MODEL_TABLE);
        String metricsTable = env(METRICS_TABLE_ENV, DEFAULT_METRICS_TABLE);

        ensureStateDir(stateDir);

        boolean anyError = false;
        for (String workload : WORKLOADS) {
            try {
                trainWorkload(workload, stateDir, modelTable, metricsTable);
            } catch (Exception e) {
                System.err.println("[ModelTraining] ERROR workload=" + workload + ": " + e.getMessage());
                e.printStackTrace(System.err);
                anyError = true;
            }
        }

        if (anyError) {
            System.exit(1);
        }
    }

    private static void trainWorkload(String workload, String stateDir, String modelTable, String metricsTable) {
        Path cursorFile = Path.of(stateDir, workload + "_last_timestamp.txt");
        long cursor = readCursor(cursorFile);

        DynamoMetricsReceiver dynamo = new DynamoMetricsReceiver(metricsTable);
        List<HistoricalMetric> allMetrics = dynamo.fetchMostRecent(workload, 0, NUMMAXROWS);

        long latestTimestamp = allMetrics.stream()
                .mapToLong(HistoricalMetric::getRequestEpochMs)
                .max()
                .orElse(0L);

        if (cursor != 0L && latestTimestamp <= cursor) {
            System.out.println("[ModelTraining] No new data for workload=" + workload
                    + ", keeping existing model");
            return;
        }

        writeCursor(cursorFile, latestTimestamp > 0 ? latestTimestamp : System.currentTimeMillis());
        if (latestTimestamp > cursor) {
            System.out.println("[ModelTraining] Cursor advanced to " + latestTimestamp
                    + " for workload=" + workload);
        }

        if (allMetrics.size() < MIN_SAMPLES_TO_TRAIN) {
            System.out.println("[ModelTraining] Skipping workload=" + workload
                    + " — only " + allMetrics.size() + " samples (need " + MIN_SAMPLES_TO_TRAIN + ")");
            return;
        }

        String partitionFeature = DUAL_MODEL_WORKLOADS.get(workload);
        if (partitionFeature != null) {
            trainDual(workload, partitionFeature, allMetrics, modelTable);
        } else {
            trainPlain(workload, allMetrics, modelTable);
        }
    }

    // -------------------------------------------------------------------------
    // training helpers
    // -------------------------------------------------------------------------

    private static void trainDual(
            String workload,
            String partitionFeature,
            List<HistoricalMetric> allMetrics,
            String modelTable) {

        DualRegressionModel model;
        try {
            model = new PartitionedRegressionTrainer().train(workload, partitionFeature, allMetrics);
        } catch (IllegalArgumentException e) {
            System.err.println("[ModelTraining] Dual training failed for workload=" + workload
                    + ": " + e.getMessage());
            return;
        }

        DynamoDualModelStore.save(model, modelTable);
        System.out.println("[ModelTraining] Saved dual model workload=" + workload
                + " partitionFeature=" + partitionFeature
                + " samplesTotal=" + (model.getFalseModel().getSampleCount()
                                      + model.getTrueModel().getSampleCount())
                + " r²(false)=" + String.format("%.4f", model.getFalseModel().getRSquared())
                + " r²(true)="  + String.format("%.4f", model.getTrueModel().getRSquared()));
    }

    private static void trainPlain(
            String workload,
            List<HistoricalMetric> allMetrics,
            String modelTable) {

        RegressionModel model;
        try {
            model = new LinearRegressionTrainer().train(workload, allMetrics);
        } catch (IllegalArgumentException e) {
            System.err.println("[ModelTraining] Training failed for workload=" + workload
                    + ": " + e.getMessage());
            return;
        }

        DynamoModelStore.save(model, modelTable);
        System.out.println("[ModelTraining] Saved model workload=" + workload
                + " samples=" + model.getSampleCount()
                + " rSquared=" + String.format("%.4f", model.getRSquared()));
    }

    // -------------------------------------------------------------------------
    // cursor / state helpers (unchanged)
    // -------------------------------------------------------------------------

    private static long readCursor(Path cursorFile) {
        if (!Files.exists(cursorFile)) {
            return 0L;
        }
        try {
            String raw = Files.readString(cursorFile, StandardCharsets.UTF_8).trim();
            return Long.parseLong(raw);
        } catch (Exception e) {
            System.err.println("[ModelTraining] Failed to read cursor from "
                    + cursorFile + ": " + e.getMessage());
            return 0L;
        }
    }

    private static void writeCursor(Path cursorFile, long epochMs) {
        try {
            Files.writeString(cursorFile, Long.toString(epochMs), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[ModelTraining] Failed to write cursor to "
                    + cursorFile + ": " + e.getMessage());
        }
    }

    private static void ensureStateDir(String stateDir) {
        try {
            Files.createDirectories(Path.of(stateDir));
        } catch (IOException e) {
            System.err.println("[ModelTraining] Warning: could not create state dir "
                    + stateDir + ": " + e.getMessage());
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}