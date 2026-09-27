package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${mcs.velocity.url:http://127.0.0.1:8082}")
    private String velocityUrl;

    @Value("${mcs.velocity.plugin-key}")
    private String pluginKey;

    /**
     * Enregistre un nouveau serveur dans Velocity
     */
    public void registerServer(String name, String host, int port) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name,
                "address", host + ":" + port
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