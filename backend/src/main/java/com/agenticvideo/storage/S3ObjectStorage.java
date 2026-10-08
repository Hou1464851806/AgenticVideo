package com.agenticvideo.storage;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {
  private final S3Client s3;
  private final S3Presigner signer;
  private final String bucket;
  private final int ttl;

  public S3ObjectStorage(
      @Value("${app.storage.endpoint}") String internal,
      @Value("${app.storage.public-endpoint}") String external,
      @Value("${app.storage.access-key}") String access,
      @Value("${app.storage.secret-key}") String secret,
      @Value("${app.storage.bucket}") String bucket,
      @Value("${app.storage.region}") String region,
      @Value("${app.storage.signature-ttl-seconds:300}") int ttl) {
    this.bucket = bucket;
    this.ttl = ttl;
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
    var config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
    s3 =
        S3Client.builder()
            .endpointOverride(URI.create(internal))
            .credentialsProvider(credentials)
            .region(Region.of(region))
            .serviceConfiguration(config)
            .httpClientBuilder(
                software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofMinutes(10))
                        .apiCallAttemptTimeout(Duration.ofMinutes(5)))
            .build();
    signer =
        S3Presigner.builder()
            .endpointOverride(URI.create(external))
            .credentialsProvider(credentials)
            .region(Region.of(region))
            .serviceConfiguration(config)
            .build();
  }

  @Override
  public String initialize(String key) {
    // The durable key belongs to exactly one session. Recover a create-before-DB-write crash.
    var existing =
        s3.listMultipartUploads(b -> b.bucket(bucket).prefix(key)).uploads().stream()
            .filter(u -> u.key().equals(key))
            .toList();
    if (!existing.isEmpty()) return existing.getFirst().uploadId();
    return s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId();
  }

  @Override
  public String signPart(String key, String uploadId, int number) {
    return signer
        .presignUploadPart(
            b ->
                b.signatureDuration(Duration.ofSeconds(ttl))
                    .uploadPartRequest(
                        r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(number)))
        .url()
        .toString();
  }

  @Override
  public void complete(String key, String id, List<Part> parts) {
    s3.completeMultipartUpload(
        b ->
            b.bucket(bucket)
                .key(key)
                .uploadId(id)
                .multipartUpload(
                    CompletedMultipartUpload.builder()
                        .parts(
                            parts.stream()
                                .map(
                                    p ->
                                        CompletedPart.builder()
                                            .partNumber(p.partNumber())
                                            .eTag(p.etag())
                                            .build())
                                .toList())
                        .build()));
  }

  @Override
  public void abort(String key, String id) {
    if (id == null) return;
    try {
      s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(id));
    } catch (S3Exception e) {
      if (e.statusCode() != 404) throw e;
    }
  }

  @Override
  public Long sizeIfPresent(String key) {
    try {
      return s3.headObject(b -> b.bucket(bucket).key(key)).contentLength();
    } catch (NoSuchKeyException e) {
      return null;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) return null;
      throw e;
    }
  }

  @Override
  public void copy(String source, String destination) {
    s3.copyObject(
        b ->
            b.sourceBucket(bucket)
                .sourceKey(source)
                .destinationBucket(bucket)
                .destinationKey(destination));
  }

  @Override
  public void download(String key, Path target) {
    download(key, target, () -> false);
  }

  @Override
  public void download(String key, Path target, java.util.function.BooleanSupplier stopped) {
    try (var stream = s3.getObject(b -> b.bucket(bucket).key(key));
        var output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = stream.read(buffer)) != -1) {
        if (stopped.getAsBoolean()) {
          stream.abort();
          throw new com.agenticvideo.media.ProcessRunner.MediaFailure("CANCELLED");
        }
        output.write(buffer, 0, count);
      }
    } catch (java.io.IOException error) {
      throw new com.agenticvideo.media.ProcessRunner.MediaFailure("STORAGE_DOWNLOAD_FAILED");
    }
  }

  @Override
  public void upload(String key, Path source) {
    upload(key, source, () -> false);
  }

  @Override
  public void upload(String key, Path source, java.util.function.BooleanSupplier stopped) {
    try {
      long bytes = Files.size(source);
      s3.putObject(
          b -> b.bucket(bucket).key(key).contentType("video/mp4"),
          RequestBody.fromContentProvider(
              () -> {
                try {
                  return new java.io.FilterInputStream(Files.newInputStream(source)) {
                    private void check() {
                      if (stopped.getAsBoolean())
                        throw new com.agenticvideo.media.ProcessRunner.MediaFailure("CANCELLED");
                    }

                    @Override
                    public int read() throws java.io.IOException {
                      check();
                      return super.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws java.io.IOException {
                      check();
                      return super.read(b, off, len);
                    }
                  };
                } catch (java.io.IOException error) {
                  throw new com.agenticvideo.media.ProcessRunner.MediaFailure(
                      "STORAGE_UPLOAD_FAILED");
                }
              },
              bytes,
              "video/mp4"));
    } catch (java.io.IOException error) {
      throw new com.agenticvideo.media.ProcessRunner.MediaFailure("STORAGE_UPLOAD_FAILED");
    }
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(b -> b.bucket(bucket).key(key));
  }

  @Override
  public String signGet(String key, boolean download) {
    return signer
        .presignGetObject(
            b ->
                b.signatureDuration(Duration.ofSeconds(300))
                    .getObjectRequest(
                        r -> {
                          r.bucket(bucket).key(key);
                          if (download)
                            r.responseContentDisposition("attachment; filename=\"export.mp4\"");
                        }))
        .url()
        .toString();
  }

  @Override
  public void close() {
    signer.close();
    s3.close();
  }
}
