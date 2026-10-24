package io.opendrs.migration.api;

import io.opendrs.common.api.Response;
import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.migration.api.request.CreateMigrationTaskRequest;
import io.opendrs.migration.api.response.MigrationLogPage;
import io.opendrs.migration.api.response.MigrationPrecheckResponse;
import io.opendrs.migration.api.response.MigrationStatusResponse;
import io.opendrs.migration.api.response.MigrationTaskResponse;
import io.opendrs.migration.api.response.MigrationTaskSummary;
import io.opendrs.migration.service.MigrationLogService;
import io.opendrs.migration.service.MigrationPrecheckService;
import io.opendrs.migration.service.MigrationTaskService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/migration/tasks")
public class MigrationTaskController {

    private final MigrationTaskService taskService;
    private final MigrationPrecheckService precheckService;
    private final MigrationLogService logService;

    public MigrationTaskController(
            MigrationTaskService taskService,
            MigrationPrecheckService precheckService,
            MigrationLogService logService) {
        this.taskService = taskService;
        this.precheckService = precheckService;
        this.logService = logService;
    }

    @PostMapping
    public Response<MigrationTaskResponse> create(@Valid @RequestBody CreateMigrationTaskRequest req) {
        return Response.success(taskService.create(req));
    }

    @GetMapping
    public Response<List<MigrationTaskSummary>> list() {
        return Response.success(taskService.list());
    }

    /** Default task name for the create wizard: DRS-xxxx, xxxx = next auto-increment value. */
    @GetMapping("/next-name")
    public Response<String> nextName() {
        return Response.success(taskService.nextTaskName());
    }

    @GetMapping("/{id}")
    public Response<MigrationTaskResponse> get(@PathVariable Long id) {
        return Response.success(taskService.get(id));
    }

    @GetMapping("/{id}/status")
    public Response<MigrationStatusResponse> status(@PathVariable Long id) {
        return Response.success(taskService.status(id));
    }

    @PostMapping("/{id}/precheck")
    public Response<MigrationPrecheckResponse> precheck(@PathVariable Long id) {
        return Response.success(precheckService.startPrecheck(id));
    }

    @GetMapping("/{id}/precheck")
    public Response<MigrationPrecheckResponse> getPrecheck(@PathVariable Long id) {
        return Response.success(precheckService.getPrecheck(id));
    }

    /**
     * @param level {@code null} or empty for every level; filtering is done in SQL so the pager
     *     counts what the filter matches
     */
    @GetMapping("/{id}/logs")
    public Response<MigrationLogPage> logs(
            @PathVariable Long id,
            @RequestParam(required = false) MigrationLogLevel level,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.success(logService.page(id, level, page, size));
    }

    @PostMapping("/{id}/start")
    public Response<MigrationStatusResponse> start(@PathVariable Long id) {
        return Response.success(taskService.start(id));
    }

    @PostMapping("/{id}/stop")
    public Response<MigrationStatusResponse> stop(@PathVariable Long id) {
        return Response.success(taskService.stop(id));
    }

    @PostMapping("/{id}/terminate")
    public Response<MigrationStatusResponse> terminate(@PathVariable Long id) {
        return Response.success(taskService.terminate(id));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Response.success();
    }
}
