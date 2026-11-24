package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Lot 66 : POST /console {"command":"lpv user X parent set admin"} sur l'API locale du proxy
 * (127.0.0.1 seulement), avec l'en-tête X-Console-Key (CONSOLE_KEY de /etc/mcs/secrets.env,
 * lisible par root seulement). Remplace la console RCON du lobby du VPS, retiré : mcs-rcon,
 * mcs-admin, mcs-node-owner et l'installation des groupes LuckPerms passent par ici.
 * La commande tourne comme si elle était tapée dans la console de Velocity ; sa sortie va
 * dans le journal (mcs-rcon la relit).
 */
final class ConsoleHandler implements HttpHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProxyServer server;
    private final Logger logger;
    private final byte[] key;

    ConsoleHandler(ProxyServer server, Logger logger, String key) {
        this.server = server;
        this.logger = logger;
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, "{\"error\":\"POST seulement\"}");
                return;
            }
            String given = ex.getRequestHeaders().getFirst("X-Console-Key");
            if (key.length < 16 || given == null
                    || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, "{\"error\":\"Clé refusée\"}");
                return;
            }
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            String command = body == null ? "" : body.path("command").asText("").trim();
            if (command.startsWith("/")) command = command.substring(1);
            if (command.isEmpty() || command.length() > 1000 || command.contains("\n")) {
                send(ex, 400, "{\"error\":\"Commande invalide\"}");
                return;
            }
            logger.info("Console (mcs-rcon) : {}", command);
            server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command);
            send(ex, 200, "{\"ok\":true}");
        } catch (Exception e) {
            send(ex, 500, JSON.createObjectNode().put("error", String.valueOf(e.getMessage())).toString());
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
