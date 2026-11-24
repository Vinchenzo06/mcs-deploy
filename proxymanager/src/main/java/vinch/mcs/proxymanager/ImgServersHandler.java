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
 *   POST /img/servers/{id}/{action}       {uuid}  -> start | stop | restart | kill | delete
 *   POST /img/servers/{id}/stats          {uuid}  -> le serveur (droits) + CPU, RAM, disque, joueurs (page du serveur)
 *   POST /img/servers/create              {uuid, name, type, version, ram_mb, cpu}
 * Console et fichiers (lot 54, droits CONSOLE et FILES) :
 *   POST /img/servers/{id}/console        {uuid, command}         -> {output}
 *   POST /img/servers/{id}/logs           {uuid, source, offset}  -> {text, offset, ...}
 *   POST /img/servers/{id}/files          {uuid, path}            -> {path, entries, truncated}
 *   POST /img/servers/{id}/files/download {uuid, path, offset, length} -> octets (X-File-Size, X-File-Mtime)
 *   POST /img/servers/{id}/files/upload?uuid=&path=&offset=&final=&abort=  corps brut (8 Mo au plus)
 *   POST /img/servers/{id}/files/mkdir    {uuid, path}
 *   POST /img/servers/{id}/files/rename   {uuid, from, to}
 *   POST /img/servers/{id}/files/delete   {uuid, path}
 *   POST /img/servers/{id}/files/archive  {uuid, dir, paths, name}  (lot 59 ; long : « pending » après 20 s)
 *   POST /img/servers/{id}/files/extract  {uuid, path}
 * Sauvegardes (lot 58, propriétaire et admins seulement, vérifié par l'API) :
 *   POST /img/servers/{id}/backups                     {uuid}  -> liste, réglages, limites
 *   POST /img/servers/{id}/backups/now                 {uuid}  -> sauvegarde manuelle
 *   POST /img/servers/{id}/backups/rule                {uuid, kind, max, duration, reset}
 *   POST /img/servers/{id}/backups/{bid}/permanent     {uuid, permanent}
 *   POST /img/servers/{id}/backups/{bid}/restore       {uuid}
 *   POST /img/backups/overview                         {uuid}  -> tous les serveurs (admins)
 *   POST /img/backups/network                          {uuid, kind, max, duration, reset} (admins)
 * Une action longue répond « pending » après 10 à 15 s et continue ; son erreur éventuelle est
 * rendue avec la liste suivante (lastError), pendant 10 min.
 */
class ImgServersHandler {

    private static final Set<String> ACTIONS = Set.of("start", "stop", "restart", "kill", "delete");
    private static final Set<String> FILE_OPS = Set.of("mkdir", "rename", "delete", "archive", "extract");
    static final int MAX_CHUNK = 8 << 20;
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

    /**
     * Réponse : (code HTTP, corps JSON) ou, pour un téléchargement réussi, bytes —
     * l'une des deux, une seule fois, depuis n'importe quel fil.
     */
    void handle(String path, JsonNode body, BiConsumer<Integer, JsonNode> reply,
                java.util.function.Consumer<ApiClient.FileContent> bytes) {
        UUID uuid = UUID.fromString(body.path("uuid").asText(""));
        if (path.startsWith("/img/backups")) {
            withPlayer(uuid, reply, pid -> backupsAdmin(path.substring("/img/backups".length()), pid, body, reply));
            return;
        }
        // Lot 69 : machines du réseau pour Admin → Réseau du site (l'API vérifie que c'est un admin)
        if (path.equals("/img/network")) {
            withPlayer(uuid, reply, pid -> jsonResult(api.networkOverview(pid), reply));
            return;
        }
        String rest = path.substring("/img/servers".length());
        withPlayer(uuid, reply, pid -> route(uuid, rest, pid, body, reply, bytes));
    }

    /** Morceau d'un téléversement : paramètres dans l'adresse, contenu brut dans le corps */
    void upload(String path, Map<String, String> query, byte[] data, BiConsumer<Integer, JsonNode> reply) {
        UUID uuid = UUID.fromString(query.getOrDefault("uuid", ""));
        String[] parts = path.substring("/img/servers/".length()).split("/");
        long serverId = Long.parseLong(parts[0]);
        String file = query.getOrDefault("path", "");
        long offset = Long.parseLong(query.getOrDefault("offset", "0"));
        boolean fin = "true".equals(query.get("final"));
        boolean abort = "true".equals(query.get("abort"));
        withPlayer(uuid, reply, pid -> {
            if (fin || abort) {
                logger.info("Site IMG : fichier {} du serveur {} {} par le joueur {}", file, serverId,
                        abort ? "abandonné" : "téléversé", pid);
            }
            jsonResult(api.writeFile(serverId, pid, file, offset, fin, abort, data), reply);
        });
    }

    private void withPlayer(UUID uuid, BiConsumer<Integer, JsonNode> reply, java.util.function.Consumer<Long> then) {
        api.getPlayerByUuid(uuid)
                .thenApply(p -> p.get("id").asLong())
                .exceptionally(e -> null)
                .thenAccept(pid -> {
                    if (pid == null) {
                        reply.accept(404, error("Ton compte Minecraft n'est pas encore connu du réseau : connecte-toi une fois "
                                + "en jeu (img.asearia.ca), puis reviens."));
                        return;
                    }
                    try {
                        then.accept(pid);
                    } catch (RuntimeException e) {
                        reply.accept(400, error("Requête invalide : " + e.getMessage()));
                    }
                });
    }

    private void jsonResult(CompletableFuture<JsonNode> work, BiConsumer<Integer, JsonNode> reply) {
        work.whenComplete((r, e) -> {
            if (e != null) {
                reply.accept(400, error(McsCommand.errorText(e)));
            } else {
                reply.accept(200, r == null ? json.createObjectNode() : r);
            }
        });
    }

    private void route(UUID uuid, String rest, long pid, JsonNode body, BiConsumer<Integer, JsonNode> reply,
                       java.util.function.Consumer<ApiClient.FileContent> bytes) {
        if (rest.isEmpty() || rest.equals("/")) {
            list(uuid, pid, reply);
            return;
        }
        if (rest.equals("/create")) {
            create(uuid, pid, body, reply);
            return;
        }
        String[] parts = rest.substring(1).split("/");
        long id = Long.parseLong(parts[0]);
        String what = parts.length > 1 ? parts[1] : "";
        if (parts.length == 2 && ACTIONS.contains(what)) {
            action(id, what, pid, reply);
        } else if (what.equals("backups")) {
            backups(id, parts, pid, body, reply);
        } else if (parts.length == 2 && what.equals("stats")) {
            stats(id, pid, reply);
        } else if (parts.length == 2 && what.equals("console")) {
            String command = body.path("command").asText("").trim();
            if (command.isEmpty() || command.length() > 256) {
                reply.accept(400, error("Commande vide ou trop longue (256 caractères au plus)."));
                return;
            }
            logger.info("Site IMG : console du serveur {} par le joueur {} : {}", id, pid, command);
            jsonResult(api.console(id, pid, command), reply);
        } else if (parts.length == 2 && what.equals("logs")) {
            jsonResult(api.serverLogs(id, pid, body.path("source").asText("game"), body.path("offset").asLong(-1)), reply);
        } else if (parts.length == 2 && what.equals("files")) {
            jsonResult(api.listFiles(id, pid, body.path("path").asText("")), reply);
        } else if (parts.length == 3 && what.equals("files") && parts[2].equals("download")) {
            int length = Math.min(Math.max(body.path("length").asInt(MAX_CHUNK), 1), MAX_CHUNK);
            api.readFile(id, pid, body.path("path").asText(""), Math.max(0, body.path("offset").asLong(0)), length)
                    .whenComplete((c, e) -> {
                        if (e != null) {
                            reply.accept(400, error(McsCommand.errorText(e)));
                        } else {
                            bytes.accept(c);
                        }
                    });
        } else if (parts.length == 3 && what.equals("files") && FILE_OPS.contains(parts[2])) {
            String op = parts[2];
            Map<String, Object> args;
            if (op.equals("rename")) {
                args = Map.of("from", body.path("from").asText(""), "to", body.path("to").asText(""));
            } else if (op.equals("archive")) {
                java.util.List<String> paths = new java.util.ArrayList<>();
                body.path("paths").forEach(p -> paths.add(p.asText("")));
                args = Map.of("dir", body.path("dir").asText(""), "paths", paths, "name", body.path("name").asText(""));
            } else {
                args = Map.of("path", body.path("path").asText(""));
            }
            logger.info("Site IMG : fichiers du serveur {}, {} {} par le joueur {}", id, op, args, pid);
            if (op.equals("archive") || op.equals("extract")) {
                finish(api.fileOp(id, pid, op, args), "srv:" + id, 20, reply,
                        op.equals("archive") ? "Archive créée." : "Archive extraite.");
            } else {
                jsonResult(api.fileOp(id, pid, op, args), reply);
            }
        } else {
            reply.accept(404, error("Route inconnue"));
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

    private static Integer intOrNull(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || n.asText("").isBlank() ? null : n.asInt();
    }

    /** Sauvegardes d'un serveur : les droits (propriétaire et admins) sont vérifiés par l'API */
    private void backups(long id, String[] parts, long pid, JsonNode body, BiConsumer<Integer, JsonNode> reply) {
        if (parts.length == 2) {
            jsonResult(api.listBackups(id, pid), reply);
        } else if (parts.length == 3 && parts[2].equals("now")) {
            logger.info("Site IMG : sauvegarde manuelle du serveur {} par le joueur {}", id, pid);
            finish(api.backupNow(id, pid, false), "srv:" + id, 20, reply, "Sauvegarde faite.");
        } else if (parts.length == 3 && parts[2].equals("rule")) {
            jsonResult(api.setBackupRule(id, pid, body.path("kind").asText(null), intOrNull(body.get("max")),
                    intOrNull(body.get("duration")), body.path("reset").asBoolean(false), false), reply);
        } else if (parts.length == 4 && parts[3].equals("permanent")) {
            jsonResult(api.setBackupPermanent(id, Long.parseLong(parts[2]), pid, body.path("permanent").asBoolean(true)), reply);
        } else if (parts.length == 4 && parts[3].equals("restore")) {
            logger.info("Site IMG : restauration de la sauvegarde {} du serveur {} par le joueur {}", parts[2], id, pid);
            finish(api.restoreBackup(id, pid, Long.parseLong(parts[2])), "srv:" + id, 20, reply, "Sauvegarde restaurée.");
        } else {
            reply.accept(404, error("Route inconnue"));
        }
    }

    /** Vue d'ensemble et réglages du réseau (admins MCS, vérifié par l'API) */
    private void backupsAdmin(String rest, long pid, JsonNode body, BiConsumer<Integer, JsonNode> reply) {
        if (rest.equals("/overview")) {
            jsonResult(api.backupOverview(pid), reply);
        } else if (rest.equals("/network")) {
            jsonResult(api.setBackupScopeRule(pid, "network", body.path("kind").asText(null), intOrNull(body.get("max")),
                    intOrNull(body.get("duration")), body.path("reset").asBoolean(false)), reply);
        } else {
            reply.accept(404, error("Route inconnue"));
        }
    }

    /** Un serveur de la team (sinon 404) avec ses mesures : CPU, RAM, disque, joueurs */
    private void stats(long id, long pid, BiConsumer<Integer, JsonNode> reply) {
        api.teamServers(pid).thenCompose(list -> {
            JsonNode found = null;
            for (JsonNode s : list.path("servers")) {
                if (s.path("id").asLong() == id) {
                    found = s;
                }
            }
            if (found == null) {
                return CompletableFuture.completedFuture((JsonNode) null);
            }
            ObjectNode out = ((ObjectNode) found).deepCopy();
            return api.getServerStats(id).handle((st, e) -> {
                if (st != null) {
                    for (String k : new String[]{"health", "cpuPercent", "memUsedMb", "memLimitMb", "diskUsedMb",
                            "allocatedStorageMb", "players", "lastStartedAt", "metricsAgeSeconds", "java", "lastBackupAt"}) {
                        if (st.has(k)) {
                            out.set(k, st.get(k));
                        }
                    }
                }
                out.put("online", proxy.getServer(out.path("velocityName").asText(""))
                        .map(r -> r.getPlayersConnected().size()).orElse(0));
                String err = lastError("srv:" + id);
                if (err != null) {
                    out.put("lastError", err);
                }
                return (JsonNode) out;
            });
        }).whenComplete((out, e) -> {
            if (e != null) {
                reply.accept(502, error(McsCommand.errorText(e)));
            } else if (out == null) {
                reply.accept(404, error("Serveur introuvable"));
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
            case "kill" -> "Serveur arrêté de force.";
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
