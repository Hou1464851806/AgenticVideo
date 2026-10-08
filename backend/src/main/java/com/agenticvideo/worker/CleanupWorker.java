package com.agenticvideo.worker;

import com.agenticvideo.storage.ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable object cleanup with generations fencing late completions. */
@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class CleanupWorker {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectStorage storage;

  public CleanupWorker(JdbcTemplate jdbc, TransactionTemplate tx, ObjectStorage storage) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.storage = storage;
  }

  public void tick() {
    var task =
        tx.execute(
            status -> {
              var rows =
                  jdbc.queryForList(
                      "select * from object_cleanup_tasks where state in ('QUEUED','RUNNING') and next_attempt_at<=now() order by created_at for update skip locked limit 1");
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              jdbc.update(
                  "update object_cleanup_tasks set state='RUNNING',attempts=attempts+1,next_attempt_at=now()+interval '30 seconds' where id=?",
                  row.get("id"));
              return row;
            });
    if (task == null) return;
    try {
      storage.abort((String) task.get("object_key"), (String) task.get("multipart_id"));
      storage.delete((String) task.get("object_key"));
      tx.executeWithoutResult(
          status -> {
            jdbc.update(
                "update object_cleanup_tasks set state='SUCCEEDED',error_code=null where id=? and generation=?",
                task.get("id"),
                task.get("generation"));
            if (task.get("asset_id") != null)
              jdbc.update(
                  "update assets set state='DELETED' where id=? and state='DELETING' and not exists(select 1 from object_cleanup_tasks where asset_id=? and state<>'SUCCEEDED')",
                  task.get("asset_id"),
                  task.get("asset_id"));
          });
    } catch (software.amazon.awssdk.core.exception.SdkException error) {
      jdbc.update(
          "update object_cleanup_tasks set state=case when attempts>=5 then 'FAILED' else 'QUEUED' end,error_code='STORAGE_UNAVAILABLE',next_attempt_at=now()+interval '30 seconds' where id=? and generation=?",
          task.get("id"),
          task.get("generation"));
    }
  }
}
