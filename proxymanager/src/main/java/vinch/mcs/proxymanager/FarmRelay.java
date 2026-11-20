package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relais des sessions de farm (lot 44) : le plugin Paper img-farm, sur un serveur de jeu,
 * envoie un message sur le canal img:farm ; le proxy l'envoie au site IMG Farm DB sur le
 * VPS (farmdb-url + /mc/farm/…, jeton farmdb-token) et renvoie la réponse au serveur.
 *
 * Sécurité : l'UUID et le nom du serveur sont ceux que le proxy connaît (jamais ceux du
 * message) ; un message venant d'un client est ignoré et n'est jamais transmis ; seules
 * les opérations check, session et best (/farm <item>, lecture seule) passent ; 20 requêtes
 * par minute et par joueur.
 * Le site n'enregistre qu'une session « à compléter » : rien n'est publié sans le membre.
 */
public class FarmRelay {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("img", "farm");

    private static final Set<String> OPS = Set.of("check", "session", "best");
    private static final int MAX_BYTES = 2048;
    private static final int MAX_PER_MINUTE = 20;

    private final Logger logger;
    private final String url;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();

    public FarmRelay(Logger logger, String farmdbUrl, String token) {
        this.logger = logger;
        this.url = farmdbUrl.replaceAll("/+$", "");
        this.token = token;
    }

    private boolean tooMany(UUID uuid) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
                times.pollFirst();
            }
            if (times.size() >= MAX_PER_MINUTE) {
                return true;
            }
            times.addLast(now);
            return false;
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // Jamais transmis plus loin (ni au client, ni au serveur)
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return; // envoyé par un client : ignoré
        }
        Player player = connection.getPlayer();
        byte[] data = event.getData();
        if (data.length > MAX_BYTES) {
            logger.warn("img:farm : message trop gros ignoré ({} octets, serveur {})", data.length,
                    connection.getServerInfo().getName());
            return;
        }

        JsonNode msg;
        try {
            msg = json.readTree(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        long rid = msg.path("rid").asLong(0);
        String op = msg.path("op").asText("");
        if (!OPS.contains(op) || !msg.path("data").isObject()) {
            reply(connection, rid, 400, error("Requête inconnue"));
            return;
        }
        if (tooMany(player.getUniqueId())) {
            reply(connection, rid, 429, error("Trop de requêtes, attends un peu."));
            return;
        }

        ObjectNode body = ((ObjectNode) msg.path("data")).deepCopy();
        body.put("uuid", player.getUniqueId().toString());
        String serverName = connection.getServerInfo().getName().replaceAll("[^A-Za-z0-9_.-]", "_");
        body.put("server", serverName.length() > 40 ? serverName.substring(0, 40) : serverName);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/mc/farm/" + op))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, e) -> {
            if (e != null) {
                logger.warn("img:farm {} de {} : site injoignable ({})", op, player.getUsername(), e.getMessage());
                reply(connection, rid, 503, error("Le site de la team ne répond pas pour le moment, réessaie plus tard."));
                return;
            }
            JsonNode answer;
            try {
                answer = json.readTree(response.body());
            } catch (Exception ex) {
                answer = error("Réponse illisible du site");
            }
            int code = response.statusCode();
            if (code == 401 || code == 503 || (code == 403 && "Réservé au VPS".equals(answer.path("error").asText()))) {
                logger.error("img:farm refusé par le site ({} : {}) : vérifie farmdb-url, farmdb-token et MC_API_TOKEN",
                        code, answer.path("error").asText(""));
                answer = error("Le site n'accepte pas les sessions pour le moment (réglage du serveur).");
            }
            reply(connection, rid, code, answer);
        });
    }

    private ObjectNode error(String message) {
        return json.createObjectNode().put("error", message);
    }

    /** Réponse au serveur, seulement si le joueur y est encore (sinon le plugin abandonne après 8 s). */
    private void reply(ServerConnection connection, long rid, int status, JsonNode body) {
        ObjectNode out = json.createObjectNode().put("rid", rid).put("status", status);
        out.set("body", body);
        Player player = connection.getPlayer();
        if (!player.isActive() || player.getCurrentServer().filter(c -> c.equals(connection)).isEmpty()) {
            return;
        }
        connection.sendPluginMessage(CHANNEL, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
