package com.agenticvideo.rendering;

import com.agenticvideo.api.CanonicalJson;
import com.agenticvideo.asset.UploadService;
import com.agenticvideo.media.FFmpegExecutor;
import com.agenticvideo.media.MediaProbe;
import com.agenticvideo.media.ProcessRunner;
import com.agenticvideo.storage.ObjectStorage;
import com.agenticvideo.timeline.TimelineCompiler;
import com.agenticvideo.worker.MediaWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class RenderWorker {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectStorage storage;
  private final OutputCommitter committer;
  private final UploadService uploads;
  private final Path root;
  private final com.agenticvideo.worker.WorkerDisk disk;
  private final long timeoutSeconds;

  public RenderWorker(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ObjectStorage storage,
      OutputCommitter committer,
      UploadService uploads,
      @Value("${app.worker.directory:/tmp/agenticvideo}") String directory,
      com.agenticvideo.worker.WorkerDisk disk,
      @Value("${app.worker.render-timeout-seconds:1800}") long timeoutSeconds) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.storage = storage;
    this.committer = committer;
    this.uploads = uploads;
    this.root = Path.of(directory);
    this.disk = disk;
    this.timeoutSeconds = timeoutSeconds;
  }

  public void tick() {
    tx.executeWithoutResult(
        status -> {
          var expired =
              jdbc.queryForList(
                  "update render_jobs set state=case when state='CANCEL_REQUESTED' then 'CANCELLED' else 'FAILED' end,error_code=case when state='CANCEL_REQUESTED' then null else 'WORKER_LOST' end,completed_at=now() where state in ('RUNNING','CANCEL_REQUESTED') and lease_until<now() returning id,attempt_token");
          for (var row : expired)
            uploads.enqueueCleanup(
                key((UUID) row.get("id"), (UUID) row.get("attempt_token")), null, null);
        });
    var job =
        tx.execute(
            status -> {
              var rows =
                  jdbc.queryForList(
                      "select * from render_jobs where state='QUEUED' order by created_at,id for update skip locked limit 1");
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              UUID token = UUID.randomUUID();
              jdbc.update(
                  "update render_jobs set state='RUNNING',attempt_token=?,lease_until=now()+interval '30 seconds',started_at=now(),progress=1 where id=?",
                  token,
                  row.get("id"));
              row.put("attempt_token", token);
              com.agenticvideo.worker.WorkerEvents.emit(
                  "render_claimed", (UUID) row.get("id"), token, null);
              return row;
            });
    if (job != null) render(job);
  }

  private void render(Map<String, Object> job) {
    UUID id = (UUID) job.get("id"), token = (UUID) job.get("attempt_token");
    Path directory = root.resolve(id.toString()).resolve(token.toString());
    String outputKey = key(id, token);
    AtomicBoolean valid = new AtomicBoolean(true);
    long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
    var heartbeat = Executors.newSingleThreadScheduledExecutor();
    heartbeat.scheduleAtFixedRate(
        () -> {
          try {
            if (jdbc.update(
                    "update render_jobs set lease_until=now()+interval '30 seconds' where id=? and attempt_token=? and state='RUNNING' and lease_until>now()",
                    id,
                    token)
                == 0) valid.set(false);
          } catch (org.springframework.dao.DataAccessException error) {
            valid.set(false);
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
    java.util.function.BooleanSupplier stopped = () -> !valid.get() || System.nanoTime() > deadline;
    AutoCloseable reservation = null;
    try {
      Files.createDirectories(directory);
      var revision =
          jdbc.queryForMap(
              "select plan_data,plan_hash from edit_plan_revisions where plan_id=? and revision=?",
              job.get("plan_id"),
              job.get("revision"));
      var dsl = CanonicalJson.parse(revision.get("plan_data").toString());
      if (!CanonicalJson.hash(dsl).equals(job.get("plan_hash")))
        throw new ProcessRunner.MediaFailure("PLAN_HASH_MISMATCH");
      var spec =
          new TimelineCompiler()
              .compile(dsl, TimelineCompiler.Profile.valueOf((String) job.get("render_profile")));
      var assets =
          jdbc.queryForList(
              "select a.* from assets a join revision_asset_refs r on r.asset_id=a.id and r.asset_version=a.version where r.plan_id=? and r.revision=? order by a.id",
              job.get("plan_id"),
              job.get("revision"));
      long sourceBytes =
          assets.stream().mapToLong(a -> ((Number) a.get("file_size")).longValue()).sum();
      // Bounded video bitrates plus PCM intermediates, final output and safety reserve.
      long required =
          Math.addExact(sourceBytes, Math.addExact(spec.durationMs() * 5600L, 512L * 1024 * 1024));
      reservation = disk.reserve(directory, required);
      var inputs = new HashMap<UUID, FFmpegExecutor.Input>();
      var probe = new MediaProbe();
      for (var asset : assets) {
        if (stopped.getAsBoolean()) throw new ProcessRunner.MediaFailure("CANCELLED");
        if (!asset.get("state").equals("MEDIA_READY"))
          throw new ProcessRunner.MediaFailure("ASSET_NOT_READY");
        UUID assetId = (UUID) asset.get("id");
        Path file = directory.resolve(assetId + ".source");
        storage.download((String) asset.get("storage_key"), file, stopped);
        if (!MediaWorker.hash(file).equals(asset.get("content_hash")))
          throw new ProcessRunner.MediaFailure("ASSET_HASH_MISMATCH");
        inputs.put(assetId, new FFmpegExecutor.Input(file, probe.probe(file, stopped)));
      }
      var output =
          new FFmpegExecutor()
              .render(
                  spec,
                  inputs,
                  directory,
                  stopped,
                  progress -> {
                    if (jdbc.update(
                            "update render_jobs set progress=greatest(progress,?) where id=? and attempt_token=? and state='RUNNING' and lease_until>now()",
                            progress,
                            id,
                            token)
                        == 0) valid.set(false);
                  });
      if (stopped.getAsBoolean()) throw new ProcessRunner.MediaFailure("CANCELLED");
      String hash = MediaWorker.hash(output);
      long bytes = Files.size(output);
      String version =
          new ProcessRunner()
              .run(List.of("ffmpeg", "-version"), Duration.ofSeconds(10), stopped)
              .stdout()
              .lines()
              .findFirst()
              .orElseThrow();
      storage.upload(outputKey, output, stopped);
      if (!Long.valueOf(bytes).equals(storage.sizeIfPresent(outputKey)))
        throw new ProcessRunner.MediaFailure("OUTPUT_STORAGE_INVALID");
      if (stopped.getAsBoolean()) throw new ProcessRunner.MediaFailure("CANCELLED");
      committer.commit(
          id,
          token,
          new OutputCommitter.Output(outputKey, hash, bytes, version, CanonicalJson.hash(spec)),
          () -> System.nanoTime() > deadline);
      com.agenticvideo.worker.WorkerEvents.emit("render_succeeded", id, token, null);
    } catch (Exception error) {
      String code =
          System.nanoTime() > deadline
              ? "RENDER_TIMEOUT"
              : error instanceof ProcessRunner.MediaFailure m
                  ? m.code()
                  : error instanceof software.amazon.awssdk.core.exception.SdkException
                      ? "STORAGE_UNAVAILABLE"
                      : "RENDER_FAILED";
      com.agenticvideo.worker.WorkerEvents.emit("render_failed", id, token, code);
      try {
        tx.executeWithoutResult(
            status -> {
              jdbc.update(
                  "update render_jobs set state=case when state='CANCEL_REQUESTED' then 'CANCELLED' else 'FAILED' end,error_code=case when state='CANCEL_REQUESTED' then null else ? end,completed_at=now() where id=? and attempt_token=? and state in ('RUNNING','CANCEL_REQUESTED')",
                  code,
                  id,
                  token);
              if (jdbc.queryForObject(
                      "select count(*) from render_jobs where id=? and state='SUCCEEDED' and output_key=?",
                      Integer.class,
                      id,
                      outputKey)
                  == 0) uploads.enqueueCleanup(outputKey, null, null);
            });
      } catch (org.springframework.dao.DataAccessException ignored) {
        /* Lease recovery owns durable failure. */
      }
    } finally {
      valid.set(false);
      heartbeat.shutdownNow();
      MediaWorker.deleteDirectory(directory);
      MediaWorker.releaseDisk(reservation);
    }
  }

  private static String key(UUID id, UUID token) {
    return "outputs/" + id + "/" + token + ".mp4";
  }
}
