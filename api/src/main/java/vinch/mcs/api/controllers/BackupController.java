package vinch.mcs.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.entities.BackupKind;
import vinch.mcs.api.services.BackupService;

import java.util.Map;

/**
 * Sauvegardes : à la demande (manuelle ou permanente), liste, réglages du serveur
 * (propriétaire dans les limites de son rôle, admins), réglages du réseau et des
 * rôles (admins).
 */
@RestController
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    /** kind : quotidienne, hebdomadaire, mensuelle, manuelle, permanente */
    /** local : sur la machine (sinon au central) */
    public record RuleBody(Long playerId, String kind, Integer max, Integer duration, Boolean reset, Boolean local) {}

    public record HostRuleBody(Long playerId, String kind, Integer max, Integer duration, Boolean reset) {}

    public record ScopeRuleBody(Long playerId, String scope, String kind, Integer max, Integer duration, Boolean reset) {}

    public record PermanentBody(Long playerId, Boolean permanent) {}

    private static ResponseEntity<?> error(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static BackupKind kind(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new RuntimeException("Type manquant : quotidienne, hebdomadaire, mensuelle, manuelle ou permanente.");
            }
            return null;
        }
        BackupKind k = BackupKind.parse(raw);
        if (k == null) {
            throw new RuntimeException("Type inconnu : " + raw
                    + " (quotidienne, hebdomadaire, mensuelle, manuelle ou permanente).");
        }
        return k;
    }

    @PostMapping("/api/v1/servers/{id}/backup")
    public ResponseEntity<?> backupNow(@PathVariable Long id, @RequestParam Long playerId,
                                       @RequestParam(defaultValue = "false") boolean permanent) {
        try {
            return ResponseEntity.ok(backupService.backupNow(id, playerId, permanent));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/api/v1/servers/{id}/backups")
    public ResponseEntity<?> list(@PathVariable Long id, @RequestParam Long playerId) {
        try {
            return ResponseEntity.ok(backupService.list(id, playerId));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/v1/servers/{id}/backups/{backupId}/permanent")
    public ResponseEntity<?> permanent(@PathVariable Long id, @PathVariable Long backupId,
                                       @RequestBody PermanentBody body) {
        try {
            return ResponseEntity.ok(backupService.setPermanent(id, backupId, body.playerId(),
                    !Boolean.FALSE.equals(body.permanent())));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/v1/servers/{id}/backup-settings")
    public ResponseEntity<?> serverRule(@PathVariable Long id, @RequestBody RuleBody body) {
        try {
            boolean reset = Boolean.TRUE.equals(body.reset());
            return ResponseEntity.ok(backupService.setServerRule(id, body.playerId(), kind(body.kind(), !reset),
                    body.max(), body.duration(), reset, Boolean.TRUE.equals(body.local())));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * scope : "network" (défauts du réseau), "min" (minimum au central), "local"
     * (minimum que les hôtes offrent) ou le nom d'un rôle LuckPerms (limites)
     */
    @GetMapping("/api/v1/backup-settings")
    public ResponseEntity<?> getScope(@RequestParam Long playerId, @RequestParam(defaultValue = "network") String scope) {
        try {
            return ResponseEntity.ok(backupService.getScope(playerId, scope));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/v1/backup-settings")
    public ResponseEntity<?> scopeRule(@RequestBody ScopeRuleBody body) {
        try {
            boolean reset = Boolean.TRUE.equals(body.reset());
            return ResponseEntity.ok(backupService.setScopeRule(body.playerId(), body.scope(),
                    kind(body.kind(), false), body.max(), body.duration(), reset));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** Machines de l'hôte (toutes pour un admin) et leurs réglages de sauvegardes locales */
    @GetMapping("/api/v1/hosts/backup-settings")
    public ResponseEntity<?> hostSettings(@RequestParam Long playerId, @RequestParam(required = false) Long machine) {
        try {
            return ResponseEntity.ok(backupService.hostSettings(playerId, machine));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/api/v1/hosts/{machine}/backup-settings")
    public ResponseEntity<?> hostRule(@PathVariable Long machine, @RequestBody HostRuleBody body) {
        try {
            boolean reset = Boolean.TRUE.equals(body.reset());
            return ResponseEntity.ok(backupService.setHostRule(body.playerId(), machine, kind(body.kind(), !reset),
                    body.max(), body.duration(), reset));
        } catch (Exception e) {
            return error(e);
        }
    }
}
