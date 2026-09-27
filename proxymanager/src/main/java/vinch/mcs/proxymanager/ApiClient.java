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