package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.PendingDeletion;

import java.util.List;

@Repository
public interface PendingDeletionRepository extends JpaRepository<PendingDeletion, Long> {

    List<PendingDeletion> findByNodeId(Long nodeId);

    boolean existsByNodeIdAndServerId(Long nodeId, Long serverId);
}
