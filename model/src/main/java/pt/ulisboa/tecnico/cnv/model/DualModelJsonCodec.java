package pt.ulisboa.tecnico.cnv.model;

import com.google.gson.Gson;

/**
 * JSON serialization for {@link DualRegressionModel}.
 *
 * <p>The wire format is a thin wrapper that embeds both sub-models as nested
 * JSON objects (re-using the same DTO that {@link ModelJsonCodec} already
 * handles internally).</p>
 *
 * <pre>
 * {
 *   "workload": "dna",
 *   "partitionFeature": "stopOnFirst",
 *   "falseModel": { ...ModelDto... },
 *   "trueModel":  { ...ModelDto... }
 * }
 * </pre>
 */
public final class DualModelJsonCodec {

    private static final Gson GSON = new Gson();

    private DualModelJsonCodec() {
    }

    public static String toJson(DualRegressionModel model) {
        DualModelDto dto = new DualModelDto();
        dto.workload         = model.getWorkload();
        dto.partitionFeature = model.getPartitionFeature();
        dto.falseModel       = GSON.fromJson(ModelJsonCodec.toJson(model.getFalseModel()), Object.class);
        dto.trueModel        = GSON.fromJson(ModelJsonCodec.toJson(model.getTrueModel()),  Object.class);
        return GSON.toJson(dto);
    }

    public static DualRegressionModel fromJson(String json) {
        DualModelDto dto = GSON.fromJson(json, DualModelDto.class);
        if (dto == null || dto.workload == null || dto.partitionFeature == null) {
            throw new IllegalArgumentException("Invalid dual model JSON");
        }
        RegressionModel falseModel = ModelJsonCodec.fromJson(GSON.toJson(dto.falseModel));
        RegressionModel trueModel  = ModelJsonCodec.fromJson(GSON.toJson(dto.trueModel));
        return new DualRegressionModel(dto.workload, dto.partitionFeature, falseModel, trueModel);
    }

    // -------------------------------------------------------------------------
    // DTO
    // -------------------------------------------------------------------------

    private static final class DualModelDto {
        String workload;
        String partitionFeature;
        Object falseModel;   // deserialized as generic Map; re-serialized for ModelJsonCodec
        Object trueModel;
    }
}
