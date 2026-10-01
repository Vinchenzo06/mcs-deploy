package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vinch.mcs.api.entities.*;
import vinch.mcs.api.repositories.*;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Déplacement d'un serveur vers une autre machine (lot 32).
 *
 * Machine d'origine en ligne : arrêt, sauvegarde au central (dépôt du serveur),
 * recréation sur la nouvelle machine à partir de cette sauvegarde, suppression de
 * l'ancienne copie, relance s'il tournait. Rien n'est perdu.
 *
 * Depuis une sauvegarde (machine d'origine hors ligne ou retirée) : recréation à
 * partir de la dernière sauvegarde du central ; ce qui a été fait après est perdu.
 * L'ancienne copie sera supprimée au retour de la machine (pending_deletions).
 *
 * Qui : admins (tout) ; propriétaire : choix de la région, ou relance depuis une
 * sauvegarde quand sa machine est hors ligne.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationService {

    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;
    private final BackupRepository backupRepository;
    private final PendingDeletionRepository pendingDeletionRepository;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AccessService accessService;
    private final ServerService serverService;
    private final BackupService backupService;
    private final VelocityClient velocityClient;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;

    private static final long CREATE_TIMEOUT_SECONDS = 3 * 3600;

    // Machines en cours d'évacuation
    private final Set<Long> evacuating = ConcurrentHashMap.newKeySet();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private record Plan(long serverId, String ref, long ownerId, long sourceNodeId, boolean sourceOnline,
                        boolean wasRunning, long targetNodeId, String targetLabel, Long backupId, boolean fromBackup,
                        String by) {}

    public static String normalizeRegion(String region) {
        if (region == null || region.isBlank()) {
            return null;
        }
        String r = region.trim().toLowerCase(Locale.ROOT);
        if (!r.matches("[a-z0-9-]{2,30}")) {
            throw new RuntimeException("Région invalide : " + region + " (ex. ca-est, eu-ouest).");
        }
        return r;
    }

    private static String label(Node n) {
        return "machine " + n.getId() + " (" + (n.getRegion() == null ? "?" : n.getRegion()) + ")";
    }

    // ------------------------------------------------------------ régions ----

    /** Régions et place libre (plus gros serveur possible) */
    public Map<String, Object> regions() {
        return tx().execute(status -> {
            Map<String, Map<String, Object>> byRegion = new TreeMap<>();
            for (Node n : nodeRepository.findAll()) {
                if (Boolean.TRUE.equals(n.getIsRevoked())) {
                    continue;
                }
                String r = n.getRegion() == null ? "?" : n.getRegion();
                Map<String, Object> m = byRegion.computeIfAbsent(r, k -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("region", k);
                    x.put("machines", 0);
                    x.put("online", 0);
                    return x;
                });
                m.put("machines", (Integer) m.get("machines") + 1);
                if (Boolean.TRUE.equals(n.getIsOnline()) && agentWebSocketHandler.isNodeOnline(n.getId())) {
                    m.put("online", (Integer) m.get("online") + 1);
                }
            }
            return Map.of("regions", new ArrayList<>(byRegion.values()));
        });
    }

    /** Admins : région d'une machine */
    public Map<String, Object> setRegion(Long playerId, Long machine, String region) {
        return tx().execute(status -> {
            if (!AccessService.isAdmin(accessService.player(playerId))) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            String r = normalizeRegion(region);
            if (r == null) {
                throw new RuntimeException("Région manquante.");
            }
            Node n = nodeRepository.findById(machine).orElseThrow(() -> new RuntimeException("Machine " + machine + " introuvable."));
            n.setRegion(r);
            nodeRepository.save(n);
            log.info("Machine {} : région {}", machine, r);
            return Map.of("machine", machine, "region", r);
        });
    }

    // ------------------------------------------------------------ déplacement ----

    /**
     * Déplace un serveur. region / targetNode : destination (aucune : la machine qui a
     * le plus de place, ailleurs). fromBackup : depuis une sauvegarde du central
     * (backupId null : la plus récente). Attend jusqu'à 20 min.
     */
    public Map<String, Object> migrate(Long serverId, Long playerId, String region, Long targetNode,
                                       boolean fromBackup, Long backupId) throws Exception {
        if (!backupService.isEnabled()) {
            throw new RuntimeException("Les sauvegardes ne sont pas encore configurées : impossible de déplacer un serveur.");
        }
        Plan plan = tx().execute(status -> prepare(serverId, playerId, normalizeRegion(region), targetNode, fromBackup, backupId, false));
        Map<String, Object> result = runWithLock(plan);
        if (playerId != null && plan.ownerId() != playerId && "SUCCESS".equals(result.get("status"))) {
            notifyOwner(serverId, plan, true);
        }
        return result;
    }

    private Plan prepare(Long serverId, Long playerId, String region, Long targetNode, boolean fromBackup,
                         Long backupId, boolean system) {
        Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
        Player p = playerId == null ? null : accessService.player(playerId);
        boolean admin = system || (p != null && AccessService.isAdmin(p));
        boolean owner = p != null && AccessService.isOwner(p, s);
        Node source = s.getNode();
        boolean sourceOnline = source != null && !Boolean.TRUE.equals(source.getIsRevoked())
                && agentWebSocketHandler.isNodeOnline(source.getId());
        if (!admin) {
            if (!owner) {
                throw new RuntimeException("Seuls le propriétaire du serveur et les admins peuvent le déplacer.");
            }
            if (targetNode != null) {
                throw new RuntimeException("Choisis une région ; le choix de la machine est réservé aux admins.");
            }
            if (fromBackup && sourceOnline) {
                throw new RuntimeException("La machine du serveur est en ligne : déplace-le sans sauvegarde, rien ne sera perdu.");
            }
        }
        if (s.getStatus() == ServerStatus.MIGRATING || s.getStatus() == ServerStatus.CREATING) {
            throw new RuntimeException("Le serveur est déjà en cours de création ou de déplacement.");
        }
        if (!fromBackup && !sourceOnline) {
            throw new RuntimeException("La machine du serveur est hors ligne : relance-le depuis sa dernière sauvegarde, "
                    + "/mcs migrate " + s.getName() + " backup");
        }

        // Destination
        Long exclude = source == null ? null : source.getId();
        Node target;
        if (targetNode != null) {
            target = nodeRepository.findById(targetNode).orElseThrow(() -> new RuntimeException("Machine " + targetNode + " introuvable."));
            if (exclude != null && exclude.equals(target.getId())) {
                throw new RuntimeException("Le serveur est déjà sur la machine " + targetNode + ".");
            }
            if (!agentWebSocketHandler.isNodeOnline(target.getId())) {
                throw new RuntimeException("La machine " + targetNode + " est hors ligne.");
            }
            if (!serverService.hasRoom(target, s.getAllocatedRamMb(), s.getAllocatedCpuCores())) {
                throw new RuntimeException("La machine " + targetNode + " n'a pas la place pour ce serveur (RAM, CPU ou disque).");
            }
        } else {
            target = serverService.pickNode(s.getAllocatedRamMb(), s.getAllocatedCpuCores(), exclude, region);
            if (target == null) {
                throw new RuntimeException(region == null
                        ? "Aucune autre machine en ligne n'a la place pour ce serveur en ce moment."
                        : "Aucune machine en ligne de la région " + region + " n'a la place pour ce serveur (voir /mcs regions).");
            }
        }

        // Sauvegarde de départ (depuis une sauvegarde) : la plus récente du central
        Long useBackup = null;
        if (fromBackup) {
            Backup b;
            if (backupId != null) {
                b = backupRepository.findById(backupId)
                        .filter(x -> Objects.equals(x.getServerTagId(), s.getId()) && "SUCCESS".equals(x.getStatus())
                                && !"LOCAL".equals(x.getLocation()))
                        .orElseThrow(() -> new RuntimeException("Sauvegarde n°" + backupId + " introuvable au central pour ce serveur."));
            } else {
                b = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS").stream()
                        .filter(x -> !"LOCAL".equals(x.getLocation()) && x.getSnapshotId() != null)
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Aucune sauvegarde de ce serveur au central."));
            }
            backupService.restoreData(b, target, s); // vérifie qu'elle est utilisable sur la cible
            useBackup = b.getId();
        }
        boolean running = s.getStatus() == ServerStatus.RUNNING || s.getStatus() == ServerStatus.STARTING;
        String ref = s.getOwner().getMinecraftUsername().toLowerCase(Locale.ROOT) + "/" + s.getName();
        return new Plan(s.getId(), ref, s.getOwner().getId(), source == null ? -1 : source.getId(), sourceOnline,
                running || (fromBackup && !sourceOnline), target.getId(), label(target), useBackup, fromBackup,
                system ? "MCS" : p.getMinecraftUsername());
    }

    private Map<String, Object> runWithLock(Plan plan) throws Exception {
        long lockNode = plan.sourceNodeId() > 0 ? plan.sourceNodeId() : plan.targetNodeId();
        if (!backupService.tryLock(plan.serverId(), lockNode)) {
            throw new RuntimeException("Une sauvegarde, une restauration ou un déplacement de ce serveur est déjà en cours.");
        }
        log.info("{} déplace {} vers la {}{}", plan.by(), plan.ref(), plan.targetLabel(),
                plan.fromBackup() ? " (depuis la sauvegarde " + plan.backupId() + ")" : "");
        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return run(plan);
            } finally {
                backupService.unlock(plan.serverId(), lockNode);
            }
        });
        try {
            return future.get(20, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            return Map.of("status", "RUNNING", "message", "Le déplacement continue en arrière-plan.",
                    "target", plan.targetLabel());
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private void setStatus(long serverId, ServerStatus st) {
        tx().executeWithoutResult(x -> serverRepository.findById(serverId).ifPresent(s -> {
            s.setStatus(st);
            serverRepository.save(s);
        }));
    }

    private Map<String, Object> run(Plan plan) {
        long serverId = plan.serverId();
        Long ownerPlayerId = tx().execute(x -> serverRepository.findById(serverId).map(s -> s.getOwner().getId()).orElse(null));

        // 1. Arrêt (machine d'origine en ligne)
        if (plan.sourceOnline() && plan.wasRunning()) {
            try {
                serverService.stopServer(serverId, ownerPlayerId);
            } catch (Exception e) {
                throw new RuntimeException("Arrêt impossible avant le déplacement : " + e.getMessage());
            }
        } else if (!plan.sourceOnline()) {
            try {
                velocityClient.unregisterServer(tx().execute(x -> serverRepository.findById(serverId).orElseThrow().getVelocityName()));
            } catch (Exception ignored) {
                // déjà retiré
            }
        }
        setStatus(serverId, ServerStatus.MIGRATING);

        // 2. Sauvegarde de départ
        long backupId;
        if (plan.fromBackup()) {
            backupId = plan.backupId();
        } else {
            Backup b = backupService.backupCentralNow(serverId, EnumSet.noneOf(BackupKind.class), BackupType.PRE_MIGRATION, plan.by());
            if (!"SUCCESS".equals(b.getStatus())) {
                rollback(plan, ownerPlayerId);
                throw new RuntimeException("Sauvegarde avant déplacement échouée : " + b.getMessage());
            }
            backupId = b.getId();
        }

        // 3. Recréation sur la nouvelle machine
        Map<String, Object> data;
        int port;
        int storage;
        try {
            Object[] prepared = tx().execute(x -> {
                Server s = serverRepository.findById(serverId).orElseThrow();
                Node target = nodeRepository.findById(plan.targetNodeId()).orElseThrow();
                Backup b = backupRepository.findById(backupId).orElseThrow();
                Map<String, Object> restore = backupService.restoreData(b, target, s);
                int p = serverService.freePortOn(target);
                int q = ServerService.quotaOn(target, s.getAllocatedRamMb());
                return new Object[]{ServerService.createData(s, p, q, restore), p, q};
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) prepared[0];
            data = d;
            port = (Integer) prepared[1];
            storage = (Integer) prepared[2];
        } catch (Exception e) {
            rollback(plan, ownerPlayerId);
            throw new RuntimeException("Préparation du déplacement : " + e.getMessage());
        }
        // Réserve le port tout de suite (personne d'autre ne le prend pendant la copie)
        tx().executeWithoutResult(x -> serverRepository.findById(serverId).ifPresent(s -> {
            originalPorts.put(serverId, s.getTunnelPort());
            s.setTunnelPort(port);
            s.setLocalPort(port);
            serverRepository.save(s);
        }));
        String error = null;
        try {
            JsonNode result = agentWebSocketHandler.sendCommand(plan.targetNodeId(), "create_server", data, CREATE_TIMEOUT_SECONDS)
                    .get(CREATE_TIMEOUT_SECONDS + 60, TimeUnit.SECONDS);
            if (result == null || (result.has("success") && !result.get("success").asBoolean())) {
                error = result == null ? "pas de réponse de la machine"
                        : result.path("message").asText(result.path("error").asText("erreur inconnue"));
            }
        } catch (Exception e) {
            error = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        }
        if (error != null) {
            log.warn("Déplacement de {} vers la {} échoué : {}", plan.ref(), plan.targetLabel(), error);
            // Nettoyage de ce qui a pu être créé sur la cible
            agentWebSocketHandler.sendCommand(plan.targetNodeId(), "delete_server",
                    Map.of("server_id", serverId, "delete_data", true));
            rollback(plan, ownerPlayerId);
            throw new RuntimeException("Déplacement échoué : " + error
                    + (plan.sourceOnline() ? " (le serveur reste sur sa machine)" : ""));
        }

        // 4. Le serveur est sur la nouvelle machine
        String velocityName = tx().execute(x -> {
            Server s = serverRepository.findById(serverId).orElseThrow();
            Node target = nodeRepository.findById(plan.targetNodeId()).orElseThrow();
            s.setNode(target);
            s.setLocalPort(port);
            s.setTunnelPort(port);
            s.setAllocatedStorageMb(storage);
            s.setStatus(ServerStatus.RUNNING);
            s.setLastStartedAt(LocalDateTime.now());
            serverRepository.save(s);
            originalPorts.remove(serverId);
            // Les sauvegardes gardées sur l'ancienne machine partent avec elle
            for (Backup b : backupRepository.findByServerTagId(serverId)) {
                if ("LOCAL".equals(b.getLocation()) && ("SUCCESS".equals(b.getStatus()) || "EXPIRED".equals(b.getStatus()))) {
                    b.setStatus("DELETED");
                    b.setMessage("Restée sur l'ancienne machine (serveur déplacé)");
                    backupRepository.save(b);
                }
            }
            return s.getVelocityName();
        });
        try {
            velocityClient.registerServer(velocityName, "127.0.0.1", port);
        } catch (Exception e) {
            log.warn("Enregistrement Velocity de {} : {}", velocityName, e.getMessage());
        }
        boolean stoppedAgain = false;
        if (!plan.wasRunning()) {
            try {
                serverService.stopServer(serverId, ownerPlayerId);
                stoppedAgain = true;
            } catch (Exception e) {
                log.warn("Arrêt de {} après déplacement : {}", plan.ref(), e.getMessage());
            }
        }

        // 5. Ancienne copie : supprimée (ou plus tard, au retour de la machine)
        if (plan.sourceNodeId() > 0) {
            boolean deleted = false;
            if (agentWebSocketHandler.isNodeOnline(plan.sourceNodeId())) {
                try {
                    JsonNode r = agentWebSocketHandler.sendCommand(plan.sourceNodeId(), "delete_server",
                            Map.of("server_id", serverId, "delete_data", true)).get(6, TimeUnit.MINUTES);
                    deleted = r != null && !(r.has("success") && !r.get("success").asBoolean());
                } catch (Exception e) {
                    log.warn("Suppression de l'ancienne copie de {} : {}", plan.ref(), e.getMessage());
                }
            }
            if (!deleted) {
                tx().executeWithoutResult(x -> {
                    if (!pendingDeletionRepository.existsByNodeIdAndServerId(plan.sourceNodeId(), serverId)) {
                        pendingDeletionRepository.save(PendingDeletion.builder()
                                .nodeId(plan.sourceNodeId()).serverId(serverId).build());
                    }
                });
            }
        }
        // L'ancienne machine ne peut plus lire le dépôt du serveur
        backupService.rotateRepoAccess(serverId);

        log.info("{} déplacé vers la {}", plan.ref(), plan.targetLabel());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "SUCCESS");
        out.put("target", plan.targetLabel());
        out.put("fromBackup", plan.fromBackup());
        out.put("backupId", backupId);
        out.put("running", plan.wasRunning() && !stoppedAgain);
        return out;
    }

    /** Échec : le serveur revient sur sa machine d'origine, dans son état d'avant */
    private void rollback(Plan plan, Long ownerPlayerId) {
        Integer port = originalPorts.remove(plan.serverId());
        tx().executeWithoutResult(x -> serverRepository.findById(plan.serverId()).ifPresent(s -> {
            s.setStatus(plan.sourceOnline() ? ServerStatus.STOPPED : ServerStatus.ERROR);
            if (port != null) {
                s.setTunnelPort(port);
                s.setLocalPort(port);
            }
            serverRepository.save(s);
        }));
        if (plan.sourceOnline() && plan.wasRunning()) {
            try {
                serverService.startServer(plan.serverId(), ownerPlayerId);
            } catch (Exception e) {
                log.warn("Relance de {} après échec du déplacement : {}", plan.ref(), e.getMessage());
            }
        }
    }

    // Port d'origine des serveurs en cours de déplacement (pour revenir en arrière)
    private final Map<Long, Integer> originalPorts = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ machines (admins) ----

    /** Admins : sauvegarde maintenant de tous les serveurs d'une machine, au central */
    public Map<String, Object> backupMachine(Long playerId, Long machine) {
        List<Long> ids = tx().execute(x -> {
            Player p = accessService.player(playerId);
            if (!AccessService.isAdmin(p)) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            if (!agentWebSocketHandler.isNodeOnline(machine)) {
                throw new RuntimeException("La machine " + machine + " est hors ligne.");
            }
            return serverRepository.findByNodeId(machine).stream().map(Server::getId).toList();
        });
        String by = tx().execute(x -> accessService.player(playerId).getMinecraftUsername());
        CompletableFuture.runAsync(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (Long id : ids) {
                if (!backupService.tryLock(id, machine)) {
                    failed.add("#" + id + " (occupé)");
                    continue;
                }
                try {
                    Backup b = backupService.backupCentralNow(id, EnumSet.of(BackupKind.MANUAL), BackupType.MANUAL, by);
                    if ("SUCCESS".equals(b.getStatus())) {
                        ok++;
                    } else {
                        failed.add(b.getServerRef());
                    }
                } catch (Exception e) {
                    failed.add("#" + id);
                } finally {
                    backupService.unlock(id, machine);
                }
            }
            notificationService.notify(playerId, "Machine " + machine + " : " + ok + "/" + ids.size()
                    + " serveur(s) sauvegardé(s) au central" + (failed.isEmpty() ? "." : ". Échecs : " + String.join(", ", failed)));
        });
        return Map.of("status", "RUNNING", "servers", ids.size());
    }

    /**
     * Admins : déplace tous les serveurs d'une machine (vers "target", ou la machine
     * qui a le plus de place, même région d'abord). Un à la fois ; rapport à la fin.
     */
    public Map<String, Object> evacuate(Long playerId, Long machine, Long target, boolean fromBackup) {
        List<Long> ids = tx().execute(x -> {
            Player p = accessService.player(playerId);
            if (!AccessService.isAdmin(p)) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            nodeRepository.findById(machine).orElseThrow(() -> new RuntimeException("Machine " + machine + " introuvable."));
            return serverRepository.findByNodeId(machine).stream().map(Server::getId).toList();
        });
        if (ids.isEmpty()) {
            return Map.of("status", "SUCCESS", "servers", 0);
        }
        if (!evacuating.add(machine)) {
            throw new RuntimeException("La machine " + machine + " est déjà en cours d'évacuation.");
        }
        CompletableFuture.runAsync(() -> {
            List<String> done = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            try {
                for (Long id : ids) {
                    String region = tx().execute(x -> nodeRepository.findById(machine).map(Node::getRegion).orElse(null));
                    try {
                        Plan plan;
                        try {
                            plan = tx().execute(x -> prepare(id, playerId, target == null ? region : null, target, fromBackup, null, false));
                        } catch (RuntimeException e) {
                            if (target != null || region == null) {
                                throw e;
                            }
                            // Pas de place dans la même région : n'importe où
                            plan = tx().execute(x -> prepare(id, playerId, null, null, fromBackup, null, false));
                        }
                        Map<String, Object> r = runWithLockBlocking(plan);
                        done.add(plan.ref() + " → " + r.get("target"));
                        notifyOwner(id, plan, true);
                    } catch (Exception e) {
                        failed.add("#" + id + " : " + e.getMessage());
                    }
                }
            } finally {
                evacuating.remove(machine);
            }
            notificationService.notify(playerId, "Évacuation de la machine " + machine + " : " + done.size() + "/" + ids.size()
                    + " serveur(s) déplacé(s)." + (failed.isEmpty() ? "" : " Échecs : " + String.join(" ; ", failed)));
        });
        return Map.of("status", "RUNNING", "servers", ids.size());
    }

    /** Comme runWithLock, sans limite d'attente (évacuation en arrière-plan) */
    private Map<String, Object> runWithLockBlocking(Plan plan) {
        long lockNode = plan.sourceNodeId() > 0 ? plan.sourceNodeId() : plan.targetNodeId();
        if (!backupService.tryLock(plan.serverId(), lockNode)) {
            throw new RuntimeException("occupé (sauvegarde ou restauration en cours)");
        }
        try {
            return run(plan);
        } finally {
            backupService.unlock(plan.serverId(), lockNode);
        }
    }

    private void notifyOwner(long serverId, Plan plan, boolean byAdmin) {
        if (!byAdmin) {
            return;
        }
        String name = plan.ref().substring(plan.ref().indexOf('/') + 1);
        notificationService.notify(plan.ownerId(), "Ton serveur " + name + " a été déplacé sur une autre machine ("
                + plan.targetLabel() + ")" + (plan.fromBackup()
                ? " à partir de sa dernière sauvegarde : ce qui a été fait après est perdu."
                : ", sans rien perdre."));
    }
}
