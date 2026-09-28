package vinch.mcs.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateNodeResponse {

    private Long nodeId;
    private String nodeToken; // Le token en clair, à donner au volontaire (une seule fois)
    private String region;
    private Integer portStart;
    private Integer portEnd;
}