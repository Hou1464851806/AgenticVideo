package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.agenticvideo.asset.UploadService;
import com.agenticvideo.media.ProcessRunner;
import com.agenticvideo.rendering.RenderWorker;
import com.agenticvideo.storage.ObjectStorage;
import com.agenticvideo.worker.CleanupWorker;
import com.agenticvideo.worker.MediaWorker;
import com.agenticvideo.worker.WorkerDisk;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class WorkerCapacityTest {
  @TempDir Path directory;

  @Test
  void concurrentReservationsCannotExceedBudgetAndReleaseIsIdempotent() throws Exception {
    var disk = new WorkerDisk(100);
    var first = disk.reserve(directory, 60);
    try {
      var error = assertThrows(ProcessRunner.MediaFailure.class, () -> disk.reserve(directory, 50));
      assertEquals("DISK_INSUFFICIENT", error.code());
      try (var second = disk.reserve(directory, 40)) {
        assertThrows(ProcessRunner.MediaFailure.class, () -> disk.reserve(directory, 1));
      }
    } finally {
      first.close();
      first.close();
    }
    try (var restored = disk.reserve(directory, 100)) {
      assertThrows(ProcessRunner.MediaFailure.class, () -> disk.reserve(directory, 1));
    }
  }

  @Test
  void configuredTwoSlotsExecuteConcurrentlyWithoutUnboundedQueue() throws Exception {
    var started = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(2);
    var cleanup = mock(CleanupWorker.class);
    var renders = mock(RenderWorker.class);
    doAnswer(
            call -> {
              started.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(cleanup)
        .tick();
    doAnswer(
            call -> {
              completed.countDown();
              return null;
            })
        .when(renders)
        .tick();
    var worker =
        new MediaWorker(
            mock(JdbcTemplate.class),
            mock(TransactionTemplate.class),
            mock(ObjectStorage.class),
            renders,
            mock(UploadService.class),
            cleanup,
            directory.resolve("absent").toString(),
            7200000,
            10800000,
            new WorkerDisk(100),
            2,
            3600);
    try {
      for (int i = 0; i < 30; i++) worker.tick();
      assertTrue(started.await(5, TimeUnit.SECONDS));
      verify(cleanup, times(2)).tick();
      release.countDown();
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      verify(renders, times(2)).tick();
    } finally {
      release.countDown();
      worker.stop();
    }
  }
}
