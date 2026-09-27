package vinch.mcs.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.entities.PlayerRole;
import vinch.mcs.api.repositories.PlayerRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerService {

    private final PlayerRepository playerRepository;

    @Transactional
    public Player loginOrCreatePlayer(UUID uuid, String username) {
        return playerRepository.findByMinecraftUuid(uuid)
                .map(player -> updateExistingPlayer(player, username))
                .orElseGet(() -> createNewPlayer(uuid, username));
    }

    private Player updateExistingPlayer(Player player, String currentUsername) {
        boolean changed = false;

        if (!player.getMinecraftUsername().equals(currentUsername)) {
            log.info("Le joueur {} a changé de pseudo : {} -> {}",
                    player.getMinecraftUuid(), player.getMinecraftUsername(), currentUsername);
            player.setMinecraftUsername(currentUsername);
            changed = true;
        }

        player.setLastSeenAt(LocalDateTime.now());

        return playerRepository.save(player);
    }

    private Player createNewPlayer(UUID uuid, String username) {
        log.info("Création d'un nouveau joueur : {} ({})", username, uuid);

        Player player = Player.builder()
                .minecraftUuid(uuid)
                .minecraftUsername(username)
                .role(PlayerRole.PLAYER)
                .maxServers(1)
                .totalStorageMb(5000)
                .isBanned(false)
                .build();

        return playerRepository.save(player);
    }

    public Player getPlayerByUuid(UUID uuid) {
        return playerRepository.findByMinecraftUuid(uuid)
                .orElseThrow(() -> new RuntimeException("Joueur non trouvé : " + uuid));
    }
    public Optional<Player> findByUuid(UUID uuid) {
        return playerRepository.findByMinecraftUuid(uuid);
    }

    public Player updateLimits(UUID uuid, int maxServers, int totalRamMb, int totalCpuCores) {
        Player player = playerRepository.findByMinecraftUuid(uuid)
                .orElseThrow(() -> new RuntimeException("Joueur introuvable : " + uuid));

        player.setMaxServers(maxServers);
        player.setTotalRamMb(totalRamMb);
        player.setTotalCpuCores(totalCpuCores);

        return playerRepository.save(player);
    }

    public Player getPlayerById(Long id) {
        return playerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Joueur introuvable : " + id));
    }

}