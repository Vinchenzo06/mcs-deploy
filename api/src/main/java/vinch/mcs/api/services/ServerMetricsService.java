package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentStatsEvent;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dernières mesures connues de chaque serveur, gardées en mémoire (pas
 * d'écriture en base toutes les 30 secondes). Perdues au redémarrage de l'API :
 * les agents les renvoient dans les 30 secondes.
 *
 * Quota disque : au-delà de l'espace alloué, un serveur ne peut plus démarrer ;
 * au-delà de 110 %, il est arrêté proprement (protection du disque du volontaire).
 * Avec la réserve de placement (quotas ≤ 90 % du disque prêté), même si tous les
 * serveurs dépassent en même temps : 90 % × 110 % = 99 % du disque prêté au plus.
 * Mesure toutes les 10 minutes : c'est un quota "souple", pas une limite dure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerMetricsService {

    public record Metrics(boolean running, double cpuPercent, long memUsedMb, long memLimitMb,
                          long netRxBytes, long netTxBytes, long pids, Long diskUsedMb, Instant updatedAt) {
    }

    private static final double DISK_STOP_RATIO = 1.10;

    private final ServerRepository serverRepository;
    private final VelocityClient velocityClient;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final vinch.mcs.api.repositories.BackupRepository backupRepository;

    private final Map<Long, Metrics> metrics = new ConcurrentHashMap<>();
    private final Map<Long, String> health = new ConcurrentHashMap<>();

    @EventListener
    public void onStats(AgentStatsEvent event) {
        // Une machine ne peut renseigner que ses propres serveurs
        Map<Long, Server> own = serverRepository.findByNodeId(event.nodeId()).stream()
                .collect(Collectors.toMap(Server::getId, Function.identity()));
        if (event.servers() == null || !event.servers().isArray()) {
            return;
        }
        Instant now = Instant.now();
        for (JsonNode s : event.servers()) {
            long id = s.path("server_id").asLong(-1);
            Server server = own.get(id);
            if (server == null) {
                continue;
            }
            Metrics previous = metrics.get(id);
            Long disk = s.hasNonNull("disk_used_mb") ? Long.valueOf(s.get("disk_used_mb").asLong())
                    : (previous != null ? previous.diskUsedMb() : null);
            metrics.put(id, new Metrics(
                    s.path("running").asBoolean(false),
                    s.path("cpu_percent").asDouble(0),
                    s.path("mem_used_mb").asLong(0),
                    s.path("mem_limit_mb").asLong(0),
                    s.path("net_rx_bytes").asLong(0),
                    s.path("net_tx_bytes").asLong(0),
                    s.path("pids").asLong(0),
                    disk,
                    now));

            if (disk != null && server.getAllocatedStorageMb() != null && server.getAllocatedStorageMb() > 0
                    && disk > server.getAllocatedStorageMb() * DISK_STOP_RATIO
                    && server.getStatus() == ServerStatus.RUNNING) {
                stopForDiskQuota(event.nodeId(), server, disk);
            }
        }
    }

    private void stopForDiskQuota(Long nodeId, Server server, long diskMb) {
        log.warn("Serveur {} ({}) : {} Mo utilisés pour un quota de {} Mo -> arrêt",
                server.getId(), server.getVelocityName(), diskMb, server.getAllocatedStorageMb());
        server.setStatus(ServerStatus.STOPPING);
        serverRepository.save(server);
        try {
            velocityClient.unregisterServer(server.getVelocityName());
        } catch (Exception e) {
            log.debug("Désenregistrement Velocity : {}", e.getMessage());
        }
        Long id = server.getId();
        agentWebSocketHandler.sendCommand(nodeId, "stop_server", Map.<String, Object>of("server_id", id))
                .whenComplete((result, error) -> serverRepository.findById(id).ifPresent(s -> {
                    s.setStatus(error == null ? ServerStatus.STOPPED : ServerStatus.ERROR);
                    s.setLastStoppedAt(LocalDateTime.now());
                    serverRepository.save(s);
                }));
    }

    /** Message d'erreur si le serveur dépasse son quota disque (ne peut pas démarrer), sinon null */
    public String diskQuotaProblem(Server server) {
        Metrics m = metrics.get(server.getId());
        Integer quota = server.getAllocatedStorageMb();
        if (m == null || m.diskUsedMb() == null || quota == null || quota <= 0 || m.diskUsedMb() <= quota) {
            return null;
        }
        return String.format("Quota disque dépassé (%d / %d Mo) : le serveur ne peut pas démarrer. "
                + "Demande à un admin d'augmenter son quota.", m.diskUsedMb(), quota);
    }

    public void recordHealth(Long serverId, String event) {
        switch (event) {
            case "healthy" -> health.put(serverId, "healthy");
            case "unhealthy" -> health.put(serverId, "unhealthy");
            case "start" -> health.put(serverId, "starting");
            case "die" -> health.put(serverId, "stopped");
            default -> { }
        }
    }

    public void forget(Long serverId) {
        metrics.remove(serverId);
        health.remove(serverId);
    }

    /** Vue d'ensemble d'un serveur pour le panneau et /mcs info */
    public Map<String, Object> describe(Server server) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", server.getId());
        out.put("name", server.getName());
        out.put("velocityName", server.getVelocityName());
        out.put("status", server.getStatus().name());
        out.put("health", health.getOrDefault(server.getId(), "unknown"));
        out.put("serverType", server.getServerType() != null ? server.getServerType().name() : null);
        out.put("minecraftVersion", server.getMinecraftVersion());
        out.put("java", JavaVersions.effective(server));
        out.put("javaAuto", server.getJavaVersion() == null);
        out.put("javaRecommended", JavaVersions.auto(server.getServerType(), server.getMinecraftVersion()));
        out.put("allocatedRamMb", server.getAllocatedRamMb());
        out.put("allocatedCpuCores", server.getAllocatedCpuCores());
        out.put("allocatedStorageMb", server.getAllocatedStorageMb());
        backupRepository.findFirstByServerIdAndStatusOrderByCreatedAtDesc(server.getId(), "SUCCESS")
                .ifPresent(b -> out.put("lastBackupAt", b.getCreatedAt().toString()));
        out.put("nodeId", server.getNode() != null ? server.getNode().getId() : null);
        out.put("lastStartedAt", server.getLastStartedAt() != null ? server.getLastStartedAt().toString() : null);
        out.put("players", velocityClient.getPlayerCounts().getOrDefault(server.getVelocityName(), 0));

        Metrics m = metrics.get(server.getId());
        if (m != null) {
            out.put("cpuPercent", Math.round(m.cpuPercent() * 10) / 10.0);
            out.put("memUsedMb", m.memUsedMb());
            out.put("memLimitMb", m.memLimitMb());
            // Serveurs créés avant la 0.7.0 : marge ajoutée par-dessus, le tas = la RAM choisie
            Integer ram = server.getAllocatedRamMb();
            if (ram != null) {
                out.put("javaHeapMb", m.memLimitMb() > ram + 64 ? ram : ServerService.javaHeapMb(ram));
            }
            out.put("netRxBytes", m.netRxBytes());
            out.put("netTxBytes", m.netTxBytes());
            out.put("pids", m.pids());
            out.put("diskUsedMb", m.diskUsedMb());
            out.put("diskQuotaExceeded", diskQuotaProblem(server) != null);
            out.put("metricsAgeSeconds", Duration.between(m.updatedAt(), Instant.now()).toSeconds());
        }
        return out;
    }
}
