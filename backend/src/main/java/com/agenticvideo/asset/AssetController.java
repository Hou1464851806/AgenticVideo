package com.agenticvideo.asset;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class AssetController {
  private final UploadService uploads;
  private final AssetService assets;

  public AssetController(UploadService uploads, AssetService assets) {
    this.uploads = uploads;
    this.assets = assets;
  }

  @PostMapping("/projects/{id}/uploads/init")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> initialize(
      @PathVariable UUID id,
      @RequestBody UploadService.Initialize body,
      @RequestHeader("Idempotency-Key") String key,
      Principal principal) {
    return uploads.initialize(id, owner(principal), key, body);
  }

  @GetMapping("/uploads/{id}")
  Map<String, Object> upload(@PathVariable UUID id, Principal principal) {
    return uploads.get(id, owner(principal));
  }

  @PostMapping("/uploads/{id}/parts/presign")
  Map<String, Object> sign(
      @PathVariable UUID id, @RequestBody UploadService.Presign body, Principal principal) {
    return uploads.sign(id, owner(principal), body);
  }

  @PostMapping("/uploads/{id}/complete")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> complete(
      @PathVariable UUID id, @RequestBody UploadService.Complete body, Principal principal) {
    return uploads.complete(id, owner(principal), body);
  }

  @PostMapping("/uploads/{id}/abort")
  Map<String, Object> abort(@PathVariable UUID id, Principal principal) {
    return uploads.abort(id, owner(principal));
  }

  @GetMapping("/projects/{id}/assets")
  Map<String, Object> list(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "30") int limit) {
    return assets.list(id, owner(principal), offset, limit);
  }

  @GetMapping("/assets/{id}")
  Map<String, Object> get(@PathVariable UUID id, Principal principal) {
    return assets.get(id, owner(principal));
  }

  @GetMapping("/assets/{id}/content")
  Map<String, Object> content(
      @PathVariable UUID id,
      Principal principal,
      @RequestParam(defaultValue = "false") boolean download) {
    return assets.content(id, owner(principal), download);
  }

  @DeleteMapping("/assets/{id}")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> delete(@PathVariable UUID id, Principal principal) {
    return assets.delete(id, owner(principal));
  }

  private static UUID owner(Principal principal) {
    return UUID.fromString(principal.getName());
  }
}
