package vinch.mcs.api.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.entities.PermissionLevel;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.services.AccessService;
import vinch.mcs.api.services.ServerService;

import java.util.Map;
import java.util.UUID;

/**
 * Accès aux serveurs : référence d'un serveur par un joueur, contrôle du proxy,
 * invités, public/privé, console, move, et actions faites au nom d'un joueur
 * (playerId = celui qui demande ; ses droits sont vérifiés par AccessService).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class AccessController {

    private final AccessService accessService;
    private final ServerService serverService;
    private final ServerRepository serverRepository;

    // Corps des requêtes : des records, pas JsonNode (Spring Boot 4 utilise Jackson 3,
    // qui ne sait pas construire le JsonNode de Jackson 2 importé ailleurs dans l'API)
    public record InviteBody(Long playerId, String username, String level) {}

    public record VisibilityBody(Long playerId, Boolean isPublic) {}

    public record ConsoleBody(Long playerId, String command) {}

    public record MoveBody(Long playerId, String username) {}

    public record ConnectedBody(String server, String uuid) {}

    private interface Action {
        Object run() throws Exception;
    }

    private static ResponseEntity<?> handle(Action action) {
        try {
            Object result = action.run();
            return ResponseEntity.ok(result == null ? Map.of("success", true) : result);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    private Server server(Long id) {
        return serverRepository.findById(id).orElseThrow(() -> new RuntimeException("Serveur introuvable"));
    }

    // ------------------------------------------------------------ joueur ----

    /** "nom" ou "proprietaire/nom" -> serveur, avec les droits du joueur */
    @GetMapping("/servers/resolve")
    public ResponseEntity<?> resolve(@RequestParam Long playerId, @RequestParam String ref) {
        return handle(() -> {
            Player p = accessService.player(playerId);
            return accessService.describeFor(p, accessService.resolve(p, ref));
        });
    }

    @PostMapping("/servers/{id}/start")
    public ResponseEntity<?> start(@PathVariable Long id, @RequestParam Long playerId) {
        return handle(() -> {
            serverService.startServer(id, playerId);
            return null;
        });
    }

    @PostMapping("/servers/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable Long id, @RequestParam Long playerId) {
        return handle(() -> {
            serverService.stopServer(id, playerId);
            return null;
        });
    }

    @PostMapping("/servers/{id}/restart")
    public ResponseEntity<?> restart(@PathVariable Long id, @RequestParam Long playerId) {
        return handle(() -> {
            serverService.restartServer(id, playerId);
            return null;
        });
    }

    @PostMapping("/servers/{id}/delete")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam Long playerId) {
        return handle(() -> {
            serverService.deleteServerFor(id, playerId);
            return null;
        });
    }

    @GetMapping("/servers/{id}/members")
    public ResponseEntity<?> members(@PathVariable Long id, @RequestParam Long playerId) {
        return handle(() -> Map.of("members", accessService.members(accessService.player(playerId), server(id))));
    }

    /** Body : {playerId, username, level} ; level = membre | gerant | technicien */
    @PostMapping("/servers/{id}/members")
    public ResponseEntity<?> invite(@PathVariable Long id, @RequestBody InviteBody body) {
        return handle(() -> {
            PermissionLevel level = PermissionLevel.parse(body.level() == null ? "membre" : body.level());
            if (level == null) {
                throw new RuntimeException("Rôle inconnu : choisis membre, gerant ou technicien.");
            }
            boolean created = accessService.invite(accessService.player(body.playerId()),
                    server(id), body.username(), level);
            return Map.of("success", true, "created", created, "level", level.label());
        });
    }

    @DeleteMapping("/servers/{id}/members/{username}")
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable String username,
                                          @RequestParam Long playerId) {
        return handle(() -> {
            accessService.remove(accessService.player(playerId), server(id), username);
            return null;
        });
    }

    /** Body : {playerId, isPublic} */
    @PostMapping("/servers/{id}/visibility")
    public ResponseEntity<?> visibility(@PathVariable Long id, @RequestBody VisibilityBody body) {
        return handle(() -> {
            accessService.setPublic(accessService.player(body.playerId()), server(id),
                    Boolean.TRUE.equals(body.isPublic()));
            return null;
        });
    }

    /** Body : {playerId, command} */
    @PostMapping("/servers/{id}/console")
    public ResponseEntity<?> console(@PathVariable Long id, @RequestBody ConsoleBody body) {
        return handle(() -> Map.of("output",
                serverService.console(id, body.playerId(), body.command() == null ? "" : body.command())));
    }

    /** Body : {playerId, username} */
    @PostMapping("/servers/{id}/move")
    public ResponseEntity<?> move(@PathVariable Long id, @RequestBody MoveBody body) {
        return handle(() -> {
            serverService.movePlayer(id, body.playerId(), body.username());
            return null;
        });
    }

    // ------------------------------------------------------------ proxy ----

    /** Le proxy demande si ce joueur peut entrer sur ce serveur */
    @GetMapping("/access/check")
    public ResponseEntity<?> check(@RequestParam String server, @RequestParam UUID uuid) {
        try {
            return ResponseEntity.ok(accessService.checkJoin(server, uuid));
        } catch (Exception e) {
            log.error("Contrôle d'accès {} / {} : {}", server, uuid, e.getMessage());
            return ResponseEntity.ok(Map.of("allowed", false, "reason", "Vérification d'accès impossible, réessaie."));
        }
    }

    /** Le proxy signale qu'un joueur est arrivé sur un serveur (OP automatique des admins) */
    @PostMapping("/access/connected")
    public ResponseEntity<?> connected(@RequestBody ConnectedBody body) {
        try {
            serverService.onPlayerConnected(body.server(), UUID.fromString(body.uuid()));
        } catch (Exception e) {
            log.debug("access/connected : {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}
