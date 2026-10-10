package pt.ulisboa.tecnico.cnv.modeltraining;

import org.junit.Test;
import pt.ulisboa.tecnico.cnv.modeltraining.receivers.CsvMetricsReceiver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CsvMetricsReceiverTest {

    @Test
    public void parsesCsvMetrics() throws Exception {
        Path tempCsv = Files.createTempFile("metrics", ".csv");
        Files.writeString(tempCsv,
                "seq1_length,seq2_length,minLength,sweep_variable,execution_time_ms,weighted_instructions\n"
                        + "250,1000,10,seq1_length,205,1116376\n"
                        + "625,1000,10,seq1_length,197,1223519\n");

        CsvMetricsReceiver receiver = new CsvMetricsReceiver(tempCsv, "weighted_instructions");
        List<HistoricalMetric> metrics = receiver.fetchAll("dna");

        assertEquals(2, metrics.size());
        assertEquals("dna", metrics.get(0).getWorkload());
        assertEquals("250", metrics.get(0).getNormalizedInputs().get("seq1_length"));
        assertEquals(1116376L, metrics.get(0).getComplexity());

        Files.deleteIfExists(tempCsv);
    }
}
