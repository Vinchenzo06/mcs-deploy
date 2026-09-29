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
            "leave", "public", "private", "display", "console", "move", "delete", "quota", "host",
            "backup", "backups"
    );

    private static final List<String> TYPES = List.of("PAPER", "SPIGOT", "FABRIC", "FORGE", "VANILLA");

    private static final List<String> COMMON_VERSIONS = List.of(
            "LATEST", "26.1.2", "1.21.11", "1.21.4", "1.21.1", "1.20.1", "1.19.2", "1.18.2"
    );

    private static final List<String> LEVELS = List.of("membre", "gerant", "technicien");

    private static final List<String> BACKUP_SUBS = List.of("keep", "unkeep", "settings", "set", "defaults", "minimum", "limits");
    private static final List<String> BACKUP_KINDS = List.of("quotidienne", "hebdomadaire", "mensuelle", "manuelle", "permanente");
    private static final List<String> RANKS = List.of("default", "vip", "premium", "host", "admin");

    private static final Set<String> SERVER_ARG = Set.of(
            "delete", "join", "restart", "info", "members", "invite", "remove", "leave",
            "public", "private", "display", "console", "start", "stop", "backup", "backups");

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
        if (sub.equals("backup") && args.length >= 2) {
            return suggestBackup(player, args);
        }
        if (args.length == 2) {
            if (sub.equals("create")) {
                return done(filter(TYPES, args[1]));
            }
            if (sub.equals("move")) {
                return done(filter(playerNames(), args[1]));
            }
            if (sub.equals("host")) {
                return done(filter(List.of("list", "set", "remove", "backups"), args[1]));
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
        if (sub.equals("host") && args[1].equalsIgnoreCase("backups") && args.length >= 4) {
            if (args.length == 4) {
                return done(filter(List.of("quotidienne", "manuelle", "reset"), args[3]));
            }
            return done(scopeArgs(args, 3, false));
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

    /** /mcs backup <serveur> | keep|unkeep|settings|set <serveur> ... | defaults ... | limits <rôle> ... */
    private CompletableFuture<List<String>> suggestBackup(Player player, String[] args) {
        String a1 = args[1].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        if (args.length == 2) {
            return serverNames(player, null).thenApply(names -> {
                List<String> all = new ArrayList<>(BACKUP_SUBS);
                all.addAll(names);
                return filter(all, last);
            });
        }
        switch (a1) {
            case "keep", "unkeep", "settings", "set" -> {
                if (args.length == 3) {
                    return serverNames(player, null).thenApply(names -> filter(names, last));
                }
                if (a1.equals("set")) {
                    boolean local = args[3].equalsIgnoreCase("local");
                    if (args.length == 4) {
                        List<String> kinds = new ArrayList<>(BACKUP_KINDS);
                        kinds.add("local");
                        kinds.add("reset");
                        return done(filter(kinds, last));
                    }
                    if (local && args.length == 5) {
                        return done(filter(List.of("quotidienne", "manuelle", "reset"), last));
                    }
                    return done(scopeArgs(args, local ? 4 : 3, false));
                }
                return done(List.of());
            }
            case "defaults" -> {
                if (args.length == 3) {
                    List<String> kinds = new ArrayList<>(BACKUP_KINDS);
                    kinds.add("local");
                    return done(filter(kinds, last));
                }
                return done(scopeArgs(args, args[2].equalsIgnoreCase("local") ? 3 : 2, false));
            }
            case "minimum" -> {
                return done(scopeArgs(args, 2, true));
            }
            case "limits" -> {
                if (args.length == 3) {
                    return done(filter(RANKS, last));
                }
                return done(scopeArgs(args, 3, true));
            }
            default -> {
                return done(List.of());
            }
        }
    }

    private static List<String> scopeArgs(String[] args, int from, boolean allowReset) {
        String last = args[args.length - 1];
        int pos = args.length - 1 - from;
        boolean perma = args.length > from && args[from].toLowerCase(Locale.ROOT).startsWith("perma");
        return switch (pos) {
            case 0 -> {
                List<String> kinds = new ArrayList<>(BACKUP_KINDS);
                if (allowReset) {
                    kinds.add("reset");
                }
                yield filter(kinds, last);
            }
            case 1 -> filter(List.of("0", "1", "2", "3", "5", "7"), last);
            case 2 -> filter(perma ? List.of("0h", "24h", "48h", "7j") : List.of("3j", "7j", "14j", "30j"), last);
            default -> List.of();
        };
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
