package vinch.mcs.api.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.dto.PlayerLoginRequest;
import vinch.mcs.api.dto.PlayerResponse;
import vinch.mcs.api.dto.UpdatePlayerLimitsRequest;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.services.PlayerService;
import vinch.mcs.api.services.ServerService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/players")
@RequiredArgsConstructor
@Slf4j
public class PlayerController {

    private final PlayerService playerService;
    private final ServerService serverService;

    @PostMapping("/login")
    public ResponseEntity<PlayerResponse> loginPlayer(@Valid @RequestBody PlayerLoginRequest request) {
        log.debug("Login request: {} ({})", request.getUsername(), request.getUuid());

        Player player = playerService.loginOrCreatePlayer(request.getUuid(), request.getUsername());

        return ResponseEntity.ok(PlayerResponse.fromEntity(player));
    }

    @GetMapping("/by-uuid/{uuid}")
    public ResponseEntity<PlayerResponse> getPlayerByUuid(@PathVariable UUID uuid) {
        return playerService.findByUuid(uuid)
                .map(player -> ResponseEntity.ok(PlayerResponse.fromEntity(player)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/servers")
    public ResponseEntity<?> getPlayerServers(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(Map.of(
                    "servers", serverService.getServersByOwnerId(id),
                    "shared", serverService.getOtherServers(id).get("shared")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/limits")
    public ResponseEntity<?> updateLimits(@Valid @RequestBody UpdatePlayerLimitsRequest request) {
        try {
            Player p = playerService.updateLimits(
                    request.getUuid(),
                    request.getMaxServers(),
                    request.getTotalRamMb(),
                    request.getTotalCpuCores(),
                    request.getAdmin()
            );
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "maxServers", p.getMaxServers(),
                    "totalRamMb", p.getTotalRamMb(),
                    "totalCpuCores", p.getTotalCpuCores()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/quota")
    public ResponseEntity<?> getPlayerQuota(@PathVariable Long id) {
        try {
            Player player = playerService.getPlayerById(id);
            long ramUsed = serverService.getTotalRamUsedByOwnerId(id);
            long cpuUsed = serverService.getTotalCpuUsedByOwnerId(id);
            long serversUsed = serverService.countServersByOwnerId(id);

            return ResponseEntity.ok(Map.of(
                    "maxServers", player.getMaxServers(),
                    "serversUsed", serversUsed,
                    "totalRamMb", player.getTotalRamMb(),
                    "ramUsedMb", ramUsed,
                    "ramRemainingMb", player.getTotalRamMb() - ramUsed,
                    "totalCpuCores", player.getTotalCpuCores(),
                    "cpuUsedCores", cpuUsed,
                    "cpuRemainingCores", player.getTotalCpuCores() - cpuUsed
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}