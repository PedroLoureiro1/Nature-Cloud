package pt.ulisboa.tecnico.cnv.smartbalancer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

final class SmartBalancerHandler implements HttpHandler {

    private static final Gson GSON = new Gson();
    private static final Map<String, String> LAMBDA_FUNCTIONS = Map.of(
            "fractals",  EnvConfig.getString("SMART_LB_LAMBDA_FRACTALS",  "CNV-FractalsHandler"),
            "dna",       EnvConfig.getString("SMART_LB_LAMBDA_DNA",       "CNV-DnaHandler"),
            "grayscott", EnvConfig.getString("SMART_LB_LAMBDA_GRAYSCOTT", "CNV-GrayScottHandler")
    );
    private static final Map<String, String> LAMBDA_CONTENT_TYPES = Map.of(
            "fractals",  "text/plain",
            "dna",       "text/html",
            "grayscott", "text/plain"
    );

    private final TargetPool targetPool;
    private final ModelCatalog modelCatalog;
    private static final long BACKEND_CONNECT_TIMEOUT_SECONDS = EnvConfig.getLong("BACKEND_CONNECT_TIMEOUT_SECONDS", 10);
    private static final long BACKEND_TIMEOUT_SECONDS         = EnvConfig.getLong("BACKEND_TIMEOUT_SECONDS", 600);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(BACKEND_CONNECT_TIMEOUT_SECONDS))
            .build();
    private final LambdaClient lambdaClient = LambdaClient.builder()
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                    .apiCallAttemptTimeout(Duration.ofMinutes(6))
                    .build())
            .build();
    private final long threshold = EnvConfig.getLong("SMART_LB_WORKER_COMPLEXITY_THRESHOLD", 1_900_000_000_000L);

    SmartBalancerHandler(TargetPool targetPool, ModelCatalog modelCatalog) {
        this.targetPool = targetPool;
        this.modelCatalog = modelCatalog;
    }

    void handleMetrics(HttpExchange exchange) throws IOException {
        long totalComplexity = targetPool.getTotalInflightComplexity();
        int workerCount = targetPool.getTargetCount();
        long queueComplexity = targetPool.getTotalQueuedComplexity();
        String body = "{\"totalInflightComplexity\":" + totalComplexity
                + ",\"workerCount\":" + workerCount
                + ",\"queueComplexity\":" + queueComplexity + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    void handleMetricsPerInstance(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TargetPool.BackendTarget t : targetPool.getAllTargets()) {
            if (!first) sb.append(",");
            sb.append("{\"id\":\"").append(t.getId()).append("\"")
              .append(",\"ip\":\"").append(t.getHost()).append("\"")
              .append(",\"inflightRequests\":").append(t.getInflightRequests())
              .append(",\"inflightComplexity\":").append(t.getInflightComplexity())
              .append(",\"retiring\":").append(t.isRetiring())
              .append("}");
            first = false;
        }
        sb.append("]");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    void handleRetire(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String ip = params.get("ip");
        if (ip == null || ip.isBlank()) {
            byte[] body = "missing ip param".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        boolean found = targetPool.retireByIp(ip);
        int status = found ? 200 : 404;
        String msg = found ? "retiring " + ip : "unknown ip " + ip;
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    void handleRetireStatus(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String ip = params.get("ip");
        if (ip == null || ip.isBlank()) {
            byte[] body = "missing ip param".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        int inflight = targetPool.getInflightRequestsForIp(ip);
        if (inflight == -1) {
            byte[] body = ("unknown ip " + ip).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        String body = "{\"ip\":\"" + ip + "\",\"inflightRequests\":" + inflight + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    void handleHealth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange.getResponseHeaders());
        if (!targetPool.hasTargets()) {
            byte[] body = "no backend targets available".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange.getResponseHeaders());
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        URI requestUri = exchange.getRequestURI();
        String workload = readWorkload(requestUri.getPath());
        if (workload == null) {
            byte[] body = "Unsupported path".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }

        Map<String, String> queryParams = parseQuery(requestUri.getRawQuery());
        long predictedComplexity = modelCatalog.predictComplexity(workload, queryParams);
        byte[] requestBody = readRequestBody(exchange);
        String method = exchange.getRequestMethod();

        System.out.println("[SmartLB] workload=" + workload + " predictedComplexity=" + predictedComplexity);

        int maxAttempts = 5;

        dispatch:
        while (true) {
            Set<String> triedIds = new HashSet<>();
            boolean capacityBlocked = false;
            boolean noTargets = false;

            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                Optional<TargetPool.BackendTarget> maybeTarget = targetPool.chooseTarget(triedIds);
                if (maybeTarget.isEmpty()) {
                    noTargets = true;
                    break;
                }

                TargetPool.BackendTarget target = maybeTarget.get();
                triedIds.add(target.getId());

                if (target.getInflightComplexity() > 0 && target.getInflightComplexity() + predictedComplexity > threshold) {
                    if (predictedComplexity > threshold / 2) {
                        System.out.println("[SmartLB] complexity=" + predictedComplexity + " exceeds Lambda cap=" + (threshold / 2) + ", all workers at capacity");
                        capacityBlocked = true;
                        continue;
                    }
                    System.out.println("[SmartLB] complexity threshold exceeded, offloading to Lambda");
                    BackendResponse lambdaResult = null;
                    for (int lambdaAttempt = 0; lambdaAttempt < 2; lambdaAttempt++) {
                        lambdaResult = fetchFromLambda(workload, queryParams);
                        if (lambdaResult != null) break;
                        System.err.println("[SmartLB] Lambda attempt " + (lambdaAttempt + 1) + "/2 failed for workload=" + workload);
                    }
                    if (lambdaResult != null) {
                        sendEntry(exchange, lambdaResult);
                        return;
                    }
                    System.err.println("[SmartLB] Lambda failed after 2 attempts, falling back to EC2 queue");
                    capacityBlocked = true;
                    continue;
                }

                System.out.println("[SmartLB] forwarding to backend " + target
                        + " (attempt=" + (attempt + 1) + ", inflight=" + target.getInflightComplexity()
                        + ", predicted=" + predictedComplexity + ")");

                targetPool.reserveLoad(target, predictedComplexity);
                BackendResponse result = null;
                try {
                    result = fetchFromBackend(target, requestUri, requestBody, method, exchange.getRequestHeaders(), predictedComplexity);
                } finally {
                    targetPool.releaseLoad(target, predictedComplexity);
                }

                if (result == null || result.status >= 500) {
                    System.err.println("[SmartLB] backend " + target + " returned "
                            + (result == null ? "transport error" : "HTTP " + result.status) + ", retrying...");
                    continue;
                }

                sendEntry(exchange, result);
                return;
            }

            if (capacityBlocked || noTargets) {
                if (noTargets && !capacityBlocked && predictedComplexity <= threshold / 2) {
                    System.out.println("[SmartLB] no workers reachable, trying Lambda fallback (predicted=" + predictedComplexity + ")");
                    BackendResponse lambdaResult = null;
                    for (int lambdaAttempt = 0; lambdaAttempt < 2; lambdaAttempt++) {
                        lambdaResult = fetchFromLambda(workload, queryParams);
                        if (lambdaResult != null) break;
                        System.err.println("[SmartLB] Lambda attempt " + (lambdaAttempt + 1) + "/2 failed for workload=" + workload);
                    }
                    if (lambdaResult != null) {
                        sendEntry(exchange, lambdaResult);
                        return;
                    }
                    System.err.println("[SmartLB] Lambda fallback failed, queuing request");
                }
                try {
                    System.out.println("[SmartLB] queuing request (noTargets=" + noTargets
                            + ", capacityBlocked=" + capacityBlocked + "), waiting for worker capacity");
                    targetPool.enqueueWaiter(predictedComplexity).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    break;
                }
                continue dispatch;
            }
            break;
        }

        byte[] body = "No backend target available".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Retry-After", "5");
        exchange.sendResponseHeaders(503, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** Fetches the response from a backend. Returns null on transport failure (safe to retry). */
    private BackendResponse fetchFromBackend(TargetPool.BackendTarget target, URI requestUri,
            byte[] requestBody, String method, Headers requestHeaders, long predictedComplexity) {
        try {
            String targetUrl = target.targetUrl(requestUri.getPath(), requestUri.getRawQuery());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(BACKEND_TIMEOUT_SECONDS));

            if (requestBody.length == 0) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody));
            }

            copyRequestHeaders(requestHeaders, builder);
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

            String contentType = response.headers().firstValue("content-type").orElse(null);
            return new BackendResponse(response.statusCode(), contentType, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[SmartLB] transport error to " + target + ": " + e.getMessage());
            return null;
        }
    }

    private static void sendEntry(HttpExchange exchange, BackendResponse response) throws IOException {
        if (response.contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType);
        }
        exchange.sendResponseHeaders(response.status, response.body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.body);
        }
    }

    private static String readWorkload(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return null;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String workload = normalized.contains("/") ? normalized.substring(0, normalized.indexOf('/')) : normalized;
        if ("dna".equals(workload) || "fractals".equals(workload) || "grayscott".equals(workload) || "test".equals(workload)) {
            return workload;
        }
        return null;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new HashMap<>();
        }
        Map<String, String> parsed = new HashMap<>();
        for (String part : rawQuery.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] entry = part.split("=", 2);
            String key = decode(entry[0]);
            String value = entry.length > 1 ? decode(entry[1]) : "";
            parsed.put(key, value);
        }
        return parsed;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static byte[] readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return is.readAllBytes();
        }
    }

    private static void copyRequestHeaders(Headers input, HttpRequest.Builder output) {
        for (Map.Entry<String, List<String>> header : input.entrySet()) {
            String name = header.getKey();
            if (isSkippedRequestHeader(name)) {
                continue;
            }
            for (String value : header.getValue()) {
                output.header(name, value);
            }
        }
    }

    private static boolean isSkippedRequestHeader(String name) {
        return "host".equalsIgnoreCase(name)
                || "content-length".equalsIgnoreCase(name)
                || "connection".equalsIgnoreCase(name)
                || "proxy-connection".equalsIgnoreCase(name)
                || "keep-alive".equalsIgnoreCase(name)
                || "transfer-encoding".equalsIgnoreCase(name)
                || "te".equalsIgnoreCase(name)
                || "trailer".equalsIgnoreCase(name)
                || "upgrade".equalsIgnoreCase(name)
                || "proxy-authenticate".equalsIgnoreCase(name)
                || "proxy-authorization".equalsIgnoreCase(name);
    }

    private BackendResponse fetchFromLambda(String workload, Map<String, String> queryParams) {
        String functionName = LAMBDA_FUNCTIONS.get(workload);
        if (functionName == null) {
            System.err.println("[SmartLB] No Lambda configured for workload: " + workload);
            return null;
        }
        try {
            InvokeRequest request = InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromUtf8String(GSON.toJson(queryParams)))
                    .build();

            InvokeResponse response = lambdaClient.invoke(request);

            if (response.functionError() != null) {
                System.err.println("[SmartLB] Lambda function error (" + workload + "): " + response.functionError());
                return null;
            }

            String content = GSON.fromJson(response.payload().asUtf8String(), String.class);
            if (content == null) {
                System.err.println("[SmartLB] Lambda returned null payload for: " + workload);
                return null;
            }

            return new BackendResponse(200, LAMBDA_CONTENT_TYPES.get(workload), content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[SmartLB] Lambda invocation failed (" + workload + "): " + e.getMessage());
            return null;
        }
    }

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
    }
}
