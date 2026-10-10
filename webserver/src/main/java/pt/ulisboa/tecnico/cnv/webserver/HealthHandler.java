package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class HealthHandler implements HttpHandler {

    private static final byte[] OK_BODY = "ok".getBytes(StandardCharsets.UTF_8);

    @Override
    public void handle(HttpExchange he) throws IOException {
        he.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

        if ("OPTIONS".equalsIgnoreCase(he.getRequestMethod())) {
            he.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            he.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            he.sendResponseHeaders(204, -1);
            return;
        }

        he.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        he.sendResponseHeaders(200, OK_BODY.length);
        try (OutputStream os = he.getResponseBody()) {
            os.write(OK_BODY);
        }
    }
}
