package io.opendrs.migration.api;

import io.opendrs.common.api.Response;
import io.opendrs.migration.api.request.ConnectionInfo;
import io.opendrs.migration.api.request.CreateConnectionRequest;
import io.opendrs.migration.api.response.ConnectionResponse;
import io.opendrs.migration.api.response.ConnectionTestResponse;
import io.opendrs.migration.service.ConnectionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/migration/connections")
public class ConnectionController {

    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @PostMapping
    public Response<ConnectionResponse> create(@Valid @RequestBody CreateConnectionRequest request) {
        return Response.success(connectionService.create(request));
    }

    @GetMapping
    public Response<List<ConnectionResponse>> list() {
        return Response.success(connectionService.list());
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        connectionService.delete(id);
        return Response.success();
    }

    @PostMapping("/test")
    public Response<ConnectionTestResponse> test(@Valid @RequestBody ConnectionInfo request) {
        return Response.success(connectionService.test(request));
    }

    @PostMapping("/{id}/test")
    public Response<ConnectionTestResponse> testById(@PathVariable Long id) {
        return Response.success(connectionService.testById(id));
    }
}
