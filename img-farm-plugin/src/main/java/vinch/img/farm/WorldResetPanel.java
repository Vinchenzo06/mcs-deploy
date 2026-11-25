package vinch.img.farm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Lot 76c : panneaux de réglage de WorldReset dans son monde « limbo » (but, chrono, mort au
 * reset, lieu de départ, JOUER, Nouveau monde). Construits à chaque démarrage du serveur si
 * plugins/ImgFarm/panneaux-worldreset.yml existe (posé par le modèle des serveurs à la demande) :
 * chaque nouveau serveur les a. Les clics passent par la console (commandes /wr de WorldReset),
 * l'état affiché est relu dans la config de WorldReset.
 */
final class WorldResetPanel implements Listener {

    static final String LIMBO = "limbo";

    private record Choice(String label, String type, String value) {}

    private static final List<Choice> GOALS = List.of(
            new Choice("Ender Dragon", "ENTITY", "minecraft:ender_dragon"),
            new Choice("Entrer dans l'End", "PORTAL", "END"),
            new Choice("Entrer au Nether", "PORTAL", "NETHER"),
            new Choice("Tuer le Wither", "ENTITY", "minecraft:wither"),
            new Choice("Un diamant", "ITEM", "diamond"),
            new Choice("Aucun", "NONE", ""));

    private static final List<Choice> STARTS = List.of(
            new Choice("Au hasard", "", ""),
            new Choice("Village", "VILLAGE", ""),
            new Choice("Portail en ruine", "RUINED_PORTAL", ""),
            new Choice("Épave", "SHIPWRECK", ""));

    private final ImgFarmPlugin plugin;
    private final File file;
    private final int x;
    private final int z;
    private Integer y;
    private final BlockFace face;
    /** Position d'un panneau -> action */
    private final Map<String, BiConsumer<Player, Plugin>> actions = new HashMap<>();
    private final Map<String, Block> signs = new HashMap<>();

