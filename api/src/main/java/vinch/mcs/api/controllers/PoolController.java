package vinch.mcs.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.services.PoolService;

import java.util.List;
import java.util.Map;

/** Lot 76 : serveurs à la demande (proxy : /jouer, arrêt des serveurs vides) */
@RestController
@RequestMapping("/api/v1/pools")
@RequiredArgsConstructor
public class PoolController {

    private final PoolService pools;

    public record ClaimBody(Long playerId, List<String> occupied) {}

    public record CreateBody(Long playerId) {}

    @FunctionalInterface
    private interface Action {
        Object run() throws Exception;
    }

    private static ResponseEntity<?> handle(Action a) {
        try {
            return ResponseEntity.ok(a.run());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return handle(() -> Map.of("pools", pools.list()));
    }

    @PostMapping("/{pool}/claim")
    public ResponseEntity<?> claim(@PathVariable String pool, @RequestBody ClaimBody body) {
        return handle(() -> pools.claim(pool, body.playerId(), body.occupied()));
    }

    /** Jusqu'à 20 min : le proxy attend la fin de la création */
    @PostMapping("/{pool}/create")
    public ResponseEntity<?> create(@PathVariable String pool, @RequestBody CreateBody body) {
        return handle(() -> pools.create(pool, body.playerId()));
    }

    @PostMapping("/servers/{id}/idle-stop")
    public ResponseEntity<?> idleStop(@PathVariable Long id) {
        return handle(() -> pools.idleStop(id));
    }
}
