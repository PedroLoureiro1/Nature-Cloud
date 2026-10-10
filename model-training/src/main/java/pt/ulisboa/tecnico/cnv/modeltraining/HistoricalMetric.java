package pt.ulisboa.tecnico.cnv.modeltraining;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class HistoricalMetric {

    private final String requestId;
    private final String workload;
    private final long requestEpochMs;
    private final long complexity;
    private final Map<String, String> normalizedInputs;

    public HistoricalMetric(
            String requestId,
            String workload,
            long requestEpochMs,
            long complexity,
            Map<String, String> normalizedInputs) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.workload = Objects.requireNonNull(workload, "workload");
        this.requestEpochMs = requestEpochMs;
        this.complexity = complexity;
        this.normalizedInputs = new HashMap<>(Objects.requireNonNull(normalizedInputs, "normalizedInputs"));
    }

    public String getRequestId() {
        return requestId;
    }

    public String getWorkload() {
        return workload;
    }

    public long getRequestEpochMs() {
        return requestEpochMs;
    }

    public long getComplexity() {
        return complexity;
    }

    public Map<String, String> getNormalizedInputs() {
        return Collections.unmodifiableMap(normalizedInputs);
    }
}
