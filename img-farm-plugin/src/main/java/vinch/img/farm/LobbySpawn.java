package vinch.img.farm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;

/**
 * Lot 77 : spawn du lobby, sans EssentialsX. /setspawn (op) note la position dans
 * plugins/ImgFarm/spawn.yml ; ensuite, sur ce serveur seulement : arrivée au spawn à chaque
 * connexion, réapparition au spawn après une mort, chute dans le vide = retour au spawn, /spawn.
 * Sans spawn.yml (les autres serveurs), rien ne change.
 */
final class LobbySpawn implements Listener {

    private final ImgFarmPlugin plugin;
    private final File file;
    private Location spawn;
    private boolean onJoin = true;
    private boolean onRespawn = true;
    private boolean noVoid = true;

    LobbySpawn(ImgFarmPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawn.yml");
    }

    /** Après le chargement des mondes */
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        World w = Bukkit.getWorld(c.getString("world", ""));
        if (w == null) {
            plugin.getLogger().warning("spawn.yml : monde « " + c.getString("world") + " » introuvable");
            return;
        }
        spawn = new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
        onJoin = c.getBoolean("a-chaque-connexion", true);
        onRespawn = c.getBoolean("apres-une-mort", true);
        noVoid = c.getBoolean("vide-ramene-au-spawn", true);
        plugin.getLogger().info("Spawn du serveur : " + w.getName() + " " + Math.round(spawn.getX()) + " "
                + Math.round(spawn.getY()) + " " + Math.round(spawn.getZ())
                + (onJoin ? " (à chaque connexion)" : ""));
    }

    boolean set(Player p) {
        Location l = p.getLocation();
        YamlConfiguration c = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        c.options().setHeader(java.util.List.of(
                "Lot 77 : spawn de ce serveur (/setspawn). Supprimer ce fichier = plus de spawn imposé.",
                "a-chaque-connexion : arriver ici à chaque connexion ; apres-une-mort : réapparaître ici ;",
                "vide-ramene-au-spawn : tomber dans le vide ramène ici (sans dégâts)."));
        c.set("world", l.getWorld().getName());
        c.set("x", Math.floor(l.getX()) + 0.5);
        c.set("y", l.getY());
        c.set("z", Math.floor(l.getZ()) + 0.5);
        c.set("yaw", (double) Math.round(l.getYaw() / 45f) * 45);
        c.set("pitch", 0.0);
        if (!c.contains("a-chaque-connexion")) c.set("a-chaque-connexion", true);
        if (!c.contains("apres-une-mort")) c.set("apres-une-mort", true);
        if (!c.contains("vide-ramene-au-spawn")) c.set("vide-ramene-au-spawn", true);
        try {
            c.save(file);
        } catch (Exception e) {
            p.sendMessage(ImgFarmPlugin.error("Spawn non enregistré : " + e.getMessage()));
            return false;
        }
        load();
        // Aussi le spawn du monde (boussoles, nouveaux joueurs si le fichier disparaît)
        l.getWorld().setSpawnLocation(spawn);
        return true;
    }

    boolean teleport(Player p) {
        if (spawn == null) return false;
        p.teleport(spawn);
        p.setFallDistance(0);
        return true;
    }

    boolean active() {
        return spawn != null;
    }

    static boolean canSet(CommandSender s) {
        return s.isOp() || s.hasPermission("imgfarm.spawn.set");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        if (spawn == null || !onJoin) return;
        Player p = e.getPlayer();
        // Au tick suivant : passe après les autres plugins qui placent le joueur
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) teleport(p);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (spawn != null && onRespawn) e.setRespawnLocation(spawn);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoid(EntityDamageEvent e) {
        if (spawn == null || !noVoid || e.getCause() != EntityDamageEvent.DamageCause.VOID) return;
        if (!(e.getEntity() instanceof Player p)) return;
        e.setCancelled(true);
        teleport(p);
    }
}
