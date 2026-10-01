package vinch.mcs.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.services.MigrationService;
import vinch.mcs.api.services.NotificationService;

import java.util.Map;

/** Déplacement de serveurs, régions, machines (admins), messages aux joueurs */
@RestController
@RequiredArgsConstructor
public class MigrationController {

    private final MigrationService migrationService;
    private final NotificationService notificationService;

    /** region ou machine : destination ; fromBackup : depuis une sauvegarde du central */
    public record MigrateBody(Long playerId, String region, Long machine, Boolean fromBackup, Long backupId) {}

    public record RegionBody(Long playerId, String region) {}

    public record MachineBody(Long playerId, Long target, Boolean fromBackup) {}

    private static ResponseEntity<?> error(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @PostMapping("/api/v1/servers/{id}/migrate")
    public ResponseEntity<?> migrate(@PathVariable Long id, @RequestBody MigrateBody body) {
        try {
            return ResponseEntity.ok(migrationService.migrate(id, body.playerId(), body.region(), body.machine(),
                    Boolean.TRUE.equals(body.fromBackup()), body.backupId()));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/api/v1/regions")
    public ResponseEntity<?> regions() {
        try {
            return ResponseEntity.ok(migrationService.regions());
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/v1/nodes/{id}/region")
    public ResponseEntity<?> region(@PathVariable Long id, @RequestBody RegionBody body) {
        try {
            return ResponseEntity.ok(migrationService.setRegion(body.playerId(), id, body.region()));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/v1/nodes/{id}/backup")
    public ResponseEntity<?> backupMachine(@PathVariable Long id, @RequestBody MachineBody body) {
        try {
            return ResponseEntity.ok(migrationService.backupMachine(body.playerId(), id));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/v1/nodes/{id}/evacuate")
    public ResponseEntity<?> evacuate(@PathVariable Long id, @RequestBody MachineBody body) {
        try {
            return ResponseEntity.ok(migrationService.evacuate(body.playerId(), id, body.target(),
                    Boolean.TRUE.equals(body.fromBackup())));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** Messages pas encore vus (le proxy les affiche à la connexion) */
    @GetMapping("/api/v1/players/{id}/notifications")
    public ResponseEntity<?> notifications(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(Map.of("notifications", notificationService.pending(id)));
        } catch (Exception e) {
            return error(e);
        }
    }
}
