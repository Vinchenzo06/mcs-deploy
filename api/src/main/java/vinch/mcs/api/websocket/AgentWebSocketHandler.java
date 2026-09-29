package vinch.mcs.api.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vinch.mcs.api.entities.Node;
import vinch.mcs.api.repositories.NodeRepository;
import vinch.mcs.api.services.NodeService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final NodeService nodeService;
    private final NodeRepository nodeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Map nodeId -> WebSocketSession pour pouvoir envoyer des messages plus tard
    private final Map<Long, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // Map sessionId -> nodeId pour identifier qui est connecté
    private final Map<String, Long> sessionToNode = new ConcurrentHashMap<>();

    // Map command_id -> CompletableFuture pour gérer les réponses asynchrones
    private final Map<String, CompletableFuture<JsonNode>> pendingCommands = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Nouvelle connexion WebSocket : {}", session.getId());
        // L'agent doit envoyer un message register dans les 5 secondes, sinon on le déconnecte
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.has("type") ? json.get("type").asText() : "";

            switch (type) {
                case "register" -> handleRegister(session, json);
                case "heartbeat" -> handleHeartbeat(session, json);
                case "command_result" -> handleCommandResult(json);
                case "inventory" -> handleInventory(session, json);
                case "server_event" -> handleServerEvent(session, json);
                case "server_stats" -> handleServerStats(session, json);
                default -> {
                    if (sessionToNode.containsKey(session.getId())) {
                        log.debug("Message reçu de node {} : type={}", sessionToNode.get(session.getId()), type);
                    } else {
                        log.warn("Message reçu d'une session non authentifiée : {}", type);
                        sendError(session, "not_authenticated");
                        session.close(CloseStatus.POLICY_VIOLATION);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erreur de traitement du message WebSocket", e);
            sendError(session, "invalid_message");
        }
    }

    // Inventaire des conteneurs de la machine : réconcilié par ReconciliationService
    private void handleInventory(WebSocketSession session, JsonNode json) throws Exception {
        Long nodeId = sessionToNode.get(session.getId());
        if (nodeId == null) {
            sendError(session, "not_authenticated");
            return;
        }
        Map<Long, String> containers = new HashMap<>();
        JsonNode list = json.get("containers");
        if (list != null && list.isArray()) {
            for (JsonNode c : list) {
                if (c.hasNonNull("server_id") && c.hasNonNull("state")) {
                    containers.put(c.get("server_id").asLong(), c.get("state").asText());
                }
            }
        }
        boolean fullSync = json.path("full_sync").asBoolean(false);
        log.debug("Inventaire de la machine {} : {} conteneur(s) (complet={})", nodeId, containers.size(), fullSync);
        eventPublisher.publishEvent(new AgentInventoryEvent(nodeId, containers, fullSync));
    }

    // Événement Docker en direct (démarrage, arrêt, santé) d'un serveur
    private void handleServerEvent(WebSocketSession session, JsonNode json) throws Exception {
        Long nodeId = sessionToNode.get(session.getId());
        if (nodeId == null) {
            sendError(session, "not_authenticated");
            return;
        }
        if (!json.hasNonNull("server_id") || !json.hasNonNull("event")) {
            return;
        }
        Integer exitCode = json.hasNonNull("exit_code") ? json.get("exit_code").asInt() : null;
        eventPublisher.publishEvent(new AgentServerEvent(
                nodeId, json.get("server_id").asLong(), json.get("event").asText(), exitCode));
    }

    // Mesures périodiques des serveurs de la machine
    private void handleServerStats(WebSocketSession session, JsonNode json) throws Exception {
        Long nodeId = sessionToNode.get(session.getId());
        if (nodeId == null) {
            sendError(session, "not_authenticated");
            return;
        }
        eventPublisher.publishEvent(new AgentStatsEvent(nodeId, json.get("servers")));
    }

    private void handleCommandResult(JsonNode json) {
        if (!json.has("command_id")) {
            log.warn("command_result sans command_id reçu");
            return;
        }

        String commandId = json.get("command_id").asText();
        CompletableFuture<JsonNode> future = pendingCommands.remove(commandId);

        if (future != null) {
            future.complete(json);
            log.debug("Résultat de commande {} reçu", commandId);
        } else {
            log.warn("Résultat reçu pour commande inconnue : {}", commandId);
        }
    }

    public CompletableFuture<JsonNode> sendCommand(Long nodeId, String commandType, Map<String, Object> data) {
        return sendCommand(nodeId, commandType, data, 300);
    }

    /** Comme sendCommand, avec un délai maximal choisi (sauvegardes : jusqu'à 2 h) */
    public CompletableFuture<JsonNode> sendCommand(Long nodeId, String commandType, Map<String, Object> data,
                                                   long timeoutSeconds) {
        WebSocketSession session = activeSessions.get(nodeId);
        if (session == null || !session.isOpen()) {
            CompletableFuture<JsonNode> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("Node " + nodeId + " non connecté"));
            return failed;
        }

        String commandId = UUID.randomUUID().toString();

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", commandType);
        message.put("command_id", commandId);
        if (data != null) {
            message.set("data", objectMapper.valueToTree(data));
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingCommands.put(commandId, future);

        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            log.debug("Commande {} ({}) envoyée à node {}", commandType, commandId, nodeId);
        } catch (Exception e) {
            pendingCommands.remove(commandId);
            future.completeExceptionally(e);
            return future;
        }

        // Timeout après 30 secondes
        future.orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    pendingCommands.remove(commandId);
                    return null;
                });

        return future;
    }

    private void handleRegister(WebSocketSession session, JsonNode json) throws Exception {
        if (!json.has("node_token")) {
            sendError(session, "missing_token");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String token = json.get("node_token").asText();
        Node node = nodeService.authenticateNode(token);

        if (node == null) {
            log.warn("Tentative d'authentification avec un token invalide");
            sendError(session, "invalid_token");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Si le node était déjà connecté avec une autre session, on ferme l'ancienne
        WebSocketSession previousSession = activeSessions.get(node.getId());
        if (previousSession != null && previousSession.isOpen()) {
            previousSession.close(CloseStatus.POLICY_VIOLATION.withReason("Replaced by new connection"));
        }

        activeSessions.put(node.getId(), session);
        sessionToNode.put(session.getId(), node.getId());

        // Mise à jour du node en base
        node.setIsOnline(true);
        node.setLastHeartbeatAt(LocalDateTime.now());
        if (json.has("agent_version")) {
            node.setAgentVersion(json.get("agent_version").asText());
        }
        // Capacité prêtée par le volontaire et ressources réelles de la machine
        JsonNode capacity = json.path("capacity");
        if (capacity.path("ram_mb").asLong(0) > 0) {
            node.setTotalRamMb((int) capacity.get("ram_mb").asLong());
            node.setCpuCores((int) capacity.path("cpu_cores").asLong(1));
            node.setTotalStorageMb((int) capacity.path("disk_mb").asLong(0));
        }
        JsonNode host = json.path("host");
        if (host.isObject()) {
            node.setHostRamMb((int) host.path("ram_total_mb").asLong(0));
            node.setHostCpuCores((int) host.path("cpu_cores").asLong(0));
            node.setHostDiskTotalMb((int) host.path("disk_total_mb").asLong(0));
            node.setHostDiskFreeMb((int) host.path("disk_free_mb").asLong(0));
        }
        nodeRepository.save(node);

        log.info("Node {} authentifié et connecté (volontaire {})", node.getId(), node.getVolunteer().getId());

        // Réponse au client
        ObjectNode response = objectMapper.createObjectNode();
        response.put("type", "register_ok");
        response.put("node_id", node.getId());
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    }

    private void handleHeartbeat(WebSocketSession session, JsonNode json) throws Exception {
        Long nodeId = sessionToNode.get(session.getId());
        if (nodeId == null) {
            sendError(session, "not_authenticated");
            return;
        }

        Node node = nodeRepository.findById(nodeId).orElse(null);
        if (node == null) return;

        node.setLastHeartbeatAt(LocalDateTime.now());

        // Mise à jour des stats si fournies
        if (json.has("stats")) {
            JsonNode stats = json.get("stats");
            if (stats.has("ram_used_mb")) node.setUsedRamMb(stats.get("ram_used_mb").asInt());
            if (stats.has("storage_used_mb")) node.setUsedStorageMb(stats.get("storage_used_mb").asInt());
            if (stats.has("disk_free_mb")) node.setHostDiskFreeMb(stats.get("disk_free_mb").asInt());
        }

        nodeRepository.save(node);

        log.debug("Heartbeat reçu de node {}", nodeId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long nodeId = sessionToNode.remove(session.getId());
        if (nodeId != null) {
            activeSessions.remove(nodeId);

            // Marquer le node comme offline
            nodeRepository.findById(nodeId).ifPresent(node -> {
                node.setIsOnline(false);
                nodeRepository.save(node);
            });

            log.info("Node {} déconnecté (raison : {})", nodeId, status);
        }
    }

    private void sendError(WebSocketSession session, String errorCode) throws Exception {
        if (!session.isOpen()) return;
        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "error");
        error.put("error", errorCode);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
    }

    public void disconnectNode(Long nodeId) {
        WebSocketSession session = activeSessions.remove(nodeId);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("Node revoked"));
            } catch (Exception e) {
                log.warn("Fermeture de la session du node {} : {}", nodeId, e.getMessage());
            }
        }
    }

    public boolean isNodeOnline(Long nodeId) {
        WebSocketSession session = activeSessions.get(nodeId);
        return session != null && session.isOpen();
    }

    public void sendToNode(Long nodeId, String message) throws Exception {
        WebSocketSession session = activeSessions.get(nodeId);
        if (session != null && session.isOpen()) {
            session.sendMessage(new TextMessage(message));
        } else {
            throw new RuntimeException("Node " + nodeId + " non connecté");
        }
    }
}