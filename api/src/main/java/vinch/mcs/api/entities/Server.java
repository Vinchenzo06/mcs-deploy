package vinch.mcs.api.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "servers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "velocity_name", nullable = false, unique = true, length = 80)
    private String velocityName;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Player owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    private Node node;

    @Column(name = "minecraft_version", nullable = false, length = 20)
    private String minecraftVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "server_type", nullable = false, length = 20)
    private ServerType serverType;

    @Column(name = "allocated_ram_mb", nullable = false)
    private Integer allocatedRamMb = 1024;

    @Column(name = "allocated_cpu_cores", nullable = false)
    private Integer allocatedCpuCores = 1;

    @Column(name = "allocated_storage_mb", nullable = false)
    private Integer allocatedStorageMb = 5000;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServerStatus status = ServerStatus.CREATING;

    @Column(name = "local_port")
    private Integer localPort;

    @Column(name = "tunnel_port")
    private Integer tunnelPort;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_started_at")
    private LocalDateTime lastStartedAt;

    @Column(name = "last_stopped_at")
    private LocalDateTime lastStoppedAt;

    // Privé par défaut : voir AccessService
    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = false;

    // Politique de sauvegarde propre à ce serveur (null : celle du rôle du propriétaire)
    @Column(name = "backup_interval_hours")
    private Integer backupIntervalHours;

    @Column(name = "backup_keep_last")
    private Integer backupKeepLast;

    @Column(name = "backup_keep_weekly")
    private Integer backupKeepWeekly;

    // Rôle réseau affiché en préfixe (équipes vanilla) ; le propriétaire peut le couper
    @Column(name = "show_network_rank", nullable = false)
    @Builder.Default
    private Boolean showNetworkRank = true;

    // Sauvegardes sur la machine (lot 30) : mot de passe du dépôt restic local,
    // et sa taille au dernier passage (comptée dans le quota disque du serveur)
    @Column(name = "local_backup_password", length = 64)
    private String localBackupPassword;

    @Column(name = "local_backup_mb")
    private Integer localBackupMb;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}