package vinch.mcs.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class PlayerLoginRequest {

    @NotNull(message = "L'UUID est obligatoire")
    private UUID uuid;

    @NotBlank(message = "Le username est obligatoire")
    @Size(min = 1, max = 16, message = "Le username doit faire entre 1 et 16 caractères")
    private String username;
}