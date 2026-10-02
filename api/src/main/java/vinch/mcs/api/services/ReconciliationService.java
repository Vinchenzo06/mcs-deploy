package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentInventoryEvent;
import vinch.mcs.api.websocket.AgentServerEvent;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aligne la base de données sur la réalité d'une machine, à partir de
 * l'inventaire de ses conteneurs :
 *  - serveur en base mais conteneur absent        -> statut ERROR ;
 *  - conteneur démarré / arrêté hors de l'API       -> statut corrigé, Velocity mis à jour ;
 *  - conteneur inconnu de la base (orphelin)        -> mis en quarantaine par l'agent
 *    (arrêté, renommé mcs-orphan-*, données CONSERVÉES : jamais de suppression
 *    automatique, au cas où la base de données serait celle qui a un problème).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    // États transitoires : une opération est peut-être en cours
    private static final Set<ServerStatus> TRANSITIONAL =
            EnumSet.of(ServerStatus.CREATING, ServerStatus.STARTING, ServerStatus.STOPPING);

    private final ServerRepository serverRepository;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final VelocityClient velocityClient;
    private final ServerMetricsService metricsService;
    private final vinch.mcs.api.repositories.PendingDeletionRepository pendingDeletionRepository;

    /**
     * Événements en direct. Les opérations lancées par l'API (création, arrêt)
     * gardent la main sur le statut : seuls les changements survenus hors de son
     * contrôle (plantage, redémarrage automatique, action manuelle) sont appliqués.
     */
    @EventListener
    @Transactional
    public void onServerEvent(AgentServerEvent event) {
        Server server = serverRepository.findById(event.serverId()).orElse(null);
        if (server == null) {
            return; // orphelin : l'inventaire s'en occupe
        }
        if (server.getNode() == null || !server.getNode().getId().equals(event.nodeId())) {
            log.warn("Événement ignoré : le serveur {} n'est pas sur la machine {}", event.serverId(), event.nodeId());
            return;
        }
        metricsService.recordHealth(server.getId(), event.event());

        ServerStatus status = server.getStatus();
        if (status == ServerStatus.CREATING || status == ServerStatus.STOPPING || status == ServerStatus.MIGRATING) {
            return;
        }

        switch (event.event()) {
            case "start" -> {
                // Redémarrage hors de l'API (automatique après plantage, ou manuel)
                if (status == ServerStatus.STOPPED || status == ServerStatus.ERROR) {
                    server.setStatus(ServerStatus.STARTING);
                    serverRepository.save(server);
                }
            }
            case "healthy" -> {
                // Minecraft répond : le serveur est réellement joignable
                if (status != ServerStatus.RUNNING) {
                    log.info("Serveur {} ({}) prêt -> RUNNING", server.getId(), server.getVelocityName());
                    server.setStatus(ServerStatus.RUNNING);
                    server.setLastStartedAt(LocalDateTime.now());
                    serverRepository.save(server);
                    register(server);
                }
            }
            case "die" -> {
                if (status == ServerStatus.RUNNING || status == ServerStatus.STARTING) {
                    log.warn("Serveur {} ({}) arrêté hors de l'API (code {}) -> STOPPED",
                            server.getId(), server.getVelocityName(), event.exitCode());
                    server.setStatus(ServerStatus.STOPPED);
                    server.setLastStoppedAt(LocalDateTime.now());
                    serverRepository.save(server);
                    unregister(server);
                }
            }
            case "unhealthy" -> log.warn("Serveur {} ({}) ne répond plus (healthcheck)",
                    server.getId(), server.getVelocityName());
            default -> { }
        }
    }

    @EventListener
    @Transactional
    public void onInventory(AgentInventoryEvent event) {
        Long nodeId = event.nodeId();
        Map<Long, String> containers = event.containers();
        List<Server> servers = serverRepository.findByNodeId(nodeId);
        Set<Long> known = new HashSet<>();

        for (Server server : servers) {
            known.add(server.getId());
            ServerStatus status = server.getStatus();

            // La création est toujours laissée tranquille (elle peut durer plusieurs minutes).
            // Les démarrages/arrêts ne sont corrigés qu'à la connexion de l'agent : une
            // opération en cours a forcément été interrompue par la déconnexion.
            if (status == ServerStatus.CREATING || status == ServerStatus.MIGRATING
                    || (!event.fullSync() && TRANSITIONAL.contains(status))) {
                continue;
            }

            String state = containers.get(server.getId());
            if (state == null && "COLD".equals(server.getParkState())) {
                continue; // rangé au central, plus de copie sur la machine : normal
            }
            if (state == null) {
                if (status != ServerStatus.ERROR) {
                    log.warn("Serveur {} ({}) : conteneur introuvable sur la machine {} -> ERROR",
                            server.getId(), server.getVelocityName(), nodeId);
                    server.setStatus(ServerStatus.ERROR);
                    unregister(server);
                }
            } else if ("running".equals(state)) {
                if (status != ServerStatus.RUNNING) {
                    log.info("Serveur {} ({}) en marche sur la machine {} -> RUNNING",
                            server.getId(), server.getVelocityName(), nodeId);
                    server.setStatus(ServerStatus.RUNNING);
                    server.setLastStartedAt(LocalDateTime.now());
                    register(server);
                }
            } else if (status != ServerStatus.STOPPED) {
                log.info("Serveur {} ({}) arrêté sur la machine {} (état Docker : {}) -> STOPPED",
                        server.getId(), server.getVelocityName(), nodeId, state);
                server.setStatus(ServerStatus.STOPPED);
                server.setLastStoppedAt(LocalDateTime.now());
                unregister(server);
            }
        }
        serverRepository.saveAll(servers);

        for (Long orphanId : containers.keySet()) {
            if (known.contains(orphanId)) {
                continue;
            }
            if (pendingDeletionRepository.existsByNodeIdAndServerId(nodeId, orphanId)) {
                // Serveur déplacé pendant que cette machine était hors ligne : il tourne
                // ailleurs, on supprime l'ancienne copie (données et sauvegardes locales)
                log.info("Serveur {} déplacé ailleurs : suppression de l'ancienne copie sur la machine {}", orphanId, nodeId);
                agentWebSocketHandler.sendCommand(nodeId, "delete_server",
                                Map.<String, Object>of("server_id", orphanId, "delete_data", true))
                        .whenComplete((result, error) -> {
                            boolean ok = error == null && result != null
                                    && !(result.has("success") && !result.get("success").asBoolean());
                            if (ok) {
                                pendingDeletionRepository.findByNodeId(nodeId).stream()
                                        .filter(p -> p.getServerId().equals(orphanId))
                                        .forEach(pendingDeletionRepository::delete);
                            } else {
                                log.warn("Suppression de l'ancienne copie de {} impossible : {}", orphanId,
                                        error != null ? error.getMessage() : result);
                            }
                        });
                continue;
            }
            log.warn("Conteneur orphelin mcs-server-{} sur la machine {} : mise en quarantaine", orphanId, nodeId);
            try {
                agentWebSocketHandler.sendCommand(nodeId, "quarantine_server",
                                Map.<String, Object>of("server_id", orphanId))
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                log.warn("Quarantaine de mcs-server-{} impossible : {}", orphanId, error.getMessage());
                            }
                        });
            } catch (Exception e) {
                log.warn("Quarantaine de mcs-server-{} impossible : {}", orphanId, e.getMessage());
            }
        }
    }

    private void register(Server server) {
        try {
            velocityClient.registerServer(server.getVelocityName(), "127.0.0.1", server.getTunnelPort());
        } catch (Exception e) {
            // Déjà enregistré (409) ou Velocity indisponible : il se resynchronise à son démarrage
            log.debug("Enregistrement Velocity de {} : {}", server.getVelocityName(), e.getMessage());
        }
    }

    private void unregister(Server server) {
        try {
            velocityClient.unregisterServer(server.getVelocityName());
        } catch (Exception e) {
            log.debug("Désenregistrement Velocity de {} : {}", server.getVelocityName(), e.getMessage());
        }
    }
}
