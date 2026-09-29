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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

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

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}