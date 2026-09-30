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
 * Deux emplacements :
 *  - CENTRAL : le serveur de sauvegarde (à la maison), joint par le VPS
 *    (rest:http://10.99.0.1:8100/node<id>/, un dépôt par machine, chiffré, en ajout seul) ;
 *  - LOCAL : la machine qui héberge le serveur, si son volontaire l'accepte
 *    (dépôt restic par serveur, <data>/.backups/<id>). Ces sauvegardes comptent dans le
 *    quota disque du serveur : l'agent supprime les plus vieilles pour faire de la place,
 *    sinon la sauvegarde part au central (manuelle) ou est sautée (la quotidienne existe
 *    aussi au central).
 *
 * Répartition quand la machine accepte : quotidiennes en double (machine + central),
 * manuelles sur la machine ; hebdomadaires, mensuelles et permanentes au central.
 *
 * Types (BackupKind) : chacun a un nombre max (au-delà, la plus ancienne n'est plus
 * retenue) et une durée de vie. Une sauvegarde expire quand plus aucun type ne la
 * retient (la plus récente de chaque emplacement n'expire jamais). C'est l'API qui
 * décide ; le serveur de sauvegarde et l'agent suppriment ce qu'elle leur indique.
 *
 * Réglages (backup_policies) :
 *  - "network" : défauts du réseau (central) ; "rank:<groupe>" : ce que le propriétaire
 *    peut régler au plus ; "min" : minimum toujours gardé au central ; "server:<id>" ;
 *  - "local:network" : minimum que l'hôte doit offrir (et défaut) ; "local:node:<id>" :
 *    plafond fixé par l'hôte pour sa machine ; "local:server:<id>" : choix du propriétaire.
 * Les admins règlent tout.
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

    public static final String CENTRAL = "CENTRAL";
    public static final String LOCAL = "LOCAL";
    /** Types qui peuvent être gardés sur la machine */
    public static final Set<BackupKind> LOCAL_KINDS = EnumSet.of(BackupKind.DAILY, BackupKind.MANUAL);

    // Une sauvegarde à la fois par serveur et par machine
    private final Set<Long> runningServers = ConcurrentHashMap.newKeySet();
    private final Set<Long> runningNodes = ConcurrentHashMap.newKeySet();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(30);
    private static final Duration NO_SPACE_BACKOFF = Duration.ofHours(6);
    private static final long AGENT_TIMEOUT_SECONDS = 2 * 3600;
    // Tolérance : une sauvegarde "quotidienne" peut partir un peu avant 24 h pile
    private static final Duration SLACK = Duration.ofMinutes(20);

    static final int MAX_COUNT = 50;
    static final int MAX_DAYS = 3650;
    static final int MAX_HOURS = 24 * 365;

    /** Réglage effectif d'un type : nombre max et durée (jours, heures pour PERMANENT) */
    public record Rule(int max, int duration) {}

    private record Job(long serverId, long nodeId, String velocityName, String location, Set<BackupKind> kinds) {}

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------ réglages ----

    /** Pour chaque type : première valeur non nulle parmi les scopes (dans l'ordre), sinon 0 */
    private Map<BackupKind, Rule> resolve(String... scopes) {
        List<BackupPolicy> layers = new ArrayList<>();
        for (String sc : scopes) {
            policyRepository.findByScope(sc).ifPresent(layers::add);
        }
        Map<BackupKind, Rule> out = new EnumMap<>(BackupKind.class);
        for (BackupKind k : BackupKind.values()) {
            Integer max = null;
            Integer dur = null;
            for (BackupPolicy p : layers) {
                if (max == null) {
                    max = p.max(k);
                }
                if (dur == null) {
                    dur = p.duration(k);
                }
            }
            out.put(k, new Rule(max == null ? 0 : max, dur == null ? 0 : dur));
        }
        return out;
    }

    private static Map<BackupKind, Rule> onlyLocal(Map<BackupKind, Rule> rules) {
        Map<BackupKind, Rule> out = new EnumMap<>(BackupKind.class);
        for (BackupKind k : BackupKind.values()) {
            out.put(k, LOCAL_KINDS.contains(k) ? rules.getOrDefault(k, new Rule(0, 0)) : new Rule(0, 0));
        }
        return out;
    }

    private static String rankScope(Player owner) {
        String r = owner.getNetworkRank() == null ? "default" : owner.getNetworkRank();
        return "rank:" + r.toLowerCase(Locale.ROOT);
    }

    /** Réglages effectifs d'un serveur au central : les siens, sinon ceux du réseau */
    public Map<BackupKind, Rule> effective(Server s) {
        return resolve("server:" + s.getId(), "network");
    }

    /** Limites du rôle du propriétaire (ce qu'il peut régler au plus, au central) */
    public Map<BackupKind, Rule> limits(Player owner) {
        return resolve(rankScope(owner), "network");
    }

    /** Minimum toujours gardé au central (le propriétaire ne descend pas en dessous) */
    public Map<BackupKind, Rule> minimum() {
        return resolve("min");
    }

    /** La machine du serveur garde-t-elle des sauvegardes ? */
    public static boolean localEnabled(Server s) {
        Node n = s.getNode();
        return n != null && Boolean.TRUE.equals(n.getAcceptsLocalBackups()) && !Boolean.TRUE.equals(n.getIsRevoked());
    }

    /** Plafond de la machine : réglage de l'hôte, sinon minimum fixé par l'admin */
    public Map<BackupKind, Rule> localCeiling(Node node) {
        return onlyLocal(resolve("local:node:" + node.getId(), "local:network"));
    }

    /** Minimum que l'hôte doit offrir (fixé par l'admin) */
    public Map<BackupKind, Rule> localMinimum() {
        return onlyLocal(resolve("local:network"));
    }

    /** Réglages effectifs sur la machine : choix du propriétaire, sinon plafond de la machine */
    public Map<BackupKind, Rule> localEffective(Server s) {
        if (!localEnabled(s)) {
            return onlyLocal(Map.of());
        }
        return onlyLocal(resolve("local:server:" + s.getId(), "local:node:" + s.getNode().getId(), "local:network"));
    }

    private static Map<String, Object> rulesToMap(Map<BackupKind, Rule> rules, Set<BackupKind> kinds) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (BackupKind k : BackupKind.values()) {
            if (!kinds.contains(k)) {
                continue;
            }
            Rule r = rules.getOrDefault(k, new Rule(0, 0));
            m.put(k.name(), Map.of("max", r.max(), "duration", r.duration(), "label", k.label(), "unit", k.unit()));
        }
        return m;
    }

    private static Map<String, Object> rulesToMap(Map<BackupKind, Rule> rules) {
        return rulesToMap(rules, EnumSet.allOf(BackupKind.class));
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

    private static void checkLocalKind(BackupKind k) {
        if (!LOCAL_KINDS.contains(k)) {
            throw new RuntimeException("Sur la machine : seulement les quotidiennes et les manuelles "
                    + "(hebdomadaires, mensuelles et permanentes restent au central).");
        }
    }

    private BackupPolicy policyFor(String scope) {
        return policyRepository.findByScope(scope).orElseGet(() -> BackupPolicy.builder().scope(scope).build());
    }

    private void saveRule(String scope, BackupKind kind, Integer max, Integer duration, Player by) {
        BackupPolicy policy = policyFor(scope);
        policy.set(kind, max != null ? max : policy.max(kind), duration != null ? duration : policy.duration(kind));
        policy.setUpdatedBy(by.getMinecraftUsername());
        policyRepository.save(policy);
    }

    private static String unitText(BackupKind k, int v) {
        return v + " " + k.unit();
    }

    /**
     * Réglage d'un type pour un serveur. Central : propriétaire entre le minimum du
     * réseau et les limites de son rôle. Machine (local) : propriétaire sous le plafond
     * de la machine. Admins : sans limite. reset : revient aux défauts.
     */
    public Map<String, Object> setServerRule(Long serverId, Long playerId, BackupKind kind, Integer max,
                                             Integer duration, boolean reset, boolean local) {
        return tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            boolean admin = AccessService.isAdmin(p);
            if (!admin && !AccessService.isOwner(p, s)) {
                throw new RuntimeException("Seuls le propriétaire du serveur et les admins règlent ses sauvegardes.");
            }
            if (local && !localEnabled(s)) {
                throw new RuntimeException("La machine de ce serveur ne garde pas de sauvegardes : tout va au central.");
            }
            String scope = (local ? "local:server:" : "server:") + s.getId();
            if (reset) {
                policyRepository.findByScope(scope).ifPresent(policyRepository::delete);
            } else {
                if (kind == null) {
                    throw new RuntimeException("Type inconnu : quotidienne, hebdomadaire, mensuelle, manuelle ou permanente.");
                }
                checkBounds(kind, max, duration);
                if (local) {
                    checkLocalKind(kind);
                }
                if (!admin) {
                    if (local) {
                        Rule cap = localCeiling(s.getNode()).get(kind);
                        if (max != null && max > cap.max()) {
                            throw new RuntimeException("La machine permet au plus " + cap.max() + " sauvegarde(s) "
                                    + kind.label() + " (réglage de l'hôte).");
                        }
                        if (duration != null && duration > cap.duration()) {
                            throw new RuntimeException("La machine garde une sauvegarde " + kind.label() + " au plus "
                                    + unitText(kind, cap.duration()) + " (réglage de l'hôte).");
                        }
                    } else {
                        Rule cap = limits(s.getOwner()).get(kind);
                        Rule min = minimum().get(kind);
                        if (max != null && max > cap.max()) {
                            throw new RuntimeException("Ton rôle permet au plus " + cap.max() + " sauvegarde(s) " + kind.label() + ".");
                        }
                        if (duration != null && duration > cap.duration()) {
                            throw new RuntimeException("Ton rôle permet de garder une sauvegarde " + kind.label()
                                    + " au plus " + unitText(kind, cap.duration()) + ".");
                        }
                        if (min.max() > 0 && max != null && max < min.max()) {
                            throw new RuntimeException("Le réseau garde toujours au moins " + min.max() + " sauvegarde(s) "
                                    + kind.label() + " au central.");
                        }
                        if (min.max() > 0 && duration != null && duration < min.duration()) {
                            throw new RuntimeException("Le réseau garde toujours une sauvegarde " + kind.label()
                                    + " au moins " + unitText(kind, min.duration()) + " au central.");
                        }
                    }
                }
                saveRule(scope, kind, max, duration, p);
            }
            log.info("{} : sauvegardes {}de {} {}", p.getMinecraftUsername(), local ? "locales " : "", s.getVelocityName(),
                    reset ? "remises par défaut" : kind + " = " + max + " / " + duration);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rules", rulesToMap(effective(s)));
            out.put("localRules", rulesToMap(localEffective(s), LOCAL_KINDS));
            return out;
        });
    }

    /**
     * Admins : défauts du réseau ("network"), limites d'un rôle ("rank:<groupe>"),
     * minimum au central ("min"), minimum que les hôtes doivent offrir ("local").
     */
    public Map<String, Object> setScopeRule(Long playerId, String scope, BackupKind kind, Integer max,
                                            Integer duration, boolean reset) {
        return tx().execute(status -> {
            Player p = accessService.player(playerId);
            if (!AccessService.isAdmin(p)) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            String sc = normalizeScope(scope);
            if (reset) {
                if (sc.equals("network") || sc.equals("local:network")) {
                    throw new RuntimeException("Ces réglages ne se suppriment pas, change-les type par type.");
                }
                policyRepository.findByScope(sc).ifPresent(policyRepository::delete);
            } else if (kind != null) {
                checkBounds(kind, max, duration);
                if (sc.equals("local:network")) {
                    checkLocalKind(kind);
                }
                saveRule(sc, kind, max, duration, p);
                log.info("{} : sauvegardes {} {} = {} / {}", p.getMinecraftUsername(), sc, kind, max, duration);
            }
            return describeScope(sc);
        });
    }

    private static String normalizeScope(String scope) {
        String s = scope == null ? "network" : scope.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "network", "reseau", "réseau" -> {
                return "network";
            }
            case "min", "minimum" -> {
                return "min";
            }
            case "local", "local:network", "machine" -> {
                return "local:network";
            }
            default -> {
            }
        }
        String rank = s.startsWith("rank:") ? s.substring(5) : s;
        if (!rank.matches("[a-z0-9_-]{1,32}")) {
            throw new RuntimeException("Rôle invalide : " + scope);
        }
        return "rank:" + rank;
    }

    /** Réglages d'un scope, tels qu'ils s'appliquent */
    public Map<String, Object> describeScope(String scope) {
        Map<BackupKind, Rule> rules;
        Set<BackupKind> kinds = EnumSet.allOf(BackupKind.class);
        if (scope.equals("network")) {
            rules = resolve("network");
        } else if (scope.equals("min")) {
            rules = minimum();
        } else if (scope.equals("local:network")) {
            rules = localMinimum();
            kinds = LOCAL_KINDS;
        } else {
            rules = resolve(scope, "network");
        }
        return Map.of("scope", scope, "rules", rulesToMap(rules, kinds));
    }

    public Map<String, Object> getScope(Long playerId, String scope) {
        return tx().execute(status -> {
            if (!AccessService.isAdmin(accessService.player(playerId))) {
                throw new RuntimeException("Réservé aux admins du réseau.");
            }
            return describeScope(normalizeScope(scope));
        });
    }

    // ------------------------------------------------------------ hôtes ----

    private static boolean hosts(Player p, Node n) {
        return n.getOwnerPlayer() != null && n.getOwnerPlayer().getId().equals(p.getId());
    }

    private Map<String, Object> describeNode(Node n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("machine", n.getId());
        m.put("hostname", n.getHostname() == null ? "?" : n.getHostname());
        m.put("acceptsLocal", Boolean.TRUE.equals(n.getAcceptsLocalBackups()));
        m.put("host", n.getOwnerPlayer() == null ? null : n.getOwnerPlayer().getMinecraftUsername());
        m.put("customized", policyRepository.findByScope("local:node:" + n.getId()).isPresent());
        m.put("rules", rulesToMap(localCeiling(n), LOCAL_KINDS));
        return m;
    }

    /** Machines de l'hôte (toutes pour un admin) et leurs réglages locaux */
    public Map<String, Object> hostSettings(Long playerId, Long machine) {
        return tx().execute(status -> {
            Player p = accessService.player(playerId);
            boolean admin = AccessService.isAdmin(p);
            List<Map<String, Object>> machines = new ArrayList<>();
            for (Node n : nodeRepository.findAll()) {
                if (Boolean.TRUE.equals(n.getIsRevoked()) || (machine != null && !machine.equals(n.getId()))) {
                    continue;
                }
                if (admin || hosts(p, n)) {
                    machines.add(describeNode(n));
                }
            }
            if (machine != null && machines.isEmpty()) {
                throw new RuntimeException("Machine " + machine + " introuvable, ou tu n'en es pas l'hôte.");
            }
            machines.sort(Comparator.comparing(m -> (Long) m.get("machine")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("machines", machines);
            out.put("minimum", rulesToMap(localMinimum(), LOCAL_KINDS));
            return out;
        });
    }

    /**
     * Plafond des sauvegardes gardées sur la machine, réglé par son hôte (ou un admin).
     * L'hôte ne descend pas sous le minimum fixé par l'admin.
     */
    public Map<String, Object> setHostRule(Long playerId, Long machine, BackupKind kind, Integer max,
                                           Integer duration, boolean reset) {
        return tx().execute(status -> {
            Player p = accessService.player(playerId);
            boolean admin = AccessService.isAdmin(p);
            Node n = nodeRepository.findById(machine)
                    .filter(x -> !Boolean.TRUE.equals(x.getIsRevoked()))
                    .orElseThrow(() -> new RuntimeException("Machine " + machine + " introuvable."));
            if (!admin && !hosts(p, n)) {
                throw new RuntimeException("Seuls l'hôte de la machine et les admins règlent ses sauvegardes.");
            }
            if (!Boolean.TRUE.equals(n.getAcceptsLocalBackups())) {
                throw new RuntimeException("La machine " + machine + " ne garde pas de sauvegardes "
                        + "(à activer sur la machine : node-setup.sh --capacity).");
            }
            String scope = "local:node:" + n.getId();
            if (reset) {
                policyRepository.findByScope(scope).ifPresent(policyRepository::delete);
            } else {
                if (kind == null) {
                    throw new RuntimeException("Type : quotidienne ou manuelle.");
                }
                checkLocalKind(kind);
                checkBounds(kind, max, duration);
                if (!admin) {
                    Rule min = localMinimum().get(kind);
                    if (max != null && max < min.max()) {
                        throw new RuntimeException("Le réseau demande d'offrir au moins " + min.max()
                                + " sauvegarde(s) " + kind.label() + " par serveur.");
                    }
                    if (duration != null && duration < min.duration()) {
                        throw new RuntimeException("Le réseau demande de garder une sauvegarde " + kind.label()
                                + " au moins " + unitText(kind, min.duration()) + ".");
                    }
                }
                saveRule(scope, kind, max, duration, p);
            }
            log.info("{} : plafond des sauvegardes de la machine {} {}", p.getMinecraftUsername(), n.getId(),
                    reset ? "remis au minimum du réseau" : kind + " = " + max + " / " + duration);
            return describeNode(n);
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

    private void ensureLocalPassword(Server s) {
        if (s.getLocalBackupPassword() == null) {
            s.setLocalBackupPassword(secret());
            serverRepository.save(s);
        }
    }

    // ------------------------------------------------------------ automatique ----

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void scheduledBackups() {
        if (!enabled) {
            return;
        }
        List<List<Job>> due;
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
        for (List<Job> jobs : due) {
            Job first = jobs.get(0);
            if (runningNodes.contains(first.nodeId()) || !runningServers.add(first.serverId())) {
                continue;
            }
            runningNodes.add(first.nodeId());
            CompletableFuture.runAsync(() -> {
                try {
                    for (Job job : jobs) {
                        execute(job, BackupType.AUTO, null);
                    }
                } finally {
                    runningServers.remove(first.serverId());
                    runningNodes.remove(first.nodeId());
                }
            });
        }
    }

    /** Réserve le serveur (et sa machine) : aucune sauvegarde pendant une restauration */
    public boolean tryLock(long serverId, long nodeId) {
        if (!runningServers.add(serverId)) {
            return false;
        }
        runningNodes.add(nodeId);
        return true;
    }

    public void unlock(long serverId, long nodeId) {
        runningServers.remove(serverId);
        runningNodes.remove(nodeId);
    }

    /**
     * Données de restauration d'une sauvegarde pour l'agent de la machine "node".
     * Une sauvegarde du central n'est lisible qu'avec les identifiants de la machine
     * qui l'a faite : on ne les donne jamais à une autre machine.
     */
    public Map<String, Object> restoreData(Backup b, Node node, Server target) {
        if (b.getSnapshotId() == null) {
            throw new RuntimeException("Sauvegarde n°" + b.getId() + " sans instantané enregistré.");
        }
        if (b.getNodeId() == null || !b.getNodeId().equals(node.getId())) {
            throw new RuntimeException("La sauvegarde n°" + b.getId() + " a été faite sur une autre machine : "
                    + "la restaurer ailleurs viendra avec le déplacement de serveurs.");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("snapshot_id", b.getSnapshotId());
        if (at(b, LOCAL)) {
            if (target == null || target.getLocalBackupPassword() == null || !localEnabled(target)) {
                throw new RuntimeException("La sauvegarde n°" + b.getId() + " n'est plus disponible sur la machine.");
            }
            data.put("local", true);
            data.put("repo_password", target.getLocalBackupPassword());
        } else {
            ensureCredentials(node);
            data.put("local", false);
            data.put("repository", "rest:" + repoBase + "/node" + node.getId() + "/");
            data.put("http_user", "node" + node.getId());
            data.put("http_password", node.getBackupHttpPassword());
            data.put("repo_password", node.getBackupRepoPassword());
        }
        return data;
    }

    private static boolean olderThan(LocalDateTime t, Duration d, LocalDateTime now) {
        return t == null || !t.plus(d).minus(SLACK).isAfter(now);
    }

    private static boolean at(Backup b, String location) {
        return location.equals(b.getLocation() == null ? CENTRAL : b.getLocation());
    }

    private static List<Backup> at(List<Backup> list, String location) {
        List<Backup> out = new ArrayList<>();
        for (Backup b : list) {
            if (at(b, location)) {
                out.add(b);
            }
        }
        return out;
    }

    /** Dernier essai à cet emplacement encore en attente (échec récent, en cours...) ? */
    private static boolean backingOff(List<Backup> recent, String location, LocalDateTime now) {
        for (Backup b : recent) {
            if (!at(b, location)) {
                continue;
            }
            return switch (b.getStatus()) {
                case "FAILED" -> b.getCreatedAt().isAfter(now.minus(FAILURE_BACKOFF));
                case "NO_SPACE" -> b.getCreatedAt().isAfter(now.minus(NO_SPACE_BACKOFF));
                case "RUNNING" -> b.getCreatedAt().isAfter(now.minusHours(3));
                default -> false;
            };
        }
        return false;
    }

    /** Sauvegardes automatiques dues, par serveur (machine d'abord, puis central) */
    private List<List<Job>> planDue() {
        LocalDateTime now = LocalDateTime.now();
        List<List<Job>> due = new ArrayList<>();
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
            List<Backup> recent = backupRepository.findTop30ByServerTagIdOrderByCreatedAtDesc(s.getId());
            List<Backup> ok = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            List<Job> jobs = new ArrayList<>();

            // Sur la machine : quotidienne
            if (localEnabled(s) && !backingOff(recent, LOCAL, now)
                    && localEffective(s).get(BackupKind.DAILY).max() > 0) {
                List<Backup> local = at(ok, LOCAL);
                LocalDateTime lastDaily = null;
                for (Backup b : local) {
                    if (b.is(BackupKind.DAILY)) {
                        lastDaily = b.getCreatedAt();
                        break;
                    }
                }
                LocalDateTime lastAny = local.isEmpty() ? null : local.get(0).getCreatedAt();
                boolean isDue = running
                        ? olderThan(lastDaily != null ? lastDaily : s.getCreatedAt(), Duration.ofDays(1), now)
                        : s.getLastStoppedAt() != null && (lastAny == null || lastAny.isBefore(s.getLastStoppedAt()));
                if (isDue) {
                    jobs.add(new Job(s.getId(), node.getId(), s.getVelocityName(), LOCAL, EnumSet.of(BackupKind.DAILY)));
                }
            }

            // Au central : quotidienne, hebdomadaire, mensuelle
            if (!backingOff(recent, CENTRAL, now)) {
                Map<BackupKind, Rule> rules = effective(s);
                List<Backup> central = at(ok, CENTRAL);
                LocalDateTime lastAuto = null;
                LocalDateTime lastWeekly = null;
                LocalDateTime lastMonthly = null;
                LocalDateTime lastAny = central.isEmpty() ? null : central.get(0).getCreatedAt();
                for (Backup b : central) {
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
                if (isDue) {
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
                    jobs.add(new Job(s.getId(), node.getId(), s.getVelocityName(), CENTRAL, kinds));
                }
            }
            if (!jobs.isEmpty()) {
                due.add(jobs);
            }
        }
        return due;
    }

    // ------------------------------------------------------------ expiration ----

    private void markExpired(List<Backup> ok, Set<Long> kept, LocalDateTime now, String name) {
        for (Backup b : ok) {
            if (!kept.contains(b.getId())) {
                b.setStatus("EXPIRED");
                b.setExpiresAt(now);
                backupRepository.save(b);
                log.info("Sauvegarde {} ({}) de {} expirée", b.getId(), b.getLocation(), name);
            }
        }
    }

    /**
     * Marque EXPIRED les sauvegardes qu'aucun type ne retient plus, à chaque emplacement.
     * Pour chaque type, les "max" plus récentes de ce type dans leur durée de vie
     * (permanente : sans durée). La plus récente de chaque emplacement n'expire jamais.
     * Serveur supprimé : expires_at, fixée à la suppression.
     */
    private void expire() {
        LocalDateTime now = LocalDateTime.now();
        for (Server s : serverRepository.findAll()) {
            List<Backup> all = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");

            // Central
            List<Backup> ok = at(all, CENTRAL);
            Map<BackupKind, Rule> rules = effective(s);
            // Au-delà du max de permanentes : la plus anciennement marquée ne l'est plus
            // (elle retombe dans ses autres types, et peut donc expirer)
            Set<Long> perm = keptPermanent(ok, rules.get(BackupKind.PERMANENT));
            for (Backup b : ok) {
                if (b.is(BackupKind.PERMANENT) && !perm.contains(b.getId())) {
                    b.mark(BackupKind.PERMANENT, false);
                    backupRepository.save(b);
                    log.info("Sauvegarde {} de {} : n'est plus permanente (max {})", b.getId(), s.getVelocityName(),
                            rules.get(BackupKind.PERMANENT).max());
                }
            }
            if (ok.size() > 1) {
                Set<Long> kept = retained(ok, rules, now, false);
                kept.add(ok.get(0).getId());
                markExpired(ok, kept, now, s.getVelocityName());
            }

            // Machine
            List<Backup> local = at(all, LOCAL);
            if (!localEnabled(s)) {
                // La machine ne garde plus de sauvegardes (ou a été révoquée) : l'agent a vidé son dossier
                for (Backup b : backupRepository.findTop30ByServerTagIdOrderByCreatedAtDesc(s.getId())) {
                    if (at(b, LOCAL) && ("SUCCESS".equals(b.getStatus()) || "EXPIRED".equals(b.getStatus()))) {
                        b.setStatus("DELETED");
                        b.setMessage("La machine ne garde plus de sauvegardes");
                        backupRepository.save(b);
                    }
                }
            } else if (local.size() > 1) {
                Set<Long> kept = retained(local, localEffective(s), now, false);
                kept.add(local.get(0).getId());
                markExpired(local, kept, now, s.getVelocityName());
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

    /** Permanentes retenues : les "max" plus récemment marquées */
    private static Set<Long> keptPermanent(List<Backup> ok, Rule r) {
        List<Backup> perm = new ArrayList<>();
        for (Backup b : ok) {
            if (b.is(BackupKind.PERMANENT)) {
                perm.add(b);
            }
        }
        perm.sort(Comparator.comparing((Backup b) -> b.getPermanentAt() != null ? b.getPermanentAt() : b.getCreatedAt())
                .reversed());
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < Math.min(Math.max(r.max(), 0), perm.size()); i++) {
            ids.add(perm.get(i).getId());
        }
        return ids;
    }

    /**
     * Ids retenus par au moins un type (ok : du plus récent au plus ancien). Une
     * permanente ne prend pas de place dans les autres types : faire une 3e manuelle
     * ne touche jamais une permanente.
     */
    private static Set<Long> retained(List<Backup> ok, Map<BackupKind, Rule> rules, LocalDateTime now,
                                      boolean serverDeleted) {
        Set<Long> perm = keptPermanent(ok, rules.get(BackupKind.PERMANENT));
        Set<Long> kept = new HashSet<>(serverDeleted ? Set.of() : perm);
        for (BackupKind k : BackupKind.values()) {
            if (k == BackupKind.PERMANENT) {
                continue;
            }
            Rule r = rules.get(k);
            int rank = 0;
            for (Backup b : ok) {
                if (!b.is(k) || perm.contains(b.getId())) {
                    continue;
                }
                boolean inCount = rank < r.max();
                rank++;
                if (inCount && b.getCreatedAt().plusDays(r.duration()).isAfter(now)) {
                    kept.add(b.getId());
                }
            }
        }
        return kept;
    }

    /** Permanentes et sauvegardes valides d'un serveur, pour dire ce qu'une action a changé */
    private record State(Set<Long> permanent, Set<Long> success) {}

    private State state(long serverId) {
        Set<Long> permanent = new HashSet<>();
        Set<Long> success = new HashSet<>();
        for (Backup b : backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(serverId, "SUCCESS")) {
            success.add(b.getId());
            if (b.is(BackupKind.PERMANENT)) {
                permanent.add(b.getId());
            }
        }
        return new State(permanent, success);
    }

    /** "demoted" : ne sont plus permanentes ; "expired" : supprimées par la rotation */
    private void putChanges(Map<String, Object> m, State before, long serverId, Long except) {
        State after = state(serverId);
        List<Long> demoted = new ArrayList<>();
        for (Long id : before.permanent()) {
            if (!after.permanent().contains(id) && !id.equals(except)) {
                demoted.add(id);
            }
        }
        List<Long> expired = new ArrayList<>();
        for (Long id : before.success()) {
            if (!after.success().contains(id)) {
                expired.add(id);
            }
        }
        Collections.sort(demoted);
        Collections.sort(expired);
        m.put("demoted", demoted);
        m.put("expired", expired);
    }

    /**
     * Le serveur va être supprimé : ses sauvegardes au central restent le temps de leur
     * durée de vie (permanente : "durée" heures après la suppression, 0 = tout de suite).
     * Celles de la machine partent avec le serveur (l'agent supprime son dossier).
     */
    public void onServerDeleting(Long serverId) {
        tx().executeWithoutResult(status -> {
            Server s = serverRepository.findById(serverId).orElse(null);
            if (s == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            Map<BackupKind, Rule> rules = effective(s);
            List<Backup> all = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            List<Backup> ok = at(all, CENTRAL);
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
                // De quoi recréer le serveur à partir de cette sauvegarde (/mcs restore deleted)
                b.setOwnerId(s.getOwner().getId());
                b.setServerName(s.getName());
                b.setServerType(s.getServerType().name());
                b.setMinecraftVersion(s.getMinecraftVersion());
                b.setRamMb(s.getAllocatedRamMb());
                b.setCpuCores(s.getAllocatedCpuCores());
                backupRepository.save(b);
            }
            for (Backup b : backupRepository.findTop30ByServerTagIdOrderByCreatedAtDesc(s.getId())) {
                if (at(b, LOCAL) && ("SUCCESS".equals(b.getStatus()) || "EXPIRED".equals(b.getStatus()))) {
                    b.setStatus("DELETED");
                    b.setServerRef(s.getVelocityName());
                    backupRepository.save(b);
                }
            }
            policyRepository.findByScope("server:" + s.getId()).ifPresent(policyRepository::delete);
            policyRepository.findByScope("local:server:" + s.getId()).ifPresent(policyRepository::delete);
            log.info("Serveur {} supprimé : {} sauvegarde(s) au central gardée(s) jusqu'à leur expiration",
                    s.getVelocityName(), ok.size());
        });
    }

    // ------------------------------------------------------------ exécution ----

    private static List<String> snapshots(List<Backup> list) {
        List<String> out = new ArrayList<>();
        for (Backup b : list) {
            if (b.getSnapshotId() != null) {
                out.add(b.getSnapshotId());
            }
        }
        return out;
    }

    private static boolean matches(String snapshotId, Collection<String> ids) {
        for (String id : ids) {
            if (id != null && snapshotId != null && (id.startsWith(snapshotId) || snapshotId.startsWith(id))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asText());
        }
        return out;
    }

    /** Lance la sauvegarde sur la machine et l'enregistre */
    private Backup execute(Job job, BackupType type, String requestedBy) {
        boolean local = LOCAL.equals(job.location());
        Map<String, Object> data = new HashMap<>();
        Backup record = tx().execute(status -> {
            Server s = serverRepository.findById(job.serverId()).orElseThrow();
            Node node = s.getNode();
            data.put("server_id", s.getId());
            data.put("tag", "server-" + s.getId());
            data.put("host", "node" + node.getId());
            if (local) {
                ensureLocalPassword(s);
                List<Backup> all = backupRepository.findTop30ByServerTagIdOrderByCreatedAtDesc(s.getId());
                List<Backup> expired = new ArrayList<>();
                List<Backup> evictable = new ArrayList<>();
                Integer lastAdded = null;
                for (Backup b : all) {
                    if (!at(b, LOCAL)) {
                        continue;
                    }
                    if ("EXPIRED".equals(b.getStatus())) {
                        expired.add(b);
                    } else if ("SUCCESS".equals(b.getStatus())) {
                        evictable.add(0, b); // du plus ancien au plus récent
                        if (lastAdded == null) {
                            lastAdded = b.getSizeMb();
                        }
                    }
                }
                data.put("local", true);
                data.put("repo_password", s.getLocalBackupPassword());
                data.put("quota_mb", s.getAllocatedStorageMb() == null ? 0 : s.getAllocatedStorageMb());
                // Estimation de la prochaine : 2 × la dernière (dédupliquée), au moins 50 Mo ;
                // sans sauvegarde locale, l'agent compte la taille des données
                data.put("estimate_mb", lastAdded == null ? 0 : Math.max(50, lastAdded * 2));
                data.put("forget", snapshots(expired));
                data.put("evict", snapshots(evictable));
            } else {
                ensureCredentials(node);
                data.put("repository", "rest:" + repoBase + "/node" + node.getId() + "/");
                data.put("http_user", "node" + node.getId());
                data.put("http_password", node.getBackupHttpPassword());
                data.put("repo_password", node.getBackupRepoPassword());
            }
            Backup b = Backup.builder()
                    .server(s)
                    .serverTagId(s.getId())
                    .serverRef(s.getVelocityName())
                    .ownerId(s.getOwner().getId())
                    .backupType(type)
                    .location(job.location())
                    .storagePath(local ? "local:node" + node.getId() : "node" + node.getId())
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
        log.info("Sauvegarde {} {} {} de {} sur la machine {}", backupId, job.location(), job.kinds(),
                job.velocityName(), job.nodeId());

        String status;
        String message = null;
        String snapshot = null;
        Integer addedMb = null;
        Integer totalMb = null;
        Integer repoMb = null;
        List<String> evicted = List.of();
        List<String> present = null;
        try {
            JsonNode result = agentWebSocketHandler.sendCommand(job.nodeId(), "backup_server", data, AGENT_TIMEOUT_SECONDS)
                    .get(AGENT_TIMEOUT_SECONDS + 60, TimeUnit.SECONDS);
            if (result == null || (result.has("success") && !result.get("success").asBoolean())) {
                status = "FAILED";
                message = result == null ? "pas de réponse de la machine"
                        : result.path("message").asText(result.path("error").asText("erreur inconnue"));
            } else {
                JsonNode r = result.path("result");
                evicted = texts(r.path("evicted"));
                if (r.has("present")) {
                    present = texts(r.path("present"));
                }
                if (r.has("repo_mb")) {
                    repoMb = r.path("repo_mb").asInt();
                }
                if ("no_space".equals(r.path("skipped").asText(""))) {
                    status = "NO_SPACE";
                    message = "Pas assez de place dans le quota du serveur (" + r.path("data_mb").asLong() + " Mo de données, "
                            + r.path("repo_mb").asLong() + " Mo de sauvegardes, quota " + r.path("quota_mb").asLong() + " Mo)";
                } else {
                    status = "SUCCESS";
                    snapshot = r.path("snapshot_id").asText(null);
                    addedMb = (int) (r.path("data_added").asLong(0) / (1024 * 1024));
                    totalMb = (int) (r.path("total_bytes").asLong(0) / (1024 * 1024));
                }
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
        Integer fRepo = repoMb;
        List<String> fEvicted = evicted;
        List<String> fPresent = present;
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
            if (local) {
                // Supprimées par l'agent (place à faire ou expirées) -> DELETED
                for (Backup x : backupRepository.findTop30ByServerTagIdOrderByCreatedAtDesc(job.serverId())) {
                    if (!at(x, LOCAL) || x.getId().equals(backupId) || x.getSnapshotId() == null) {
                        continue;
                    }
                    boolean gone = matches(x.getSnapshotId(), fEvicted)
                            || (fPresent != null && ("EXPIRED".equals(x.getStatus()) || "SUCCESS".equals(x.getStatus()))
                                && !matches(x.getSnapshotId(), fPresent));
                    if (gone && !"DELETED".equals(x.getStatus())) {
                        x.setStatus("DELETED");
                        if (matches(x.getSnapshotId(), fEvicted)) {
                            x.setMessage("Supprimée pour faire de la place (quota du serveur)");
                        }
                        backupRepository.save(x);
                    }
                }
                if (fRepo != null) {
                    serverRepository.findById(job.serverId()).ifPresent(s -> {
                        s.setLocalBackupMb(fRepo);
                        serverRepository.save(s);
                    });
                }
            }
            // Rotation tout de suite (ex. 3e manuelle : la plus ancienne n'est plus retenue)
            expire();
            return saved;
        });
        if ("SUCCESS".equals(status)) {
            log.info("Sauvegarde {} de {} réussie : {} Mo nouveaux / {} Mo", backupId, job.velocityName(), addedMb, totalMb);
        } else {
            log.warn("Sauvegarde {} de {} : {} ({})", backupId, job.velocityName(), status, message);
        }
        return done;
    }

    // ------------------------------------------------------------ à la demande ----

    /**
     * Sauvegarde demandée par un joueur : manuelle (droit POWER ; sur la machine si elle
     * l'accepte, sinon au central) ou permanente (propriétaire et admins ; au central).
     * Attend jusqu'à 20 min.
     */
    public Map<String, Object> backupNow(Long serverId, Long playerId, boolean permanent) throws Exception {
        if (!enabled) {
            throw new RuntimeException("Les sauvegardes ne sont pas encore configurées sur ce réseau.");
        }
        Job job = tx().execute(status -> {
            Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
            Player p = accessService.player(playerId);
            String location = CENTRAL;
            if (permanent) {
                if (!AccessService.isAdmin(p) && !AccessService.isOwner(p, s)) {
                    throw new RuntimeException("Seuls le propriétaire du serveur et les admins font des sauvegardes permanentes.");
                }
                if (effective(s).get(BackupKind.PERMANENT).max() <= 0) {
                    throw new RuntimeException("Les sauvegardes permanentes sont désactivées pour ce serveur.");
                }
            } else {
                accessService.require(p, s, AccessService.Right.POWER);
                if (localEnabled(s) && localEffective(s).get(BackupKind.MANUAL).max() > 0) {
                    location = LOCAL;
                } else if (effective(s).get(BackupKind.MANUAL).max() <= 0) {
                    throw new RuntimeException("Les sauvegardes manuelles sont désactivées pour ce serveur.");
                }
            }
            if (s.getNode() == null || !agentWebSocketHandler.isNodeOnline(s.getNode().getId())) {
                throw new RuntimeException("La machine qui héberge ce serveur est hors ligne");
            }
            return new Job(s.getId(), s.getNode().getId(), s.getVelocityName(), location,
                    EnumSet.of(permanent ? BackupKind.PERMANENT : BackupKind.MANUAL));
        });
        State before = tx().execute(status -> state(serverId));
        String requestedBy = tx().execute(status -> accessService.player(playerId).getMinecraftUsername());
        boolean centralManualOn = tx().execute(status -> serverRepository.findById(serverId)
                .map(s -> effective(s).get(BackupKind.MANUAL).max() > 0).orElse(false));
        if (!runningServers.add(job.serverId())) {
            throw new RuntimeException("Une sauvegarde de ce serveur est déjà en cours.");
        }
        runningNodes.add(job.nodeId());
        final boolean[] fellBack = {false};
        CompletableFuture<Backup> future = CompletableFuture.supplyAsync(() -> {
            try {
                Backup b = execute(job, BackupType.MANUAL, requestedBy);
                if ("NO_SPACE".equals(b.getStatus()) && centralManualOn) {
                    // Pas de place sur la machine : au central à la place
                    fellBack[0] = true;
                    b = execute(new Job(job.serverId(), job.nodeId(), job.velocityName(), CENTRAL, job.kinds()),
                            BackupType.MANUAL, requestedBy);
                }
                return b;
            } finally {
                runningServers.remove(job.serverId());
                runningNodes.remove(job.nodeId());
            }
        });
        try {
            Backup b = future.get(20, TimeUnit.MINUTES);
            return tx().execute(status -> {
                Map<String, Object> m = describe(backupRepository.findById(b.getId()).orElse(b));
                m.put("fallback", fellBack[0]);
                putChanges(m, before, serverId, null);
                return m;
            });
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
            if (permanent && at(b, LOCAL)) {
                throw new RuntimeException("La n°" + backupId + " est sur la machine : seules les sauvegardes du central "
                        + "peuvent être permanentes. Fais-en une nouvelle : /mcs backup keep <serveur>");
            }
            if (permanent && effective(s).get(BackupKind.PERMANENT).max() <= 0) {
                throw new RuntimeException("Les sauvegardes permanentes sont désactivées pour ce serveur.");
            }
            State before = state(s.getId());
            b.mark(BackupKind.PERMANENT, permanent);
            backupRepository.save(b);
            // Au-delà du max, la plus anciennement marquée n'est plus permanente ;
            // retirer une permanente peut la faire expirer (autres types pleins)
            expire();
            Map<String, Object> m = describe(backupRepository.findById(backupId).orElse(b));
            m.put("maxPermanent", effective(s).get(BackupKind.PERMANENT).max());
            putChanges(m, before, s.getId(), backupId);
            return m;
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
        m.put("location", b.getLocation() == null ? CENTRAL : b.getLocation());
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
            Map<BackupKind, Rule> localRules = localEffective(s);
            List<Backup> all = backupRepository.findByServerTagIdAndStatusOrderByCreatedAtDesc(s.getId(), "SUCCESS");
            List<Backup> centralOk = at(all, CENTRAL);
            List<Backup> localOk = at(all, LOCAL);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", enabled);
            out.put("rules", rulesToMap(rules));
            out.put("limits", rulesToMap(limits(s.getOwner())));
            out.put("minimum", rulesToMap(minimum()));
            out.put("customized", policyRepository.findByScope("server:" + s.getId()).isPresent());
            out.put("canConfigure", AccessService.isAdmin(p) || AccessService.isOwner(p, s));
            out.put("canRestore", AccessService.isAdmin(p) || AccessService.isOwner(p, s));
            out.put("running", runningServers.contains(s.getId()));
            Map<String, Object> local = new LinkedHashMap<>();
            local.put("enabled", localEnabled(s));
            if (localEnabled(s)) {
                local.put("rules", rulesToMap(localRules, LOCAL_KINDS));
                local.put("ceiling", rulesToMap(localCeiling(s.getNode()), LOCAL_KINDS));
                local.put("customized", policyRepository.findByScope("local:server:" + s.getId()).isPresent());
                local.put("repoMb", s.getLocalBackupMb());
                local.put("quotaMb", s.getAllocatedStorageMb());
            }
            out.put("local", local);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Backup b : backupRepository.findTop30ByServerTagIdOrderByCreatedAtDesc(s.getId())) {
                if ("DELETED".equals(b.getStatus())) {
                    continue;
                }
                Map<String, Object> m = describe(b);
                if ("SUCCESS".equals(b.getStatus())) {
                    LocalDateTime until = at(b, LOCAL) ? expiry(b, localRules, localOk) : expiry(b, rules, centralOk);
                    m.put("expiresAt", until == null ? null : until.toString());
                }
                items.add(m);
            }
            out.put("backups", items);
            return out;
        });
    }
}
