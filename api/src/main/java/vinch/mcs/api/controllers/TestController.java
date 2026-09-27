package vinch.mcs.api.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/admin/test")
@RequiredArgsConstructor
@Slf4j
public class TestController {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/ping/{nodeId}")
    public ResponseEntity<?> pingAgent(@PathVariable Long nodeId) {
        return executeCommand(nodeId, "ping", Map.of("timestamp", System.currentTimeMillis()));
    }

    @PostMapping("/create-server/{nodeId}")
    public ResponseEntity<?> createServer(@PathVariable Long nodeId, @RequestBody Map<String, Object> body) {
        return executeCommand(nodeId, "create_server", body);
    }

    @PostMapping("/start-server/{nodeId}/{serverId}")
    public ResponseEntity<?> startServer(@PathVariable Long nodeId, @PathVariable Long serverId) {
        return executeCommand(nodeId, "start_server", Map.of("server_id", serverId));
    }

    @PostMapping("/stop-server/{nodeId}/{serverId}")
    public ResponseEntity<?> stopServer(@PathVariable Long nodeId, @PathVariable Long serverId) {
        return executeCommand(nodeId, "stop_server", Map.of("server_id", serverId));
    }

    @DeleteMapping("/delete-server/{nodeId}/{serverId}")
    public ResponseEntity<?> deleteServer(@PathVariable Long nodeId, @PathVariable Long serverId,
                                          @RequestParam(defaultValue = "false") boolean deleteData) {
        return executeCommand(nodeId, "delete_server",
                Map.of("server_id", serverId, "delete_data", deleteData));
    }

    @GetMapping("/server-status/{nodeId}/{serverId}")
    public ResponseEntity<?> serverStatus(@PathVariable Long nodeId, @PathVariable Long serverId) {
        return executeCommand(nodeId, "server_status", Map.of("server_id", serverId));
    }

    @GetMapping("/list-servers/{nodeId}")
    public ResponseEntity<?> listServers(@PathVariable Long nodeId) {
        return executeCommand(nodeId, "list_servers", new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<?> executeCommand(Long nodeId, String commandType, Map<String, Object> data) {
        try {
            CompletableFuture<JsonNode> future = agentWebSocketHandler.sendCommand(nodeId, commandType, data);
            JsonNode result = future.get();
            Map<String, Object> resultMap = objectMapper.convertValue(result, Map.class);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "result", resultMap
            ));
        } catch (Exception e) {
            log.error("Erreur commande {} sur node {} : {}", commandType, nodeId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}