package com.agenticvideo.worker;

import com.agenticvideo.api.CanonicalJson;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;

/** Only controlled codes and internal identifiers; no media, credentials or signed addresses. */
public final class WorkerEvents {
  private WorkerEvents() {}

  public static void emit(String event, UUID job, UUID attempt, String code) {
    LoggerFactory.getLogger("media.events")
        .info(
            CanonicalJson.text(
                Map.of(
                    "event",
                    event,
                    "job_id",
                    job == null ? "" : job.toString(),
                    "attempt",
                    attempt == null ? "" : attempt.toString(),
                    "code",
                    code == null ? "" : code)));
  }
}
