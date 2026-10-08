package com.agenticvideo.storage;

import java.nio.file.Path;
import java.util.List;

public interface ObjectStorage {
  record Part(int partNumber, String etag) {}

  String initialize(String key);

  String signPart(String key, String uploadId, int partNumber);

  void complete(String key, String uploadId, List<Part> parts);

  void abort(String key, String uploadId);

  Long sizeIfPresent(String key);

  void copy(String source, String destination);

  void download(String key, Path destination);

  void download(String key, Path destination, java.util.function.BooleanSupplier stopped);

  void upload(String key, Path source);

  void upload(String key, Path source, java.util.function.BooleanSupplier stopped);

  void delete(String key);

  String signGet(String key, boolean download);
}
