package org.synanton.synvault.adapter;

import org.synanton.synvault.config.SynvaultObjectStoreProperties;
import org.synanton.synvault.port.ObjectStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

public class MinioObjectStoreAdapter implements ObjectStorePort {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStoreAdapter.class);
    private final S3Client s3;
    private final S3Presigner presigner;

    public MinioObjectStoreAdapter(SynvaultObjectStoreProperties props) {
        var creds = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(props.accessKey(), props.secretKey())
        );
        this.s3 = S3Client.builder()
            .endpointOverride(URI.create(props.endpoint()))
            .region(Region.of(props.region()))
            .credentialsProvider(creds)
            .forcePathStyle(props.pathStyleAccess())
            .build();
        this.presigner = S3Presigner.builder()
            .endpointOverride(URI.create(props.endpoint()))
            .region(Region.of(props.region()))
            .credentialsProvider(creds)
            .build();
        ensureBucketExists(props.hotBucket());
    }

    private void ensureBucketExists(String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            log.info("Creating bucket: {}", bucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (Exception e) {
            log.warn("Cannot verify bucket {}: {}", bucket, e.getMessage());
        }
    }

    @Override
    public void putObject(String bucket, String key, InputStream data, long size, String contentType) {
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(size).build(),
            RequestBody.fromInputStream(data, size)
        );
    }

    @Override
    public InputStream getObject(String bucket, String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean headObject(String bucket, String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public String presignedUrl(String bucket, String key, int expiryMinutes) {
        var presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(expiryMinutes))
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }
}
