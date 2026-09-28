package vinch.mcs.proxymanager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Lecture de LuckPerms sur le proxy (même base PostgreSQL que le lobby).
 * Chargée uniquement si LuckPerms est présent (voir PlayerSync).
 */
public class LuckPermsBridge implements RankSource {

    private final LuckPerms luckPerms = LuckPermsProvider.get();

    @Override
    public CompletableFuture<Meta> load(UUID uuid) {
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user == null) {
                return new Meta(1, 1024, 1, false, "default", "", "");
            }
            CachedMetaData meta = user.getCachedData().getMetaData();
            boolean admin = user.getCachedData().getPermissionData().checkPermission("mcs.admin").asBoolean();
            return new Meta(
                    parse(meta.getMetaValue("max-servers"), 1),
                    parse(meta.getMetaValue("total-ram"), 1024),
                    parse(meta.getMetaValue("total-cpu"), 1),
                    admin,
                    user.getPrimaryGroup(),
                    toJson(meta.getPrefix()),
                    meta.getMetaValue("name-color") == null ? "" : meta.getMetaValue("name-color"));
        });
    }

    @Override
    public CompletableFuture<Void> setGroup(UUID uuid, String group, boolean member) {
        return luckPerms.getUserManager().modifyUser(uuid, user -> {
            InheritanceNode node = InheritanceNode.builder(group).build();
            if (member) {
                user.data().add(node);
            } else {
                user.data().remove(node);
            }
        });
    }

    /** "&c[Admin] " (codes & ou §) -> composant texte JSON, "" si pas de préfixe */
    static String toJson(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return "";
        }
        Component c = legacy.indexOf('§') >= 0
                ? LegacyComponentSerializer.legacySection().deserialize(legacy)
                : LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        return GsonComponentSerializer.gson().serialize(c);
    }

    private static int parse(String value, int def) {
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
