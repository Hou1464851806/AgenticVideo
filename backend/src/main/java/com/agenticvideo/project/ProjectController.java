package com.agenticvideo.project;

import com.agenticvideo.api.ApiException;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProjectController {
  public record CreateProject(String name) {}

  private final JdbcTemplate jdbc;
  private final ProjectService projects;

  public ProjectController(JdbcTemplate jdbc, ProjectService projects) {
    this.jdbc = jdbc;
    this.projects = projects;
  }

  @GetMapping("/health")
  Map<String, String> health() {
    jdbc.queryForObject("select 1", Integer.class);
    return Map.of("status", "UP");
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(@RequestBody CreateProject input, Principal principal) {
    if (input.name() == null || input.name().isBlank() || input.name().length() > 200)
      throw new ApiException(422, "PROJECT_NAME_INVALID", "项目名称为 1–200 字");
    UUID id = UUID.randomUUID(), owner = UUID.fromString(principal.getName());
    jdbc.update(
        "insert into projects(id,owner_id,name) values (?,?,?)", id, owner, input.name().trim());
    return projects.requireOwned(id, owner, false);
  }

  @GetMapping("/projects/{id}")
  Map<String, Object> get(@PathVariable UUID id, Principal principal) {
    return projects.requireOwned(id, UUID.fromString(principal.getName()), false);
  }

  @GetMapping("/projects")
  Map<String, Object> list(
      Principal principal,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "30") int limit) {
    ProjectService.offset(offset);
    ProjectService.limit(limit);
    UUID owner = UUID.fromString(principal.getName());
    return Map.of(
        "items",
        jdbc.queryForList(
            "select id,name,created_at from projects where owner_id=? order by created_at desc,id limit ? offset ?",
            owner,
            limit,
            offset),
        "total",
        jdbc.queryForObject("select count(*) from projects where owner_id=?", Long.class, owner),
        "offset",
        offset,
        "limit",
        limit);
  }
}
