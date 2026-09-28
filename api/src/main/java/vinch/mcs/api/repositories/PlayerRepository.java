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

    boolean existsByMinecraftUuid(UUID minecraftUuid);
}