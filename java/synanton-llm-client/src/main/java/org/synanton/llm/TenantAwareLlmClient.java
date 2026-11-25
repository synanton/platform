package org.synanton.llm;

/**
 * An {@link LlmClient} whose embedding calls are scoped to a tenant, such as the GPU-plane
 * gRPC client. The GPU Gateway authorizes every request's {@code tenant_id} for the calling
 * mTLS principal (gpu-runtime Deployment Plan §13).
 *
 * <p>Callers that know the tenant should use {@link #embed(LlmClient, EmbedRequest, String)}.
 * It passes the tenant to tenant-aware clients and falls back to plain
 * {@link LlmClient#embed(EmbedRequest)} for everything else, such as the HTTP clients.
 */
public interface TenantAwareLlmClient extends LlmClient {

    EmbedResponse embed(EmbedRequest request, String tenantId);

    /**
     * Completion calls that must carry the tenant assertion (GPU Gateway). Clients without a
     * tenant-aware path fall back to the plain interface.
     */
    default CompletionResponse complete(CompletionRequest request, String tenantId) {
        return complete(request);
    }

    static CompletionResponse complete(LlmClient client, CompletionRequest request, String tenantId) {
        return client instanceof TenantAwareLlmClient t
                ? t.complete(request, tenantId)
                : client.complete(request);
    }

    static EmbedResponse embed(LlmClient client, EmbedRequest request, String tenantId) {
        return client instanceof TenantAwareLlmClient t ? t.embed(request, tenantId) : client.embed(request);
    }
}
