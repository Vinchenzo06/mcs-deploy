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
import net.luckperms.api.node.types.WeightNode;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.GroupManager;

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
                return new Meta(1, 1024, 1, false, "default", "", "", null, null, null, false);
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
                    parseOrNull(meta.getMetaValue("backup-keep-weekly")),
                    ownGroup(user, "img"));
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

    private static final java.util.List<String> QUOTA_KEYS = java.util.List.of(MAX_SERVERS, TOTAL_RAM, TOTAL_CPU);

    private static Quotas quotasOf(java.util.Collection<MetaNode> nodes) {
        Integer[] v = new Integer[3];
        for (MetaNode n : nodes) {
            int i = QUOTA_KEYS.indexOf(n.getMetaKey());
            if (i >= 0) {
                v[i] = parseOrNull(n.getMetaValue());
            }
        }
        return new Quotas(v[0], v[1], v[2]);
    }

    /** Remplace les méta de quotas données (valeur null = retirée) */
    private static void applyQuotas(NodeMap data, java.util.Map<String, Integer> set) {
        for (java.util.Map.Entry<String, Integer> e : set.entrySet()) {
            String key = e.getKey();
            if (!QUOTA_KEYS.contains(key)) {
                continue;
            }
            data.clear(NodeType.META.predicate(n -> key.equals(n.getMetaKey())));
            if (e.getValue() != null) {
                data.add(MetaNode.builder(key, String.valueOf(e.getValue())).build());
            }
        }
    }

    private static boolean ownGroup(net.luckperms.api.model.user.User user, String group) {
        for (InheritanceNode n : user.getNodes(NodeType.INHERITANCE)) {
            if (group.equalsIgnoreCase(n.getGroupName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<Settings> settings(UUID uuid) {
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user -> {
            CachedMetaData meta = user.getCachedData().getMetaData();
            boolean admin = user.getCachedData().getPermissionData().checkPermission("mcs.admin").asBoolean();
            return new Settings(quotasOf(user.getNodes(NodeType.META)),
                    parse(meta.getMetaValue(MAX_SERVERS), 1), parse(meta.getMetaValue(TOTAL_RAM), 1024),
                    parse(meta.getMetaValue(TOTAL_CPU), 1), admin, ownGroup(user, "admin"), ownGroup(user, "img"));
        });
    }

    @Override
    public CompletableFuture<Void> setUserQuotas(UUID uuid, java.util.Map<String, Integer> set) {
        return luckPerms.getUserManager().modifyUser(uuid, user -> applyQuotas(user.data(), set));
    }

    @Override
    public CompletableFuture<Void> ensureGroup(String group, int weight) {
        GroupManager groups = luckPerms.getGroupManager();
        return groups.loadGroup(group).thenCompose(existing -> existing.isPresent()
                ? CompletableFuture.completedFuture((Void) null)
                : groups.createAndLoadGroup(group).thenCompose(g ->
                        groups.modifyGroup(group, gg -> gg.data().add(WeightNode.builder(weight).build()))));
    }

    @Override
    public CompletableFuture<Quotas> groupQuotas(String group) {
        return luckPerms.getGroupManager().loadGroup(group).thenApply(g ->
                g.map(x -> quotasOf(x.getNodes(NodeType.META))).orElse(new Quotas(null, null, null)));
    }

    @Override
    public CompletableFuture<Void> setGroupQuotas(String group, java.util.Map<String, Integer> set) {
        return luckPerms.getGroupManager().modifyGroup(group, g -> applyQuotas(g.data(), set));
    }

    @Override
    public CompletableFuture<Boolean> inGroup(UUID uuid, String group) {
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user -> ownGroup(user, group));
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
