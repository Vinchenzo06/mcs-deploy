package vinch.mcs.lobby.permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LuckPermsHelper {

    private LuckPerms api;
    private boolean enabled = false;

    public void init() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            enabled = false;
            return;
        }

        try {
            api = LuckPermsProvider.get();
            enabled = true;
        } catch (Exception e) {
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Charge les meta du joueur de manière async.
     * Force le chargement complet pour s'assurer que tout est dispo.
     */
    public CompletableFuture<UserMeta> loadUserMeta(UUID uuid) {
        if (!enabled) {
            return CompletableFuture.completedFuture(new UserMeta(1, 1024, 1));
        }

        return api.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user == null) {
                return new UserMeta(1, 1024, 1);
            }

            CachedMetaData meta = user.getCachedData().getMetaData();

            int maxServers = parseInt(meta.getMetaValue("max-servers"), 1);
            int totalRam = parseInt(meta.getMetaValue("total-ram"), 1024);
            int totalCpu = parseInt(meta.getMetaValue("total-cpu"), 1);

            return new UserMeta(maxServers, totalRam, totalCpu);
        });
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record UserMeta(int maxServers, int totalRamMb, int totalCpuCores) {}
}