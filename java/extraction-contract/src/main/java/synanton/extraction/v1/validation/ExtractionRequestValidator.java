package synanton.extraction.v1.validation;

import synanton.extraction.v1.ExtractionRequestItem;
import synanton.extraction.v1.GetOperationsRequest;
import synanton.extraction.v1.ListCompletedOperationsRequest;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SubmitExtractionBatchRequest;
import synanton.extraction.v1.SubmitExtractionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates {@code synanton.extraction.v1} requests against the field rules documented in
 * {@code extraction_service.proto}.
 *
 * <p>This is hand-written rather than generated because the protoc-gen-validate plugin is not
 * wired into either repository's build. PGV annotations in the {@code .proto} would compile
 * without complaint and validate nothing — a silent gap on requests that admit expensive work.
 * The platform's existing {@code PgvRuleCatalogue} takes the same approach for the same reason.
 *
 * <p>This class lives in the contract module so that the server (which must reject bad requests
 * before admission) and the client (which should not send them) enforce one definition. It is
 * pure: no Spring, no I/O, no logging.
 *
 * <p>Rules are checked in a fixed order and all violations are collected, so a caller sees every
 * problem at once rather than fixing them one round-trip at a time.
 */
public final class ExtractionRequestValidator {

    /** Tenant identifiers, per the platform-wide convention. */
    public static final Pattern TENANT_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** Lowercase hex sha256. Uppercase is rejected so digests compare as strings. */
    public static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 256;
    public static final int MAX_CONTENT_REF_ID_LENGTH = 256;
    public static final int MAX_BUCKET_LENGTH = 255;
    public static final int MAX_KEY_LENGTH = 1024;
    public static final int MAX_MEDIA_TYPE_LENGTH = 255;
    public static final int MAX_BATCH_ITEMS = 256;
    public static final int MAX_OPERATION_IDS = 256;
    public static final int MAX_CURSOR_LENGTH = 2000;
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 1000;

    private ExtractionRequestValidator() {
    }

    /**
     * Validates a single-item submission.
     *
     * @return every violation found; empty when the request is acceptable
     */
    public static List<FieldViolation> validate(SubmitExtractionRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        checkTenantId(request.getTenantId(), violations);
        checkIdempotencyKey(request.getIdempotencyKey(), violations);
        checkPriorityClass(request.getPriorityClass(), violations);

        if (!request.hasItem()) {
            violations.add(FieldViolation.required("item"));
        } else {
            checkItem(request.getItem(), "item", violations);
        }
        return violations;
    }

    /**
     * Validates a batch submission. The batch must be non-empty: an empty batch is a caller bug
     * that would otherwise create an operation that completes immediately having done nothing.
     */
    public static List<FieldViolation> validate(SubmitExtractionBatchRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        checkTenantId(request.getTenantId(), violations);
        checkIdempotencyKey(request.getIdempotencyKey(), violations);
        checkPriorityClass(request.getPriorityClass(), violations);

        int itemCount = request.getItemsCount();
        if (itemCount < 1) {
            violations.add(new FieldViolation("items", "repeated.min_items", "items must not be empty"));
        } else if (itemCount > MAX_BATCH_ITEMS) {
            violations.add(new FieldViolation(
                    "items",
                    "repeated.max_items",
                    "items must contain at most " + MAX_BATCH_ITEMS + " entries, got " + itemCount));
        }

        for (int index = 0; index < itemCount; index++) {
            checkItem(request.getItems(index), "items[" + index + "]", violations);
        }
        return violations;
    }

    /** Validates an operation-status poll. */
    public static List<FieldViolation> validate(GetOperationsRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        checkTenantId(request.getTenantId(), violations);

        int idCount = request.getOperationIdsCount();
        if (idCount < 1) {
            violations.add(new FieldViolation(
                    "operation_ids", "repeated.min_items", "operation_ids must not be empty"));
        } else if (idCount > MAX_OPERATION_IDS) {
            violations.add(new FieldViolation(
                    "operation_ids",
                    "repeated.max_items",
                    "operation_ids must contain at most " + MAX_OPERATION_IDS + " entries, got " + idCount));
        }

        for (int index = 0; index < idCount; index++) {
            if (request.getOperationIds(index).isBlank()) {
                violations.add(FieldViolation.required("operation_ids[" + index + "]"));
            }
        }
        return violations;
    }

