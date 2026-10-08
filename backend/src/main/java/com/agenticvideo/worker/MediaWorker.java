package com.agenticvideo.worker;

import com.agenticvideo.api.ApiException;
import com.agenticvideo.api.CanonicalJson;
import com.agenticvideo.asset.UploadService;
import com.agenticvideo.media.MediaProbe;
import com.agenticvideo.media.ProcessRunner;
import com.agenticvideo.storage.ObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class MediaWorker {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectStorage storage;
  private final com.agenticvideo.rendering.RenderWorker renders;
  private final UploadService uploads;
  private final CleanupWorker objectCleanup;
  private final Path root;
  private final long assetDuration;
  private final long projectDuration;
  private final WorkerDisk disk;
  private final java.util.concurrent.ExecutorService executions;
  private final java.util.concurrent.Semaphore slots;
  private final long cleanupTtlMillis;

  public MediaWorker(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ObjectStorage storage,
      com.agenticvideo.rendering.RenderWorker renders,
      UploadService uploads,
      CleanupWorker objectCleanup,
      @Value("${app.worker.directory:/tmp/agenticvideo}") String directory,
      @Value("${app.limits.asset-duration-ms:7200000}") long assetDuration,
      @Value("${app.limits.project-duration-ms:10800000}") long projectDuration,
      WorkerDisk disk,
      @Value("${app.worker.concurrency:1}") int concurrency,
      @Value("${app.worker.cleanup-ttl-seconds:3600}") long cleanupTtlSeconds) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.storage = storage;
    this.renders = renders;
    this.uploads = uploads;
    this.objectCleanup = objectCleanup;
    this.root = Path.of(directory);
    this.assetDuration = assetDuration;
    this.projectDuration = projectDuration;
    this.disk = disk;
    if (concurrency < 1 || concurrency > 4)
      throw new IllegalArgumentException("Worker concurrency must be 1–4");
    this.executions = Executors.newFixedThreadPool(concurrency);
    this.slots = new java.util.concurrent.Semaphore(concurrency);
    if (cleanupTtlSeconds < 60)
      throw new IllegalArgumentException("Cleanup TTL must be at least 60 seconds");
    this.cleanupTtlMillis = Math.multiplyExact(cleanupTtlSeconds, 1000);
  }

  @Scheduled(fixedDelayString = "${app.worker.poll-ms:500}")
  public void tick() {
    if (!slots.tryAcquire()) return;
    try {
      executions.execute(
          () -> {
            try {
              execute();
            } finally {
              slots.release();
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException stopped) {
      slots.release();
    }
  }

  @jakarta.annotation.PreDestroy
  public void stop() {
    executions.shutdownNow();
    try {
      executions.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void execute() {
    try {
      expireLeases();
      sweepTemporaryFiles();
      recoverUploads();
      objectCleanup.tick();
      var job = claim();
      if (job != null) probe(job);
      else renders.tick();
    } catch (org.springframework.dao.DataAccessException
        | software.amazon.awssdk.core.exception.SdkException error) {
      WorkerEvents.emit(
          "poll_unavailable",
          null,
          null,
          error instanceof org.springframework.dao.DataAccessException
              ? "DATABASE_UNAVAILABLE"
              : "STORAGE_UNAVAILABLE");
    }
  }

  private Map<String, Object> claim() {
    return tx.execute(
        status -> {
          var rows =
              jdbc.queryForList(
                  "select id,asset_id from media_probe_jobs where state='QUEUED' order by created_at,id for update skip locked limit 1");
          if (rows.isEmpty()) return null;
          var job = rows.getFirst();
          UUID token = UUID.randomUUID();
          jdbc.update(
              "update media_probe_jobs set state='RUNNING',attempt_token=?,lease_until=now()+interval '30 seconds',progress=1 where id=?",
              token,
              job.get("id"));
          job.put("attempt_token", token);
          WorkerEvents.emit("probe_claimed", (UUID) job.get("id"), token, null);
          return job;
        });
  }

  private void probe(Map<String, Object> job) {
    UUID id = (UUID) job.get("id"),
        assetId = (UUID) job.get("asset_id"),
        token = (UUID) job.get("attempt_token");
    var rows =
        jdbc.queryForList(
            "select a.*,u.staging_key,u.content_type from assets a join upload_sessions u on u.id=a.upload_id where a.id=?",
            assetId);
    var asset = rows.getFirst();
    Path directory = root.resolve(id.toString()).resolve(token.toString());
    AtomicBoolean valid = new AtomicBoolean(true);
    var heartbeat = Executors.newSingleThreadScheduledExecutor();
    heartbeat.scheduleAtFixedRate(
        () -> {
          try {
            if (jdbc.update(
                    "update media_probe_jobs set lease_until=now()+interval '30 seconds' where id=? and attempt_token=? and state='RUNNING' and lease_until>now()",
                    id,
                    token)
                == 0) valid.set(false);
          } catch (org.springframework.dao.DataAccessException e) {
            valid.set(false);
          }
        },
        5,
        5,
        TimeUnit.SECONDS);
    AutoCloseable reservation = null;
    try {
      Files.createDirectories(directory);
      long required = ((Number) asset.get("file_size")).longValue() + 512L * 1024 * 1024;
      reservation = disk.reserve(directory, required);
      Path input = directory.resolve("source");
      storage.download((String) asset.get("staging_key"), input, () -> !valid.get());
      String hash = hash(input);
      var media = new MediaProbe();
      var metadata = media.probe(input, () -> !valid.get());
      if (metadata.durationMs() > assetDuration)
        throw new ProcessRunner.MediaFailure("ASSET_DURATION_EXCEEDED");
      media.decode(input, () -> !valid.get());
      storage.copy((String) asset.get("staging_key"), (String) asset.get("storage_key"));
      if (!Long.valueOf(Files.size(input))
          .equals(storage.sizeIfPresent((String) asset.get("storage_key"))))
        throw new ProcessRunner.MediaFailure("SEALED_OBJECT_INVALID");
      String proxy = null;
      if (asset.get("content_type").equals("video/quicktime")) {
        Path output = directory.resolve("proxy.mp4");
        var result =
            new ProcessRunner()
                .run(
                    List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-nostdin",
                        "-y",
                        "-protocol_whitelist",
                        "file,pipe",
                        "-i",
                        input.toString(),
                        "-vf",
                        "scale=640:640:force_original_aspect_ratio=decrease,setsar=1",
                        "-threads",
                        "2",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "veryfast",
                        "-crf",
                        "28",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-movflags",
                        "+faststart",
                        output.toString()),
                    Duration.ofMinutes(15),
                    () -> !valid.get());
        if (result.exitCode() != 0) throw new ProcessRunner.MediaFailure("PROXY_FAILED");
        media.probe(output, () -> !valid.get());
        proxy = "proxies/" + assetId + "/v1.mp4";
        storage.upload(proxy, output, () -> !valid.get());
      }
      String proxyKey = proxy;
      tx.executeWithoutResult(
          status -> {
            var locked =
                jdbc.queryForList(
                    "select id from projects where id=? for update", asset.get("project_id"));
            if (locked.isEmpty()) throw new ProcessRunner.MediaFailure("PROJECT_NOT_FOUND");
            long existing =
                jdbc.queryForObject(
                    "select coalesce(sum(duration_ms),0) from assets where project_id=? and state='MEDIA_READY' and id<>?",
                    Long.class,
                    asset.get("project_id"),
                    assetId);
            if (existing + metadata.durationMs() > projectDuration)
              throw new ProcessRunner.MediaFailure("PROJECT_DURATION_EXCEEDED");
            int committed =
                jdbc.update(
                    "update media_probe_jobs set state='SUCCEEDED',progress=100,completed_at=now() where id=? and attempt_token=? and state='RUNNING' and lease_until>now()",
                    id,
                    token);
            if (committed == 0) throw new ProcessRunner.MediaFailure("WORKER_LOST");
            jdbc.update(
                "update assets set state='MEDIA_READY',content_hash=?,duration_ms=?,width=?,height=?,fps=?,rotation=?,has_audio=?,video_start=?,audio_start=?,proxy_key=? where id=? and state='VALIDATING'",
                hash,
                metadata.durationMs(),
                metadata.width(),
                metadata.height(),
                metadata.fps(),
                metadata.rotation(),
                metadata.audio(),
                metadata.videoStart(),
                metadata.audioStart(),
                proxyKey,
                assetId);
            uploads.enqueueCleanup((String) asset.get("staging_key"), null, null);
          });
    } catch (Exception error) {
      String code =
          error instanceof ProcessRunner.MediaFailure m
              ? m.code()
              : error instanceof software.amazon.awssdk.core.exception.SdkException
                  ? "STORAGE_UNAVAILABLE"
                  : "MEDIA_PROCESSING_FAILED";
      WorkerEvents.emit("probe_failed", id, token, code);
      failProbe(id, token, asset, code);
    } finally {
      valid.set(false);
      heartbeat.shutdownNow();
      deleteDirectory(directory);
      releaseDisk(reservation);
    }
  }

  private void failProbe(UUID id, UUID token, Map<String, Object> asset, String code) {
    try {
      tx.executeWithoutResult(
          status -> {
            if (jdbc.update(
                    "update media_probe_jobs set state='FAILED',error_code=?,completed_at=now() where id=? and attempt_token=? and state='RUNNING'",
                    code,
                    id,
                    token)
                > 0) {
              jdbc.update(
                  "update assets set state='INVALID',error_code=? where id=? and state='VALIDATING'",
                  code,
                  asset.get("id"));
            }
            if (jdbc.queryForObject(
                    "select count(*) from media_probe_jobs where id=? and attempt_token=? and state<>'SUCCEEDED'",
                    Integer.class,
                    id,
                    token)
                > 0) {
              uploads.enqueueCleanup((String) asset.get("staging_key"), null, null);
              uploads.enqueueCleanup((String) asset.get("storage_key"), null, null);
              uploads.enqueueCleanup("proxies/" + asset.get("id") + "/v1.mp4", null, null);
            }
          });
    } catch (org.springframework.dao.DataAccessException ignored) {
      /* Lease expiry persists the failure when DB returns. */
    }
  }

  private void expireLeases() {
    tx.executeWithoutResult(
        status -> {
          var expired =
              jdbc.queryForList(
                  "update media_probe_jobs set state='FAILED',error_code='WORKER_LOST',completed_at=now() where state='RUNNING' and lease_until<now() returning asset_id");
          for (var row : expired) {
            UUID asset = (UUID) row.get("asset_id");
            jdbc.update(
                "update assets set state='INVALID',error_code='WORKER_LOST' where id=? and state='VALIDATING'",
                asset);
            var keys =
                jdbc.queryForMap(
                    "select a.storage_key,u.staging_key from assets a join upload_sessions u on u.id=a.upload_id where a.id=?",
                    asset);
            uploads.enqueueCleanup((String) keys.get("storage_key"), null, null);
            uploads.enqueueCleanup((String) keys.get("staging_key"), null, null);
          }
          var expiredUploads =
              jdbc.queryForList(
                  "update upload_sessions set state='EXPIRED',error_code='UPLOAD_EXPIRED' where state='INITIATED' and expires_at<now() returning staging_key,multipart_id");
          for (var row : expiredUploads)
            uploads.enqueueCleanup(
                (String) row.get("staging_key"), (String) row.get("multipart_id"), null);
        });
  }

  private void recoverUploads() {
    var pending =
        jdbc.queryForList(
            "select u.id,u.completion_parts,p.owner_id from upload_sessions u join projects p on p.id=u.project_id where u.state='COMPLETING' order by u.created_at limit 1");
    for (var row : pending) {
      try {
        var body =
            CanonicalJson.MAPPER.treeToValue(
                CanonicalJson.parse(row.get("completion_parts").toString()),
                UploadService.Complete.class);
        uploads.complete((UUID) row.get("id"), (UUID) row.get("owner_id"), body);
      } catch (ApiException error) {
        if (error.status() == 422 || error.code().equals("UPLOAD_EXPIRED")) {
          tx.executeWithoutResult(
              status -> {
                var failed =
                    jdbc.queryForList(
                        "update upload_sessions set state='FAILED',error_code=? where id=? and state='COMPLETING' returning staging_key,multipart_id",
                        error.code(),
                        row.get("id"));
                for (var f : failed)
                  uploads.enqueueCleanup(
                      (String) f.get("staging_key"), (String) f.get("multipart_id"), null);
              });
        }
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Invalid durable upload manifest", error);
      }
    }
  }

  private void sweepTemporaryFiles() {
    if (!Files.isDirectory(root)) return;
    try (var jobs = Files.list(root)) {
      for (Path job : jobs.toList()) {
        UUID id;
        try {
          id = UUID.fromString(job.getFileName().toString());
        } catch (IllegalArgumentException ignored) {
          continue;
        }
        if (!Files.isDirectory(job, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
        if (jdbc.queryForObject(
                "select (select count(*) from render_jobs where id=?)+(select count(*) from media_probe_jobs where id=?)",
                Integer.class,
                id,
                id)
            == 0) continue;
        try (var attempts = Files.list(job)) {
          for (Path attempt : attempts.toList()) {
            UUID token;
            try {
              token = UUID.fromString(attempt.getFileName().toString());
            } catch (IllegalArgumentException ignored) {
              continue;
            }
            if (!Files.isDirectory(attempt, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.getLastModifiedTime(attempt).toMillis()
                    > System.currentTimeMillis() - cleanupTtlMillis) continue;
            int active =
                jdbc.queryForObject(
                    "select (select count(*) from render_jobs where id=? and attempt_token=? and state in ('RUNNING','CANCEL_REQUESTED') and lease_until>now())+(select count(*) from media_probe_jobs where id=? and attempt_token=? and state='RUNNING' and lease_until>now())",
                    Integer.class,
                    id,
                    token,
                    id,
                    token);
            if (active == 0) {
              deleteDirectory(attempt);
              WorkerEvents.emit("temporary_cleanup", id, token, null);
            }
          }
        }
        try (var remaining = Files.list(job)) {
          if (remaining.findAny().isEmpty()) Files.deleteIfExists(job);
        }
      }
    } catch (java.io.IOException ignored) {
      WorkerEvents.emit("temporary_cleanup_failed", null, null, "IO_UNAVAILABLE");
    }
  }

  public static void releaseDisk(AutoCloseable reservation) {
    if (reservation != null) {
      try {
        reservation.close();
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    }
  }

  public static String hash(Path file) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var stream = Files.newInputStream(file)) {
      byte[] bytes = new byte[65536];
      int read;
      while ((read = stream.read(bytes)) != -1) digest.update(bytes, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static void deleteDirectory(Path directory) {
    if (!Files.exists(directory)) return;
    try (var paths = Files.walk(directory)) {
      for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
        Files.deleteIfExists(path);
    } catch (java.io.IOException ignored) {
      /* Task-owned files can be recovered by the TTL cleanup operation. */
    }
  }
}
