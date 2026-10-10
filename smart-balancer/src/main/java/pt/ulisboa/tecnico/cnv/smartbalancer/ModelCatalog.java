package pt.ulisboa.tecnico.cnv.smartbalancer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import pt.ulisboa.tecnico.cnv.model.DualModelJsonCodec;
import pt.ulisboa.tecnico.cnv.model.DualRegressionFunction;
import pt.ulisboa.tecnico.cnv.model.DualRegressionModel;
import pt.ulisboa.tecnico.cnv.model.DynamoDualModelStore;
import pt.ulisboa.tecnico.cnv.model.DynamoModelStore;
import pt.ulisboa.tecnico.cnv.model.ModelJsonCodec;
import pt.ulisboa.tecnico.cnv.model.RegressionFunction;
import pt.ulisboa.tecnico.cnv.model.RegressionModel;

final class ModelCatalog {

    private static final List<String> WORKLOADS = List.of("dna", "fractals", "grayscott");

    private final ConcurrentMap<String, RegressionModel> models = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DualRegressionModel> dualModels = new ConcurrentHashMap<>();
    private final String modelTable = EnvConfig.getString("HHS_MODEL_TABLE", "hhs-regression-models");
    private final Path localModelDir = Path.of(
            EnvConfig.getString("SMART_LB_MODEL_DIR", "/home/ec2-user/project/model-training/models"));
    private final long refreshSeconds = EnvConfig.getLong("SMART_LB_MODEL_REFRESH_SECONDS", 300L);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    ModelCatalog() {
        refreshModels();
        scheduler.scheduleAtFixedRate(this::safeRefresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    long predictComplexity(String workload, Map<String, String> queryParams) {
        if ("dna".equals(workload)) {
            DualRegressionModel dualModel = dualModels.get(workload);
            if (dualModel != null) {
                System.out.println("[SmartLB] using dual regression for workload=dna partitionFeature="
                        + dualModel.getPartitionFeature());
                double prediction = DualRegressionFunction.predictComplexity(dualModel, queryParams);
                if (!Double.isNaN(prediction) && !Double.isInfinite(prediction) && prediction >= 1.0) {
                    return Math.round(prediction);
                }
            }
        }

        RegressionModel model = models.get(workload);
        if (model == null) {
            return heuristicFallback(workload, queryParams);
        }
        double prediction = RegressionFunction.predictComplexity(model, queryParams);
        if (Double.isNaN(prediction) || Double.isInfinite(prediction) || prediction < 1.0) {
            return heuristicFallback(workload, queryParams);
        }
        return Math.round(prediction);
    }

    private long heuristicFallback(String workload, Map<String, String> queryParams) {
        try {
            System.out.println("[SmartLB] using heuristic fallback for workload=" + workload);
            if (workload == null) {
                return 1L;
            }

            switch (workload) {
                case "dna": {
                    long seq1 = lengthParam(queryParams, "seq1", "seq1Length", 1000L);
                    long seq2 = lengthParam(queryParams, "seq2", "seq2Length", 1000L);
                    boolean stopOnFirst = boolParam(queryParams, "stopOnFirst", false);
                    double k = stopOnFirst ? 0.22 : 1.30;
                    return Math.max(1L, Math.round(seq1 * seq2 * k));
                }
                case "fractals": {
                    long width = longParam(queryParams, "w", longParam(queryParams, "width", 800L));
                    long height = longParam(queryParams, "h", longParam(queryParams, "height", 800L));
                    long iterations = longParam(queryParams, "iterations", 100L);
                    return Math.max(1L, (width * height * 1725L) + (iterations * 1000L));
                }
                case "grayscott": {
                    long size = longParam(queryParams, "size", 400L);
                    long maxIter = longParam(queryParams, "maxIterations", 600L);
                    return Math.max(1L, size * size * maxIter * 264L);
                }
                default:
                    return 1L;
            }
        } catch (Exception e) {
            System.err.println("[SmartLB] Heuristic fallback failed for workload=" + workload + ": " + e.getMessage());
            return 1L;
        }
    }

    private static long longParam(Map<String, String> params, String key, long defaultValue) {
        String v = params.get(key);
        if (v == null || v.isBlank()) return defaultValue;
        return Long.parseLong(v.trim());
    }

    private static long lengthParam(Map<String, String> params, String key, String fallbackKey, long defaultValue) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            value = params.get(fallbackKey);
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            String raw = value.trim();
            String sequence = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            return Math.max(1L, sequence.replaceAll("\\s+", "").length());
        }
    }

    private static boolean boolParam(Map<String, String> params, String key, boolean defaultValue) {
        String v = params.get(key);
        if (v == null || v.isBlank()) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    private void safeRefresh() {
        try {
            refreshModels();
        } catch (Exception e) {
            System.err.println("[SmartLB] Failed to refresh models: " + e.getMessage());
        }
    }

    private void refreshModels() {
        for (String workload : WORKLOADS) {
            if ("dna".equals(workload)) {
                Optional<DualRegressionModel> dualFromDynamo = tryLoadDualFromDynamo(workload);
                if (dualFromDynamo.isPresent()) {
                    dualModels.put(workload, dualFromDynamo.get());
                    models.remove(workload);
                    continue;
                }

                DualRegressionModel dualFromFile = tryLoadDualFromFile(workload);
                if (dualFromFile != null) {
                    dualModels.put(workload, dualFromFile);
                    models.remove(workload);
                    continue;
                }
            }

            Optional<RegressionModel> fromDynamo = tryLoadFromDynamo(workload);
            if (fromDynamo.isPresent()) {
                models.put(workload, fromDynamo.get());
                dualModels.remove(workload);
                continue;
            }

            RegressionModel fromFile = tryLoadFromFile(workload);
            if (fromFile != null) {
                models.put(workload, fromFile);
                dualModels.remove(workload);
            }
        }
    }

    private Optional<DualRegressionModel> tryLoadDualFromDynamo(String workload) {
        try {
            return DynamoDualModelStore.loadLatest(workload, modelTable);
        } catch (Exception e) {
            System.err.println("[SmartLB] DynamoDB dual model load failed for workload=" + workload + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private DualRegressionModel tryLoadDualFromFile(String workload) {
        Path modelFile = localModelDir.resolve(workload + "_dual_model.json");
        if (!Files.exists(modelFile)) {
            return null;
        }
        try {
            String json = Files.readString(modelFile);
            return DualModelJsonCodec.fromJson(json);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[SmartLB] File dual model load failed for workload=" + workload + ": " + e.getMessage());
            return null;
        }
    }

    private Optional<RegressionModel> tryLoadFromDynamo(String workload) {
        try {
            return DynamoModelStore.loadLatest(workload, modelTable);
        } catch (Exception e) {
            System.err.println("[SmartLB] DynamoDB model load failed for workload=" + workload + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private RegressionModel tryLoadFromFile(String workload) {
        Path modelFile = localModelDir.resolve(workload + "_model.json");
        if (!Files.exists(modelFile)) {
            return null;
        }
        try {
            String json = Files.readString(modelFile);
            return ModelJsonCodec.fromJson(json);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[SmartLB] File model load failed for workload=" + workload + ": " + e.getMessage());
            return null;
        }
    }
}
