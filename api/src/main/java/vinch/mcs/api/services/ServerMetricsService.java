package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentStatsEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dernières mesures connues de chaque serveur, gardées en mémoire (pas
 * d'écriture en base toutes les 30 secondes). Perdues au redémarrage de l'API :
 * les agents les renvoient dans les 30 secondes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerMetricsService {

    public record Metrics(boolean running, double cpuPercent, long memUsedMb, long memLimitMb,
                          long netRxBytes, long netTxBytes, long pids, Long diskUsedMb, Instant updatedAt) {
    }

    private final ServerRepository serverRepository;
    private final VelocityClient velocityClient;

    private final Map<Long, Metrics> metrics = new ConcurrentHashMap<>();
    private final Map<Long, String> health = new ConcurrentHashMap<>();

    @EventListener
    public void onStats(AgentStatsEvent event) {
        // Une machine ne peut renseigner que ses propres serveurs
        Set<Long> own = serverRepository.findByNodeId(event.nodeId()).stream()
                .map(Server::getId).collect(Collectors.toSet());
        if (event.servers() == null || !event.servers().isArray()) {
            return;
        }
        Instant now = Instant.now();
        for (JsonNode s : event.servers()) {
            long id = s.path("server_id").asLong(-1);
            if (!own.contains(id)) {
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
        }
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
        out.put("allocatedRamMb", server.getAllocatedRamMb());
        out.put("allocatedCpuCores", server.getAllocatedCpuCores());
        out.put("allocatedStorageMb", server.getAllocatedStorageMb());
        out.put("nodeId", server.getNode() != null ? server.getNode().getId() : null);
        out.put("lastStartedAt", server.getLastStartedAt() != null ? server.getLastStartedAt().toString() : null);
        out.put("players", velocityClient.getPlayerCounts().getOrDefault(server.getVelocityName(), 0));

        Metrics m = metrics.get(server.getId());
        if (m != null) {
            out.put("cpuPercent", Math.round(m.cpuPercent() * 10) / 10.0);
            out.put("memUsedMb", m.memUsedMb());
            out.put("memLimitMb", m.memLimitMb());
            out.put("netRxBytes", m.netRxBytes());
            out.put("netTxBytes", m.netTxBytes());
            out.put("pids", m.pids());
            out.put("diskUsedMb", m.diskUsedMb());
            out.put("metricsAgeSeconds", Duration.between(m.updatedAt(), Instant.now()).toSeconds());
        }
        return out;
    }
}
