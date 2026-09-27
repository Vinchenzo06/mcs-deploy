package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.ServerCollaborator;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerCollaboratorRepository extends JpaRepository<ServerCollaborator, Long> {

    List<ServerCollaborator> findByServerId(Long serverId);

    List<ServerCollaborator> findByPlayerId(Long playerId);

    Optional<ServerCollaborator> findByServerIdAndPlayerId(Long serverId, Long playerId);

    void deleteByServerIdAndPlayerId(Long serverId, Long playerId);
}