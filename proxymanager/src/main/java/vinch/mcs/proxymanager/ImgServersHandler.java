package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Serveurs de la team depuis le site IMG Farm DB (lot 53), au nom du membre connecté au site
 * (son UUID Minecraft lié, envoyé par le site). Les droits sont ceux de MCS pour ce joueur
 * (AccessService) : le site n'a aucun pouvoir propre.
 *   POST /img/servers                     {uuid}  -> serveurs de la team, droits, joueurs connectés, quota
 *   POST /img/servers/{id}/{action}       {uuid}  -> start | stop | restart | delete
 *   POST /img/servers/create              {uuid, name, type, version, ram_mb, cpu}
 * Une action longue répond « pending » après 10 à 15 s et continue ; son erreur éventuelle est
 * rendue avec la liste suivante (lastError), pendant 10 min.
 */
class ImgServersHandler {

    private static final Set<String> ACTIONS = Set.of("start", "stop", "restart", "delete");
    private static final Set<String> TYPES = Set.of("PAPER", "SPIGOT", "FABRIC", "FORGE", "NEOFORGE");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{3,32}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9][0-9A-Za-z.\\-]{0,19}$");
    private static final long ERROR_TTL_MS = 10 * 60_000;

    private record LastError(String message, long at) {}

    private final ProxyServer proxy;
    private final ApiClient api;
    private final PlayerSync playerSync;
    private final Logger logger;
    private final ObjectMapper json = new ObjectMapper();
    /** "srv:<id>" ou "create:<uuid>" -> dernière erreur d'une action terminée après la réponse */
    private final Map<String, LastError> errors = new ConcurrentHashMap<>();

    ImgServersHandler(ProxyServer proxy, ApiClient api, PlayerSync playerSync, Logger logger) {
        this.proxy = proxy;
        this.api = api;
        this.playerSync = playerSync;
        this.logger = logger;
    }

    /** Réponse : (code HTTP, corps) — appelée une seule fois, depuis n'importe quel fil */
    void handle(String path, JsonNode body, BiConsumer<Integer, JsonNode> reply) {
        UUID uuid = UUID.fromString(body.path("uuid").asText(""));
        String rest = path.substring("/img/servers".length());
        CompletableFuture<Long> playerId = api.getPlayerByUuid(uuid)
                .thenApply(p -> p.get("id").asLong())
                .exceptionally(e -> null);
        playerId.thenAccept(pid -> {
            try {
                route(uuid, rest, pid, body, reply);
            } catch (RuntimeException e) {
                reply.accept(400, error("Requête invalide : " + e.getMessage()));
            }
        });
    }

    private void route(UUID uuid, String rest, Long pid, JsonNode body, BiConsumer<Integer, JsonNode> reply) {
        if (pid == null) {
            reply.accept(404, error("Ton compte Minecraft n'est pas encore connu du réseau : connecte-toi une fois "
                    + "en jeu (img.asearia.ca), puis reviens."));
            return;
        }
        if (rest.isEmpty() || rest.equals("/")) {
            list(uuid, pid, reply);
        } else if (rest.equals("/create")) {
            create(uuid, pid, body, reply);
        } else {
            String[] parts = rest.substring(1).split("/");
            if (parts.length != 2 || !ACTIONS.contains(parts[1])) {
                reply.accept(404, error("Route inconnue"));
                return;
            }
            action(Long.parseLong(parts[0]), parts[1], pid, reply);
        }
    }

    private ObjectNode error(String message) {
        return json.createObjectNode().put("error", message);
    }

    private String lastError(String key) {
        LastError e = errors.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.at() > ERROR_TTL_MS) {
            errors.remove(key);
            return null;
        }
        return e.message();
    }

    private void list(UUID uuid, long pid, BiConsumer<Integer, JsonNode> reply) {
        CompletableFuture<JsonNode> servers = api.teamServers(pid);
        CompletableFuture<JsonNode> quota = api.getPlayerQuota(pid).exceptionally(e -> null);
        servers.thenCombine(quota, (s, q) -> {
            ObjectNode out = json.createObjectNode();
            out.put("player_id", pid);
            if (q != null) {
                out.set("quota", q);
            }
            String createError = lastError("create:" + uuid);
            if (createError != null) {
                out.put("create_error", createError);
            }
            ArrayNode list = out.putArray("servers");
            for (JsonNode srv : s.path("servers")) {
                ObjectNode o = ((ObjectNode) srv).deepCopy();
                int online = proxy.getServer(srv.path("velocityName").asText(""))
                        .map(r -> r.getPlayersConnected().size()).orElse(0);
                o.put("online", online);
                String err = lastError("srv:" + srv.path("id").asLong());
                if (err != null) {
                    o.put("lastError", err);
                }
                list.add(o);
            }
            return out;
        }).whenComplete((out, e) -> {
            if (e != null) {
                reply.accept(502, error(McsCommand.errorText(e)));
            } else {
                reply.accept(200, out);
            }
        });
    }

    /** Attend au plus « wait » secondes ; ensuite « pending » et l'erreur éventuelle est gardée */
    private void finish(CompletableFuture<JsonNode> work, String errorKey, int wait, BiConsumer<Integer, JsonNode> reply,
                        String done) {
        errors.remove(errorKey);
        CompletableFuture<Boolean> replied = new CompletableFuture<>();
        work.whenComplete((r, e) -> {
            if (e != null) {
                String msg = McsCommand.errorText(e);
                if (replied.complete(true)) {
                    reply.accept(400, error(msg));
                } else {
                    errors.put(errorKey, new LastError(msg, System.currentTimeMillis()));
                }
                return;
            }
            if (replied.complete(true)) {
                ObjectNode ok = json.createObjectNode().put("ok", true).put("message", done);
                if (r != null && r.has("status")) {
                    ok.put("status", r.get("status").asText());
                }
                reply.accept(200, ok);
            }
        });
        CompletableFuture.delayedExecutor(wait, TimeUnit.SECONDS).execute(() -> {
            if (replied.complete(true)) {
                reply.accept(202, json.createObjectNode().put("pending", true)
                        .put("message", "C'est en cours : la liste se met à jour toute seule."));
            }
        });
    }

    private void action(long serverId, String action, long pid, BiConsumer<Integer, JsonNode> reply) {
        logger.info("Site IMG : {} du serveur {} par le joueur {}", action, serverId, pid);
        String done = switch (action) {
            case "start" -> "Serveur démarré.";
            case "stop" -> "Serveur arrêté.";
            case "restart" -> "Serveur redémarré.";
            default -> "Serveur supprimé.";
        };
        finish(api.serverAction(serverId, action, pid), "srv:" + serverId, 10, reply, done);
    }

    private void create(UUID uuid, long pid, JsonNode body, BiConsumer<Integer, JsonNode> reply) {
        String name = body.path("name").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        String type = body.path("type").asText("").trim().toUpperCase(java.util.Locale.ROOT);
        String version = body.path("version").asText("").trim();
        int ram = body.path("ram_mb").asInt(0);
        int cpu = body.path("cpu").asInt(0);
        if (!NAME.matcher(name).matches()) {
            reply.accept(400, error("Nom invalide : 3 à 32 caractères, lettres minuscules, chiffres et tirets."));
            return;
        }
        if (!TYPES.contains(type)) {
            reply.accept(400, error("Type inconnu : Paper, Spigot, Fabric, Forge ou NeoForge."));
            return;
        }
        if (!VERSION.matcher(version).matches()) {
            reply.accept(400, error("Version invalide (ex. 1.21.4)."));
            return;
        }
        if (ram < 512 || ram > 1048576 || cpu < 1 || cpu > 256) {
            reply.accept(400, error("RAM ou CPU invalide."));
            return;
        }
        logger.info("Site IMG : création du serveur {} ({} {}, {} Mo, {} CPU) par le joueur {}", name, type, version, ram, cpu, pid);
        // Quotas LuckPerms à jour dans l'API avant la création (le joueur n'est peut-être pas en jeu)
        CompletableFuture<JsonNode> work = playerSync.syncUuid(uuid)
                .thenCompose(v -> api.createServer(pid, name, type, version, ram, cpu, false, true));
        finish(work, "create:" + uuid, 15, reply, "Serveur créé.");
    }
}
