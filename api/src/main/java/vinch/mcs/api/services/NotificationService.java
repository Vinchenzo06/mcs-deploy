package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.entities.PlayerNotification;
import vinch.mcs.api.repositories.PlayerNotificationRepository;
import vinch.mcs.api.repositories.PlayerRepository;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Messages aux joueurs (serveur déplacé, machine hors ligne...) : envoyés tout de
 * suite s'ils sont connectés, sinon montrés à leur prochaine connexion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final PlayerNotificationRepository notificationRepository;
    private final PlayerRepository playerRepository;
    private final VelocityClient velocityClient;

    @Transactional
    public void notify(Long playerId, String message) {
        if (playerId == null) {
            return;
        }
        String text = message.length() > 600 ? message.substring(0, 600) : message;
        PlayerNotification n = notificationRepository.save(PlayerNotification.builder()
                .playerId(playerId).message(text).build());
        Optional<Player> p = playerRepository.findById(playerId);
        if (p.isPresent() && p.get().getMinecraftUuid() != null
                && velocityClient.notifyPlayer(p.get().getMinecraftUuid(), text)) {
            n.setDeliveredAt(LocalDateTime.now());
            notificationRepository.save(n);
        }
        log.info("Message pour {} : {}", p.map(Player::getMinecraftUsername).orElse("#" + playerId), text);
    }

    /** Messages pas encore vus (marqués vus) : appelé par le proxy à la connexion */
    @Transactional
    public List<Map<String, Object>> pending(Long playerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlayerNotification n : notificationRepository.findByPlayerIdAndDeliveredAtIsNullOrderByCreatedAtAsc(playerId)) {
            out.add(Map.of("message", n.getMessage(), "createdAt", n.getCreatedAt().toString()));
            n.setDeliveredAt(LocalDateTime.now());
            notificationRepository.save(n);
        }
        return out;
    }
}
