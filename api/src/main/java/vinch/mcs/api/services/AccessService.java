package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.entities.*;
import vinch.mcs.api.repositories.PlayerRepository;
import vinch.mcs.api.repositories.ServerCollaboratorRepository;
import vinch.mcs.api.repositories.ServerRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qui peut faire quoi sur un serveur.
 *
 * <pre>
 *                        rejoindre  start/stop  console+fichiers  inviter  retirer  public/privé  supprimer  amener (move)
 * propriétaire              ✔          ✔             ✔              ✔        ✔          ✔            ✔           ✔
 * admin (mcs.admin)         ✔          ✔             ✔                       ✔          ✔            ✔           ✔
 * hébergeur (sa machine)    ✔          ✔             ✔                                                          ✔
 * technicien (invité)       ✔          ✔             ✔
 * gérant (invité)           ✔          ✔
 * membre (invité)           ✔
 * tout le monde             ✔ si le serveur est public
 * </pre>
 *
 * Le proxy demande {@link #checkJoin} avant chaque connexion à un serveur joueur :
 * c'est là que l'accès est réellement appliqué (/mcs join, /goto, /server...).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccessService {

    public enum Right { JOIN, POWER, CONSOLE, FILES, INVITE, REMOVE, VISIBILITY, DELETE, MOVE }

    private static final Set<Right> HOST_RIGHTS = EnumSet.of(Right.JOIN, Right.POWER, Right.CONSOLE, Right.FILES, Right.MOVE);

    private final ServerRepository serverRepository;
    private final PlayerRepository playerRepository;
    private final ServerCollaboratorRepository collaboratorRepository;

    // Laissez-passer à usage unique donnés par /mcs move : "serverId:uuid" -> expiration
    private final Map<String, Instant> passes = new ConcurrentHashMap<>();
    private static final Duration PASS_TTL = Duration.ofSeconds(60);

    public static boolean isAdmin(Player p) {
        return p.getRole() == PlayerRole.ADMIN;
    }

    public static boolean isOwner(Player p, Server s) {
        return s.getOwner().getId().equals(p.getId());
    }

    public static boolean isHost(Player p, Server s) {
        Node n = s.getNode();
        return n != null && n.getOwnerPlayer() != null && n.getOwnerPlayer().getId().equals(p.getId());
    }

    public Optional<ServerCollaborator> collaboration(Player p, Server s) {
        return collaboratorRepository.findByServerIdAndPlayerId(s.getId(), p.getId());
    }

    public Set<Right> rightsOf(Player p, Server s) {
        if (isOwner(p, s)) {
            return EnumSet.allOf(Right.class);
        }
        EnumSet<Right> rights = EnumSet.noneOf(Right.class);
        if (isAdmin(p)) {
            rights.addAll(EnumSet.allOf(Right.class));
            rights.remove(Right.INVITE); // les admins n'invitent personne
        }
        if (isHost(p, s)) {
            rights.addAll(HOST_RIGHTS);
        }
        collaboration(p, s).ifPresent(c -> {
            switch (c.getPermissionLevel()) {
                case TECHNICIAN -> rights.addAll(EnumSet.of(Right.JOIN, Right.POWER, Right.CONSOLE, Right.FILES));
                case OPERATOR -> rights.addAll(EnumSet.of(Right.JOIN, Right.POWER));
                case MEMBER -> rights.add(Right.JOIN);
            }
        });
        if (Boolean.TRUE.equals(s.getIsPublic())) {
            rights.add(Right.JOIN);
        }
        return rights;
    }

    /** Serveurs des autres où le joueur est invité */
    public List<Server> sharedWith(Player p) {
        return collaboratorRepository.findByPlayerId(p.getId()).stream()
                .map(ServerCollaborator::getServer)
                .toList();
    }

    /** Relation principale du joueur avec le serveur, pour l'affichage */
    public String relation(Player p, Server s) {
        if (isOwner(p, s)) {
            return "proprietaire";
        }
        Optional<ServerCollaborator> c = collaboration(p, s);
        if (c.isPresent()) {
            return c.get().getPermissionLevel().label();
        }
        if (isHost(p, s)) {
            return "hebergeur";
        }
        if (isAdmin(p)) {
            return "admin";
        }
        return Boolean.TRUE.equals(s.getIsPublic()) ? "visiteur" : "aucun";
    }

    public void require(Player p, Server s, Right right) {
        if (!rightsOf(p, s).contains(right)) {
            throw new RuntimeException(switch (right) {
                case JOIN -> "Ce serveur est privé : demande une invitation à son propriétaire.";
                case POWER -> "Tu n'as pas le droit de démarrer ou d'arrêter ce serveur.";
                case CONSOLE, FILES -> "Tu n'as pas accès à la console de ce serveur.";
                case INVITE -> "Seul le propriétaire du serveur peut inviter des joueurs.";
                case REMOVE -> "Seul le propriétaire du serveur peut retirer des joueurs.";
                case VISIBILITY -> "Seul le propriétaire du serveur peut le rendre public ou privé.";
                case DELETE -> "Seul le propriétaire du serveur peut le supprimer.";
                case MOVE -> "Seuls l'hébergeur et les admins peuvent amener un joueur sur ce serveur.";
            });
        }
    }

    public Player player(Long id) {
        return playerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Joueur introuvable : " + id));
    }

    public Player playerByName(String username) {
        return playerRepository.findFirstByMinecraftUsernameIgnoreCase(username)
                .orElseThrow(() -> new RuntimeException(
                        "Joueur introuvable : " + username + " (il doit s'être connecté au moins une fois)"));
    }

    // ------------------------------------------------------------ référence ----

    /**
     * Trouve le serveur désigné par un joueur : "nom" (le sien, sinon un serveur
     * où il a un rôle) ou "proprietaire/nom". Un serveur sur lequel il n'a aucun
     * droit est « introuvable » (on ne révèle pas les serveurs privés des autres).
     */
    @Transactional(readOnly = true)
    public Server resolve(Player requester, String ref) {
        String r = ref == null ? "" : ref.trim().toLowerCase(Locale.ROOT);
        Server found;
        int slash = r.indexOf('/');
        if (slash > 0) {
            Player owner = playerRepository.findFirstByMinecraftUsernameIgnoreCase(r.substring(0, slash))
                    .orElseThrow(() -> new RuntimeException("Serveur '" + ref + "' introuvable"));
            found = serverRepository.findByOwnerIdAndName(owner.getId(), r.substring(slash + 1))
                    .orElseThrow(() -> new RuntimeException("Serveur '" + ref + "' introuvable"));
        } else {
            Optional<Server> own = serverRepository.findByOwnerIdAndName(requester.getId(), r);
            if (own.isPresent()) {
                return own.get();
            }
            Map<Long, Server> candidates = new LinkedHashMap<>();
            for (ServerCollaborator c : collaboratorRepository.findByPlayerId(requester.getId())) {
                if (c.getServer().getName().equals(r)) {
                    candidates.put(c.getServer().getId(), c.getServer());
                }
            }
            for (Server s : serverRepository.findHostedByPlayerId(requester.getId())) {
                if (s.getName().equals(r)) {
                    candidates.put(s.getId(), s);
                }
            }
            if (isAdmin(requester) || candidates.isEmpty()) {
                // Admin : tout serveur de ce nom ; autres : les serveurs publics de ce nom
                for (Server s : serverRepository.findAllByName(r)) {
                    if (isAdmin(requester) || Boolean.TRUE.equals(s.getIsPublic())) {
                        candidates.put(s.getId(), s);
                    }
                }
            }
            if (candidates.size() > 1) {
                throw new RuntimeException("Plusieurs serveurs s'appellent '" + r
                        + "' : précise le propriétaire, par exemple " + candidates.values().iterator().next()
                        .getOwner().getMinecraftUsername().toLowerCase(Locale.ROOT) + "/" + r);
            }
            found = candidates.values().stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("Serveur '" + ref + "' introuvable"));
        }
        if (rightsOf(requester, found).isEmpty()) {
            throw new RuntimeException("Serveur '" + ref + "' introuvable");
        }
        return found;
    }

    /** Résumé d'un serveur vu par un joueur (droits compris) */
    @Transactional(readOnly = true)
    public Map<String, Object> describeFor(Player p, Server s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("ref", isOwner(p, s) ? s.getName()
                : s.getOwner().getMinecraftUsername().toLowerCase(Locale.ROOT) + "/" + s.getName());
        m.put("velocityName", s.getVelocityName());
        m.put("ownerUsername", s.getOwner().getMinecraftUsername());
        m.put("status", s.getStatus().name());
        m.put("serverType", s.getServerType().name());
        m.put("minecraftVersion", s.getMinecraftVersion());
        m.put("allocatedRamMb", s.getAllocatedRamMb());
        m.put("allocatedCpuCores", s.getAllocatedCpuCores());
        m.put("isPublic", Boolean.TRUE.equals(s.getIsPublic()));
        m.put("relation", relation(p, s));
        m.put("rights", rightsOf(p, s).stream().map(Enum::name).toList());
        m.put("members", collaboratorRepository.findByServerId(s.getId()).size());
        return m;
    }

    // ------------------------------------------------------------ proxy ----

    /** Le joueur (uuid) peut-il entrer sur ce serveur (nom Velocity) ? Appelé par le proxy. */
    @Transactional(readOnly = true)
    public Map<String, Object> checkJoin(String velocityName, UUID uuid) {
        Optional<Server> server = serverRepository.findByVelocityName(velocityName);
        if (server.isEmpty()) {
            return Map.of("allowed", false, "reason", "Serveur inconnu.");
        }
        Optional<Player> player = playerRepository.findByMinecraftUuid(uuid);
        if (player.isEmpty()) {
            return Map.of("allowed", false, "reason", "Profil MCS introuvable, reconnecte-toi.");
        }
        Player p = player.get();
        Server s = server.get();
        if (Boolean.TRUE.equals(p.getIsBanned())) {
            return Map.of("allowed", false, "reason", "Ton compte est suspendu.");
        }
        if (consumePass(s.getId(), uuid)) {
            log.info("{} entre sur {} avec un laissez-passer", p.getMinecraftUsername(), s.getVelocityName());
            return Map.of("allowed", true, "reason", "pass");
        }
        if (rightsOf(p, s).contains(Right.JOIN)) {
            return Map.of("allowed", true, "reason", relation(p, s));
        }
        return Map.of("allowed", false,
                "reason", "Ce serveur est privé : demande une invitation à son propriétaire.");
    }

    public void grantPass(Long serverId, UUID uuid) {
        passes.put(serverId + ":" + uuid, Instant.now().plus(PASS_TTL));
    }

    private boolean consumePass(Long serverId, UUID uuid) {
        Instant now = Instant.now();
        passes.values().removeIf(exp -> exp.isBefore(now));
        Instant exp = passes.remove(serverId + ":" + uuid);
        return exp != null && exp.isAfter(now);
    }

    // ------------------------------------------------------------ membres ----

    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(Player requester, Server s) {
        if (rightsOf(requester, s).isEmpty()) {
            throw new RuntimeException("Serveur introuvable");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ServerCollaborator c : collaboratorRepository.findByServerId(s.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", c.getPlayer().getMinecraftUsername());
            m.put("level", c.getPermissionLevel().label());
            m.put("addedAt", c.getAddedAt());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("username")).toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Invite un joueur ou change son rôle. Renvoie true si c'est une nouvelle invitation. */
    @Transactional
    public boolean invite(Player requester, Server s, String username, PermissionLevel level) {
        require(requester, s, Right.INVITE);
        Player target = playerByName(username);
        if (isOwner(target, s)) {
            throw new RuntimeException("C'est déjà le propriétaire de ce serveur.");
        }
        Optional<ServerCollaborator> existing = collaboratorRepository.findByServerIdAndPlayerId(s.getId(), target.getId());
        if (existing.isPresent()) {
            ServerCollaborator c = existing.get();
            c.setPermissionLevel(level);
            collaboratorRepository.save(c);
            return false;
        }
        collaboratorRepository.save(ServerCollaborator.builder()
                .server(s)
                .player(target)
                .permissionLevel(level)
                .build());
        log.info("{} invite {} sur {} ({})", requester.getMinecraftUsername(), target.getMinecraftUsername(),
                s.getVelocityName(), level.label());
        return true;
    }

    /** Retire un invité (propriétaire, admin, ou l'invité lui-même qui s'en va) */
    @Transactional
    public void remove(Player requester, Server s, String username) {
        Player target = playerByName(username);
        if (!target.getId().equals(requester.getId())) {
            require(requester, s, Right.REMOVE);
        }
        ServerCollaborator c = collaboratorRepository.findByServerIdAndPlayerId(s.getId(), target.getId())
                .orElseThrow(() -> new RuntimeException(target.getMinecraftUsername() + " n'est pas membre de ce serveur."));
        collaboratorRepository.delete(c);
        log.info("{} retire {} de {}", requester.getMinecraftUsername(), target.getMinecraftUsername(), s.getVelocityName());
    }

    @Transactional
    public void setPublic(Player requester, Server s, boolean isPublic) {
        require(requester, s, Right.VISIBILITY);
        s.setIsPublic(isPublic);
        serverRepository.save(s);
    }
}
