package com.agenticvideo.asset;

import com.agenticvideo.api.ApiException;
import com.agenticvideo.api.CanonicalJson;
import com.agenticvideo.project.ProjectService;
import com.agenticvideo.storage.ObjectStorage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UploadService {
  public record Initialize(String filename, long file_size, String content_type) {}

  public record CompletedPart(int part_number, String etag) {}

  public record Complete(List<CompletedPart> parts) {}

  public record Presign(List<Integer> part_numbers) {}

  private final JdbcTemplate jdbc;
  private final ProjectService projects;
  private final ObjectStorage storage;
  private final TransactionTemplate tx;
  private final long maxBytes;
  private final int maxAssets;
  private final long maxProjectBytes;
  private final int partSize = 8 * 1024 * 1024;

  public UploadService(
      JdbcTemplate jdbc,
      ProjectService projects,
      ObjectStorage storage,
      TransactionTemplate tx,
      @Value("${app.limits.asset-bytes:4000000000}") long maxBytes,
      @Value("${app.limits.project-assets:30}") int maxAssets,
      @Value("${app.limits.project-bytes:120000000000}") long maxProjectBytes) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.storage = storage;
    this.tx = tx;
    this.maxBytes = maxBytes;
    this.maxAssets = maxAssets;
    this.maxProjectBytes = maxProjectBytes;
  }

  public Map<String, Object> initialize(UUID project, UUID owner, String key, Initialize body) {
    CanonicalJson.requireKey(key);
    if (body.filename() == null
        || body.filename().isBlank()
        || body.filename().length() > 255
        || (body.content_type() == null
            || !List.of("video/mp4", "video/quicktime").contains(body.content_type())))
      throw new ApiException(422, "UPLOAD_FORMAT_INVALID", "支持 MP4/MOV 素材");
    if (body.file_size() < 1 || body.file_size() > maxBytes)
      throw new ApiException(413, "ASSET_SIZE_EXCEEDED", "素材大小超过限制");
    String hash = CanonicalJson.hash(body);
    UUID id =
        tx.execute(
            status -> {
              projects.requireOwned(project, owner, true);
              var previous =
                  jdbc.queryForList(
                      "select id,request_hash from upload_sessions where project_id=? and idempotency_key=?",
                      project,
                      key);
              if (!previous.isEmpty()) {
                CanonicalJson.sameHash((String) previous.getFirst().get("request_hash"), hash);
                return (UUID) previous.getFirst().get("id");
              }
              long count =
                  jdbc.queryForObject(
                      "select (select count(*) from assets where project_id=? and state in ('VALIDATING','MEDIA_READY','DELETING'))+(select count(*) from upload_sessions where project_id=? and state in ('INITIATED','COMPLETING'))",
                      Long.class,
                      project,
                      project);
              long bytes =
                  jdbc.queryForObject(
                      "select coalesce((select sum(file_size) from assets where project_id=? and state in ('VALIDATING','MEDIA_READY','DELETING')),0)+coalesce((select sum(file_size) from upload_sessions where project_id=? and state in ('INITIATED','COMPLETING')),0)",
                      Long.class,
                      project,
                      project);
              if (count >= maxAssets || bytes + body.file_size() > maxProjectBytes)
                throw new ApiException(413, "PROJECT_QUOTA_EXCEEDED", "项目素材配额不足");
              UUID upload = UUID.randomUUID();
              jdbc.update(
                  "insert into upload_sessions(id,project_id,filename,content_type,file_size,part_size,total_parts,staging_key,state,idempotency_key,request_hash,expires_at) values (?,?,?,?,?,?,?,?,'INITIATED',?,?,?)",
                  upload,
                  project,
                  body.filename(),
                  body.content_type(),
                  body.file_size(),
                  partSize,
                  (body.file_size() + partSize - 1) / partSize,
                  "staging/" + project + "/" + upload,
                  key,
                  hash,
                  java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
              return upload;
            });
    tx.executeWithoutResult(
        status -> {
          var row = owned(id, owner, true);
          if (row.get("multipart_id") == null && row.get("state").equals("INITIATED"))
            jdbc.update(
                "update upload_sessions set multipart_id=? where id=?",
                storage.initialize((String) row.get("staging_key")),
                id);
        });
    return get(id, owner);
  }

  public Map<String, Object> get(UUID id, UUID owner) {
    return publicState(owned(id, owner, false));
  }

  public Map<String, Object> sign(UUID id, UUID owner, Presign body) {
    return tx.execute(
        status -> {
          var row = owned(id, owner, true);
          active(row);
          if (!row.get("state").equals("INITIATED"))
            throw new ApiException(409, "UPLOAD_STATE_CONFLICT", "上传已结束或正在完成");
          if (body.part_numbers() == null
              || body.part_numbers().isEmpty()
              || body.part_numbers().size() > 20
              || new java.util.HashSet<>(body.part_numbers()).size() != body.part_numbers().size())
            throw new ApiException(422, "UPLOAD_PARTS_INVALID", "分片序号无效");
          int count = ((Number) row.get("total_parts")).intValue();
          for (var n : body.part_numbers())
            if (n == null || n < 1 || n > count)
              throw new ApiException(422, "UPLOAD_PARTS_INVALID", "分片序号超出范围");
          return Map.of(
              "parts",
              body.part_numbers().stream()
                  .map(
                      n ->
                          Map.of(
                              "part_number",
                              n,
                              "url",
                              storage.signPart(
                                  (String) row.get("staging_key"),
                                  (String) row.get("multipart_id"),
                                  n)))
                  .toList());
        });
  }

  public Map<String, Object> complete(UUID id, UUID owner, Complete body) {
    String hash = CanonicalJson.hash(body);
    var intent =
        tx.execute(
            status -> {
              var row = owned(id, owner, true);
              if (row.get("state").equals("COMPLETED")) {
                CanonicalJson.sameHash((String) row.get("completion_hash"), hash);
                return row;
              }
              active(row);
              if (row.get("state").equals("COMPLETING")) {
                CanonicalJson.sameHash((String) row.get("completion_hash"), hash);
                return row;
              }
              if (!row.get("state").equals("INITIATED"))
                throw new ApiException(409, "UPLOAD_STATE_CONFLICT", "上传不可完成");
              int count = ((Number) row.get("total_parts")).intValue();
              if (body.parts() == null || body.parts().size() != count)
                throw new ApiException(422, "UPLOAD_PARTS_INVALID", "分片列表不完整");
              for (int i = 0; i < count; i++) {
                var part = body.parts().get(i);
                if (part == null
                    || part.part_number() != i + 1
                    || part.etag() == null
                    || !part.etag().matches("\"?[0-9a-fA-F]{32}\"?"))
                  throw new ApiException(422, "UPLOAD_PARTS_INVALID", "分片序号或 ETag 无效");
              }
              jdbc.update(
                  "update upload_sessions set state='COMPLETING',completion_hash=?,completion_parts=?::jsonb where id=?",
                  hash,
                  CanonicalJson.text(body),
                  id);
              return row;
            });
    if (intent.get("state").equals("COMPLETED")) return publicState(intent);
    try {
      tx.executeWithoutResult(
          status -> {
            var row = owned(id, owner, true);
            if (row.get("state").equals("COMPLETED")) return;
            if (!row.get("state").equals("COMPLETING"))
              throw new ApiException(409, "UPLOAD_STATE_CONFLICT", "上传状态已改变");
            String staging = (String) row.get("staging_key");
            Long bytes = storage.sizeIfPresent(staging);
            if (bytes == null) {
              try {
                storage.complete(
                    staging,
                    (String) row.get("multipart_id"),
                    body.parts().stream()
                        .map(p -> new ObjectStorage.Part(p.part_number(), p.etag()))
                        .toList());
              } catch (software.amazon.awssdk.services.s3.model.S3Exception error) {
                if (List.of("InvalidPart", "InvalidPartOrder", "EntityTooSmall", "NoSuchUpload")
                    .contains(error.awsErrorDetails().errorCode()))
                  throw new ApiException(422, "UPLOAD_PARTS_INVALID", "分片与已上传数据不匹配");
                throw error;
              }
              bytes = storage.sizeIfPresent(staging);
            }
            if (bytes == null || bytes.longValue() != ((Number) row.get("file_size")).longValue())
              throw new ApiException(422, "UPLOAD_SIZE_MISMATCH", "实际上传大小与声明不一致");
            UUID asset = UUID.randomUUID();
            String sealed = "assets/" + row.get("project_id") + "/" + asset + "/v1";
            jdbc.update(
                "insert into assets(id,upload_id,project_id,filename,file_size,state,storage_key) values (?,?,?,?,?,'VALIDATING',?)",
                asset,
                id,
                row.get("project_id"),
                row.get("filename"),
                bytes,
                sealed);
            jdbc.update(
                "insert into media_probe_jobs(id,asset_id) values (?,?)", UUID.randomUUID(), asset);
            jdbc.update(
                "update upload_sessions set state='COMPLETED',asset_id=? where id=?", asset, id);
          });
    } catch (ApiException error) {
      if (error.status() == 422) failCompletion(id, error.code());
      throw error;
    }
    return get(id, owner);
  }

  public void failCompletion(UUID id, String code) {
    tx.executeWithoutResult(
        status -> {
          var rows =
              jdbc.queryForList(
                  "update upload_sessions set state='FAILED',error_code=? where id=? and state='COMPLETING' returning staging_key,multipart_id",
                  code,
                  id);
          for (var row : rows)
            enqueueCleanup((String) row.get("staging_key"), (String) row.get("multipart_id"), null);
        });
  }

  public Map<String, Object> abort(UUID id, UUID owner) {
    tx.executeWithoutResult(
        status -> {
          var row = owned(id, owner, true);
          if (row.get("state").equals("ABORTED")) return;
          if (!row.get("state").equals("INITIATED"))
            throw new ApiException(409, "UPLOAD_STATE_CONFLICT", "上传正在完成或已结束");
          jdbc.update("update upload_sessions set state='ABORTED' where id=?", id);
          enqueueCleanup((String) row.get("staging_key"), (String) row.get("multipart_id"), null);
        });
    return get(id, owner);
  }

  public void enqueueCleanup(String key, String multipart, UUID asset) {
    jdbc.update(
        "insert into object_cleanup_tasks(id,object_key,multipart_id,asset_id) values (?,?,?,?) on conflict(object_key) do update set asset_id=coalesce(object_cleanup_tasks.asset_id,excluded.asset_id),generation=object_cleanup_tasks.generation+1,state='QUEUED',attempts=0,next_attempt_at=now(),error_code=null",
        UUID.randomUUID(),
        key,
        multipart,
        asset);
  }

  private Map<String, Object> owned(UUID id, UUID owner, boolean lock) {
    var rows =
        jdbc.queryForList(
            "select u.* from upload_sessions u join projects p on p.id=u.project_id where u.id=? and p.owner_id=?"
                + (lock ? " for update of u" : ""),
            id,
            owner);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.getFirst();
  }

  private static void active(Map<String, Object> row) {
    if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now()))
      throw new ApiException(409, "UPLOAD_EXPIRED", "上传会话已过期");
  }

  private static Map<String, Object> publicState(Map<String, Object> row) {
    var result = new java.util.LinkedHashMap<String, Object>();
    for (String field :
        List.of(
            "id", "project_id", "part_size", "total_parts", "asset_id", "expires_at", "error_code"))
      result.put(field, row.get(field));
    result.put("status", row.get("state"));
    return result;
  }
}
