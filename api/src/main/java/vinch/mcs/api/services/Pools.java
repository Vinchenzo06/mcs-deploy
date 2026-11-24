package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vinch.mcs.api.entities.ServerType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lot 76 : groupes de serveurs à la demande, décrits dans {@code /var/lib/mcs/pools/pools.json}
 * (copié depuis {@code deploy/pools} du dépôt par mcs-deploy). Chaque groupe a un modèle :
 * type, version, RAM, CPU, plugins Modrinth, et des fichiers posés avant le 1er démarrage
 * ({@code <groupe>/files/…}, ex. la config de WorldReset).
 */
public final class Pools {

    private static final Logger log = LoggerFactory.getLogger(Pools.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{2,24}$");
    private static final long MAX_FILE = 512 * 1024;
    private static final long MAX_TOTAL = 2 * 1024 * 1024;

    public record Pool(String name, String label, String description, String prefix, ServerType type,
                       String version, int ramMb, int cpu, List<String> modrinth, int keep, int maxExtra,
                       int idleDeleteHours, int idleStopMinutes) {}

    public record Config(String owner, List<Pool> pools) {}

    private static volatile Config cache;
    private static volatile long cacheStamp = -1;

    private Pools() {
    }

    static Path dir() {
        String env = System.getenv("MCS_POOLS_DIR");
        return Path.of(env == null || env.isBlank() ? "/var/lib/mcs/pools" : env);
    }

    /** La configuration (relue quand pools.json change) ; vide si le fichier manque ou est illisible */
    public static Config config() {
        Path file = dir().resolve("pools.json");
        long stamp;
        try {
            stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return new Config(null, List.of());
        }
        if (cache != null && stamp == cacheStamp) {
            return cache;
        }
        try {
            cache = parse(JSON.readTree(Files.readAllBytes(file)));
            cacheStamp = stamp;
        } catch (Exception e) {
            log.warn("Groupes de serveurs illisibles ({}) : {}", file, e.getMessage());
            return cache != null ? cache : new Config(null, List.of());
        }
        return cache;
    }

    static Config parse(JsonNode root) {
        List<Pool> out = new ArrayList<>();
        for (JsonNode p : root.path("pools")) {
            String name = p.path("name").asText("");
            if (!NAME.matcher(name).matches()) {
                log.warn("Groupe ignoré : nom invalide « {} »", name);
                continue;
            }
            List<String> modrinth = new ArrayList<>();
            p.path("modrinth").forEach(m -> modrinth.add(m.asText("")));
            String prefix = p.path("prefix").asText(name);
            ServerType type;
            try {
                type = ServerType.valueOf(p.path("type").asText("PAPER").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warn("Groupe {} ignoré : type inconnu « {} »", name, p.path("type").asText());
                continue;
            }
            out.add(new Pool(name, p.path("label").asText(name), p.path("description").asText(""),
                    NAME.matcher(prefix).matches() ? prefix : name,
                    type,
                    p.path("version").asText("LATEST"),
                    Math.max(1024, p.path("ramMb").asInt(4096)),
                    Math.max(1, p.path("cpu").asInt(2)),
                    ModrinthPlugins.normalize(modrinth),
                    Math.max(0, p.path("keep").asInt(1)),
                    Math.max(0, p.path("maxExtra").asInt(3)),
                    Math.max(1, p.path("idleDeleteHours").asInt(24)),
                    Math.max(0, p.path("idleStopMinutes").asInt(15))));
        }
        String owner = root.path("owner").isTextual() ? root.path("owner").asText() : null;
        return new Config(owner == null || owner.isBlank() ? null : owner, List.copyOf(out));
    }

    public static Pool get(String name) {
        for (Pool p : config().pools()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** Fichiers du modèle (chemin relatif au dossier du serveur, contenu en base64) */
    public static List<Map<String, Object>> templateFiles(String pool) {
        Path base = dir().resolve(pool).resolve("files");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return out;
        }
        long total = 0;
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                long size = Files.size(f);
                if (size > MAX_FILE || total + size > MAX_TOTAL) {
                    log.warn("Modèle {} : {} ignoré (trop gros)", pool, f);
                    continue;
                }
                total += size;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", base.relativize(f).toString().replace('\\', '/'));
                m.put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(f)));
                out.add(m);
            }
        } catch (IOException e) {
            log.warn("Modèle {} illisible : {}", pool, e.getMessage());
        }
        return out;
    }
}
