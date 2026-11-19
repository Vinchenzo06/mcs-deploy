package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /link CODE (lot 43) : lie le compte Minecraft du joueur à son compte Discord sur le
 * site IMG Farm DB. Le code s'obtient sur le site (Mon profil). Le proxy envoie
 * l'UUID réel du joueur (mode en ligne) au site, sur le VPS même (farmdb-url), avec
 * le jeton farmdb-token : un serveur de jeu ne peut donc pas lier un autre joueur.
 */
public class LinkCommand implements SimpleCommand {

    private static final Duration COOLDOWN = Duration.ofSeconds(3);

    private final Logger logger;
    private final String url;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, Instant> lastUse = new ConcurrentHashMap<>();

    public LinkCommand(Logger logger, String farmdbUrl, String token) {
        this.logger = logger;
        this.url = farmdbUrl.replaceAll("/+$", "");
        this.token = token;
    }

    private static Component info(String text) {
        return Component.text("IMG » ").color(NamedTextColor.GOLD).append(Component.text(text).color(NamedTextColor.GRAY));
    }

    private static Component ok(String text) {
        return Component.text("IMG » ✔ ").color(NamedTextColor.GOLD).append(Component.text(text).color(NamedTextColor.GREEN));
    }

    private static Component error(String text) {
        return Component.text("IMG » ✖ ").color(NamedTextColor.GOLD).append(Component.text(text).color(NamedTextColor.RED));
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Commande réservée aux joueurs."));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length != 1 || args[0].isBlank()) {
            player.sendMessage(info("Pour lier ton compte Minecraft au site de la team : va sur img.asearia.ca, "
                    + "Mon profil, « Lier mon compte Minecraft », puis tape ici /link <code>."));
            return;
        }
        Instant now = Instant.now();
        Instant last = lastUse.put(player.getUniqueId(), now);
        if (last != null && last.plus(COOLDOWN).isAfter(now)) {
            player.sendMessage(error("Attends quelques secondes avant de réessayer."));
            return;
        }

        String body;
        try {
            body = json.writeValueAsString(Map.of("code", args[0].trim(),
                    "uuid", player.getUniqueId().toString(), "name", player.getUsername()));
        } catch (Exception e) {
            player.sendMessage(error("Erreur interne."));
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/mc/link"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, e) -> {
            if (e != null) {
                logger.warn("/link de {} : site injoignable ({})", player.getUsername(), e.getMessage());
                player.sendMessage(error("Le site de la team ne répond pas pour le moment, réessaie plus tard."));
                return;
            }
            JsonNode answer;
            try {
                answer = json.readTree(response.body());
            } catch (Exception ex) {
                answer = json.createObjectNode();
            }
            if (response.statusCode() == 200 && answer.path("ok").asBoolean(false)) {
                logger.info("{} ({}) lié au compte {} du site", player.getUsername(), player.getUniqueId(),
                        answer.path("username").asText("?"));
                player.sendMessage(ok("Compte lié à " + answer.path("username").asText("ton compte")
                        + " sur le site de la team."));
                return;
            }
            int code = response.statusCode();
            if (code == 401 || code == 503 || (code == 403 && "Réservé au VPS".equals(answer.path("error").asText()))) {
                logger.error("/link refusé par le site ({} : {}) : vérifie farmdb-url, farmdb-token et MC_API_TOKEN",
                        code, answer.path("error").asText(""));
                player.sendMessage(error("La liaison n'est pas disponible pour le moment (réglage du serveur)."));
                return;
            }
            player.sendMessage(error(answer.path("error").asText("Liaison impossible, réessaie.")));
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
