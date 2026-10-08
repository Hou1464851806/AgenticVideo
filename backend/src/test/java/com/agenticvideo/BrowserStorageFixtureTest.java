package com.agenticvideo;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@EnabledIfEnvironmentVariable(named = "SILO_BROWSER_FIXTURE", matches = ".+")
class BrowserStorageFixtureTest {
  @Test
  void generateTemporaryBrowserFixture() throws Exception {
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(
                System.getenv("S3_ACCESS_KEY"), System.getenv("S3_SECRET_KEY")));
    var endpoint =
        URI.create(System.getenv().getOrDefault("S3_ENDPOINT", "http://localhost:19000"));
    var config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
    try (var s3 =
            S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(config)
                .build();
        var signer =
            S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(config)
                .build()) {
      String bucket = "m1-browser-contract";
      try {
        s3.createBucket(b -> b.bucket(bucket));
      } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
      }
      for (var upload : s3.listMultipartUploads(b -> b.bucket(bucket)).uploads())
        s3.abortMultipartUpload(
            b -> b.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()));
      s3.putObject(
          b -> b.bucket(bucket).key("sealed"), RequestBody.fromString("known-media-bytes"));
      String id = s3.createMultipartUpload(b -> b.bucket(bucket).key("staging")).uploadId();
      String put =
          signer
              .presignUploadPart(
                  b ->
                      b.signatureDuration(Duration.ofMinutes(5))
                          .uploadPartRequest(
                              r -> r.bucket(bucket).key("staging").uploadId(id).partNumber(1)))
              .url()
              .toString();
      String get =
          signer
              .presignGetObject(
                  b ->
                      b.signatureDuration(Duration.ofMinutes(5))
                          .getObjectRequest(r -> r.bucket(bucket).key("sealed")))
              .url()
              .toString();
      var file = Path.of(System.getenv("SILO_BROWSER_FIXTURE"));
      Files.writeString(
          file,
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(Map.of("putUrl", put, "getUrl", get)));
      Files.setPosixFilePermissions(
          file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
  }
}
