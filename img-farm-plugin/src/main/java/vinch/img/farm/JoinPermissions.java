package vinch.img.farm;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lot 76b : permissions données à chaque joueur qui entre, lues dans
 * plugins/ImgFarm/permissions-joueurs.yml (posé par le modèle des serveurs à la demande :
 * ex. WorldReset — choisir son but, relancer un monde). Sans ce fichier : rien n'est donné.
 * Seuls les membres de la team entrent sur ces serveurs (contrôle d'accès du proxy).
 */
final class JoinPermissions implements Listener {

    private static final Pattern NODE = Pattern.compile("^[a-z0-9_.*-]{1,64}$");

    private final ImgFarmPlugin plugin;
    private final List<String> nodes;

    private JoinPermissions(ImgFarmPlugin plugin, List<String> nodes) {
        this.plugin = plugin;
        this.nodes = nodes;
    }

    /** null si le fichier est absent ou vide */
    static JoinPermissions load(ImgFarmPlugin plugin) {
        File f = new File(plugin.getDataFolder(), "permissions-joueurs.yml");
        if (!f.isFile()) return null;
        List<String> nodes = YamlConfiguration.loadConfiguration(f).getStringList("permissions").stream()
                .map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(s -> NODE.matcher(s).matches() && !s.equals("*"))
                .distinct().toList();
        if (nodes.isEmpty()) return null;
        plugin.getLogger().info("Permissions données aux joueurs : " + String.join(", ", nodes));
        return new JoinPermissions(plugin, nodes);
    }

    void applyOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) give(p);
    }

    private void give(Player p) {
        PermissionAttachment a = p.addAttachment(plugin);
        for (String n : nodes) a.setPermission(n, true);
        p.recalculatePermissions();
        p.updateCommands();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        give(e.getPlayer());
    }
}
