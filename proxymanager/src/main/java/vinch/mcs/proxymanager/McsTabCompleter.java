package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.velocitypowered.api.proxy.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Auto-complétion de /mcs (sur le proxy, donc sur tous les serveurs) */
public class McsTabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "list", "info", "join", "start", "stop", "restart", "members", "invite", "remove",
            "leave", "public", "private", "display", "console", "move", "delete", "quota", "host"
    );

    private static final List<String> TYPES = List.of("PAPER", "SPIGOT", "FABRIC", "FORGE", "VANILLA");

    private static final List<String> COMMON_VERSIONS = List.of(
            "LATEST", "26.1.2", "1.21.11", "1.21.4", "1.21.1", "1.20.1", "1.19.2", "1.18.2"
    );

    private static final List<String> LEVELS = List.of("membre", "gerant", "technicien");

    private static final Set<String> SERVER_ARG = Set.of(
            "delete", "join", "restart", "info", "members", "invite", "remove", "leave",
            "public", "private", "display", "console", "start", "stop");

    // Serveurs du joueur (les siens + partagés en "pseudo/nom"), gardés 10 s
    private final Map<UUID, List<Cached>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRefresh = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 10_000;

    private final McsCommand command;

    public McsTabCompleter(McsCommand command) {
        this.command = command;
    }

    public CompletableFuture<List<String>> suggest(Player player, String[] rawArgs) {
        // "/mcs " : Velocity peut donner zéro argument ; on raisonne comme avec un argument vide
        String[] args = rawArgs.length == 0 ? new String[]{""} : rawArgs;
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            return done(filter(SUBCOMMANDS, args[0]));
        }
        if (args.length == 2) {
            if (sub.equals("create")) {
                return done(filter(TYPES, args[1]));
            }
            if (sub.equals("move")) {
                return done(filter(playerNames(), args[1]));
            }
            if (sub.equals("host")) {
                return done(filter(List.of("list", "set", "remove"), args[1]));
            }
            if (SERVER_ARG.contains(sub)) {
                String status = switch (sub) {
                    case "stop" -> "RUNNING";
                    default -> null;
                };
                return serverNames(player, status).thenApply(names -> filter(names, args[1]));
            }
        }
        if (args.length == 3) {
            switch (sub) {
                case "create" -> {
                    return done(filter(COMMON_VERSIONS, args[2]));
                }
                case "invite", "remove" -> {
                    return done(filter(playerNames(), args[2]));
                }
                case "move" -> {
                    return serverNames(player, null).thenApply(names -> filter(names, args[2]));
                }
                case "display" -> {
                    return done(filter(List.of("on", "off"), args[2]));
                }
                default -> {
                }
            }
        }
        if (args.length == 4 && sub.equals("host") && args[1].equalsIgnoreCase("set")) {
            return done(filter(playerNames(), args[3]));
        }
        if (args.length == 4 && sub.equals("invite")) {
            return done(filter(LEVELS, args[3]));
        }
        if (args.length == 5 && sub.equals("create")) {
            return done(filter(List.of("2G", "4G", "6G", "8G", "12G", "16G"), args[4]));
        }
        if (args.length == 6 && sub.equals("create")) {
            return done(filter(List.of("1", "2", "4", "8"), args[5]));
        }
        return done(List.of());
    }

    private static CompletableFuture<List<String>> done(List<String> list) {
        return CompletableFuture.completedFuture(list);
    }

    private List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : command.proxy().getAllPlayers()) {
            names.add(p.getUsername());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(opt);
            }
        }
        return result;
    }

    /** Noms de serveurs ; le cache évite un appel à l'API à chaque touche */
    private CompletableFuture<List<String>> serverNames(Player player, String statusFilter) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        List<Cached> cached = cache.get(uuid);
        if (cached != null && now - lastRefresh.getOrDefault(uuid, 0L) < CACHE_TTL) {
            return done(names(cached, statusFilter));
        }
        lastRefresh.put(uuid, now);
        return command.api().getPlayerByUuid(uuid)
                .thenCompose(info -> command.api().getPlayerServers(info.get("id").asLong()))
                .thenApply(result -> {
                    List<Cached> list = new ArrayList<>();
                    for (JsonNode s : result.path("servers")) {
                        list.add(new Cached(s.path("name").asText(), s.path("status").asText()));
                    }
                    for (JsonNode s : result.path("shared")) {
                        list.add(new Cached(s.path("ref").asText(), s.path("status").asText()));
                    }
                    cache.put(uuid, list);
                    return names(list, statusFilter);
                })
                .exceptionally(e -> cached == null ? List.of() : names(cached, statusFilter));
    }

    private static List<String> names(List<Cached> servers, String statusFilter) {
        List<String> result = new ArrayList<>();
        for (Cached s : servers) {
            if (statusFilter == null || statusFilter.equals(s.status())) {
                result.add(s.name());
            }
        }
        return result;
    }

    private record Cached(String name, String status) {}
}
