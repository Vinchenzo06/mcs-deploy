package vinch.mcs.api.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Dépôt restic au central : "srv<id>" (un par serveur, depuis le lot 32) ou
 * "node<id>" (anciens dépôts par machine). Utilisateur HTTP = nom du dépôt.
 */
@Entity
@Table(name = "backup_repos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackupRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    @Column(name = "http_password", nullable = false, length = 64)
    private String httpPassword;

    @Column(name = "repo_password", nullable = false, length = 64)
    private String repoPassword;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
