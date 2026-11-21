package vinch.img.farmmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * IMG Farm, mod client Fabric : les sessions de farm du plugin img-farm, sur n'importe quel
 * serveur (même en solo), envoyées au site de la team avec la clé du mod (/imgfarm lier).
 * Tout se passe chez le joueur : rien à installer sur le serveur.
 */
public final class ImgFarmMod implements ClientModInitializer {

    static final Logger LOG = LoggerFactory.getLogger("img-farm");
    static final String ID = "img-farm";

    static ImgFarmMod INSTANCE;

    /** Types de méthode du site (comme le plugin) */
    static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("main", "À la main");
        TYPES.put("champ", "Champ");
        TYPES.put("ferme", "Ferme auto");
        TYPES.put("villageois", "Villageois");
        TYPES.put("event", "Event");
        TYPES.put("autre", "Autre");
    }

    ModConfig config;
    SiteClient site;
    String modVersion = "?";
    String mcVersion = "?";

    FarmSession session;
    private Tracker tracker;
    private Item trackedItem;
    boolean busy;

    private long tick;
    private int saveIn = 600;
    // Dernier bloc utilisé (clic droit) : pour savoir quel coffre vient de s'ouvrir
    private String lastUseDim;
    private BlockPos lastUsePos;
    private long lastUseTick = -1000;
    private Object lastMenu;
    private String lastMenuKey;

    private record ScreenRef(Object player, Object menu, boolean screen) {
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        modVersion = version(ID);
        mcVersion = version("minecraft");
        config = ModConfig.load();
        site = new SiteClient(config, modVersion);

        session = ModConfig.loadSession();
        if (session != null && !session.finished) session.pause();
        bindTracker();

        ClientCommandRegistrationCallback.EVENT.register(Commands::register);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(this::onJoin));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onLeave());
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) {
                lastUseDim = dimension(level);
                lastUsePos = hit.getBlockPos();
                lastUseTick = tick;
            }
            return InteractionResult.PASS;
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(ID, "session"), Hud::extract);
        LOG.info("IMG Farm {} (Minecraft {}) : {}", modVersion, mcVersion, config.token == null ? "pas lié au site" : "lié au site");
    }

    private static String version(String mod) {
        return FabricLoader.getInstance().getModContainer(mod)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private void bindTracker() {
        tracker = session == null ? null : new Tracker(session);
        trackedItem = session == null || session.item == null ? null : findItem(session.item).orElse(null);
    }

    // ---------- Messages ----------

    static void say(Component c) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.sendSystemMessage(c);
    }

    /** Réponse du site : de retour sur le fil du jeu */
    void call(java.util.concurrent.CompletableFuture<SiteClient.Reply> f, Consumer<SiteClient.Reply> then) {
        busy = true;
        f.thenAccept(r -> Minecraft.getInstance().execute(() -> {
            busy = false;
            then.accept(r);
        }));
    }

    // ---------- Items ----------

    static String itemId(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    static Optional<Item> findItem(String raw) {
        String id = raw.toLowerCase(Locale.ROOT).trim();
        String full = id.contains(":") ? id : "minecraft:" + id;
        if (full.equals("minecraft:air")) return Optional.empty();
        return BuiltInRegistries.ITEM.stream().filter(i -> BuiltInRegistries.ITEM.getKey(i).toString().equals(full)).findFirst();
    }

    private static List<String> itemIds;

    static List<String> allItemIds() {
        if (itemIds == null) {
            List<String> out = new ArrayList<>();
            BuiltInRegistries.ITEM.stream().map(ImgFarmMod::itemId).filter(s -> !s.equals("air")).sorted().forEach(out::add);
            itemIds = out;
        }
        return itemIds;
    }

    // ---------- Coffres ----------

    private static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    static String key(String dim, BlockPos pos) {
        return dim + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Le conteneur que le joueur regarde (coffre, baril, trémie, shulker…), ou null */
    String lookedAtContainer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockEntity be = mc.level.getBlockEntity(hit.getBlockPos());
        if (!(be instanceof BaseContainerBlockEntity)) return null;
        return key(dimension(mc.level), hit.getBlockPos());
    }

    /** Ce menu est-il un coffre de collecte de la session ? Sa clé (celle enregistrée), sinon null */
    private String collectKey(AbstractContainerMenu menu, int containerSlots) {
        if (menu != lastMenu) {
            lastMenu = menu;
            lastMenuKey = null;
            if (lastUsePos != null && tick - lastUseTick <= 60) {
                String k = key(lastUseDim, lastUsePos);
                if (session.containers.contains(k)) {
                    lastMenuKey = k;
                } else if (containerSlots >= 54) {
                    // Grand coffre ouvert par l'autre moitié
                    for (BlockPos n : new BlockPos[]{lastUsePos.north(), lastUsePos.south(), lastUsePos.east(), lastUsePos.west()}) {
                        String nk = key(lastUseDim, n);
                        if (session.containers.contains(nk)) {
                            lastMenuKey = nk;
                            break;
                        }
                    }
                }
            }
        }
        return lastMenuKey;
    }

    // ---------- Comptage, à chaque tick ----------

    private void onTick(Minecraft mc) {
        tick++;
        FarmSession s = session;
        if (s == null || s.finished) return;
        if (!s.running()) {
            if (tracker != null) tracker.reset();
            return;
        }
        if (--saveIn <= 0) {
            saveIn = 600;
            ModConfig.saveSession(s);
        }
        LocalPlayer p = mc.player;
        if (s.farm() && trackedItem == null && s.item != null && tick % 100 == 1) trackedItem = findItem(s.item).orElse(null);
        if (s.farm() && p != null && tracker != null && trackedItem != null) {
            long before = s.quantity;
            observe(mc, p, s);
            if (s.quantity != before && s.targetReached()) {
                end(s, "Objectif atteint !");
                return;
            }
        }
        if (s.targetReached()) end(s, "Objectif atteint !");
    }

    private void observe(Minecraft mc, LocalPlayer p, FarmSession s) {
        AbstractContainerMenu menu = p.containerMenu;
        boolean screen = mc.screen instanceof AbstractContainerScreen<?>;
        ScreenRef ref = new ScreenRef(p, menu, screen);
        if (p.isCreative() || p.isSpectator()) {
            tracker.observe(Tracker.Ctx.IGNORE, ref, 0, null, -1);
            return;
        }
        Item item = trackedItem;
        if (menu instanceof InventoryMenu) {
            if (screen) {
                // Inventaire ouvert : la grille 2×2 compte comme l'inventaire (case résultat exclue)
                long n = countSlots(menu, item, true, true) + count(menu.getCarried(), item);
                tracker.observe(Tracker.Ctx.INVENTORY, ref, n, null, -1);
            } else {
                tracker.observe(Tracker.Ctx.NONE, ref, countInventory(p, item), null, -1);
            }
            return;
        }
        long player = countInventory(p, item) + count(menu.getCarried(), item);
        if (menu instanceof AbstractFurnaceMenu || menu instanceof BrewingStandMenu || menu instanceof MerchantMenu) {
            tracker.observe(s.auto ? Tracker.Ctx.OTHER : Tracker.Ctx.PRODUCER, ref, player, null, -1);
            return;
        }
        if (s.auto && !s.containers.isEmpty()) {
            int slots = 0;
            for (Slot slot : menu.slots) if (!(slot.container instanceof Inventory)) slots++;
            String k = collectKey(menu, slots);
            if (k != null) {
                tracker.observe(Tracker.Ctx.COLLECT, ref, player, k, countSlots(menu, item, false, false));
                return;
            }
        }
        tracker.observe(Tracker.Ctx.OTHER, ref, player, null, -1);
    }

    private static long count(ItemStack st, Item item) {
        return st != null && !st.isEmpty() && st.is(item) ? st.getCount() : 0;
    }

    private static long countInventory(LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        long n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) n += count(inv.getItem(i), item);
        return n;
    }

    /** playerSide : les cases du joueur (sinon celles du conteneur) ; skipFirst : sans la case 0 (résultat) */
    private static long countSlots(AbstractContainerMenu menu, Item item, boolean playerSide, boolean skipFirst) {
        long n = 0;
        for (int i = skipFirst ? 1 : 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean mine = slot.container instanceof Inventory;
            if (playerSide && menu instanceof InventoryMenu) mine = true; // tout l'écran d'inventaire est au joueur
            if (mine == playerSide) n += count(slot.getItem(), item);
        }
        return n;
    }

    // ---------- Connexion ----------

    private void onJoin() {
        lastMenu = null;
        if (tracker != null) tracker.reset();
        FarmSession s = session;
        if (s == null) return;
        if (s.finished) {
            say(Msg.info("Ta dernière session de farm n'est pas envoyée :"));
            summary(s);
        } else {
            say(Msg.info("Ta session de farm est en pause : ").append(
                    Msg.button("Reprendre", "/imgfarm reprendre", "Reprendre la session", ChatFormatting.GREEN)));
        }
    }

    private void onLeave() {
        FarmSession s = session;
        if (s == null) return;
        if (!s.finished) s.pause();
        if (tracker != null) tracker.reset();
        lastMenu = null;
        ModConfig.saveSession(s);
    }

    static String serverName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) return "solo";
        ServerData d = mc.getCurrentServer();
        if (d == null || d.ip == null) return null;
        String host = d.ip.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") || host.chars().filter(ch -> ch == ':').count() > 1) return null; // IPv6
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        host = host.replaceAll("\\.$", "");
        // Jamais une adresse IP (règle de la team) ; seulement un nom court
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || !host.matches("[a-z0-9_.-]{1,40}")) return null;
        return host;
    }

    // ---------- Sessions (comme /farm du plugin) ----------

    private boolean blockIfSession() {
        FarmSession s = session;
        if (s == null) return false;
        if (s.finished) {
            say(Msg.error("Ta dernière session est terminée mais pas envoyée :"));
            summary(s);
        } else {
            say(Msg.error("Tu as déjà une session en cours : /imgfarm stop pour la finir, /imgfarm annuler pour l'oublier."));
        }
        return true;
    }

    void start(boolean auto, String rawItem, String goal) {
        if (blockIfSession() || busy) return;
        Optional<Item> found = findItem(rawItem.replace("minecraft:", ""));
        if (found.isEmpty()) {
            say(Msg.error("Item inconnu : " + rawItem + " (ex. iron_ingot, wheat)"));
            return;
        }
        long count = 0, seconds = 0;
        if (goal != null && !goal.isBlank()) {
            String t = goal.toLowerCase(Locale.ROOT);
            if (t.matches("\\d{1,7}")) {
                count = Long.parseLong(t);
            } else {
                seconds = Msg.parseDuration(t);
                if (seconds <= 0) {
                    say(Msg.error("Objectif invalide : " + goal + ". Une durée (30m, 1h30, 90s) ou un nombre d'items (640)."));
                    return;
                }
            }
            if (count == 0 && seconds == 0) {
                say(Msg.error("L'objectif doit être plus grand que 0."));
                return;
            }
            if (seconds > 0 && seconds < FarmSession.MIN_SECONDS) {
                say(Msg.error("Durée trop courte : " + FarmSession.MIN_SECONDS + " s minimum."));
                return;
            }
        }
        String container = null;
        if (auto) {
            container = lookedAtContainer();
            if (container == null) {
                say(Msg.error("Regarde le coffre de collecte de la ferme (coffre, baril, trémie…) en tapant la commande."));
                return;
            }
        }
        Item item = found.get();
        String itemKey = itemId(item);
        JsonObject data = new JsonObject();
        data.addProperty("item", itemKey);
        final long c = count, sec = seconds;
        final String first = container;
        call(site.post("/mod/farm/check", data, true), r -> {
            if (!r.ok()) {
                say(Msg.error(r.error()));
                return;
            }
            if (session != null) return;
            String name = itemKey;
            JsonElement it = r.body().get("item");
            if (it != null && it.isJsonObject() && it.getAsJsonObject().has("name") && !it.getAsJsonObject().get("name").isJsonNull()) {
                name = it.getAsJsonObject().get("name").getAsString();
            }
            FarmSession s = new FarmSession();
            s.kind = "farm";
            s.auto = auto;
            s.item = itemKey;
            s.itemName = name;
            s.targetCount = c;
            s.targetSeconds = sec;
            s.type = auto ? "ferme" : "main";
            s.server = serverName();
            s.mcVersion = mcVersion;
            if (first != null) s.containers.add(first);
            session = s;
            bindTracker();
            ModConfig.saveSession(s);
            String target = c > 0 ? " jusqu'à " + Msg.num(c) : sec > 0 ? " pendant " + Msg.humanDuration(sec) : "";
            say(Msg.ok("Session lancée : " + name + target + (auto ? " (ferme auto, 1 coffre de collecte)" : "") + "."));
            say(Msg.info(auto
                    ? "Compté : ce qui arrive dans le coffre de collecte (ouvre-le une fois maintenant pour partir de son contenu, "
                    + "puis à chaque fois que tu viens le vider) et ce que tu ramasses. Autre coffre : /imgfarm coffre."
                    : "Compté : ce que tu ramasses (sauf ce que tu as jeté ou posé) et ce qui sort des fours, de l'alambic "
                    + "et des villageois. Ranger dans un coffre ou un shulker ne change rien au total."));
            if (c == 0 && sec == 0) say(Msg.info("Pas d'objectif : /imgfarm stop quand tu as fini."));
        });
    }

    void startConstruction(String rawPart) {
        if (blockIfSession() || busy) return;
        String part = rawPart == null ? "base" : rawPart.toLowerCase(Locale.ROOT);
        if (!part.equals("base") && !part.equals("module")) {
            say(Msg.error("/imgfarm construction base (une seule fois) ou /imgfarm construction module (un module)"));
            return;
        }
        call(site.post("/mod/farm/check", new JsonObject(), true), r -> {
            if (!r.ok()) {
                say(Msg.error(r.error()));
                return;
            }
            if (session != null) return;
            FarmSession s = new FarmSession();
            s.kind = "construction";
            s.part = part;
            s.server = serverName();
            s.mcVersion = mcVersion;
            session = s;
            bindTracker();
            ModConfig.saveSession(s);
            say(Msg.ok("Chrono de construction lancé (" + ("module".equals(part) ? "un module" : "la base")
                    + "). /imgfarm pause si tu t'arrêtes, /imgfarm stop quand c'est fini."));
        });
    }

    void addContainer() {
        FarmSession s = session;
        if (s == null || s.finished || !s.auto) {
            say(Msg.error("/imgfarm coffre sert pendant une session de ferme auto (/imgfarm auto <item>)."));
            return;
        }
        String k = lookedAtContainer();
        if (k == null) {
            say(Msg.error("Regarde un coffre, un baril ou une trémie en tapant la commande."));
            return;
        }
        if (!s.containers.add(k)) {
            say(Msg.info("Ce coffre est déjà compté."));
            return;
        }
        ModConfig.saveSession(s);
        say(Msg.ok("Coffre ajouté (" + s.containers.size() + " coffres de collecte). Ouvre-le une fois pour partir de son contenu."));
    }

    private FarmSession active() {
        FarmSession s = session;
        if (s == null || s.finished) {
            say(Msg.error("Aucune session en cours. /imgfarm aide"));
            return null;
        }
        return s;
    }

    void pause() {
        FarmSession s = active();
        if (s == null) return;
        if (s.paused()) {
            say(Msg.info("Déjà en pause : /imgfarm reprendre"));
            return;
        }
        s.pause();
        ModConfig.saveSession(s);
        say(Msg.info("Session en pause (le temps et le comptage sont arrêtés). /imgfarm reprendre pour continuer."));
    }

    void resume() {
        FarmSession s = active();
        if (s == null) return;
        if (!s.paused()) {
            say(Msg.info("La session n'est pas en pause."));
            return;
        }
        if (tracker != null) tracker.reset();
        s.resume();
        ModConfig.saveSession(s);
        say(Msg.ok("Session reprise."));
    }

    void stop() {
        FarmSession s = active();
        if (s == null) return;
        if (s.auto && s.containers.stream().anyMatch(k -> !s.seen.containsKey(k))) {
            say(Msg.info("Un coffre de collecte n'a jamais été ouvert : ce qu'il contient n'est pas compté."));
        }
        end(s, "Session terminée.");
    }

    void cancel() {
        if (session == null) {
            say(Msg.error("Aucune session à annuler."));
            return;
        }
        session = null;
        bindTracker();
        ModConfig.saveSession(null);
        say(Msg.info("Session annulée, rien n'est envoyé."));
    }

    /** Fin : trop courte ou vide = jetée ; sinon résumé avec le type et l'envoi */
    void end(FarmSession s, String why) {
        s.finish();
        if (tracker != null) tracker.reset();
        if (s.activeSeconds() < FarmSession.MIN_SECONDS) {
            session = null;
            bindTracker();
            ModConfig.saveSession(null);
            say(Msg.error("Session trop courte (" + FarmSession.MIN_SECONDS + " s minimum) : jetée."));
            return;
        }
        if (s.farm() && s.quantity <= 0) {
            session = null;
            bindTracker();
            ModConfig.saveSession(null);
            say(Msg.error("Rien n'a été compté pendant la session : jetée."));
            return;
        }
        ModConfig.saveSession(s);
        say(Msg.ok(why));
        summary(s);
    }

    void summary(FarmSession s) {
        if (!s.farm()) {
            say(Msg.info("Construction " + ("module".equals(s.part) ? "d'un module" : "de la base") + " : "
                    + Msg.humanDuration(s.activeSeconds()) + "."));
        } else {
            say(Msg.info(Msg.num(s.quantity) + " × " + s.itemName + " en " + Msg.humanDuration(s.activeSeconds())
                    + " = " + Msg.num(s.ratePerHour()) + " /h"));
            MutableComponent line = Msg.prefix().append(Component.literal("Type : ").withStyle(ChatFormatting.GRAY));
            for (var e : TYPES.entrySet()) {
                boolean chosen = e.getKey().equals(s.type);
                MutableComponent b = Msg.button(e.getValue(), "/imgfarm type " + e.getKey(), "Type de méthode sur le site",
                        chosen ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
                if (chosen) b = b.withStyle(ChatFormatting.BOLD);
                line = line.append(b).append(Component.literal(" "));
            }
            say(line);
        }
        say(Msg.prefix()
                .append(Msg.button("Envoyer au site", "/imgfarm envoyer", "À compléter ensuite sur le site (Mes sessions)", ChatFormatting.GREEN))
                .append(Component.literal("  "))
                .append(Msg.button("Jeter", "/imgfarm jeter", "Oublier cette session", ChatFormatting.RED)));
    }

    private FarmSession finishedSession() {
        FarmSession s = session;
        if (s == null || !s.finished) {
            say(Msg.error("Aucune session terminée. /imgfarm stop pour finir celle en cours."));
            return null;
        }
        return s;
    }

    void setType(String type) {
        FarmSession s = finishedSession();
        if (s == null || !s.farm()) return;
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (!TYPES.containsKey(t)) {
            say(Msg.error("Types : " + String.join(", ", TYPES.keySet())));
            return;
        }
        s.type = t;
        ModConfig.saveSession(s);
        summary(s);
    }

    void discard() {
        FarmSession s = finishedSession();
        if (s == null) return;
        session = null;
        bindTracker();
        ModConfig.saveSession(null);
        say(Msg.info("Session jetée."));
    }

    void send() {
        FarmSession s = finishedSession();
        if (s == null || busy) return;
        JsonObject data = new JsonObject();
        data.addProperty("kind", s.farm() ? "farm" : "construction");
        data.addProperty("active_seconds", s.activeSeconds());
        data.addProperty("started_at", s.startedAt);
        data.addProperty("ended_at", s.endedAt);
        if (s.mcVersion != null && !s.mcVersion.equals("?")) data.addProperty("mc_version", s.mcVersion);
        if (s.server != null) data.addProperty("server", s.server);
        if (s.farm()) {
            data.addProperty("mode", s.auto ? "auto" : "main");
            data.addProperty("item", s.item);
            data.addProperty("quantity", s.quantity);
            data.addProperty("type", s.type);
        } else {
            data.addProperty("part", s.part);
        }
        say(Msg.info("Envoi au site…"));
        call(site.post("/mod/farm/session", data, true), r -> {
            if (!r.ok()) {
                say(Msg.error(r.error()));
                say(Msg.info("La session est gardée : /imgfarm envoyer pour réessayer, /imgfarm jeter pour l'oublier."));
                return;
            }
            if (session == s) {
                session = null;
                bindTracker();
                ModConfig.saveSession(null);
            }
            say(Msg.ok("Envoyée au site. ").append(Msg.link("[Compléter sur le site]", r.text("url", ""), ChatFormatting.AQUA)));
        });
    }

    // ---------- /imgfarm <item> : les meilleures méthodes du site ----------

    void best(String query) {
        if (busy) return;
        JsonObject data = new JsonObject();
        data.addProperty("query", query.length() > 60 ? query.substring(0, 60) : query);
        call(site.post("/mod/farm/best", data, true), r -> {
            if (!r.ok()) {
                say(Msg.error(r.error()));
                if (r.status() == 404) say(Msg.info("Les commandes : /imgfarm aide"));
                return;
            }
            JsonObject body = r.body();
            JsonObject item = body.getAsJsonObject("item");
            String name = item.get("name").getAsString();
            String id = item.get("id").getAsString();
            int count = body.has("count") ? body.get("count").getAsInt() : 0;
            say(Msg.prefix().append(Component.literal(name).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" (" + id + ")").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(count == 0 ? " : aucune méthode sur le site." : " : " + count
                            + (count > 1 ? " méthodes" : " méthode") + " sur le site").withStyle(ChatFormatting.GRAY)));
            int rank = 1;
            JsonArray methods = body.has("methods") ? body.getAsJsonArray("methods") : new JsonArray();
            for (JsonElement el : methods) {
                JsonObject m = el.getAsJsonObject();
                StringBuilder detail = new StringBuilder(m.get("type").getAsString());
                if (m.has("version") && !m.get("version").isJsonNull()) detail.append(" · ").append(m.get("version").getAsString());
                if (m.has("event") && !m.get("event").isJsonNull()) detail.append(" · event ").append(m.get("event").getAsString());
                MutableComponent line = Component.literal(" " + rank++ + ". ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(Msg.num(m.get("rate").getAsDouble()) + "/h ").withStyle(ChatFormatting.GREEN))
                        .append(Msg.link(m.get("title").getAsString(), m.has("url") ? m.get("url").getAsString() : "", ChatFormatting.WHITE))
                        .append(Component.literal("  " + detail).withStyle(ChatFormatting.DARK_GRAY));
                if (m.has("dupe") && m.get("dupe").getAsBoolean()) line = line.append(Component.literal(" [dupe]").withStyle(ChatFormatting.GOLD));
                say(line);
            }
            MutableComponent foot = Component.literal(" ");
            String itemUrl = item.has("url") ? item.get("url").getAsString() : "";
            if (count > 0) foot = foot.append(Msg.link("[Tout voir sur le site]", itemUrl, ChatFormatting.AQUA)).append(Component.literal(" "));
            foot = foot.append(Msg.button("Mesurer", "/imgfarm start " + id, "Lancer une session de farm pour " + name, ChatFormatting.YELLOW));
            say(foot);
            if (body.has("others") && body.getAsJsonArray("others").size() > 0) {
                MutableComponent others = Component.literal(" Autres items : ").withStyle(ChatFormatting.DARK_GRAY);
                boolean first = true;
                for (JsonElement el : body.getAsJsonArray("others")) {
                    JsonObject o = el.getAsJsonObject();
                    if (!first) others = others.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                    first = false;
                    others = others.append(Msg.button(o.get("name").getAsString(), "/imgfarm " + o.get("id").getAsString(),
                            "/imgfarm " + o.get("id").getAsString(), ChatFormatting.GRAY));
                }
                say(others);
            }
        });
    }

    // ---------- Liaison au site ----------

    void link(String code) {
        if (busy) return;
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        LocalPlayer p = Minecraft.getInstance().player;
        data.addProperty("label", "Fabric " + mcVersion + (p != null ? " · " + p.getName().getString() : ""));
        call(site.post("/mod/link", data, false), r -> {
            if (!r.ok()) {
                say(Msg.error(r.error()));
                return;
            }
            config.token = r.text("token", null);
            config.username = r.text("username", null);
            config.save();
            say(Msg.ok("Mod lié au compte " + config.username + " du site. ")
                    .append(Component.literal("/imgfarm aide").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" pour commencer.").withStyle(ChatFormatting.GRAY)));
        });
    }

    void unlink() {
        if (config.token == null) {
            say(Msg.info("Le mod n'est pas lié au site."));
            return;
        }
        call(site.delete("/mod/token"), r -> {
            config.token = null;
            config.username = null;
            config.save();
            say(Msg.info("Mod délié" + (r.ok() ? " : sa clé est retirée du site." : " ici (le site n'a pas répondu : retire l'appareil dans Mon profil).")));
        });
    }

    void account() {
        if (config.token == null) {
            say(Msg.info("Le mod n'est pas lié. Sur le site : Mon profil → Mod Fabric → Lier le mod, puis /imgfarm lier CODE."));
            return;
        }
        call(site.get("/mod/me"), r -> {
            if (!r.ok()) {
                say(Msg.error(r.error()));
                return;
            }
            String u = r.text("username", "?");
            if (!u.equals(config.username)) {
                config.username = u;
                config.save();
            }
            say(Msg.ok("Lié au compte " + u + " du site (" + config.site + "). IMG Farm " + modVersion + ", Minecraft " + mcVersion + "."));
        });
    }

    void toggleHud() {
        config.hud = !config.hud;
        config.save();
        say(Msg.info("Cadre de la session " + (config.hud ? "affiché." : "caché.")));
    }

    void help() {
        say(Msg.info("IMG Farm " + modVersion + " : sessions de farm envoyées au site de la team (Mes sessions)."));
        say(Msg.line("/imgfarm <item>", "les meilleures méthodes du site pour cet item (ex. /imgfarm fer)"));
        say(Msg.line("/imgfarm start <item> [30m | 640]", "farm à la main : items ramassés, sortis des fours, de l'alambic et des villageois"));
        say(Msg.line("/imgfarm auto <item> [30m | 640]", "ferme auto : regarde le coffre de collecte"));
        say(Msg.line("/imgfarm coffre", "ajoute un autre coffre de collecte (regarde-le)"));
        say(Msg.line("/imgfarm construction [base | module]", "chrono de construction"));
        say(Msg.line("/imgfarm pause | reprendre | stop | annuler", ""));
        say(Msg.line("/imgfarm hud", "affiche ou cache le cadre de la session"));
        say(Msg.line("/imgfarm lier <code> | compte | delier", "liaison au site (code : Mon profil → Mod Fabric)"));
        say(Component.literal("  Objectif : une durée (30m, 1h30) ou un nombre d'items (640). Sans objectif : /imgfarm stop.")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (config.token == null) {
            say(Msg.error("Pas encore lié au site : Mon profil → Mod Fabric → Lier le mod, puis /imgfarm lier CODE."));
        }
    }
}
