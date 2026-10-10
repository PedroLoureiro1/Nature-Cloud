package pt.ulisboa.tecnico.cnv.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ModelJsonCodecTest {

    @Test
    public void roundTrip() {
        RegressionModel original = new RegressionModel(
                "dna",
                List.of("seq1Length", "seq2Length"),
                List.of(1.5, -0.3),
                List.of(500.0, 800.0),
                List.of(120.0, 200.0),
                42_000_000.0,
                0.97,
                350,
                1_700_000_000_000L);

        String json = ModelJsonCodec.toJson(original);
        RegressionModel restored = ModelJsonCodec.fromJson(json);

        assertEquals(original.getWorkload(), restored.getWorkload());
        assertEquals(original.getFeatures(), restored.getFeatures());
        assertEquals(original.getCoefficients(), restored.getCoefficients());
        assertEquals(original.getFeatureMeans(), restored.getFeatureMeans());
        assertEquals(original.getFeatureStdDevs(), restored.getFeatureStdDevs());
        assertEquals(original.getIntercept(), restored.getIntercept(), 0.0);
        assertEquals(original.getRSquared(), restored.getRSquared(), 0.0);
        assertEquals(original.getSampleCount(), restored.getSampleCount());
        assertEquals(original.getTrainedAtEpochMs(), restored.getTrainedAtEpochMs());
    }

    @Test
    public void predictionConsistentAfterRoundTrip() {
        RegressionModel original = new RegressionModel(
                "fractals",
                List.of("h", "w"),
                List.of(2.0, 3.0),
                List.of(512.0, 512.0),
                List.of(100.0, 100.0),
                1_000_000.0,
                0.95,
                100,
                1_700_000_000_000L);

        RegressionModel restored = ModelJsonCodec.fromJson(ModelJsonCodec.toJson(original));

        java.util.Map<String, String> inputs = new java.util.HashMap<>();
        inputs.put("h", "612");
        inputs.put("w", "712");

        double p1 = RegressionFunction.predictComplexity(original, inputs);
        double p2 = RegressionFunction.predictComplexity(restored, inputs);
        assertEquals(p1, p2, 0.0001);
    }
}
