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
    private final BackupService backupService;

    @Transactional
    public CreateServerResponse createServer(CreateServerRequest request) throws Exception {
        return createServer(request, null);
    }

    /**
     * restore : recréation d'un serveur supprimé à partir de sa sauvegarde (lot 31) ;
     * les données sont restaurées par l'agent avant le premier démarrage. La machine
     * est imposée (celle de la sauvegarde) mais doit avoir la place.
     */
    @Transactional
    public CreateServerResponse createServer(CreateServerRequest request, Map<String, Object> restore) throws Exception {
        return createServer(request, restore, null);
    }

    /**
     * restoringTag : ancien id du serveur supprimé qu'on recrée (sa place, occupée par
     * ses sauvegardes, lui revient).
     */
    @Transactional
    public CreateServerResponse createServer(CreateServerRequest request, Map<String, Object> restore,
                                             Long restoringTag) throws Exception {
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
        // Un serveur supprimé dont les sauvegardes sont encore gardées occupe une place :
        // créer au-delà supprime les sauvegardes du plus ancien (après confirmation)
        LinkedHashMap<Long, List<Backup>> held = backupService.heldDeleted(owner.getId());
        if (restoringTag != null) {
            held.remove(restoringTag);
        }
        List<List<Backup>> toRelease = new ArrayList<>();
        Iterator<List<Backup>> oldest = held.values().iterator();
        long used = count + held.size();
        while (used >= owner.getMaxServers() && oldest.hasNext()) {
            toRelease.add(oldest.next());
            used--;
        }
        if (!toRelease.isEmpty()) {
            if (!Boolean.TRUE.equals(request.getReplaceDeleted())) {
                StringBuilder names = new StringBuilder();
                for (List<Backup> g : toRelease) {
                    names.append(names.length() == 0 ? "" : ", ").append(BackupService.describeHeld(g));
                }
                throw new RuntimeException("Emplacement occupé : ton serveur supprimé " + names
                        + " compte encore dans tes " + owner.getMaxServers() + " serveur(s). "
                        + "Créer celui-ci supprimera ses sauvegardes pour de bon.");
            }
            for (List<Backup> g : toRelease) {
                backupService.releaseDeleted(g);
            }
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
        if (restore != null) {
            if (!agentWebSocketHandler.isNodeOnline(node.getId())) {
                throw new RuntimeException("La machine de cette sauvegarde est hors ligne : réessaie plus tard.");
            }
            if (freeRamAfter(node, request) == null) {
                throw new RuntimeException("La machine de cette sauvegarde n'a plus la place pour ce serveur "
                        + "(RAM, CPU ou disque).");
            }
        }

        // Disque proportionnel à la RAM : 10 % de la RAM prêtée par la machine
        // donne 10 % de son disque prêté (le joueur ne choisit pas son disque)
        int storageMb = diskQuotaFor(node, request.getRamMb());
        makeDiskRoom(node, storageMb);

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
        // Dépôt de sauvegarde du serveur au central (le serveur de sauvegarde le connaît en 2 min)
        backupService.repoFor(server.getId());
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
        if (restore != null) {
            data.put("restore", restore);
        }

        try {
            CompletableFuture<JsonNode> future = agentWebSocketHandler.sendCommand(
                    node.getId(), "create_server", data, restore != null ? 3 * 3600 : 300);
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

    /** Serveurs supprimés du joueur dont des sauvegardes occupent encore une place */
    public long deletedHeld(Long ownerId) {
        return backupService.heldDeleted(ownerId).size();
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
    // Serveurs qui occupent de la RAM et du CPU sur leur machine (les autres sont arrêtés)
    private static final Set<ServerStatus> ACTIVE = EnumSet.of(ServerStatus.RUNNING, ServerStatus.STARTING,
            ServerStatus.CREATING, ServerStatus.MIGRATING);

    /** Copie d'un serveur arrêté dont le rangement est à jour : peut quitter la machine */
    static boolean evictable(Server s) {
        return "CLEAN".equals(s.getParkState()) && s.getStatus() == ServerStatus.STOPPED;
    }

    /** Le serveur a-t-il une copie sur sa machine ? */
    static boolean hasCopy(Server s) {
        return !"COLD".equals(s.getParkState());
    }

    private Integer freeRamAfter(Node node, CreateServerRequest request) {
        return freeRamAfter(node, request, null);
    }

    /**
     * Place libre sur une machine pour un serveur de plus (null : elle ne peut pas
     * l'accueillir). RAM et CPU : seulement les serveurs qui tournent (lot 33 : les
     * serveurs arrêtés n'occupent rien). Disque : les copies présentes, sauf celles qui
     * peuvent partir (rangées et arrêtées), libérées au besoin (makeDiskRoom).
     */
    private Integer freeRamAfter(Node node, CreateServerRequest request, Long excludeServerId) {
        if (node.getTotalRamMb() == null || node.getTotalRamMb() <= 0) {
            return null; // capacité inconnue (agent trop ancien) : on n'y place rien
        }
        List<Server> onNode = serverRepository.findByNodeId(node.getId());
        int ram = 0, cpu = 0, disk = 0, evictableDisk = 0;
        for (Server s : onNode) {
            if (excludeServerId != null && excludeServerId.equals(s.getId())) {
                continue;
            }
            if (ACTIVE.contains(s.getStatus())) {
                ram += containerRamMb(s.getAllocatedRamMb());
                cpu += s.getAllocatedCpuCores();
            }
            if (hasCopy(s)) {
                if (evictable(s)) {
                    evictableDisk += s.getAllocatedStorageMb();
                } else {
                    disk += s.getAllocatedStorageMb();
                }
            }
        }
        int freeRam = usable(node.getTotalRamMb()) - ram - containerRamMb(request.getRamMb());
        int storage = diskQuotaFor(node, request.getRamMb());
        boolean cpuOk = cpu + request.getCpuCores() <= Math.max(1, usable(node.getCpuCores()));
        boolean diskOk = node.getTotalStorageMb() == null || node.getTotalStorageMb() <= 0
                || disk + storage <= usable(node.getTotalStorageMb());
        boolean hostDiskOk = node.getHostDiskFreeMb() == null
                || node.getHostDiskFreeMb() + evictableDisk - storage >= DISK_FREE_MARGIN_MB;
        return (freeRam >= 0 && cpuOk && diskOk && hostDiskOk) ? freeRam : null;
    }

    /** Le serveur peut-il démarrer sur sa machine (RAM et CPU libres) ? */
    public boolean hasRunRoom(Server server) {
        Node node = server.getNode();
        if (node == null || node.getTotalRamMb() == null || node.getTotalRamMb() <= 0) {
            return false;
        }
        int ram = 0, cpu = 0;
        for (Server s : serverRepository.findByNodeId(node.getId())) {
            if (!s.getId().equals(server.getId()) && ACTIVE.contains(s.getStatus())) {
                ram += containerRamMb(s.getAllocatedRamMb());
                cpu += s.getAllocatedCpuCores();
            }
        }
        return ram + containerRamMb(server.getAllocatedRamMb()) <= usable(node.getTotalRamMb())
                && cpu + server.getAllocatedCpuCores() <= Math.max(1, usable(node.getCpuCores()));
    }

    /**
     * Fait de la place sur le disque d'une machine pour "neededMb" : les copies rangées
     * des serveurs arrêtés partent, les plus anciennement arrêtées d'abord (elles restent
     * au central et repartiront sur une machine libre à leur prochain démarrage).
     */
    public void makeDiskRoom(Node node, int neededMb) {
        List<Server> onNode = new ArrayList<>(serverRepository.findByNodeId(node.getId()));
        int disk = 0;
        for (Server s : onNode) {
            if (hasCopy(s) && !evictable(s)) {
                disk += s.getAllocatedStorageMb();
            }
        }
        int limit = node.getTotalStorageMb() == null || node.getTotalStorageMb() <= 0
                ? Integer.MAX_VALUE : usable(node.getTotalStorageMb());
        long hostFree = node.getHostDiskFreeMb() == null ? Long.MAX_VALUE / 2 : node.getHostDiskFreeMb();
        // Copies présentes qui peuvent partir, les plus anciennement arrêtées d'abord
        List<Server> candidates = onNode.stream().filter(x -> hasCopy(x) && evictable(x))
                .sorted(Comparator.comparing((Server x) -> x.getLastStoppedAt() == null ? LocalDateTime.MIN : x.getLastStoppedAt()))
                .toList();
        int keptEvictable = candidates.stream().mapToInt(Server::getAllocatedStorageMb).sum();
        for (Server s : candidates) {
            // Place réellement disponible si les copies restantes restent
            boolean fits = disk + keptEvictable + neededMb <= limit && hostFree - neededMb >= DISK_FREE_MARGIN_MB;
            if (fits) {
                return;
            }
            if (evictCopy(s)) {
                keptEvictable -= s.getAllocatedStorageMb();
                hostFree += s.getAllocatedStorageMb();
            }
        }
    }

    /** Retire la copie d'un serveur rangé de sa machine (il reste au central) */
    public boolean evictCopy(Server s) {
        if (!evictable(s) || s.getNode() == null || !agentWebSocketHandler.isNodeOnline(s.getNode().getId())
                || backupService.isBusy(s.getId())) {
            return false;
        }
        try {
            JsonNode r = agentWebSocketHandler.sendCommand(s.getNode().getId(), "delete_server",
                    Map.of("server_id", s.getId(), "delete_data", true)).get(6, java.util.concurrent.TimeUnit.MINUTES);
            if (r != null && r.has("success") && !r.get("success").asBoolean()) {
                return false;
            }
        } catch (Exception e) {
            log.warn("Copie de {} : retrait impossible : {}", s.getVelocityName(), e.getMessage());
            return false;
        }
        s.setParkState("COLD");
        serverRepository.save(s);
        log.info("Copie de {} retirée de la machine {} (rangée au central)", s.getVelocityName(), s.getNode().getId());
        return true;
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
    // ------------------------------------------------------------ placement (lot 32) ----

    private static CreateServerRequest sizing(int ramMb, int cpuCores) {
        CreateServerRequest r = new CreateServerRequest();
        r.setRamMb(ramMb);
        r.setCpuCores(cpuCores);
        return r;
    }

    /** La machine peut-elle accueillir un serveur de cette taille (en plus des siens) ? */
    public boolean hasRoom(Node node, int ramMb, int cpuCores) {
        return node != null && !Boolean.TRUE.equals(node.getIsRevoked()) && node.getPortStart() != null
                && freeRamAfter(node, sizing(ramMb, cpuCores)) != null;
    }

    /**
     * Machine en ligne qui a la place (la plus de RAM libre), hors "exclude", dans la
     * région demandée (null : toutes) ; null si aucune.
     */
    public Node pickNode(int ramMb, int cpuCores, Long exclude, String region) {
        Node best = null;
        int bestFree = -1;
        for (Node n : nodeRepository.findAll()) {
            if (!Boolean.TRUE.equals(n.getIsOnline()) || Boolean.TRUE.equals(n.getIsRevoked())
                    || n.getPortStart() == null || n.getPortEnd() == null
                    || (exclude != null && exclude.equals(n.getId()))
                    || (region != null && !region.equalsIgnoreCase(n.getRegion()))
                    || !agentWebSocketHandler.isNodeOnline(n.getId())) {
                continue;
            }
            Integer free = freeRamAfter(n, sizing(ramMb, cpuCores));
            if (free != null && free > bestFree) {
                best = n;
                bestFree = free;
            }
        }
        return best;
    }

    /** Port libre sur la machine */
    public int freePortOn(Node node) {
        return allocatePort(node);
    }

    /** Quota disque d'un serveur de cette RAM sur cette machine */
    public static int quotaOn(Node node, int ramMb) {
        return diskQuotaFor(node, ramMb);
    }

    /** Commande create_server pour un serveur existant (déplacement vers une autre machine) */
    public static Map<String, Object> createData(Server server, int port, int storageMb, Map<String, Object> restore) {
        Map<String, Object> data = new HashMap<>();
        data.put("server_id", server.getId());
        data.put("type", server.getServerType().name());
        data.put("version", server.getMinecraftVersion());
        data.put("port", port);
        data.put("ram_mb", server.getAllocatedRamMb());
        data.put("cpu_cores", server.getAllocatedCpuCores());
        data.put("storage_mb", storageMb);
        data.put("owner_name", server.getOwner().getMinecraftUsername());
        if (restore != null) {
            data.put("restore", restore);
        }
        return data;
    }

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
        deleteServer(serverId, deleteData, false);
    }

    /**
     * skipAgent : la machine n'est pas contactée (hors ligne : l'appelant a prévu la
     * suppression de la copie à son retour). Serveur sans copie sur sa machine (COLD) :
     * rien à supprimer là-bas.
     */
    public void deleteServer(Long serverId, boolean deleteData, boolean skipAgent) throws Exception {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("Serveur non trouvé : " + serverId));

        log.info("Suppression définitive du serveur {} (name={} velocityName={})",
                serverId, server.getName(), server.getVelocityName());

        // La machine doit être joignable, sinon son conteneur resterait orphelin.
        // Exception : machine révoquée (ou absente), qui ne reviendra jamais.
        Node node = server.getNode();
        boolean nodeGone = node == null || Boolean.TRUE.equals(node.getIsRevoked())
                || "COLD".equals(server.getParkState()) || skipAgent;
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

        // 3. Les sauvegardes survivent au serveur le temps de leur durée de vie
        try {
            backupService.onServerDeleting(serverId);
        } catch (Exception e) {
            log.warn("Sauvegardes du serveur {} : {}", serverId, e.getMessage());
        }

        // 4. VRAIE suppression en base
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
        if (server.getStatus() == ServerStatus.MIGRATING) {
            throw new RuntimeException("Le serveur est en train de changer de machine : il redémarrera tout seul.");
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
            // Un seul titre : admin, sinon owner (créateur de CE serveur), sinon rang réseau
            // (host pour les propriétaires de machine, premium, vip...)
            String team;
            String prefix;
            if (AccessService.isAdmin(p)) {
                team = teamName(1, p.getNetworkRank() == null ? "admin" : p.getNetworkRank());
                prefix = safeTextComponent(p.getNetworkPrefix());
            } else if (AccessService.isOwner(p, s)) {
                team = teamName(2, "owner");
                prefix = OWNER_PREFIX;
            } else {
                boolean ranked = p.getNetworkRank() != null && !"default".equals(p.getNetworkRank());
                team = teamName(ranked ? 5 : 9, ranked ? p.getNetworkRank() : "default");
                prefix = safeTextComponent(p.getNetworkPrefix());
            }
            commands.add("team add " + team);
            commands.add("team modify " + team + " color " + teamColor(p.getNameColor()));
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
     * Équipe vanilla d'un titre : "mcs_1admin", "mcs_2owner", "mcs_5host", "mcs_5vip", "mcs_9default".
     * Le chiffre ordonne le Tab (trié par nom d'équipe) : admins, owner, rangs, autres.
     */
    static String teamName(int order, String rank) {
        String r = rank == null ? "" : rank.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (r.isEmpty()) {
            r = "default";
        }
        return "mcs_" + order + r.substring(0, Math.min(10, r.length()));
    }

    private static final Set<String> TEAM_COLORS = Set.of("black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red",
            "light_purple", "yellow", "white");

    /** Couleur d'équipe valide ("&a", "green", "GREEN"...) ; gris par défaut (le blanc fatigue) */
    public static String teamColor(String color) {
        if (color == null) {
            return "gray";
        }
        String c = color.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String legacy = switch (c) {
            case "&0" -> "black"; case "&1" -> "dark_blue"; case "&2" -> "dark_green"; case "&3" -> "dark_aqua";
            case "&4" -> "dark_red"; case "&5" -> "dark_purple"; case "&6" -> "gold"; case "&7" -> "gray";
            case "&8" -> "dark_gray"; case "&9" -> "blue"; case "&a" -> "green"; case "&b" -> "aqua";
            case "&c" -> "red"; case "&d" -> "light_purple"; case "&e" -> "yellow"; case "&f" -> "white";
            default -> c;
        };
        return TEAM_COLORS.contains(legacy) ? legacy : "gray";
    }

    /** Titre du créateur du serveur, discret (petites capitales) : "ᴏᴡɴᴇʀ" */
    static final String OWNER_PREFIX = "{\"text\":\"\\u1d0f\\u1d21\\u0274\\u1d07\\u0280 \",\"color\":\"gold\"}";

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
        teams.add(teamName(2, "createur")); // nom du lot 20
        teams.add(teamName(2, "owner"));
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