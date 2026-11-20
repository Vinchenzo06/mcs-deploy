package vinch.mcs.api.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Plugins de la team IMG (lot 46) : les jars de {@code /var/lib/mcs/jars/team/} (posés par
 * le kit, ex. img-farm) sont envoyés à l'agent avec chaque démarrage d'un serveur Paper ou
 * Spigot marqué « team » ({@code sudo mcs-team}). L'agent les pose dans {@code plugins/},
 * les met à jour s'ils ont changé et retire ceux qu'il avait posés et qui ne sont plus envoyés.
 *
 * {@code team_plugins} absent = l'agent ne touche à rien (dossier illisible ici) ;
 * liste vide = retirer les plugins de la team posés avant (serveur sorti de la team).
 */
public final class TeamPlugins {

    private static final Logger log = LoggerFactory.getLogger(TeamPlugins.class);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}\\.jar$");
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    static Path dir() {
        String env = System.getenv("MCS_TEAM_PLUGINS_DIR");
        return Path.of(env == null || env.isBlank() ? "/var/lib/mcs/jars/team" : env);
    }

    private TeamPlugins() {
    }

    public static void apply(Map<String, Object> data, Server server) {
        boolean plugins = server.getServerType() == ServerType.PAPER || server.getServerType() == ServerType.SPIGOT;
        if (!Boolean.TRUE.equals(server.getIsTeam()) || !plugins) {
            data.put("team_plugins", List.of());
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir())) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!NAME.matcher(name).matches() || !Files.isRegularFile(p) || Files.size(p) > MAX_BYTES) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(p);
                list.add(Map.of(
                        "name", name,
                        "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        "data", Base64.getEncoder().encodeToString(bytes)));
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("Plugins de la team illisibles ({}) : serveur {} démarré sans les mettre à jour", e.getMessage(), server.getId());
            return;
        }
        data.put("team_plugins", list);
    }
}
