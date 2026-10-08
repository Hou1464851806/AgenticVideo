package com.agenticvideo.timeline;

import com.agenticvideo.api.ApiException;
import com.agenticvideo.api.CanonicalJson;
import com.agenticvideo.project.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PlanService {
  public record Create(String name, Map<String, Object> dsl) {}

  public record Update(Map<String, Object> dsl) {}

  public record Approve(int revision, String render_profile) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ProjectService projects;
  private final long maxDuration;
  private final PlanValidator validator = new PlanValidator();

  public PlanService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ProjectService projects,
      @Value("${app.limits.output-duration-ms:600000}") long maxDuration) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.projects = projects;
    this.maxDuration = maxDuration;
  }

  public Map<String, Object> create(UUID project, UUID owner, String key, Create body) {
    CanonicalJson.requireKey(key);
    if (body.name() == null || body.name().isBlank() || body.name().length() > 200)
      throw new ApiException(422, "PLAN_NAME_INVALID", "方案名称为 1–200 字");
    return tx.execute(
        status -> {
          projects.requireOwned(project, owner, true);
          String hash = CanonicalJson.hash(body);
          var previous =
              jdbc.queryForList(
                  "select id,request_hash from edit_plans where project_id=? and idempotency_key=?",
                  project,
                  key);
          if (!previous.isEmpty()) {
            CanonicalJson.sameHash((String) previous.getFirst().get("request_hash"), hash);
            return revision((UUID) previous.getFirst().get("id"), owner, 1);
          }
          var dsl = CanonicalJson.tree(body.dsl());
          var assets = validate(project, dsl, true);
          UUID id = UUID.randomUUID();
          jdbc.update(
              "insert into edit_plans(id,project_id,name,current_revision,idempotency_key,request_hash) values (?,?,?,1,?,?)",
              id,
              project,
              body.name().trim(),
              key,
              hash);
          insertRevision(id, 1, owner, key, hash, dsl, assets);
          return revision(id, owner, 1);
        });
  }

  public Map<String, Object> update(
      UUID id, UUID owner, String key, String expectedHeader, Update body) {
    CanonicalJson.requireKey(key);
    if (expectedHeader == null)
      throw new ApiException(422, "EXPECTED_REVISION_REQUIRED", "请提供 If-Match 版本");
    if (!expectedHeader.matches("\"revision-[1-9][0-9]*\""))
      throw new ApiException(422, "EXPECTED_REVISION_INVALID", "If-Match 格式无效");
    int expected;
    try {
      expected = Integer.parseInt(expectedHeader.substring(10, expectedHeader.length() - 1));
    } catch (NumberFormatException e) {
      throw new ApiException(422, "EXPECTED_REVISION_INVALID", "版本超出范围");
    }
    validator.validateStructure(CanonicalJson.tree(body.dsl()), maxDuration);
    int expectedVersion = expected;
    var initial = owned(id, owner, false);
    return tx.execute(
        status -> {
          UUID project = (UUID) initial.get("project_id");
          projects.requireOwned(project, owner, true);
          var plan = owned(id, owner, true);
          String hash =
              CanonicalJson.hash(Map.of("dsl", body.dsl(), "expected_revision", expectedVersion));
          var previous =
              jdbc.queryForList(
                  "select revision,request_hash from edit_plan_revisions where plan_id=? and idempotency_key=?",
                  id,
                  key);
          if (!previous.isEmpty()) {
            CanonicalJson.sameHash((String) previous.getFirst().get("request_hash"), hash);
            return revision(id, owner, ((Number) previous.getFirst().get("revision")).intValue());
          }
          int current = ((Number) plan.get("current_revision")).intValue();
          if (current != expectedVersion)
            throw new ApiException(409, "EDIT_PLAN_VERSION_CONFLICT", "方案已更新，请重新读取当前版本");
          var dsl = CanonicalJson.tree(body.dsl());
          var assets = validate(project, dsl, true);
          int next = Math.addExact(current, 1);
          insertRevision(id, next, owner, key, hash, dsl, assets);
          jdbc.update("update edit_plans set current_revision=? where id=?", next, id);
          return revision(id, owner, next);
        });
  }

  public Map<String, Object> owned(UUID id, UUID owner, boolean lock) {
    var rows =
        jdbc.queryForList(
            "select e.* from edit_plans e join projects p on p.id=e.project_id where e.id=? and p.owner_id=?"
                + (lock ? " for update of e" : ""),
            id,
            owner);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.getFirst();
  }

  public Map<String, Object> current(UUID id, UUID owner) {
    return revision(
        id, owner, ((Number) owned(id, owner, false).get("current_revision")).intValue());
  }

  public Map<String, Object> revision(UUID id, UUID owner, int version) {
    var plan = owned(id, owner, false);
    var rows =
        jdbc.queryForList(
            "select plan_data,plan_hash from edit_plan_revisions where plan_id=? and revision=?",
            id,
            version);
    if (rows.isEmpty()) throw ApiException.notFound();
    var row = rows.getFirst();
    return Map.of(
        "plan_id",
        id,
        "project_id",
        plan.get("project_id"),
        "name",
        plan.get("name"),
        "revision",
        version,
        "plan_hash",
        row.get("plan_hash"),
        "dsl",
        CanonicalJson.MAPPER.convertValue(
            CanonicalJson.parse(row.get("plan_data").toString()), Map.class));
  }

  public Map<String, Object> list(UUID project, UUID owner, int offset, int limit) {
    projects.requireOwned(project, owner, false);
    ProjectService.offset(offset);
    ProjectService.limit(limit);
    var plans =
        jdbc.queryForList(
            "select id,current_revision from edit_plans where project_id=? order by created_at desc,id limit ? offset ?",
            project,
            limit,
            offset);
    return Map.of(
        "items",
        plans.stream()
            .map(
                p ->
                    revision(
                        (UUID) p.get("id"), owner, ((Number) p.get("current_revision")).intValue()))
            .toList(),
        "total",
        jdbc.queryForObject(
            "select count(*) from edit_plans where project_id=?", Long.class, project),
        "offset",
        offset,
        "limit",
        limit);
  }

  public Map<String, Object> history(UUID id, UUID owner, int offset, int limit) {
    owned(id, owner, false);
    ProjectService.offset(offset);
    ProjectService.limit(limit);
    var revisions =
        jdbc.queryForList(
            "select revision from edit_plan_revisions where plan_id=? order by revision desc limit ? offset ?",
            id,
            limit,
            offset);
    return Map.of(
        "items",
        revisions.stream()
            .map(r -> revision(id, owner, ((Number) r.get("revision")).intValue()))
            .toList(),
        "total",
        jdbc.queryForObject(
            "select count(*) from edit_plan_revisions where plan_id=?", Long.class, id),
        "offset",
        offset,
        "limit",
        limit);
  }

  public Map<String, Object> check(UUID id, UUID owner) {
    var revision = current(id, owner);
    return Map.of(
        "valid",
        true,
        "duration_ms",
        validator.validate(
            CanonicalJson.tree(revision.get("dsl")),
            (UUID) revision.get("project_id"),
            validate(
                (UUID) revision.get("project_id"), CanonicalJson.tree(revision.get("dsl")), false),
            maxDuration));
  }

  public Map<String, Object> approve(UUID id, UUID owner, Approve body) {
    if (!"FINAL_1080P".equals(body.render_profile()))
      throw new ApiException(422, "RENDER_PROFILE_INVALID", "请确认 FINAL_1080P 参数");
    var initial = owned(id, owner, false);
    return tx.execute(
        status -> {
          projects.requireOwned((UUID) initial.get("project_id"), owner, true);
          var revision = revision(id, owner, body.revision());
          var assets =
              validate(
                  (UUID) revision.get("project_id"), CanonicalJson.tree(revision.get("dsl")), true);
          var versions = new java.util.TreeMap<String, Integer>();
          assets.forEach((asset, metadata) -> versions.put(asset.toString(), metadata.version()));
          jdbc.update(
              "insert into plan_approvals(plan_id,revision,plan_hash,asset_versions,final_profile,approved_by) values (?,?,?,?::jsonb,?,?) on conflict do nothing",
              id,
              body.revision(),
              revision.get("plan_hash"),
              CanonicalJson.text(versions),
              body.render_profile(),
              owner);
          return revision;
        });
  }

  public Map<UUID, PlanValidator.Asset> validate(UUID project, JsonNode dsl, boolean lock) {
    validator.validateStructure(dsl, maxDuration);
    var ids = new TreeSet<UUID>();
    dsl.path("tracks")
        .get(0)
        .path("clips")
        .forEach(c -> ids.add(UUID.fromString(c.path("asset_id").asText())));
    var assets = new HashMap<UUID, PlanValidator.Asset>();
    for (UUID id : ids) {
      var rows =
          jdbc.queryForList(
              "select id,project_id,version,duration_ms,state from assets where id=?"
                  + (lock ? " for update" : ""),
              id);
      if (!rows.isEmpty()) {
        var row = rows.getFirst();
        assets.put(
            id,
            new PlanValidator.Asset(
                id,
                (UUID) row.get("project_id"),
                ((Number) row.get("version")).intValue(),
                row.get("duration_ms") == null ? 0 : ((Number) row.get("duration_ms")).longValue(),
                row.get("state").equals("MEDIA_READY")));
      }
    }
    validator.validate(dsl, project, assets, maxDuration);
    return assets;
  }

  private void insertRevision(
      UUID id,
      int revision,
      UUID owner,
      String key,
      String requestHash,
      JsonNode dsl,
      Map<UUID, PlanValidator.Asset> assets) {
    jdbc.update(
        "insert into edit_plan_revisions(plan_id,revision,plan_data,plan_hash,created_by,idempotency_key,request_hash) values (?,?,?::jsonb,?,?,?,?)",
        id,
        revision,
        CanonicalJson.text(dsl),
        CanonicalJson.hash(dsl),
        owner,
        key,
        requestHash);
    for (var asset : assets.values())
      jdbc.update(
          "insert into revision_asset_refs(plan_id,revision,asset_id,asset_version) values (?,?,?,?)",
          id,
          revision,
          asset.id(),
          asset.version());
  }
}
