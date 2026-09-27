package vinch.mcs.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import vinch.mcs.api.entities.ServerType;

@Data
public class CreateServerRequest {

    @NotNull
    private Long ownerPlayerId;

    @NotBlank
    private String name;

    @NotBlank
    private String displayName;

    @NotNull
    private ServerType serverType;

    @NotBlank
    private String minecraftVersion;

    @NotNull
    private Integer ramMb;

    @NotNull
    private Integer cpuCores;

    @NotNull
    private Integer storageMb;

    private Long forceNodeId;
}