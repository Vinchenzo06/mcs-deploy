package vinch.mcs.api.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.dto.CreateServerRequest;
import vinch.mcs.api.dto.CreateServerResponse;
import vinch.mcs.api.services.ServerService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/servers")
@RequiredArgsConstructor
@Slf4j
public class ServerController {

    private final ServerService serverService;

    @PostMapping
    public ResponseEntity<?> createServer(@Valid @RequestBody CreateServerRequest request) {
        try {
            CreateServerResponse response = serverService.createServer(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erreur création : {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{serverId}")
    public ResponseEntity<?> deleteServer(@PathVariable Long serverId,
                                          @RequestParam(defaultValue = "false") boolean deleteData) {
        try {
            serverService.deleteServer(serverId, deleteData);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Erreur suppression : {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/by-name/{name}")
    public ResponseEntity<?> deleteServerByName(@PathVariable String name,
                                                @RequestParam(defaultValue = "true") boolean deleteData,
                                                @RequestParam Long requestedByPlayerId) {
        try {
            serverService.deleteServerByName(name, deleteData, requestedByPlayerId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Erreur suppression : {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/by-owner-name")
    public ResponseEntity<?> getServerByOwnerAndName(@RequestParam Long ownerId,
                                                     @RequestParam String name) {
        try {
            return ResponseEntity.ok(serverService.getServerByOwnerAndName(ownerId, name));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/by-username-name")
    public ResponseEntity<?> getServerByUsernameAndName(@RequestParam String username,
                                                        @RequestParam String name) {
        try {
            return ResponseEntity.ok(serverService.getServerByUsernameAndName(username, name));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActiveServers() {
        return ResponseEntity.ok(Map.of("servers", serverService.getActiveServers()));
    }

    @PostMapping("/by-name/{name}/start")
    public ResponseEntity<?> startServerByName(@PathVariable String name,
                                               @RequestParam Long requestedByPlayerId) {
        try {
            serverService.startServerByName(name, requestedByPlayerId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Erreur démarrage : {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/by-name/{name}/stop")
    public ResponseEntity<?> stopServerByName(@PathVariable String name,
                                              @RequestParam Long requestedByPlayerId) {
        try {
            serverService.stopServerByName(name, requestedByPlayerId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Erreur arrêt : {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/by-name/{name}/restart")
    public ResponseEntity<?> restartServerByName(@PathVariable String name,
                                                 @RequestParam Long requestedByPlayerId) {
        try {
            serverService.restartServerByName(name, requestedByPlayerId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Erreur redémarrage : {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}