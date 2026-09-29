package vinch.mcs.api.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Réglages de sauvegarde : "network" (défauts), "rank:<groupe>" (limites du rôle
 * pour les propriétaires), "server:<id>" (un serveur). Champ null : valeur du réseau.
 */
@Entity
@Table(name = "backup_policies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackupPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String scope;

    @Column(name = "daily_max")
    private Integer dailyMax;
    @Column(name = "daily_days")
    private Integer dailyDays;
    @Column(name = "weekly_max")
    private Integer weeklyMax;
    @Column(name = "weekly_days")
    private Integer weeklyDays;
    @Column(name = "monthly_max")
    private Integer monthlyMax;
    @Column(name = "monthly_days")
    private Integer monthlyDays;
    @Column(name = "manual_max")
    private Integer manualMax;
    @Column(name = "manual_days")
    private Integer manualDays;
    @Column(name = "permanent_max")
    private Integer permanentMax;
    @Column(name = "permanent_hours")
    private Integer permanentHours;

    @Column(name = "updated_by", length = 16)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Integer max(BackupKind k) {
        return switch (k) {
            case DAILY -> dailyMax;
            case WEEKLY -> weeklyMax;
            case MONTHLY -> monthlyMax;
            case MANUAL -> manualMax;
            case PERMANENT -> permanentMax;
        };
    }

    /** Durée : jours, ou heures pour PERMANENT */
    public Integer duration(BackupKind k) {
        return switch (k) {
            case DAILY -> dailyDays;
            case WEEKLY -> weeklyDays;
            case MONTHLY -> monthlyDays;
            case MANUAL -> manualDays;
            case PERMANENT -> permanentHours;
        };
    }

    public void set(BackupKind k, Integer max, Integer duration) {
        switch (k) {
            case DAILY -> { dailyMax = max; dailyDays = duration; }
            case WEEKLY -> { weeklyMax = max; weeklyDays = duration; }
            case MONTHLY -> { monthlyMax = max; monthlyDays = duration; }
            case MANUAL -> { manualMax = max; manualDays = duration; }
            case PERMANENT -> { permanentMax = max; permanentHours = duration; }
        }
    }
}
