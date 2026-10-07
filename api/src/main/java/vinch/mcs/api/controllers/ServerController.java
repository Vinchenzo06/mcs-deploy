package vinch.mcs.api.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.dto.CreateServerRequest;
import vinch.mcs.api.dto.CreateServerResponse;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.services.JavaVersions;
import vinch.mcs.api.services.NotificationService;
import vinch.mcs.api.services.ServerMetricsService;
import vinch.mcs.api.services.ServerService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/v1/servers")
@RequiredArgsConstructor
@Slf4j
public class ServerController {

    private final ServerService serverService;
    private final ServerMetricsService metricsService;
    private final ServerRepository serverRepository;
    private final NotificationService notificationService;

    /** Créations en cours (une machine neuve peut mettre plusieurs minutes) */
    private static final ExecutorService CREATIONS = Executors.newVirtualThreadPerTaskExecutor();
    /** Au-delà, la réponse part tout de suite et le joueur est prévenu à la fin */
    private static final int CREATE_WAIT_SECONDS = 45;

    // État et mesures d'un serveur (panneau, /mcs info)
    @GetMapping("/{serverId}/stats")
    public ResponseEntity<?> getServerStats(@PathVariable Long serverId) {
        return serverRepository.findById(serverId)
                .<ResponseEntity<?>>map(server -> ResponseEntity.ok(metricsService.describe(server)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Serveur introuvable")));
    }

    @PostMapping
    public ResponseEntity<?> createServer(@Valid @RequestBody CreateServerRequest request) {
        CompletableFuture<CreateServerResponse> creation = CompletableFuture.supplyAsync(() -> {
            try {
                return serverService.createServer(request);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, CREATIONS);
        try {
            return ResponseEntity.ok(creation.get(CREATE_WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            // Longue création (machine neuve, gros monde) : le joueur reçoit un message à la fin
            String name = request.getName();
            Long playerId = request.getOwnerPlayerId();
            creation.whenComplete((done, error) -> {
                try {
                    if (error == null) {
                        notificationService.notify(playerId, "Ton serveur " + name + " est prêt : /mcs join " + name);
                    } else {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause() : error;
                        log.error("Erreur création (en arrière-plan) : {}", cause.getMessage());
                        notificationService.notify(playerId, cause instanceof JavaVersions.JavaIssueException je
                                ? "Ton serveur " + name + " ne démarre pas. " + je.hint()
                                : "La création de " + name + " a échoué : " + cause.getMessage());
                    }
                } catch (Exception ex) {
                    log.warn("Message de fin de création pour {} : {}", name, ex.getMessage());
                }
            });
            return ResponseEntity.accepted().body(Map.of("status", "CREATING", "name", name));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException && e.getCause().getCause() != null
                    ? e.getCause().getCause() : e.getCause();
            log.error("Erreur création : {}", cause.getMessage());
            if (cause instanceof JavaVersions.JavaIssueException je) {
                return ResponseEntity.badRequest().body(je.body());
            }
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(cause.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(Map.of("error", "Création interrompue"));
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