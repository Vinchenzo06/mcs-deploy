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

    // ============================================================ /mcs (lot 19) ====
    // Mêmes routes que l'ancien plugin du lobby. Les erreurs gardent le format
    // "API erreur (code) : <message de l'API>" (champ "error" de la réponse).

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
            return httpClient.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        JsonNode json;
                        try {
                            json = response.body() == null || response.body().isBlank()
                                    ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
                        } catch (Exception e) {
                            json = objectMapper.createObjectNode();
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            String error = json.has("error") ? json.get("error").asText() : "";
                            throw new RuntimeException("API erreur (" + response.statusCode() + ") : " + error);
                        }
                        return json;
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    public CompletableFuture<JsonNode> getPlayerByUuid(UUID uuid) {
        return call("GET", "/api/v1/players/by-uuid/" + uuid, null, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> createServer(long ownerPlayerId, String name, String type, String version,
                                                    int ramMb, int cpuCores) {
        return call("POST", "/api/v1/servers", java.util.Map.of(
                "ownerPlayerId", ownerPlayerId, "name", name, "displayName", name, "serverType", type,
                "minecraftVersion", version, "ramMb", ramMb, "cpuCores", cpuCores, "storageMb", 0),
                Duration.ofMinutes(5));
    }

    public CompletableFuture<JsonNode> getPlayerServers(long playerId) {
        return call("GET", "/api/v1/players/" + playerId + "/servers", null, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> getServerStats(long serverId) {
        return call("GET", "/api/v1/servers/" + serverId + "/stats", null, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> getPlayerQuota(long playerId) {
        return call("GET", "/api/v1/players/" + playerId + "/quota", null, Duration.ofSeconds(10));
    }

    /** Quotas, rôle admin et rang réseau (LuckPerms) -> API */
    public CompletableFuture<JsonNode> updatePlayerLimits(UUID uuid, int maxServers, int totalRamMb, int totalCpuCores,
                                                        boolean admin, String rank, String prefix,
                                                        String nameColor, Integer backupIntervalHours,
                                                        Integer backupKeepLast, Integer backupKeepWeekly) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("uuid", uuid.toString());
        body.put("maxServers", maxServers);
        body.put("totalRamMb", totalRamMb);
        body.put("totalCpuCores", totalCpuCores);
        body.put("admin", admin);
        body.put("rank", rank == null ? "" : rank);
        body.put("prefix", prefix == null ? "" : prefix);
        body.put("nameColor", nameColor == null ? "" : nameColor);
        body.put("backupIntervalHours", backupIntervalHours);
        body.put("backupKeepLast", backupKeepLast);
        body.put("backupKeepWeekly", backupKeepWeekly);
        return call("POST", "/api/v1/players/limits", body, Duration.ofSeconds(10));
    }

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
                java.util.Map.of("playerId", playerId, "username", username, "level", level), Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> removeMember(long serverId, long playerId, String username) {
        return call("DELETE", "/api/v1/servers/" + serverId + "/members/" + enc(username) + "?playerId=" + playerId,
                null, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> setVisibility(long serverId, long playerId, boolean isPublic) {
        return call("POST", "/api/v1/servers/" + serverId + "/visibility",
                java.util.Map.of("playerId", playerId, "isPublic", isPublic), Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> setDisplay(long serverId, long playerId, boolean enabled) {
        return call("POST", "/api/v1/servers/" + serverId + "/display",
                java.util.Map.of("playerId", playerId, "enabled", enabled), Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> console(long serverId, long playerId, String command) {
        return call("POST", "/api/v1/servers/" + serverId + "/console",
                java.util.Map.of("playerId", playerId, "command", command), Duration.ofSeconds(30));
    }

    // ------------------------------------------------------------ sauvegardes ----

    /** Sauvegarde immédiate (l'API attend la fin, jusqu'à 20 min) */
    /** Sauvegarde maintenant : manuelle, ou permanente (propriétaire et admins) */
    public CompletableFuture<JsonNode> backupNow(long serverId, long playerId, boolean permanent) {
        return call("POST", "/api/v1/servers/" + serverId + "/backup?playerId=" + playerId + "&permanent=" + permanent,
                null, Duration.ofMinutes(21));
    }

    public CompletableFuture<JsonNode> listBackups(long serverId, long playerId) {
        return call("GET", "/api/v1/servers/" + serverId + "/backups?playerId=" + playerId, null, Duration.ofSeconds(10));
    }

    /** Rendre permanente (ou non) une sauvegarde existante */
    public CompletableFuture<JsonNode> setBackupPermanent(long serverId, long backupId, long playerId, boolean permanent) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("playerId", playerId);
        body.put("permanent", permanent);
        return call("POST", "/api/v1/servers/" + serverId + "/backups/" + backupId + "/permanent", body,
                Duration.ofSeconds(10));
    }

    /** Réglage d'un type de sauvegarde pour un serveur ; reset : réglages du réseau */
    public CompletableFuture<JsonNode> setBackupRule(long serverId, long playerId, String kind, Integer max,
                                                     Integer duration, boolean reset) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("playerId", playerId);
        body.put("kind", kind);
        body.put("max", max);
        body.put("duration", duration);
        body.put("reset", reset);
        return call("POST", "/api/v1/servers/" + serverId + "/backup-settings", body, Duration.ofSeconds(10));
    }

    /** Réglages du réseau ("network") ou limites d'un rôle (admins) */
    public CompletableFuture<JsonNode> getBackupScope(long playerId, String scope) {
        return call("GET", "/api/v1/backup-settings?playerId=" + playerId + "&scope="
                + java.net.URLEncoder.encode(scope, java.nio.charset.StandardCharsets.UTF_8), null, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> setBackupScopeRule(long playerId, String scope, String kind, Integer max,
                                                          Integer duration, boolean reset) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("playerId", playerId);
        body.put("scope", scope);
        body.put("kind", kind);
        body.put("max", max);
        body.put("duration", duration);
        body.put("reset", reset);
        return call("POST", "/api/v1/backup-settings", body, Duration.ofSeconds(10));
    }

    /** Machines et leur hôte (admins seulement, vérifié par l'API) */
    public CompletableFuture<JsonNode> getHosts(long playerId) {
        return call("GET", "/api/v1/hosts?playerId=" + playerId, null, Duration.ofSeconds(10));
    }

    /** username null : plus d'hôte */
    public CompletableFuture<JsonNode> setHost(long machineId, long playerId, String username) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("playerId", playerId);
        body.put("username", username);
        return call("POST", "/api/v1/hosts/" + machineId, body, Duration.ofSeconds(10));
    }

    public CompletableFuture<JsonNode> move(long serverId, long playerId, String username) {
        return call("POST", "/api/v1/servers/" + serverId + "/move",
                java.util.Map.of("playerId", playerId, "username", username), Duration.ofSeconds(15));
    }
}