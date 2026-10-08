package vinch.mcs.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vinch.mcs.api.entities.Player;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByMinecraftUuid(UUID minecraftUuid);

    Optional<Player> findByMinecraftUsername(String minecraftUsername);

    Optional<Player> findFirstByMinecraftUsernameIgnoreCase(String minecraftUsername);

    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT p.networkRank FROM Player p WHERE p.networkRank IS NOT NULL")
    java.util.List<String> findDistinctNetworkRanks();

    boolean existsByMinecraftUuid(UUID minecraftUuid);

    /** Admins (avertis des abus détectés par les machines, lot 37) */
    java.util.List<Player> findByRole(vinch.mcs.api.entities.PlayerRole role);
}