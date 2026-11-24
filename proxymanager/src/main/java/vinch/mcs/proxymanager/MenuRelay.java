package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Menu en jeu (lot 60) : le plugin img-farm affiche les menus ; le proxy lui donne les données
 * et exécute les actions sans risque, toujours comme la commande /mcs du joueur.
 *
 * Sécurité : un clic dans un menu est vu par le serveur de jeu, pas par le proxy. Or un membre
 * peut installer des plugins sur un serveur de la team : le proxy ne fait donc pour lui que des
 * actions inoffensives (rejoindre, démarrer, lire). Tout ce qui modifie ou détruit (arrêter,
 * supprimer, console, créer, sauvegarder…) passe par un bouton « Confirmer » dans le chat : c'est
 * alors le client du joueur qui envoie la vraie commande /mcs, ce qu'un serveur ne peut pas imiter.
 *
 * Opérations (canal img:farm, relayées par FarmRelay) :
 *   context            -> {lobby, site}  ce serveur est-il le lobby IMG (boussole du menu), adresse du site
 *   servers            -> {servers, quota, admin}   serveurs du joueur et ceux qui lui sont ouverts
 *   server {id}        -> le serveur + CPU, RAM, disque, joueurs
 *   act {cmd, ref}     -> /mcs <cmd> <ref> exécuté pour le joueur ; cmd parmi SAFE seulement
 *   opened {push}      -> le plugin confirme avoir ouvert un menu demandé par le proxy (/mcs seul)
 */
class MenuRelay {

