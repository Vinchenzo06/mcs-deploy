package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Entrée IMG (lot 40) : les joueurs qui tapent l'adresse IMG (img-host, ex.
 * img.asearia.ca) arrivent sur le lobby IMG (img-lobby, un serveur MCS privé
 * « pseudo/nom ») au lieu du lobby MCS, et y reviennent quand un serveur les
 * expulse. Seuls ceux qui ont accès au lobby IMG (invités) entrent ; s'il est
 * éteint, il démarre au nom de son propriétaire et le joueur se reconnecte.
 *
 * Désactivé tant que img-host ou img-lobby est vide.
 */
public class ImgGate {

    private static final Duration WAKE_COOLDOWN = Duration.ofMinutes(2);

    private final ProxyServer server;
    private final ApiClient api;
    private final Logger logger;
    private final String host;
    private final String ownerName;
    private final String lobbyName;

    /** Joueurs entrés par l'adresse IMG (jusqu'à leur déconnexion) */
    private final Set<UUID> imgPlayers = ConcurrentHashMap.newKeySet();
    /** Nom Velocity du lobby IMG, connu après la première recherche */
    private volatile String lobbyVelocityName;
    private volatile Instant lastWake = Instant.EPOCH;

    record Lobby(long id, long ownerId, String velocityName, String status) {}

    public ImgGate(ProxyServer server, ApiClient api, Logger logger, String host, String lobbyRef) {
        this.server = server;
        this.api = api;
        this.logger = logger;
        this.host = normalize(host);
        String ref = lobbyRef == null ? "" : lobbyRef.trim();
        int slash = ref.indexOf('/');
        this.ownerName = slash > 0 ? ref.substring(0, slash).trim() : "";
        this.lobbyName = slash > 0 ? ref.substring(slash + 1).trim() : "";
    }

    public boolean enabled() {
        return !host.isEmpty() && !ownerName.isEmpty() && !lobbyName.isEmpty();
    }

    public String describe() {
        return host + " -> " + ownerName + "/" + lobbyName;
    }

    /** Ce joueur est-il entré par l'adresse IMG ? */
    public boolean isImgPlayer(Player player) {
        return enabled() && imgPlayers.contains(player.getUniqueId());
    }

    /** Le lobby IMG s'il est en marche (enregistré dans Velocity) */
    public Optional<RegisteredServer> lobbyServer() {
        String name = lobbyVelocityName;
        if (name == null) {
            Lobby l = fetchLobby();
            name = l == null ? null : l.velocityName();
        }
        return name == null ? Optional.empty() : server.getServer(name);
    }