    private WorldResetPanel(ImgFarmPlugin plugin, File file, YamlConfiguration c) {
        this.plugin = plugin;
        this.file = file;
        this.x = c.getInt("x", 7);
        this.z = c.getInt("z", -9);
        this.y = c.isInt("y") ? c.getInt("y") : null;
        BlockFace f;
        try {
            f = BlockFace.valueOf(c.getString("face", "SOUTH").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            f = BlockFace.SOUTH;
        }
        this.face = switch (f) {
            case NORTH, SOUTH, EAST, WEST -> f;
            default -> BlockFace.SOUTH;
        };
    }

    /** null si le fichier est absent ou si WorldReset n'est pas là */
    static WorldResetPanel load(ImgFarmPlugin plugin) {
        File f = new File(plugin.getDataFolder(), "panneaux-worldreset.yml");
        if (!f.isFile()) return null;
        if (Bukkit.getPluginManager().getPlugin("WorldReset") == null) {
            plugin.getLogger().warning("panneaux-worldreset.yml présent mais WorldReset absent : pas de panneaux");
            return null;
        }
        return new WorldResetPanel(plugin, f, YamlConfiguration.loadConfiguration(f));
    }

    private static Plugin worldReset() {
        Plugin p = Bukkit.getPluginManager().getPlugin("WorldReset");
        return p != null && p.isEnabled() ? p : null;
    }

    private static void console(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private static String key(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** À droite pour un joueur qui regarde le panneau */
    private BlockFace right() {
        return switch (face) {
            case SOUTH -> BlockFace.EAST;
            case NORTH -> BlockFace.WEST;
            case EAST -> BlockFace.NORTH;
            default -> BlockFace.SOUTH;
        };
    }

    // ------------------------------------------------------------------ construction ----

    /** Après le chargement des mondes (WorldReset crée « limbo » à son démarrage) */
    void start() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!build()) {
                plugin.getLogger().warning("Monde « limbo » de WorldReset introuvable : pas de panneaux");
                return;
            }
            Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, 100L);
        }, 40L);
    }

    private boolean build() {
        World w = Bukkit.getWorld(LIMBO);
        if (w == null || worldReset() == null) return false;
        if (y == null) {
            // Une seule fois : le sol à cet endroit (ensuite le mur est là, on garde la hauteur)
            y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
            c.set("y", y);
            try {
                c.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning("panneaux-worldreset.yml : hauteur non enregistrée : " + e.getMessage());
            }
        }
        BlockFace r = right();
        Material wall = Material.STRIPPED_SPRUCE_LOG;
        for (int i = -2; i <= 2; i++) {
            for (int dy = 0; dy <= 2; dy++) {
                w.getBlockAt(x + r.getModX() * i, y + dy, z + r.getModZ() * i).setType(wall, false);
            }
        }
        actions.clear();
        signs.clear();
        // Rangée du haut (à hauteur des yeux)
        place(w, -2, 1, "but", this::cycleGoal);
        place(w, -1, 1, "chrono", this::cycleTimer);
        place(w, 0, 1, "jouer", (p, wr) -> play(p));
        place(w, 1, 1, "mort", this::cycleDeath);
        place(w, 2, 1, "depart", this::cycleStart);
        // Rangée du bas
        place(w, -2, 0, "info", null);
        place(w, 0, 0, "reset", (p, wr) -> newWorld(p));
        place(w, 2, 0, "aide", null);
        refresh();
        plugin.getLogger().info("Panneaux WorldReset posés dans « limbo » (" + x + ", " + y + ", " + z + ", face " + face + ")");
        return true;
    }

    private void place(World w, int col, int row, String id, BiConsumer<Player, Plugin> action) {
        BlockFace r = right();
        Block b = w.getBlockAt(x + r.getModX() * col + face.getModX(), y + row, z + r.getModZ() * col + face.getModZ());
        b.setType(Material.BIRCH_WALL_SIGN, false);
        if (b.getBlockData() instanceof WallSign ws) {
            ws.setFacing(face);
            b.setBlockData(ws, false);
        }
        if (b.getState() instanceof Sign s) {
            s.setWaxed(true); // personne ne peut écrire dessus
            s.update(true, false);
        }
        signs.put(id, b);
        if (action != null) actions.put(key(b), action);
    }

    private void write(String id, Component... lines) {
        Block b = signs.get(id);
        if (b == null || !(b.getState() instanceof Sign s)) return;
        SignSide side = s.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            side.line(i, i < lines.length ? lines[i] : Component.empty());
        }
        s.setWaxed(true);
        s.update(true, false);
    }

    private static Component title(String s) {
        return Component.text(s, NamedTextColor.DARK_BLUE, TextDecoration.BOLD);
    }

    private static Component value(String s) {
        return Component.text(s, NamedTextColor.DARK_GREEN);
    }

    private static Component hint(String s) {
        return Component.text(s, NamedTextColor.GRAY);
    }

    // ------------------------------------------------------------------ état ----

    private static int goalIndex(Plugin wr) {
        String type = wr.getConfig().getString("timer.goal.type", "NONE").toUpperCase(java.util.Locale.ROOT);
        String value = wr.getConfig().getString("timer.goal.value", "");
        for (int i = 0; i < GOALS.size(); i++) {
            Choice c = GOALS.get(i);
            if (c.type().equals(type) && (type.equals("NONE") || c.value().equalsIgnoreCase(value)
                    || ("minecraft:" + value).equalsIgnoreCase(c.value()))) {
                return i;
            }
        }
        return -1;
    }

    private static int startIndex(Plugin wr) {
        String s = wr.getConfig().getString("filter.structure", "").toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < STARTS.size(); i++) {
            if (STARTS.get(i).type().equals(s)) return i;
        }
        return -1;
    }

    void refresh() {
        Plugin wr = worldReset();
        if (wr == null || signs.isEmpty()) return;
        int g = goalIndex(wr);
        write("but", title("But"), value(g >= 0 ? GOALS.get(g).label()
                : wr.getConfig().getString("timer.goal.value", "?")), Component.empty(), hint("clic : changer"));
        boolean timer = wr.getConfig().getBoolean("timer.enabled", false);
        String scope = wr.getConfig().getString("timer.scope", "GLOBAL").toUpperCase(java.util.Locale.ROOT);
        write("chrono", title("Chrono"), value(!timer ? "Aucun" : scope.equals("INDIVIDUAL") ? "Chacun le sien" : "Commun"),
                Component.empty(), hint("clic : changer"));
        write("jouer", Component.empty(), Component.text("▶ JOUER", NamedTextColor.DARK_GREEN, TextDecoration.BOLD),
                hint("partir / reprendre"));
        int d = wr.getConfig().getInt("death-limit", 0);
        write("mort", title("À la mort"), value(d <= 0 ? "On continue" : d == 1 ? "Monde neuf" : d + " vies"),
                Component.empty(), hint("clic : changer"));
        int st = startIndex(wr);
        write("depart", title("Départ"), value(st >= 0 ? STARTS.get(st).label()
                : wr.getConfig().getString("filter.structure", "?")), hint("au prochain monde"), hint("clic : changer"));
        write("info", title("WorldReset"), hint("Réglages ici,"), hint("puis ▶ JOUER"));
        write("reset", Component.empty(), Component.text("⟳ Nouveau", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("monde", NamedTextColor.DARK_RED, TextDecoration.BOLD), hint("+ chrono à zéro"));
        write("aide", title("Revenir ici"), value("/limbo"), hint("(ta partie est"), hint("gardée)"));
    }

    // ------------------------------------------------------------------ actions ----

    private void announce(Player p, String what) {
        Component msg = Component.text("» ", NamedTextColor.DARK_AQUA)
                .append(Component.text(p.getName() + " : " + what, NamedTextColor.GRAY));
        for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(msg);
    }

    private void cycleGoal(Player p, Plugin wr) {
        Choice c = GOALS.get((goalIndex(wr) + 1) % GOALS.size());
        console(c.type().equals("NONE") ? "wr timer goal NONE" : "wr timer goal " + c.type() + " " + c.value());
        announce(p, "but → " + c.label());
    }

    private void cycleTimer(Player p, Plugin wr) {
        boolean timer = wr.getConfig().getBoolean("timer.enabled", false);
        String scope = wr.getConfig().getString("timer.scope", "GLOBAL").toUpperCase(java.util.Locale.ROOT);
        if (!timer) {
            console("wr timer enable");
            console("wr timer scope INDIVIDUAL");
            announce(p, "chrono → chacun le sien");
        } else if (scope.equals("INDIVIDUAL")) {
            console("wr timer scope GLOBAL");
            announce(p, "chrono → commun");
        } else {
            console("wr timer disable");
            announce(p, "chrono → aucun");
        }
    }

    private void cycleDeath(Player p, Plugin wr) {
        boolean on = wr.getConfig().getInt("death-limit", 0) > 0;
        console("wr death " + (on ? 0 : 1));
        announce(p, on ? "à la mort → on continue" : "à la mort → monde neuf");
    }

    private void cycleStart(Player p, Plugin wr) {
        Choice c = STARTS.get((startIndex(wr) + 1) % STARTS.size());
        console(c.type().isEmpty() ? "wr filter structure CLEAR" : "wr filter structure " + c.type());
        announce(p, "départ → " + c.label() + " (au prochain monde : ⟳ Nouveau monde)");
    }

    private void play(Player p) {
        console("wr start");
    }

    private void newWorld(Player p) {
        announce(p, "nouveau monde !");
        console("wr reset");
    }

    /** /limbo : retour aux panneaux, la partie en cours est gardée par WorldReset */
    boolean toLimbo(Player p) {
        if (worldReset() == null) return false;
        if (p.getWorld().getName().equals(LIMBO)) {
            p.sendMessage(ImgFarmPlugin.info("Tu es déjà au limbo : règle les panneaux puis ▶ JOUER."));
            return true;
        }
        console("wr limbo " + p.getName());
        return true;
    }

    // ------------------------------------------------------------------ événements ----

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        BiConsumer<Player, Plugin> action = actions.get(key(e.getClickedBlock()));
        if (action == null || !e.getClickedBlock().getWorld().getName().equals(LIMBO)) return;
        e.setCancelled(true);
        Plugin wr = worldReset();
        if (wr == null) return;
        action.accept(e.getPlayer(), wr);
        Bukkit.getScheduler().runTaskLater(plugin, this::refresh, 2L);
    }

    private void welcome(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !p.getWorld().getName().equals(LIMBO)) return;
            p.showTitle(Title.title(Component.text("WorldReset", NamedTextColor.GOLD),
                    Component.text("Choisis tes réglages sur les panneaux, puis ▶ JOUER", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
        }, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        welcome(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        welcome(e.getPlayer());
    }
}
