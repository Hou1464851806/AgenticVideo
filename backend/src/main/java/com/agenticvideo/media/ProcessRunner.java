package com.agenticvideo.media;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Executes only argument arrays. Cancels and drains both pipes while bounding retained output. */
public final class ProcessRunner {
  private final int stderrLimit;

  public ProcessRunner() {
    this(Integer.parseInt(System.getenv().getOrDefault("MEDIA_STDERR_BYTES", "8192")));
  }

  public ProcessRunner(int stderrLimit) {
    if (stderrLimit < 1024 || stderrLimit > 65536)
      throw new IllegalArgumentException("stderr limit must be 1024–65536 bytes");
    this.stderrLimit = stderrLimit;
  }

  public record Result(int exitCode, String stdout, String stderr) {}

  public static final class MediaFailure extends RuntimeException {
    private final String code;

    public MediaFailure(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  public Result run(List<String> args, Duration timeout, BooleanSupplier cancelled) {
    return run(args, timeout, cancelled, line -> {});
  }

  public Result run(
      List<String> args,
      Duration timeout,
      BooleanSupplier cancelled,
      java.util.function.Consumer<String> outputLine) {
    if (cancelled.getAsBoolean()) throw new MediaFailure("CANCELLED");
    try {
      var process = new ProcessBuilder(args).start();
      var stdout = new ByteArrayOutputStream();
      var stderr = new ByteArrayOutputStream();
      var out =
          Thread.ofVirtual().start(() -> drainLines(process.getInputStream(), stdout, outputLine));
      var err =
          Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr, stderrLimit));
      long deadline = System.nanoTime() + timeout.toNanos();
      try {
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
          if (cancelled.getAsBoolean()) throw new MediaFailure("CANCELLED");
          if (System.nanoTime() > deadline) throw new MediaFailure("PROCESS_TIMEOUT");
        }
        if (cancelled.getAsBoolean()) throw new MediaFailure("CANCELLED");
        out.join();
        err.join();
        return new Result(
            process.exitValue(),
            stdout.toString(java.nio.charset.StandardCharsets.UTF_8),
            stderr.toString(java.nio.charset.StandardCharsets.UTF_8));
      } finally {
        if (process.isAlive()) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MediaFailure("WORKER_INTERRUPTED");
    } catch (java.io.IOException e) {
      throw new MediaFailure("PROCESS_UNAVAILABLE");
    }
  }

  private static void drainLines(
      InputStream input,
      ByteArrayOutputStream output,
      java.util.function.Consumer<String> lineConsumer) {
    try (input) {
      byte[] buffer = new byte[8192];
      var line = new ByteArrayOutputStream();
      boolean oversized = false;
      int count;
      while ((count = input.read(buffer)) != -1) {
        int remaining = 1024 * 1024 - output.size();
        if (remaining > 0) output.write(buffer, 0, Math.min(count, remaining));
        for (int i = 0; i < count; i++) {
          if (buffer[i] == '\n') {
            if (!oversized) {
              try {
                lineConsumer.accept(line.toString(java.nio.charset.StandardCharsets.UTF_8));
              } catch (RuntimeException ignored) {
                /* Cancellation owns callback failures. */
              }
            }
            line.reset();
            oversized = false;
          } else if (line.size() < 4096) line.write(buffer[i]);
          else oversized = true;
        }
      }
    } catch (java.io.IOException ignored) {
      /* Process closed its pipe. */
    }
  }

  private static void drain(InputStream input, ByteArrayOutputStream output, int limit) {
    try (input) {
      byte[] bytes = new byte[8192];
      int read;
      while ((read = input.read(bytes)) != -1) {
        int remaining = limit - output.size();
        if (remaining > 0) output.write(bytes, 0, Math.min(read, remaining));
      }
    } catch (java.io.IOException ignored) {
      /* The parent process has closed the pipe. */
    }
  }
}
