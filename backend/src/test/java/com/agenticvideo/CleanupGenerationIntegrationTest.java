package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agenticvideo.asset.UploadService;
import com.agenticvideo.storage.ObjectStorage;
import com.agenticvideo.worker.CleanupWorker;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.flyway.schemas=m1_cleanup_test",
      "spring.flyway.default-schema=m1_cleanup_test",
      "spring.datasource.hikari.connection-init-sql=SET search_path TO m1_cleanup_test",
      "spring.session.jdbc.table-name=m1_cleanup_test.spring_session"
    })
@EnabledIfEnvironmentVariable(named = "M1_CLEANUP_INTEGRATION", matches = "true")
class CleanupGenerationIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired UploadService uploads;

  @Test
  void lateCleanupCompletionCannotConsumeNewCleanupRequest() throws Exception {
    String key = "cleanup-generation-test/" + UUID.randomUUID();
    var objects = ConcurrentHashMap.<String>newKeySet();
    objects.add(key);
    var deleted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ObjectStorage storage = mock(ObjectStorage.class);
    doAnswer(
            call -> {
              objects.remove(key);
              deleted.countDown();
              assertTrue(release.await(10, TimeUnit.SECONDS));
              return null;
            })
        .when(storage)
        .delete(key);
    var worker = new CleanupWorker(jdbc, tx, storage);
    uploads.enqueueCleanup(key, null, null);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var oldAttempt = executor.submit(worker::tick);
      assertTrue(deleted.await(10, TimeUnit.SECONDS));
      // A previous upload finishes after the old delete, then asks for cleanup again.
      objects.add(key);
      uploads.enqueueCleanup(key, null, null);
      release.countDown();
      oldAttempt.get(10, TimeUnit.SECONDS);
      assertEquals(
          "QUEUED",
          jdbc.queryForObject(
              "select state from object_cleanup_tasks where object_key=?", String.class, key));
      assertTrue(objects.contains(key));
      worker.tick();
      assertFalse(objects.contains(key));
      assertEquals(
          "SUCCEEDED",
          jdbc.queryForObject(
              "select state from object_cleanup_tasks where object_key=?", String.class, key));
      // A completed cleanup can also be requested again after another late object write.
      objects.add(key);
      uploads.enqueueCleanup(key, null, null);
      worker.tick();
      assertFalse(objects.contains(key));
      assertEquals(
          2L,
          jdbc.queryForObject(
              "select generation from object_cleanup_tasks where object_key=?", Long.class, key));
    } finally {
      release.countDown();
    }
  }
}
