package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Dans la liste des serveurs du jeu, l'adresse IMG a sa propre icône (img-icon :
 * fichier ou URL, ex. le logo du site farmdb, rechargée toutes les 10 min), son
 * propre message (img-motd) et ne compte que les joueurs entrés par l'adresse IMG.
 *
 * Désactivé tant que img-host ou img-lobby est vide.
 */
public class ImgGate {

    private static final Duration WAKE_COOLDOWN = Duration.ofMinutes(2);
    private static final int MAX_ICON_BYTES = 8 * 1024 * 1024;

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

    // Liste des serveurs du jeu
    private final String iconSource;
    private final Path dataDirectory;
    private final Component motd;
    private volatile Favicon favicon;
    private volatile String iconProblem;

    record Lobby(long id, long ownerId, String velocityName, String status) {}

    public ImgGate(ProxyServer server, ApiClient api, Logger logger, String host, String lobbyRef,
                   String iconSource, String motd, Path dataDirectory) {
        this.server = server;
        this.api = api;
        this.logger = logger;
        this.host = normalize(host);
        String ref = lobbyRef == null ? "" : lobbyRef.trim();
        int slash = ref.indexOf('/');
        this.ownerName = slash > 0 ? ref.substring(0, slash).trim() : "";
        this.lobbyName = slash > 0 ? ref.substring(slash + 1).trim() : "";
        this.iconSource = iconSource == null ? "" : iconSource.trim();
        this.dataDirectory = dataDirectory;
        // Codes couleur « & » (comme les préfixes LuckPerms), \n pour la 2e ligne
        this.motd = motd == null || motd.isBlank() ? null
                : LegacyComponentSerializer.legacyAmpersand().deserialize(motd.replace("\\n", "\n"));
    }

    public boolean enabled() {
        return !host.isEmpty() && !ownerName.isEmpty() && !lobbyName.isEmpty();
    }

