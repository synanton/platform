package org.synanton.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JsonResponseValidatorTest {

    @Mock
    private LlmClient delegate;

    private final CompletionRequest baseRequest =
            new CompletionRequest("m", "sys", "user msg", 0.5, 128);

    @Test
    void returnsImmediatelyWhenResponseIsValidJson() {
        CompletionResponse good = new CompletionResponse("{\"key\":\"value\"}", 10, 5);
        when(delegate.complete(any())).thenReturn(good);

        JsonResponseValidator validator = new JsonResponseValidator(delegate);
        CompletionResponse result = validator.completeWithJsonValidation(baseRequest);

        assertThat(result).isSameAs(good);
        verify(delegate, times(1)).complete(any());
    }

    @Test
    void retriesWhenFirstResponseIsNotValidJson() {
        CompletionResponse bad  = new CompletionResponse("plain text, no braces", 5, 5);
        CompletionResponse good = new CompletionResponse("{\"ok\":true}", 5, 5);
        when(delegate.complete(any())).thenReturn(bad, good);

        JsonResponseValidator validator = new JsonResponseValidator(delegate);
        CompletionResponse result = validator.completeWithJsonValidation(baseRequest);

        assertThat(result).isSameAs(good);
        verify(delegate, times(2)).complete(any());
    }

    @Test
    void retryRequestContainsJsonReminder() {
        CompletionResponse bad  = new CompletionResponse("oops", 5, 5);
        CompletionResponse good = new CompletionResponse("{\"ok\":true}", 5, 5);
        when(delegate.complete(any())).thenReturn(bad, good);

        JsonResponseValidator validator = new JsonResponseValidator(delegate);
        validator.completeWithJsonValidation(baseRequest);

        // Only the retry (second) invocation carries the JSON reminder
        verify(delegate, times(1)).complete(argThat(req ->
                req.userMessage().contains("not valid JSON")));
    }

    @Test
    void throwsAfterExhaustingAllRetries() {
        CompletionResponse bad = new CompletionResponse("not json at all", 5, 5);
        when(delegate.complete(any())).thenReturn(bad);

        JsonResponseValidator validator = new JsonResponseValidator(delegate);
        assertThatThrownBy(() -> validator.completeWithJsonValidation(baseRequest))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("valid JSON");

        // MAX_JSON_RETRIES=2 means 3 total attempts
        verify(delegate, times(3)).complete(any());
    }

    @Test
    void acceptsJsonEmbeddedInPreambleText() {
        // LLMs often prefix the JSON object with prose
        CompletionResponse embeddedJson = new CompletionResponse(
                "Sure, here you go: {\"result\":42} - let me know if you need more.", 5, 5);
        when(delegate.complete(any())).thenReturn(embeddedJson);

        JsonResponseValidator validator = new JsonResponseValidator(delegate);
        CompletionResponse result = validator.completeWithJsonValidation(baseRequest);

        assertThat(result).isSameAs(embeddedJson);
        verify(delegate, times(1)).complete(any());
    }

    @Test
    void treatsNullTextAsInvalidJson() {
        CompletionResponse nullText = new CompletionResponse(null, 0, 0);
        CompletionResponse good    = new CompletionResponse("{\"x\":1}", 5, 5);
        when(delegate.complete(any())).thenReturn(nullText, good);

        JsonResponseValidator validator = new JsonResponseValidator(delegate);
        CompletionResponse result = validator.completeWithJsonValidation(baseRequest);

        assertThat(result).isSameAs(good);
    }
}
