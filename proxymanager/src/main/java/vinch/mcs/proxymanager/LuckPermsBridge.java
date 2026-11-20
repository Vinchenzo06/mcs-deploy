package vinch.mcs.proxymanager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;

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
                return new Meta(1, 1024, 1, false, "default", "", "", null, null, null);
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
                    meta.getMetaValue("name-color") == null ? "" : meta.getMetaValue("name-color"),
                    parseOrNull(meta.getMetaValue("backup-interval")),
                    parseOrNull(meta.getMetaValue("backup-keep-last")),
                    parseOrNull(meta.getMetaValue("backup-keep-weekly")));
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

    @Override
    public CompletableFuture<Settings> settings(UUID uuid) {
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user -> {
            Integer own = null;
            for (MetaNode n : user.getNodes(NodeType.META)) {
                if ("max-servers".equals(n.getMetaKey())) {
                    own = parseOrNull(n.getMetaValue());
                }
            }
            boolean adminGroup = false;
            for (InheritanceNode n : user.getNodes(NodeType.INHERITANCE)) {
                if ("admin".equalsIgnoreCase(n.getGroupName())) {
                    adminGroup = true;
                }
            }
            CachedMetaData meta = user.getCachedData().getMetaData();
            boolean admin = user.getCachedData().getPermissionData().checkPermission("mcs.admin").asBoolean();
            return new Settings(own, parse(meta.getMetaValue("max-servers"), 1), admin, adminGroup);
        });
    }

    @Override
    public CompletableFuture<Void> setMaxServers(UUID uuid, Integer maxServers) {
        return luckPerms.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(NodeType.META.predicate(n -> "max-servers".equals(n.getMetaKey())));
            if (maxServers != null) {
                user.data().add(MetaNode.builder("max-servers", String.valueOf(maxServers)).build());
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

    /** Méta entière facultative (politique de sauvegarde du rôle) */
    private static Integer parseOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
