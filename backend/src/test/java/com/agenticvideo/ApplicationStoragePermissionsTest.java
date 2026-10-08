package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

@EnabledIfEnvironmentVariable(named = "SILO_APP_CONTRACT", matches = "true")
class ApplicationStoragePermissionsTest {
  @Test
  void applicationCanUseOnlyItsBucket() {
    try (var s3 =
        S3Client.builder()
            .endpointOverride(URI.create("http://localhost:19000"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        System.getenv("S3_ACCESS_KEY"), System.getenv("S3_SECRET_KEY"))))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
      String key = "m1-permissions-contract/probe";
      try {
        s3.putObject(b -> b.bucket("agenticvideo").key(key), RequestBody.fromString("private"));
        assertEquals(7, s3.headObject(b -> b.bucket("agenticvideo").key(key)).contentLength());
        // Silo filters ListBuckets to authorized buckets; it does not reject this request.
        assertEquals(
            java.util.List.of("agenticvideo"),
            s3.listBuckets().buckets().stream().map(b -> b.name()).toList());
        assertEquals(
            403,
            assertThrows(
                    S3Exception.class,
                    () -> s3.listObjectsV2(b -> b.bucket("foreign-contract-bucket")))
                .statusCode());
        assertEquals(
            403,
            assertThrows(
                    S3Exception.class, () -> s3.createBucket(b -> b.bucket("forbidden-bucket")))
                .statusCode());
      } finally {
        s3.deleteObject(b -> b.bucket("agenticvideo").key(key));
      }
    }
  }
}
