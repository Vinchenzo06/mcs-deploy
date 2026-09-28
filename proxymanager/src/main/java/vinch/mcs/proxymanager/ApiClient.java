package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ApiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PluginConfig config;
    private final Logger logger;

    public ApiClient(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public CompletableFuture<JsonNode> getActiveServers() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/active"))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        return sendAsync(request);
    }

    public CompletableFuture<JsonNode> playerLogin(UUID uuid, String username) {
        String body;
        try {
            body = objectMapper.writeValueAsString(new PlayerLoginPayload(uuid.toString(), username));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/players/login"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return sendAsync(request);
    }

    /** Ce joueur peut-il entrer sur ce serveur ? -> {allowed, reason} */
    public CompletableFuture<JsonNode> checkAccess(String velocityName, UUID uuid) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/access/check?server="
                        + java.net.URLEncoder.encode(velocityName, java.nio.charset.StandardCharsets.UTF_8)
                        + "&uuid=" + uuid))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return sendAsync(request);
    }

    /** Prévient l'API qu'un joueur est arrivé sur un serveur (OP auto des admins) */
    public void notifyConnected(String velocityName, UUID uuid) {
        String body;
        try {
            body = objectMapper.writeValueAsString(java.util.Map.of("server", velocityName, "uuid", uuid.toString()));
        } catch (Exception e) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/access/connected"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        sendAsync(request).exceptionally(e -> null);
    }

    private CompletableFuture<JsonNode> sendAsync(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        logger.warn("API a retourné le code {} : {}", response.statusCode(), response.body());
                        throw new RuntimeException("API erreur (" + response.statusCode() + ") : " + response.body());
                    }
                    try {
                        return objectMapper.readTree(response.body());
                    } catch (Exception e) {
                        throw new RuntimeException("Erreur parsing réponse API", e);
                    }
                });
    }

    private record PlayerLoginPayload(String uuid, String username) {}
}