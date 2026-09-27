package vinch.mcs.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vinch.mcs.api.entities.Player;
import vinch.mcs.api.entities.PlayerRole;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerResponse {

    private Long id;
    private UUID uuid;
    private String username;
    private PlayerRole role;
    private Integer maxServers;
    private Integer totalStorageMb;
    private Boolean isBanned;
    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;

    public static PlayerResponse fromEntity(Player player) {
        return PlayerResponse.builder()
                .id(player.getId())
                .uuid(player.getMinecraftUuid())
                .username(player.getMinecraftUsername())
                .role(player.getRole())
                .maxServers(player.getMaxServers())
                .totalStorageMb(player.getTotalStorageMb())
                .isBanned(player.getIsBanned())
                .createdAt(player.getCreatedAt())
                .lastSeenAt(player.getLastSeenAt())
                .build();
    }
}