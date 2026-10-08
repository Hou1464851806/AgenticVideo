package com.agenticvideo.rendering;

import com.agenticvideo.media.ProcessRunner;
import com.agenticvideo.storage.ObjectStorage;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Recovers an ambiguous DB acknowledgement without uploading or creating a second artifact. */
@Component
public class OutputCommitter {
  public record Output(
      String key, String hash, long bytes, String ffmpegVersion, String compilerHash) {}

  private final JdbcTemplate jdbc;
  private final ObjectStorage storage;

  public OutputCommitter(JdbcTemplate jdbc, ObjectStorage storage) {
    this.jdbc = jdbc;
    this.storage = storage;
  }

  public void commit(UUID id, UUID token, Output output, BooleanSupplier timedOut) {
    for (int attempt = 0; attempt < 3; attempt++) {
      if (timedOut.getAsBoolean()) throw new ProcessRunner.MediaFailure("RENDER_TIMEOUT");
      try {
        if (!Long.valueOf(output.bytes()).equals(storage.sizeIfPresent(output.key())))
          throw new ProcessRunner.MediaFailure("OUTPUT_STORAGE_INVALID");
        var rows =
            jdbc.queryForList(
                "select state,output_key,output_hash,attempt_token from render_jobs where id=?",
                id);
        if (rows.isEmpty()) throw new ProcessRunner.MediaFailure("WORKER_LOST");
        var row = rows.getFirst();
        if (row.get("state").equals("SUCCEEDED")
            && token.equals(row.get("attempt_token"))
            && output.key().equals(row.get("output_key"))
            && output.hash().equals(row.get("output_hash"))) return;
        int written =
            jdbc.update(
                "update render_jobs set state='SUCCEEDED',progress=100,output_key=?,output_hash=?,output_bytes=?,ffmpeg_version=?,compiler_hash=?,completed_at=now() where id=? and attempt_token=? and state='RUNNING' and lease_until>now()",
                output.key(),
                output.hash(),
                output.bytes(),
                output.ffmpegVersion(),
                output.compilerHash(),
                id,
                token);
        if (written == 0) throw new ProcessRunner.MediaFailure("WORKER_LOST");
        return;
      } catch (DataAccessException error) {
        if (attempt == 2) throw error;
        try {
          Thread.sleep(100L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new ProcessRunner.MediaFailure("WORKER_INTERRUPTED");
        }
      }
    }
  }
}
