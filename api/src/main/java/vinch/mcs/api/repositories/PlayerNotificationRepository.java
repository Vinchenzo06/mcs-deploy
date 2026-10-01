package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.PlayerNotification;

import java.util.List;

@Repository
public interface PlayerNotificationRepository extends JpaRepository<PlayerNotification, Long> {

    List<PlayerNotification> findByPlayerIdAndDeliveredAtIsNullOrderByCreatedAtAsc(Long playerId);
}
