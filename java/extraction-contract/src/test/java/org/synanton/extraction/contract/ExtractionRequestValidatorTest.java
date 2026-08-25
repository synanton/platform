package org.synanton.extraction.contract;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import synanton.extraction.v1.ExtractionOptions;
import synanton.extraction.v1.ExtractionRequestItem;
import synanton.extraction.v1.GetOperationsRequest;
import synanton.extraction.v1.ListCompletedOperationsRequest;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SubmitExtractionBatchRequest;
import synanton.extraction.v1.SubmitExtractionRequest;
import synanton.extraction.v1.validation.ExtractionRequestValidator;
import synanton.extraction.v1.validation.FieldViolation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the documented validation rules (SCEP-1 DoD item 2).
 *
 * <p>These matter because every rule here guards work that is expensive to perform and awkward to
 * undo. A request that reaches admission malformed either wastes extraction capacity or produces a
 * result that cannot be tied back to its source.
 */
class ExtractionRequestValidatorTest {

    private static final String VALID_SHA = "a".repeat(64);

    private static ObjectReference.Builder validSource() {
        return ObjectReference.newBuilder()
                .setBucket("tenant-content")
                .setKey("documents/contract.pdf")
                .setVersion("v1")
                .setSha256(VALID_SHA)
                .setSizeBytes(2048);
    }

    private static ExtractionRequestItem.Builder validItem() {
        return ExtractionRequestItem.newBuilder()
                .setContentRefId("content-pdf-001")
                .setSource(validSource())
                .setMediaType("application/pdf")
                .setOptions(ExtractionOptions.newBuilder().setLayout(true));
    }

    private static SubmitExtractionRequest.Builder validRequest() {
        return SubmitExtractionRequest.newBuilder()
                .setTenantId("acme-legal")
                .setIdempotencyKey("job-42-item-1")
                .setItem(validItem())
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .setExpiresAt(Timestamp.newBuilder().setSeconds(1_800_000_000L));
    }

    private static List<String> fields(List<FieldViolation> violations) {
        return violations.stream().map(FieldViolation::field).toList();
    }

    @Test
    void shouldAcceptAValidRequest() {
        assertThat(ExtractionRequestValidator.validate(validRequest().build())).isEmpty();
    }

    @Test
    void shouldRejectMissingTenantId() {
        var request = validRequest().clearTenantId().build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("tenant_id");
    }

    @Test
    void shouldRejectTenantIdOutsideThePattern() {
        var request = validRequest().setTenantId("acme legal/../etc").build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("tenant_id");
    }

    @Test
    void shouldRejectTenantIdLongerThanSixtyFourCharacters() {
        var request = validRequest().setTenantId("t".repeat(65)).build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("tenant_id");
    }

    @Test
    void shouldRejectMissingIdempotencyKey() {
        // Without a key there is no retry safety: a network failure would duplicate the work.
        var request = validRequest().clearIdempotencyKey().build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("idempotency_key");
    }

    @Test
    void shouldRejectOverlongIdempotencyKey() {
        var request = validRequest().setIdempotencyKey("k".repeat(257)).build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("idempotency_key");
    }

    @Test
    void shouldRejectUnspecifiedPriorityClass() {
        var request = validRequest().setPriorityClass(PriorityClass.PRIORITY_CLASS_UNSPECIFIED).build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("priority_class");
    }

    @Test
    void shouldRejectMissingItem() {
        var request = validRequest().clearItem().build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("item");
    }

    @Test
    void shouldRejectMissingContentRefId() {
        var request = validRequest().setItem(validItem().clearContentRefId()).build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("item.content_ref_id");
    }

    @Test
    void shouldRejectMissingMediaType() {
        var request = validRequest().setItem(validItem().clearMediaType()).build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("item.media_type");
    }

    @Test
    void shouldRejectMissingSource() {
        var request = validRequest().setItem(validItem().clearSource()).build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("item.source");
    }

    @Test
    void shouldRejectEmptyBucketAndKey() {
        var request = validRequest()
                .setItem(validItem().setSource(validSource().clearBucket().clearKey()))
                .build();
        assertThat(fields(ExtractionRequestValidator.validate(request)))
                .contains("item.source.bucket", "item.source.key");
    }

    @Test
    void shouldRejectMalformedSha256() {
        var request = validRequest()
                .setItem(validItem().setSource(validSource().setSha256("not-a-digest")))
                .build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("item.source.sha256");
    }

    @Test
    void shouldRejectUppercaseSha256() {
        // Digests are compared as strings; accepting both cases would make equal objects look
        // different and defeat OBJECT_CHANGED detection.
        var request = validRequest()
                .setItem(validItem().setSource(validSource().setSha256("A".repeat(64))))
                .build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("item.source.sha256");
    }

    @Test
    void shouldRejectNonPositiveSizeBytes() {
        var request = validRequest()
                .setItem(validItem().setSource(validSource().setSizeBytes(0)))
                .build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("item.source.size_bytes");
    }

    @Test
    void shouldReportEveryViolationAtOnce() {
        // A caller should not have to fix one field per round trip.
        var request = SubmitExtractionRequest.newBuilder().build();
        assertThat(ExtractionRequestValidator.validate(request)).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldAcceptAValidBatch() {
        var request = SubmitExtractionBatchRequest.newBuilder()
                .setTenantId("acme-legal")
                .setIdempotencyKey("batch-7")
                .addItems(validItem())
                .addItems(validItem().setContentRefId("content-pdf-002"))
                .setPriorityClass(PriorityClass.PRIORITY_HIGH)
                .build();
        assertThat(ExtractionRequestValidator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectEmptyBatch() {
        var request = SubmitExtractionBatchRequest.newBuilder()
                .setTenantId("acme-legal")
                .setIdempotencyKey("batch-8")
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("items");
    }

    @Test
    void shouldReportBatchItemViolationsWithTheirIndex() {
        var request = SubmitExtractionBatchRequest.newBuilder()
                .setTenantId("acme-legal")
                .setIdempotencyKey("batch-9")
                .addItems(validItem())
                .addItems(validItem().clearMediaType())
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("items[1].media_type");
    }

    @Test
    void shouldRejectPageSizeOutsideBounds() {
        var tooSmall = ListCompletedOperationsRequest.newBuilder()
                .setTenantId("acme-legal").setPageSize(0).build();
        var tooLarge = ListCompletedOperationsRequest.newBuilder()
                .setTenantId("acme-legal").setPageSize(1001).build();

        assertThat(fields(ExtractionRequestValidator.validate(tooSmall))).contains("page_size");
        assertThat(fields(ExtractionRequestValidator.validate(tooLarge))).contains("page_size");
    }

    @Test
    void shouldAcceptPageSizeAtBounds() {
        var atMin = ListCompletedOperationsRequest.newBuilder()
                .setTenantId("acme-legal").setPageSize(1).build();
        var atMax = ListCompletedOperationsRequest.newBuilder()
                .setTenantId("acme-legal").setPageSize(1000).build();

        assertThat(ExtractionRequestValidator.validate(atMin)).isEmpty();
        assertThat(ExtractionRequestValidator.validate(atMax)).isEmpty();
    }

    @Test
    void shouldRejectEmptyOperationIdList() {
        var request = GetOperationsRequest.newBuilder().setTenantId("acme-legal").build();
        assertThat(fields(ExtractionRequestValidator.validate(request))).contains("operation_ids");
    }
}
