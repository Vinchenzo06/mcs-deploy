package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Console (journal) et fichiers d'un serveur, pour le site IMG (lot 54). Droits : FILES pour
 * les fichiers, CONSOLE pour le journal. Tout est fait par l'agent de la machine du serveur,
 * dans le dossier du serveur seulement (os.Root, noms en « . » réservés).
 *
 * Les fichiers voyagent par morceaux de 1 Mo entre l'API et l'agent ; une requête HTTP en
 * porte 8 Mo au plus : le site découpe les gros fichiers, rien n'est gardé en entier.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerFilesService {

    public static final int AGENT_CHUNK = 1 << 20;
    public static final int MAX_REQUEST = 8 << 20;

    private final ServerRepository serverRepository;
    private final AccessService accessService;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final BackupService backupService;
    private final PlatformTransactionManager transactionManager;

    public record Target(long serverId, long nodeId, String server, String player) {}

    public record Content(byte[] data, long size, long mtime) {}

    private Target target(Long serverId, Long playerId, AccessService.Right right, boolean writes) {
        return new TransactionTemplate(transactionManager).execute(st -> {
            Server s = serverRepository.findById(serverId)
                    .orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            accessService.require(p, s, right);
            if (s.getStatus() == ServerStatus.DELETED) {
                throw new RuntimeException("Serveur introuvable");
            }
            if (s.getStatus() == ServerStatus.CREATING || s.getStatus() == ServerStatus.MIGRATING) {
                throw new RuntimeException("Le serveur est en cours de création ou de déplacement : réessaie dans un instant.");
            }
            if (s.getNode() == null || "COLD".equals(s.getParkState()) || "LOST".equals(s.getParkState())) {
                throw new RuntimeException("Les fichiers de ce serveur sont rangés au central : démarre-le pour les retrouver.");
            }
            if (writes && backupService.isBusy(s.getId())) {
                throw new RuntimeException("Sauvegarde ou restauration en cours sur ce serveur : réessaie dans un instant.");
            }
            long nodeId = s.getNode().getId();
            if (!agentWebSocketHandler.isNodeOnline(nodeId)) {
                throw new RuntimeException("La machine de ce serveur est hors ligne : réessaie plus tard.");
            }
            return new Target(s.getId(), nodeId, s.getVelocityName(), p.getMinecraftUsername());
        });
    }

    private JsonNode call(Target t, String type, Map<String, Object> data, int timeoutSeconds) throws Exception {
        data.put("server_id", t.serverId());
        JsonNode r;
        try {
            r = agentWebSocketHandler.sendCommand(t.nodeId(), type, data, timeoutSeconds)
                    .get(timeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            r = null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException) {
                r = null;
            } else {
                throw new RuntimeException(e.getCause() == null ? "erreur de la machine" : e.getCause().getMessage());
            }
        }
        if (r == null) {
            throw new RuntimeException("Pas de réponse de la machine (délai dépassé).");
        }
        if (r.has("success") && !r.get("success").asBoolean()) {
            if ("unknown_command".equals(r.path("error").asText())) {
                throw new RuntimeException("L'agent de la machine de ce serveur est trop ancien : mets-le à jour (--update-agent).");
            }
            throw new RuntimeException(r.path("message").asText("erreur inconnue"));
        }
        return r.path("result");
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public JsonNode list(Long serverId, Long playerId, String path) throws Exception {
        Target t = target(serverId, playerId, AccessService.Right.FILES, false);
        return call(t, "files_list", data("path", path == null ? "" : path), 30);
    }

    /** Lit jusqu'à length octets (8 Mo au plus) à partir de offset */
    public Content read(Long serverId, Long playerId, String path, long offset, int length) throws Exception {
        if (offset < 0) {
            throw new RuntimeException("Position invalide");
        }
        int want = length <= 0 ? MAX_REQUEST : Math.min(length, MAX_REQUEST);
        Target t = target(serverId, playerId, AccessService.Right.FILES, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long size = 0;
        long mtime = 0;
        long pos = offset;
        while (out.size() < want) {
            int n = Math.min(AGENT_CHUNK, want - out.size());
            JsonNode r = call(t, "files_read", data("path", path, "offset", pos, "length", n), 60);
            byte[] piece = Base64.getDecoder().decode(r.path("data").asText(""));
            size = r.path("size").asLong();
            mtime = r.path("mtime").asLong();
            out.write(piece);
            pos += piece.length;
            if (piece.length < n || pos >= size) {
                break;
            }
        }
        return new Content(out.toByteArray(), size, mtime);
    }

    /**
     * Écrit un morceau (8 Mo au plus) d'un téléversement à la position offset ; fin = le
     * fichier remplace sa cible ; abort = téléversement abandonné. Renvoie la taille écrite.
     */
    public long write(Long serverId, Long playerId, String path, long offset, byte[] body,
                      boolean fin, boolean abort) throws Exception {
        if (body != null && body.length > MAX_REQUEST) {
            throw new RuntimeException("Morceau trop gros (8 Mo au plus)");
        }
        Target t = target(serverId, playerId, AccessService.Right.FILES, true);
        if (abort) {
            call(t, "files_write", data("path", path, "abort", true), 30);
            return 0;
        }
        byte[] bytes = body == null ? new byte[0] : body;
        long size = offset;
        int pos = 0;
        do {
            int n = Math.min(AGENT_CHUNK, bytes.length - pos);
            byte[] piece = java.util.Arrays.copyOfRange(bytes, pos, pos + n);
            boolean last = pos + n >= bytes.length;
            JsonNode r = call(t, "files_write", data("path", path, "offset", offset + pos,
                    "data", Base64.getEncoder().encodeToString(piece), "final", fin && last), 120);
            size = r.path("size").asLong(offset + pos + n);
            pos += n;
        } while (pos < bytes.length);
        if (fin) {
            log.info("Fichiers {} : {} téléversé par {} ({} octets)", t.server(), path, t.player(), size);
        }
        return size;
    }

    public JsonNode mkdir(Long serverId, Long playerId, String path) throws Exception {
        Target t = target(serverId, playerId, AccessService.Right.FILES, true);
        JsonNode r = call(t, "files_mkdir", data("path", path), 30);
        log.info("Fichiers {} : dossier {} créé par {}", t.server(), path, t.player());
        return r;
    }

    public JsonNode rename(Long serverId, Long playerId, String from, String to) throws Exception {
        Target t = target(serverId, playerId, AccessService.Right.FILES, true);
        JsonNode r = call(t, "files_rename", data("from", from, "to", to), 60);
        log.info("Fichiers {} : {} renommé en {} par {}", t.server(), from, to, t.player());
        return r;
    }

    public JsonNode delete(Long serverId, Long playerId, String path) throws Exception {
        Target t = target(serverId, playerId, AccessService.Right.FILES, true);
        JsonNode r = call(t, "files_delete", data("path", path), 300);
        log.info("Fichiers {} : {} supprimé par {}", t.server(), path, t.player());
        return r;
    }

    /** source "game" (logs/latest.log, à partir de offset ; -1 = la fin) ou "docker" (fin du journal du conteneur) */
    public JsonNode logs(Long serverId, Long playerId, String source, long offset) throws Exception {
        Target t = target(serverId, playerId, AccessService.Right.CONSOLE, false);
        return call(t, "server_logs", data("source", "docker".equals(source) ? "docker" : "game", "offset", offset), 20);
    }
}
