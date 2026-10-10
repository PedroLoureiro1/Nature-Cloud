package pt.ulisboa.tecnico.cnv.modeltraining.receivers;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import pt.ulisboa.tecnico.cnv.modeltraining.HistoricalMetric;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CsvMetricsReceiver {

    private static final Set<String> EXCLUDED_COLUMNS = new HashSet<>();

    static {
        EXCLUDED_COLUMNS.add("sweep_variable");
        EXCLUDED_COLUMNS.add("execution_time_ms");
        EXCLUDED_COLUMNS.add("seedMode");
        EXCLUDED_COLUMNS.add("workload");
    }

    private final Path csvPath;
    private final String complexityColumn;

    public CsvMetricsReceiver(Path csvPath, String complexityColumn) {
        this.csvPath = csvPath;
        this.complexityColumn = complexityColumn;
    }

    public List<HistoricalMetric> fetchAll(String workload) {
        List<HistoricalMetric> metrics = new ArrayList<>();

        try (Reader reader = Files.newBufferedReader(csvPath);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                long complexity = parseLong(record.get(complexityColumn));
                Map<String, String> inputs = new HashMap<>();

                for (String header : parser.getHeaderMap().keySet()) {
                    if (header.equals(complexityColumn) || EXCLUDED_COLUMNS.contains(header)) {
                        continue;
                    }
                    String value = record.get(header);
                    if (value != null && !value.isBlank()) {
                        inputs.put(header, value.trim());
                    }
                }

                metrics.add(new HistoricalMetric(
                        UUID.randomUUID().toString(),
                        workload,
                        Instant.now().toEpochMilli(),
                        complexity,
                        inputs));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading CSV metrics from " + csvPath, e);
        }

        return metrics;
    }

    private long parseLong(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}
