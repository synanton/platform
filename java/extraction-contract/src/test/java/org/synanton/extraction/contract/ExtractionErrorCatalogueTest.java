package org.synanton.extraction.contract;

import org.junit.jupiter.api.Test;
import synanton.extraction.v1.ExtractionErrorCatalogue;
import synanton.extraction.v1.ExtractionErrorCode;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the §24 error catalogue is complete and that retryability is classified deliberately
 * rather than by accident.
 */
class ExtractionErrorCatalogueTest {

    /** The 13 contract codes of §24, excluding UNSPECIFIED and UNRECOGNIZED. */
    private static List<ExtractionErrorCode> contractCodes() {
        return Arrays.stream(ExtractionErrorCode.values())
                .filter(code -> code != ExtractionErrorCode.ERROR_CODE_UNSPECIFIED)
                .filter(code -> code != ExtractionErrorCode.UNRECOGNIZED)
                .toList();
    }

    @Test
    void shouldDefineExactlyThirteenContractErrorCodes() {
        assertThat(contractCodes()).hasSize(13);
    }

    @Test
    void shouldDocumentCallerActionForEveryContractCode() {
        assertThat(ExtractionErrorCatalogue.documentedActions().keySet())
                .containsExactlyInAnyOrderElementsOf(contractCodes());
    }

    @Test
    void shouldClassifyRetryabilityForEveryContractCode() {
        assertThat(ExtractionErrorCatalogue.retryability().keySet())
                .containsExactlyInAnyOrderElementsOf(contractCodes());
    }

    @Test
    void shouldTreatOnlyTransientConditionsAsRetryable() {
        List<ExtractionErrorCode> retryable = contractCodes().stream()
                .filter(ExtractionErrorCatalogue::isRetryable)
                .toList();

        assertThat(retryable).containsExactlyInAnyOrder(
                ExtractionErrorCode.ERROR_REJECTED_CAPACITY,
                ExtractionErrorCode.ERROR_TIMEOUT,
                ExtractionErrorCode.ERROR_INTERNAL_ERROR);
    }

    @Test
    void shouldNotTreatExpiryAsRetryable() {
        // §12: expiry is a lifecycle outcome. Auto-retrying an expired request would silently
        // re-run work whose deadline the caller already declared passed.
        assertThat(ExtractionErrorCatalogue.isRetryable(ExtractionErrorCode.ERROR_EXPIRED)).isFalse();
    }

    @Test
    void shouldNotTreatUnrecognisedCodeAsRetryable() {
        // A client meeting a code from a newer plane must not decide looping is safe.
        assertThat(ExtractionErrorCatalogue.isRetryable(ExtractionErrorCode.UNRECOGNIZED)).isFalse();
        assertThat(ExtractionErrorCatalogue.isRetryable(ExtractionErrorCode.ERROR_CODE_UNSPECIFIED))
                .isFalse();
    }

    @Test
    void shouldProvideActionForUnrecognisedCode() {
        assertThat(ExtractionErrorCatalogue.callerAction(ExtractionErrorCode.UNRECOGNIZED))
                .contains("Do not retry automatically");
    }
}
