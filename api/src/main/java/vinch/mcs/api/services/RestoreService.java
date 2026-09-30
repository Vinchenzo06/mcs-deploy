package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vinch.mcs.api.dto.CreateServerRequest;
import vinch.mcs.api.dto.CreateServerResponse;
import vinch.mcs.api.entities.*;
import vinch.mcs.api.repositories.BackupRepository;
import vinch.mcs.api.repositories.NodeRepository;
import vinch.mcs.api.repositories.PlayerRepository;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Restauration (propriétaire et admins seulement) :
 *  - d'un serveur existant : arrêt s'il tourne, données remplacées par la sauvegarde,
 *    redémarrage s'il tournait. Pas de sauvegarde de l'état actuel avant (choix de Vincent) ;
 *  - d'un serveur supprimé (sauvegardes encore gardées au central) : le serveur est
 *    recréé sur la même machine avec ses réglages, ses données restaurées avant le
 *    premier démarrage, et ses anciennes sauvegardes lui sont rattachées.
 * Une sauvegarde du central n'est restaurée que sur la machine qui l'a faite (ses
 * identifiants ne vont jamais à une autre machine) ; ailleurs : lot 32 (migration).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestoreService {

    private final ServerRepository serverRepository;
    private final BackupRepository backupRepository;
    private final NodeRepository nodeRepository;
    private final PlayerRepository playerRepository;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AccessService accessService;
    private final ServerService serverService;
    private final BackupService backupService;
    private final PlatformTransactionManager transactionManager;

    private static final long AGENT_TIMEOUT_SECONDS = 2 * 3600;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private record Plan(long serverId, long nodeId, String ref, boolean wasRunning, Map<String, Object> data, long ownerId) {}

    private static void requireOwnerOrAdmin(Player p, Server s) {
        if (!AccessService.isAdmin(p) && !AccessService.isOwner(p, s)) {
            throw new RuntimeException("Seuls le propriétaire du serveur et les admins peuvent restaurer une sauvegarde.");
        }
    }

    /** Restaure une sauvegarde d'un serveur existant ; attend jusqu'à 20 min */
    public Map<String, Object> restore(Long serverId, Long backupId, Long playerId) throws Exception {
        if (!backupService.isEnabled()) {
            throw new RuntimeException("Les sauvegardes ne sont pas encore configurées sur ce réseau.");
        }
        Plan plan = tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            requireOwnerOrAdmin(p, s);
            Backup b = backupRepository.findById(backupId)
                    .filter(x -> Objects.equals(x.getServerTagId(), s.getId()) && "SUCCESS".equals(x.getStatus()))
                    .orElseThrow(() -> new RuntimeException("Sauvegarde n°" + backupId + " introuvable pour ce serveur."));
            Node node = s.getNode();
            if (node == null || !agentWebSocketHandler.isNodeOnline(node.getId())) {
                throw new RuntimeException("La machine qui héberge ce serveur est hors ligne.");
            }
            Map<String, Object> data = backupService.restoreData(b, node, s);
            data.put("server_id", s.getId());
            boolean running = s.getStatus() == ServerStatus.RUNNING || s.getStatus() == ServerStatus.STARTING;
            return new Plan(s.getId(), node.getId(), s.getVelocityName(), running, data, s.getOwner().getId());
        });
        if (!backupService.tryLock(plan.serverId(), plan.nodeId())) {
            throw new RuntimeException("Une sauvegarde ou une restauration de ce serveur est déjà en cours.");
        }
        String by = tx().execute(status -> accessService.player(playerId).getMinecraftUsername());
        log.info("{} restaure {} depuis la sauvegarde {}", by, plan.ref(), backupId);

        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return runRestore(plan, backupId, playerId);
            } finally {
                backupService.unlock(plan.serverId(), plan.nodeId());
            }
        });
        try {
            return future.get(20, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            return Map.of("status", "RUNNING", "message", "La restauration continue en arrière-plan.");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private Map<String, Object> runRestore(Plan plan, Long backupId, Long playerId) {
        // 1. Arrêt (les joueurs sont renvoyés au lobby)
        if (plan.wasRunning()) {
            try {
                serverService.stopServer(plan.serverId(), playerId);
            } catch (Exception e) {
                throw new RuntimeException("Arrêt impossible avant la restauration : " + e.getMessage());
            }
        }
        // 2. Restauration par l'agent
        Map<String, Object> out = new LinkedHashMap<>();
        String error = null;
        try {
            JsonNode result = agentWebSocketHandler.sendCommand(plan.nodeId(), "restore_server", plan.data(), AGENT_TIMEOUT_SECONDS)
                    .get(AGENT_TIMEOUT_SECONDS + 60, TimeUnit.SECONDS);
            if (result == null || (result.has("success") && !result.get("success").asBoolean())) {
                error = result == null ? "pas de réponse de la machine"
                        : result.path("message").asText(result.path("error").asText("erreur inconnue"));
            } else {
                out.put("restoredMb", result.path("result").path("restored_mb").asLong());
                out.put("seconds", result.path("result").path("seconds").asLong());
            }
        } catch (Exception e) {
            error = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        }
        if (error != null) {
            log.warn("Restauration de {} (sauvegarde {}) échouée : {}", plan.ref(), backupId, error);
        } else {
            log.info("{} restauré depuis la sauvegarde {}", plan.ref(), backupId);
        }
        // 3. Redémarrage s'il tournait (même après un échec : les données n'ont pas bougé)
        boolean restarted = false;
        String restartError = null;
        if (plan.wasRunning()) {
            try {
                serverService.startServer(plan.serverId(), playerId);
                restarted = true;
            } catch (Exception e) {
                restartError = e.getMessage();
            }
        }
        if (error != null) {
            throw new RuntimeException("Restauration échouée : " + error
                    + (plan.wasRunning() ? (restarted ? " (le serveur a redémarré tel quel)" : "") : ""));
        }
        out.put("status", "SUCCESS");
        out.put("backupId", backupId);
        out.put("wasRunning", plan.wasRunning());
        out.put("restarted", restarted);
        if (restartError != null) {
            out.put("restartError", restartError);
        }
        return out;
    }

    // ------------------------------------------------------------ serveurs supprimés ----

    /** Sauvegardes encore gardées des serveurs supprimés du joueur (admins : toutes) */
    public Map<String, Object> deleted(Long playerId) {
        return tx().execute(status -> {
            Player p = accessService.player(playerId);
            boolean admin = AccessService.isAdmin(p);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Backup b : backupRepository.findByServerIsNullAndStatus("SUCCESS")) {
                if (!admin && !Objects.equals(b.getOwnerId(), p.getId())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.getId());
                m.put("name", b.getServerName());
                m.put("ref", b.getServerRef());
                m.put("createdAt", b.getCreatedAt() == null ? null : b.getCreatedAt().toString());
                m.put("expiresAt", b.getExpiresAt() == null ? null : b.getExpiresAt().toString());
                m.put("totalMb", b.getTotalMb());
                m.put("location", b.getLocation());
                m.put("restorable", b.getServerName() != null && "CENTRAL".equals(b.getLocation()));
                if (admin && b.getOwnerId() != null) {
                    playerRepository.findById(b.getOwnerId()).ifPresent(o -> m.put("owner", o.getMinecraftUsername()));
                }
                List<String> kinds = new ArrayList<>();
                for (BackupKind k : BackupKind.values()) {
                    if (b.is(k)) {
                        kinds.add(k.name());
                    }
                }
                m.put("kinds", kinds);
                items.add(m);
            }
            items.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("createdAt"))).reversed());
            return Map.of("backups", items.size() > 30 ? items.subList(0, 30) : items);
        });
    }

    private record Recreate(CreateServerRequest request, Map<String, Object> restore, long oldTag, String name) {}

    /**
     * Recrée un serveur supprimé à partir d'une de ses sauvegardes (propriétaire ou
     * admin). Le serveur revient au propriétaire d'origine, avec ses réglages, sur la
     * machine de la sauvegarde ; ses quotas (nombre, RAM, CPU) s'appliquent.
     */
    public Map<String, Object> restoreDeleted(Long backupId, Long playerId, String newName) throws Exception {
        if (!backupService.isEnabled()) {
            throw new RuntimeException("Les sauvegardes ne sont pas encore configurées sur ce réseau.");
        }
        Recreate plan = tx().execute(status -> {
            Player p = accessService.player(playerId);
            Backup b = backupRepository.findById(backupId)
                    .filter(x -> x.getServer() == null && "SUCCESS".equals(x.getStatus()))
                    .orElseThrow(() -> new RuntimeException("Sauvegarde n°" + backupId + " introuvable parmi les serveurs supprimés."));
            if (!AccessService.isAdmin(p) && !Objects.equals(b.getOwnerId(), p.getId())) {
                throw new RuntimeException("Sauvegarde n°" + backupId + " introuvable parmi les serveurs supprimés.");
            }
            if (b.getServerName() == null || b.getServerType() == null) {
                throw new RuntimeException("Cette sauvegarde vient d'un serveur supprimé avant la mise à jour : "
                        + "ses réglages ne sont pas connus, il ne peut pas être recréé.");
            }
            Node node = nodeRepository.findById(b.getNodeId())
                    .filter(n -> !Boolean.TRUE.equals(n.getIsRevoked()))
                    .orElseThrow(() -> new RuntimeException("La machine de cette sauvegarde n'existe plus."));
            Map<String, Object> restore = backupService.restoreData(b, node, null);
            String name = newName == null || newName.isBlank() ? b.getServerName() : newName.trim().toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9-]{3,32}")) {
                throw new RuntimeException("Nom invalide : " + name + " (3 à 32 caractères : lettres minuscules, chiffres et -).");
            }
            if (serverRepository.findByOwnerIdAndName(b.getOwnerId(), name).isPresent()) {
                throw new RuntimeException("Un serveur nommé '" + name + "' existe déjà : choisis un autre nom, "
                        + "ex. /mcs restore deleted " + backupId + " " + name + "2");
            }
            CreateServerRequest req = new CreateServerRequest();
            req.setOwnerPlayerId(b.getOwnerId());
            req.setName(name);
            req.setDisplayName(name);
            req.setServerType(ServerType.valueOf(b.getServerType()));
            req.setMinecraftVersion(b.getMinecraftVersion());
            req.setRamMb(b.getRamMb());
            req.setCpuCores(b.getCpuCores());
            req.setForceNodeId(node.getId());
            return new Recreate(req, restore, b.getServerTagId(), name);
        });
        log.info("Recréation du serveur supprimé {} depuis la sauvegarde {}", plan.name(), backupId);

        CompletableFuture<CreateServerResponse> future = CompletableFuture.supplyAsync(() -> {
            try {
                CreateServerResponse created = serverService.createServer(plan.request(), plan.restore());
                // Les anciennes sauvegardes suivent le nouveau serveur (et ses règles)
                tx().executeWithoutResult(status -> {
                    Server s = serverRepository.findById(created.getServerId()).orElseThrow();
                    for (Backup x : backupRepository.findByServerIsNullAndStatus("SUCCESS")) {
                        if (Objects.equals(x.getServerTagId(), plan.oldTag())) {
                            x.setServer(s);
                            x.setServerTagId(s.getId());
                            x.setServerRef(s.getVelocityName());
                            x.setExpiresAt(null);
                            backupRepository.save(x);
                        }
                    }
                });
                return created;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
        try {
            CreateServerResponse created = future.get(20, TimeUnit.MINUTES);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "SUCCESS");
            out.put("serverId", created.getServerId());
            out.put("name", created.getName());
            return out;
        } catch (TimeoutException e) {
            return Map.of("status", "RUNNING", "message", "La recréation continue en arrière-plan.", "name", plan.name());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            while (c != null && c.getCause() != null && c.getMessage() != null && c.getMessage().equals(c.getCause().getMessage())) {
                c = c.getCause();
            }
            throw new RuntimeException(c != null ? c.getMessage() : e.getMessage());
        }
    }
}
