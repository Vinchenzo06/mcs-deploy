package vinch.mcs.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.services.BackupService;

import java.util.Map;

/** Sauvegardes : à la demande, liste, politique (admins) */
@RestController
@RequestMapping("/api/v1/servers/{id}")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    public record PolicyBody(Long playerId, Integer intervalHours, Integer keepLast, Integer keepWeekly, Boolean reset) {}

    private static ResponseEntity<?> error(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @PostMapping("/backup")
    public ResponseEntity<?> backupNow(@PathVariable Long id, @RequestParam Long playerId) {
        try {
            return ResponseEntity.ok(backupService.backupNow(id, playerId));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/backups")
    public ResponseEntity<?> list(@PathVariable Long id, @RequestParam Long playerId) {
        try {
            return ResponseEntity.ok(backupService.list(id, playerId));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/backup-policy")
    public ResponseEntity<?> policy(@PathVariable Long id, @RequestBody PolicyBody body) {
        try {
            return ResponseEntity.ok(backupService.setPolicy(id, body.playerId(), body.intervalHours(),
                    body.keepLast(), body.keepWeekly(), Boolean.TRUE.equals(body.reset())));
        } catch (Exception e) {
            return error(e);
        }
    }
}
