package vinch.img.farm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Menu en jeu (lot 60) : /menu, /farm seul, /mcs seul (demandé par le proxy) et, dans le lobby
 * IMG, une boussole dans la hotbar. Les données viennent du proxy (canal img:farm).
 *
 * Le proxy n'exécute pour le menu que des actions sans risque (rejoindre, démarrer, lire) :
 * pour tout ce qui modifie ou détruit, le menu se ferme et propose un bouton « Confirmer » dans
 * le chat, qui envoie la vraie commande /mcs depuis le client du joueur. Les textes à écrire
 * (nom, version, commande, item) se tapent dans une enclume.
 */
final class Menu implements Listener {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{3,32}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9][0-9A-Za-z.\\-]{0,19}$");
    private static final String[][] TYPES = {
            {"paper", "Paper", "PAPER", "Plugins ; le plugin img-farm est installé d'office"},
            {"spigot", "Spigot", "STRING", "Plugins ; le plugin img-farm est installé d'office"},
            {"fabric", "Fabric", "LOOM", "Mods Fabric"},
            {"forge", "Forge", "ANVIL", "Mods Forge"},
            {"neoforge", "NeoForge", "SMITHING_TABLE", "Mods NeoForge"},
    };

    private final ImgFarmPlugin plugin;
    private final Bridge bridge;
    private final NamespacedKey key;
    private final Map<UUID, Consumer<String>> asking = new ConcurrentHashMap<>();
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    /** null : pas encore demandé au proxy ; ensuite : ce serveur est-il le lobby IMG ? */
    private volatile Boolean lobby;
    private volatile String site;

    /** Serveur en cours de création */
    private static final class Draft {
        String type;
        String typeLabel;
        String version;
        int ramGb;
        int cpu;
        JsonObject quota;
    }

    /** Un menu (coffre) : chaque case peut avoir une action */
    static final class Gui implements InventoryHolder {
        final Inventory inv;
        final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();

        Gui(int rows, String title) {
            inv = Bukkit.createInventory(this, rows * 9, Component.text(title, NamedTextColor.DARK_GRAY));
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        void set(int slot, ItemStack item, Consumer<ClickType> action) {
            inv.setItem(slot, item);
            if (action != null) actions.put(slot, action);
            else actions.remove(slot);
        }
    }

    Menu(ImgFarmPlugin plugin, Bridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.key = new NamespacedKey(plugin, "menu");
    }

    // ---------- Objets ----------

    static ItemStack item(Material m, String name, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m == null ? Material.PAPER : m, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Component.text(l, l.startsWith("»") ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack back() {
        return item(Material.ARROW, "← Retour", NamedTextColor.WHITE);
    }

    private void open(Player p, Gui gui) {
        p.openInventory(gui.getInventory());
    }

    private static String str(JsonObject o, String k, String d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    private static long lng(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
    }

    private static boolean has(JsonObject o, String right) {
        if (o == null || !o.has("rights")) return false;
        for (JsonElement e : o.getAsJsonArray("rights")) if (right.equals(e.getAsString())) return true;
        return false;
    }

    private static String gb(long mb) {
        return mb % 1024 == 0 ? (mb / 1024) + " Go" : String.format(Locale.FRANCE, "%.1f Go", mb / 1024.0);
    }

    /** Bouton « Confirmer » dans le chat : le client du joueur enverra lui-même la commande */
    private void confirm(Player p, String what, String label, String command, NamedTextColor color) {
        p.closeInventory();
        p.sendMessage(ImgFarmPlugin.info(what));
        p.sendMessage(Component.text("  ")
                .append(Component.text("[✔ " + label + "]", color)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(command))))
                .append(Component.text("  "))
                .append(Component.text("[Menu]", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/menu"))
                        .hoverEvent(HoverEvent.showText(Component.text("Revenir au menu")))));
    }

    /** Action sans risque faite par le proxy (comme /mcs du joueur) */
    private void act(Player p, String cmd, String ref) {
        p.closeInventory();
        JsonObject d = new JsonObject();
        d.addProperty("cmd", cmd);
        d.addProperty("ref", ref == null ? "" : ref);
        bridge.send(p, "act", d, r -> {
            if (!r.ok() && p.isOnline()) p.sendMessage(ImgFarmPlugin.error(r.error()));
        });
    }

    // ---------- Texte à écrire : une enclume ----------

    void ask(Player p, String title, String initial, Consumer<String> done) {
        p.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            AnvilView view = MenuType.ANVIL.create(p, Component.text(title));
            asking.put(p.getUniqueId(), done);
            p.openInventory(view);
            view.getTopInventory().setItem(0, item(Material.PAPER, initial == null || initial.isBlank() ? "…" : initial,
                    NamedTextColor.WHITE));
            p.sendMessage(ImgFarmPlugin.info("Écris dans le champ en haut de l'enclume, puis clique le papier à droite."));
        });
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        if (!(e.getView().getPlayer() instanceof Player p) || !asking.containsKey(p.getUniqueId())) return;
        String text = e.getView().getRenameText();
        e.setResult(item(Material.PAPER, text == null || text.isBlank() ? "…" : text, NamedTextColor.GREEN, "» Clique pour valider"));
        e.getView().setRepairCost(0);
    }

    // ---------- Clics ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() instanceof AnvilView view && asking.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            if (e.getRawSlot() == 2) {
                String text = view.getRenameText();
                Consumer<String> done = asking.remove(p.getUniqueId());
                view.getTopInventory().clear();
                p.closeInventory();
                if (done != null) {
                    String t = text == null ? "" : text.trim();
                    plugin.getServer().getScheduler().runTask(plugin, () -> done.accept(t.equals("…") ? "" : t));
                }
            }
            return;
        }
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui gui)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getRawSlot() >= gui.inv.getSize()) return;
        Consumer<ClickType> action = gui.actions.get(e.getRawSlot());
        if (action != null) {
            ClickType click = e.getClick();
            plugin.getServer().getScheduler().runTask(plugin, () -> action.accept(click));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getView() instanceof AnvilView view && e.getPlayer() instanceof Player p && asking.remove(p.getUniqueId()) != null) {
            view.getTopInventory().clear(); // le papier n'est jamais rendu au joueur
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        asking.remove(e.getPlayer().getUniqueId());
        drafts.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Boussole du lobby IMG ----------

    private ItemStack compass() {
        ItemStack it = item(Material.COMPASS, "Menu IMG", NamedTextColor.GOLD, "» Clic droit : tes serveurs, la farm, créer un serveur");
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isCompass(ItemStack it) {
        if (it == null || it.getType() != Material.COMPASS || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void giveCompass(Player p) {
        ItemStack current = p.getInventory().getItem(8);
        if (current == null || current.getType() == Material.AIR || isCompass(current)) {
            p.getInventory().setItem(8, compass());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Le proxy ne répond qu'une fois le joueur bien arrivé : on attend un peu
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (lobby != null) {
                if (lobby) giveCompass(p);
                return;
            }
            bridge.send(p, "context", new JsonObject(), r -> {
                if (!r.ok()) return;
                lobby = r.body().has("lobby") && r.body().get("lobby").getAsBoolean();
                site = str(r.body(), "site", null);
                if (lobby && p.isOnline()) giveCompass(p);
            });
        }, 40L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (Boolean.TRUE.equals(lobby)) {
            Player p = e.getPlayer();
            plugin.getServer().getScheduler().runTask(plugin, () -> giveCompass(p));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isCompass(e.getItem()) || !e.getAction().isRightClick()) return;
        e.setCancelled(true);
        main(e.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isCompass(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    /** Le proxy demande d'ouvrir un menu (/mcs seul) : on l'ouvre et on le lui confirme */
    void openFromProxy(Player p, String which, long push) {
        if ("servers".equals(which)) servers(p);
        else main(p);
        JsonObject d = new JsonObject();
        d.addProperty("push", push);
        bridge.send(p, "opened", d, r -> { });
    }

    // ---------- Menu principal ----------

    void main(Player p) {
        Gui g = new Gui(3, "Menu IMG");
        g.set(10, item(Material.ENDER_CHEST, "Mes serveurs", NamedTextColor.AQUA,
                "Les serveurs de la team et les tiens :", "rejoindre, démarrer, arrêter,", "console, sauvegardes…", "", "» Clic : ouvrir"), c -> servers(p));
        g.set(12, item(Material.NETHER_STAR, "Créer un serveur", NamedTextColor.GREEN,
                "Type, version, RAM, CPU et nom", "en quelques clics.", "", "» Clic : commencer"), c -> createType(p));
        FarmSession s = plugin.sessions.get(p.getUniqueId());
        g.set(14, item(Material.DIAMOND_HOE, "Farm", NamedTextColor.GOLD,
                s == null ? "Mesurer une session de farm" : s.finished ? "Session terminée : à envoyer" : "Session en cours : " + (s.itemName == null ? "construction" : s.itemName),
                "ou voir les meilleures méthodes.", "", "» Clic : ouvrir"), c -> farm(p));
        g.set(16, item(Material.WRITABLE_BOOK, "Site de la team", NamedTextColor.LIGHT_PURPLE,
                "Méthodes, sessions, fichiers", "et sauvegardes des serveurs.", "", "» Clic : lien dans le chat"), c -> {
            p.closeInventory();
            String url = site != null ? site : "https://img.asearia.ca";
            p.sendMessage(ImgFarmPlugin.info("Site de la team : ").append(Component.text(url, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(url)).hoverEvent(HoverEvent.showText(Component.text("Ouvrir")))));
        });
        g.set(22, item(Material.PAPER, "Mon quota", NamedTextColor.WHITE, "Chargement…"), null);
        open(p, g);
        bridge.send(p, "servers", new JsonObject(), r -> {
            if (!r.ok() || p.getOpenInventory().getTopInventory().getHolder() != g) return;
            JsonObject q = r.body().has("quota") ? r.body().getAsJsonObject("quota") : null;
            if (q == null) return;
            g.set(22, item(Material.PAPER, "Mon quota", NamedTextColor.WHITE,
                    "Serveurs : " + lng(q, "serversUsed") + " / " + lng(q, "maxServers"),
                    "RAM : " + gb(lng(q, "ramUsedMb")) + " / " + gb(lng(q, "totalRamMb")),
                    "CPU : " + lng(q, "cpuUsedCores") + " / " + lng(q, "totalCpuCores")), null);
        });
    }

    // ---------- Serveurs ----------

    private static Material statusItem(String status) {
        return switch (status) {
            case "RUNNING" -> Material.LIME_CONCRETE;
            case "STOPPED" -> Material.RED_CONCRETE;
            case "ERROR" -> Material.GRAY_CONCRETE;
            default -> Material.YELLOW_CONCRETE;
        };
    }

    private static String statusText(JsonObject s) {
        return switch (str(s, "status", "")) {
            case "RUNNING" -> "● En marche · " + lng(s, "online") + " joueur" + (lng(s, "online") > 1 ? "s" : "");
            case "STOPPED" -> "○ Arrêté";
            case "STARTING" -> "◐ Démarrage…";
            case "STOPPING" -> "◐ Arrêt…";
            case "CREATING" -> "◐ Création…";
            case "MIGRATING" -> "◐ Changement de machine…";
            case "ERROR" -> "✖ Erreur";
            default -> str(s, "status", "?");
        };
    }

    private static String relation(JsonObject s) {
        return switch (str(s, "relation", "")) {
            case "proprietaire" -> "à toi";
            case "equipe" -> "équipe IMG";
            case "admin" -> "admin";
            default -> str(s, "relation", "");
        };
    }

    void servers(Player p) {
        Gui g = new Gui(6, "Mes serveurs");
        g.set(22, item(Material.CLOCK, "Chargement…", NamedTextColor.GRAY), null);
        g.set(45, back(), c -> main(p));
        g.set(49, item(Material.NETHER_STAR, "Créer un serveur", NamedTextColor.GREEN), c -> createType(p));
        g.set(53, item(Material.SUNFLOWER, "Actualiser", NamedTextColor.WHITE), c -> servers(p));
        open(p, g);
        bridge.send(p, "servers", new JsonObject(), r -> {
            if (p.getOpenInventory().getTopInventory().getHolder() != g) return;
            g.set(22, null, null);
            if (!r.ok()) {
                g.set(22, item(Material.BARRIER, "Erreur", NamedTextColor.RED, r.error()), null);
                return;
            }
            JsonArray list = r.body().getAsJsonArray("servers");
            if (list == null || list.isEmpty()) {
                g.set(22, item(Material.BARRIER, "Aucun serveur", NamedTextColor.GRAY, "Crée le premier avec l'étoile en bas."), null);
                return;
            }
            int slot = 0;
            for (JsonElement el : list) {
                if (slot >= 45) break;
                JsonObject s = el.getAsJsonObject();
                long id = lng(s, "id");
                g.set(slot++, item(statusItem(str(s, "status", "")), str(s, "name", "?"), NamedTextColor.WHITE,
                        "de " + str(s, "ownerUsername", "?") + " · " + relation(s),
                        cap(str(s, "serverType", "")) + " " + str(s, "minecraftVersion", "") + " · "
                                + gb(lng(s, "allocatedRamMb")) + " · " + lng(s, "allocatedCpuCores") + " CPU",
                        statusText(s), "", "» Clic : gérer"), c -> server(p, id));
            }
        });
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    void server(Player p, long id) {
        Gui g = new Gui(4, "Serveur");
        g.set(13, item(Material.CLOCK, "Chargement…", NamedTextColor.GRAY), null);
        g.set(27, back(), c -> servers(p));
        open(p, g);
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        bridge.send(p, "server", d, r -> {
            if (p.getOpenInventory().getTopInventory().getHolder() != g) return;
            g.set(13, null, null);
            if (!r.ok()) {
                g.set(13, item(Material.BARRIER, "Erreur", NamedTextColor.RED, r.error()), null);
                return;
            }
            fillServer(p, g, r.body());
        });
    }

    private void fillServer(Player p, Gui g, JsonObject s) {
        long id = lng(s, "id");
        String name = str(s, "name", "?");
        String ref = str(s, "ref", name);
        String st = str(s, "status", "");
        boolean running = st.equals("RUNNING");
        boolean stopped = st.equals("STOPPED") || st.equals("ERROR");
        boolean isProtected = s.has("protected") && s.get("protected").getAsBoolean();
        boolean canBackup = s.has("canBackup") && s.get("canBackup").getAsBoolean();
        List<String> info = new ArrayList<>(List.of(
                "de " + str(s, "ownerUsername", "?") + " · " + relation(s),
                cap(str(s, "serverType", "")) + " " + str(s, "minecraftVersion", ""),
                statusText(s)));
        if (running && s.has("cpuPercent")) info.add("CPU : " + Math.round(s.get("cpuPercent").getAsDouble()) + " % de " + lng(s, "allocatedCpuCores") + " cœur(s)");
        if (running && s.has("memUsedMb")) info.add("Mémoire : " + gb(lng(s, "memUsedMb")) + " / " + gb(lng(s, "memLimitMb") > 0 ? lng(s, "memLimitMb") : lng(s, "allocatedRamMb")));
        if (s.has("diskUsedMb")) info.add("Disque : " + gb(lng(s, "diskUsedMb")));
        info.add("");
        info.add("En jeu : /mcs join " + ref);
        g.set(4, item(statusItem(st), name, NamedTextColor.WHITE, info.toArray(String[]::new)), null);

        if (has(s, "JOIN")) {
            g.set(10, item(Material.ENDER_PEARL, "Rejoindre", NamedTextColor.AQUA,
                    running ? "Aller sur " + name : "Le serveur démarre s'il est arrêté", "", "» Clic : y aller"), c -> act(p, "join", ref));
        }
        if (has(s, "POWER")) {
            if (stopped) {
                g.set(11, item(Material.LIME_DYE, "Démarrer", NamedTextColor.GREEN, "", "» Clic : démarrer"), c -> act(p, "start", ref));
            }
            if (running) {
                g.set(12, item(Material.CLOCK, "Redémarrer", NamedTextColor.GOLD, "", "» Clic : confirmer dans le chat"),
                        c -> confirm(p, "Redémarrer " + name + " ?", "Redémarrer", "/mcs restart " + ref, NamedTextColor.GOLD));
            }
            if (running || st.equals("STARTING")) {
                g.set(13, item(Material.RED_DYE, "Arrêter", NamedTextColor.RED,
                        lng(s, "online") > 0 ? lng(s, "online") + " joueur(s) connecté(s)" : "", "» Clic : confirmer dans le chat"),
                        c -> confirm(p, "Arrêter " + name + " ?", "Arrêter", "/mcs stop " + ref, NamedTextColor.RED));
            }
        }
        if (has(s, "CONSOLE") && running) {
            g.set(15, item(Material.COMMAND_BLOCK, "Commande console", NamedTextColor.LIGHT_PURPLE,
                    "Envoyer une commande au serveur", "(ex. say Salut, time set day)", "", "» Clic : l'écrire"),
                    c -> ask(p, "Commande pour " + name, "", cmd -> {
                        String line = cmd.startsWith("/") ? cmd.substring(1) : cmd;
                        if (line.isBlank()) return;
                        if (line.length() > 200) {
                            p.sendMessage(ImgFarmPlugin.error("Commande trop longue."));
                            return;
                        }
                        confirm(p, "Envoyer « " + line + " » à la console de " + name + " ?", "Envoyer", "/mcs console " + ref + " " + line,
                                NamedTextColor.LIGHT_PURPLE);
                    }));
        }
        g.set(16, item(Material.BOOK, "Infos détaillées", NamedTextColor.AQUA, "Dans le chat", "", "» Clic : afficher"), c -> act(p, "info", ref));
        if (canBackup) {
            g.set(19, item(Material.CHEST, "Sauvegarder maintenant", NamedTextColor.GREEN,
                    s.has("lastBackupAt") ? "Dernière : " + str(s, "lastBackupAt", "").replace('T', ' ').replaceAll(":\\d{2}(\\.\\d+)?$", "") : "",
                    "Restaurer : sur le site (onglet Sauvegardes)", "", "» Clic : confirmer dans le chat"),
                    c -> confirm(p, "Faire une sauvegarde de " + name + " maintenant ?", "Sauvegarder", "/mcs backup " + ref, NamedTextColor.GREEN));
            g.set(20, item(Material.BOOKSHELF, "Historique des sauvegardes", NamedTextColor.AQUA, "Dans le chat", "", "» Clic : afficher"),
                    c -> act(p, "backups", ref));
        }
        if (has(s, "INVITE")) {
            g.set(21, item(Material.PLAYER_HEAD, "Membres et invitations", NamedTextColor.AQUA, "Dans le chat", "", "» Clic : afficher"),
                    c -> act(p, "members", ref));
        }
        if (has(s, "DELETE") && !isProtected) {
            g.set(25, item(Material.BARRIER, "Supprimer le serveur", NamedTextColor.RED, "Ses fichiers seront effacés", "(les sauvegardes restent un temps)", "",
                    "» Clic : confirmer dans le chat"), c -> confirm(p, "Supprimer " + name + " ? Une 2e confirmation suivra.", "Supprimer",
                    "/mcs delete " + ref, NamedTextColor.RED));
        }
        g.set(31, item(Material.SUNFLOWER, "Actualiser", NamedTextColor.WHITE), c -> server(p, id));
    }

    // ---------- Créer un serveur ----------

    void createType(Player p) {
        Draft d = new Draft();
        drafts.put(p.getUniqueId(), d);
        Gui g = new Gui(3, "Créer : le type");
        int slot = 11;
        for (String[] t : TYPES) {
            g.set(slot++, item(Material.matchMaterial(t[2]), t[1], NamedTextColor.WHITE, t[3], "", "» Clic : choisir"), c -> {
                d.type = t[0];
                d.typeLabel = t[1];
                createVersion(p, d);
            });
        }
        g.set(18, back(), c -> main(p));
        open(p, g);
        bridge.send(p, "servers", new JsonObject(), r -> {
            if (r.ok() && r.body().has("quota")) d.quota = r.body().getAsJsonObject("quota");
        });
    }

    private void createVersion(Player p, Draft d) {
        ask(p, "Version de Minecraft", Bukkit.getMinecraftVersion(), v -> {
            if (!VERSION.matcher(v).matches()) {
                p.sendMessage(ImgFarmPlugin.error("Version invalide (ex. 1.21.4)."));
                return;
            }
            d.version = v;
            createRam(p, d);
        });
    }

    private void createRam(Player p, Draft d) {
        Gui g = new Gui(3, "Créer : la RAM");
        long left = d.quota == null ? Long.MAX_VALUE : lng(d.quota, "ramRemainingMb");
        int slot = 10;
        for (int gbs : new int[]{1, 2, 3, 4, 6, 8, 12}) {
            boolean ok = (long) gbs * 1024 <= left;
            g.set(slot++, item(ok ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, gbs + " Go",
                    ok ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY,
                    ok ? "» Clic : choisir" : "Plus que ton quota (" + gb(Math.max(0, left)) + " libres)"), ok ? c -> {
                d.ramGb = gbs;
                createCpu(p, d);
            } : null);
        }
        g.set(18, back(), c -> createType(p));
        open(p, g);
    }

    private void createCpu(Player p, Draft d) {
        Gui g = new Gui(3, "Créer : les CPU");
        long left = d.quota == null ? Long.MAX_VALUE : lng(d.quota, "cpuRemainingCores");
        int slot = 11;
        for (int n : new int[]{1, 2, 3, 4, 6}) {
            boolean ok = n <= left;
            g.set(slot++, item(ok ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, n + " CPU",
                    ok ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY,
                    ok ? "» Clic : choisir" : "Plus que ton quota (" + Math.max(0, left) + " libres)"), ok ? c -> {
                d.cpu = n;
                createName(p, d);
            } : null);
        }
        g.set(18, back(), c -> createRam(p, d));
        open(p, g);
    }

    private void createName(Player p, Draft d) {
        ask(p, "Nom du serveur", "mon-serveur", raw -> {
            String name = raw.toLowerCase(Locale.ROOT).replace(' ', '-');
            if (!NAME.matcher(name).matches()) {
                p.sendMessage(ImgFarmPlugin.error("Nom invalide : 3 à 32 caractères, lettres minuscules, chiffres et tirets."));
                p.sendMessage(Component.text("  ").append(ImgFarmPlugin.button("Réessayer", "/menu", "Rouvrir le menu", NamedTextColor.YELLOW)));
                return;
            }
            drafts.remove(p.getUniqueId());
            confirm(p, "Créer « " + name + " » : " + d.typeLabel + " " + d.version + ", " + d.ramGb + " Go, " + d.cpu + " CPU ?",
                    "Créer " + name, "/mcs create " + d.type + " " + d.version + " " + name + " " + d.ramGb + "G " + d.cpu,
                    NamedTextColor.GREEN);
        });
    }

    // ---------- Farm ----------

    void farm(Player p) {
        FarmSession s = plugin.sessions.get(p.getUniqueId());
        Gui g = new Gui(3, "Farm");
        g.set(18, back(), c -> main(p));
        if (s != null && !s.finished) {
            g.set(4, item(Material.CLOCK, "Session en cours : " + (s.itemName == null ? "construction" : s.itemName), NamedTextColor.GOLD,
                    s.kind == FarmSession.Kind.CONSTRUCTION ? "Construction" : s.auto ? "Ferme auto" : "À la main",
                    s.paused() ? "En pause" : "En cours",
                    s.kind == FarmSession.Kind.CONSTRUCTION ? "Temps : " + ImgFarmPlugin.clock(s.activeMillis() / 1000)
                            : "Compté : " + ImgFarmPlugin.num(s.quantity) + " · " + ImgFarmPlugin.clock(s.activeMillis() / 1000)), null);
            g.set(11, s.paused()
                    ? item(Material.LIME_DYE, "Reprendre", NamedTextColor.GREEN, "", "» Clic : reprendre")
                    : item(Material.YELLOW_DYE, "Pause", NamedTextColor.YELLOW, "", "» Clic : mettre en pause"), c -> {
                p.closeInventory();
                if (s.paused()) plugin.resume(p);
                else plugin.pause(p);
            });
            g.set(13, item(Material.RED_DYE, "Terminer", NamedTextColor.RED, "Finit la session ;", "tu choisis ensuite le type", "et tu l'envoies au site.", "",
                    "» Clic : terminer"), c -> {
                p.closeInventory();
                plugin.stop(p);
            });
            if (s.auto) {
                g.set(14, item(Material.HOPPER, "Ajouter un coffre de collecte", NamedTextColor.AQUA,
                        "Regarde le coffre, puis", "tape /farm coffre"), null);
            }
            g.set(15, item(Material.BARRIER, "Annuler", NamedTextColor.DARK_RED, "La session est oubliée.", "", "» Clic : confirmer"),
                    c -> yesNo(p, "Annuler la session ?", () -> {
                        p.closeInventory();
                        plugin.cancel(p);
                    }, () -> farm(p)));
        } else if (s != null) {
            g.set(4, item(Material.PAPER, "Session terminée : " + (s.itemName == null ? "construction" : s.itemName), NamedTextColor.GOLD,
                    "Type : " + ImgFarmPlugin.TYPES.getOrDefault(s.type, "à choisir")), null);
            g.set(11, item(Material.NAME_TAG, "Type de méthode", NamedTextColor.AQUA,
                    "Actuel : " + ImgFarmPlugin.TYPES.getOrDefault(s.type, "aucun"), "", "» Clic : changer"), c -> farmType(p));
            g.set(13, item(Material.EMERALD, "Envoyer au site", NamedTextColor.GREEN, "Elle arrive « à compléter »", "dans Farming → Mes sessions", "",
                    "» Clic : envoyer"), c -> {
                p.closeInventory();
                plugin.sendToSite(p);
            });
            g.set(15, item(Material.BARRIER, "Jeter", NamedTextColor.DARK_RED, "", "» Clic : confirmer"),
                    c -> yesNo(p, "Jeter la session ?", () -> {
                        p.closeInventory();
                        plugin.discard(p);
                    }, () -> farm(p)));
        } else {
            g.set(10, item(Material.IRON_PICKAXE, "Farm à la main", NamedTextColor.WHITE, "Items ramassés, sortis des fours", "et des villageois.", "",
                    "» Clic : choisir l'item"), c -> farmItem(p, false));
            g.set(11, item(Material.HOPPER, "Ferme auto", NamedTextColor.WHITE, "Regarde le coffre de collecte", "AVANT d'ouvrir le menu.", "",
                    "» Clic : choisir l'item"), c -> farmItem(p, true));
            g.set(12, item(Material.BRICKS, "Construction", NamedTextColor.WHITE, "Chrono de construction", "(le temps seulement).", "",
                    "» Clic : choisir"), c -> farmBuild(p));
            g.set(14, item(Material.KNOWLEDGE_BOOK, "Meilleures méthodes", NamedTextColor.AQUA, "Les méthodes du site pour un item", "(ex. fer, iron_ingot).", "",
                    "» Clic : chercher"), c -> ask(p, "Item à chercher", heldItem(p), q -> {
                if (!q.isBlank()) plugin.best(p, q);
            }));
            g.set(16, item(Material.BOOK, "Aide", NamedTextColor.GRAY, "Les commandes /farm", "", "» Clic : afficher"), c -> {
                p.closeInventory();
                plugin.help(p);
            });
        }
        open(p, g);
    }

    private static String heldItem(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        return hand == null || hand.getType() == Material.AIR ? "" : hand.getType().getKey().getKey();
    }

    private void farmItem(Player p, boolean auto) {
        ask(p, auto ? "Item de la ferme" : "Item à farmer", heldItem(p).isEmpty() ? "iron_ingot" : heldItem(p), itemName -> {
            if (itemName.isBlank()) return;
            Material m = Material.matchMaterial(itemName);
            if (m == null) {
                p.sendMessage(ImgFarmPlugin.error("Item inconnu : " + itemName + " (nom anglais, ex. iron_ingot, wheat)."));
                return;
            }
            farmGoal(p, auto, m.getKey().getKey());
        });
    }

    private void farmGoal(Player p, boolean auto, String itemKey) {
        Gui g = new Gui(3, "Objectif");
        String[][] goals = {{"30m", "30 minutes"}, {"1h", "1 heure"}, {"2h", "2 heures"}, {"640", "640 items (10 stacks)"},
                {"1728", "1 728 items (un coffre)"}, {"", "Sans objectif"}};
        int slot = 10;
        for (String[] goal : goals) {
            g.set(slot++, item(goal[0].isEmpty() ? Material.BARRIER : goal[0].endsWith("m") || goal[0].endsWith("h") ? Material.CLOCK : Material.CHEST,
                    goal[1], NamedTextColor.WHITE, goal[0].isEmpty() ? "Tu arrêtes avec /farm stop" : "", "» Clic : lancer"), c -> {
                p.closeInventory();
                plugin.start(p, auto, goal[0].isEmpty() ? new String[]{auto ? "auto" : "start", itemKey}
                        : new String[]{auto ? "auto" : "start", itemKey, goal[0]});
            });
        }
        g.set(16, item(Material.NAME_TAG, "Autre…", NamedTextColor.AQUA, "Une durée (45m, 1h30)", "ou un nombre d'items", "", "» Clic : l'écrire"),
                c -> ask(p, "Objectif", "45m", goal -> {
                    if (!goal.isBlank()) plugin.start(p, auto, new String[]{auto ? "auto" : "start", itemKey, goal});
                }));
        g.set(18, back(), c -> farm(p));
        open(p, g);
    }

    private void farmBuild(Player p) {
        Gui g = new Gui(3, "Construction");
        g.set(11, item(Material.BRICKS, "La base de la ferme", NamedTextColor.WHITE, "", "» Clic : lancer le chrono"), c -> {
            p.closeInventory();
            plugin.startConstruction(p, new String[]{"construction", "base"});
        });
        g.set(13, item(Material.STONE_BRICKS, "Un module", NamedTextColor.WHITE, "Une partie qu'on répète", "", "» Clic : lancer le chrono"), c -> {
            p.closeInventory();
            plugin.startConstruction(p, new String[]{"construction", "module"});
        });
        g.set(15, item(Material.CLOCK, "Sans précision", NamedTextColor.WHITE, "", "» Clic : lancer le chrono"), c -> {
            p.closeInventory();
            plugin.startConstruction(p, new String[]{"construction"});
        });
        g.set(18, back(), c -> farm(p));
        open(p, g);
    }

    private void farmType(Player p) {
        Gui g = new Gui(3, "Type de méthode");
        int slot = 10;
        for (Map.Entry<String, String> t : ImgFarmPlugin.TYPES.entrySet()) {
            g.set(slot++, item(Material.NAME_TAG, t.getValue(), NamedTextColor.WHITE, "", "» Clic : choisir"), c -> {
                plugin.setType(p, new String[]{"type", t.getKey()});
                farm(p);
            });
        }
        g.set(18, back(), c -> farm(p));
        open(p, g);
    }

    private void yesNo(Player p, String title, Runnable yes, Runnable no) {
        Gui g = new Gui(3, title);
        g.set(11, item(Material.LIME_CONCRETE, "Oui", NamedTextColor.GREEN), c -> yes.run());
        g.set(15, item(Material.RED_CONCRETE, "Non", NamedTextColor.RED), c -> no.run());
        open(p, g);
    }
}
