package pt.ulisboa.tecnico.cnv.smartbalancer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

public final class SmartLoadBalancerServer {

    private SmartLoadBalancerServer() {
    }

    public static void main(String[] args) throws Exception {
        int listenPort = EnvConfig.getInt("SMART_LB_PORT", 9000);
        int backendPort = EnvConfig.getInt("SMART_LB_BACKEND_PORT", 8000);

        TargetPool targetPool = new TargetPool(backendPort);
        ModelCatalog modelCatalog = new ModelCatalog();
        SmartBalancerHandler handler = new SmartBalancerHandler(targetPool, modelCatalog);

        HttpServer server = HttpServer.create(new InetSocketAddress(listenPort), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/metrics", handler::handleMetrics);
        server.createContext("/metrics-per-instance", handler::handleMetricsPerInstance);
        server.createContext("/retire", handler::handleRetire);
        server.createContext("/retire-status", handler::handleRetireStatus);
        server.createContext("/test", handler::handleHealth);
        server.createContext("/", handler);
        server.start();

        System.out.println("Smart load balancer listening on port " + listenPort
                + " (backendPort=" + backendPort + ")");
    }

}
