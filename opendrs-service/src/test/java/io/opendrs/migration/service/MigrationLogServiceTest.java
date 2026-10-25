package io.opendrs.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.migration.domain.MigrationLog;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.MigrationLogMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Buffer semantics of {@link MigrationLogService}. The writer thread is never started here
 * ({@code @PostConstruct} is Spring's job), so every case drives the queue through {@link
 * MigrationLogService#flush()} and asserts on the batch the mapper receives.
 */
@ExtendWith(MockitoExtension.class)
class MigrationLogServiceTest {

    @Mock
    private MigrationLogMapper logMapper;

    @Mock
    private MigrationTaskMapper taskMapper;

    private MigrationLogService service;

    @BeforeEach
    void setUp() {
        service = new MigrationLogService(logMapper, taskMapper);
    }

    @Test
    void flushWritesWhatWasBuffered() {
        service.log(7L, MigrationLogLevel.INFO, "schema migration starting for 3 table(s)");
        service.log(7L, MigrationLogLevel.WARNING, "table test.tmp skipped");
        service.flush();

        List<MigrationLog> written = captured();
        assertThat(written).hasSize(2);
        assertThat(written.get(0).getTaskId()).isEqualTo(7L);
        assertThat(written.get(0).getLevel()).isEqualTo(MigrationLogLevel.INFO);
        assertThat(written.get(0).getLoggedAt()).isNotNull();
    }

    @Test
    void engineListenerWritesUnderTheBoundTask() {
        MigrationLogListener listener = service.listenerFor(11L);
        listener.onLog(MigrationLogLevel.ERROR, "failed to apply 2 CDC record(s)");
        service.flush();

        assertThat(captured()).singleElement().satisfies(row -> {
            assertThat(row.getTaskId()).isEqualTo(11L);
            assertThat(row.getLevel()).isEqualTo(MigrationLogLevel.ERROR);
        });
    }

    @Test
    void longMessagesAreCutToTheColumnWidth() {
        service.log(7L, MigrationLogLevel.INFO, "x".repeat(5000));
        service.flush();

        assertThat(captured()).singleElement().satisfies(row -> assertThat(row.getMessage()).hasSize(2000));
    }

    @Test
    void blankMessagesAreIgnored() {
        service.log(7L, MigrationLogLevel.INFO, "  ");
        service.flush();

        verify(logMapper, never()).insertBatch(any());
    }

    /** The page request must not turn a bad client value into a full table scan. */
    @Test
    void pageClampsSizeAndComputesOffset() {
        when(taskMapper.findById(7L)).thenReturn(new MigrationTask());
        when(logMapper.count(7L, null)).thenReturn(5_000L);
        when(logMapper.findPage(eq(7L), isNull(), anyInt(), anyInt())).thenReturn(List.of());

        service.page(7L, null, 3, 5000);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(logMapper).findPage(eq(7L), isNull(), offset.capture(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(200);
        assertThat(offset.getValue()).isEqualTo(400);
    }

    @Test
    void pageDefaultsWhenClientSendsNonsense() {
        when(taskMapper.findById(7L)).thenReturn(new MigrationTask());
        when(logMapper.count(7L, null)).thenReturn(5L);
        when(logMapper.findPage(eq(7L), isNull(), anyInt(), anyInt())).thenReturn(List.of());

        service.page(7L, null, 0, 0);

        verify(logMapper).findPage(eq(7L), isNull(), eq(0), eq(20));
    }

    /** Past the end the mapper is not asked at all, and the pager still gets a usable page. */
    @Test
    void pageBeyondTheEndReturnsNothing() {
        when(taskMapper.findById(7L)).thenReturn(new MigrationTask());
        when(logMapper.count(7L, null)).thenReturn(10L);

        assertThat(service.page(7L, null, 9, 20).items()).isEmpty();
        verify(logMapper, never()).findPage(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void pageRejectsUnknownTask() {
        when(taskMapper.findById(404L)).thenReturn(null);

        // AppException#getMessage() is now the i18n key; assert the business code instead.
        assertThatThrownBy(() -> service.page(404L, MigrationLogLevel.ERROR, 1, 20))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getCode())
                .isEqualTo(ErrorCode.TASK_NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    private List<MigrationLog> captured() {
        ArgumentCaptor<List<MigrationLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(logMapper).insertBatch(captor.capture());
        return captor.getValue();
    }
}
