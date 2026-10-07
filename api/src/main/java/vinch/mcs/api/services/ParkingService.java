package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vinch.mcs.api.entities.*;
import vinch.mcs.api.repositories.BackupRepository;
import vinch.mcs.api.repositories.NodeRepository;
import vinch.mcs.api.repositories.PendingDeletionRepository;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rangement des serveurs (lot 33), inspiré du « Limbo » de Play Hosting et d'Aternos.
 *
 * La seule vraie version d'un serveur arrêté est son rangement au central (sauvegarde
 * cachée, faite à chaque arrêt, quelle qu'en soit la cause). La copie sur la machine
 * n'est qu'un cache pour redémarrer vite ; elle n'est utilisée que si elle est à jour.
 *
 *   DIRTY : il a tourné depuis le dernier rangement (la copie de la machine est la
 *           plus récente) -> rangement 2 min après l'arrêt ; démarre sur sa machine.
 *   CLEAN : copie de la machine = rangement -> démarre sur sa machine si elle a la
 *           place, sinon sur une autre à partir du rangement (répartition de la charge).
 *   COLD  : plus de copie sur la machine (inactif 30 jours, ou place libérée) ->
 *           démarre sur la machine qui a la place, à partir du rangement.
 *   LOST  : sa machine est hors ligne depuis 24 h avec des données non rangées ->
 *           repart de sa dernière sauvegarde au central (propriétaire prévenu). Si la
 *           machine revient avant, il redevient DIRTY (rien de perdu).
 *
 * Une seule copie fait foi : quand un serveur démarre ailleurs, l'ancienne copie est
 * supprimée (tout de suite, ou au retour de sa machine). Fichiers modifiés serveur
 * arrêté (futur panneau web) : sur la copie de la machine, puis nouveau rangement.
 *
 * Inactif 6 mois : avertissement 14 jours avant, puis suppression (sauvegardes gardées
 * le temps de leur durée de vie, comme toute suppression).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParkingService {

    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;
    private final BackupRepository backupRepository;
    private final PendingDeletionRepository pendingDeletionRepository;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AccessService accessService;
    private final ServerService serverService;
    private final BackupService backupService;
    private final MigrationService migrationService;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;

    static final Duration PARK_DELAY = Duration.ofMinutes(2);
    static final Duration PARK_RETRY = Duration.ofMinutes(15);
    static final Duration COLD_AFTER = Duration.ofDays(30);
    static final Duration LOST_AFTER = Duration.ofHours(24);
    static final Duration DELETE_AFTER = Duration.ofDays(182);
    static final Duration WARN_BEFORE = Duration.ofDays(14);

    // Dernier essai de rangement raté (pour ne pas réessayer en boucle)
    private final Map<Long, LocalDateTime> failedParks = new ConcurrentHashMap<>();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static String name(Server s) {
        return s.getName();
    }

    // ------------------------------------------------------------ démarrage ----

    private record StartPlan(String mode, Long backupId, boolean avoidSource) {}

    /**
     * Démarre un serveur là où c'est possible. Sur sa machine si sa copie est à jour
     * et qu'elle a la place ; sinon sur une autre à partir de son rangement.
     */
    public Map<String, Object> start(Long serverId, Long playerId) throws Exception {
        StartPlan plan = tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur non trouvé : " + serverId));
            accessService.require(accessService.player(playerId), s, AccessService.Right.POWER);
            if (s.getStatus() == ServerStatus.RUNNING || s.getStatus() == ServerStatus.STARTING) {
                throw new RuntimeException("Le serveur est déjà en marche");
            }
            if (s.getStatus() == ServerStatus.MIGRATING || s.getStatus() == ServerStatus.CREATING) {
                throw new RuntimeException("Le serveur est en train de changer de machine : il démarrera tout seul.");
            }
            if (backupService.isBusy(s.getId())) {
                throw new RuntimeException("Rangement ou sauvegarde de ce serveur en cours : réessaie dans un instant.");
            }
            Node node = s.getNode();
            boolean nodeOk = node != null && !Boolean.TRUE.equals(node.getIsRevoked())
                    && agentWebSocketHandler.isNodeOnline(node.getId());
            String state = s.getParkState();
            switch (state) {
                case "DIRTY" -> {
                    // Ses données les plus récentes ne sont que sur sa machine
                    if (!nodeOk) {
                        throw new RuntimeException("La machine de ce serveur est hors ligne et ses dernières données y sont "
                                + "encore. Réessaie plus tard, ou repars de sa dernière sauvegarde : /mcs migrate "
                                + name(s) + " backup");
                    }
                    if (!serverService.hasRunRoom(s)) {
                        throw new RuntimeException("La machine de ce serveur est pleine pour l'instant (ses dernières données "
                                + "n'y sont pas encore rangées) : réessaie dans quelques minutes.");
                    }
                    return new StartPlan("HERE", null, false);
                }
                case "CLEAN" -> {
                    if (nodeOk && serverService.hasRunRoom(s)) {
                        return new StartPlan("HERE", null, false);
                    }
                    return new StartPlan("MOVE", s.getParkedBackupId(), true);
                }
                case "COLD" -> {
                    return new StartPlan("MOVE", s.getParkedBackupId(), false);
                }
                default -> {
                    // LOST : dernière sauvegarde au central, quelle qu'elle soit
                    return new StartPlan("MOVE", null, true);
                }
            }
        });
        if ("HERE".equals(plan.mode())) {
            serverService.startServer(serverId, playerId);
            return Map.of("status", "SUCCESS", "moved", false);
        }
        Map<String, Object> r = migrationService.relocateForStart(serverId, plan.backupId(), plan.avoidSource());
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("moved", true);
        return out;
    }

    // ------------------------------------------------------------ tâche de fond ----

    @Scheduled(initialDelay = 90_000, fixedDelay = 120_000)
    public void tick() {
        if (!backupService.isEnabled()) {
            return;
        }
        try {
            trackOfflineNodes();
            parkStopped();
            coolInactive();
            deleteAbandoned();
        } catch (Exception e) {
            log.warn("Rangement des serveurs : {}", e.getMessage());
        }
    }

    /** Serveurs arrêtés dont les données ne sont pas rangées : rangement au central */
    private void parkStopped() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> todo = tx().execute(x -> {
            List<Long> ids = new ArrayList<>();
            for (Server s : serverRepository.findAll()) {
                if (!"DIRTY".equals(s.getParkState()) || s.getStatus() != ServerStatus.STOPPED || s.getNode() == null
                        || !agentWebSocketHandler.isNodeOnline(s.getNode().getId()) || backupService.isBusy(s.getId())) {
                    continue;
                }
                LocalDateTime stopped = s.getLastStoppedAt() == null ? s.getCreatedAt() : s.getLastStoppedAt();
                if (stopped.plus(PARK_DELAY).isAfter(now)) {
                    continue; // laisse le temps d'un redémarrage
                }
                LocalDateTime failed = failedParks.get(s.getId());
                if (failed != null && failed.plus(PARK_RETRY).isAfter(now)) {
                    continue;
                }
                ids.add(s.getId());
            }
            return ids;
        });
        for (Long id : todo == null ? List.<Long>of() : todo) {
            park(id);
        }
    }

    /** Range un serveur arrêté (synchrone) ; true si c'est fait */
    public boolean park(long serverId) {
        Long nodeId = tx().execute(x -> serverRepository.findById(serverId)
                .map(s -> s.getNode() == null ? null : s.getNode().getId()).orElse(null));
        if (nodeId == null || !backupService.tryLock(serverId, nodeId)) {
            return false;
        }
        try {
            // Toujours arrêté et pas rangé ?
            boolean still = Boolean.TRUE.equals(tx().execute(x -> serverRepository.findById(serverId)
                    .map(s -> s.getStatus() == ServerStatus.STOPPED && "DIRTY".equals(s.getParkState())).orElse(false)));
            if (!still) {
                return false;
            }
            Backup b = backupService.parkNow(serverId);
            if (!"SUCCESS".equals(b.getStatus())) {
                failedParks.put(serverId, LocalDateTime.now());
                log.warn("Rangement de {} raté : {}", b.getServerRef(), b.getMessage());
                return false;
            }
            failedParks.remove(serverId);
            return Boolean.TRUE.equals(tx().execute(x -> serverRepository.findById(serverId).map(s -> {
                // Démarré pendant le rangement : il redeviendra DIRTY de toute façon
                if (s.getStatus() != ServerStatus.STOPPED) {
                    return false;
                }
                s.setParkState("CLEAN");
                s.setParkedBackupId(b.getId());
                serverRepository.save(s);
                log.info("{} rangé au central (sauvegarde {})", s.getVelocityName(), b.getId());
                return true;
            }).orElse(false)));
        } finally {
            backupService.unlock(serverId, nodeId);
        }
    }

    /** Copies rangées de serveurs arrêtés depuis 30 jours : elles quittent la machine */
    private void coolInactive() {
        LocalDateTime limit = LocalDateTime.now().minus(COLD_AFTER);
        tx().executeWithoutResult(x -> {
            for (Server s : serverRepository.findAll()) {
                if (ServerService.evictable(s) && s.getLastStoppedAt() != null && s.getLastStoppedAt().isBefore(limit)) {
                    serverService.evictCopy(s);
                }
            }
        });
    }

    /**
     * Machines hors ligne : date de début ; après 24 h, leurs serveurs aux données non
     * rangées partent de leur dernière sauvegarde (LOST), propriétaires prévenus. Une
     * machine revenue : ses serveurs LOST redeviennent DIRTY (rien de perdu).
     */
    private void trackOfflineNodes() {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> messages = new ArrayList<>();
        tx().executeWithoutResult(x -> {
            for (Node n : nodeRepository.findAll()) {
                if (Boolean.TRUE.equals(n.getIsRevoked())) {
                    continue;
                }
                boolean online = agentWebSocketHandler.isNodeOnline(n.getId());
                if (online) {
                    if (n.getOfflineSince() != null) {
                        n.setOfflineSince(null);
                        nodeRepository.save(n);
                    }
                    for (Server s : serverRepository.findByNodeId(n.getId())) {
                        if ("LOST".equals(s.getParkState())) {
                            s.setParkState("DIRTY");
                            serverRepository.save(s);
                            messages.add(new Object[]{s.getOwner().getId(), "La machine de ton serveur " + name(s)
                                    + " est revenue : il repart de ses données les plus récentes, rien n'est perdu."});
                        }
                    }
                    continue;
                }
                if (n.getOfflineSince() == null) {
                    n.setOfflineSince(now);
                    nodeRepository.save(n);
                    continue;
                }
                if (n.getOfflineSince().plus(LOST_AFTER).isAfter(now)) {
                    continue;
                }
                for (Server s : serverRepository.findByNodeId(n.getId())) {
                    if ("DIRTY".equals(s.getParkState())) {
                        s.setParkState("LOST");
                        if (s.getStatus() == ServerStatus.RUNNING || s.getStatus() == ServerStatus.STARTING) {
                            s.setStatus(ServerStatus.STOPPED);
                            s.setLastStoppedAt(now);
                        }
                        serverRepository.save(s);
                        Backup last = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS")
                                .stream().filter(b -> !"LOCAL".equals(b.getLocation()) && b.getSnapshotId() != null)
                                .findFirst().orElse(null);
                        messages.add(new Object[]{s.getOwner().getId(), "La machine de ton serveur " + name(s)
                                + " est hors ligne depuis 24 h. " + (last == null
                                ? "Il n'a pas encore de sauvegarde : il faudra attendre le retour de sa machine."
                                : "À son prochain démarrage (/mcs start " + name(s) + "), il repartira sur une autre machine "
                                  + "depuis sa sauvegarde du " + date(last.getCreatedAt()) + " : ce qui a été fait après sera perdu "
                                  + "si sa machine ne revient pas avant.")});
                    }
                }
            }
        });
        for (Object[] m : messages) {
            notificationService.notify((Long) m[0], (String) m[1]);
        }
    }

    private static String date(LocalDateTime t) {
        return String.format("%02d/%02d à %02dh%02d", t.getDayOfMonth(), t.getMonthValue(), t.getHour(), t.getMinute());
    }

    /** Pas démarré depuis 6 mois : avertissement 14 jours avant, puis suppression */
    private void deleteAbandoned() {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> warn = new ArrayList<>();
        List<Long> delete = new ArrayList<>();
        tx().executeWithoutResult(x -> {
            for (Server s : serverRepository.findAll()) {
                if (s.getStatus() != ServerStatus.STOPPED || backupService.isBusy(s.getId())) {
                    continue;
                }
                LocalDateTime last = s.getLastStartedAt() != null ? s.getLastStartedAt() : s.getCreatedAt();
                if (last == null || last.plus(DELETE_AFTER).minus(WARN_BEFORE).isAfter(now)) {
                    continue;
                }
                if (s.getInactivityWarnedAt() == null) {
                    s.setInactivityWarnedAt(now);
                    serverRepository.save(s);
                    warn.add(new Object[]{s.getOwner().getId(), "Ton serveur " + name(s) + " n'a pas été démarré depuis "
                            + "presque 6 mois : il sera supprimé dans 14 jours. Démarre-le une fois (/mcs start "
                            + name(s) + ") pour le garder."});
                } else if (s.getInactivityWarnedAt().plus(WARN_BEFORE).isBefore(now) && last.plus(DELETE_AFTER).isBefore(now)) {
                    delete.add(s.getId());
                }
            }
        });
        for (Object[] w : warn) {
            notificationService.notify((Long) w[0], (String) w[1]);
        }
        for (Long id : delete) {
            deleteInactive(id);
        }
    }

    private void deleteInactive(long serverId) {
        Object[] info = tx().execute(x -> serverRepository.findById(serverId).map(s -> new Object[]{
                s.getOwner().getId(), s.getName(), s.getNode() == null ? null : s.getNode().getId(),
                ServerService.hasCopy(s)}).orElse(null));
        if (info == null) {
            return;
        }
        Long nodeId = (Long) info[2];
        boolean offlineCopy = (Boolean) info[3] && nodeId != null && !agentWebSocketHandler.isNodeOnline(nodeId);
        try {
            if (offlineCopy) {
                tx().executeWithoutResult(x -> {
                    if (!pendingDeletionRepository.existsByNodeIdAndServerId(nodeId, serverId)) {
                        pendingDeletionRepository.save(PendingDeletion.builder().nodeId(nodeId).serverId(serverId).build());
                    }
                });
            }
            serverService.deleteServer(serverId, true, offlineCopy);
            notificationService.notify((Long) info[0], "Ton serveur " + info[1] + " a été supprimé après 6 mois sans "
                    + "démarrage. Ses sauvegardes restent un moment : /mcs restore deleted");
            log.info("Serveur {} supprimé après 6 mois d'inactivité", info[1]);
        } catch (Exception e) {
            log.warn("Suppression du serveur inactif {} : {}", info[1], e.getMessage());
        }
    }
}
