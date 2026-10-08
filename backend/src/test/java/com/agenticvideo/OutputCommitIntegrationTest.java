package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.agenticvideo.media.ProcessRunner;
import com.agenticvideo.rendering.OutputCommitter;
import com.agenticvideo.storage.ObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "M1_COMMIT_INTEGRATION", matches = "true")
class OutputCommitIntegrationTest {
  @Autowired DataSource database;
  @Autowired ObjectStorage storage;
  @TempDir Path directory;

  @Test
  void realConditionalWriteRecoversBothDatabaseFailureAndLostAcknowledgement() throws Exception {
    String schema = "commit_test_" + UUID.randomUUID().toString().replace("-", "");
    String key = "commit-tests/" + UUID.randomUUID();
    Path artifact = directory.resolve("artifact");
    Files.writeString(artifact, "small output submission regression");
    storage.upload(key, artifact, () -> false);
    try (var connection = database.getConnection()) {
      var real = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      real.execute("create schema " + schema);
      real.execute("set search_path to " + schema);
      try {
        real.execute(
            "create table render_jobs(id uuid primary key,state text,attempt_token uuid,lease_until timestamptz,progress int,output_key text,output_hash text,output_bytes bigint,ffmpeg_version text,compiler_hash text,completed_at timestamptz)");
        var output =
            new OutputCommitter.Output(
                key,
                com.agenticvideo.worker.MediaWorker.hash(artifact),
                Files.size(artifact),
                "fixed-ffmpeg",
                "compiler");
        for (boolean acknowledgementLost : new boolean[] {false, true}) {
          UUID id = UUID.randomUUID(), token = UUID.randomUUID();
          real.update(
              "insert into render_jobs(id,state,attempt_token,lease_until) values(?,'RUNNING',?,now()+interval '30 seconds')",
              id,
              token);
          var failing = spy(real);
          var first = new AtomicBoolean(true);
          doAnswer(
                  call -> {
                    if (first.getAndSet(false)) {
                      if (acknowledgementLost) call.callRealMethod();
                      throw new DataAccessResourceFailureException("Injected acknowledgement loss");
                    }
                    return call.callRealMethod();
                  })
              .when(failing)
              .update(anyString(), any(Object[].class));
          new OutputCommitter(failing, storage).commit(id, token, output, () -> false);
          assertEquals(
              "SUCCEEDED",
              real.queryForObject("select state from render_jobs where id=?", String.class, id));
          assertEquals(
              output.hash(),
              real.queryForObject(
                  "select output_hash from render_jobs where id=?", String.class, id));
          assertEquals(
              100,
              real.queryForObject(
                  "select progress from render_jobs where id=?", Integer.class, id));
        }
        // Two real connections race cancellation against the same conditional success write.
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
          for (int round = 0; round < 8; round++) {
            UUID id = UUID.randomUUID(), token = UUID.randomUUID();
            real.update(
                "insert into render_jobs(id,state,attempt_token,lease_until) values(?,'RUNNING',?,now()+interval '30 seconds')",
                id,
                token);
            var start = new java.util.concurrent.CountDownLatch(1);
            var complete =
                pool.submit(
                    () -> {
                      try (var c = database.getConnection()) {
                        c.setSchema(schema);
                        try {
                          start.await();
                          new OutputCommitter(
                                  new JdbcTemplate(new SingleConnectionDataSource(c, true)),
                                  storage)
                              .commit(id, token, output, () -> false);
                          return true;
                        } catch (ProcessRunner.MediaFailure lost) {
                          assertEquals("WORKER_LOST", lost.code());
                          return false;
                        } finally {
                          c.setSchema("business_schema");
                        }
                      }
                    });
            var cancel =
                pool.submit(
                    () -> {
                      try (var c = database.getConnection()) {
                        c.setSchema(schema);
                        try {
                          start.await();
                          return new JdbcTemplate(new SingleConnectionDataSource(c, true))
                              .update(
                                  "update render_jobs set state='CANCEL_REQUESTED' where id=? and state='RUNNING'",
                                  id);
                        } finally {
                          c.setSchema("business_schema");
                        }
                      }
                    });
            start.countDown();
            boolean succeeded = complete.get(10, java.util.concurrent.TimeUnit.SECONDS);
            int cancelled = cancel.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(succeeded ? 0 : 1, cancelled);
            assertEquals(
                succeeded ? "SUCCEEDED" : "CANCEL_REQUESTED",
                real.queryForObject("select state from render_jobs where id=?", String.class, id));
            assertEquals(
                succeeded ? key : null,
                real.queryForObject(
                    "select output_key from render_jobs where id=?", String.class, id));
          }
        }
        for (String state : new String[] {"CANCEL_REQUESTED", "RUNNING"}) {
          UUID id = UUID.randomUUID(), token = UUID.randomUUID();
          real.update(
              "insert into render_jobs(id,state,attempt_token,lease_until) values(?,?,?,now()-interval '1 second')",
              id,
              state,
              token);
          assertThrows(
              ProcessRunner.MediaFailure.class,
              () -> new OutputCommitter(real, storage).commit(id, token, output, () -> false));
          assertEquals(
              state,
              real.queryForObject("select state from render_jobs where id=?", String.class, id));
          assertEquals(
              null,
              real.queryForObject(
                  "select output_key from render_jobs where id=?", String.class, id));
        }
      } finally {
        real.execute("set search_path to business_schema");
        real.execute("drop schema " + schema + " cascade");
      }
    } finally {
      storage.delete(key);
    }
  }
}
