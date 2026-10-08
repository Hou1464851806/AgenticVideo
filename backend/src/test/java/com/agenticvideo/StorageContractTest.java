package com.agenticvideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@EnabledIfEnvironmentVariable(named = "SILO_CONTRACT", matches = "true")
class StorageContractTest {
  @Test
  void realMultipartSigningRangeCopyAbortAndPrivacy() throws Exception {
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(
                System.getenv("S3_ACCESS_KEY"), System.getenv("S3_SECRET_KEY")));
    var endpoint =
        URI.create(System.getenv().getOrDefault("S3_ENDPOINT", "http://localhost:19000"));
    var config =
        software.amazon.awssdk.services.s3.S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            .build();
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
      String bucket = "m1-contract-" + UUID.randomUUID();
      var http = HttpClient.newHttpClient();
      s3.createBucket(b -> b.bucket(bucket));
      try {
        String id = s3.createMultipartUpload(b -> b.bucket(bucket).key("staging")).uploadId();
        var part =
            signer
                .presignUploadPart(
                    b ->
                        b.signatureDuration(Duration.ofSeconds(60))
                            .uploadPartRequest(
                                r -> r.bucket(bucket).key("staging").uploadId(id).partNumber(1)))
                .url();
        var put =
            http.send(
                HttpRequest.newBuilder(part.toURI())
                    .PUT(HttpRequest.BodyPublishers.ofString("known-media-bytes"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode());
        String etag = put.headers().firstValue("etag").orElseThrow();
        s3.completeMultipartUpload(
            b ->
                b.bucket(bucket)
                    .key("staging")
                    .uploadId(id)
                    .multipartUpload(
                        CompletedMultipartUpload.builder()
                            .parts(CompletedPart.builder().partNumber(1).eTag(etag).build())
                            .build()));
        assertEquals(17, s3.headObject(b -> b.bucket(bucket).key("staging")).contentLength());
        s3.copyObject(
            b ->
                b.sourceBucket(bucket)
                    .sourceKey("staging")
                    .destinationBucket(bucket)
                    .destinationKey("sealed"));
        var get =
            signer
                .presignGetObject(
                    b ->
                        b.signatureDuration(Duration.ofSeconds(60))
                            .getObjectRequest(r -> r.bucket(bucket).key("sealed")))
                .url();
        var range =
            http.send(
                HttpRequest.newBuilder(get.toURI()).header("Range", "bytes=0-4").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(206, range.statusCode());
        assertEquals("known", range.body());
        assertEquals(
            403,
            http.send(
                    HttpRequest.newBuilder(endpoint.resolve("/" + bucket + "/sealed"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode());
        assertEquals(
            403,
            http.send(
                    HttpRequest.newBuilder(
                            URI.create(
                                get.toString().replace("X-Amz-Signature=", "X-Amz-Signature=000")))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode());
        var expired =
            signer
                .presignGetObject(
                    b ->
                        b.signatureDuration(Duration.ofSeconds(1))
                            .getObjectRequest(r -> r.bucket(bucket).key("sealed")))
                .url();
        Thread.sleep(2100);
        assertEquals(
            403,
            http.send(
                    HttpRequest.newBuilder(expired.toURI()).GET().build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode());
        String abort = s3.createMultipartUpload(b -> b.bucket(bucket).key("abort")).uploadId();
        s3.abortMultipartUpload(b -> b.bucket(bucket).key("abort").uploadId(abort));
        assertTrue(s3.listMultipartUploads(b -> b.bucket(bucket)).uploads().isEmpty());
        s3.deleteObject(b -> b.bucket(bucket).key("staging"));
        s3.deleteObject(b -> b.bucket(bucket).key("sealed"));
        assertThrows(S3Exception.class, () -> s3.headObject(b -> b.bucket(bucket).key("sealed")));
      } finally {
        for (var object : s3.listObjectsV2(b -> b.bucket(bucket)).contents())
          s3.deleteObject(b -> b.bucket(bucket).key(object.key()));
        for (var upload : s3.listMultipartUploads(b -> b.bucket(bucket)).uploads())
          s3.abortMultipartUpload(
              b -> b.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()));
        s3.deleteBucket(b -> b.bucket(bucket));
      }
    }
  }
}