    /** Validates a cursor page request. */
    public static List<FieldViolation> validate(ListCompletedOperationsRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        checkTenantId(request.getTenantId(), violations);

        if (request.getCursor().length() > MAX_CURSOR_LENGTH) {
            violations.add(new FieldViolation(
                    "cursor", "string.max_len", "cursor must be at most " + MAX_CURSOR_LENGTH + " characters"));
        }

        int pageSize = request.getPageSize();
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            violations.add(new FieldViolation(
                    "page_size",
                    "int32.range",
                    "page_size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE + ", got " + pageSize));
        }
        return violations;
    }

    private static void checkItem(ExtractionRequestItem item, String path, List<FieldViolation> violations) {
        if (item.getContentRefId().isBlank()) {
            violations.add(FieldViolation.required(path + ".content_ref_id"));
        } else if (item.getContentRefId().length() > MAX_CONTENT_REF_ID_LENGTH) {
            violations.add(new FieldViolation(
                    path + ".content_ref_id",
                    "string.max_len",
                    "content_ref_id must be at most " + MAX_CONTENT_REF_ID_LENGTH + " characters"));
        }

        if (item.getMediaType().isBlank()) {
            violations.add(FieldViolation.required(path + ".media_type"));
        } else if (item.getMediaType().length() > MAX_MEDIA_TYPE_LENGTH) {
            violations.add(new FieldViolation(
                    path + ".media_type",
                    "string.max_len",
                    "media_type must be at most " + MAX_MEDIA_TYPE_LENGTH + " characters"));
        }

        if (!item.hasSource()) {
            violations.add(FieldViolation.required(path + ".source"));
        } else {
            checkSource(item.getSource(), path + ".source", violations);
        }
    }

    private static void checkSource(ObjectReference source, String path, List<FieldViolation> violations) {
        if (source.getBucket().isBlank()) {
            violations.add(FieldViolation.required(path + ".bucket"));
        } else if (source.getBucket().length() > MAX_BUCKET_LENGTH) {
            violations.add(new FieldViolation(
                    path + ".bucket", "string.max_len", "bucket must be at most " + MAX_BUCKET_LENGTH + " characters"));
        }

        if (source.getKey().isBlank()) {
            violations.add(FieldViolation.required(path + ".key"));
        } else if (source.getKey().length() > MAX_KEY_LENGTH) {
            violations.add(new FieldViolation(
                    path + ".key", "string.max_len", "key must be at most " + MAX_KEY_LENGTH + " characters"));
        }

        // The digest binds the result to exact source bytes and is what makes
        // ERROR_OBJECT_CHANGED detectable, so it is required rather than optional.
        if (!SHA256.matcher(source.getSha256()).matches()) {
            violations.add(new FieldViolation(
                    path + ".sha256", "string.pattern", "sha256 must be 64 lowercase hex characters"));
        }

        // Checked before download so an oversized object costs nothing (§27).
        if (source.getSizeBytes() <= 0) {
            violations.add(new FieldViolation(
                    path + ".size_bytes", "int64.gt", "size_bytes must be greater than 0"));
        }
    }

    private static void checkTenantId(String tenantId, List<FieldViolation> violations) {
        if (!TENANT_ID.matcher(tenantId).matches()) {
            violations.add(new FieldViolation(
                    "tenant_id", "string.pattern", "tenant_id must match " + TENANT_ID.pattern()));
        }
    }

    private static void checkIdempotencyKey(String key, List<FieldViolation> violations) {
        if (key.isBlank()) {
            violations.add(FieldViolation.required("idempotency_key"));
        } else if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            violations.add(new FieldViolation(
                    "idempotency_key",
                    "string.max_len",
                    "idempotency_key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters"));
        }
    }

    /**
     * Priority must be stated explicitly. Treating UNSPECIFIED as NORMAL would let callers inherit
     * an invisible default and then depend on it.
     */
    private static void checkPriorityClass(PriorityClass priorityClass, List<FieldViolation> violations) {
        if (priorityClass == PriorityClass.PRIORITY_CLASS_UNSPECIFIED
                || priorityClass == PriorityClass.UNRECOGNIZED) {
            violations.add(new FieldViolation(
                    "priority_class", "enum.defined_only", "priority_class must be set explicitly"));
        }
    }
}
