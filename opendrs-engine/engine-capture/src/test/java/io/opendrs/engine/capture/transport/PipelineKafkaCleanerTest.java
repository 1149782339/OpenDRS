package io.opendrs.engine.capture.transport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;

class PipelineKafkaCleanerTest {

    private static final String PUBLIC_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Test
    void deletesTopicAndConsumerGroup() {
        Admin admin = mock(Admin.class);
        stubDeleteTopicSuccess(admin);
        stubDeleteGroupSuccess(admin);

        PipelineKafkaCleaner.deleteTopicAndGroup(admin, PUBLIC_ID);

        verify(admin).deleteTopics(anyCollection());
        verify(admin).deleteConsumerGroups(anyCollection());
    }

    @Test
    void missingTopicAndGroupAreIdempotentSuccess() {
        Admin admin = mock(Admin.class);
        DeleteTopicsResult topics = mock(DeleteTopicsResult.class);
        when(admin.deleteTopics(anyCollection())).thenReturn(topics);
        when(topics.all()).thenReturn(failedFuture(new UnknownTopicOrPartitionException("gone")));

        DeleteConsumerGroupsResult groups = mock(DeleteConsumerGroupsResult.class);
        when(admin.deleteConsumerGroups(anyCollection())).thenReturn(groups);
        when(groups.all()).thenReturn(failedFuture(new GroupIdNotFoundException("gone")));

        PipelineKafkaCleaner.deleteTopicAndGroup(admin, PUBLIC_ID);

        verify(admin).deleteTopics(anyCollection());
        verify(admin).deleteConsumerGroups(anyCollection());
    }

    @Test
    void retriesTransientFailuresThenSucceeds() {
        Admin admin = mock(Admin.class);
        DeleteTopicsResult fail = mock(DeleteTopicsResult.class);
        DeleteTopicsResult ok = mock(DeleteTopicsResult.class);
        when(fail.all()).thenReturn(failedFuture(new TimeoutException("slow")));
        when(ok.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(admin.deleteTopics(anyCollection())).thenReturn(fail, ok);
        stubDeleteGroupSuccess(admin);

        PipelineKafkaCleaner.deleteTopicAndGroup(admin, PUBLIC_ID);

        verify(admin, times(2)).deleteTopics(anyCollection());
        verify(admin).deleteConsumerGroups(anyCollection());
    }

    @Test
    void exhaustsRetriesAndThrows() {
        Admin admin = mock(Admin.class);
        DeleteTopicsResult fail = mock(DeleteTopicsResult.class);
        when(fail.all()).thenReturn(failedFuture(new TimeoutException("slow")));
        when(admin.deleteTopics(anyCollection())).thenReturn(fail);

        assertThatThrownBy(() -> PipelineKafkaCleaner.deleteTopicAndGroup(admin, PUBLIC_ID))
                .isInstanceOf(RuntimeException.class);

        verify(admin, times(PipelineKafkaCleaner.MAX_ATTEMPTS)).deleteTopics(anyCollection());
    }

    @Test
    void rejectsBlankPublicId() {
        assertThatThrownBy(() -> PipelineKafkaCleaner.deleteTopicAndGroup(mock(Admin.class), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void stubDeleteTopicSuccess(Admin admin) {
        DeleteTopicsResult topics = mock(DeleteTopicsResult.class);
        when(admin.deleteTopics(anyCollection())).thenReturn(topics);
        when(topics.all()).thenReturn(KafkaFuture.completedFuture(null));
    }

    private static void stubDeleteGroupSuccess(Admin admin) {
        DeleteConsumerGroupsResult groups = mock(DeleteConsumerGroupsResult.class);
        when(admin.deleteConsumerGroups(anyCollection())).thenReturn(groups);
        when(groups.all()).thenReturn(KafkaFuture.completedFuture(null));
    }

    private static <T> KafkaFuture<T> failedFuture(Throwable cause) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(cause);
        return future;
    }
}
