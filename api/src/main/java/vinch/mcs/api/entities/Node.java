package vinch.mcs.api.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "nodes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Node {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "volunteer_id", nullable = false)
    private Volunteer volunteer;

    @Column(name = "node_token_hash", nullable = false, unique = true, length = 255)
    private String nodeTokenHash;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(length = 255)
    private String hostname;

    @Column(name = "agent_version", length = 50)
    private String agentVersion;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "is_online", nullable = false)
    @Builder.Default
    private Boolean isOnline = false;

    @Column(name = "total_ram_mb", nullable = false)
    @Builder.Default
    private Integer totalRamMb = 0;

    @Column(name = "used_ram_mb", nullable = false)
    @Builder.Default
    private Integer usedRamMb = 0;

    @Column(name = "total_storage_mb", nullable = false)
    @Builder.Default
    private Integer totalStorageMb = 0;

    @Column(name = "used_storage_mb", nullable = false)
    @Builder.Default
    private Integer usedStorageMb = 0;

    @Column(name = "cpu_cores", nullable = false)
    @Builder.Default
    private Integer cpuCores = 1;

    // Ressources réelles de la machine (remontées par l'agent)
    @Column(name = "host_ram_mb")
    private Integer hostRamMb;

    @Column(name = "host_cpu_cores")
    private Integer hostCpuCores;

    @Column(name = "host_disk_total_mb")
    private Integer hostDiskTotalMb;

    @Column(name = "host_disk_free_mb")
    private Integer hostDiskFreeMb;

    // Plage de ports réservée à cette machine (tunnels rathole dédiés)
    @Column(name = "port_start")
    private Integer portStart;

    @Column(name = "port_end")
    private Integer portEnd;

    @Column(name = "is_revoked", nullable = false)
    @Builder.Default
    private Boolean isRevoked = false;

    // Sauvegardes : identifiants rest-server (utilisateur "node<id>") et chiffrement du dépôt
    @Column(name = "backup_http_password", length = 64)
    private String backupHttpPassword;

    @Column(name = "backup_repo_password", length = 64)
    private String backupRepoPassword;

    // Joueur propriétaire de la machine (mcs-node-owner), null si inconnu
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_player_id")
    private Player ownerPlayer;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}