    static String normalize(String h) {
        if (h == null) {
            return "";
        }
        String s = h.trim().toLowerCase(Locale.ROOT);
        int nul = s.indexOf('\0'); // marqueurs Forge (« \0FML\0 ») si Velocity les a laissés
        if (nul >= 0) {
            s = s.substring(0, nul);
        }
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private boolean cameThroughImg(Player player) {
        return player.getVirtualHost()
                .map(InetSocketAddress::getHostString)
                .map(ImgGate::normalize)
                .filter(host::equals)
                .isPresent();
    }

    private Lobby fetchLobby() {
        try {
            JsonNode s = api.getServerByUsernameAndName(ownerName, lobbyName).get(5, TimeUnit.SECONDS);
            if (!s.hasNonNull("id") || !s.hasNonNull("ownerId") || !s.hasNonNull("velocityName")) {
                logger.error("Lobby IMG {}/{} : réponse de l'API incomplète (API à mettre à jour ?)", ownerName, lobbyName);
                return null;
            }
            Lobby l = new Lobby(s.get("id").asLong(), s.get("ownerId").asLong(),
                    s.get("velocityName").asText(), s.path("status").asText(""));
            lobbyVelocityName = l.velocityName();
            return l;
        } catch (Exception e) {
            logger.warn("Lobby IMG {}/{} introuvable : {}", ownerName, lobbyName, e.getMessage());
            return null;
        }
    }

    private static Component msg(String text) {
        return Component.text(text).color(NamedTextColor.GOLD);
    }

    /**
     * Arrivée sur le réseau. Les handlers Velocity sont asynchrones : les attentes
     * (quelques secondes au plus) ne bloquent pas le réseau.
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!cameThroughImg(player)) {
            return;
        }
        imgPlayers.add(player.getUniqueId());

        Lobby lobby = fetchLobby();
        if (lobby == null) {
            player.disconnect(msg("IMG » Le lobby IMG est indisponible pour le moment. Réessaie plus tard."));
            return;
        }

        // Profil MCS créé tout de suite (PostLoginEvent le fait aussi, mais sans attendre) :
        // sinon un nouveau joueur serait refusé faute de profil
        try {
            api.playerLogin(player.getUniqueId(), player.getUsername()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Profil MCS de {} : {}", player.getUsername(), e.getMessage());
        }

        boolean allowed;
        try {
            JsonNode answer = api.checkAccess(lobby.velocityName(), player.getUniqueId()).get(4, TimeUnit.SECONDS);
            allowed = answer.path("allowed").asBoolean(false);
        } catch (Exception e) {
            logger.warn("Accès de {} au lobby IMG impossible à vérifier : {}", player.getUsername(), e.getMessage());
            player.disconnect(msg("IMG » Vérification d'accès impossible pour le moment, réessaie."));
            return;
        }
        if (!allowed) {
            logger.info("{} refusé à l'entrée IMG (pas invité sur {})", player.getUsername(), lobby.velocityName());
            player.disconnect(msg("IMG » Cette adresse est réservée aux membres de la team IMG.\n"
                    + "Demande une invitation, puis reconnecte-toi."));
            return;
        }

        Optional<RegisteredServer> target = server.getServer(lobby.velocityName());
        if (target.isPresent()) {
            event.setInitialServer(target.get());
            return;
        }

        // Éteint (ou rangé) : on le démarre au nom de son propriétaire
        String status = lobby.status();
        if (!"STARTING".equals(status) && !"CREATING".equals(status) && !"MIGRATING".equals(status)) {
            wake(lobby);
        }
        player.disconnect(msg("IMG » Le lobby IMG démarre.\nReconnecte-toi dans une minute."));
    }

    private void wake(Lobby lobby) {
        Instant now = Instant.now();
        if (lastWake.plus(WAKE_COOLDOWN).isAfter(now)) {
            return; // déjà demandé : on ne relance pas à chaque tentative de connexion
        }
        lastWake = now;
        logger.info("Lobby IMG {} éteint : démarrage demandé", lobby.velocityName());
        api.serverAction(lobby.id(), "start", lobby.ownerId()).whenComplete((r, e) -> {
            if (e != null) {
                logger.warn("Démarrage du lobby IMG impossible : {}", e.getMessage());
                lastWake = Instant.EPOCH; // nouvel essai à la prochaine connexion
            }
        });
    }

    /**
     * Expulsé d'un serveur : un joueur IMG revient au lobby IMG, jamais au lobby MCS
     * (le « try » de Velocity). Expulsé du lobby IMG lui-même : déconnecté.
     */
    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        if (!isImgPlayer(player)) {
            return;
        }
        boolean onAServer = player.getCurrentServer().isPresent();
        if (event.kickedDuringServerConnect() && onAServer) {
            return; // connexion ratée vers un autre serveur : il reste où il est (comportement normal)
        }
        String from = event.getServer().getServerInfo().getName();
        Component reason = event.getServerKickReason().orElse(Component.text("Serveur arrêté."));
        Optional<RegisteredServer> lobby = lobbyServer();
        if (lobby.isEmpty() || lobby.get().getServerInfo().getName().equals(from)) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    Component.text("IMG » ").color(NamedTextColor.GOLD).append(reason)));
            return;
        }
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby.get(),
                Component.text("IMG » Retour au lobby IMG : ").color(NamedTextColor.GOLD).append(reason)));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        imgPlayers.remove(event.getPlayer().getUniqueId());
    }
}
