package vinch.mcs.api.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.dto.CreateNodeRequest;
import vinch.mcs.api.dto.CreateNodeResponse;
import vinch.mcs.api.services.NodeService;

@RestController
@RequestMapping("/api/v1/admin/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final NodeService nodeService;

    @PostMapping
    public ResponseEntity<CreateNodeResponse> createNode(@Valid @RequestBody CreateNodeRequest request) {
        return ResponseEntity.ok(nodeService.createNode(request));
    }
}