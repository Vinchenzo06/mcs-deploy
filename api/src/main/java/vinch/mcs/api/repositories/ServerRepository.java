package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long> {

    List<Server> findByStatus(ServerStatus status);

    @Query("SELECT s.tunnelPort FROM Server s WHERE s.node.id = :nodeId AND s.tunnelPort IS NOT NULL")
    List<Integer> findUsedPortsByNodeId(Long nodeId);

    @Query("SELECT s FROM Server s WHERE s.owner.id = :playerId")
    List<Server> findByOwnerId(Long playerId);

    @Query("SELECT COUNT(s) FROM Server s WHERE s.owner.id = :playerId")
    long countByOwnerId(Long playerId);

    @Query("SELECT COALESCE(SUM(s.allocatedRamMb), 0) FROM Server s WHERE s.owner.id = :playerId")
    long sumAllocatedRamByOwnerId(Long playerId);

    @Query("SELECT COALESCE(SUM(s.allocatedCpuCores), 0) FROM Server s WHERE s.owner.id = :playerId")
    long sumAllocatedCpuByOwnerId(Long playerId);

    Optional<Server> findByName(String name);

    Optional<Server> findByVelocityName(String velocityName);

    Optional<Server> findByOwnerIdAndName(Long ownerId, String name);
}