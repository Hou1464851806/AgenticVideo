package com.agenticvideo.project;

import com.agenticvideo.api.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
  private final JdbcTemplate jdbc;

  public ProjectService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> requireOwned(UUID id, UUID owner, boolean lock) {
    var rows =
        jdbc.queryForList(
            "select id,name,created_at from projects where id=? and owner_id=?"
                + (lock ? " for update" : ""),
            id,
            owner);
    if (rows.isEmpty()) throw ApiException.notFound();
    return rows.getFirst();
  }

  public static int limit(int limit) {
    if (limit < 1 || limit > 100) throw new ApiException(422, "PAGE_INVALID", "limit 必须为 1–100");
    return limit;
  }

  public static int offset(int offset) {
    if (offset < 0) throw new ApiException(422, "PAGE_INVALID", "offset 必须非负");
    return offset;
  }
}
