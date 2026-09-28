package vinch.mcs.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateServerResponse {

    private Long serverId;
    private String name;
    private String velocityName;
    private Integer port;
    private Long nodeId;
    private String status;
    private Integer storageMb;
}