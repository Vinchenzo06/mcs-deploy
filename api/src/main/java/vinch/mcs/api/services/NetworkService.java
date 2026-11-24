package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.entities.Node;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import vinch.mcs.api.repositories.NodeRepository;
import vinch.mcs.api.repositories.ServerRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lot 69 : vue d'ensemble des machines pour la page Admin → Réseau du site IMG (par le proxy,
 * au nom d'un admin du réseau) : en ligne, version de l'agent, capacité prêtée et utilisée,
 * disque libre, serveurs.
 */
@Service
@RequiredArgsConstructor
public class NetworkService {

    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final AccessService accessService;

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Long playerId) {
        if (!AccessService.isAdmin(accessService.player(playerId))) {
            throw new SecurityException("Réservé aux admins du réseau.");
        }
        // Par machine : serveurs, en marche, RAM / CPU / disque alloués
        Map<Long, long[]> use = new HashMap<>();
        for (Server s : serverRepository.findAll()) {
            if (s.getNode() == null || s.getStatus() == ServerStatus.DELETED) {
                continue;
            }
            long[] u = use.computeIfAbsent(s.getNode().getId(), k -> new long[5]);
            u[0]++;
            if (s.getStatus() == ServerStatus.RUNNING) {
                u[1]++;
            }
            u[2] += s.getAllocatedRamMb() == null ? 0 : s.getAllocatedRamMb();
            u[3] += s.getAllocatedCpuCores() == null ? 0 : s.getAllocatedCpuCores();
            u[4] += s.getAllocatedStorageMb() == null ? 0 : s.getAllocatedStorageMb();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Node> all = new ArrayList<>(nodeRepository.findAll());
        all.sort(Comparator.comparing(Node::getId));
        for (Node n : all) {
            long[] u = use.getOrDefault(n.getId(), new long[5]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("hostname", n.getHostname());
            m.put("region", n.getRegion());
            m.put("online", Boolean.TRUE.equals(n.getIsOnline()));
            m.put("revoked", Boolean.TRUE.equals(n.getIsRevoked()));
            m.put("lastHeartbeatAt", n.getLastHeartbeatAt() == null ? null : n.getLastHeartbeatAt().toString());
            m.put("agentVersion", n.getAgentVersion());
            m.put("owner", n.getOwnerPlayer() == null ? null : n.getOwnerPlayer().getMinecraftUsername());
            m.put("ramLentMb", n.getTotalRamMb());
            m.put("cpuLent", n.getCpuCores());
            m.put("storageLentMb", n.getTotalStorageMb());
            m.put("hostRamMb", n.getHostRamMb());
            m.put("hostCpuCores", n.getHostCpuCores());
            m.put("hostDiskTotalMb", n.getHostDiskTotalMb());
            m.put("hostDiskFreeMb", n.getHostDiskFreeMb());
            m.put("servers", u[0]);
            m.put("running", u[1]);
            m.put("ramAllocatedMb", u[2]);
            m.put("cpuAllocated", u[3]);
            m.put("storageAllocatedMb", u[4]);
            nodes.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("generatedAt", LocalDateTime.now().toString());
        return out;
    }
}
