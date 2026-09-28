package vinch.mcs.lobby;

import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import vinch.mcs.lobby.listeners.PlayerJoinListener;
import vinch.mcs.lobby.permissions.LuckPermsHelper;

@Getter
public final class McsLobbyPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private ApiClient apiClient;
    private LuckPermsHelper luckPermsHelper;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pluginConfig = PluginConfig.from(getConfig());

        if ("CHANGE_ME".equals(pluginConfig.getApiKey())) {
            getLogger().severe("ATTENTION : api-key par défaut détectée, modifie le fichier config.yml !");
        }

        apiClient = new ApiClient(pluginConfig, this);

        luckPermsHelper = new LuckPermsHelper();
        luckPermsHelper.init();
        if (luckPermsHelper.isEnabled()) {
            getLogger().info("LuckPerms détecté et initialisé");
        } else {
            getLogger().warning("LuckPerms non trouvé, utilisation des limites par défaut");
        }

        // Canal privé vers le proxy (le canal "BungeeCord" est désactivé sur Velocity)
        getServer().getMessenger().registerOutgoingPluginChannel(this, "mcs:connect");

        // /mcs est maintenant sur le proxy (proxymanager) : disponible sur tous les serveurs

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        getLogger().info("MCSLobbyPlugin démarré avec succès");
    }

    /**
     * Envoie à l'API les quotas LuckPerms actuels du joueur (max-servers,
     * total-ram, total-cpu). Appelé à la connexion, puis avant /mcs create et
     * /mcs quota : un changement de groupe est pris en compte tout de suite.
     * N'échoue jamais : en cas de problème, les dernières limites connues restent.
     */
    public CompletableFuture<Void> syncPlayerLimits(UUID uuid, String name) {
        if (!luckPermsHelper.isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return luckPermsHelper.loadUserMeta(uuid)
                .thenApply(meta -> {
                    getServer().getScheduler().runTask(this, () -> applyLobbyTitle(uuid, meta));
                    return meta;
                })
                .thenCompose(meta -> apiClient.updatePlayerLimits(
                        uuid, meta.maxServers(), meta.totalRamMb(), meta.totalCpuCores(), meta.admin(),
                        meta.rank(), meta.prefixJson(), meta.nameColor()))
                .handle((result, error) -> {
                    if (error != null) {
                        getLogger().warning("Synchronisation des limites de " + name + " : " + error.getMessage());
                    }
                    return (Void) null;
                });
    }

    /**
     * Titre réseau dans le lobby (chat, Tab, au-dessus de la tête), comme sur les
     * serveurs de jeu : équipe "mcs_<ordre><rang>" avec le préfixe LuckPerms.
     */
    private void applyLobbyTitle(UUID uuid, LuckPermsHelper.UserMeta meta) {
        org.bukkit.entity.Player player = getServer().getPlayer(uuid);
        if (player == null || getServer().getScoreboardManager() == null) {
            return;
        }
        String rank = meta.rank() == null ? "" : meta.rank().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (rank.isEmpty()) {
            rank = "default";
        }
        int order = meta.admin() ? 1 : "default".equals(rank) ? 9 : 5;
        String name = "mcs_" + order + rank.substring(0, Math.min(10, rank.length()));

        org.bukkit.scoreboard.Scoreboard board = getServer().getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
        }
        String json = meta.prefixJson();
        net.kyori.adventure.text.Component prefix = json == null || json.isEmpty()
                ? net.kyori.adventure.text.Component.empty()
                : net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json);
        net.kyori.adventure.text.format.NamedTextColor color = nameColor(meta.nameColor());
        team.prefix(prefix);
        team.color(color);
        team.addEntry(player.getName());
        // Paper n'utilise pas l'équipe dans le chat : le titre passe par le nom affiché
        player.displayName(prefix.append(net.kyori.adventure.text.Component.text(player.getName(), color)));
    }

    /** "green", "&a", "GOLD"... -> couleur ; gris par défaut (le blanc fatigue) */
    static net.kyori.adventure.text.format.NamedTextColor nameColor(String value) {
        if (value == null || value.isBlank()) {
            return net.kyori.adventure.text.format.NamedTextColor.GRAY;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.length() == 2 && (v.charAt(0) == '&' || v.charAt(0) == '§')) {
            v = switch (v.charAt(1)) {
                case '0' -> "black"; case '1' -> "dark_blue"; case '2' -> "dark_green"; case '3' -> "dark_aqua";
                case '4' -> "dark_red"; case '5' -> "dark_purple"; case '6' -> "gold"; case '7' -> "gray";
                case '8' -> "dark_gray"; case '9' -> "blue"; case 'a' -> "green"; case 'b' -> "aqua";
                case 'c' -> "red"; case 'd' -> "light_purple"; case 'e' -> "yellow"; case 'f' -> "white";
                default -> v;
            };
        }
        net.kyori.adventure.text.format.NamedTextColor c = net.kyori.adventure.text.format.NamedTextColor.NAMES.value(v);
        return c == null ? net.kyori.adventure.text.format.NamedTextColor.GRAY : c;
    }

    @Override
    public void onDisable() {
        getLogger().info("MCSLobbyPlugin arrêté");
    }
}