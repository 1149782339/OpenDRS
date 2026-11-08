package io.opendrs.datacheck.api;

import io.opendrs.common.api.Response;
import io.opendrs.datacheck.api.request.CreateDataCheckTaskRequest;
import io.opendrs.datacheck.api.response.DataCheckResultPage;
import io.opendrs.datacheck.api.response.DataCheckTableResult;
import io.opendrs.datacheck.api.response.DataCheckTaskResponse;
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

/** Data-check task API. Service wiring is still a TODO; the shapes are fixed for the UI. */
@RestController
@RequestMapping("/api/v1/datacheck/tasks")
public class DataCheckTaskController {

    @PostMapping
    public Response<DataCheckTaskResponse> create(
            @Valid @RequestBody CreateDataCheckTaskRequest request) {
        // TODO: DataCheckTaskService.create(request) — persist one datacheck_tasks row with the
        //  explicit source table list and derive the target tables from the migration task mapping.
        return Response.success(null);
    }

    /** Lists checks, optionally only the ones under one migration task. */
    @GetMapping
    public Response<List<DataCheckTaskResponse>> list(
            @RequestParam(required = false) Long migrationTaskId) {
        // TODO: DataCheckTaskService.list(migrationTaskId)
        return Response.success(List.of());
    }

    @GetMapping("/{id}")
    public Response<DataCheckTaskResponse> get(@PathVariable Long id) {
        // TODO: DataCheckTaskService.get(id)
        return Response.success(null);
    }

    /** Deletes the check task; the service must also drop datacheck_result_<id>. */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        // TODO: DataCheckTaskService.delete(id) — delete the row, then DROP TABLE datacheck_result_<id>.
        return Response.success();
    }

    @PostMapping("/{id}/start")
    public Response<Void> start(@PathVariable Long id) {
        // TODO: start the engine data check (full, incremental or both, per mode).
        return Response.success();
    }

    @PostMapping("/{id}/stop")
    public Response<Void> stop(@PathVariable Long id) {
        // TODO: cooperative stop of the running check.
        return Response.success();
    }

    @PostMapping("/{id}/terminate")
    public Response<Void> terminate(@PathVariable Long id) {
        // TODO: final termination; after this the row may be deleted.
        return Response.success();
    }

    /** Per-table summary: what the table list on the check detail page renders. */
    @GetMapping("/{id}/tables")
    public Response<List<DataCheckTableResult>> tables(@PathVariable Long id) {
        // TODO: SELECT table_id, flag, COUNT(*) FROM datacheck_result_<id> GROUP BY table_id, flag
        //  and fold the three flag counts into DataCheckTableResult.
        return Response.success(List.of());
    }

    /**
     * Paged inconsistent rows of one check task.
     *
     * <p>{@code tableId} is the source identifier ({@code schema.table}); {@code flag} is 0/1/2,
     * null means every flag. The result table name is dynamic: {@code datacheck_result_<id>}.
     */
    @GetMapping("/{id}/results")
    public Response<DataCheckResultPage> results(
            @PathVariable Long id,
            @RequestParam(required = false) String tableId,
            @RequestParam(required = false) Integer flag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // TODO: page over datacheck_result_<id> filtered by table_id / flag. The id is a long taken
        //  from the persisted datacheck_tasks row, so it is safe as a dynamic table suffix; use
        //  MyBatis ${id} or a @SelectProvider. A table that does not exist yet (the check never
        //  ran) should surface as an empty page or a clear business error, not a raw SQL error.
        return Response.success(new DataCheckResultPage(List.of(), 0, page, size));
    }
}
