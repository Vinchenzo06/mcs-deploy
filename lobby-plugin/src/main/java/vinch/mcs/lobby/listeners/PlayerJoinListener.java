package vinch.mcs.lobby.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import vinch.mcs.lobby.McsLobbyPlugin;

public class PlayerJoinListener implements Listener {

    private final McsLobbyPlugin plugin;

    public PlayerJoinListener(McsLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();

        plugin.getLogger().info("PlayerJoinEvent déclenché pour " + player.getName());

        plugin.getLuckPermsHelper().loadUserMeta(player.getUniqueId())
                .whenComplete((meta, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("Erreur chargement meta LuckPerms pour "
                                + player.getName() + " : " + error.getMessage());
                        return;
                    }

                    plugin.getLogger().info("Joueur " + player.getName()
                            + " : max-servers=" + meta.maxServers()
                            + " total-ram=" + meta.totalRamMb()
                            + " total-cpu=" + meta.totalCpuCores());

                    plugin.getApiClient().updatePlayerLimits(
                                    player.getUniqueId(),
                                    meta.maxServers(),
                                    meta.totalRamMb(),
                                    meta.totalCpuCores())
                            .whenComplete((result, err) -> {
                                if (err != null) {
                                    plugin.getLogger().warning("Erreur sync limites pour "
                                            + player.getName() + " : " + err.getMessage());
                                } else {
                                    plugin.getLogger().info("Limites synchronisées pour "
                                            + player.getName());
                                }
                            });
                });
    }
}