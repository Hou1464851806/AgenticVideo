package com.agenticvideo.timeline;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PlanController {
  private final PlanService plans;

  public PlanController(PlanService plans) {
    this.plans = plans;
  }

  @PostMapping("/projects/{id}/edit-plans")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(
      @PathVariable UUID id,
      Principal user,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody PlanService.Create body) {
    return plans.create(id, owner(user), key, body);
  }

  @GetMapping("/projects/{id}/edit-plans")
  Map<String, Object> list(
      @PathVariable UUID id,
      Principal user,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "30") int limit) {
    return plans.list(id, owner(user), offset, limit);
  }

  @GetMapping("/edit-plans/{id}")
  Map<String, Object> get(@PathVariable UUID id, Principal user) {
    return plans.current(id, owner(user));
  }

  @PutMapping("/edit-plans/{id}")
  Map<String, Object> update(
      @PathVariable UUID id,
      Principal user,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader(value = "If-Match", required = false) String expected,
      @RequestBody PlanService.Update body) {
    return plans.update(id, owner(user), key, expected, body);
  }

  @GetMapping("/edit-plans/{id}/revisions")
  Map<String, Object> history(
      @PathVariable UUID id,
      Principal user,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "30") int limit) {
    return plans.history(id, owner(user), offset, limit);
  }

  @GetMapping("/edit-plans/{id}/revisions/{rev}")
  Map<String, Object> revision(@PathVariable UUID id, @PathVariable int rev, Principal user) {
    return plans.revision(id, owner(user), rev);
  }

  @PostMapping("/edit-plans/{id}/validate")
  Map<String, Object> validate(@PathVariable UUID id, Principal user) {
    return plans.check(id, owner(user));
  }

  @PostMapping("/edit-plans/{id}/approve")
  Map<String, Object> approve(
      @PathVariable UUID id, Principal user, @RequestBody PlanService.Approve body) {
    return plans.approve(id, owner(user), body);
  }

  private static UUID owner(Principal user) {
    return UUID.fromString(user.getName());
  }
}
