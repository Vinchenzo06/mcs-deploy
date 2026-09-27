package vinch.mcs.api.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "volunteers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Volunteer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_player_id")
    private Player linkedPlayer;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 20)
    private TrustLevel trustLevel = TrustLevel.NEW;

    @Column(name = "max_servers_allowed", nullable = false)
    private Integer maxServersAllowed = 5;

    @Column(name = "max_ram_mb", nullable = false)
    private Integer maxRamMb = 8192;

    @Column(name = "registration_date", nullable = false)
    private LocalDateTime registrationDate;

    @PrePersist
    protected void onCreate() {
        if (registrationDate == null) registrationDate = LocalDateTime.now();
    }
}