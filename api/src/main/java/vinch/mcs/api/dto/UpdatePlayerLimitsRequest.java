package vinch.mcs.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdatePlayerLimitsRequest {

    @NotNull
    private UUID uuid;

    @NotNull
    private Integer maxServers;

    @NotNull
    private Integer totalRamMb;

    @NotNull
    private Integer totalCpuCores;

    // Permission LuckPerms mcs.admin (null : ne change pas le rôle)
    private Boolean admin;

    // Groupe LuckPerms principal et préfixe (composant texte JSON), facultatifs
    private String rank;
    private String prefix;
}