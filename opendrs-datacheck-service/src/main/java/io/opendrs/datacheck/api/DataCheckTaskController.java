package io.opendrs.datacheck.api;

import io.opendrs.common.api.Response;
import io.opendrs.datacheck.api.request.CreateDataCheckTaskRequest;
import io.opendrs.datacheck.api.response.DataCheckResultPage;
import io.opendrs.datacheck.api.response.DataCheckTableResult;
import io.opendrs.datacheck.api.response.DataCheckTaskResponse;
import io.opendrs.datacheck.service.DataCheckTaskService;
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
@RequestMapping("/api/v1/datacheck/tasks")
public class DataCheckTaskController {

    private final DataCheckTaskService taskService;

    public DataCheckTaskController(DataCheckTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public Response<DataCheckTaskResponse> create(
            @Valid @RequestBody CreateDataCheckTaskRequest request) {
        return Response.success(taskService.create(request));
    }

    /** Lists checks, optionally only the ones under one migration task. */
    @GetMapping
    public Response<List<DataCheckTaskResponse>> list(
            @RequestParam(required = false) Long migrationTaskId) {
        return Response.success(taskService.list(migrationTaskId));
    }

    @GetMapping("/{id}")
    public Response<DataCheckTaskResponse> get(@PathVariable Long id) {
        return Response.success(taskService.get(id));
    }

    /** Deletes the check task, its table rows and its result table. */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Response.success();
    }

    @PostMapping("/{id}/start")
    public Response<Void> start(@PathVariable Long id) {
        taskService.start(id);
        return Response.success();
    }

    @PostMapping("/{id}/stop")
    public Response<Void> stop(@PathVariable Long id) {
        taskService.stop(id);
        return Response.success();
    }

    @PostMapping("/{id}/terminate")
    public Response<Void> terminate(@PathVariable Long id) {
        taskService.terminate(id);
        return Response.success();
    }

    /** Per-table state and verdict; the table list on the check detail page. */
    @GetMapping("/{id}/tables")
    public Response<List<DataCheckTableResult>> tables(@PathVariable Long id) {
        return Response.success(taskService.tables(id));
    }

    /**
     * Paged inconsistent rows of one check task.
     *
     * <p>{@code tableId} is the source identifier ({@code schema.table}); {@code flag} is 0/1/2,
     * null means every flag.
     */
    @GetMapping("/{id}/results")
    public Response<DataCheckResultPage> results(
            @PathVariable Long id,
            @RequestParam(required = false) String tableId,
            @RequestParam(required = false) Integer flag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.success(taskService.results(id, tableId, flag, page, size));
    }
}
