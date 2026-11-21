package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lot 64 : versions de Minecraft proposées par la complétion de /mcs create. Lues dans la liste
 * de Mojang toutes les 12 h : une nouvelle version apparaît toute seule, sans nouveau jar.
 * (LATEST prend toujours la dernière ; on peut aussi taper n'importe quelle version.)
 */
final class MinecraftVersions {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final List<String> OLDER = List.of("1.21.11", "1.21.4", "1.21.1", "1.20.1", "1.19.2", "1.18.2");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static volatile List<String> suggestions = build(List.of("26.3", "26.2", "26.1.2"));

    private MinecraftVersions() {
    }

    static List<String> suggestions() {
        return suggestions;
    }

    private static List<String> build(List<String> recent) {
        List<String> out = new ArrayList<>();
        out.add("LATEST");
        for (String v : recent) if (!out.contains(v)) out.add(v);
        for (String v : OLDER) if (!out.contains(v)) out.add(v);
        return List.copyOf(out);
    }

    /** Les 4 dernières versions stables de Mojang (appelé par le planificateur du proxy) */
    static void refresh(Logger logger) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(MANIFEST))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            List<String> recent = new ArrayList<>();
            for (JsonNode v : JSON.readTree(res.body()).path("versions")) {
                if ("release".equals(v.path("type").asText()) && !v.path("id").asText().isBlank()) recent.add(v.path("id").asText());
                if (recent.size() >= 4) break;
            }
            if (!recent.isEmpty()) suggestions = build(recent);
        } catch (Exception e) {
            logger.debug("Liste des versions de Minecraft indisponible : {}", e.toString());
        }
    }
}
