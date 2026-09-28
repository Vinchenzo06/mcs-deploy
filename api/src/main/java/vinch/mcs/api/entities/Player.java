package vinch.mcs.api.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "players")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "minecraft_uuid", nullable = false, unique = true)
    private UUID minecraftUuid;

    @Column(name = "minecraft_username", nullable = false, length = 16)
    private String minecraftUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PlayerRole role = PlayerRole.PLAYER;

    @Column(name = "max_servers", nullable = false)
    @Builder.Default
    private Integer maxServers = 1;

    @Column(name = "total_storage_mb", nullable = false)
    @Builder.Default
    private Integer totalStorageMb = 5000;

    @Column(name = "is_banned", nullable = false)
    @Builder.Default
    private Boolean isBanned = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "total_ram_mb", nullable = false)
    @Builder.Default
    private Integer totalRamMb = 1024;

    @Column(name = "total_cpu_cores", nullable = false)
    @Builder.Default
    private Integer totalCpuCores = 1;

    // Groupe LuckPerms principal (réseau) et son préfixe en composant texte JSON
    @Column(name = "network_rank", length = 32)
    private String networkRank;

    @Column(name = "network_prefix", length = 1024)
    private String networkPrefix;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastSeenAt == null) lastSeenAt = LocalDateTime.now();
    }
}