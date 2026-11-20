package vinch.img.farm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Échange avec le site par le proxy MCS (canal img:farm) : le serveur n'a aucun jeton et
 * ne joint pas le site lui-même. Le proxy ajoute l'UUID réel du joueur et le nom du serveur.
 */
final class Bridge implements PluginMessageListener {

    static final String CHANNEL = "img:farm";

    /** Réponse du site : statut HTTP et corps JSON */
    record Reply(int status, JsonObject body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String error() {
            if (body != null && body.has("error")) return body.get("error").getAsString();
            return "Erreur " + status;
        }

        String text(String key, String fallback) {
            return body != null && body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsString() : fallback;
        }
    }

    private final JavaPlugin plugin;
    private final AtomicLong next = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Reply>> pending = new ConcurrentHashMap<>();

    Bridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    /**
     * Envoie une requête par la connexion du joueur ; {@code done} est appelé sur le fil
     * principal du serveur, avec la réponse ou une erreur 504 si le proxy ne répond pas en 8 s.
     */
    void send(Player player, String op, JsonObject data, Consumer<Reply> done) {
        long rid = next.getAndIncrement();
        CompletableFuture<Reply> future = new CompletableFuture<>();
        pending.put(rid, future);
        future.orTimeout(8, TimeUnit.SECONDS).whenComplete((reply, error) -> {
            pending.remove(rid);
            Reply r = reply != null ? reply : timeout();
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, () -> done.accept(r));
        });
        JsonObject msg = new JsonObject();
        msg.addProperty("rid", rid);
        msg.addProperty("op", op);
        msg.add("data", data);
        player.sendPluginMessage(plugin, CHANNEL, msg.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Reply timeout() {
        JsonObject body = new JsonObject();
        body.addProperty("error", "Le proxy n'a pas répondu. Ce serveur doit passer par le proxy MCS (lot 44 ou plus récent).");
        return new Reply(504, body);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try {
            JsonObject msg = JsonParser.parseString(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
            CompletableFuture<Reply> future = pending.get(msg.get("rid").getAsLong());
            if (future == null) return;
            JsonObject body = msg.has("body") && msg.get("body").isJsonObject() ? msg.getAsJsonObject("body") : new JsonObject();
            future.complete(new Reply(msg.get("status").getAsInt(), body));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Réponse illisible du proxy sur img:farm : " + e.getMessage());
        }
    }
}
