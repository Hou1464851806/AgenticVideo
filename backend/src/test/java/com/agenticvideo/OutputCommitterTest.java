package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticvideo.media.ProcessRunner;
import com.agenticvideo.rendering.OutputCommitter;
import com.agenticvideo.storage.ObjectStorage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class OutputCommitterTest {
  @Test
  void retriesFailedWriteAndRecognizesLostAcknowledgement() {
    for (boolean committed : List.of(false, true)) {
      var jdbc = mock(JdbcTemplate.class);
      var storage = mock(ObjectStorage.class);
      var id = UUID.randomUUID();
      var token = UUID.randomUUID();
      var output = new OutputCommitter.Output("outputs/test", "hash", 10, "ffmpeg", "compiler");
      when(storage.sizeIfPresent(output.key())).thenReturn(10L);
      var running = Map.<String, Object>of("state", "RUNNING", "attempt_token", token);
      var succeeded =
          Map.<String, Object>of(
              "state",
              "SUCCEEDED",
              "attempt_token",
              token,
              "output_key",
              output.key(),
              "output_hash",
              output.hash());
      when(jdbc.queryForList(anyString(), eq(id)))
          .thenReturn(List.of(running), List.of(committed ? succeeded : running));
      when(jdbc.update(anyString(), any(Object[].class)))
          .thenThrow(new DataAccessResourceFailureException("Lost acknowledgement"))
          .thenReturn(1);
      new OutputCommitter(jdbc, storage).commit(id, token, output, () -> false);
      verify(jdbc, times(committed ? 1 : 2)).update(anyString(), any(Object[].class));
      verify(storage, times(2)).sizeIfPresent(output.key());
    }
  }

  @Test
  void rejectedCancelledOrExpiredAttemptCannotCommit() {
    var jdbc = mock(JdbcTemplate.class);
    var storage = mock(ObjectStorage.class);
    var id = UUID.randomUUID();
    var token = UUID.randomUUID();
    var output = new OutputCommitter.Output("outputs/test", "hash", 10, "ffmpeg", "compiler");
    when(storage.sizeIfPresent(output.key())).thenReturn(10L);
    when(jdbc.queryForList(anyString(), eq(id)))
        .thenReturn(List.of(Map.of("state", "CANCEL_REQUESTED", "attempt_token", token)));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    var error =
        assertThrows(
            ProcessRunner.MediaFailure.class,
            () -> new OutputCommitter(jdbc, storage).commit(id, token, output, () -> false));
    assertEquals("WORKER_LOST", error.code());
    verify(jdbc, times(1)).update(anyString(), any(Object[].class));
  }
}
