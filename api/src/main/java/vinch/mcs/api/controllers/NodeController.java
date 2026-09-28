package vinch.mcs.api.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.dto.CreateNodeRequest;
import vinch.mcs.api.dto.CreateNodeResponse;
import vinch.mcs.api.services.NodeService;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final NodeService nodeService;
    private final AgentWebSocketHandler agentWebSocketHandler;

    @PostMapping
    public ResponseEntity<CreateNodeResponse> createNode(@Valid @RequestBody CreateNodeRequest request) {
        return ResponseEntity.ok(nodeService.createNode(request));
    }

    // Nouveau jeton pour une machine existante (re-jumelage) : l'ancien est refusé
    @PostMapping("/{nodeId}/token")
    public ResponseEntity<?> rotateToken(@PathVariable Long nodeId) {
        try {
            CreateNodeResponse response = nodeService.rotateToken(nodeId);
            agentWebSocketHandler.disconnectNode(nodeId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // Révoque une machine : son jeton est refusé et sa connexion coupée
    @PostMapping("/{nodeId}/revoke")
    public ResponseEntity<?> revokeNode(@PathVariable Long nodeId) {
        try {
            nodeService.revokeNode(nodeId);
            agentWebSocketHandler.disconnectNode(nodeId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}