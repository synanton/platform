package org.synanton.llm;

public class LlmClientException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    public LlmClientException(String message) { super(message); }
    public LlmClientException(String message, Throwable cause) { super(message, cause); }
}
