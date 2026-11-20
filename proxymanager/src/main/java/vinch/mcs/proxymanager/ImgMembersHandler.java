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
 * Réglages MCS des membres, depuis le site IMG Farm DB (lots 50-51), sur l'API locale du proxy
 * (127.0.0.1:8082, jeton farmdb-token en « Authorization: Bearer ») :
 *   POST /img/members         {"uuids": [...]}  -> réglages de chaque joueur
 *   POST /img/members/{uuid}  {"max_servers", "total_ram", "total_cpu": n|null, "admin": b}
 *                             -> quotas propres au joueur (null = ceux de son rôle), admin
 *   POST /img/role            {} pour lire, ou {"max_servers", "total_ram", "total_cpu": n|null}
 *                             -> quotas du rôle IMG (groupe LuckPerms « img »)
 * Tout est dans LuckPerms (méta max-servers, total-ram en Mo, total-cpu ; groupe « admin »),
 * la même source que /mcs et le lobby ; l'API MCS est resynchronisée pour les joueurs connectés.
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
    private final ImgServersHandler servers;

    public ImgMembersHandler(ProxyServer proxy, ApiClient api, PlayerSync playerSync, Logger logger, String token) {
        this.proxy = proxy;
        this.api = api;
        this.playerSync = playerSync;
        this.logger = logger;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.servers = new ImgServersHandler(proxy, api, playerSync, logger);
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
            String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
            // Lot 54 : morceau d'un téléversement (corps brut, paramètres dans l'adresse)
            if (UPLOAD.matcher(path).matches()) {
                byte[] data = exchange.getRequestBody().readNBytes(ImgServersHandler.MAX_CHUNK + 1);
                if (data.length > ImgServersHandler.MAX_CHUNK) {
                    send(exchange, 413, error("Morceau trop gros (8 Mo au plus)"));
                    return;
                }
                servers.upload(path, query(exchange.getRequestURI().getRawQuery()), data,
                        (code, out) -> sendQuietly(exchange, code, out));
                return;
            }
            byte[] raw = exchange.getRequestBody().readNBytes(64 * 1024);
            JsonNode body = raw.length == 0 ? json.createObjectNode() : json.readTree(raw);
            // Lots 53-54 : serveurs de la team (page Serveur du site), console et fichiers
            if (path.equals("/img/servers") || path.startsWith("/img/servers/") || path.startsWith("/img/backups/")) {
                servers.handle(path, body, (code, out) -> sendQuietly(exchange, code, out),
                        content -> sendBytesQuietly(exchange, content));
                return;
            }
            RankSource lp = playerSync.source();
            if (lp == null) {
                send(exchange, 503, error("LuckPerms absent du proxy : réglages indisponibles"));
                return;
            }
            if (path.equals("/img/role")) {
                role(exchange, lp, body);
            } else if (path.equals("/img/members")) {
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
            o.put("total_ram", s.ramMb());
            o.put("total_cpu", s.cpu());
            ObjectNode own = o.putObject("own");
            putNullable(own, "max_servers", s.own().maxServers());
            putNullable(own, "total_ram", s.own().ramMb());
            putNullable(own, "total_cpu", s.own().cpu());
            o.put("admin", s.admin());
            o.put("admin_group", s.adminGroup());
            o.put("img", s.imgGroup());
            putNullable(o, "servers", n);
            return o;
        });
    }

    private static void putNullable(ObjectNode o, String key, Integer value) {
        if (value == null) {
            o.putNull(key);
        } else {
            o.put(key, value);
        }
    }

    /** max_servers 0-100, total_ram 512 Mo à 1 To, total_cpu 0-256 ; null = retiré */
    private static java.util.Map<String, Integer> quotaChanges(JsonNode body) {
        java.util.Map<String, Integer> set = new java.util.HashMap<>();
        Object[][] fields = {
                {"max_servers", RankSource.MAX_SERVERS, 0, 100},
                {"total_ram", RankSource.TOTAL_RAM, 512, 1048576},
                {"total_cpu", RankSource.TOTAL_CPU, 0, 256},
        };
        for (Object[] f : fields) {
            String name = (String) f[0];
            if (!body.has(name)) {
                continue;
            }
            JsonNode v = body.get(name);
            if (v.isNull()) {
                set.put((String) f[1], null);
                continue;
            }
            int min = (int) f[2];
            int max = (int) f[3];
            if (!v.canConvertToInt() || v.asInt() < min || v.asInt() > max) {
                throw new IllegalArgumentException(name + " : entier de " + min + " à " + max + ", ou null");
            }
            set.put((String) f[1], v.asInt());
        }
        return set;
    }

    private ObjectNode quotasJson(RankSource.Quotas q) {
        ObjectNode o = json.createObjectNode();
        putNullable(o, "max_servers", q.maxServers());
        putNullable(o, "total_ram", q.ramMb());
        putNullable(o, "total_cpu", q.cpu());
        return o;
    }

    /** Quotas du rôle IMG : lecture, ou changement puis resynchronisation des joueurs connectés */
    private void role(HttpExchange exchange, RankSource lp, JsonNode body) {
        java.util.Map<String, Integer> set = quotaChanges(body);
        CompletableFuture<Void> done = lp.ensureGroup(ImgRole.GROUP, ImgRole.WEIGHT);
        if (!set.isEmpty()) {
            done = done.thenCompose(v -> lp.setGroupQuotas(ImgRole.GROUP, set))
                    .thenRun(() -> {
                        logger.info("Site IMG : quotas du rôle IMG changés ({})", body);
                        proxy.getAllPlayers().forEach(playerSync::sync);
                    });
        }
        done.thenCompose(v -> lp.groupQuotas(ImgRole.GROUP))
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((q, e) -> {
                    if (e != null) {
                        logger.warn("/img/role : {}", e.getMessage());
                        sendQuietly(exchange, 500, error("Rôle IMG indisponible : " + e.getMessage()));
                        return;
                    }
                    ObjectNode out = json.createObjectNode();
                    out.put("group", ImgRole.GROUP);
                    out.set("quotas", quotasJson(q));
                    sendQuietly(exchange, 200, out);
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
        java.util.Map<String, Integer> set = quotaChanges(body);
        if (!set.isEmpty()) {
            done = done.thenCompose(x -> lp.setUserQuotas(uuid, set));
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

    private static final java.util.regex.Pattern UPLOAD = java.util.regex.Pattern.compile("^/img/servers/\\d+/files/upload$");

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new java.util.HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = java.net.URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private void sendBytesQuietly(HttpExchange exchange, ApiClient.FileContent content) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("X-File-Size", Long.toString(content.size()));
            exchange.getResponseHeaders().set("X-File-Mtime", Long.toString(content.mtime()));
            exchange.sendResponseHeaders(200, content.data().length == 0 ? -1 : content.data().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content.data());
            }
        } catch (IOException e) {
            logger.warn("/img/servers : fichier non envoyé ({})", e.getMessage());
        }
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
