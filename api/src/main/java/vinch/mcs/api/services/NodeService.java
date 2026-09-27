package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.dto.CreateNodeRequest;
import vinch.mcs.api.dto.CreateNodeResponse;
import vinch.mcs.api.entities.Node;
import vinch.mcs.api.entities.Volunteer;
import vinch.mcs.api.repositories.NodeRepository;
import vinch.mcs.api.repositories.VolunteerRepository;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class NodeService {

    private final NodeRepository nodeRepository;
    private final VolunteerRepository volunteerRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public CreateNodeResponse createNode(CreateNodeRequest request) {
        Volunteer volunteer = volunteerRepository.findById(request.getVolunteerId())
                .orElseThrow(() -> new RuntimeException("Volontaire non trouvé : " + request.getVolunteerId()));

        // Génère un token aléatoire de 32 bytes en base64
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getEncoder().withoutPadding().encodeToString(tokenBytes);

        // Hash du token pour stockage
        String tokenHash = hashToken(token);

        Node node = Node.builder()
                .volunteer(volunteer)
                .nodeTokenHash(tokenHash)
                .region(request.getRegion())
                .hostname(request.getHostname())
                .totalRamMb(request.getTotalRamMb())
                .totalStorageMb(request.getTotalStorageMb())
                .cpuCores(request.getCpuCores())
                .isOnline(false)
                .isRevoked(false)
                .build();

        node = nodeRepository.save(node);

        log.info("Nouveau node créé : id={} pour volontaire={}", node.getId(), volunteer.getId());

        return CreateNodeResponse.builder()
                .nodeId(node.getId())
                .nodeToken(token)
                .region(node.getRegion())
                .build();
    }

    public Node authenticateNode(String token) {
        String tokenHash = hashToken(token);
        return nodeRepository.findByNodeTokenHash(tokenHash)
                .filter(node -> !node.getIsRevoked())
                .orElse(null);
    }

    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Erreur de hashage", e);
        }
    }
}