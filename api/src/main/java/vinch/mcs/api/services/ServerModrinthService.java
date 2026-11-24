package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import vinch.mcs.api.repositories.ServerRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lot 70 : liste des plugins/mods Modrinth d'un serveur (droit « fichiers » : mêmes personnes que la console) */
@Service
@RequiredArgsConstructor
public class ServerModrinthService {

    private final ServerRepository serverRepository;
    private final AccessService accessService;

    private Server server(Long serverId, Long playerId) {
        Server s = serverRepository.findById(serverId)
                .filter(x -> x.getStatus() != ServerStatus.DELETED)
                .orElseThrow(() -> new RuntimeException("Serveur introuvable"));
        Player p = accessService.player(playerId);
        accessService.require(p, s, AccessService.Right.FILES);
        return s;
    }

    private static Map<String, Object> describe(Server s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.getServerType().name());
        m.put("version", s.getMinecraftVersion());
        m.put("supported", ModrinthPlugins.supports(s.getServerType()));
        m.put("kind", ModrinthPlugins.kind(s.getServerType()));
        m.put("projects", ModrinthPlugins.list(s));
        m.put("status", s.getStatus().name());
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long serverId, Long playerId) {
        return describe(server(serverId, playerId));
    }

    @Transactional
    public Map<String, Object> set(Long serverId, Long playerId, List<String> projects) {
        Server s = server(serverId, playerId);
        if (!ModrinthPlugins.supports(s.getServerType())) {
            throw new RuntimeException("Un serveur Vanilla ne charge ni plugins ni mods.");
        }
        List<String> clean = ModrinthPlugins.normalize(projects);
        s.setModrinthProjects(clean.isEmpty() ? null : String.join(",", clean));
        serverRepository.save(s);
        return describe(s);
    }
}
