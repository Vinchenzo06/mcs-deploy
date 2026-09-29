package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vinch.mcs.api.entities.*;
import vinch.mcs.api.repositories.BackupRepository;
import vinch.mcs.api.repositories.NodeRepository;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sauvegardes des serveurs joueurs.
 *
 * L'agent de la machine envoie les données du serveur avec restic vers le serveur
 * de sauvegarde (à la maison), joint par le VPS : rest:http://10.99.0.1:8100/node<id>/
 * (tunnel WireGuard jusqu'au VPS, puis tunnel SSH du VPS vers la maison). Chaque
 * machine a son dépôt, chiffré, en ajout seul : elle ne peut ni lire les autres
 * dépôts ni effacer ses anciennes sauvegardes. Le tri (rétention) est fait sur le
 * serveur de sauvegarde (mcs-backup-prune), avec la politique exportée par le VPS.
 *
 * Politique d'un serveur : réglage du serveur (admins, /mcs backup policy) >
 * méta LuckPerms du rôle de son propriétaire (backup-interval, backup-keep-last,
 * backup-keep-weekly) > défaut (mcs.backup.*). Intervalle 0 : pas de sauvegarde auto.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupService {

    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;
    private final BackupRepository backupRepository;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AccessService accessService;
    private final PlatformTransactionManager transactionManager;

    @Value("${mcs.backup.enabled:false}")
    private boolean enabled;

    @Value("${mcs.backup.repo-base:http://10.99.0.1:8100}")
    private String repoBase;

    @Value("${mcs.backup.default-interval-hours:24}")
    private int defaultIntervalHours;

    @Value("${mcs.backup.default-keep-last:3}")
    private int defaultKeepLast;

    @Value("${mcs.backup.default-keep-weekly:4}")
    private int defaultKeepWeekly;

    // Une sauvegarde à la fois par serveur et par machine
    private final Set<Long> runningServers = ConcurrentHashMap.newKeySet();
    private final Set<Long> runningNodes = ConcurrentHashMap.newKeySet();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(30);
    private static final long AGENT_TIMEOUT_SECONDS = 2 * 3600;

    public record Policy(int intervalHours, int keepLast, int keepWeekly, String source) {}

    private record Job(long serverId, long nodeId, String velocityName, boolean running) {}

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Policy policy(Server s) {
        Player owner = s.getOwner();
        String source = "défaut";
        Integer interval = s.getBackupIntervalHours();
        if (interval != null) {
            source = "serveur";
        } else if (owner.getBackupIntervalHours() != null) {
            interval = owner.getBackupIntervalHours();
            source = "rôle";
        }
        int keepLast = firstNonNull(s.getBackupKeepLast(), owner.getBackupKeepLast(), defaultKeepLast);
        int keepWeekly = firstNonNull(s.getBackupKeepWeekly(), owner.getBackupKeepWeekly(), defaultKeepWeekly);
        return new Policy(interval == null ? defaultIntervalHours : Math.max(0, interval),
                Math.max(1, keepLast), Math.max(0, keepWeekly), source);
    }

    private static int firstNonNull(Integer a, Integer b, int def) {
        return a != null ? a : b != null ? b : def;
    }

    private static String secret() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Identifiants de sauvegarde de chaque machine (exportés vers le serveur de sauvegarde) */
    private void ensureCredentials(Node node) {
        boolean changed = false;
        if (node.getBackupHttpPassword() == null) {
            node.setBackupHttpPassword(secret());
            changed = true;
        }
        if (node.getBackupRepoPassword() == null) {
            node.setBackupRepoPassword(secret());
            changed = true;
        }
        if (changed) {
            nodeRepository.save(node);
            log.info("Identifiants de sauvegarde créés pour la machine {}", node.getId());
        }
    }

    // ------------------------------------------------------------ automatique ----

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void scheduledBackups() {
        if (!enabled) {
            return;
        }
        List<Job> due;
        try {
            due = tx().execute(status -> planDue());
        } catch (Exception e) {
            log.warn("Planification des sauvegardes : {}", e.getMessage());
            return;
        }
        if (due == null) {
            return;
        }
        for (Job job : due) {
            if (runningNodes.contains(job.nodeId()) || !runningServers.add(job.serverId())) {
                continue;
            }
            runningNodes.add(job.nodeId());
            CompletableFuture.runAsync(() -> {
                try {
                    execute(job, BackupType.AUTO, null);
                } finally {
                    runningServers.remove(job.serverId());
                    runningNodes.remove(job.nodeId());
                }
            });
        }
    }

    /** Serveurs dont la sauvegarde est due (dans une transaction) */
    private List<Job> planDue() {
        LocalDateTime now = LocalDateTime.now();
        List<Job> due = new ArrayList<>();
        Set<Long> credentialsDone = new HashSet<>();
        for (Server s : serverRepository.findAll()) {
            Node node = s.getNode();
            if (node == null || Boolean.TRUE.equals(node.getIsRevoked())) {
                continue;
            }
            if (credentialsDone.add(node.getId())) {
                ensureCredentials(node);
            }
            if (!agentWebSocketHandler.isNodeOnline(node.getId()) || runningServers.contains(s.getId())) {
                continue;
            }
            boolean running = s.getStatus() == ServerStatus.RUNNING;
            if (!running && s.getStatus() != ServerStatus.STOPPED) {
                continue;
            }
            Policy policy = policy(s);
            if (policy.intervalHours() <= 0) {
                continue;
            }
            Optional<Backup> last = backupRepository.findFirstByServerIdOrderByCreatedAtDesc(s.getId());
            if (last.isPresent() && "FAILED".equals(last.get().getStatus())
                    && last.get().getCreatedAt().isAfter(now.minus(FAILURE_BACKOFF))) {
                continue; // échec récent : on réessaie plus tard
            }
            if (last.isPresent() && "RUNNING".equals(last.get().getStatus())
                    && last.get().getCreatedAt().isAfter(now.minusHours(3))) {
                continue;
            }
            Optional<Backup> lastOk = backupRepository.findFirstByServerIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            boolean isDue;
            if (running) {
                LocalDateTime base = lastOk.map(Backup::getCreatedAt).orElse(s.getCreatedAt());
                isDue = !base.plusHours(policy.intervalHours()).isAfter(now);
            } else {
                // Arrêté : une sauvegarde après l'arrêt, puis plus rien tant qu'il ne tourne pas
                isDue = s.getLastStoppedAt() != null
                        && lastOk.map(b -> b.getCreatedAt().isBefore(s.getLastStoppedAt())).orElse(true);
            }
            if (isDue) {
                due.add(new Job(s.getId(), node.getId(), s.getVelocityName(), running));
            }
        }
        return due;
    }

    // ------------------------------------------------------------ exécution ----

    /** Lance la sauvegarde sur la machine et l'enregistre ; renvoie la ligne "backups" */
    private Backup execute(Job job, BackupType type, String requestedBy) {
        Map<String, Object> data = new HashMap<>();
        Backup record = tx().execute(status -> {
            Server s = serverRepository.findById(job.serverId()).orElseThrow();
            Node node = s.getNode();
            ensureCredentials(node);
            data.put("server_id", s.getId());
            data.put("repository", "rest:" + repoBase + "/node" + node.getId() + "/");
            data.put("http_user", "node" + node.getId());
            data.put("http_password", node.getBackupHttpPassword());
            data.put("repo_password", node.getBackupRepoPassword());
            data.put("tag", "server-" + s.getId());
            data.put("host", "node" + node.getId());
            return backupRepository.save(Backup.builder()
                    .server(s)
                    .backupType(type)
                    .storagePath("node" + node.getId())
                    .nodeId(node.getId())
                    .requestedBy(requestedBy)
                    .status("RUNNING")
                    .build());
        });
        long backupId = record.getId();
        log.info("Sauvegarde {} de {} ({}) sur la machine {}", type, job.velocityName(), backupId, job.nodeId());

        String status;
        String message = null;
        String snapshot = null;
        Integer addedMb = null;
        Integer totalMb = null;
        try {
            JsonNode result = agentWebSocketHandler.sendCommand(job.nodeId(), "backup_server", data, AGENT_TIMEOUT_SECONDS)
                    .get(AGENT_TIMEOUT_SECONDS + 60, TimeUnit.SECONDS);
            if (result == null || (result.has("success") && !result.get("success").asBoolean())) {
                status = "FAILED";
                message = result == null ? "pas de réponse de la machine"
                        : result.path("message").asText(result.path("error").asText("erreur inconnue"));
            } else {
                JsonNode r = result.path("result");
                status = "SUCCESS";
                snapshot = r.path("snapshot_id").asText(null);
                addedMb = (int) (r.path("data_added").asLong(0) / (1024 * 1024));
                totalMb = (int) (r.path("total_bytes").asLong(0) / (1024 * 1024));
            }
        } catch (Exception e) {
            status = "FAILED";
            message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        }
        if (message != null && message.length() > 480) {
            message = message.substring(0, 480);
        }

        String fStatus = status;
        String fMessage = message;
        String fSnapshot = snapshot;
        Integer fAdded = addedMb;
        Integer fTotal = totalMb;
        Backup done = tx().execute(st -> {
            Backup b = backupRepository.findById(backupId).orElseThrow();
            b.setStatus(fStatus);
            b.setMessage(fMessage);
            b.setSnapshotId(fSnapshot);
            b.setSizeMb(fAdded == null ? 0 : fAdded);
            b.setTotalMb(fTotal);
            b.setIsComplete("SUCCESS".equals(fStatus));
            b.setCompletedAt(LocalDateTime.now());
            return backupRepository.save(b);
        });
        if ("SUCCESS".equals(status)) {
            log.info("Sauvegarde {} de {} réussie : {} Mo nouveaux / {} Mo", backupId, job.velocityName(), addedMb, totalMb);
        } else {
            log.warn("Sauvegarde {} de {} échouée : {}", backupId, job.velocityName(), message);
        }
        return done;
    }

    // ------------------------------------------------------------ à la demande ----

    /** Sauvegarde immédiate demandée par un joueur (droit POWER) ; attend jusqu'à 20 min */
    public Map<String, Object> backupNow(Long serverId, Long playerId) throws Exception {
        if (!enabled) {
            throw new RuntimeException("Les sauvegardes ne sont pas encore configurées sur ce réseau.");
        }
        Job job = tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            accessService.require(p, s, AccessService.Right.POWER);
            if (s.getNode() == null || !agentWebSocketHandler.isNodeOnline(s.getNode().getId())) {
                throw new RuntimeException("La machine qui héberge ce serveur est hors ligne");
            }
            return new Job(s.getId(), s.getNode().getId(), s.getVelocityName(), s.getStatus() == ServerStatus.RUNNING);
        });
        String requestedBy = tx().execute(status -> accessService.player(playerId).getMinecraftUsername());
        if (!runningServers.add(job.serverId())) {
            throw new RuntimeException("Une sauvegarde de ce serveur est déjà en cours.");
        }
        runningNodes.add(job.nodeId());
        CompletableFuture<Backup> future = CompletableFuture.supplyAsync(() -> {
            try {
                return execute(job, BackupType.MANUAL, requestedBy);
            } finally {
                runningServers.remove(job.serverId());
                runningNodes.remove(job.nodeId());
            }
        });
        try {
            return describe(future.get(20, TimeUnit.MINUTES));
        } catch (TimeoutException e) {
            return Map.of("status", "RUNNING", "message", "La sauvegarde continue en arrière-plan.");
        }
    }

    private static Map<String, Object> describe(Backup b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("type", b.getBackupType().name());
        m.put("status", b.getStatus());
        m.put("createdAt", b.getCreatedAt());
        m.put("completedAt", b.getCompletedAt());
        m.put("addedMb", b.getSizeMb());
        m.put("totalMb", b.getTotalMb());
        m.put("snapshot", b.getSnapshotId() == null ? null : b.getSnapshotId().substring(0, Math.min(8, b.getSnapshotId().length())));
        m.put("message", b.getMessage());
        m.put("requestedBy", b.getRequestedBy());
        return m;
    }

    /** Dernières sauvegardes et politique d'un serveur (droit POWER) */
    public Map<String, Object> list(Long serverId, Long playerId) {
        return tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            accessService.require(p, s, AccessService.Right.POWER);
            Policy policy = policy(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", enabled);
            out.put("intervalHours", policy.intervalHours());
            out.put("keepLast", policy.keepLast());
            out.put("keepWeekly", policy.keepWeekly());
            out.put("policySource", policy.source());
            out.put("running", runningServers.contains(s.getId()));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Backup b : backupRepository.findTop15ByServerIdOrderByCreatedAtDesc(s.getId())) {
                items.add(describe(b));
            }
            out.put("backups", items);
            return out;
        });
    }

    /** Dernière sauvegarde réussie (pour /mcs info), ou null */
    public LocalDateTime lastSuccess(Long serverId) {
        return backupRepository.findFirstByServerIdAndStatusOrderByCreatedAtDesc(serverId, "SUCCESS")
                .map(Backup::getCreatedAt).orElse(null);
    }

    /**
     * Politique propre à un serveur (admins). Valeurs null : inchangées ;
     * reset : revient à la politique du rôle du propriétaire.
     */
    public Map<String, Object> setPolicy(Long serverId, Long playerId, Integer intervalHours, Integer keepLast,
                                         Integer keepWeekly, boolean reset) {
        return tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            if (!AccessService.isAdmin(p)) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            if (reset) {
                s.setBackupIntervalHours(null);
                s.setBackupKeepLast(null);
                s.setBackupKeepWeekly(null);
            } else {
                if (intervalHours != null) {
                    if (intervalHours < 0 || intervalHours > 24 * 30) {
                        throw new RuntimeException("Fréquence : de 0 (jamais) à 720 heures.");
                    }
                    s.setBackupIntervalHours(intervalHours);
                }
                if (keepLast != null) {
                    if (keepLast < 1 || keepLast > 50) {
                        throw new RuntimeException("Dernières sauvegardes gardées : de 1 à 50.");
                    }
                    s.setBackupKeepLast(keepLast);
                }
                if (keepWeekly != null) {
                    if (keepWeekly < 0 || keepWeekly > 52) {
                        throw new RuntimeException("Semaines gardées : de 0 à 52.");
                    }
                    s.setBackupKeepWeekly(keepWeekly);
                }
            }
            serverRepository.save(s);
            Policy policy = policy(s);
            log.info("{} : politique de sauvegarde de {} = {}", p.getMinecraftUsername(), s.getVelocityName(), policy);
            return Map.of("intervalHours", policy.intervalHours(), "keepLast", policy.keepLast(),
                    "keepWeekly", policy.keepWeekly(), "policySource", policy.source());
        });
    }
}
