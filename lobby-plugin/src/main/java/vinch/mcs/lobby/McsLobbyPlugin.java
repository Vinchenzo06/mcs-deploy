package vinch.mcs.lobby;

import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import vinch.mcs.lobby.commands.ServerCommand;
import vinch.mcs.lobby.commands.ServerTabCompleter;
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

        getCommand("mcs").setExecutor(new ServerCommand(this));
        getCommand("mcs").setTabCompleter(new ServerTabCompleter(this));

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
                .thenCompose(meta -> apiClient.updatePlayerLimits(
                        uuid, meta.maxServers(), meta.totalRamMb(), meta.totalCpuCores(), meta.admin()))
                .handle((result, error) -> {
                    if (error != null) {
                        getLogger().warning("Synchronisation des limites de " + name + " : " + error.getMessage());
                    }
                    return (Void) null;
                });
    }

    @Override
    public void onDisable() {
        getLogger().info("MCSLobbyPlugin arrêté");
    }
}