package vinch.img.farm;

import com.google.gson.JsonObject;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * img-farm : sessions de farm mesurées en jeu, pour le site IMG Farm DB.
 *
 * /farm start <item> [30m|640]   farm à la main (items ramassés, sauf jetés, + sortis des fours et villageois)
 * /farm auto <item> [30m|640]    ferme auto : en regardant le coffre de collecte
 * /farm coffre                   ajoute un autre coffre de collecte
 * /farm construction [base|module]  chrono de construction (le temps seulement)
 * /farm pause | reprendre | stop | annuler
 * À la fin : choix du type, puis /farm envoyer (session « à compléter » sur le site) ou /farm jeter.
 */
public final class ImgFarmPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    static final int MIN_SECONDS = 30;

    /** Types de méthode du site (clé -> libellé) */
    static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("main", "À la main");
        TYPES.put("champ", "Champ");
        TYPES.put("ferme", "Ferme auto");
        TYPES.put("villageois", "Villageois");
        TYPES.put("event", "Event");
        TYPES.put("autre", "Autre");
    }

    private static final List<String> SUBCOMMANDS = List.of("start", "auto", "coffre", "construction", "pause",
            "reprendre", "stop", "annuler", "envoyer", "jeter", "type", "aide");
    private static final Pattern DURATION = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)(?:min|m))?(?:(\\d+)s)?$");

    final Map<UUID, FarmSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private Bridge bridge;
    Menu menu;
    private int tick;

    @Override
    public void onEnable() {
        bridge = new Bridge(this);
        bridge.register();
        menu = new Menu(this, bridge);
        bridge.setPushHandler(menu::openFromProxy);
        getServer().getPluginManager().registerEvents(menu, this);
        PluginCommand menuCmd = getCommand("menu");
        if (menuCmd != null) {
            menuCmd.setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player pl) menu.main(pl);
                else sender.sendMessage("Commande réservée aux joueurs.");
                return true;
            });
        }
        // Lot 76 : /jouer <jeu> lancé ici (PNJ FancyNpcs du lobby : action « player_command jouer speedrun »)
        // -> le proxy trouve ou prépare un serveur à la demande. Tapée par un joueur, /jouer va
        // directement au proxy ; cette commande sert aux PNJ, panneaux et menus.
        PluginCommand jouer = getCommand("jouer");
        if (jouer != null) {
            jouer.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player pl)) {
                    sender.sendMessage("Commande réservée aux joueurs.");
                } else if (args.length != 1 || !args[0].toLowerCase(java.util.Locale.ROOT).matches("[a-z0-9-]{2,24}")) {
                    pl.sendMessage(error("Utilisation : /jouer <jeu> (ex. /jouer speedrun)"));
                } else {
                    menu.act(pl, "jouer", args[0].toLowerCase(java.util.Locale.ROOT));
                }
                return true;
            });
        }
        PluginCommand cmd = getCommand("farm");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(new FarmListener(this), this);
        getServer().getScheduler().runTaskTimer(this, this::everySecond, 20L, 20L);
        getLogger().info("img-farm prêt : /farm (sessions envoyées au site par le proxy, canal " + Bridge.CHANNEL + ")");
    }

    @Override
    public void onDisable() {
        for (FarmSession s : sessions.values()) {
            Player p = getServer().getPlayer(s.player);
            if (p != null) {
                hideBar(p, s);
                p.sendMessage(error("Le serveur s'arrête : ta session de farm est perdue."));
            }
        }
        sessions.clear();
    }

    // ---------- Messages ----------

    static Component prefix() {
        return Component.text("IMG » ", NamedTextColor.GOLD);
    }

    static Component info(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GRAY));
    }

    static Component ok(String text) {
        return prefix().append(Component.text("✔ " + text, NamedTextColor.GREEN));
    }

    static Component error(String text) {
        return prefix().append(Component.text("✖ " + text, NamedTextColor.RED));
    }

    static Component button(String label, String command, String hover, NamedTextColor color) {
        return Component.text("[" + label + "]", color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    static String num(double n) {
        String s = String.format(Locale.ROOT, "%,d", Math.round(n));
        return s.replace(',', ' ');
    }

    static String clock(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static String humanDuration(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + " h " + String.format(Locale.ROOT, "%02d", m);
        if (m > 0) return m + " min" + (s > 0 ? " " + String.format(Locale.ROOT, "%02d", s) + " s" : "");
        return s + " s";
    }

    void help(Player p) {
        p.sendMessage(Component.text("  /menu", NamedTextColor.YELLOW)
                .append(Component.text(" le menu IMG : tes serveurs, créer un serveur, la farm (aussi /farm et /mcs seuls)", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /farm <item>", NamedTextColor.YELLOW)
                .append(Component.text(" les meilleures méthodes du site pour cet item (ex. /farm fer)", NamedTextColor.GRAY)));
        p.sendMessage(info("Sessions de farm, envoyées sur le site de la team (Farming → Mes sessions) :"));
        p.sendMessage(Component.text("  /farm start <item> [30m | 640]", NamedTextColor.YELLOW)
                .append(Component.text(" farm à la main : items ramassés, sortis des fours et des villageois", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /farm auto <item> [30m | 640]", NamedTextColor.YELLOW)
                .append(Component.text(" ferme auto : regarde le coffre de collecte", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /farm coffre", NamedTextColor.YELLOW)
                .append(Component.text(" ajoute un autre coffre de collecte", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /farm construction [base | module]", NamedTextColor.YELLOW)
                .append(Component.text(" chrono de construction", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /farm pause | reprendre | stop | annuler", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  Objectif : une durée (30m, 1h30) ou un nombre d'items (640). Sans objectif : /farm stop.",
                NamedTextColor.DARK_GRAY));
    }

    // ---------- Barre de progression ----------

    void showBar(Player p, FarmSession s) {
        if (s.bar == null) {
            s.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        }
        updateBar(s);
        p.showBossBar(s.bar);
    }

    void hideBar(Player p, FarmSession s) {
        if (s.bar != null) p.hideBossBar(s.bar);
    }

    void updateBar(FarmSession s) {
        if (s.bar == null) return;
        long sec = s.activeSeconds();
        StringBuilder t = new StringBuilder();
        if (s.paused()) t.append("[Pause] ");
        float progress = 1f;
        if (s.kind == FarmSession.Kind.CONSTRUCTION) {
            t.append("Construction ").append("module".equals(s.part) ? "d'un module" : "de la base").append(" · ").append(clock(sec));
        } else {
            if (s.auto) t.append("Ferme auto · ");
            t.append(s.itemName).append(" · ").append(num(Math.max(0, s.quantity)));
            if (s.targetCount > 0) {
                t.append(" / ").append(num(s.targetCount));
                progress = (float) Math.max(0, s.quantity) / s.targetCount;
            }
            double rate = s.ratePerHour();
            t.append(" · ").append(rate < 0 ? "…" : num(rate)).append(" /h · ").append(clock(sec));
            if (s.targetSeconds > 0) {
                t.append(" / ").append(clock(s.targetSeconds));
                progress = (float) sec / s.targetSeconds;
            }
        }
        s.bar.name(Component.text(t.toString()));
        s.bar.progress(Math.max(0f, Math.min(1f, progress)));
        s.bar.color(s.paused() ? BossBar.Color.YELLOW : s.kind == FarmSession.Kind.CONSTRUCTION ? BossBar.Color.BLUE : BossBar.Color.GREEN);
    }

    private void everySecond() {
        tick++;
        for (FarmSession s : sessions.values()) {
            if (s.finished) continue;
            Player p = getServer().getPlayer(s.player);
            if (p == null) continue;
            if (s.running() && s.targetReached()) {
                end(p, s, "Objectif atteint !");
                continue;
            }
            updateBar(s);
            if (s.auto && s.running() && tick % 5 == 0) warnIfFull(p, s);
        }
    }

    /** Ferme auto : prévient si tous les coffres de collecte sont pleins (la mesure serait faussée) */
    private void warnIfFull(Player p, FarmSession s) {
        if (System.currentTimeMillis() - s.fullWarnedAt < 60_000) return;
        boolean anyLoaded = false;
        for (String key : s.containers) {
            Inventory inv = inventoryAt(key);
            if (inv == null) continue;
            anyLoaded = true;
            if (inv.firstEmpty() >= 0) return;
            for (var stack : inv.getStorageContents()) {
                if (stack != null && stack.getType() == s.item && stack.getAmount() < stack.getMaxStackSize()) return;
            }
        }
        if (!anyLoaded) return;
        s.fullWarnedAt = System.currentTimeMillis();
        p.sendMessage(error("Le coffre de collecte est plein : vide-le ou ajoute un coffre (/farm coffre), sinon la mesure sera fausse."));
    }

    // ---------- Coffres ----------

    /** Clé d'un inventaire de bloc : « monde:x,y,z » (un grand coffre a une seule clé, au milieu) */
    static String key(Inventory inv) {
        if (inv == null) return null;
        Location loc = inv.getLocation();
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Inventory inventoryAt(String key) {
        try {
            String[] wp = key.split(":", 2);
            String[] c = wp[1].split(",");
            var world = getServer().getWorld(wp[0]);
            if (world == null) return null;
            int x = Integer.parseInt(c[0]), y = Integer.parseInt(c[1]), z = Integer.parseInt(c[2]);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
            BlockState state = world.getBlockAt(x, y, z).getState();
            return state instanceof Container container ? container.getInventory() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String lookedAtContainer(Player p) {
        Block b = p.getTargetBlockExact(6);
        if (b == null) return null;
        BlockState state = b.getState();
        return state instanceof Container container ? key(container.getInventory()) : null;
    }

    // ---------- Commandes ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (args.length == 0) {
            menu.farm(p); // lot 60 : /farm seul ouvre le menu de la farm (l'aide : /farm aide)
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "auto" -> start(p, sub.equals("auto"), args);
            case "construction" -> startConstruction(p, args);
            case "coffre" -> addContainer(p);
            case "pause" -> pause(p);
            case "reprendre" -> resume(p);
            case "stop" -> stop(p);
            case "annuler" -> cancel(p);
            case "type" -> setType(p, args);
            case "envoyer" -> sendToSite(p);
            case "jeter" -> discard(p);
            case "aide", "help" -> help(p);
            default -> best(p, String.join(" ", args));
        }
        return true;
    }

    private boolean blockIfSession(Player p) {
        FarmSession s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        if (s.finished) {
            p.sendMessage(error("Ta dernière session est terminée mais pas envoyée :"));
            summary(p, s);
        } else {
            p.sendMessage(error("Tu as déjà une session en cours : /farm stop pour la finir, /farm annuler pour l'oublier."));
        }
        return true;
    }

    void start(Player p, boolean auto, String[] args) {
        if (blockIfSession(p) || busy.contains(p.getUniqueId())) return;
        if (args.length < 2) {
            p.sendMessage(error("Indique l'item : /farm " + args[0] + " <item> [30m | 640]"));
            return;
        }
        Material item = Material.matchMaterial(args[1]);
        if (item == null || !item.isItem() || item.isAir()) {
            p.sendMessage(error("Item inconnu : " + args[1] + " (ex. iron_ingot, wheat)"));
            return;
        }
        long count = 0, seconds = 0;
        if (args.length >= 3) {
            String t = args[2].toLowerCase(Locale.ROOT);
            if (t.matches("\\d{1,7}")) {
                count = Long.parseLong(t);
            } else {
                seconds = parseDuration(t);
                if (seconds <= 0) {
                    p.sendMessage(error("Objectif invalide : " + args[2] + ". Une durée (30m, 1h30, 90s) ou un nombre d'items (640)."));
                    return;
                }
            }
            if (count == 0 && seconds == 0) {
                p.sendMessage(error("L'objectif doit être plus grand que 0."));
                return;
            }
            if (seconds > 0 && seconds < MIN_SECONDS) {
                p.sendMessage(error("Durée trop courte : " + MIN_SECONDS + " s minimum."));
                return;
            }
        }
        String container = null;
        if (auto) {
            container = lookedAtContainer(p);
            if (container == null) {
                p.sendMessage(error("Regarde le coffre de collecte de la ferme (coffre, baril, trémie…) en tapant la commande."));
                return;
            }
        }
        String itemKey = item.getKey().getKey();
        JsonObject data = new JsonObject();
        data.addProperty("item", itemKey);
        busy.add(p.getUniqueId());
        final long c = count, sec = seconds;
        final String firstContainer = container;
        bridge.send(p, "check", data, reply -> {
            busy.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (!reply.ok()) {
                p.sendMessage(error(reply.error()));
                return;
            }
            if (sessions.containsKey(p.getUniqueId())) return;
            String name = itemKey;
            if (reply.body().has("item") && reply.body().get("item").isJsonObject()) {
                JsonObject it = reply.body().getAsJsonObject("item");
                if (it.has("name") && !it.get("name").isJsonNull()) name = it.get("name").getAsString();
            }
            FarmSession s = new FarmSession(p.getUniqueId(), FarmSession.Kind.FARM, auto, null, item, itemKey, name, c, sec);
            if (firstContainer != null) s.containers.add(firstContainer);
            sessions.put(p.getUniqueId(), s);
            showBar(p, s);
            String target = c > 0 ? " jusqu'à " + num(c) : sec > 0 ? " pendant " + humanDuration(sec) : "";
            p.sendMessage(ok("Session lancée : " + name + target + (auto ? " (ferme auto, 1 coffre de collecte)" : "") + "."));
            p.sendMessage(info(auto
                    ? "Compté : ce qui entre dans le coffre de collecte et ce que tu ramasses. Autre coffre : /farm coffre."
                    : "Compté : ce que tu ramasses (sauf ce qui a été jeté) et ce qui sort des fours et des villageois. "
                    + "Ranger dans un coffre ou un shulker ne change rien au total."));
            if (c == 0 && sec == 0) p.sendMessage(info("Pas d'objectif : /farm stop quand tu as fini."));
        });
    }

    static long parseDuration(String t) {
        if (t.isEmpty()) return 0;
        // « 1h30 » = 1 h 30 min
        Matcher hm = Pattern.compile("^(\\d+)h(\\d+)$").matcher(t);
        if (hm.matches()) return Long.parseLong(hm.group(1)) * 3600 + Long.parseLong(hm.group(2)) * 60;
        if (!t.matches(".*[hms].*")) return 0;
        Matcher m = DURATION.matcher(t);
        if (!m.matches()) return 0;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long s = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        long total = h * 3600 + min * 60 + s;
        return total > 48 * 3600 ? 0 : total;
    }

    void startConstruction(Player p, String[] args) {
        if (blockIfSession(p) || busy.contains(p.getUniqueId())) return;
        String part = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "base";
        if (!part.equals("base") && !part.equals("module")) {
            p.sendMessage(error("/farm construction base (une seule fois) ou /farm construction module (un module)"));
            return;
        }
        busy.add(p.getUniqueId());
        bridge.send(p, "check", new JsonObject(), reply -> {
            busy.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (!reply.ok()) {
                p.sendMessage(error(reply.error()));
                return;
            }
            if (sessions.containsKey(p.getUniqueId())) return;
            FarmSession s = new FarmSession(p.getUniqueId(), FarmSession.Kind.CONSTRUCTION, false, part, null, null, null, 0, 0);
            sessions.put(p.getUniqueId(), s);
            showBar(p, s);
            p.sendMessage(ok("Chrono de construction lancé (" + ("module".equals(part) ? "un module" : "la base")
                    + "). /farm pause si tu t'arrêtes, /farm stop quand c'est fini."));
        });
    }

    private void addContainer(Player p) {
        FarmSession s = sessions.get(p.getUniqueId());
        if (s == null || s.finished || !s.auto) {
            p.sendMessage(error("/farm coffre sert pendant une session de ferme auto (/farm auto <item>)."));
            return;
        }
        String key = lookedAtContainer(p);
        if (key == null) {
            p.sendMessage(error("Regarde un coffre, un baril ou une trémie en tapant la commande."));
            return;
        }
        if (!s.containers.add(key)) {
            p.sendMessage(info("Ce coffre est déjà compté."));
            return;
        }
        p.sendMessage(ok("Coffre ajouté (" + s.containers.size() + " coffres de collecte)."));
    }

    private FarmSession active(Player p) {
        FarmSession s = sessions.get(p.getUniqueId());
        if (s == null || s.finished) {
            p.sendMessage(error("Aucune session en cours. /farm pour l'aide."));
            return null;
        }
        return s;
    }

    void pause(Player p) {
        FarmSession s = active(p);
        if (s == null) return;
        if (s.paused()) {
            p.sendMessage(info("Déjà en pause : /farm reprendre"));
            return;
        }
        s.pause();
        updateBar(s);
        p.sendMessage(info("Session en pause (le temps et le comptage sont arrêtés). /farm reprendre pour continuer."));
    }

    void resume(Player p) {
        FarmSession s = active(p);
        if (s == null) return;
        if (!s.paused()) {
            p.sendMessage(info("La session n'est pas en pause."));
            return;
        }
        s.resume();
        showBar(p, s);
        p.sendMessage(ok("Session reprise."));
    }

    void stop(Player p) {
        FarmSession s = active(p);
        if (s == null) return;
        end(p, s, "Session terminée.");
    }

    void cancel(Player p) {
        FarmSession s = sessions.remove(p.getUniqueId());
        if (s == null) {
            p.sendMessage(error("Aucune session à annuler."));
            return;
        }
        hideBar(p, s);
        p.sendMessage(info("Session annulée, rien n'est envoyé."));
    }

    /** Fin de session : trop courte ou vide = jetée ; sinon résumé avec le choix du type et l'envoi */
    void end(Player p, FarmSession s, String why) {
        s.finish();
        hideBar(p, s);
        if (s.activeSeconds() < MIN_SECONDS) {
            sessions.remove(s.player);
            p.sendMessage(error("Session trop courte (" + MIN_SECONDS + " s minimum) : jetée."));
            return;
        }
        if (s.kind == FarmSession.Kind.FARM && s.quantity <= 0) {
            sessions.remove(s.player);
            p.sendMessage(error("Rien n'a été compté pendant la session : jetée."));
            return;
        }
        p.sendMessage(ok(why));
        summary(p, s);
    }

    void summary(Player p, FarmSession s) {
        if (s.kind == FarmSession.Kind.CONSTRUCTION) {
            p.sendMessage(info("Construction " + ("module".equals(s.part) ? "d'un module" : "de la base") + " : "
                    + humanDuration(s.activeSeconds()) + "."));
        } else {
            p.sendMessage(info(num(s.quantity) + " × " + s.itemName + " en " + humanDuration(s.activeSeconds())
                    + " = " + num(s.ratePerHour()) + " /h"));
            Component line = prefix().append(Component.text("Type : ", NamedTextColor.GRAY));
            for (var e : TYPES.entrySet()) {
                boolean chosen = e.getKey().equals(s.type);
                Component b = button(e.getValue(), "/farm type " + e.getKey(), "Type de méthode sur le site",
                        chosen ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY);
                line = line.append(chosen ? b.decorate(TextDecoration.BOLD) : b).append(Component.text(" "));
            }
            p.sendMessage(line);
        }
        p.sendMessage(prefix()
                .append(button("Envoyer au site", "/farm envoyer", "À compléter ensuite sur le site (Mes sessions)", NamedTextColor.GREEN))
                .append(Component.text("  "))
                .append(button("Jeter", "/farm jeter", "Oublier cette session", NamedTextColor.RED)));
    }

    private FarmSession finishedSession(Player p) {
        FarmSession s = sessions.get(p.getUniqueId());
        if (s == null || !s.finished) {
            p.sendMessage(error("Aucune session terminée. /farm stop pour finir celle en cours."));
            return null;
        }
        return s;
    }

    void setType(Player p, String[] args) {
        FarmSession s = finishedSession(p);
        if (s == null || s.kind != FarmSession.Kind.FARM) return;
        if (args.length < 2 || !TYPES.containsKey(args[1].toLowerCase(Locale.ROOT))) {
            p.sendMessage(error("Types : " + String.join(", ", TYPES.keySet())));
            return;
        }
        s.type = args[1].toLowerCase(Locale.ROOT);
        summary(p, s);
    }

    void discard(Player p) {
        FarmSession s = finishedSession(p);
        if (s == null) return;
        sessions.remove(p.getUniqueId());
        p.sendMessage(info("Session jetée."));
    }

    void sendToSite(Player p) {
        FarmSession s = finishedSession(p);
        if (s == null || busy.contains(p.getUniqueId())) return;
        JsonObject data = new JsonObject();
        data.addProperty("kind", s.kind == FarmSession.Kind.FARM ? "farm" : "construction");
        data.addProperty("active_seconds", s.activeSeconds());
        data.addProperty("started_at", s.startedAt);
        data.addProperty("ended_at", s.endedAt);
        data.addProperty("mc_version", getServer().getMinecraftVersion());
        if (s.kind == FarmSession.Kind.FARM) {
            data.addProperty("mode", s.auto ? "auto" : "main");
            data.addProperty("item", s.itemKey);
            data.addProperty("quantity", s.quantity);
            data.addProperty("type", s.type);
        } else {
            data.addProperty("part", s.part);
        }
        busy.add(p.getUniqueId());
        p.sendMessage(info("Envoi au site…"));
        bridge.send(p, "session", data, reply -> {
            busy.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (!reply.ok()) {
                p.sendMessage(error(reply.error()));
                p.sendMessage(info("La session est gardée : /farm envoyer pour réessayer, /farm jeter pour l'oublier."));
                return;
            }
            sessions.remove(p.getUniqueId(), s);
            String url = reply.text("url", "");
            Component msg = ok("Envoyée au site. ");
            if (url.startsWith("https://") || url.startsWith("http://")) {
                msg = msg.append(Component.text("[Compléter sur le site]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text(url))));
            }
            p.sendMessage(msg);
        });
    }

    // ---------- /farm <item> : les meilleures méthodes du site ----------

    void best(Player p, String query) {
        if (busy.contains(p.getUniqueId())) return;
        JsonObject data = new JsonObject();
        data.addProperty("query", query.length() > 60 ? query.substring(0, 60) : query);
        busy.add(p.getUniqueId());
        bridge.send(p, "best", data, reply -> {
            busy.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (!reply.ok()) {
                p.sendMessage(error(reply.error()));
                if (reply.status() == 404) p.sendMessage(info("Les commandes : /farm aide"));
                return;
            }
            JsonObject body = reply.body();
            JsonObject item = body.getAsJsonObject("item");
            String name = item.get("name").getAsString();
            String id = item.get("id").getAsString();
            int count = body.has("count") ? body.get("count").getAsInt() : 0;
            Component head = info("").append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" (" + id + ")", NamedTextColor.DARK_GRAY))
                    .append(Component.text(count == 0 ? " : aucune méthode sur le site." : " : " + count
                            + (count > 1 ? " méthodes" : " méthode") + " sur le site", NamedTextColor.GRAY));
            p.sendMessage(head);
            int rank = 1;
            for (var el : body.getAsJsonArray("methods")) {
                JsonObject m = el.getAsJsonObject();
                String url = m.has("url") ? m.get("url").getAsString() : "";
                StringBuilder detail = new StringBuilder(m.get("type").getAsString());
                if (m.has("version") && !m.get("version").isJsonNull()) detail.append(" · ").append(m.get("version").getAsString());
                if (m.has("event") && !m.get("event").isJsonNull()) detail.append(" · event ").append(m.get("event").getAsString());
                Component line = Component.text(" " + rank++ + ". ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(num(m.get("rate").getAsDouble()) + "/h ", NamedTextColor.GREEN))
                        .append(Component.text(m.get("title").getAsString(), NamedTextColor.WHITE))
                        .append(Component.text("  " + detail, NamedTextColor.DARK_GRAY));
                if (m.has("dupe") && m.get("dupe").getAsBoolean()) {
                    line = line.append(Component.text(" [dupe]", NamedTextColor.GOLD));
                }
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    line = line.clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Ouvrir la méthode sur le site")));
                }
                p.sendMessage(line);
            }
            Component foot = Component.text(" ");
            String itemUrl = item.has("url") ? item.get("url").getAsString() : "";
            if (count > 0 && (itemUrl.startsWith("https://") || itemUrl.startsWith("http://"))) {
                foot = foot.append(Component.text("[Tout voir sur le site]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(itemUrl))
                        .hoverEvent(HoverEvent.showText(Component.text(itemUrl)))).append(Component.text(" "));
            }
            foot = foot.append(button("Mesurer", "/farm start " + id, "Lancer une session de farm pour " + name, NamedTextColor.YELLOW));
            p.sendMessage(foot);
            if (body.has("others") && body.getAsJsonArray("others").size() > 0) {
                Component others = Component.text(" Autres items : ", NamedTextColor.DARK_GRAY);
                boolean first = true;
                for (var el : body.getAsJsonArray("others")) {
                    JsonObject o = el.getAsJsonObject();
                    if (!first) others = others.append(Component.text(", ", NamedTextColor.DARK_GRAY));
                    first = false;
                    others = others.append(Component.text(o.get("name").getAsString(), NamedTextColor.GRAY)
                            .clickEvent(ClickEvent.runCommand("/farm " + o.get("id").getAsString()))
                            .hoverEvent(HoverEvent.showText(Component.text("/farm " + o.get("id").getAsString()))));
                }
                p.sendMessage(others);
            }
        });
    }

    // ---------- Complétion ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String q = args[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
            for (String s : SUBCOMMANDS) if (s.startsWith(q)) out.add(s);
            // /farm <item> : les identifiants d'items (à partir de 2 lettres)
            if (q.length() >= 2) {
                for (Material m : Material.values()) {
                    if (out.size() >= 60) break;
                    if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
                    String k = m.getKey().getKey();
                    if (k.startsWith(q) || (q.length() >= 3 && k.contains(q))) out.add(k);
                }
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("start") || sub.equals("auto")) && args.length == 2) {
            String q = args[1].toLowerCase(Locale.ROOT).replace("minecraft:", "");
            for (Material m : Material.values()) {
                if (out.size() >= 60) break;
                if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
                String k = m.getKey().getKey();
                if (k.startsWith(q) || (q.length() >= 3 && k.contains(q))) out.add(k);
            }
            return out;
        }
        if ((sub.equals("start") || sub.equals("auto")) && args.length == 3) return List.of("30m", "1h", "640");
        if (sub.equals("construction") && args.length == 2) return List.of("base", "module");
        if (sub.equals("type") && args.length == 2) return new ArrayList<>(TYPES.keySet());
        return out;
    }
}
