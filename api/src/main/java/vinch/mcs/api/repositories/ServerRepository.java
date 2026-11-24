package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long> {

    List<Server> findByStatus(ServerStatus status);

    List<Server> findByNodeId(Long nodeId);

    @Query("SELECT s.tunnelPort FROM Server s WHERE s.node.id = :nodeId AND s.tunnelPort IS NOT NULL")
    List<Integer> findUsedPortsByNodeId(Long nodeId);

    // Les ports sont uniques sur tout le VPS (un port = un tunnel rathole)
    @Query("SELECT s.tunnelPort FROM Server s WHERE s.tunnelPort IS NOT NULL")
    List<Integer> findAllUsedPorts();

    @Query("SELECT s FROM Server s WHERE s.owner.id = :playerId")
    List<Server> findByOwnerId(Long playerId);

    /** Serveurs de la team IMG (lot 52) */
    List<Server> findByIsTeamTrue();

    /** Page Serveur du site : serveurs de la team + serveurs protégés (lobby IMG), lot 56 */
    List<Server> findByIsTeamTrueOrIsProtectedTrue();

    // Lot 76 : les serveurs à la demande (pool) ne comptent dans le quota de personne
    @Query("SELECT COUNT(s) FROM Server s WHERE s.owner.id = :playerId AND s.pool IS NULL")
    long countByOwnerId(Long playerId);

    @Query("SELECT COALESCE(SUM(s.allocatedRamMb), 0) FROM Server s WHERE s.owner.id = :playerId AND s.pool IS NULL")
    long sumAllocatedRamByOwnerId(Long playerId);

    @Query("SELECT COALESCE(SUM(s.allocatedCpuCores), 0) FROM Server s WHERE s.owner.id = :playerId AND s.pool IS NULL")
    long sumAllocatedCpuByOwnerId(Long playerId);

    Optional<Server> findByName(String name);

    // Le nom n'est unique que par propriétaire
    List<Server> findAllByName(String name);

    @Query("SELECT s FROM Server s WHERE s.node.ownerPlayer.id = :playerId")
    List<Server> findHostedByPlayerId(Long playerId);

    Optional<Server> findByVelocityName(String velocityName);

    Optional<Server> findByOwnerIdAndName(Long ownerId, String name);

    /** Lot 76 : serveurs d'un groupe à la demande */
    List<Server> findByPoolOrderByIdAsc(String pool);

    List<Server> findByPoolIsNotNull();

    /** Lot 76 : un joueur vient d'entrer sur ce serveur */
    @Modifying
    @Transactional
    @Query("UPDATE Server s SET s.lastJoinedAt = :t WHERE s.id = :id")
    int touchJoined(@Param("id") Long id, @Param("t") LocalDateTime t);
}
