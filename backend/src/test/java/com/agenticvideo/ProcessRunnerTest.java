package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticvideo.media.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProcessRunnerTest {
  @Test
  void hugeSingleLineIsDrainedWithBoundedRetention() {
    var callbacks = new AtomicInteger();
    var result =
        new ProcessRunner()
            .run(
                List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    OutputChild.class.getName()),
                Duration.ofSeconds(10),
                () -> false,
                line -> callbacks.incrementAndGet());
    assertEquals(0, result.exitCode());
    assertEquals(1048576, result.stdout().length());
    assertEquals(8192, result.stderr().length());
    assertEquals(1, callbacks.get());
  }

  @Test
  void cancellationBeforeLaunchRejectsWithoutStartingProcess() {
    var failure =
        assertThrows(
            ProcessRunner.MediaFailure.class,
            () ->
                new ProcessRunner()
                    .run(List.of("must-not-be-launched"), Duration.ofSeconds(1), () -> true));
    assertEquals("CANCELLED", failure.code());
  }

  public static class OutputChild {
    public static void main(String[] args) {
      System.out.print("x".repeat(2 * 1024 * 1024));
      System.out.print("\nout_time_us=10\n");
      System.err.print("e".repeat(20000));
    }
  }
}
