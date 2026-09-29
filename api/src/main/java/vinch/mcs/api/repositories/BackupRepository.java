package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.Backup;

import java.util.List;
import java.util.Optional;

@Repository
public interface BackupRepository extends JpaRepository<Backup, Long> {

    List<Backup> findByServerIdOrderByCreatedAtDesc(Long serverId);

    Optional<Backup> findFirstByServerIdAndIsCompleteTrueOrderByCreatedAtDesc(Long serverId);

    long countByServerId(Long serverId);

    List<Backup> findTop15ByServerIdOrderByCreatedAtDesc(Long serverId);

    Optional<Backup> findFirstByServerIdAndStatusOrderByCreatedAtDesc(Long serverId, String status);

    Optional<Backup> findFirstByServerIdOrderByCreatedAtDesc(Long serverId);
}