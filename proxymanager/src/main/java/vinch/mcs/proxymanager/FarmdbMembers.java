package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Membres de la team selon le site IMG Farm DB (lot 48) : un joueur dont le compte Minecraft
 * est lié à un compte du site non bloqué ({@code GET /mc/player/<uuid>}, jeton farmdb-token).
 * « Membre » gardé 60 s, « pas membre » 5 s (un joueur qui vient de lier son compte se
 * reconnecte aussitôt). Site injoignable = inconnu (les invitations MCS restent valables).
 */
public class FarmdbMembers {

    private static final long TTL_MS = 60_000;
    private static final long TTL_NOT_MEMBER_MS = 5_000;

    private record Entry(boolean member, long at) {
    }

    private final Logger logger;
    private final String url;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private volatile long lastErrorLog;

    public FarmdbMembers(Logger logger, String farmdbUrl, String token) {
        this.logger = logger;
        this.url = farmdbUrl.replaceAll("/+$", "");
        this.token = token;
    }

    /** Bloquant (3 s au plus) : à appeler depuis un handler Velocity (asynchrone) */
    public boolean isMember(UUID uuid) {
        return Boolean.TRUE.equals(check(uuid));
    }

    /** true/false selon le site, null si le site ne répond pas */
    public Boolean check(UUID uuid) {
        long now = System.currentTimeMillis();
        Entry e = cache.get(uuid);
        if (e != null && now - e.at() < (e.member() ? TTL_MS : TTL_NOT_MEMBER_MS)) {
            return e.member();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/mc/player/" + uuid))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            JsonNode answer = json.readTree(response.body());
            boolean member = answer.path("linked").asBoolean(false) && answer.path("member").asBoolean(false);
            cache.put(uuid, new Entry(member, now));
            if (cache.size() > 2000) {
                cache.values().removeIf(x -> now - x.at() > TTL_MS);
            }
            return member;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            if (now - lastErrorLog > 60_000) {
                lastErrorLog = now;
                logger.warn("Site IMG Farm DB injoignable pour vérifier un membre ({}) : seules les invitations comptent", ex.getMessage());
            }
            return null;
        }
    }

    /**
     * Lot 51 : code à entrer sur le site (Mon profil) pour lier ce compte Minecraft sans passer
     * par /link ({@code POST /mc/join-code}). Réponse du site ({code, expires_at} ou {linked,
     * member}), ou null s'il ne répond pas. Bloquant (3 s au plus).
     */
    public JsonNode joinCode(UUID uuid, String name) {
        try {
            String body = json.createObjectNode().put("uuid", uuid.toString()).put("name", name).toString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/mc/join-code"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? json.readTree(response.body()) : null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            logger.warn("Code de liaison IMG pour {} impossible : {}", name, ex.getMessage());
            return null;
        }
    }
}
