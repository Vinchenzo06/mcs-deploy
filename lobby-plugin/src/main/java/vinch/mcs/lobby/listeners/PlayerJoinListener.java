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
        // Quotas LuckPerms -> API (aussi refait avant /mcs create et /mcs quota)
        plugin.syncPlayerLimits(player.getUniqueId(), player.getName());
    }
}
