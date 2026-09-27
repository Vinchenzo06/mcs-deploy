package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ApiServer {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final PluginConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer httpServer;

    public ApiServer(ProxyServer proxyServer, Logger logger, PluginConfig config) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.config = config;
    }

    public void start() throws IOException {
        int port = config.getLocalApiPort();
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        httpServer.createContext("/servers", new ServersHandler());
        httpServer.createContext("/health", new HealthHandler());

        httpServer.setExecutor(null); // par défaut, single threaded, suffisant
        httpServer.start();

        logger.info("API locale du plugin démarrée sur 127.0.0.1:{}", port);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            logger.info("API locale du plugin arrêtée");
        }
    }

    /**
     * Handler pour /servers : GET (list), POST (add), DELETE /servers/{name} (remove)
     */
    private class ServersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                // Authentification par token
                String auth = exchange.getRequestHeaders().getFirst("X-Plugin-Key");
                if (auth == null || !auth.equals(config.getLocalApiKey())) {
                    sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }

                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if (method.equals("GET") && path.equals("/servers")) {
                    handleList(exchange);
                } else if (method.equals("POST") && path.equals("/servers")) {
                    handleAdd(exchange);
                } else if (method.equals("DELETE") && path.startsWith("/servers/")) {
                    String name = path.substring("/servers/".length());
                    handleRemove(exchange, name);
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"Not found\"}");
                }
            } catch (Exception e) {
                logger.error("Erreur API : ", e);
                sendResponse(exchange, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> servers = new ArrayList<>();
        for (RegisteredServer rs : proxyServer.getAllServers()) {
            Map<String, Object> s = new HashMap<>();
            s.put("name", rs.getServerInfo().getName());
            s.put("address", rs.getServerInfo().getAddress().toString());
            s.put("players", rs.getPlayersConnected().size());
            servers.add(s);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("servers", servers);

        String json = objectMapper.writeValueAsString(response);
        sendResponse(exchange, 200, json);
    }

    private void handleAdd(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        JsonNode json = objectMapper.readTree(body);

        if (!json.has("name") || !json.has("address")) {
            sendResponse(exchange, 400, "{\"error\":\"missing name or address\"}");
            return;
        }

        String name = json.get("name").asText();
        String address = json.get("address").asText();

        // Vérifier si déjà existant
        if (proxyServer.getServer(name).isPresent()) {
            sendResponse(exchange, 409, "{\"error\":\"Server already exists\"}");
            return;
        }

        // Parser l'adresse
        String[] parts = address.split(":");
        if (parts.length != 2) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid address format, expected host:port\"}");
            return;
        }

        try {
            InetSocketAddress addr = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
            ServerInfo info = new ServerInfo(name, addr);
            RegisteredServer registered = proxyServer.registerServer(info);

            logger.info("Serveur ajouté à Velocity : {} -> {}", name, address);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("success", true);
            response.put("name", registered.getServerInfo().getName());
            sendResponse(exchange, 200, response.toString());

        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid port\"}");
        }
    }

    private void handleRemove(HttpExchange exchange, String name) throws IOException {
        Optional<RegisteredServer> server = proxyServer.getServer(name);
        if (server.isEmpty()) {
            sendResponse(exchange, 404, "{\"error\":\"Server not found\"}");
            return;
        }

        proxyServer.unregisterServer(server.get().getServerInfo());
        logger.info("Serveur retiré de Velocity : {}", name);

        sendResponse(exchange, 200, "{\"success\":true}");
    }

    /**
     * Handler pour /health : retourne juste un OK
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, "{\"status\":\"UP\"}");
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}