    public String describe() {
        return host + " -> " + ownerName + "/" + lobbyName
                + (iconSource.isEmpty() ? "" : ", icône " + iconSource)
                + (motd == null ? "" : ", message perso");
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

    /** Membres du site (lot 48) : null = seules les invitations comptent */
    private volatile FarmdbMembers members;

    public void setMembers(FarmdbMembers members) {
        this.members = members;
    }

    /** Ce serveur Velocity est-il le lobby IMG ? */
    public boolean isLobby(String velocityName) {
        String name = lobbyVelocityName;
        if (name == null) {
            fetchLobby();
            name = lobbyVelocityName;
        }
        return name != null && name.equals(velocityName);
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

    /**
     * Écran de refus (lot 51) : un code à entrer sur le site pour lier son compte, sans
     * passer par un autre serveur. Compte lié mais bloqué : on le dit.
     */
    private Component refusal(Player player) {
        JsonNode jc = members == null ? null : members.joinCode(player.getUniqueId(), player.getUsername());
        if (jc != null && jc.path("linked").asBoolean(false) && !jc.path("member").asBoolean(true)) {
            return msg("IMG » Ton compte du site de la team est bloqué.\nParles-en à un admin de la team.");
        }
        String code = jc == null ? "" : jc.path("code").asText("");
        if (code.isEmpty()) {
            return msg("IMG » Cette adresse est réservée aux membres de la team IMG.\n"
                    + "Demande une invitation, puis reconnecte-toi.");
        }
        return Component.text("IMG » Réservé aux membres de la team IMG.\n\n").color(NamedTextColor.GOLD)
                .append(Component.text("Membre ? Va sur ").color(NamedTextColor.GRAY))
                .append(Component.text(host).color(NamedTextColor.WHITE))
                .append(Component.text(" (Mon profil) et entre ce code :\n\n").color(NamedTextColor.GRAY))
                .append(Component.text(code).color(NamedTextColor.GREEN))
                .append(Component.text("\n\nValable 15 minutes. Ensuite, reconnecte-toi.").color(NamedTextColor.GRAY));
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

        // Accès : invité sur le lobby IMG (MCS) ou membre lié du site IMG Farm DB (lot 48)
        boolean allowed = false;
        boolean checked = false;
        try {
            JsonNode answer = api.checkAccess(lobby.velocityName(), player.getUniqueId()).get(4, TimeUnit.SECONDS);
            allowed = answer.path("allowed").asBoolean(false);
            checked = true;
        } catch (Exception e) {
            logger.warn("Accès de {} au lobby IMG impossible à vérifier : {}", player.getUsername(), e.getMessage());
        }
        if (!allowed && members != null && members.isMember(player.getUniqueId())) {
            allowed = true;
            checked = true;
        }
        if (!checked) {
            player.disconnect(msg("IMG » Vérification d'accès impossible pour le moment, réessaie."));
            return;
        }
        if (!allowed) {
            logger.info("{} refusé à l'entrée IMG (ni invité sur {}, ni membre lié du site)", player.getUsername(), lobby.velocityName());
            player.disconnect(refusal(player));
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

    // ------------------------------------------------------------ liste des serveurs ----

    /** Ping de la liste des serveurs par l'adresse IMG : icône, message et compte IMG */
    @Subscribe
    public void onPing(ProxyPingEvent event) {
        if (!enabled()) {
            return;
        }
        boolean img = event.getConnection().getVirtualHost()
                .map(InetSocketAddress::getHostString)
                .map(ImgGate::normalize)
                .filter(host::equals)
                .isPresent();
        if (!img) {
            return;
        }
        ServerPing.Builder b = event.getPing().asBuilder()
                .onlinePlayers(imgPlayers.size())
                .clearSamplePlayers(); // jamais les pseudos des joueurs MCS
        Favicon icon = favicon;
        if (icon != null) {
            b.favicon(icon);
        }
        if (motd != null) {
            b.description(motd);
        }
        event.setPing(b.build());
    }

    /** (Re)charge l'icône IMG ; appelé au démarrage puis toutes les 10 minutes */
    public void reloadIcon() {
        if (iconSource.isEmpty()) {
            return;
        }
        try {
            byte[] data = readIcon();
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) {
                problem("format d'image non lu (PNG, JPG ou GIF ; pas WEBP)");
                return;
            }
            boolean first = favicon == null;
            favicon = Favicon.create(toIcon(src));
            if (first || iconProblem != null) {
                logger.info("Icône IMG chargée depuis {} ({}x{} réduite en 64x64)", iconSource,
                        src.getWidth(), src.getHeight());
            }
            iconProblem = null;
        } catch (Exception e) {
            problem(e.getMessage());
        }
    }

    private void problem(String why) {
        if (!why.equals(iconProblem)) { // une seule fois par problème, pas toutes les 10 min
            logger.warn("Icône IMG ({}) : {}{}", iconSource, why,
                    favicon == null ? " ; icône de Velocity gardée" : " ; ancienne icône gardée");
        }
        iconProblem = why;
    }

    private byte[] readIcon() throws Exception {
        if (iconSource.startsWith("http://") || iconSource.startsWith("https://")) {
            HttpResponse<InputStream> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(HttpRequest.newBuilder(URI.create(iconSource)).timeout(Duration.ofSeconds(10)).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("réponse HTTP " + r.statusCode()
                            + (r.statusCode() == 404 ? " (aucun logo choisi sur le site ?)" : ""));
                }
                byte[] data = in.readNBytes(MAX_ICON_BYTES + 1);
                if (data.length > MAX_ICON_BYTES) {
                    throw new IllegalStateException("image de plus de 8 Mo");
                }
                return data;
            }
        }
        Path file = Path.of(iconSource);
        if (!file.isAbsolute() && dataDirectory != null) {
            file = dataDirectory.resolve(file);
        }
        if (Files.size(file) > MAX_ICON_BYTES) {
            throw new IllegalStateException("image de plus de 8 Mo");
        }
        return Files.readAllBytes(file);
    }

    /** N'importe quelle image -> 64×64 (exigé par Minecraft), proportions gardées, centrée */
    static BufferedImage toIcon(BufferedImage src) {
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.min(64.0 / src.getWidth(), 64.0 / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, (64 - w) / 2, (64 - h) / 2, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        imgPlayers.remove(event.getPlayer().getUniqueId());
    }
}
