package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Lot 76 : /jouer <jeu> — un serveur à la demande libre (speedrun, monde neuf…) : un serveur en
 * marche où personne n'est, sinon un arrêté (démarré), sinon un nouveau (créé, 1 à 3 min).
 * Aussi appelé par le plugin img-farm (PNJ du lobby, menu) : action sans risque, bornée par les
 * limites du groupe. Arrête aussi les serveurs du groupe vides depuis idleStopMinutes.
 */
final class PoolJoin implements SimpleCommand {

    static final Pattern POOL = Pattern.compile("^[a-z0-9-]{2,24}$");

    private final ProxyServer proxy;
    private final ApiClient api;
    private final Logger logger;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();
    private volatile JsonNode pools;

    PoolJoin(ProxyServer proxy, ApiClient api, Logger logger) {
        this.proxy = proxy;
        this.api = api;
        this.logger = logger;
    }

    private static Component info(String s) {
        return Component.text("» ", NamedTextColor.DARK_AQUA).append(Component.text(s, NamedTextColor.GRAY));
    }

    private static Component error(String s) {
        return Component.text("✘ ", NamedTextColor.RED).append(Component.text(s, NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ commande ----

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Commande réservée aux joueurs."));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            list(player);
            return;
        }
        String first = args[0].toLowerCase(java.util.Locale.ROOT);
        // /jouer avec <joueur> : la partie d'un autre joueur, quel que soit le jeu
        if (first.equals("avec")) {
            if (args.length < 2) {
                player.sendMessage(error("Utilisation : /jouer avec <joueur>"));
            } else {
                joinWith(player, null, args[1]);
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("seul")) {
            claim(player, first);
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("avec")) {
            joinWith(player, first, args[2]);
        } else {
            join(player, first);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        List<String> out = new ArrayList<>();
        JsonNode p = pools;
        String[] a = invocation.arguments();
        if (a.length == 3 && a[1].equalsIgnoreCase("avec") || a.length == 2 && a[0].equalsIgnoreCase("avec")) {
            String start = a[a.length - 1].toLowerCase(java.util.Locale.ROOT);
            for (Player o : proxy.getAllPlayers()) {
                if (o.getUsername().toLowerCase(java.util.Locale.ROOT).startsWith(start)) {
                    out.add(o.getUsername());
                }
            }
            return out;
        }
        if (a.length == 2) {
            for (String o : List.of("seul", "avec")) {
                if (o.startsWith(a[1].toLowerCase(java.util.Locale.ROOT))) {
                    out.add(o);
                }
            }
            return out;
        }
        if (p != null && invocation.arguments().length <= 1) {
            String start = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
            p.path("pools").forEach(x -> {
                String n = x.path("name").asText();
                if (n.startsWith(start)) {
                    out.add(n);
                }
            });
        }
        return out;
    }

    private void list(Player player) {
        api.pools().whenComplete((r, e) -> {
            if (e != null) {
                player.sendMessage(error(McsCommand.errorText(e)));
                return;
            }
            pools = r;
            // Un seul jeu : on y va directement
            if (r.path("pools").size() == 1) {
                join(player, r.path("pools").get(0).path("name").asText());
                return;
            }
            player.sendMessage(info("Jeux à la demande (un serveur rien que pour toi) :"));
            for (JsonNode x : r.path("pools")) {
                String n = x.path("name").asText();
                player.sendMessage(Component.text("  [" + x.path("label").asText(n) + "]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/jouer " + n))
                        .hoverEvent(HoverEvent.showText(Component.text(x.path("description").asText("/jouer " + n))))
                        .append(Component.text("  /jouer " + n, NamedTextColor.DARK_GRAY)));
            }
        });
    }

    // ------------------------------------------------------------------ rejoindre ----

    /** Serveurs où des joueurs sont connectés */
    private List<String> occupied() {
        List<String> out = new ArrayList<>();
        for (RegisteredServer s : proxy.getAllServers()) {
            if (!s.getPlayersConnected().isEmpty()) {
                out.add(s.getServerInfo().getName());
            }
        }
        return out;
    }

    /** Serveurs du groupe (null = tous les groupes) en marche, avec leurs joueurs (pas celui du joueur) */
    private Map<String, List<String>> playing(JsonNode r, String pool, Player me) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        String mine = me.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        for (JsonNode p : r.path("pools")) {
            if (pool != null && !pool.equals(p.path("name").asText())) {
                continue;
            }
            for (JsonNode s : p.path("servers")) {
                String vn = s.path("velocityName").asText("");
                if (vn.equals(mine)) {
                    continue;
                }
                proxy.getServer(vn).ifPresent(rs -> {
                    List<String> names = rs.getPlayersConnected().stream().map(Player::getUsername).sorted().toList();
                    if (!names.isEmpty()) {
                        out.put(s.path("name").asText(vn), names);
                    }
                });
            }
        }
        return out;
    }

    /**
     * /jouer <jeu> : si des joueurs sont déjà sur un serveur du jeu, on propose de les rejoindre
     * ou d'avoir son propre monde ; sinon directement un serveur libre.
     */
    void join(Player player, String pool) {
        if (!POOL.matcher(pool).matches()) {
            player.sendMessage(error("Jeu inconnu : /jouer pour la liste."));
            return;
        }
        api.pools().whenComplete((r, e) -> {
            if (e != null) {
                player.sendMessage(error(McsCommand.errorText(e)));
                return;
            }
            pools = r;
            Map<String, List<String>> others = playing(r, pool, player);
            if (others.isEmpty()) {
                claim(player, pool);
                return;
            }
            player.sendMessage(info("Des joueurs sont déjà en partie. Tu veux :"));
            for (Map.Entry<String, List<String>> en : others.entrySet()) {
                String who = String.join(", ", en.getValue());
                String cmd = "/jouer " + pool + " avec " + en.getValue().get(0);
                player.sendMessage(Component.text("  [Rejoindre " + who + "]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand(cmd))
                        .hoverEvent(HoverEvent.showText(Component.text("Même monde que " + who + " (" + en.getKey() + ")")))
                        .append(Component.text("  " + cmd, NamedTextColor.DARK_GRAY)));
            }
            String solo = "/jouer " + pool + " seul";
            player.sendMessage(Component.text("  [Mon propre monde]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand(solo))
                    .hoverEvent(HoverEvent.showText(Component.text("Un serveur libre, ou un nouveau au besoin")))
                    .append(Component.text("  " + solo, NamedTextColor.DARK_GRAY)));
        });
    }

    /** Sur le serveur à la demande où est ce joueur (pool null = n'importe quel jeu) */
    void joinWith(Player player, String pool, String name) {
        Optional<Player> target = proxy.getPlayer(name);
        if (target.isEmpty()) {
            player.sendMessage(error(name + " n'est pas connecté."));
            return;
        }
        api.pools().whenComplete((r, e) -> {
            if (e != null) {
                player.sendMessage(error(McsCommand.errorText(e)));
                return;
            }
            pools = r;
            String where = target.get().getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
            String mine = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
            for (JsonNode p : r.path("pools")) {
                if (pool != null && !pool.equals(p.path("name").asText())) {
                    continue;
                }
                for (JsonNode s : p.path("servers")) {
                    if (s.path("velocityName").asText("").equals(where)) {
                        if (where.equals(mine)) {
                            player.sendMessage(info("Tu es déjà sur le même serveur que " + target.get().getUsername() + "."));
                            return;
                        }
                        connect(player, where, s.path("name").asText(where));
                        return;
                    }
                }
            }
            player.sendMessage(error(target.get().getUsername() + " n'est pas sur un serveur "
                    + (pool == null ? "à la demande" : pool) + "."));
        });
    }

    /** Un serveur du jeu rien que pour soi : libre, démarré ou créé */
    void claim(Player player, String pool) {
        if (!POOL.matcher(pool).matches()) {
            player.sendMessage(error("Jeu inconnu : /jouer pour la liste."));
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            player.sendMessage(info("Déjà en route vers un serveur, patiente un instant."));
            return;
        }
        api.getPlayerByUuid(player.getUniqueId())
                .thenCompose(p -> {
                    long pid = p.get("id").asLong();
                    return api.poolClaim(pool, pid, occupied()).thenCompose(r -> act(player, pool, pid, r, 0));
                })
                .whenComplete((v, e) -> {
                    busy.remove(player.getUniqueId());
                    if (e != null) {
                        player.sendMessage(error(McsCommand.errorText(e)));
                    }
                });
    }

    private CompletableFuture<Void> act(Player player, String pool, long pid, JsonNode r, int tries) {
        String label = r.path("label").asText(pool);
        JsonNode s = r.path("server");
        String name = s.path("name").asText("");
        switch (r.path("action").asText("")) {
            case "join" -> {
                return connect(player, s.path("velocityName").asText(), name);
            }
            case "start" -> {
                player.sendMessage(info("Démarrage de " + name + " (" + label + ") : environ une minute…"));
                return api.serverAction(s.path("id").asLong(), "start", pid)
                        .handle((x, e) -> e)
                        .thenCompose(e -> {
                            if (e == null) {
                                return connect(player, s.path("velocityName").asText(), name);
                            }
                            // Démarré au même moment par un autre joueur ? on regarde de nouveau une fois
                            return api.poolClaim(pool, pid, occupied()).thenCompose(next -> {
                                // Seulement s'il a été démarré par quelqu'un d'autre entre-temps ; jamais
                                // créer un serveur en plus parce que celui-ci n'a pas démarré
                                String na = next.path("action").asText();
                                if (!(na.equals("join") || na.equals("wait")) || tries >= 18) {
                                    player.sendMessage(error(McsCommand.errorText(e)));
                                    return CompletableFuture.<Void>completedFuture(null);
                                }
                                return act(player, pool, pid, next, tries + 1);
                            });
                        });
            }
            case "wait", "wait-create" -> {
                if (tries == 0) {
                    player.sendMessage(info("Un serveur " + label + " se prépare, tu y vas dès qu'il est prêt…"));
                }
                return retry(player, pool, pid, tries);
            }
            case "create" -> {
                if (r.path("extra").asBoolean(false)) {
                    int h = r.path("idleDeleteHours").asInt(24);
                    player.sendMessage(info("Tous les serveurs " + label + " sont pris : j'en prépare un en plus "
                            + "(1 à 3 minutes ; il sera supprimé après " + (h == 24 ? "un jour" : h + " h") + " sans personne)…"));
                } else {
                    player.sendMessage(info("Préparation du serveur " + label + " (1 à 3 minutes, la première fois seulement)…"));
                }
                logger.info("{} demande un nouveau serveur {}", player.getUsername(), pool);
                return api.poolCreate(pool, pid).handle((c, e) -> {
                    if (e != null) {
                        // Un autre joueur a lancé la création au même moment, ou la limite est atteinte
                        player.sendMessage(error(McsCommand.errorText(e)));
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return connect(player, c.path("server").path("velocityName").asText(),
                            c.path("server").path("name").asText());
                }).thenCompose(f -> f);
            }
            case "full" -> {
                player.sendMessage(error("Tous les serveurs " + label + " sont pris et la limite est atteinte : réessaie plus tard."));
                return CompletableFuture.completedFuture(null);
            }
            default -> {
                player.sendMessage(error("Réponse inattendue du réseau, réessaie."));
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    /** Regarde de nouveau dans 10 s (serveur qui se prépare), 3 min au plus */
    private CompletableFuture<Void> retry(Player player, String pool, long pid, int tries) {
        if (tries >= 18 || !player.isActive()) {
            if (player.isActive()) {
                player.sendMessage(error("Le serveur met trop de temps : réessaie /jouer " + pool + "."));
            }
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS))
                .thenCompose(x -> api.poolClaim(pool, pid, occupied()))
                .thenCompose(next -> act(player, pool, pid, next, tries + 1));
    }

    /** Envoie le joueur (le serveur peut mettre quelques secondes à être connu du proxy) */
    private CompletableFuture<Void> connect(Player player, String velocityName, String name) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        tryConnect(player, velocityName, name, 0, done);
        return done;
    }

    private void tryConnect(Player player, String velocityName, String name, int attempt, CompletableFuture<Void> done) {
        if (!player.isActive()) {
            done.complete(null);
            return;
        }
        Optional<RegisteredServer> target = proxy.getServer(velocityName);
        if (target.isEmpty()) {
            if (attempt >= 10) {
                player.sendMessage(error(name + " n'est pas encore joignable : réessaie dans un instant."));
                done.complete(null);
                return;
            }
            proxy.getScheduler().buildTask(plugin, () -> tryConnect(player, velocityName, name, attempt + 1, done))
                    .delay(Duration.ofSeconds(1)).schedule();
            return;
        }
        player.sendMessage(info("Connexion à " + name + "…"));
        player.createConnectionRequest(target.get()).fireAndForget();
        done.complete(null);
    }

    private Object plugin;

    void setPlugin(Object plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- serveurs vides ----

    /** Toutes les minutes : liste des groupes, et arrêt des serveurs vides depuis idleStopMinutes */
    void tick() {
        api.pools().whenComplete((r, e) -> {
            if (e != null || r == null) {
                return;
            }
            pools = r;
            long now = System.currentTimeMillis();
            Set<String> seen = ConcurrentHashMap.newKeySet();
            for (JsonNode p : r.path("pools")) {
                int idle = p.path("idleStopMinutes").asInt(0);
                for (JsonNode s : p.path("servers")) {
                    String vn = s.path("velocityName").asText("");
                    if (!"RUNNING".equals(s.path("status").asText()) || idle <= 0) {
                        continue;
                    }
                    seen.add(vn);
                    int players = proxy.getServer(vn).map(x -> x.getPlayersConnected().size()).orElse(0);
                    if (players > 0) {
                        emptySince.remove(vn);
                        continue;
                    }
                    long since = emptySince.computeIfAbsent(vn, k -> now);
                    if (now - since >= idle * 60_000L) {
                        emptySince.remove(vn);
                        logger.info("Serveur à la demande {} vide depuis {} min : arrêt", s.path("name").asText(), idle);
                        api.poolIdleStop(s.path("id").asLong()).exceptionally(x -> null);
                    }
                }
            }
            emptySince.keySet().retainAll(seen);
        });
    }
}
