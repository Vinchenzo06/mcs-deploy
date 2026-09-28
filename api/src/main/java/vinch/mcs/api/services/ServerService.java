package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.dto.CreateServerRequest;
import vinch.mcs.api.dto.CreateServerResponse;
import vinch.mcs.api.entities.*;
import vinch.mcs.api.repositories.NodeRepository;
import vinch.mcs.api.repositories.PlayerRepository;
import vinch.mcs.api.repositories.ServerRepository;
import vinch.mcs.api.websocket.AgentWebSocketHandler;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerService {

    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;
    private final PlayerRepository playerRepository;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final VelocityClient velocityClient;
    private final ServerMetricsService metricsService;
    private final AccessService accessService;

    @Transactional
    public CreateServerResponse createServer(CreateServerRequest request) throws Exception {
        log.info("Création d'un serveur : owner={} name={} type={} version={}",
                request.getOwnerPlayerId(), request.getName(), request.getServerType(), request.getMinecraftVersion());

        // Vérifier que le joueur existe
        Player owner = playerRepository.findById(request.getOwnerPlayerId())
                .orElseThrow(() -> new RuntimeException("Joueur non trouvé : " + request.getOwnerPlayerId()));

        if (request.getRamMb() < MIN_RAM_MB) {
            throw new RuntimeException("RAM minimum : " + MIN_RAM_MB + " Mo");
        }
        if (request.getCpuCores() < 1) {
            throw new RuntimeException("CPU minimum : 1 cœur");
        }

        // Vérifier la limite de serveurs du joueur
        long count = serverRepository.countByOwnerId(owner.getId());
        if (count >= owner.getMaxServers()) {
            throw new RuntimeException("Limite de serveurs atteinte : " + owner.getMaxServers());
        }

// Vérifier le budget RAM total
        long currentRamUsed = serverRepository.sumAllocatedRamByOwnerId(owner.getId());
        long ramAfterCreation = currentRamUsed + request.getRamMb();

        if (ramAfterCreation > owner.getTotalRamMb()) {
            long remaining = owner.getTotalRamMb() - currentRamUsed;
            throw new RuntimeException(String.format(
                    "Pas assez de RAM disponible. Demandé : %d Mo, budget restant : %d Mo (sur %d Mo total)",
                    request.getRamMb(), remaining, owner.getTotalRamMb()
            ));
        }

        // Vérifier le budget CPU
        long currentCpuUsed = serverRepository.sumAllocatedCpuByOwnerId(owner.getId());
        long cpuAfterCreation = currentCpuUsed + request.getCpuCores();

        if (cpuAfterCreation > owner.getTotalCpuCores()) {
            long remaining = owner.getTotalCpuCores() - currentCpuUsed;
            throw new RuntimeException(String.format(
                    "Pas assez de CPU disponible. Demandé : %d vcore(s), budget restant : %d vcore(s) (sur %d total)",
                    request.getCpuCores(), remaining, owner.getTotalCpuCores()
            ));
        }

        // Vérifier que ce joueur n'a pas déjà un serveur avec ce nom
        if (serverRepository.findByOwnerIdAndName(owner.getId(), request.getName()).isPresent()) {
            throw new RuntimeException("Tu as déjà un serveur nommé '" + request.getName() + "'");
        }

        // Choisir le node : assez de place pour ce serveur (RAM, CPU, disque)
        Node node = chooseNode(request.getForceNodeId(), request);

        // Disque proportionnel à la RAM : 10 % de la RAM prêtée par la machine
        // donne 10 % de son disque prêté (le joueur ne choisit pas son disque)
        int storageMb = diskQuotaFor(node, request.getRamMb());

        // Allouer un port libre
        int port = allocatePort(node);

        log.info("Allocation : node={} port={}", node.getId(), port);

        String uuidShort = owner.getMinecraftUuid().toString().substring(0, 8);
        String velocityName = uuidShort + "-" + request.getName();

        Server server = Server.builder()
                .owner(owner)
                .node(node)
                .name(request.getName())
                .velocityName(velocityName)
                .displayName(request.getDisplayName())
                .serverType(request.getServerType())
                .minecraftVersion(request.getMinecraftVersion())
                .localPort(port)
                .tunnelPort(port)
                .allocatedRamMb(request.getRamMb())
                .allocatedCpuCores(request.getCpuCores())
                .allocatedStorageMb(storageMb)
                .status(ServerStatus.CREATING)
                .build();

        server = serverRepository.save(server);
        log.info("Serveur créé en base : id={} velocityName={}", server.getId(), velocityName);

        // Envoyer la commande à l'agent
        Map<String, Object> data = new HashMap<>();
        data.put("server_id", server.getId());
        data.put("type", request.getServerType().name());
        data.put("version", request.getMinecraftVersion());
        data.put("port", port);
        data.put("ram_mb", request.getRamMb());
        data.put("cpu_cores", request.getCpuCores());  // ← AJOUTER
        data.put("storage_mb", storageMb);
        data.put("owner_name", owner.getMinecraftUsername());

        try {
            CompletableFuture<JsonNode> future = agentWebSocketHandler.sendCommand(
                    node.getId(), "create_server", data);
            JsonNode result = future.get();

            if (result.has("success") && !result.get("success").asBoolean()) {
                server.setStatus(ServerStatus.ERROR);
                serverRepository.save(server);
                String error = result.has("message") ? result.get("message").asText() : "Erreur inconnue";
                throw new RuntimeException("Échec création côté agent : " + error);
            }

            // Succès : marquer comme RUNNING
            server.setStatus(ServerStatus.RUNNING);
            server.setLastStartedAt(LocalDateTime.now());
            serverRepository.save(server);

            log.info("Serveur {} créé avec succès sur node {}", server.getId(), node.getId());

        } catch (Exception e) {
            log.error("Erreur création serveur {}: {}", server.getId(), e.getMessage());
            server.setStatus(ServerStatus.ERROR);
            serverRepository.save(server);
            throw e;
        }

        // Enregistrer le serveur dans Velocity avec le velocity_name
        try {
            velocityClient.registerServer(server.getVelocityName(), "127.0.0.1", server.getTunnelPort());
        } catch (Exception e) {
            log.error("Erreur d'enregistrement Velocity : {}", e.getMessage());
        }

        return CreateServerResponse.builder()
                .serverId(server.getId())
                .name(server.getName())
                .velocityName(server.getVelocityName())
                .port(server.getTunnelPort())
                .nodeId(node.getId())
                .status(server.getStatus().name())
                .storageMb(storageMb)
                .build();
    }

    /** RAM minimale d'un serveur (limite totale du conteneur) */
    public static final int MIN_RAM_MB = 1024;

    /**
     * Mémoire réelle d'un conteneur : exactement la RAM choisie par le joueur.
     * C'est une limite stricte ; la marge dont la JVM a besoin est prise dedans
     * (voir javaHeapMb), jamais ajoutée par-dessus.
     */
    static int containerRamMb(int ramMb) {
        return ramMb;
    }

    /**
     * Part de la RAM d'un serveur donnée au tas Java (-Xmx). Le reste sert à la JVM
     * hors du tas : classes, code compilé, threads, tampons réseau, GC.
     * 20 % de la RAM, au moins 512 Mo, au plus 3 Go. Même calcul que l'agent (javaHeapMb).
     */
    public static int javaHeapMb(int ramMb) {
        int overhead = Math.max(512, Math.min(3072, ramMb / 5));
        return Math.max(256, ramMb - overhead);
    }

    // Marge de disque libre à garder sur la machine du volontaire
    private static final int DISK_FREE_MARGIN_MB = 2048;

    // Plancher du quota disque (petites machines, petits serveurs)
    private static final int MIN_DISK_QUOTA_MB = 1024;

    // Réserve de sécurité : une machine n'est remplie qu'à (100 - réserve) % de ce
    // que le volontaire prête (RAM, CPU, disque). Les quotas disque suivant la RAM,
    // leur somme reste elle aussi sous ce seuil. Réglage : MCS_NODES_RESERVE_PERCENT.
    @Value("${mcs.nodes.reserve-percent:10}")
    private int reservePercent;

    private int usable(int lent) {
        int pct = Math.max(0, Math.min(50, reservePercent));
        return lent * (100 - pct) / 100;
    }

    /**
     * Quota disque d'un serveur sur une machine : la même part de son disque prêté
     * que la part de sa RAM prêtée qu'il occupe (RAM du conteneur, comme pour le
     * placement). La somme des quotas ne peut donc jamais dépasser le disque prêté.
     */
    static int diskQuotaFor(Node node, int ramMb) {
        Integer nodeRam = node.getTotalRamMb();
        Integer nodeDisk = node.getTotalStorageMb();
        if (nodeRam == null || nodeRam <= 0 || nodeDisk == null || nodeDisk <= 0) {
            return 5000; // capacité inconnue : ancien comportement
        }
        long quota = (long) nodeDisk * containerRamMb(ramMb) / nodeRam;
        return (int) Math.max(MIN_DISK_QUOTA_MB, Math.min(quota, nodeDisk));
    }

    /** Place encore libre sur une machine pour un nouveau serveur, ou null si elle ne peut pas l'accueillir */
    private Integer freeRamAfter(Node node, CreateServerRequest request) {
        if (node.getTotalRamMb() == null || node.getTotalRamMb() <= 0) {
            return null; // capacité inconnue (agent trop ancien) : on n'y place rien
        }
        List<Server> onNode = serverRepository.findByNodeId(node.getId());
        int ram = 0, cpu = 0, disk = 0;
        for (Server s : onNode) {
            ram += containerRamMb(s.getAllocatedRamMb());
            cpu += s.getAllocatedCpuCores();
            disk += s.getAllocatedStorageMb();
        }
        int freeRam = usable(node.getTotalRamMb()) - ram - containerRamMb(request.getRamMb());
        int storage = diskQuotaFor(node, request.getRamMb());
        boolean cpuOk = cpu + request.getCpuCores() <= Math.max(1, usable(node.getCpuCores()));
        boolean diskOk = node.getTotalStorageMb() == null || node.getTotalStorageMb() <= 0
                || disk + storage <= usable(node.getTotalStorageMb());
        boolean hostDiskOk = node.getHostDiskFreeMb() == null
                || node.getHostDiskFreeMb() - storage >= DISK_FREE_MARGIN_MB;
        return (freeRam >= 0 && cpuOk && diskOk && hostDiskOk) ? freeRam : null;
    }

    private Node chooseNode(Long forceNodeId, CreateServerRequest request) {
        if (forceNodeId != null) {
            Node forced = nodeRepository.findById(forceNodeId)
                    .orElseThrow(() -> new RuntimeException("Node forcée non trouvée : " + forceNodeId));
            if (forced.getIsRevoked() || forced.getPortStart() == null) {
                throw new RuntimeException("Node forcée inutilisable (révoquée ou sans plage de ports) : " + forceNodeId);
            }
            return forced;
        }

        // Parmi les machines en ligne qui ont la place, celle qui garde le plus de RAM libre
        List<Node> online = nodeRepository.findAll().stream()
                .filter(Node::getIsOnline)
                .filter(n -> !n.getIsRevoked())
                .filter(n -> n.getPortStart() != null && n.getPortEnd() != null)
                .toList();
        if (online.isEmpty()) {
            throw new RuntimeException("Aucune node disponible");
        }
        Node best = null;
        int bestFree = -1;
        for (Node n : online) {
            Integer free = freeRamAfter(n, request);
            if (free != null && free > bestFree) {
                best = n;
                bestFree = free;
            }
        }
        if (best == null) {
            throw new RuntimeException("Aucune machine n'a assez de place pour ce serveur (RAM, CPU ou disque) : "
                    + "essaie avec moins de RAM ou de CPU, ou plus tard");
        }
        return best;
    }

    // Premier port libre dans la plage de la machine. Les ports sont vérifiés
    // sur tout le VPS : deux serveurs ne peuvent jamais partager un tunnel.
    private int allocatePort(Node node) {
        Set<Integer> usedPorts = new HashSet<>(serverRepository.findAllUsedPorts());

        for (int port = node.getPortStart(); port <= node.getPortEnd(); port++) {
            if (!usedPorts.contains(port)) {
                return port;
            }
        }

        throw new RuntimeException("Plus de port libre sur la machine " + node.getId()
                + " (" + node.getPortStart() + "-" + node.getPortEnd() + ")");
    }

    public void deleteServer(Long serverId, boolean deleteData) throws Exception {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur non trouvé : " + serverId));

        log.info("Suppression définitive du serveur {} (name={} velocityName={})",
                serverId, server.getName(), server.getVelocityName());

        // La machine doit être joignable, sinon son conteneur resterait orphelin.
        // Exception : machine révoquée (ou absente), qui ne reviendra jamais.
        Node node = server.getNode();
        boolean nodeGone = node == null || Boolean.TRUE.equals(node.getIsRevoked());
        if (!nodeGone && !agentWebSocketHandler.isNodeOnline(node.getId())) {
            throw new RuntimeException("La machine qui héberge ce serveur est hors ligne : "
                    + "suppression impossible pour l'instant, réessaie plus tard");
        }

        // 1. Désenregistrer du Velocity avec velocity_name
        try {
            velocityClient.unregisterServer(server.getVelocityName());
            log.info("Serveur désenregistré de Velocity");
        } catch (Exception e) {
            log.warn("Erreur désenregistrement Velocity (on continue) : {}", e.getMessage());
        }

        // 2. Envoyer la commande à l'agent (sauf machine révoquée) : en cas d'échec,
        //    la base n'est PAS modifiée, pour ne jamais laisser de conteneur orphelin
        if (!nodeGone) {
            Map<String, Object> data = new HashMap<>();
            data.put("server_id", server.getId());
            data.put("delete_data", deleteData);

            JsonNode result = agentWebSocketHandler.sendCommand(node.getId(), "delete_server", data).get();
            if (result != null && result.has("success") && !result.get("success").asBoolean()) {
                String message = result.path("message").asText("erreur inconnue");
                throw new RuntimeException("Suppression refusée par la machine : " + message);
            }
            log.info("Conteneur et données supprimés côté agent");
        } else {
            log.info("Machine révoquée : suppression en base uniquement");
        }

        // 3. VRAIE suppression en base
        serverRepository.delete(server);
        metricsService.forget(serverId);
        log.info("Serveur {} supprimé de la base", serverId);
    }

    // Un résultat d'agent "success": false est un échec, pas une réussite
    private static void requireSuccess(JsonNode result) {
        if (result == null) {
            throw new RuntimeException("pas de réponse de la machine (délai dépassé)");
        }
        if (result.has("success") && !result.get("success").asBoolean()) {
            throw new RuntimeException(result.path("message").asText(result.path("error").asText("erreur inconnue")));
        }
    }

    public void deleteServerByName(String name, boolean deleteData, Long requestedByPlayerId) throws Exception {
        // Trouver le serveur par owner + name
        Server server = serverRepository.findByOwnerIdAndName(requestedByPlayerId, name)
                .orElseThrow(() -> new RuntimeException("Serveur '" + name + "' introuvable"));

        deleteServer(server.getId(), deleteData);
    }

    public void startServer(Long serverId, Long requestedByPlayerId) throws Exception {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur non trouvé : " + serverId));

        accessService.require(accessService.player(requestedByPlayerId), server, AccessService.Right.POWER);

        if (server.getStatus() == ServerStatus.RUNNING) {
            throw new RuntimeException("Le serveur est déjà en marche");
        }

        String quota = metricsService.diskQuotaProblem(server);
        if (quota != null) {
            throw new RuntimeException(quota);
        }

        log.info("Démarrage du serveur {} (name={})", serverId, server.getName());

        Map<String, Object> data = new HashMap<>();
        data.put("server_id", server.getId());

        server.setStatus(ServerStatus.STARTING);
        serverRepository.save(server);

        try {
            CompletableFuture<JsonNode> future = agentWebSocketHandler.sendCommand(
                    server.getNode().getId(), "start_server", data);
            requireSuccess(future.get());

            server.setStatus(ServerStatus.RUNNING);
            server.setLastStartedAt(LocalDateTime.now());
            serverRepository.save(server);

            // Réenregistrer dans Velocity (au cas où il aurait été retiré)
            try {
                velocityClient.registerServer(server.getVelocityName(), "127.0.0.1", server.getTunnelPort());
            } catch (Exception e) {
                log.warn("Erreur réenregistrement Velocity : {}", e.getMessage());
            }

            log.info("Serveur {} démarré", serverId);
        } catch (Exception e) {
            server.setStatus(ServerStatus.ERROR);
            serverRepository.save(server);
            throw new RuntimeException("Erreur démarrage : " + e.getMessage());
        }
    }

    public void stopServer(Long serverId, Long requestedByPlayerId) throws Exception {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur non trouvé : " + serverId));

        accessService.require(accessService.player(requestedByPlayerId), server, AccessService.Right.POWER);

        if (server.getStatus() == ServerStatus.STOPPED) {
            throw new RuntimeException("Le serveur est déjà arrêté");
        }

        log.info("Arrêt du serveur {} (name={})", serverId, server.getName());

        Map<String, Object> data = new HashMap<>();
        data.put("server_id", server.getId());

        server.setStatus(ServerStatus.STOPPING);
        serverRepository.save(server);

        // Désenregistrer de Velocity en premier
        try {
            velocityClient.unregisterServer(server.getVelocityName());
        } catch (Exception e) {
            log.warn("Erreur désenregistrement Velocity : {}", e.getMessage());
        }

        try {
            CompletableFuture<JsonNode> future = agentWebSocketHandler.sendCommand(
                    server.getNode().getId(), "stop_server", data);
            requireSuccess(future.get());

            server.setStatus(ServerStatus.STOPPED);
            server.setLastStoppedAt(LocalDateTime.now());
            serverRepository.save(server);

            log.info("Serveur {} arrêté", serverId);
        } catch (Exception e) {
            server.setStatus(ServerStatus.ERROR);
            serverRepository.save(server);
            throw new RuntimeException("Erreur arrêt : " + e.getMessage());
        }
    }

    public void restartServer(Long serverId, Long requestedByPlayerId) throws Exception {
        log.info("Redémarrage du serveur {}", serverId);
        stopServer(serverId, requestedByPlayerId);
        Thread.sleep(2000); // Attendre 2 secondes pour que tout se ferme proprement
        startServer(serverId, requestedByPlayerId);
    }

    // Helpers pour appeler par nom (utilisés par les endpoints "by-name")
    public void startServerByName(String name, Long requestedByPlayerId) throws Exception {
        Server server = serverRepository.findByOwnerIdAndName(requestedByPlayerId, name)
                .orElseThrow(() -> new RuntimeException("Serveur '" + name + "' introuvable"));
        startServer(server.getId(), requestedByPlayerId);
    }

    public void stopServerByName(String name, Long requestedByPlayerId) throws Exception {
        Server server = serverRepository.findByOwnerIdAndName(requestedByPlayerId, name)
                .orElseThrow(() -> new RuntimeException("Serveur '" + name + "' introuvable"));
        stopServer(server.getId(), requestedByPlayerId);
    }

    public void restartServerByName(String name, Long requestedByPlayerId) throws Exception {
        Server server = serverRepository.findByOwnerIdAndName(requestedByPlayerId, name)
                .orElseThrow(() -> new RuntimeException("Serveur '" + name + "' introuvable"));
        restartServer(server.getId(), requestedByPlayerId);
    }

    /** Suppression demandée par un joueur (propriétaire ou admin) */
    public void deleteServerFor(Long serverId, Long requestedByPlayerId) throws Exception {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur introuvable"));
        accessService.require(accessService.player(requestedByPlayerId), server, AccessService.Right.DELETE);
        deleteServer(serverId, true);
    }

    // ------------------------------------------------------------ console ----

    /** Commande dans la console du serveur (rcon-cli dans le conteneur) ; renvoie la réponse */
    public String console(Long serverId, Long requestedByPlayerId, String command) throws Exception {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur introuvable"));
        Player requester = accessService.player(requestedByPlayerId);
        accessService.require(requester, server, AccessService.Right.CONSOLE);
        if (command == null || command.isBlank()) {
            throw new RuntimeException("Commande vide");
        }
        log.info("Console {} par {} : {}", server.getVelocityName(), requester.getMinecraftUsername(), command);
        return runConsole(server, command);
    }

    private String runConsole(Server server, String command) throws Exception {
        if (server.getStatus() != ServerStatus.RUNNING) {
            throw new RuntimeException("Le serveur n'est pas en marche");
        }
        Node node = server.getNode();
        return sendConsole(node == null ? null : node.getId(), server.getId(), command);
    }

    // Sans entité : utilisable hors de la requête (pas de chargement paresseux)
    private String sendConsole(Long nodeId, Long serverId, String command) throws Exception {
        if (nodeId == null || !agentWebSocketHandler.isNodeOnline(nodeId)) {
            throw new RuntimeException("La machine qui héberge ce serveur est hors ligne");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("server_id", serverId);
        data.put("command", command);
        JsonNode result;
        try {
            result = agentWebSocketHandler.sendCommand(nodeId, "console", data)
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("pas de réponse de la machine (délai dépassé)");
        }
        requireSuccess(result);
        return result.path("result").path("output").asText("");
    }

    /**
     * Un joueur vient d'arriver sur un serveur (prévenu par le proxy), par la console :
     *  - le créateur du serveur et les admins y sont OP automatiquement ;
     *  - son rôle réseau (groupe LuckPerms) s'affiche en préfixe via une équipe
     *    vanilla "mcs_<rang>" (chat, Tab, au-dessus de la tête), sauf si le
     *    propriétaire l'a coupé (/mcs display). Marche sur Paper, Fabric, Forge, Vanilla.
     * Ne bloque pas le proxy.
     */
    public void onPlayerConnected(String velocityName, UUID uuid) {
        Optional<Server> server = serverRepository.findByVelocityName(velocityName);
        Optional<Player> player = playerRepository.findByMinecraftUuid(uuid);
        if (server.isEmpty() || player.isEmpty()) {
            return;
        }
        Server s = server.get();
        Player p = player.get();
        if (s.getStatus() != ServerStatus.RUNNING || s.getNode() == null) {
            return;
        }
        String name = p.getMinecraftUsername();
        List<String> commands = new ArrayList<>();
        if (AccessService.isOwner(p, s) || AccessService.isAdmin(p)) {
            commands.add("op " + name);
        }
        if (!Boolean.FALSE.equals(s.getShowNetworkRank())) {
            // Un seul titre : admin, sinon créateur du serveur, sinon rang réseau
            String team;
            String prefix;
            if (AccessService.isAdmin(p)) {
                team = teamName(1, p.getNetworkRank() == null ? "admin" : p.getNetworkRank());
                prefix = safeTextComponent(p.getNetworkPrefix());
            } else if (AccessService.isOwner(p, s)) {
                team = teamName(2, "createur");
                prefix = CREATOR_PREFIX;
            } else {
                boolean ranked = p.getNetworkRank() != null && !"default".equals(p.getNetworkRank());
                team = teamName(ranked ? 5 : 9, ranked ? p.getNetworkRank() : "default");
                prefix = safeTextComponent(p.getNetworkPrefix());
            }
            commands.add("team add " + team);
            commands.add("team modify " + team + " prefix " + prefix);
            commands.add("team join " + team + " " + name);
        }
        if (commands.isEmpty()) {
            return;
        }
        Long nodeId = s.getNode().getId();
        Long serverId = s.getId();
        CompletableFuture.runAsync(() -> {
            for (String cmd : commands) {
                try {
                    sendConsole(nodeId, serverId, cmd);
                } catch (Exception e) {
                    log.warn("Arrivée de {} sur {} : « {} » impossible : {}", name, velocityName, cmd, e.getMessage());
                    return;
                }
            }
            log.debug("Arrivée de {} sur {} : {}", name, velocityName, commands);
        });
    }

    /**
     * Équipe vanilla d'un titre : "mcs_1admin", "mcs_2createur", "mcs_5vip", "mcs_9default".
     * Le chiffre ordonne le Tab (trié par nom d'équipe) : admins, créateur, rangs, autres.
     */
    static String teamName(int order, String rank) {
        String r = rank == null ? "" : rank.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (r.isEmpty()) {
            r = "default";
        }
        return "mcs_" + order + r.substring(0, Math.min(10, r.length()));
    }

    /** Titre du créateur, discret (petites capitales), comme les préfixes par défaut du réseau */
    static final String CREATOR_PREFIX = "{\"text\":\"\\u1d04\\u0280\\u1d07\\u1d00\\u1d1b\\u1d07\\u1d1c\\u0280 \",\"color\":\"gold\"}";

    // Caractères non ASCII échappés (\\uXXXX) : la commande console reste en ASCII
    private static final com.fasterxml.jackson.databind.ObjectMapper TEXT_MAPPER =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .enable(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII)
                    .build();

    /**
     * Préfixe en composant texte JSON, validé et réécrit sur une ligne (il part dans
     * une commande console). Invalide ou absent : préfixe vide.
     */
    static String safeTextComponent(String json) {
        if (json == null || json.isBlank()) {
            return "\"\"";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = TEXT_MAPPER.readTree(json);
            if (node == null || !(node.isObject() || node.isTextual() || node.isArray())) {
                return "\"\"";
            }
            String compact = TEXT_MAPPER.writeValueAsString(node);
            return compact.length() > 512 ? "\"\"" : compact;
        } catch (Exception e) {
            return "\"\"";
        }
    }

    /** Coupe ou remet l'affichage des rôles réseau (propriétaire, admins) */
    public void setShowNetworkRank(Long serverId, Long requestedByPlayerId, boolean enabled) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur introuvable"));
        accessService.require(accessService.player(requestedByPlayerId), server, AccessService.Right.VISIBILITY);
        server.setShowNetworkRank(enabled);
        serverRepository.save(server);
        if (enabled || server.getStatus() != ServerStatus.RUNNING || server.getNode() == null) {
            return;
        }
        // Coupé : on retire nos équipes du serveur (les joueurs gardent leur nom normal)
        Long nodeId = server.getNode().getId();
        Long id = server.getId();
        Set<String> teams = new LinkedHashSet<>();
        teams.add("mcs_default"); // noms du lot 19
        teams.add(teamName(2, "createur"));
        teams.add(teamName(9, "default"));
        teams.add(teamName(1, "admin"));
        for (String rank : playerRepository.findDistinctNetworkRanks()) {
            teams.add("mcs_" + rank.replaceAll("[^a-z0-9_]", ""));
            teams.add(teamName(1, rank));
            teams.add(teamName(5, rank));
        }
        CompletableFuture.runAsync(() -> {
            for (String t : teams) {
                try {
                    sendConsole(nodeId, id, "team remove " + t);
                } catch (Exception e) {
                    log.debug("team remove {} : {}", t, e.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------ move ----

    /**
     * Amène un joueur connecté au réseau sur ce serveur, une seule fois : il reçoit
     * un laissez-passer d'une minute, consommé à son arrivée. S'il repart, il ne
     * peut pas revenir seul (sauf s'il a un accès par ailleurs).
     */
    public void movePlayer(Long serverId, Long requestedByPlayerId, String username) throws Exception {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur introuvable"));
        Player requester = accessService.player(requestedByPlayerId);
        accessService.require(requester, server, AccessService.Right.MOVE);
        if (server.getStatus() != ServerStatus.RUNNING) {
            throw new RuntimeException("Le serveur n'est pas en marche");
        }
        Player target = accessService.playerByName(username);
        accessService.grantPass(server.getId(), target.getMinecraftUuid());
        velocityClient.connectPlayer(target.getMinecraftUuid(), server.getVelocityName());
        log.info("{} amène {} sur {}", requester.getMinecraftUsername(), target.getMinecraftUsername(),
                server.getVelocityName());
    }

    /** Serveurs des autres où le joueur a un rôle, et serveurs hébergés sur ses machines */
    @Transactional(readOnly = true)
    public Map<String, Object> getOtherServers(Long playerId) {
        Player p = accessService.player(playerId);
        Map<Long, Server> servers = new LinkedHashMap<>();
        for (Server s : accessService.sharedWith(p)) {
            servers.put(s.getId(), s);
        }
        for (Server s : serverRepository.findHostedByPlayerId(playerId)) {
            if (!AccessService.isOwner(p, s)) {
                servers.put(s.getId(), s);
            }
        }
        List<Map<String, Object>> shared = new ArrayList<>();
        for (Server s : servers.values()) {
            Map<String, Object> m = accessService.describeFor(p, s);
            m.put("hosted", AccessService.isHost(p, s));
            shared.add(m);
        }
        return Map.of("shared", shared);
    }

    public List<Map<String, Object>> getServersByOwnerId(Long ownerId) {
        return serverRepository.findByOwnerId(ownerId).stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("velocityName", s.getVelocityName());
                    m.put("displayName", s.getDisplayName());
                    m.put("status", s.getStatus().name());
                    m.put("serverType", s.getServerType().name());
                    m.put("minecraftVersion", s.getMinecraftVersion());
                    m.put("port", s.getTunnelPort());
                    m.put("allocatedRamMb", s.getAllocatedRamMb());
                    m.put("allocatedCpuCores", s.getAllocatedCpuCores());
                    m.put("isPublic", Boolean.TRUE.equals(s.getIsPublic()));
                    return m;
                })
                .toList();
    }

    public Map<String, Object> getServerByOwnerAndName(Long ownerId, String name) {
        Server s = serverRepository.findByOwnerIdAndName(ownerId, name)
                .orElseThrow(() -> new RuntimeException("Serveur introuvable"));

        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("velocityName", s.getVelocityName());
        m.put("displayName", s.getDisplayName());
        m.put("status", s.getStatus().name());
        m.put("ownerUsername", s.getOwner().getMinecraftUsername());
        return m;
    }

    public Map<String, Object> getServerByUsernameAndName(String username, String name) {
        Player owner = playerRepository.findByMinecraftUsername(username)
                .orElseThrow(() -> new RuntimeException("Joueur introuvable : " + username));

        return getServerByOwnerAndName(owner.getId(), name);
    }

    public List<Map<String, Object>> getActiveServers() {
        return serverRepository.findByStatus(ServerStatus.RUNNING).stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("velocityName", s.getVelocityName());
                    m.put("host", "127.0.0.1");
                    m.put("port", s.getTunnelPort());
                    return m;
                })
                .toList();
    }

    public long getTotalRamUsedByOwnerId(Long ownerId) {
        return serverRepository.sumAllocatedRamByOwnerId(ownerId);
    }

    public long countServersByOwnerId(Long ownerId) {
        return serverRepository.countByOwnerId(ownerId);
    }

    public long getTotalCpuUsedByOwnerId(Long ownerId) {
        return serverRepository.sumAllocatedCpuByOwnerId(ownerId);
    }
}