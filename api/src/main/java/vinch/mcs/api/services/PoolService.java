package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vinch.mcs.api.dto.CreateServerRequest;
import vinch.mcs.api.dto.CreateServerResponse;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.entities.PlayerRole;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import vinch.mcs.api.repositories.PlayerRepository;
import vinch.mcs.api.repositories.ServerRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lot 76 : serveurs à la demande (speedrun, farm sur un monde neuf…). Pour chaque groupe de
 * pools.json : « keep » serveurs gardés en permanence (arrêtés quand personne n'y est), jusqu'à
 * « maxExtra » de plus créés quand tous sont pris, supprimés après « idleDeleteHours » sans
 * personne. Les serveurs appartiennent au propriétaire des groupes (pools.json « owner », sinon
 * le premier admin), sont des serveurs de la team (tous les membres y entrent) et ne comptent
 * dans le quota de personne. Pas de sauvegarde automatique : leurs mondes sont jetables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolService {

    private final ServerRepository serverRepository;
    private final PlayerRepository playerRepository;
    private final AccessService accessService;
    private final ServerService serverService;

    /** Groupes dont un serveur est en cours de création (un à la fois par groupe) */
    private final Set<String> creating = ConcurrentHashMap.newKeySet();

    private static final Set<ServerStatus> GONE = Set.of(ServerStatus.DELETED);

    private List<Server> members(String pool) {
        Pools.Pool p = Pools.get(pool);
        List<Server> out = new ArrayList<>();
        List<Server> all = p != null && p.fixed() ? serverRepository.findAllByName(p.server())
                : serverRepository.findByPoolOrderByIdAsc(pool);
        for (Server s : all) {
            if (!GONE.contains(s.getStatus())) {
                out.add(s);
            }
        }
        if (p != null && p.fixed()) {
            // Même nom chez plusieurs joueurs : le serveur de la team d'abord, puis le plus ancien
            out.sort(Comparator.comparing((Server s) -> !Boolean.TRUE.equals(s.getIsTeam())).thenComparing(Server::getId));
        }
        return out;
    }

    private Player owner() {
        String name = Pools.config().owner();
        if (name != null) {
            return playerRepository.findByMinecraftUsername(name)
                    .orElseThrow(() -> new RuntimeException("Propriétaire des serveurs à la demande introuvable : " + name));
        }
        return playerRepository.findByRole(PlayerRole.ADMIN).stream().min(Comparator.comparing(Player::getId))
                .orElseThrow(() -> new RuntimeException("Aucun admin du réseau pour posséder les serveurs à la demande."));
    }

    private static Map<String, Object> describe(Server s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("velocityName", s.getVelocityName());
        m.put("status", s.getStatus().name());
        m.put("lastJoinedAt", s.getLastJoinedAt() == null ? null : s.getLastJoinedAt().toString());
        return m;
    }

    /** Les groupes et leurs serveurs (proxy : /jouer, arrêt des serveurs vides) */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pools.Pool p : Pools.config().pools()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("label", p.label());
            m.put("description", p.description());
            m.put("idleStopMinutes", p.idleStopMinutes());
            m.put("fixed", p.fixed());
            m.put("servers", members(p.name()).stream().map(PoolService::describe).toList());
            out.add(m);
        }
        return out;
    }

    private Pools.Pool pool(String name) {
        Pools.Pool p = Pools.get(name);
        if (p == null) {
            throw new RuntimeException("Jeu inconnu : " + name + ". Choix : "
                    + String.join(", ", Pools.config().pools().stream().map(Pools.Pool::name).toList()));
        }
        return p;
    }

    private void requireMember(Long playerId) {
        Player p = accessService.player(playerId);
        if (!AccessService.isAdmin(p) && !AccessService.isImgMember(p)) {
            throw new RuntimeException("Réservé aux membres de la team.");
        }
    }

    /**
     * Un serveur libre pour ce joueur. occupied : serveurs où des joueurs sont connectés (vu par
     * le proxy). Réponse : action « join » (en marche et libre), « start » (arrêté), « create »
     * (tous pris : en créer un, POST …/create) ou « full » (limite du groupe atteinte).
     */
    public Map<String, Object> claim(String poolName, Long playerId, Collection<String> occupied) {
        Pools.Pool p = pool(poolName);
        requireMember(playerId);
        Set<String> busy = new HashSet<>(occupied == null ? List.of() : occupied);
        List<Server> list = members(p.name());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", p.label());
        // Lot 79 : serveur existant partagé : on y va, qu'il y ait du monde ou non
        if (p.fixed()) {
            if (list.isEmpty()) {
                throw new RuntimeException("Le serveur « " + p.server() + " » de " + p.label() + " n'existe pas.");
            }
            Server s = list.get(0);
            out.put("action", switch (s.getStatus()) {
                case RUNNING -> "join";
                case STOPPED -> "start";
                case STARTING, CREATING -> "wait";
                default -> throw new RuntimeException(p.label() + " n'est pas disponible pour l'instant ("
                        + s.getStatus().name().toLowerCase(java.util.Locale.ROOT) + ").");
            });
            out.put("server", describe(s));
            return out;
        }
        for (ServerStatus want : List.of(ServerStatus.RUNNING, ServerStatus.STOPPED, ServerStatus.STARTING)) {
            for (Server s : list) {
                if (s.getStatus() == want && !busy.contains(s.getVelocityName())) {
                    out.put("action", want == ServerStatus.RUNNING ? "join" : want == ServerStatus.STOPPED ? "start" : "wait");
                    out.put("server", describe(s));
                    return out;
                }
            }
        }
        if (creating.contains(p.name())) {
            out.put("action", "wait-create");
        } else if (list.size() < p.keep() + p.maxExtra()) {
            out.put("action", "create");
            // Serveur gardé (manquant) ou en plus (supprimé après idleDeleteHours sans personne)
            out.put("extra", list.size() >= p.keep());
            out.put("idleDeleteHours", p.idleDeleteHours());
        } else {
            out.put("action", "full");
        }
        return out;
    }

    private String freeName(Pools.Pool p) {
        for (int i = 1; i < 100; i++) {
            String name = p.prefix() + "-" + i;
            if (serverRepository.findAllByName(name).isEmpty()) {
                return name;
            }
        }
        throw new RuntimeException("Plus de nom libre pour " + p.name());
    }

    /** Crée un serveur dans le groupe (jusqu'à 20 min) ; requester null = MCS lui-même (minimum gardé) */
    public Map<String, Object> create(String poolName, Long requesterId) throws Exception {
        Pools.Pool p = pool(poolName);
        if (requesterId != null) {
            requireMember(requesterId);
        }
        if (p.fixed()) {
            throw new RuntimeException(p.label() + " est un serveur existant : rien à créer.");
        }
        if (!creating.add(p.name())) {
            throw new RuntimeException("Un serveur « " + p.label() + " » est déjà en préparation : réessaie dans un instant.");
        }
        try {
            if (members(p.name()).size() >= p.keep() + p.maxExtra()) {
                throw new RuntimeException("Tous les serveurs « " + p.label() + " » sont pris (" + (p.keep() + p.maxExtra())
                        + " au plus) : réessaie plus tard.");
            }
            CreateServerRequest r = new CreateServerRequest();
            Player owner = owner();
            String name = freeName(p);
            r.setOwnerPlayerId(owner.getId());
            r.setName(name);
            r.setDisplayName(p.label() + " " + name.substring(name.lastIndexOf('-') + 1));
            r.setServerType(p.type());
            r.setMinecraftVersion(p.version());
            r.setRamMb(p.ramMb());
            r.setCpuCores(p.cpu());
            r.setTeam(true);
            r.setPool(p.name());
            r.setModrinthProjects(p.modrinth().isEmpty() ? null : String.join(",", p.modrinth()));
            log.info("Serveur à la demande : création de {} (groupe {}, demandé par {})", name, p.name(),
                    requesterId == null ? "MCS" : requesterId);
            CreateServerResponse res = serverService.createServer(r);
            Server s = serverRepository.findById(res.getServerId()).orElseThrow();
            return Map.of("action", "join", "label", p.label(), "server", describe(s));
        } finally {
            creating.remove(p.name());
        }
    }

    /** Serveur vide depuis idleStopMinutes (vu par le proxy) : arrêté, il reste prêt à redémarrer */
    public Map<String, Object> idleStop(Long serverId) throws Exception {
        Server s = serverRepository.findById(serverId).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
        if (s.getPool() == null) {
            throw new RuntimeException("Pas un serveur à la demande");
        }
        if (s.getStatus() == ServerStatus.RUNNING) {
            log.info("Serveur à la demande {} vide : arrêt", s.getName());
            serverService.stopServer(s.getId(), s.getOwner().getId());
        }
        return Map.of("ok", true);
    }

    /**
     * Toutes les 10 min : le minimum de chaque groupe existe, et les serveurs en trop sans personne
     * depuis idleDeleteHours (arrêtés) sont supprimés. Les plus anciens serveurs sont ceux gardés.
     */
    @Scheduled(initialDelay = 180_000, fixedDelay = 600_000)
    public void maintain() {
        List<Pools.Pool> pools = Pools.config().pools();
        // Groupe retiré de pools.json (ex. speedrun et monde-neuf → worldreset) : ses serveurs
        // arrêtés sont supprimés. Jamais quand pools.json manque ou est vide (fichier illisible…)
        if (!pools.isEmpty()) {
            Set<String> names = new HashSet<>(pools.stream().map(Pools.Pool::name).toList());
            for (Server s : serverRepository.findByPoolIsNotNull()) {
                if (!names.contains(s.getPool()) && s.getStatus() == ServerStatus.STOPPED) {
                    try {
                        log.info("Serveur à la demande {} : groupe « {} » retiré, supprimé", s.getName(), s.getPool());
                        serverService.deleteServer(s.getId(), true);
                    } catch (Exception e) {
                        log.warn("Suppression de {} : {}", s.getName(), e.getMessage());
                    }
                }
            }
        }
        for (Pools.Pool p : pools) {
            if (p.fixed()) {
                continue; // serveur existant : ni création ni suppression
            }
            try {
                List<Server> list = members(p.name());
                if (list.size() < p.keep() && !creating.contains(p.name())) {
                    Thread.ofVirtual().start(() -> {
                        try {
                            create(p.name(), null);
                        } catch (Exception e) {
                            log.warn("Groupe {} : création du serveur gardé impossible : {}", p.name(), e.getMessage());
                        }
                    });
                }
                LocalDateTime limit = LocalDateTime.now().minusHours(p.idleDeleteHours());
                for (int i = p.keep(); i < list.size(); i++) {
                    Server s = list.get(i);
                    LocalDateTime last = s.getLastJoinedAt() != null ? s.getLastJoinedAt() : s.getCreatedAt();
                    if (s.getStatus() == ServerStatus.STOPPED && last != null && last.isBefore(limit)) {
                        log.info("Serveur à la demande {} : personne depuis {} h, supprimé", s.getName(), p.idleDeleteHours());
                        serverService.deleteServer(s.getId(), true);
                    }
                }
            } catch (Exception e) {
                log.warn("Groupe {} : {}", p.name(), e.getMessage());
            }
        }
    }
}
