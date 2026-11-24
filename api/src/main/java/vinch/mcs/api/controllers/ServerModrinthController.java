package vinch.mcs.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.services.ServerModrinthService;

import java.util.List;
import java.util.Map;

/** Lot 70 : plugins/mods Modrinth d'un serveur, appliqués au prochain démarrage (site IMG, par le proxy) */
@RestController
@RequestMapping("/api/v1/servers/{id}/modrinth")
@RequiredArgsConstructor
public class ServerModrinthController {

    private final ServerModrinthService service;

    public record Body(Long playerId, List<String> projects) {}

    @GetMapping
    public ResponseEntity<?> get(@PathVariable Long id, @RequestParam Long playerId) {
        try {
            return ResponseEntity.ok(service.get(id, playerId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PutMapping
    public ResponseEntity<?> set(@PathVariable Long id, @RequestBody Body body) {
        try {
            return ResponseEntity.ok(service.set(id, body.playerId(), body.projects()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
