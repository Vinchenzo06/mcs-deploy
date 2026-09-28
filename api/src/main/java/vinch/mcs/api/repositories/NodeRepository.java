package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.Node;

import java.util.List;
import java.util.Optional;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {

    Optional<Node> findByNodeTokenHash(String nodeTokenHash);

    List<Node> findByIsOnlineTrueAndIsRevokedFalse();

    List<Node> findByRegionAndIsOnlineTrue(String region);

    List<Node> findByVolunteerId(Long volunteerId);

    // Fin de la dernière plage attribuée (les plages révoquées ne sont jamais réutilisées)
    @Query("SELECT MAX(n.portEnd) FROM Node n")
    Integer findMaxPortEnd();
}