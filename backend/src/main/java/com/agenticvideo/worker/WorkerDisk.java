package com.agenticvideo.worker;

import com.agenticvideo.media.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shares the configured scratch budget across all media executions in this process. */
@Component
public class WorkerDisk {
  private final long budget;
  private long reserved;

  public WorkerDisk(@Value("${app.worker.disk-bytes:32000000000}") long budget) {
    if (budget < 1) throw new IllegalArgumentException("Disk budget must be positive");
    this.budget = budget;
  }

  public synchronized AutoCloseable reserve(Path directory, long bytes) throws IOException {
    if (bytes < 1
        || bytes > budget - reserved
        || Files.getFileStore(directory).getUsableSpace() < bytes + reserved)
      throw new ProcessRunner.MediaFailure("DISK_INSUFFICIENT");
    reserved += bytes;
    return new AutoCloseable() {
      private boolean released;

      @Override
      public void close() {
        synchronized (WorkerDisk.this) {
          if (!released) {
            reserved -= bytes;
            released = true;
          }
        }
      }
    };
  }
}
