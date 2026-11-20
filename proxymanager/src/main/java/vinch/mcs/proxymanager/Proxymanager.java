package vinch.mcs.proxymanager;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Plugin(
        id = "proxymanager",
        name = "ProxyManager",
        version = "1.0.0",
        authors = {"MCS"}
)
public class Proxymanager {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    // Canal privé du lobby pour envoyer un joueur vers un serveur. Il remplace le
    // canal "BungeeCord", désactivé : celui-ci permettait à n'importe quel serveur
    // joueur de lire l'IP de tous les joueurs (IPOther), de les expulser
    // (KickPlayer) ou de les déplacer (ConnectOther).
    public static final MinecraftChannelIdentifier MCS_CONNECT = MinecraftChannelIdentifier.create("mcs", "connect");
    private static final String LOBBY_SERVER = "lobby";
    private static final String PLAYER_INFO_CHANNEL = "velocity:player_info";
    private volatile IpMasker ipMasker;

    private PluginConfig config;
    private ApiClient apiClient;
    private ApiServer apiServer;
    private PlayerSync playerSync;
    private ImgGate imgGate;
    private FarmdbMembers farmdbMembers;
    private ImgRole imgRole;

    // Faux tant que le masquage des IP (s'il est demandé) n'est pas en place :
    // aucun serveur joueur n'est alors enregistré (fail-closed)
    private volatile boolean playerServersAllowed = false;

