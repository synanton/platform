package org.synanton.gateway.gpu;

import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves tenant-specific models and providers for GPU operations.
 * In production, this would call the GetModels gRPC API or use a shared config.
 * For now, uses a local in-memory configuration matching the GPU gateway ModelCatalogService.
 */
@Component
public class ModelResolver {

    private final Map<String, TenantModels> tenantModels = new ConcurrentHashMap<>();

    public ModelResolver() {
        initializeDefaultCatalog();
    }

    private void initializeDefaultCatalog() {
        // Default operation models (matches GPU gateway default)
        Map<Operation, OperationModels> defaults = Map.of(
            Operation.SYNTHESIZE, new OperationModels(
                "llama-3.1-8b-instruct",
                Map.of(
                    "llama-3.1-8b-instruct", new ModelInfo("meta-llama/llama-3.1-8b-instruct:free", "Llama 3.1 8B Instruct", "OPENROUTER", true, 8192, 2048, 0)
                )
            ),
            Operation.EMBED, new OperationModels(
                "text-embedding-3-small",
                Map.of(
                    "text-embedding-3-small", new ModelInfo("text-embedding-3-small", "OpenAI text-embedding-3-small", "OPENROUTER", true, 8192, 0, 1536)
                )
            ),
            Operation.RERANK, new OperationModels(
                "ms-marco-MiniLM-L-6-v2",
                Map.of(
                    "ms-marco-MiniLM-L-6-v2", new ModelInfo("cross-encoder/ms-marco-MiniLM-L-6-v2", "MS MARCO MiniLM L6 v2", "OPENROUTER", true, 512, 0, 0)
                )
            )
        );

        tenantModels.put("default", new TenantModels(defaults, Map.of()));
        tenantModels.put("demo", new TenantModels(defaults, Map.of()));
    }

    /**
     * Resolves the model ID for a tenant and operation.
     * Falls back to default if tenant not found or no override.
     */
    public String resolveModel(String tenantId, Operation operation) {
        TenantModels tenant = tenantModels.getOrDefault(tenantId, tenantModels.get("default"));
        OperationModels opModels = tenant.operations().get(operation);
        if (opModels != null && opModels.defaultModel() != null) {
            return opModels.defaultModel();
        }
        // Ultimate fallback
        return switch (operation) {
            case SYNTHESIZE -> "llama-3.1-8b-instruct";
            case EMBED -> "text-embedding-3-small";
            case RERANK -> "ms-marco-MiniLM-L-6-v2";
            default -> "llama-3.1-8b-instruct";
        };
    }

    /**
     * Resolves the provider for a tenant, model, and operation.
     */
    public Provider resolveProvider(String tenantId, String modelId, Operation operation) {
        TenantModels tenant = tenantModels.getOrDefault(tenantId, tenantModels.get("default"));
        OperationModels opModels = tenant.operations().get(operation);
        if (opModels != null) {
            ModelInfo modelInfo = opModels.models().get(modelId);
            if (modelInfo != null) {
                try {
                    return Provider.valueOf(modelInfo.provider());
                } catch (IllegalArgumentException e) {
                    return Provider.OPENROUTER;
                }
            }
        }
        return Provider.OPENROUTER;
    }

    public record ModelInfo(
        String providerModelId,
        String displayName,
        String provider,
        boolean isDefault,
        int maxInputTokens,
        int maxOutputTokens,
        int embeddingDim
    ) {}

    public record OperationModels(
        String defaultModel,
        Map<String, ModelInfo> models
    ) {}

    public record TenantModels(
        Map<Operation, OperationModels> operations,
        Map<String, Map<Operation, String>> overrides
    ) {}
}