    static final Set<String> OPS = Set.of("context", "servers", "server", "act", "opened");
    private static final Set<String> SAFE = Set.of("join", "start", "info", "list", "quota", "backups", "members");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_./-]{1,80}$");

    private final ProxyServer proxy;
    private final ApiClient api;
    private final ImgGate gate;
    private final String site;
    private final Logger logger;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextPush = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Boolean>> pushes = new ConcurrentHashMap<>();

    MenuRelay(ProxyServer proxy, ApiClient api, ImgGate gate, String site, Logger logger) {
        this.proxy = proxy;
        this.api = api;
        this.gate = gate;
        this.site = site;
        this.logger = logger;
    }

    private ObjectNode error(String message) {
        return json.createObjectNode().put("error", message);
    }

    private static String text(Throwable e) {
        return McsCommand.errorText(e);
    }

    void handle(ServerConnection connection, Player player, String op, JsonNode data, BiConsumer<Integer, JsonNode> reply) {
        switch (op) {
            case "context" -> {
                boolean lobby = gate != null && gate.enabled() && gate.isLobby(connection.getServerInfo().getName());
                ObjectNode out = json.createObjectNode().put("lobby", lobby);
                if (site != null) {
                    out.put("site", site);
                }
                reply.accept(200, out);
            }
            case "opened" -> {
                CompletableFuture<Boolean> f = pushes.remove(data.path("push").asLong(0));
                if (f != null) {
                    f.complete(true);
                }
                reply.accept(200, json.createObjectNode().put("ok", true));
            }
            case "act" -> act(player, data, reply);
            case "servers", "server" -> withPlayer(player, reply, pid -> {
                if (op.equals("servers")) {
                    servers(pid, reply);
                } else {
                    server(pid, data.path("id").asLong(0), reply);
                }
            });
            default -> reply.accept(400, error("Requête inconnue"));
        }
    }

    private void withPlayer(Player player, BiConsumer<Integer, JsonNode> reply, java.util.function.LongConsumer then) {
        api.getPlayerByUuid(player.getUniqueId()).whenComplete((p, e) -> {
            if (e != null || p == null || !p.has("id")) {
                reply.accept(404, error("Ton profil MCS est introuvable : reconnecte-toi au réseau."));
                return;
            }
            then.accept(p.get("id").asLong());
        });
    }

    private int online(JsonNode server) {
        return proxy.getServer(server.path("velocityName").asText(""))
                .map(r -> r.getPlayersConnected().size()).orElse(0);
    }

    private void servers(long pid, BiConsumer<Integer, JsonNode> reply) {
        CompletableFuture<JsonNode> list = api.menuServers(pid);
        CompletableFuture<JsonNode> quota = api.getPlayerQuota(pid).exceptionally(e -> null);
        list.thenCombine(quota, (l, q) -> {
            ObjectNode out = json.createObjectNode();
            ArrayNode servers = out.putArray("servers");
            for (JsonNode s : l.path("servers")) {
                ObjectNode o = ((ObjectNode) s).deepCopy();
                o.put("online", online(s));
                servers.add(o);
            }
            out.put("admin", l.path("admin").asBoolean(false));
            if (q != null) {
                out.set("quota", q);
            }
            return (JsonNode) out;
        }).whenComplete((out, e) -> {
            if (e != null) {
                reply.accept(502, error(text(e)));
            } else {
                reply.accept(200, out);
            }
        });
    }

    private void server(long pid, long id, BiConsumer<Integer, JsonNode> reply) {
        api.menuServers(pid).thenCompose(l -> {
            JsonNode found = null;
            for (JsonNode s : l.path("servers")) {
                if (s.path("id").asLong() == id) {
                    found = s;
                }
            }
            if (found == null) {
                return CompletableFuture.completedFuture((JsonNode) null);
            }
            ObjectNode out = ((ObjectNode) found).deepCopy();
            out.put("online", online(found));
            return api.getServerStats(id).handle((st, e) -> {
                if (st != null) {
                    for (String k : new String[]{"cpuPercent", "memUsedMb", "memLimitMb", "diskUsedMb",
                            "allocatedStorageMb", "lastStartedAt", "lastBackupAt", "java"}) {
                        if (st.has(k)) {
                            out.set(k, st.get(k));
                        }
                    }
                }
                return (JsonNode) out;
            });
        }).whenComplete((out, e) -> {
            if (e != null) {
                reply.accept(502, error(text(e)));
            } else if (out == null) {
                reply.accept(404, error("Serveur introuvable"));
            } else {
                reply.accept(200, out);
            }
        });
    }

    /** Actions sans risque seulement, exécutées comme la commande /mcs du joueur */
    private PoolJoin poolJoin;

    void setPoolJoin(PoolJoin poolJoin) {
        this.poolJoin = poolJoin;
    }

    private void act(Player player, JsonNode data, BiConsumer<Integer, JsonNode> reply) {
        String cmd = data.path("cmd").asText("");
        String ref = data.path("ref").asText("").trim();
        // Lot 76 : /jouer <jeu> depuis un serveur (PNJ du lobby, menu) : rejoindre un serveur à la
        // demande est sans risque (création bornée par les limites du groupe, au nom du joueur)
        if ("jouer".equals(cmd) && poolJoin != null && PoolJoin.POOL.matcher(ref).matches()) {
            poolJoin.join(player, ref);
            reply.accept(200, json.createObjectNode().put("ok", true));
            return;
        }
        if (!SAFE.contains(cmd) || (!ref.isEmpty() && !REF.matcher(ref).matches())) {
            reply.accept(400, error("Action non permise depuis le menu : confirme-la dans le chat."));
            return;
        }
        String line = "mcs " + cmd + (ref.isEmpty() ? "" : " " + ref);
        proxy.getCommandManager().executeAsync(player, line);
        reply.accept(200, json.createObjectNode().put("ok", true));
    }

    /** /mcs sans argument : demande au plugin du serveur d'ouvrir un menu ; false s'il ne répond pas */
    CompletableFuture<Boolean> open(Player player, String menu) {
        Optional<ServerConnection> conn = player.getCurrentServer();
        if (conn.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        long id = nextPush.getAndIncrement();
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        pushes.put(id, f);
        ObjectNode msg = json.createObjectNode().put("push", id).put("open", menu);
        try {
            conn.get().sendPluginMessage(FarmRelay.CHANNEL, msg.toString().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            pushes.remove(id);
            return CompletableFuture.completedFuture(false);
        }
        return f.completeOnTimeout(false, 1500, TimeUnit.MILLISECONDS).whenComplete((r, e) -> pushes.remove(id));
    }
}
