package io.opendrs.migration.api;

import io.opendrs.common.api.Response;
import io.opendrs.migration.api.request.CreateMigrationTaskRequest;
import io.opendrs.migration.api.response.MigrationStatusResponse;
import io.opendrs.migration.api.response.MigrationTaskResponse;
import io.opendrs.migration.api.response.MigrationTaskSummary;
import io.opendrs.migration.service.MigrationTaskService;
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
@RequestMapping("/api/v1/migration/tasks")
public class MigrationTaskController {

    private final MigrationTaskService taskService;

    public MigrationTaskController(MigrationTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public Response<MigrationTaskResponse> create(@Valid @RequestBody CreateMigrationTaskRequest req) {
        return Response.success(taskService.create(req));
    }

    @GetMapping
    public Response<List<MigrationTaskSummary>> list() {
        return Response.success(taskService.list());
    }

    @GetMapping("/{id}")
    public Response<MigrationTaskResponse> get(@PathVariable Long id) {
        return Response.success(taskService.get(id));
    }

    @GetMapping("/{id}/status")
    public Response<MigrationStatusResponse> status(@PathVariable Long id) {
        return Response.success(taskService.status(id));
    }

    @PostMapping("/{id}/start")
    public Response<MigrationStatusResponse> start(@PathVariable Long id) {
        return Response.success(taskService.start(id));
    }

    @PostMapping("/{id}/stop")
    public Response<MigrationStatusResponse> stop(@PathVariable Long id) {
        return Response.success(taskService.stop(id));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Response.success();
    }
}
