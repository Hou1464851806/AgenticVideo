package com.agenticvideo.asset;

import com.agenticvideo.api.ApiException;
import com.agenticvideo.project.ProjectService;
import com.agenticvideo.storage.ObjectStorage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssetService {
  private final JdbcTemplate jdbc;
  private final ProjectService projects;
  private final UploadService uploads;
  private final ObjectStorage storage;
  private final TransactionTemplate tx;

  public AssetService(
      JdbcTemplate jdbc,
      ProjectService projects,
      UploadService uploads,
      ObjectStorage storage,
      TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.uploads = uploads;
    this.storage = storage;
    this.tx = tx;
  }

  public Map<String, Object> requireOwned(UUID id, UUID owner, boolean lock) {
    var rows =
        jdbc.queryForList(
            "select a.* from assets a join projects p on p.id=a.project_id where a.id=? and p.owner_id=?"
                + (lock ? " for update of a" : ""),
            id,
            owner);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.getFirst();
  }

  public Map<String, Object> get(UUID id, UUID owner) {
    return publicState(requireOwned(id, owner, false));
  }

  public Map<String, Object> list(UUID project, UUID owner, int offset, int limit) {
    projects.requireOwned(project, owner, false);
    ProjectService.offset(offset);
    ProjectService.limit(limit);
    return Map.of(
        "items",
        jdbc
            .queryForList(
                "select * from assets where project_id=? and state<>'DELETED' order by created_at desc,id limit ? offset ?",
                project,
                limit,
                offset)
            .stream()
            .map(AssetService::publicState)
            .toList(),
        "total",
        jdbc.queryForObject(
            "select count(*) from assets where project_id=? and state<>'DELETED'",
            Long.class,
            project),
        "offset",
        offset,
        "limit",
        limit);
  }

  public Map<String, Object> content(UUID id, UUID owner, boolean download) {
    var asset = requireOwned(id, owner, false);
    if (!asset.get("state").equals("MEDIA_READY"))
      throw new ApiException(409, "ASSET_NOT_READY", "素材尚不可播放");
    String key =
        download || asset.get("proxy_key") == null
            ? (String) asset.get("storage_key")
            : (String) asset.get("proxy_key");
    return Map.of("url", storage.signGet(key, download), "expires_in", 300);
  }

  public Map<String, Object> delete(UUID id, UUID owner) {
    var initial = requireOwned(id, owner, false);
    tx.executeWithoutResult(
        status -> {
          projects.requireOwned((UUID) initial.get("project_id"), owner, true);
          var asset = requireOwned(id, owner, true);
          if (List.of("DELETING", "DELETED").contains(asset.get("state"))) return;
          long active =
              jdbc.queryForObject(
                  "select count(*) from media_probe_jobs where asset_id=? and state in ('QUEUED','RUNNING')",
                  Long.class,
                  id);
          active +=
              jdbc.queryForObject(
                  "select count(*) from revision_asset_refs where asset_id=?", Long.class, id);
          if (active > 0) throw new ApiException(409, "ASSET_IN_USE", "素材仍被活动校验任务引用");
          jdbc.update("update assets set state='DELETING' where id=?", id);
          uploads.enqueueCleanup((String) asset.get("storage_key"), null, id);
          if (asset.get("proxy_key") != null)
            uploads.enqueueCleanup((String) asset.get("proxy_key"), null, id);
          jdbc.update(
              "update assets set state='DELETED' where id=? and state='DELETING' and not exists(select 1 from object_cleanup_tasks where asset_id=? and state<>'SUCCEEDED')",
              id,
              id);
        });
    return get(id, owner);
  }

  public static Map<String, Object> publicState(Map<String, Object> asset) {
    var output = new java.util.LinkedHashMap<String, Object>();
    for (String name :
        List.of(
            "id",
            "project_id",
            "filename",
            "file_size",
            "version",
            "content_hash",
            "duration_ms",
            "width",
            "height",
            "fps",
            "rotation",
            "has_audio",
            "analysis_status",
            "error_code",
            "created_at")) output.put(name, asset.get(name));
    output.put("status", asset.get("state"));
    return output;
  }
}
