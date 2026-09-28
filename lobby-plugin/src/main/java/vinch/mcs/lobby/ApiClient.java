package vinch.mcs.lobby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ApiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PluginConfig config;
    private final JavaPlugin plugin;

    public ApiClient(PluginConfig config, JavaPlugin plugin) {
        this.config = config;
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Récupère un joueur par son UUID Minecraft
     */
    public CompletableFuture<JsonNode> getPlayerByUuid(UUID uuid) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/players/by-uuid/" + uuid))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return sendAsync(request);
    }

    /**
     * Crée un serveur
     */
    public CompletableFuture<JsonNode> createServer(Long ownerPlayerId, String name, String displayName,
                                                    String serverType, String version, int ramMb,
                                                    int cpuCores, int storageMb) {
        try {
            Map<String, Object> body = Map.of(
                    "ownerPlayerId", ownerPlayerId,
                    "name", name,
                    "displayName", displayName,
                    "serverType", serverType,
                    "minecraftVersion", version,
                    "ramMb", ramMb,
                    "cpuCores", cpuCores,
                    "storageMb", storageMb
            );

            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + "/api/v1/servers"))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", config.getApiKey())
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return sendAsync(request);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Supprime un serveur par son nom
     */
    public CompletableFuture<JsonNode> deleteServer(String name, Long requestedByPlayerId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/by-name/" + name
                        + "?deleteData=true&requestedByPlayerId=" + requestedByPlayerId))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofMinutes(1))
                .DELETE()
                .build();

        return sendAsync(request);
    }

    public CompletableFuture<JsonNode> startServer(String name, Long requestedByPlayerId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/by-name/" + name
                        + "/start?requestedByPlayerId=" + requestedByPlayerId))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return sendAsync(request);
    }

    public CompletableFuture<JsonNode> stopServer(String name, Long requestedByPlayerId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/by-name/" + name
                        + "/stop?requestedByPlayerId=" + requestedByPlayerId))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofMinutes(1))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return sendAsync(request);
    }

    public CompletableFuture<JsonNode> restartServer(String name, Long requestedByPlayerId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/by-name/" + name
                        + "/restart?requestedByPlayerId=" + requestedByPlayerId))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return sendAsync(request);
    }

    /**
     * Liste les serveurs d'un joueur
     */
    public CompletableFuture<JsonNode> getPlayerServers(Long playerId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/players/" + playerId + "/servers"))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return sendAsync(request);
    }

    private CompletableFuture<JsonNode> sendAsync(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonNode body = objectMapper.readTree(response.body());

                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            String error = body.has("error") ? body.get("error").asText() : response.body();
                            throw new RuntimeException("API erreur (" + response.statusCode() + ") : " + error);
                        }

                        return body;
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException("Erreur parsing réponse API", e);
                    }
                });
    }

    /**
     * Récupère un serveur par owner + nom
     */
    /** État et mesures d'un serveur (CPU, RAM, disque, joueurs...) */
    public CompletableFuture<JsonNode> getServerStats(long serverId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/" + serverId + "/stats"))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return sendAsync(request);
    }

    public CompletableFuture<JsonNode> getServerByOwnerAndName(Long ownerId, String name) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/by-owner-name?ownerId=" + ownerId + "&name=" + name))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return sendAsync(request);
    }

    /**
     * Récupère un serveur par pseudo + nom
     */
    public CompletableFuture<JsonNode> getServerByUsernameAndName(String username, String name) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/servers/by-username-name?username=" + username + "&name=" + name))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return sendAsync(request);
    }

    public CompletableFuture<JsonNode> updatePlayerLimits(UUID uuid, int maxServers, int totalRamMb, int totalCpuCores,
                                                        boolean admin, String rank, String prefixJson,
                                                        String nameColor) {
        try {
            Map<String, Object> body = Map.of(
                    "uuid", uuid.toString(),
                    "maxServers", maxServers,
                    "totalRamMb", totalRamMb,
                    "totalCpuCores", totalCpuCores,
                    "admin", admin,
                    "rank", rank == null ? "" : rank,
                    "prefix", prefixJson == null ? "" : prefixJson,
                    "nameColor", nameColor == null ? "" : nameColor
            );

            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + "/api/v1/players/limits"))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", config.getApiKey())
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return sendAsync(request);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ------------------------------------------------------------ accès (lot 17) ----

    private CompletableFuture<JsonNode> call(String method, String path, Object body, Duration timeout) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + path))
                    .header("X-API-Key", config.getApiKey())
                    .timeout(timeout);
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return sendAsync(b.build());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** "nom" ou "proprietaire/nom" -> serveur, avec les droits du joueur (rights, relation, ref...) */
    public CompletableFuture<JsonNode> resolveServer(long playerId, String ref) {
        return call("GET", "/api/v1/servers/resolve?playerId=" + playerId + "&ref=" + enc(ref), null,
                Duration.ofSeconds(10));
    }

    /** action = start | stop | restart | delete, au nom du joueur */
    public CompletableFuture<JsonNode> serverAction(long serverId, String action, long playerId) {
        Duration timeout = switch (action) {
            case "start" -> Duration.ofMinutes(3);
            case "restart" -> Duration.ofMinutes(5);
            default -> Duration.ofMinutes(1);
        };
        return call("POST", "/api/v1/servers/" + serverId + "/" + action + "?playerId=" + playerId, null, timeout);
    }

    public CompletableFuture<JsonNode> getMembers(long serverId, long playerId) {
        return call("GET", "/api/v1/servers/" + serverId + "/members?playerId=" + playerId, null, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> invite(long serverId, long playerId, String username, String level) {
        return call("POST", "/api/v1/servers/" + serverId + "/members",
                Map.of("playerId", playerId, "username", username, "level", level), Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> removeMember(long serverId, long playerId, String username) {
        return call("DELETE", "/api/v1/servers/" + serverId + "/members/" + enc(username) + "?playerId=" + playerId,
                null, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> setVisibility(long serverId, long playerId, boolean isPublic) {
        return call("POST", "/api/v1/servers/" + serverId + "/visibility",
                Map.of("playerId", playerId, "isPublic", isPublic), Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> console(long serverId, long playerId, String command) {
        return call("POST", "/api/v1/servers/" + serverId + "/console",
                Map.of("playerId", playerId, "command", command), Duration.ofSeconds(30));
    }

    public CompletableFuture<JsonNode> move(long serverId, long playerId, String username) {
        return call("POST", "/api/v1/servers/" + serverId + "/move",
                Map.of("playerId", playerId, "username", username), Duration.ofSeconds(15));
    }

    public CompletableFuture<JsonNode> getPlayerQuota(Long playerId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl() + "/api/v1/players/" + playerId + "/quota"))
                .header("X-API-Key", config.getApiKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return sendAsync(request);
    }



}