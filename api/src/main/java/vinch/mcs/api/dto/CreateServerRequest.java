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

    // Ignoré : le quota disque est calculé par l'API (part de la machine)
    private Integer storageMb;

    private Long forceNodeId;
    // Accepte de supprimer les sauvegardes d'un serveur supprimé qui occupe encore une place
    private Boolean replaceDeleted;
    // Serveur de la team IMG (lot 49) : créé depuis l'entrée IMG ou le site IMG Farm DB
    private Boolean team;
    // Lot 76 : serveur d'un groupe à la demande (créé par MCS, hors quota) et ses plugins Modrinth
    private String pool;
    private String modrinthProjects;
}
