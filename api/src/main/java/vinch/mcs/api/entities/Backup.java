package vinch.mcs.api.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "backups")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Backup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Null une fois le serveur supprimé (ses sauvegardes restent le temps de leur durée de vie)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id")
    private Server server;

    // Id et nom du serveur, gardés après sa suppression
    @Column(name = "server_tag_id")
    private Long serverTagId;

    @Column(name = "server_ref", length = 80)
    private String serverRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "backup_type", nullable = false, length = 20)
    @Builder.Default
    private BackupType backupType = BackupType.AUTO;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(name = "size_mb", nullable = false)
    @Builder.Default
    private Integer sizeMb = 0;

    @Column(name = "is_complete", nullable = false)
    @Builder.Default
    private Boolean isComplete = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // RUNNING, SUCCESS ou FAILED
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "RUNNING";

    @Column(name = "snapshot_id", length = 80)
    private String snapshotId;

    // Taille totale du serveur sauvegardé (sizeMb = données nouvelles envoyées)
    @Column(name = "total_mb")
    private Integer totalMb;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "requested_by", length = 16)
    private String requestedBy;

    // Types retenus (voir BackupKind)
    @Column(name = "is_daily", nullable = false)
    @Builder.Default
    private Boolean isDaily = false;

    @Column(name = "is_weekly", nullable = false)
    @Builder.Default
    private Boolean isWeekly = false;

    @Column(name = "is_monthly", nullable = false)
    @Builder.Default
    private Boolean isMonthly = false;

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private Boolean isManual = false;

    @Column(name = "is_permanent", nullable = false)
    @Builder.Default
    private Boolean isPermanent = false;

    // Fixée à la suppression du serveur (sinon calculée à chaque passage)
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public boolean is(BackupKind k) {
        return Boolean.TRUE.equals(switch (k) {
            case DAILY -> isDaily;
            case WEEKLY -> isWeekly;
            case MONTHLY -> isMonthly;
            case MANUAL -> isManual;
            case PERMANENT -> isPermanent;
        });
    }

    public void mark(BackupKind k, boolean value) {
        switch (k) {
            case DAILY -> isDaily = value;
            case WEEKLY -> isWeekly = value;
            case MONTHLY -> isMonthly = value;
            case MANUAL -> isManual = value;
            case PERMANENT -> isPermanent = value;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}