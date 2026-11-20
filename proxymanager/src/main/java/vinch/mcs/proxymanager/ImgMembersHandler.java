package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Réglages MCS des membres, depuis le site IMG Farm DB (lot 50), sur l'API locale du proxy
 * (127.0.0.1:8082, jeton farmdb-token en « Authorization: Bearer ») :
 *   POST /img/members         {"uuids": [...]}                    -> réglages de chaque joueur
 *   POST /img/members/{uuid}  {"max_servers": n|null, "admin": b}  -> change puis renvoie les réglages
 * max_servers et admin sont des données LuckPerms (méta « max-servers » du joueur, groupe « admin »),
 * la même source que /mcs et le lobby ; l'API MCS est resynchronisée si le joueur est connecté.
 * Le site ne reçoit jamais la clé de l'API MCS.
 */
public class ImgMembersHandler implements HttpHandler {

    private static final int MAX_UUIDS = 200;

    private final ProxyServer proxy;
    private final ApiClient api;
    private final PlayerSync playerSync;
    private final Logger logger;
    private final byte[] token;
    private final ObjectMapper json = new ObjectMapper();

    public ImgMembersHandler(ProxyServer proxy, ApiClient api, PlayerSync playerSync, Logger logger, String token) {
        this.proxy = proxy;
        this.api = api;
        this.playerSync = playerSync;
        this.logger = logger;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ") || token.length < 32) {
            return false;
        }
        return MessageDigest.isEqual(header.substring(7).trim().getBytes(StandardCharsets.UTF_8), token);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                send(exchange, 401, error("Jeton invalide"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("POST seulement"));
                return;
            }
            RankSource lp = playerSync.source();
            if (lp == null) {
                send(exchange, 503, error("LuckPerms absent du proxy : réglages indisponibles"));
                return;
            }
            byte[] raw = exchange.getRequestBody().readNBytes(64 * 1024);
            JsonNode body = raw.length == 0 ? json.createObjectNode() : json.readTree(raw);
            String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
            if (path.equals("/img/members")) {
                list(exchange, lp, body);
            } else if (path.startsWith("/img/members/")) {
                update(exchange, lp, UUID.fromString(path.substring("/img/members/".length())), body);
            } else {
                send(exchange, 404, error("Route inconnue"));
            }
        } catch (IllegalArgumentException e) {
            send(exchange, 400, error("Requête invalide : " + e.getMessage()));
        } catch (Exception e) {
            logger.error("API /img/members : ", e);
            send(exchange, 500, error("Erreur du proxy"));
        }
    }

    /** Réglages + nombre de serveurs (API MCS ; null si le joueur n'y est pas encore) */
    private CompletableFuture<ObjectNode> describe(RankSource lp, UUID uuid) {
        CompletableFuture<Integer> count = api.getPlayerByUuid(uuid)
                .thenCompose(p -> api.getPlayerServers(p.get("id").asLong()))
                .thenApply(s -> s.path("servers").size())
                .exceptionally(e -> null);
        return lp.settings(uuid).thenCombine(count, (s, n) -> {
            ObjectNode o = json.createObjectNode();
            o.put("max_servers", s.maxServers());
            if (s.ownMaxServers() == null) {
                o.putNull("own_max_servers");
            } else {
                o.put("own_max_servers", s.ownMaxServers());
            }
            o.put("admin", s.admin());
            o.put("admin_group", s.adminGroup());
            if (n == null) {
                o.putNull("servers");
            } else {
                o.put("servers", n);
            }
            return o;
        });
    }

    private void list(HttpExchange exchange, RankSource lp, JsonNode body) throws Exception {
        JsonNode uuids = body.path("uuids");
        if (!uuids.isArray() || uuids.size() > MAX_UUIDS) {
            throw new IllegalArgumentException("uuids : liste de " + MAX_UUIDS + " au plus");
        }
        Map<String, CompletableFuture<ObjectNode>> all = new LinkedHashMap<>();
        for (JsonNode u : uuids) {
            UUID uuid = UUID.fromString(u.asText());
            all.put(uuid.toString(), describe(lp, uuid));
        }
        CompletableFuture.allOf(all.values().toArray(new CompletableFuture[0]))
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((v, e) -> {
                    ObjectNode out = json.createObjectNode();
                    ObjectNode members = out.putObject("members");
                    List<String> failed = new ArrayList<>();
                    all.forEach((uuid, f) -> {
                        if (f.isDone() && !f.isCompletedExceptionally()) {
                            members.set(uuid, f.join());
                        } else {
                            failed.add(uuid);
                        }
                    });
                    if (!failed.isEmpty()) {
                        logger.warn("/img/members : réglages illisibles pour {} joueur(s)", failed.size());
                    }
                    sendQuietly(exchange, 200, out);
                });
    }

    private void update(HttpExchange exchange, RankSource lp, UUID uuid, JsonNode body) {
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        if (body.has("max_servers")) {
            JsonNode v = body.get("max_servers");
            Integer max = null;
            if (!v.isNull()) {
                if (!v.canConvertToInt() || v.asInt() < 0 || v.asInt() > 100) {
                    throw new IllegalArgumentException("max_servers : entier de 0 à 100, ou null");
                }
                max = v.asInt();
            }
            Integer m = max;
            done = done.thenCompose(x -> lp.setMaxServers(uuid, m));
        }
        if (body.has("admin")) {
            boolean admin = body.get("admin").asBoolean(false);
            done = done.thenCompose(x -> lp.setGroup(uuid, "admin", admin));
        }
        done.thenCompose(x -> {
                    // Joueur connecté : l'API MCS reçoit tout de suite les nouvelles limites
                    return proxy.getPlayer(uuid).map(playerSync::sync)
                            .orElse(CompletableFuture.completedFuture(null));
                })
                .thenCompose(x -> describe(lp, uuid))
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((o, e) -> {
                    if (e != null) {
                        logger.warn("/img/members/{} : {}", uuid, e.getMessage());
                        sendQuietly(exchange, 500, error("Changement impossible : " + e.getMessage()));
                        return;
                    }
                    logger.info("Site IMG : réglages MCS de {} changés ({})", uuid, body);
                    sendQuietly(exchange, 200, o);
                });
    }

    private ObjectNode error(String message) {
        return json.createObjectNode().put("error", message);
    }

    private void sendQuietly(HttpExchange exchange, int code, JsonNode body) {
        try {
            send(exchange, code, body);
        } catch (IOException e) {
            logger.warn("/img/members : réponse non envoyée ({})", e.getMessage());
        }
    }

    private void send(HttpExchange exchange, int code, JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
