package com.agenticvideo;

import com.agenticvideo.media.FFmpegExecutor;
import com.agenticvideo.media.MediaProbe;
import com.agenticvideo.media.ProcessRunner;
import com.agenticvideo.timeline.TimelineCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Runs in the pinned media image against generated public fixtures; throws on observable errors.
 */
public final class MediaExperiment {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]).toAbsolutePath();
    Path fixtures = root.resolve(".runtime/media");
    var probe = new MediaProbe();
    var first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    var inputs = new HashMap<UUID, FFmpegExecutor.Input>();
    for (var fixture : List.of("red", "blue", "silent", "rotated", "vfr-pts", "short-audio")) {
      Path input = fixtures.resolve(fixture + (fixture.equals("rotated") ? ".mov" : ".mp4"));
      var metadata = probe.probe(input, () -> false);
      check(
          metadata.durationMs() > 2000 && metadata.durationMs() <= 3100,
          "source duration " + fixture);
      probe.decode(input, () -> false);
    }
    boolean rejected = false;
    try {
      probe.probe(fixtures.resolve("corrupt.mp4"), () -> false);
    } catch (ProcessRunner.MediaFailure e) {
      rejected = e.code().equals("MEDIA_INVALID");
    }
    check(rejected, "corrupt file must fail");
    var json = new ObjectMapper();
    var plan = json.readTree(root.resolve("contracts/fixtures/valid.json").toFile());
    var compiler = new TimelineCompiler();
    for (var pair :
        List.of(
            List.of("red.mp4", "blue.mp4"),
            List.of("rotated.mov", "silent.mp4"),
            List.of("vfr-pts.mp4", "short-audio.mp4"))) {
      inputs.put(
          first,
          new FFmpegExecutor.Input(
              fixtures.resolve(pair.get(0)),
              probe.probe(fixtures.resolve(pair.get(0)), () -> false)));
      inputs.put(
          second,
          new FFmpegExecutor.Input(
              fixtures.resolve(pair.get(1)),
              probe.probe(fixtures.resolve(pair.get(1)), () -> false)));
      for (var profile : TimelineCompiler.Profile.values()) {
        var spec = compiler.compile(plan, profile);
        Path directory =
            fixtures.resolve("result-" + pair.get(0).replace('.', '-') + "-" + profile);
        Path output = new FFmpegExecutor().render(spec, inputs, directory, () -> false, p -> {});
        var actual = probe.probe(output, () -> false);
        check(Math.abs(actual.durationMs() - 2000) <= 34, "duration");
        check(actual.width() == spec.width() && actual.height() == spec.height(), "dimensions");
        check(actual.fps().equals("30/1"), "fps");
        if (pair.get(0).equals("red.mp4")) {
          checkColor(output, directory, 0.4, true);
          checkColor(output, directory, 1.4, false);
          checkTone(output, directory, 0.05, 440);
          checkTone(output, directory, 0.65, 660);
          checkTone(output, directory, 1.2, 880);
          checkSynchronization(output, directory);
        }
        System.out.println(
            "PASS "
                + pair
                + " "
                + profile
                + " "
                + actual.durationMs()
                + "ms "
                + actual.width()
                + "x"
                + actual.height());
      }
    }
    // Fractional boundaries and sub-frame plans keep total duration within one output frame.
    inputs.put(
        first,
        new FFmpegExecutor.Input(
            fixtures.resolve("red.mp4"), probe.probe(fixtures.resolve("red.mp4"), () -> false)));
    inputs.put(
        second,
        new FFmpegExecutor.Input(
            fixtures.resolve("blue.mp4"), probe.probe(fixtures.resolve("blue.mp4"), () -> false)));
    var fractional = plan.deepCopy();
    var firstClip =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            fractional.path("tracks").get(0).path("clips").get(0);
    var secondClip =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            fractional.path("tracks").get(0).path("clips").get(1);
    firstClip.put("source_in_ms", 501).put("source_out_ms", 1487);
    secondClip.put("source_in_ms", 1011).put("source_out_ms", 1983).put("timeline_start_ms", 986);
    ((com.fasterxml.jackson.databind.node.ObjectNode) fractional.path("constraints"))
        .put("target_duration_ms", 1958);
    var fractionalSpec = compiler.compile(fractional, TimelineCompiler.Profile.PREVIEW_720P);
    var fractionalOutput =
        new FFmpegExecutor()
            .render(fractionalSpec, inputs, fixtures.resolve("fractional"), () -> false, p -> {});
    check(
        Math.abs(probe.probe(fractionalOutput, () -> false).durationMs() - 1958) <= 34,
        "fractional duration");
    var shortPlan = plan.deepCopy();
    var shortClips =
        (com.fasterxml.jackson.databind.node.ArrayNode)
            shortPlan.path("tracks").get(0).path("clips");
    shortClips.remove(1);
    ((com.fasterxml.jackson.databind.node.ObjectNode) shortClips.get(0))
        .put("source_in_ms", 501)
        .put("source_out_ms", 502);
    ((com.fasterxml.jackson.databind.node.ObjectNode) shortPlan.path("constraints"))
        .put("target_duration_ms", 1);
    var shortSpec = compiler.compile(shortPlan, TimelineCompiler.Profile.PREVIEW_720P);
    new FFmpegExecutor()
        .render(shortSpec, inputs, fixtures.resolve("one-ms"), () -> false, p -> {});
    System.out.println("PASS fractional boundaries and 1ms plan");
    // Portrait fit/crop mix, static output guarantees rotation happens before resize.
    ((com.fasterxml.jackson.databind.node.ObjectNode) plan.path("output"))
        .put("width", 1080)
        .put("height", 1920);
    var portrait = compiler.compile(plan, TimelineCompiler.Profile.PREVIEW_720P);
    var output =
        new FFmpegExecutor()
            .render(portrait, inputs, fixtures.resolve("portrait"), () -> false, p -> {});
    check(probe.probe(output, () -> false).width() == 720, "portrait");
    System.out.println("PASS portrait and corrupt-media rejection");
  }

  private static void checkColor(Path output, Path directory, double time, boolean red)
      throws Exception {
    Path sample = directory.resolve("pixel-" + time + ".rgb");
    run(
        List.of(
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-ss",
            Double.toString(time),
            "-i",
            output.toString(),
            "-vf",
            "scale=1:1",
            "-frames:v",
            "1",
            "-pix_fmt",
            "rgb24",
            "-f",
            "rawvideo",
            sample.toString()));
    byte[] pixel = Files.readAllBytes(sample);
    check(pixel.length == 3, "pixel sample");
    int r = Byte.toUnsignedInt(pixel[0]), b = Byte.toUnsignedInt(pixel[2]);
    check(red ? r > 200 && b < 30 : b > 200 && r < 30, "clip order/color");
  }

  private static void checkTone(Path output, Path directory, double time, int expected)
      throws Exception {
    Path sample = directory.resolve("audio-" + time + ".f32");
    run(
        List.of(
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-ss",
            Double.toString(time),
            "-i",
            output.toString(),
            "-t",
            "0.3",
            "-map",
            "0:a:0",
            "-ac",
            "1",
            "-ar",
            "48000",
            "-f",
            "f32le",
            sample.toString()));
    var floats =
        ByteBuffer.wrap(Files.readAllBytes(sample)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    float previous = floats.get();
    int crossings = 0, samples = floats.remaining();
    double power = 0;
    while (floats.hasRemaining()) {
      float value = floats.get();
      if (previous <= 0 && value > 0) crossings++;
      previous = value;
      power += value * value;
    }
    double frequency = crossings * 48000.0 / samples;
    check(
        Math.abs(frequency - expected) < 8 && power / samples > 0.001,
        "tone selection " + frequency);
  }

  private static void checkSynchronization(Path output, Path directory) throws Exception {
    Path pixels = directory.resolve("frames.rgb"), audio = directory.resolve("full.f32");
    run(
        List.of(
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            output.toString(),
            "-vf",
            "scale=1:1",
            "-pix_fmt",
            "rgb24",
            "-f",
            "rawvideo",
            pixels.toString()));
    run(
        List.of(
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            output.toString(),
            "-map",
            "0:a:0",
            "-ac",
            "1",
            "-ar",
            "48000",
            "-f",
            "f32le",
            audio.toString()));
    byte[] rgb = Files.readAllBytes(pixels);
    int videoFrame = -1;
    for (int i = 0; i < rgb.length / 3; i++)
      if (Byte.toUnsignedInt(rgb[i * 3 + 1]) > 150) {
        videoFrame = i;
        break;
      }
    var data =
        ByteBuffer.wrap(Files.readAllBytes(audio)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    float[] samples = new float[data.remaining()];
    data.get(samples);
    double audioTransition = -1;
    int window = 1024;
    for (int start = 18000; start < 30000; start += 240) {
      int crossings = 0;
      for (int i = start + 1; i < start + window; i++)
        if (samples[i - 1] <= 0 && samples[i] > 0) crossings++;
      if (crossings * 48000.0 / window > 550) {
        audioTransition = (start + window / 2.0) / 48000;
        break;
      }
    }
    check(videoFrame >= 0 && audioTransition >= 0, "AV transition found");
    double differenceMs = Math.abs(videoFrame / 30.0 - audioTransition) * 1000;
    check(differenceMs <= 54.7, "AV synchronization " + differenceMs + "ms");
    check(Math.abs(videoFrame / 30.0 - 0.5) * 1000 <= 33.4, "source trim boundary");
    System.out.println("PASS AV synchronization " + differenceMs + "ms");
  }

  private static void run(List<String> args) {
    check(
        new ProcessRunner().run(args, Duration.ofSeconds(30), () -> false).exitCode() == 0,
        "sample decode");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