    @Inject
    public Proxymanager(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Forwarding modern d'un serveur (Fabric, Forge récents) : Velocity étant en mode
     * "none", c'est ici qu'on répond à sa demande d'infos du joueur (lot 36).
     */
    @Subscribe
    public void onServerLoginPluginMessage(ServerLoginPluginMessageEvent event) {
        if (ipMasker == null || !PLAYER_INFO_CHANNEL.equals(event.getIdentifier().getId())) {
            return;
        }
        try {
            byte[] data = ipMasker.modernForwardingData(event.getConnection(), event.getContents());
            if (data != null) {
                event.setResult(ServerLoginPluginMessageEvent.ResponseResult.reply(data));
            }
        } catch (Exception e) {
            logger.error("Forwarding modern vers {} impossible", event.getConnection().getServerInfo().getName(), e);
        }
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("ProxyManager démarrage...");

        try {
            this.config = PluginConfig.load(dataDirectory);
            this.apiClient = new ApiClient(config, logger);

            if ("CHANGE_ME".equals(config.getApiKey())) {
                logger.error("ATTENTION : api-key par défaut détectée, modifie le fichier config.yml du plugin !");
            }

            if ("CHANGE_ME_PLUGIN".equals(config.getLocalApiKey())) {
                logger.error("ATTENTION : local-api-key par défaut détectée, modifie le fichier config.yml du plugin !");
            }

            logger.info("ProxyManager configuré avec API : {}", config.getApiUrl());
        } catch (Exception e) {
            logger.error("Erreur au chargement de la config : ", e);
            return;
        }

        // Masquage des IP et forwarding serveur par serveur (lot 36), avant tout
        // enregistrement de serveur joueur
        if (config.isMaskPlayerIps()
                && (config.getIpMaskKey() == null || config.getIpMaskKey().length() < 16)) {
            logger.error("ip-mask-key absente ou trop courte : serveurs joueurs désactivés (relance le déploiement)");
        } else {
            if (!config.isMaskPlayerIps()) {
                logger.warn("ATTENTION : mask-player-ips=false, l'IP réelle des joueurs est envoyée aux serveurs");
            }
            ipMasker = new IpMasker(logger, config.isMaskPlayerIps() ? config.getIpMaskKey() : null);
            playerServersAllowed = ipMasker.install(server);
            if (!playerServersAllowed) {
                logger.error("Masquage des IP / forwarding impossible : les serveurs joueurs ne seront PAS enregistrés");
            }
        }

        server.getChannelRegistrar().register(MCS_CONNECT);

        // Démarrer l'API locale qui expose Velocity à l'API Spring
        try {
            this.apiServer = new ApiServer(server, logger, config, () -> playerServersAllowed);
            apiServer.start();
        } catch (Exception e) {
            logger.error("Erreur démarrage API locale : ", e);
        }

        playerSync = new PlayerSync(server, apiClient, logger);

        // Entrée IMG (lot 40) : adresse dédiée -> lobby IMG
        imgGate = new ImgGate(server, apiClient, logger, config.getImgHost(), config.getImgLobby(),
                config.getImgIcon(), config.getImgMotd(), dataDirectory);
        if (imgGate.enabled()) {
            server.getEventManager().register(this, imgGate);
            logger.info("Entrée IMG active : {}", imgGate.describe());
            // Icône de la liste des serveurs : tout de suite, puis toutes les 10 min
            // (suit le logo du site farmdb s'il change)
            server.getScheduler().buildTask(this, imgGate::reloadIcon)
                    .repeat(java.time.Duration.ofMinutes(10)).schedule();
        } else if (!config.getImgHost().isBlank() || !config.getImgLobby().isBlank()) {
            logger.warn("Entrée IMG désactivée : img-host et img-lobby (pseudo/nom) sont tous deux requis");
        }
        registerCommands();

        logger.info("ProxyManager démarré avec succès");

        loadServersFromApi();
    }

    private void loadServersFromApi() {
        if (!playerServersAllowed) {
            logger.error("Serveurs joueurs non restaurés : masquage des IP inactif");
            return;
        }
        logger.info("Chargement des serveurs depuis l'API...");

        apiClient.getActiveServers().whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Erreur de chargement des serveurs au démarrage : {}", error.getMessage());
                return;
            }

            if (!result.has("servers")) {
                logger.info("Aucun serveur actif à charger.");
                return;
            }

            int count = 0;
            for (JsonNode serverNode : result.get("servers")) {
                String velocityName = serverNode.get("velocityName").asText();
                String host = serverNode.get("host").asText();
                int port = serverNode.get("port").asInt();
                IpMasker.setMode(velocityName, serverNode.path("forwarding").asText("legacy"));

                try {
                    InetSocketAddress address = new InetSocketAddress(host, port);
                    ServerInfo serverInfo = new ServerInfo(velocityName, address);

                    // Si le serveur est déjà enregistré, on le retire d'abord
                    server.getServer(velocityName).ifPresent(existing ->
                            server.unregisterServer(existing.getServerInfo())
                    );

                    server.registerServer(serverInfo);
                    logger.info("Serveur restauré : {} -> {}:{}", velocityName, host, port);
                    count++;
                } catch (Exception e) {
                    logger.error("Erreur d'enregistrement de {} : {}", velocityName, e.getMessage());
                }
            }

            logger.info("{} serveur(s) restauré(s) depuis l'API", count);
        });
    }

    /**
     * Canal mcs:connect : seul le lobby peut demander d'envoyer SON joueur vers un
     * serveur. Le message n'est jamais transmis au client.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!MCS_CONNECT.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection connection)) {
            return; // envoyé par un client : ignoré
        }
        if (!LOBBY_SERVER.equals(connection.getServerInfo().getName())) {
            logger.warn("mcs:connect refusé depuis le serveur {}", connection.getServerInfo().getName());
            return;
        }

        String target = new String(event.getData(), StandardCharsets.UTF_8).trim();
        Player player = connection.getPlayer();
        Optional<RegisteredServer> destination = server.getServer(target);
        if (destination.isEmpty()) {
            player.sendMessage(Component.text("Serveur introuvable : " + target).color(NamedTextColor.RED));
            return;
        }
        player.createConnectionRequest(destination.get()).fireAndForget();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (apiServer != null) {
            apiServer.stop();
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();

        apiClient.playerLogin(player.getUniqueId(), player.getUsername())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        logger.error("Erreur lors du login API pour {} : {}", player.getUsername(), error.getMessage());
                        return;
                    }

                    logger.debug("Joueur enregistré dans l'API : {} (id={})",
                            player.getUsername(), response.get("id").asLong());
                    // Quotas, rôle admin et rang réseau (LuckPerms du proxy) ; lot 51 : le rôle
                    // IMG suit d'abord le site (membre lié et non bloqué)
                    (imgRole == null ? CompletableFuture.completedFuture((Void) null)
                            : imgRole.sync(player.getUniqueId(), player.getUsername()))
                            .thenCompose(v -> playerSync.sync(player));
                    // Messages MCS reçus pendant son absence (serveur déplacé...)
                    apiClient.getNotifications(response.get("id").asLong()).thenAccept(r -> {
                        JsonNode list = r.path("notifications");
                        if (list.size() > 0) {
                            server.getScheduler().buildTask(this, () -> {
                                for (JsonNode n : list) {
                                    McsCommand.notifyPlayer(player, n.path("message").asText(""));
                                }
                            }).delay(java.time.Duration.ofSeconds(3)).schedule();
                        }
                    });
                });
    }

    private void registerCommands() {
        CommandManager commandManager = server.getCommandManager();

        commandManager.register(
                commandManager.metaBuilder("hub").aliases("lobby").build(),
                new HubCommand()
        );

        commandManager.register(
                commandManager.metaBuilder("servers").build(),
                new ServersCommand()
        );

        commandManager.register(
                commandManager.metaBuilder("goto").build(),
                new GotoCommand()
        );

        // /link CODE : liaison du compte Minecraft au site IMG Farm DB (lot 43)
        if (!config.getFarmdbUrl().isBlank() && config.getFarmdbToken().length() >= 32) {
            commandManager.register(commandManager.metaBuilder("link").build(),
                    new LinkCommand(logger, config.getFarmdbUrl(), config.getFarmdbToken()));
            logger.info("/link actif vers {}", config.getFarmdbUrl());
            // Sessions de farm du plugin img-farm (lot 44) : canal img:farm -> site
            server.getChannelRegistrar().register(FarmRelay.CHANNEL);
            server.getEventManager().register(this, new FarmRelay(logger, config.getFarmdbUrl(), config.getFarmdbToken()));
            logger.info("Relais img:farm actif (sessions de farm)");
            // Lot 48 : les membres liés du site entrent dans le lobby IMG sans invitation
            farmdbMembers = new FarmdbMembers(logger, config.getFarmdbUrl(), config.getFarmdbToken());
            if (imgGate != null && imgGate.enabled()) {
                imgGate.setMembers(farmdbMembers);
                logger.info("Lobby IMG ouvert aux membres liés du site IMG Farm DB");
            }
            // Lot 51 : rôle IMG (groupe LuckPerms) pour les membres du site
            if (playerSync.source() != null) {
                imgRole = new ImgRole(playerSync.source(), farmdbMembers, logger);
                imgRole.ensureGroup();
                logger.info("Rôle IMG (groupe LuckPerms {}) donné aux membres liés du site", ImgRole.GROUP);
            }
            // Lot 50 : réglages MCS des membres (serveurs max, admin) depuis le site
            if (apiServer != null) {
                apiServer.addContext("/img/",
                        new ImgMembersHandler(server, apiClient, playerSync, logger, config.getFarmdbToken()));
                logger.info("Réglages des membres et du rôle IMG ouverts au site IMG Farm DB (127.0.0.1:{}/img/)", config.getLocalApiPort());
            }
        } else if (!config.getFarmdbUrl().isBlank()) {
            logger.warn("/link désactivé : farmdb-token absent ou trop court");
        }

        // /mcs sur le proxy : disponible sur le lobby et sur tous les serveurs de jeu
        commandManager.register(
                commandManager.metaBuilder("mcs").build(),
                mcsCommand()
        );
    }

    private class HubCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("Cette commande est réservée aux joueurs.").color(NamedTextColor.RED));
                return;
            }

            // Entré par l'adresse IMG : son lobby est celui d'IMG
            if (imgGate != null && imgGate.isImgPlayer(player)) {
                Optional<RegisteredServer> img = imgGate.lobbyServer();
                if (img.isEmpty()) {
                    player.sendMessage(Component.text("Le lobby IMG est éteint pour le moment.").color(NamedTextColor.RED));
                    return;
                }
                player.createConnectionRequest(img.get()).fireAndForget();
                player.sendMessage(Component.text("Téléportation vers le lobby IMG...").color(NamedTextColor.GREEN));
                return;
            }

            Optional<RegisteredServer> lobby = server.getServer("lobby");
            if (lobby.isEmpty()) {
                player.sendMessage(Component.text("Le lobby est introuvable.").color(NamedTextColor.RED));
                return;
            }

            player.createConnectionRequest(lobby.get()).fireAndForget();
            player.sendMessage(Component.text("Téléportation vers le lobby...").color(NamedTextColor.GREEN));
        }
    }

    private class ServersCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();

            source.sendMessage(Component.text("Serveurs disponibles :").color(NamedTextColor.YELLOW));
            for (RegisteredServer rs : server.getAllServers()) {
                int playerCount = rs.getPlayersConnected().size();
                source.sendMessage(
                        Component.text("- " + rs.getServerInfo().getName() + " (" + playerCount + " joueurs)")
                                .color(NamedTextColor.AQUA)
                );
            }
        }
    }

    private class GotoCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("Cette commande est réservée aux joueurs.").color(NamedTextColor.RED));
                return;
            }

            if (args.length != 1) {
                player.sendMessage(Component.text("Usage : /goto <serveur>").color(NamedTextColor.RED));
                return;
            }

            String serverName = args[0];
            Optional<RegisteredServer> target = server.getServer(serverName);

            if (target.isEmpty()) {
                player.sendMessage(Component.text("Serveur introuvable : " + serverName).color(NamedTextColor.RED));
                return;
            }

            player.createConnectionRequest(target.get()).fireAndForget();
            player.sendMessage(Component.text("Connexion à " + serverName + "...").color(NamedTextColor.GREEN));
        }
    }

    /**
     * Contrôle d'accès : avant toute connexion à un serveur joueur (/mcs join,
     * /goto, /server, move...), l'API dit si ce joueur y a droit. En cas de doute
     * (API muette), l'accès est refusé. Le lobby reste toujours ouvert.
     * Les handlers Velocity sont asynchrones par défaut : l'attente (3 s au plus)
     * ne bloque pas le réseau.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            return;
        }
        String name = target.get().getServerInfo().getName();
        if (LOBBY_SERVER.equals(name)) {
            return;
        }
        Player player = event.getPlayer();
        String reason;
        try {
            JsonNode answer = apiClient.checkAccess(name, player.getUniqueId()).get(4, java.util.concurrent.TimeUnit.SECONDS);
            if (answer.path("allowed").asBoolean(false)) {
                return;
            }
            reason = answer.path("reason").asText("Accès refusé.");
            if (imgMember(name, player)) {
                return;
            }
        } catch (Exception e) {
            logger.warn("Contrôle d'accès de {} vers {} impossible : {}", player.getUsername(), name, e.getMessage());
            reason = "Vérification d'accès impossible pour le moment, réessaie.";
            if (imgMember(name, player)) {
                return;
            }
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(Component.text("MCS » ✖ " + reason).color(NamedTextColor.RED));
    }

    /** /mcs ; lot 49 : créé par un joueur entré par l'adresse IMG = serveur de la team */
    private McsCommand mcsCommand() {
        McsCommand cmd = new McsCommand(server, apiClient, playerSync, logger);
        if (imgGate != null && imgGate.enabled()) {
            cmd.setTeamCreator(imgGate::isImgPlayer);
        }
        return cmd;
    }

    /** Lot 48 : le lobby IMG est ouvert aux membres liés du site, en plus des invités */
    private boolean imgMember(String velocityName, Player player) {
        return farmdbMembers != null && imgGate != null && imgGate.enabled()
                && imgGate.isLobby(velocityName) && farmdbMembers.isMember(player.getUniqueId());
    }

    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(current -> {
            String serverName = current.getServerInfo().getName();
            if (!LOBBY_SERVER.equals(serverName)) {
                apiClient.notifyConnected(serverName, player.getUniqueId());
            }
        });
    }
}