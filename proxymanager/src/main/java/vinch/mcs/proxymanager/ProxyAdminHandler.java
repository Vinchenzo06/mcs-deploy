package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Proxy Velocity depuis Admin → Réseau du site IMG (lot 70c), pour un admin du réseau
 * (rôle ADMIN de MCS, vérifié ici auprès de l'API) :
 *   POST /img/proxy/info     {uuid}           -> version de Velocity, joueurs connectés, plugins
 *   POST /img/proxy/logs     {uuid, lines}    -> fin du journal du proxy (logs/latest.log)
 *   POST /img/proxy/console  {uuid, command}  -> la commande tourne comme dans la console ; sa
 *                                                sortie = les lignes ajoutées au journal ensuite
 * Les adresses IP sont masquées dans tout ce qui sort d'ici (règle : jamais d'IP sur le site).
 * Mettre à jour Velocity, ses plugins ou le redémarrer : fait par root sur le VPS
 * (mcs-site-action), jamais par le proxy lui-même.
 */
final class ProxyAdminHandler {

    private static final Path LOG = Path.of("logs", "latest.log");
    private static final int MAX_TAIL = 256 * 1024;
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    // IPv6 telle que Velocity l'écrit après « / » (« /[2001:db8::1]:1234 » ou « /2001:db8::1 »)
    private static final Pattern IPV6 = Pattern.compile("/\\[?[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7}(?:%\\w+)?]?");

    private final ProxyServer proxy;
    private final ApiClient api;
    private final Logger logger;
    private final ObjectMapper json = new ObjectMapper();

    ProxyAdminHandler(ProxyServer proxy, ApiClient api, Logger logger) {
        this.proxy = proxy;
        this.api = api;
        this.logger = logger;
    }

    static String maskIps(String text) {
        return IPV4.matcher(IPV6.matcher(text).replaceAll("/[ip]")).replaceAll("x.x.x.x");
    }

    void handle(String path, JsonNode body, BiConsumer<Integer, JsonNode> reply) {
        UUID uuid;
        try {
            uuid = UUID.fromString(body.path("uuid").asText(""));
        } catch (IllegalArgumentException e) {
            reply.accept(400, error("Compte Minecraft invalide"));
            return;
        }
        api.getPlayerByUuid(uuid).whenComplete((p, e) -> {
            if (e != null || p == null || !"ADMIN".equals(p.path("role").asText())) {
                reply.accept(403, error("Réservé aux admins du réseau (rôle admin de MCS)."));
                return;
            }
            String who = p.path("username").asText("?");
            try {
                switch (path) {
                    case "/img/proxy/info" -> reply.accept(200, info());
                    case "/img/proxy/logs" -> {
                        ObjectNode out = json.createObjectNode();
                        out.put("text", tail(Math.min(Math.max(body.path("lines").asInt(200), 20), 1000)));
                        reply.accept(200, out);
                    }
                    case "/img/proxy/console" -> console(who, body.path("command").asText(""), reply);
                    default -> reply.accept(404, error("Inconnu"));
                }
            } catch (Exception ex) {
                reply.accept(500, error(String.valueOf(ex.getMessage())));
            }
        });
    }

    private ObjectNode info() {
        ObjectNode out = json.createObjectNode();
        out.put("version", proxy.getVersion().getVersion());
        out.put("players", proxy.getPlayerCount());
        ArrayNode plugins = out.putArray("plugins");
        for (PluginContainer c : proxy.getPluginManager().getPlugins()) {
            ObjectNode n = plugins.addObject();
            n.put("id", c.getDescription().getId());
            n.put("version", c.getDescription().getVersion().orElse(""));
        }
        return out;
    }

    /** Les dernières lignes du journal (IP masquées) */
    private String tail(int lines) throws IOException {
        if (!Files.isRegularFile(LOG)) {
            return "";
        }
        try (RandomAccessFile f = new RandomAccessFile(LOG.toFile(), "r")) {
            long len = f.length();
            int n = (int) Math.min(len, MAX_TAIL);
            byte[] buf = new byte[n];
            f.seek(len - n);
            f.readFully(buf);
            String[] all = new String(buf, StandardCharsets.UTF_8).split("\n");
            int from = Math.max(n < len ? 1 : 0, all.length - lines); // 1re ligne coupée si on a tronqué
            return maskIps(String.join("\n", Arrays.copyOfRange(all, Math.min(from, all.length), all.length)));
        }
    }

    private void console(String who, String raw, BiConsumer<Integer, JsonNode> reply) throws IOException {
        String command = raw.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty() || command.length() > 500 || command.contains("\n") || command.contains("\r")) {
            reply.accept(400, error("Commande vide ou trop longue (500 caractères au plus)."));
            return;
        }
        long before = Files.isRegularFile(LOG) ? Files.size(LOG) : 0;
        logger.info("Console (site IMG, {}) : {}", who, command);
        String cmd = command;
        proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), cmd)
                .completeOnTimeout(false, 10, TimeUnit.SECONDS)
                // LuckPerms et d'autres répondent un peu après : on laisse 1,5 s au journal
                .thenCompose(ok -> CompletableFuture.supplyAsync(() -> ok,
                        CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)))
                .whenComplete((ok, e) -> {
                    ObjectNode out = json.createObjectNode();
                    out.put("found", Boolean.TRUE.equals(ok));
                    try {
                        out.put("output", since(before));
                    } catch (IOException ex) {
                        out.put("output", "");
                    }
                    reply.accept(200, out);
                });
    }

    /** Ce qui a été ajouté au journal depuis la position {@code from} (IP masquées) */
    private String since(long from) throws IOException {
        if (!Files.isRegularFile(LOG)) {
            return "";
        }
        try (RandomAccessFile f = new RandomAccessFile(LOG.toFile(), "r")) {
            long len = f.length();
            if (len < from) {
                from = 0; // journal recommencé
            }
            int n = (int) Math.min(len - from, 64 * 1024);
            byte[] buf = new byte[n];
            f.seek(len - n);
            f.readFully(buf);
            return maskIps(new String(buf, StandardCharsets.UTF_8));
        }
    }

    private ObjectNode error(String message) {
        ObjectNode n = json.createObjectNode();
        n.put("error", message);
        return n;
    }
}
