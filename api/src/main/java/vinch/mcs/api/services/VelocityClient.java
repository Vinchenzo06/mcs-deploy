package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import vinch.mcs.api.repositories.ServerRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Service
@Slf4j
public class VelocityClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Lazy
    private ServerRepository serverRepository;

    @Value("${mcs.velocity.url:http://127.0.0.1:8082}")
    private String velocityUrl;

    @Value("${mcs.velocity.plugin-key}")
    private String pluginKey;

    /**
     * Enregistre un nouveau serveur dans Velocity
     */
    public void registerServer(String name, String host, int port) throws Exception {
        // Mode de forwarding de ce serveur (lot 36) : legacy par défaut
        String forwarding = serverRepository.findByVelocityName(name).map(ProxyForwarding::modeFor).orElse("legacy");
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name,
                "address", host + ":" + port,
                "forwarding", forwarding
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(velocityUrl + "/servers"))
                .header("Content-Type", "application/json")
                .header("X-Plugin-Key", pluginKey)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur d'enregistrement Velocity : " + response.statusCode() + " - " + response.body());
        }

        log.info("Serveur '{}' enregistré dans Velocity ({}:{})", name, host, port);
    }

    /**
     * Retire un serveur de Velocity
     */
    /** Nombre de joueurs connectés par serveur (nom Velocity -> joueurs) ; vide si Velocity ne répond pas */
    public Map<String, Integer> getPlayerCounts() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(velocityUrl + "/servers"))
                    .header("X-Plugin-Key", pluginKey)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of();
            }
            Map<String, Integer> counts = new java.util.HashMap<>();
            for (var s : objectMapper.readTree(response.body()).path("servers")) {
                counts.put(s.path("name").asText(), s.path("players").asInt(0));
            }
            return counts;
        } catch (Exception e) {
            log.debug("Nombre de joueurs indisponible : {}", e.getMessage());
            return Map.of();
        }
    }

    /** Envoie un joueur connecté au réseau vers un serveur (/mcs move) */
    public void connectPlayer(java.util.UUID uuid, String serverName) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "uuid", uuid.toString(),
                "server", serverName
        ));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(velocityUrl + "/players/connect"))
                .header("Content-Type", "application/json")
                .header("X-Plugin-Key", pluginKey)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new RuntimeException("Ce joueur n'est pas connecté au réseau.");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("Le proxy a refusé le déplacement : " + response.statusCode() + " - " + response.body());
        }
    }

    /** Message à un joueur connecté ; false s'il n'est pas en ligne (ou proxy injoignable) */
    public boolean notifyPlayer(java.util.UUID uuid, String message) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("uuid", uuid.toString(), "message", message));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(velocityUrl + "/players/notify"))
                    .header("Content-Type", "application/json")
                    .header("X-Plugin-Key", pluginKey)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void unregisterServer(String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(velocityUrl + "/servers/" + name))
                .header("X-Plugin-Key", pluginKey)
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw new RuntimeException("Erreur de désenregistrement Velocity : " + response.statusCode() + " - " + response.body());
        }

        log.info("Serveur '{}' retiré de Velocity", name);
    }
}