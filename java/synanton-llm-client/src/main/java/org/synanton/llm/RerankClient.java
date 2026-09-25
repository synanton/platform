package org.synanton.llm;

public interface RerankClient {
    RerankResponse rerank(RerankRequest request);
}