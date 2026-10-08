package com.agenticvideo.rendering;

import com.agenticvideo.api.ApiException;
import com.agenticvideo.api.CanonicalJson;
import com.agenticvideo.project.ProjectService;
import com.agenticvideo.storage.ObjectStorage;
import com.agenticvideo.timeline.PlanService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RenderService {
  public record Create(UUID plan_id, int revision, String render_profile) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final PlanService plans;
  private final ProjectService projects;
  private final ObjectStorage storage;
  private final int projectLimit;
  private final int userLimit;

  public RenderService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      PlanService plans,
      ProjectService projects,
      ObjectStorage storage,
      @Value("${app.limits.project-pending-jobs:5}") int projectLimit,
      @Value("${app.limits.user-pending-jobs:10}") int userLimit) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.plans = plans;
    this.projects = projects;
    this.storage = storage;
    this.projectLimit = projectLimit;
    this.userLimit = userLimit;
  }

  public Map<String, Object> create(UUID owner, String key, Create body) {
    return enqueue(owner, key, body, null);
  }

  private Map<String, Object> enqueue(UUID owner, String key, Create body, UUID retry) {
    CanonicalJson.requireKey(key);
    if (body.plan_id() == null
        || body.revision() < 1
        || !("PREVIEW_720P".equals(body.render_profile())
            || "FINAL_1080P".equals(body.render_profile())))
      throw new ApiException(422, "RENDER_REQUEST_INVALID", "请指定版本与输出参数");
    var plan = plans.owned(body.plan_id(), owner, false);
    return tx.execute(
        status -> {
          jdbc.queryForList("select id from users where id=? for update", owner);
          UUID project = (UUID) plan.get("project_id");
          projects.requireOwned(project, owner, true);
          String hash =
              CanonicalJson.hash(
                  Map.of("request", body, "retry_of", retry == null ? "" : retry.toString()));
          var previous =
              jdbc.queryForList(
                  "select * from render_jobs where owner_id=? and idempotency_key=?", owner, key);
          if (!previous.isEmpty()) {
            CanonicalJson.sameHash((String) previous.getFirst().get("request_hash"), hash);
            return view(previous.getFirst());
          }
          if (retry != null && !owned(retry, owner).get("state").equals("FAILED"))
            throw new ApiException(409, "RENDER_RETRY_INVALID", "仅失败任务可重试");
          var revision = plans.revision(body.plan_id(), owner, body.revision());
          plans.validate(project, CanonicalJson.tree(revision.get("dsl")), true);
          if (body.render_profile().equals("FINAL_1080P")
              && jdbc.queryForObject(
                      "select count(*) from plan_approvals where plan_id=? and revision=? and plan_hash=? and final_profile=? and approved_by=?",
                      Long.class,
                      body.plan_id(),
                      body.revision(),
                      revision.get("plan_hash"),
                      body.render_profile(),
                      owner)
                  == 0) throw new ApiException(409, "PLAN_APPROVAL_REQUIRED", "请先确认此版本的正式导出参数");
          String active = "state in ('QUEUED','RUNNING','CANCEL_REQUESTED')";
          if (jdbc.queryForObject(
                      "select count(*) from render_jobs where project_id=? and " + active,
                      Long.class,
                      project)
                  >= projectLimit
              || jdbc.queryForObject(
                      "select count(*) from render_jobs where owner_id=? and " + active,
                      Long.class,
                      owner)
                  >= userLimit) throw new ApiException(429, "RENDER_QUEUE_FULL", "待处理任务达到上限");
          UUID id = UUID.randomUUID();
          jdbc.update(
              "insert into render_jobs(id,project_id,owner_id,plan_id,revision,plan_hash,render_profile,idempotency_key,request_hash,retry_of) values (?,?,?,?,?,?,?,?,?,?)",
              id,
              project,
              owner,
              body.plan_id(),
              body.revision(),
              revision.get("plan_hash"),
              body.render_profile(),
              key,
              hash,
              retry);
          return get(id, owner);
        });
  }

  private Map<String, Object> owned(UUID id, UUID owner) {
    var rows = jdbc.queryForList("select * from render_jobs where id=? and owner_id=?", id, owner);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.getFirst();
  }

  public Map<String, Object> get(UUID id, UUID owner) {
    return view(owned(id, owner));
  }

  public Map<String, Object> list(UUID project, UUID owner, int offset, int limit) {
    projects.requireOwned(project, owner, false);
    ProjectService.offset(offset);
    ProjectService.limit(limit);
    return Map.of(
        "items",
        jdbc
            .queryForList(
                "select * from render_jobs where project_id=? order by created_at desc,id limit ? offset ?",
                project,
                limit,
                offset)
            .stream()
            .map(RenderService::view)
            .toList(),
        "total",
        jdbc.queryForObject(
            "select count(*) from render_jobs where project_id=?", Long.class, project),
        "offset",
        offset,
        "limit",
        limit);
  }

  public Map<String, Object> cancel(UUID id, UUID owner) {
    owned(id, owner);
    jdbc.update(
        "update render_jobs set state=case when state='QUEUED' then 'CANCELLED' else 'CANCEL_REQUESTED' end,completed_at=case when state='QUEUED' then now() else null end where id=? and owner_id=? and state in ('QUEUED','RUNNING')",
        id,
        owner);
    var job = owned(id, owner);
    if (job.get("state").equals("SUCCEEDED") || job.get("state").equals("FAILED"))
      throw new ApiException(409, "RENDER_ALREADY_FINISHED", "任务已结束");
    return view(job);
  }

  public Map<String, Object> retry(UUID id, UUID owner, String key) {
    var old = owned(id, owner);
    return enqueue(
        owner,
        key,
        new Create(
            (UUID) old.get("plan_id"),
            ((Number) old.get("revision")).intValue(),
            (String) old.get("render_profile")),
        id);
  }

  public Map<String, Object> output(UUID id, UUID owner, boolean download) {
    var job = owned(id, owner);
    if (!job.get("state").equals("SUCCEEDED"))
      throw new ApiException(409, "OUTPUT_NOT_READY", "产物尚未就绪");
    return Map.of(
        "url", storage.signGet((String) job.get("output_key"), download), "expires_in", 300);
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    for (String key :
        new String[] {
          "id",
          "project_id",
          "plan_id",
          "revision",
          "render_profile",
          "progress",
          "error_code",
          "retry_of"
        }) out.put(key, row.get(key));
    out.put("status", row.get("state"));
    return out;
  }
}
