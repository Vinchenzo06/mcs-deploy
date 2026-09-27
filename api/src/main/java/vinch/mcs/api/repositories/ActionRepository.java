package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.Action;
import vinch.mcs.api.entities.ActionStatus;

import java.util.List;

@Repository
public interface ActionRepository extends JpaRepository<Action, Long> {

    Page<Action> findByServerIdOrderByCreatedAtDesc(Long serverId, Pageable pageable);

    List<Action> findByPlayerIdOrderByCreatedAtDesc(Long playerId);

    List<Action> findByStatus(ActionStatus status);
}