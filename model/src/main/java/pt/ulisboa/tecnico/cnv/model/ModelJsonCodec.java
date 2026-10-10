package pt.ulisboa.tecnico.cnv.model;

import com.google.gson.Gson;

import java.util.List;

public final class ModelJsonCodec {

    private static final Gson GSON = new Gson();

    private ModelJsonCodec() {
    }

    public static String toJson(RegressionModel model) {
        ModelDto dto = new ModelDto();
        dto.workload = model.getWorkload();
        dto.features = model.getFeatures();
        dto.coefficients = model.getCoefficients();
        dto.featureMeans = model.getFeatureMeans();
        dto.featureStdDevs = model.getFeatureStdDevs();
        dto.intercept = model.getIntercept();
        dto.rSquared = model.getRSquared();
        dto.sampleCount = model.getSampleCount();
        dto.trainedAtEpochMs = model.getTrainedAtEpochMs();
        dto.logTarget = model.isLogTarget();
        return GSON.toJson(dto);
    }

    public static RegressionModel fromJson(String json) {
        ModelDto dto = GSON.fromJson(json, ModelDto.class);
        if (dto == null || dto.workload == null || dto.features == null) {
            throw new IllegalArgumentException("Invalid model JSON");
        }
        return new RegressionModel(
                dto.workload,
                dto.features,
                dto.coefficients,
                dto.featureMeans,
                dto.featureStdDevs,
                dto.intercept,
                dto.rSquared,
                dto.sampleCount,
                dto.trainedAtEpochMs,
                dto.logTarget);
    }

    private static final class ModelDto {
        String workload;
        List<String> features;
        List<Double> coefficients;
        List<Double> featureMeans;
        List<Double> featureStdDevs;
        double intercept;
        double rSquared;
        int sampleCount;
        long trainedAtEpochMs;
        boolean logTarget;
    }
}
