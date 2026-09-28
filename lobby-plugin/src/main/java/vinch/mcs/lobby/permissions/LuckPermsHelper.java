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
            return CompletableFuture.completedFuture(new UserMeta(1, 1024, 1, false, "default", "", ""));
        }

        return api.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user == null) {
                return new UserMeta(1, 1024, 1, false, "default", "", "");
            }

            CachedMetaData meta = user.getCachedData().getMetaData();

            int maxServers = parseInt(meta.getMetaValue("max-servers"), 1);
            int totalRam = parseInt(meta.getMetaValue("total-ram"), 1024);
            int totalCpu = parseInt(meta.getMetaValue("total-cpu"), 1);

            // Admin MCS : permission mcs.admin (donnée au groupe admin par le kit)
            boolean admin = user.getCachedData().getPermissionData().checkPermission("mcs.admin").asBoolean();

            // Rang réseau : groupe principal et son préfixe, en composant texte JSON
            String prefix = meta.getPrefix();
            String prefixJson = "";
            if (prefix != null && !prefix.isBlank()) {
                net.kyori.adventure.text.Component c = prefix.indexOf('§') >= 0
                        ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(prefix)
                        : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
                prefixJson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(c);
            }

            String nameColor = meta.getMetaValue("name-color");

            return new UserMeta(maxServers, totalRam, totalCpu, admin, user.getPrimaryGroup(), prefixJson,
                    nameColor == null ? "" : nameColor);
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

    public record UserMeta(int maxServers, int totalRamMb, int totalCpuCores, boolean admin, String rank,
                           String prefixJson, String nameColor) {}
}