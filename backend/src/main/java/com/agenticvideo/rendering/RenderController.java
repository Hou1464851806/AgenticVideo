package com.agenticvideo.rendering;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RenderController {
  private final RenderService renders;

  public RenderController(RenderService renders) {
    this.renders = renders;
  }

  @PostMapping("/render-jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> create(
      Principal user,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody RenderService.Create body) {
    return renders.create(owner(user), key, body);
  }

  @GetMapping("/render-jobs/{id}")
  Map<String, Object> get(@PathVariable UUID id, Principal user) {
    return renders.get(id, owner(user));
  }

  @GetMapping("/projects/{id}/render-jobs")
  Map<String, Object> list(
      @PathVariable UUID id,
      Principal user,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "30") int limit) {
    return renders.list(id, owner(user), offset, limit);
  }

  @PostMapping("/render-jobs/{id}/cancel")
  Map<String, Object> cancel(@PathVariable UUID id, Principal user) {
    return renders.cancel(id, owner(user));
  }

  @PostMapping("/render-jobs/{id}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> retry(
      @PathVariable UUID id, Principal user, @RequestHeader("Idempotency-Key") String key) {
    return renders.retry(id, owner(user), key);
  }

  @GetMapping("/render-jobs/{id}/output")
  Map<String, Object> output(
      @PathVariable UUID id,
      Principal user,
      @RequestParam(defaultValue = "false") boolean download) {
    return renders.output(id, owner(user), download);
  }

  private static UUID owner(Principal user) {
    return UUID.fromString(user.getName());
  }
}
