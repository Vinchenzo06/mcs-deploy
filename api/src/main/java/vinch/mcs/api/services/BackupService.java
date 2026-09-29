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
import vinch.mcs.api.repositories.BackupPolicyRepository;
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
 * Transport : l'agent de la machine envoie les données avec restic vers le serveur
 * de sauvegarde (à la maison), joint par le VPS : rest:http://10.99.0.1:8100/node<id>/.
 * Chaque machine a son dépôt, chiffré, en ajout seul.
 *
 * Types (BackupKind) : quotidienne, hebdomadaire, mensuelle (automatiques),
 * manuelle, permanente. Chacun a un nombre max (au-delà, la plus ancienne n'est
 * plus retenue) et une durée de vie. Une sauvegarde peut cumuler plusieurs types ;
 * elle expire quand plus aucun ne la retient (la plus récente d'un serveur n'expire
 * jamais). C'est l'API qui décide : le serveur de sauvegarde supprime les instantanés
 * expirés que lui transmet le VPS (mcs-backup-export), puis confirme.
 *
 * Réglages (backup_policies) : "network" (défauts), "rank:<groupe>" (limites que le
 * propriétaire ne peut pas dépasser), "server:<id>". Les admins règlent tout.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupService {

    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;
    private final BackupRepository backupRepository;
    private final BackupPolicyRepository policyRepository;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AccessService accessService;
    private final PlatformTransactionManager transactionManager;

    @Value("${mcs.backup.enabled:false}")
    private boolean enabled;

    @Value("${mcs.backup.repo-base:http://10.99.0.1:8100}")
    private String repoBase;

    // Une sauvegarde à la fois par serveur et par machine
    private final Set<Long> runningServers = ConcurrentHashMap.newKeySet();
    private final Set<Long> runningNodes = ConcurrentHashMap.newKeySet();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(30);
    private static final long AGENT_TIMEOUT_SECONDS = 2 * 3600;
    // Tolérance : une sauvegarde "quotidienne" peut partir un peu avant 24 h pile
    private static final Duration SLACK = Duration.ofMinutes(20);

    static final int MAX_COUNT = 50;
    static final int MAX_DAYS = 3650;
    static final int MAX_HOURS = 24 * 365;

    /** Réglage effectif d'un type : nombre max et durée (jours, heures pour PERMANENT) */
    public record Rule(int max, int duration) {}

    private record Job(long serverId, long nodeId, String velocityName, Set<BackupKind> kinds) {}

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------ réglages ----

    private BackupPolicy network() {
        return policyRepository.findByScope("network").orElseGet(() -> BackupPolicy.builder()
                .scope("network")
                .dailyMax(3).dailyDays(3).weeklyMax(2).weeklyDays(14).monthlyMax(0).monthlyDays(30)
                .manualMax(2).manualDays(3).permanentMax(1).permanentHours(24)
                .build());
    }

    private static String rankScope(Player owner) {
        String r = owner.getNetworkRank() == null ? "default" : owner.getNetworkRank();
        return "rank:" + r.toLowerCase(Locale.ROOT);
    }

    /** Réglages effectifs d'un serveur : les siens, sinon ceux du réseau */
    public Map<BackupKind, Rule> effective(Server s) {
        BackupPolicy net = network();
        Optional<BackupPolicy> own = policyRepository.findByScope("server:" + s.getId());
        Map<BackupKind, Rule> out = new EnumMap<>(BackupKind.class);
        for (BackupKind k : BackupKind.values()) {
            Integer max = own.map(p -> p.max(k)).orElse(null);
            Integer dur = own.map(p -> p.duration(k)).orElse(null);
            out.put(k, new Rule(max != null ? max : nz(net.max(k)), dur != null ? dur : nz(net.duration(k))));
        }
        return out;
    }

    /** Limites du rôle du propriétaire (ce qu'il peut régler au plus) */
    public Map<BackupKind, Rule> limits(Player owner) {
        BackupPolicy net = network();
        Optional<BackupPolicy> rank = policyRepository.findByScope(rankScope(owner));
        Map<BackupKind, Rule> out = new EnumMap<>(BackupKind.class);
        for (BackupKind k : BackupKind.values()) {
            Integer max = rank.map(p -> p.max(k)).orElse(null);
            Integer dur = rank.map(p -> p.duration(k)).orElse(null);
            out.put(k, new Rule(max != null ? max : nz(net.max(k)), dur != null ? dur : nz(net.duration(k))));
        }
        return out;
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static Map<String, Object> rulesToMap(Map<BackupKind, Rule> rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<BackupKind, Rule> e : rules.entrySet()) {
            m.put(e.getKey().name(), Map.of("max", e.getValue().max(), "duration", e.getValue().duration(),
                    "label", e.getKey().label(), "unit", e.getKey().unit()));
        }
        return m;
    }

    private static void checkBounds(BackupKind k, Integer max, Integer duration) {
        if (max != null && (max < 0 || max > MAX_COUNT)) {
            throw new RuntimeException("Nombre max : de 0 à " + MAX_COUNT + ".");
        }
        int limit = k == BackupKind.PERMANENT ? MAX_HOURS : MAX_DAYS;
        if (duration != null && (duration < 0 || duration > limit)) {
            throw new RuntimeException("Durée : de 0 à " + limit + " " + k.unit() + ".");
        }
    }

    private BackupPolicy policyFor(String scope) {
        return policyRepository.findByScope(scope).orElseGet(() -> BackupPolicy.builder().scope(scope).build());
    }

    /**
     * Réglage d'un type pour un serveur. Propriétaire : dans les limites de son rôle ;
     * admins : sans limite. reset : revient aux réglages du réseau.
     */
    public Map<String, Object> setServerRule(Long serverId, Long playerId, BackupKind kind, Integer max,
                                             Integer duration, boolean reset) {
        return tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            boolean admin = AccessService.isAdmin(p);
            if (!admin && !AccessService.isOwner(p, s)) {
                throw new RuntimeException("Seuls le propriétaire du serveur et les admins règlent ses sauvegardes.");
            }
            String scope = "server:" + s.getId();
            if (reset) {
                policyRepository.findByScope(scope).ifPresent(policyRepository::delete);
            } else {
                if (kind == null) {
                    throw new RuntimeException("Type inconnu : quotidienne, hebdomadaire, mensuelle, manuelle ou permanente.");
                }
                checkBounds(kind, max, duration);
                if (!admin) {
                    Rule cap = limits(s.getOwner()).get(kind);
                    if (max != null && max > cap.max()) {
                        throw new RuntimeException("Ton rôle permet au plus " + cap.max() + " sauvegarde(s) " + kind.label() + ".");
                    }
                    if (duration != null && duration > cap.duration()) {
                        throw new RuntimeException("Ton rôle permet de garder une sauvegarde " + kind.label()
                                + " au plus " + cap.duration() + " " + kind.unit() + ".");
                    }
                }
                BackupPolicy policy = policyFor(scope);
                policy.set(kind, max != null ? max : policy.max(kind), duration != null ? duration : policy.duration(kind));
                policy.setUpdatedBy(p.getMinecraftUsername());
                policyRepository.save(policy);
            }
            log.info("{} : sauvegardes de {} {}", p.getMinecraftUsername(), s.getVelocityName(),
                    reset ? "remises par défaut" : kind + " = " + max + " / " + duration);
            return Map.of("rules", rulesToMap(effective(s)));
        });
    }

    /** Réglage réseau ("network") ou limites d'un rôle ("rank:<groupe>") ; admins */
    public Map<String, Object> setScopeRule(Long playerId, String scope, BackupKind kind, Integer max,
                                            Integer duration, boolean reset) {
        return tx().execute(status -> {
            Player p = accessService.player(playerId);
            if (!AccessService.isAdmin(p)) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            String sc = normalizeScope(scope);
            if (reset) {
                if (sc.equals("network")) {
                    throw new RuntimeException("Les réglages du réseau ne se suppriment pas, change-les type par type.");
                }
                policyRepository.findByScope(sc).ifPresent(policyRepository::delete);
            } else if (kind != null) {
                checkBounds(kind, max, duration);
                BackupPolicy policy = sc.equals("network") ? network() : policyFor(sc);
                policy.set(kind, max != null ? max : policy.max(kind), duration != null ? duration : policy.duration(kind));
                policy.setUpdatedBy(p.getMinecraftUsername());
                policyRepository.save(policy);
                log.info("{} : sauvegardes {} {} = {} / {}", p.getMinecraftUsername(), sc, kind, max, duration);
            }
            return describeScope(sc);
        });
    }

    private static String normalizeScope(String scope) {
        String s = scope == null ? "network" : scope.trim().toLowerCase(Locale.ROOT);
        if (s.equals("network") || s.equals("reseau") || s.equals("réseau")) {
            return "network";
        }
        String rank = s.startsWith("rank:") ? s.substring(5) : s;
        if (!rank.matches("[a-z0-9_-]{1,32}")) {
            throw new RuntimeException("Rôle invalide : " + scope);
        }
        return "rank:" + rank;
    }

    /** Réglages d'un scope (valeurs du réseau quand le rôle n'a rien de propre) */
    public Map<String, Object> describeScope(String scope) {
        BackupPolicy net = network();
        Optional<BackupPolicy> own = scope.equals("network") ? Optional.of(net) : policyRepository.findByScope(scope);
        Map<BackupKind, Rule> rules = new EnumMap<>(BackupKind.class);
        for (BackupKind k : BackupKind.values()) {
            Integer max = own.map(p -> p.max(k)).orElse(null);
            Integer dur = own.map(p -> p.duration(k)).orElse(null);
            rules.put(k, new Rule(max != null ? max : nz(net.max(k)), dur != null ? dur : nz(net.duration(k))));
        }
        return Map.of("scope", scope, "rules", rulesToMap(rules));
    }

    public Map<String, Object> getScope(Long playerId, String scope) {
        return tx().execute(status -> {
            if (!AccessService.isAdmin(accessService.player(playerId))) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            return describeScope(normalizeScope(scope));
        });
    }

    // ------------------------------------------------------------ identifiants ----

    private static String secret() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

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
            due = tx().execute(status -> {
                expire();
                return planDue();
            });
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

    private static boolean olderThan(LocalDateTime t, Duration d, LocalDateTime now) {
        return t == null || !t.plus(d).minus(SLACK).isAfter(now);
    }

    /** Serveurs dont une sauvegarde automatique est due, avec ses types */
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
            Optional<Backup> last = backupRepository.findFirstByServerIdOrderByCreatedAtDesc(s.getId());
            if (last.isPresent() && "FAILED".equals(last.get().getStatus())
                    && last.get().getCreatedAt().isAfter(now.minus(FAILURE_BACKOFF))) {
                continue;
            }
            if (last.isPresent() && "RUNNING".equals(last.get().getStatus())
                    && last.get().getCreatedAt().isAfter(now.minusHours(3))) {
                continue;
            }
            Map<BackupKind, Rule> rules = effective(s);
            List<Backup> ok = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            LocalDateTime lastAuto = null;
            LocalDateTime lastWeekly = null;
            LocalDateTime lastMonthly = null;
            LocalDateTime lastAny = ok.isEmpty() ? null : ok.get(0).getCreatedAt();
            for (Backup b : ok) {
                boolean auto = b.is(BackupKind.DAILY) || b.is(BackupKind.WEEKLY) || b.is(BackupKind.MONTHLY);
                if (auto && lastAuto == null) {
                    lastAuto = b.getCreatedAt();
                }
                if (b.is(BackupKind.WEEKLY) && lastWeekly == null) {
                    lastWeekly = b.getCreatedAt();
                }
                if (b.is(BackupKind.MONTHLY) && lastMonthly == null) {
                    lastMonthly = b.getCreatedAt();
                }
            }
            if (lastAuto == null) {
                // Pas encore de sauvegarde automatique : on compte depuis la création
                lastAuto = s.getCreatedAt();
            }
            boolean dailyOn = rules.get(BackupKind.DAILY).max() > 0;
            boolean weeklyDue = rules.get(BackupKind.WEEKLY).max() > 0 && olderThan(lastWeekly, Duration.ofDays(7), now);
            boolean monthlyDue = rules.get(BackupKind.MONTHLY).max() > 0 && olderThan(lastMonthly, Duration.ofDays(30), now);
            boolean dailyDue = dailyOn && olderThan(lastAuto, Duration.ofDays(1), now);

            boolean isDue;
            if (running) {
                // Hebdo/mensuelle profitent de la quotidienne (pas une 2e sauvegarde le même jour)
                isDue = dailyOn ? dailyDue : (weeklyDue || monthlyDue);
            } else {
                // Arrêté : une sauvegarde après l'arrêt (si rien depuis), puis plus rien
                isDue = (dailyOn || weeklyDue || monthlyDue) && s.getLastStoppedAt() != null
                        && (lastAny == null || lastAny.isBefore(s.getLastStoppedAt()));
            }
            if (!isDue) {
                continue;
            }
            Set<BackupKind> kinds = EnumSet.noneOf(BackupKind.class);
            if (dailyOn) {
                kinds.add(BackupKind.DAILY);
            }
            if (weeklyDue) {
                kinds.add(BackupKind.WEEKLY);
            }
            if (monthlyDue) {
                kinds.add(BackupKind.MONTHLY);
            }
            due.add(new Job(s.getId(), node.getId(), s.getVelocityName(), kinds));
        }
        return due;
    }

    // ------------------------------------------------------------ expiration ----

    /**
     * Marque EXPIRED les sauvegardes qu'aucun type ne retient plus. Serveur existant :
     * pour chaque type, les "max" plus récentes de ce type, dans leur durée de vie
     * (permanente : sans durée). La plus récente d'un serveur n'expire jamais.
     * Serveur supprimé : expires_at, fixée à la suppression.
     */
    private void expire() {
        LocalDateTime now = LocalDateTime.now();
        for (Server s : serverRepository.findAll()) {
            List<Backup> ok = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            if (ok.size() <= 1) {
                continue;
            }
            Set<Long> kept = retained(ok, effective(s), now, false);
            kept.add(ok.get(0).getId());
            for (Backup b : ok) {
                if (!kept.contains(b.getId())) {
                    b.setStatus("EXPIRED");
                    b.setExpiresAt(now);
                    backupRepository.save(b);
                    log.info("Sauvegarde {} de {} expirée", b.getId(), s.getVelocityName());
                }
            }
        }
        for (Backup b : backupRepository.findByServerIsNullAndStatus("SUCCESS")) {
            if (b.getExpiresAt() == null || !b.getExpiresAt().isAfter(now)) {
                b.setStatus("EXPIRED");
                backupRepository.save(b);
                log.info("Sauvegarde {} de {} (serveur supprimé) expirée", b.getId(), b.getServerRef());
            }
        }
    }

    /** Ids retenus par au moins un type (ok : du plus récent au plus ancien) */
    private static Set<Long> retained(List<Backup> ok, Map<BackupKind, Rule> rules, LocalDateTime now,
                                      boolean serverDeleted) {
        Set<Long> kept = new HashSet<>();
        for (BackupKind k : BackupKind.values()) {
            Rule r = rules.get(k);
            int rank = 0;
            for (Backup b : ok) {
                if (!b.is(k)) {
                    continue;
                }
                boolean inCount = rank < r.max();
                rank++;
                boolean inTime = k == BackupKind.PERMANENT
                        ? !serverDeleted
                        : b.getCreatedAt().plusDays(r.duration()).isAfter(now);
                if (inCount && inTime) {
                    kept.add(b.getId());
                }
            }
        }
        return kept;
    }

    /**
     * Le serveur va être supprimé : ses sauvegardes restent le temps de leur durée
     * de vie (permanente : "durée" heures après la suppression, 0 = tout de suite).
     */
    public void onServerDeleting(Long serverId) {
        tx().executeWithoutResult(status -> {
            Server s = serverRepository.findById(serverId).orElse(null);
            if (s == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            Map<BackupKind, Rule> rules = effective(s);
            List<Backup> ok = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            Set<Long> kept = retained(ok, rules, now, false);
            for (Backup b : ok) {
                LocalDateTime until = now;
                if (kept.contains(b.getId())) {
                    for (BackupKind k : BackupKind.values()) {
                        if (!b.is(k)) {
                            continue;
                        }
                        LocalDateTime t = k == BackupKind.PERMANENT
                                ? now.plusHours(rules.get(k).duration())
                                : b.getCreatedAt().plusDays(rules.get(k).duration());
                        if (t.isAfter(until)) {
                            until = t;
                        }
                    }
                }
                b.setExpiresAt(until);
                b.setServerRef(s.getVelocityName());
                backupRepository.save(b);
            }
            policyRepository.findByScope("server:" + s.getId()).ifPresent(policyRepository::delete);
            log.info("Serveur {} supprimé : {} sauvegarde(s) gardée(s) jusqu'à leur expiration", s.getVelocityName(), ok.size());
        });
    }

    // ------------------------------------------------------------ exécution ----

    /** Lance la sauvegarde sur la machine et l'enregistre */
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
            Backup b = Backup.builder()
                    .server(s)
                    .serverTagId(s.getId())
                    .serverRef(s.getVelocityName())
                    .backupType(type)
                    .storagePath("node" + node.getId())
                    .nodeId(node.getId())
                    .requestedBy(requestedBy)
                    .status("RUNNING")
                    .build();
            for (BackupKind k : job.kinds()) {
                b.mark(k, true);
            }
            return backupRepository.save(b);
        });
        long backupId = record.getId();
        log.info("Sauvegarde {} {} de {} sur la machine {}", backupId, job.kinds(), job.velocityName(), job.nodeId());

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
            Backup saved = backupRepository.save(b);
            // Rotation tout de suite (ex. 3e manuelle : la plus ancienne n'est plus retenue)
            expire();
            return saved;
        });
        if ("SUCCESS".equals(status)) {
            log.info("Sauvegarde {} de {} réussie : {} Mo nouveaux / {} Mo", backupId, job.velocityName(), addedMb, totalMb);
        } else {
            log.warn("Sauvegarde {} de {} échouée : {}", backupId, job.velocityName(), message);
        }
        return done;
    }

    // ------------------------------------------------------------ à la demande ----

    /**
     * Sauvegarde demandée par un joueur : manuelle (droit POWER) ou permanente
     * (propriétaire et admins). Attend jusqu'à 20 min.
     */
    public Map<String, Object> backupNow(Long serverId, Long playerId, boolean permanent) throws Exception {
        if (!enabled) {
            throw new RuntimeException("Les sauvegardes ne sont pas encore configurées sur ce réseau.");
        }
        Job job = tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            if (permanent) {
                if (!AccessService.isAdmin(p) && !AccessService.isOwner(p, s)) {
                    throw new RuntimeException("Seuls le propriétaire du serveur et les admins font des sauvegardes permanentes.");
                }
                if (effective(s).get(BackupKind.PERMANENT).max() <= 0) {
                    throw new RuntimeException("Les sauvegardes permanentes sont désactivées pour ce serveur.");
                }
            } else {
                accessService.require(p, s, AccessService.Right.POWER);
                if (effective(s).get(BackupKind.MANUAL).max() <= 0) {
                    throw new RuntimeException("Les sauvegardes manuelles sont désactivées pour ce serveur.");
                }
            }
            if (s.getNode() == null || !agentWebSocketHandler.isNodeOnline(s.getNode().getId())) {
                throw new RuntimeException("La machine qui héberge ce serveur est hors ligne");
            }
            return new Job(s.getId(), s.getNode().getId(), s.getVelocityName(),
                    EnumSet.of(permanent ? BackupKind.PERMANENT : BackupKind.MANUAL));
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
            Backup b = future.get(20, TimeUnit.MINUTES);
            return tx().execute(status -> describe(backupRepository.findById(b.getId()).orElse(b)));
        } catch (TimeoutException e) {
            return Map.of("status", "RUNNING", "message", "La sauvegarde continue en arrière-plan.");
        }
    }

    /** Rendre permanente (ou non) une sauvegarde existante ; propriétaire et admins */
    public Map<String, Object> setPermanent(Long serverId, Long backupId, Long playerId, boolean permanent) {
        return tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            if (!AccessService.isAdmin(p) && !AccessService.isOwner(p, s)) {
                throw new RuntimeException("Seuls le propriétaire du serveur et les admins gèrent les sauvegardes permanentes.");
            }
            Backup b = backupRepository.findById(backupId)
                    .filter(x -> Objects.equals(x.getServerTagId(), s.getId()) && "SUCCESS".equals(x.getStatus()))
                    .orElseThrow(() -> new RuntimeException("Sauvegarde n°" + backupId + " introuvable pour ce serveur."));
            if (permanent && effective(s).get(BackupKind.PERMANENT).max() <= 0) {
                throw new RuntimeException("Les sauvegardes permanentes sont désactivées pour ce serveur.");
            }
            b.mark(BackupKind.PERMANENT, permanent);
            backupRepository.save(b);
            // Au-delà du max, la plus ancienne permanente n'est plus retenue
            expire();
            return describe(backupRepository.findById(backupId).orElse(b));
        });
    }

    /** Date d'expiration prévue d'une sauvegarde (null : permanente ou la plus récente) */
    private static LocalDateTime expiry(Backup b, Map<BackupKind, Rule> rules, List<Backup> ok) {
        if (b.getExpiresAt() != null) {
            return b.getExpiresAt();
        }
        if (b.is(BackupKind.PERMANENT) || (!ok.isEmpty() && ok.get(0).getId().equals(b.getId()))) {
            return null;
        }
        LocalDateTime until = null;
        for (BackupKind k : BackupKind.values()) {
            if (k != BackupKind.PERMANENT && b.is(k)) {
                LocalDateTime t = b.getCreatedAt().plusDays(rules.get(k).duration());
                if (until == null || t.isAfter(until)) {
                    until = t;
                }
            }
        }
        return until;
    }

    private static Map<String, Object> describe(Backup b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("type", b.getBackupType().name());
        m.put("status", b.getStatus());
        m.put("createdAt", b.getCreatedAt() == null ? null : b.getCreatedAt().toString());
        m.put("addedMb", b.getSizeMb());
        m.put("totalMb", b.getTotalMb());
        m.put("message", b.getMessage());
        m.put("requestedBy", b.getRequestedBy());
        List<String> kinds = new ArrayList<>();
        for (BackupKind k : BackupKind.values()) {
            if (b.is(k)) {
                kinds.add(k.name());
            }
        }
        m.put("kinds", kinds);
        return m;
    }

    /** Sauvegardes (hors supprimées), réglages et limites d'un serveur ; droit POWER */
    public Map<String, Object> list(Long serverId, Long playerId) {
        return tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            accessService.require(p, s, AccessService.Right.POWER);
            Map<BackupKind, Rule> rules = effective(s);
            List<Backup> ok = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", enabled);
            out.put("rules", rulesToMap(rules));
            out.put("limits", rulesToMap(limits(s.getOwner())));
            out.put("customized", policyRepository.findByScope("server:" + s.getId()).isPresent());
            out.put("canConfigure", AccessService.isAdmin(p) || AccessService.isOwner(p, s));
            out.put("running", runningServers.contains(s.getId()));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Backup b : backupRepository.findTop30ByServerTagIdOrderByCreatedAtDesc(s.getId())) {
                if ("DELETED".equals(b.getStatus())) {
                    continue;
                }
                Map<String, Object> m = describe(b);
                if ("SUCCESS".equals(b.getStatus())) {
                    LocalDateTime until = expiry(b, rules, ok);
                    m.put("expiresAt", until == null ? null : until.toString());
                }
                items.add(m);
            }
            out.put("backups", items);
            return out;
        });
    }
}
