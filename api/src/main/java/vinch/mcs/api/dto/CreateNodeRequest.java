package vinch.mcs.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateNodeRequest {

    @NotNull
    private Long volunteerId;

    @NotBlank
    private String region;

    private String hostname;

    private Integer totalRamMb = 0;
    private Integer totalStorageMb = 0;
    private Integer cpuCores = 1;
}