package pt.ulisboa.tecnico.cnv.modeltraining;

import org.junit.Test;
import pt.ulisboa.tecnico.cnv.model.RegressionFunction;
import pt.ulisboa.tecnico.cnv.model.RegressionModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LinearRegressionTrainerTest {

    @Test
    public void trainsExpectedCoefficients() {
        List<HistoricalMetric> metrics = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            int x2 = (i * i) % 13 + 3;
            Map<String, String> inputs = new HashMap<>();
            inputs.put("x1", Integer.toString(i));
            inputs.put("x2", Integer.toString(x2));

            long complexity = 10 + (2L * i) + (3L * x2);
            metrics.add(new HistoricalMetric("id-" + i, "dna", System.currentTimeMillis(), complexity, inputs));
        }

        RegressionModel model = new LinearRegressionTrainer().train("dna", metrics);

        assertEquals(2, model.getFeatures().size());
        assertEquals(2, model.getFeatureMeans().size());
        assertEquals(2, model.getFeatureStdDevs().size());
        assertTrue(model.getRSquared() > 0.999);

        Map<String, String> newInputs = new HashMap<>();
        newInputs.put("x1", "30");
        newInputs.put("x2", "7");

        double predicted = RegressionFunction.predictComplexity(model, newInputs);
        assertEquals(91.0, predicted, 0.0001);
    }
}
