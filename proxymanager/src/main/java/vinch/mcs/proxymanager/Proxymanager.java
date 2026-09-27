package vinch.mcs.proxymanager;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Optional;

@Plugin(
        id = "proxymanager",
        name = "ProxyManager",
        version = "1.0.0",
        authors = {"Vincent"}
)
public class Proxymanager {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private ApiClient apiClient;
    private ApiServer apiServer;

    @Inject
    public Proxymanager(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
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

        // Démarrer l'API locale qui expose Velocity à l'API Spring
        try {
            this.apiServer = new ApiServer(server, logger, config);
            apiServer.start();
        } catch (Exception e) {
            logger.error("Erreur démarrage API locale : ", e);
        }

        registerCommands();

        logger.info("ProxyManager démarré avec succès");

        loadServersFromApi();
    }

    private void loadServersFromApi() {
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
    }

    private class HubCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("Cette commande est réservée aux joueurs.").color(NamedTextColor.RED));
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

    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> currentServer = player.getCurrentServer().map(s -> s.getServer());

        if (currentServer.isPresent()) {
            String serverName = currentServer.get().getServerInfo().getName();
            player.sendMessage(
                    Component.text("Tu es maintenant sur : " + serverName)
                            .color(NamedTextColor.GREEN)
            );
        }
    }
}