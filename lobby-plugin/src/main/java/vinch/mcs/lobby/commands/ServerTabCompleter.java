package vinch.mcs.lobby.commands;

import com.fasterxml.jackson.databind.JsonNode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import vinch.mcs.lobby.McsLobbyPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServerTabCompleter implements TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "list", "delete", "join", "start", "stop", "restart", "quota", "info"
    );

    private static final List<String> TYPES = List.of(
            "PAPER", "SPIGOT", "FABRIC", "FORGE", "VANILLA"
    );

    private static final List<String> COMMON_VERSIONS = List.of(
            "LATEST", "26.1.2", "1.21.11", "1.21.4", "1.21.1", "1.20.1", "1.19.2", "1.18.2"
    );

    // Cache : UUID joueur -> liste de ses serveurs (avec status)
    private final Map<UUID, List<CachedServer>> serverCache = new ConcurrentHashMap<>();
    // Cache : UUID joueur -> timestamp du dernier refresh
    private final Map<UUID, Long> lastRefresh = new ConcurrentHashMap<>();

    private static final long CACHE_TTL = 10_000; // 10 secondes

    private final McsLobbyPlugin plugin;

    public ServerTabCompleter(McsLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            // /mcs <TAB>
            return filter(SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            switch (sub) {
                case "create":
                    return filter(TYPES, args[1]);

                case "delete":
                case "join":
                case "restart":
                case "info":
                    refreshCacheIfNeeded(player);
                    return filter(getServerNames(player, null), args[1]);

                case "start":
                    refreshCacheIfNeeded(player);
                    List<String> startable = new ArrayList<>(getServerNames(player, "STOPPED"));
                    startable.addAll(getServerNames(player, "ERROR"));
                    return filter(startable, args[1]);

                case "stop":
                    refreshCacheIfNeeded(player);
                    return filter(getServerNames(player, "RUNNING"), args[1]);
            }
        }

        if (args.length == 3 && sub.equals("create")) {
            // /mcs create PAPER <TAB> -> propose les versions
            return filter(COMMON_VERSIONS, args[2]);
        }

        if (args.length == 5 && sub.equals("create")) {
            // RAM en Go (2G) ou en Mo (2048)
            return filter(List.of("2G", "4G", "6G", "8G", "12G", "16G"), args[4]);
        }

        if (args.length == 6 && sub.equals("create")) {
            return filter(List.of("1", "2", "4", "8"), args[5]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String input) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lower = input.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(lower)) {
                result.add(opt);
            }
        }
        return result;
    }

    private List<String> getServerNames(Player player, String statusFilter) {
        List<CachedServer> servers = serverCache.get(player.getUniqueId());
        if (servers == null) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (CachedServer s : servers) {
            if (statusFilter == null || statusFilter.equals(s.status)) {
                result.add(s.name);
            }
        }
        return result;
    }

    private void refreshCacheIfNeeded(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastRefresh.getOrDefault(uuid, 0L);

        if (now - last < CACHE_TTL) {
            return; // Cache encore valide
        }

        // Marquer comme rafraîchi maintenant pour éviter les appels multiples
        lastRefresh.put(uuid, now);

        // Refresh async, sans bloquer le tab completer
        plugin.getApiClient().getPlayerByUuid(uuid)
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().getPlayerServers(playerId);
                })
                .whenComplete((result, error) -> {
                    if (error != null || result == null || !result.has("servers")) {
                        return;
                    }
                    List<CachedServer> list = new ArrayList<>();
                    for (JsonNode s : result.get("servers")) {
                        list.add(new CachedServer(s.get("name").asText(), s.get("status").asText()));
                    }
                    serverCache.put(uuid, list);
                });
    }

    private record CachedServer(String name, String status) {}
}