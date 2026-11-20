package vinch.img.farm;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * Comptage des items d'une session.
 *  - À la main : items ramassés + (sortis − remis) des coffres, fours, trémies et villageois,
 *    mesuré sur l'inventaire du joueur entre l'ouverture et la fermeture de l'interface.
 *  - Ferme auto : ce qui entre dans les coffres de collecte (trémies, droppers, ramassage
 *    d'une trémie au sol) + ce que le joueur ramasse.
 * Pause automatique quand le joueur quitte le serveur.
 */
final class FarmListener implements Listener {

    /** Interfaces dont la sortie compte (pas l'établi : fabriquer n'est pas farmer) */
    private static final Set<InventoryType> COUNTED = EnumSet.of(
            InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX, InventoryType.ENDER_CHEST,
            InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER,
            InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER,
            InventoryType.BREWING, InventoryType.MERCHANT);

    private final ImgFarmPlugin plugin;

    FarmListener(ImgFarmPlugin plugin) {
        this.plugin = plugin;
    }

    private FarmSession running(Player p) {
        FarmSession s = plugin.sessions.get(p.getUniqueId());
        return s != null && s.kind == FarmSession.Kind.FARM && s.running() ? s : null;
    }

    private void add(Player p, FarmSession s, long amount) {
        if (amount == 0) return;
        s.quantity += amount;
        if (s.targetReached()) {
            plugin.end(p, s, "Objectif atteint !");
        } else {
            plugin.updateBar(s);
        }
    }

    private static int countIn(Player p, Material type) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == type) n += it.getAmount();
        }
        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && cursor.getType() == type) n += cursor.getAmount();
        return n;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        FarmSession s = running(p);
        if (s == null) return;
        ItemStack stack = event.getItem().getItemStack();
        if (stack.getType() != s.item) return;
        int amount = stack.getAmount() - event.getRemaining();
        if (amount <= 0) return;
        if (s.countAtOpen >= 0) s.pickupsSinceOpen += amount;
        add(p, s, amount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        FarmSession s = running(p);
        if (s == null || s.auto || !COUNTED.contains(event.getInventory().getType())) return;
        s.countAtOpen = countIn(p, s.item);
        s.pickupsSinceOpen = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        FarmSession s = plugin.sessions.get(p.getUniqueId());
        if (s == null || s.countAtOpen < 0) return;
        // Le tick suivant : l'item tenu au curseur est revenu dans l'inventaire
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (s.countAtOpen < 0) return;
            int before = s.countAtOpen;
            s.countAtOpen = -1;
            if (!p.isOnline() || !s.running()) return;
            long moved = countIn(p, s.item) - before - s.pickupsSinceOpen;
            add(p, s, moved);
        });
    }

    /** Ferme auto : ce qu'une trémie ou un dropper fait entrer dans un coffre de collecte */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (plugin.sessions.isEmpty()) return;
        ItemStack it = event.getItem();
        String key = null;
        for (FarmSession s : plugin.sessions.values()) {
            if (!s.auto || !s.running() || it.getType() != s.item) continue;
            if (key == null) key = ImgFarmPlugin.key(event.getDestination());
            if (key != null && s.containers.contains(key)) {
                Player p = plugin.getServer().getPlayer(s.player);
                if (p != null) add(p, s, it.getAmount());
            }
        }
    }

    /** Ferme auto : une trémie de collecte qui ramasse au sol */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (plugin.sessions.isEmpty()) return;
        ItemStack it = event.getItem().getItemStack();
        String key = null;
        for (FarmSession s : plugin.sessions.values()) {
            if (!s.auto || !s.running() || it.getType() != s.item) continue;
            if (key == null) key = ImgFarmPlugin.key(event.getInventory());
            if (key != null && s.containers.contains(key)) {
                Player p = plugin.getServer().getPlayer(s.player);
                if (p != null) add(p, s, it.getAmount());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        FarmSession s = plugin.sessions.get(event.getPlayer().getUniqueId());
        if (s == null || s.finished) return;
        s.countAtOpen = -1;
        s.pause();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        FarmSession s = plugin.sessions.get(p.getUniqueId());
        if (s == null) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (s.finished) {
                p.sendMessage(ImgFarmPlugin.info("Ta dernière session de farm n'est pas envoyée :"));
                plugin.summary(p, s);
            } else {
                plugin.showBar(p, s);
                p.sendMessage(ImgFarmPlugin.info("Ta session de farm est en pause depuis ton départ : /farm reprendre pour continuer."));
            }
        }, 40L);
    }
}
