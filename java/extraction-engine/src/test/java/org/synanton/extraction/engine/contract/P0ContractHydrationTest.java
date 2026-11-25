package org.synanton.extraction.engine.contract;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class P0ContractHydrationTest {

    @Test
    void loadsP0ContractAndHydratesPromptWithPredicatesDomainsAndIdentity() throws Exception {
        CompiledExtractionContract contract = loadContract();
        assertThat(contract.entityIds()).hasSize(11).contains("Book", "Author", "Topic");
        assertThat(contract.predicateIds()).hasSize(8).contains("AUTHORED_BY", "COVERS_TOPIC");
        assertThat(contract.contractDigest()).hasSize(64);

        AtomicReference<CompletionRequest> captured = new AtomicReference<>();
        LlmClient client = new LlmClient() {
            @Override public CompletionResponse complete(CompletionRequest request) {
                captured.set(request);
                return new CompletionResponse("", 0, 0, "stop", "req", "exec");
            }
            @Override public EmbedResponse embed(EmbedRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        LlmExtractionAdapter adapter = new LlmExtractionAdapter(
            client, "model", 512, null, "extraction-v1", contract);
        adapter.extract(new org.synanton.extraction.engine.TextSegment("chunk", "Book", 0),
            new ExtractionContext("demo", "doc", Set.of("Book"), "version"));

        String prompt = captured.get().systemPrompt();
        assertThat(prompt)
            .contains("AUTHORED_BY")
            .contains("Book --AUTHORED_BY--> Author")
            .contains("Book --COVERS_TOPIC--> Topic")
            .contains("releaseId=47fe38f8-9f72-481b-826a-6c5b12a6027a")
            .contains("contractDigest=" + contract.contractDigest());
    }

    @Test
    void rejectsPredicatesOutsideHydratedP0Contract() throws Exception {
        CompiledExtractionContract contract = loadContract();
        LlmClient client = new LlmClient() {
            @Override public CompletionResponse complete(CompletionRequest request) {
                String content = "{\"type\":\"entity\",\"surfaceForm\":\"Book\",\"proposedType\":\"Book\",\"charStart\":0,\"charEnd\":4}\n"
                    + "{\"type\":\"entity\",\"surfaceForm\":\"Author\",\"proposedType\":\"Author\",\"charStart\":5,\"charEnd\":11}\n"
                    + "{\"type\":\"relation\",\"subjectForm\":\"Book\",\"predicate\":\"hassupplier\",\"objectForm\":\"Author\",\"evidenceQuote\":\"Book Author\"}\n";
                return new CompletionResponse(content, 10, 10, "stop", "req", "exec");
            }
            @Override public EmbedResponse embed(EmbedRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        LlmExtractionAdapter adapter = new LlmExtractionAdapter(
            client, "model", 512, null, "extraction-v1", contract);
        var result = adapter.extract(new org.synanton.extraction.engine.TextSegment(
            "chunk", "Book Author", 0),
            new ExtractionContext("demo", "doc", Set.of("Book", "Author"), "version"));

        assertThat(result.relations()).isEmpty();
        assertThat(result.quarantined()).anySatisfy(line -> {
            assertThat(line.reasonCode()).isEqualTo("UNKNOWN_PREDICATE");
            assertThat(line.detail()).isEqualTo("hassupplier");
        });
    }

    @Test
    void rejectsRelationWhoseEndpointTypesViolateHydratedDomainRange() throws Exception {
        CompiledExtractionContract contract = loadContract();
        LlmClient client = new LlmClient() {
            @Override public CompletionResponse complete(CompletionRequest request) {
                String content = "{\"type\":\"entity\",\"surfaceForm\":\"Book\",\"proposedType\":\"Book\",\"charStart\":0,\"charEnd\":4}\n"
                    + "{\"type\":\"entity\",\"surfaceForm\":\"Author\",\"proposedType\":\"Author\",\"charStart\":5,\"charEnd\":11}\n"
                    + "{\"type\":\"relation\",\"subjectForm\":\"Book\",\"predicate\":\"HAS_CHAPTER\",\"objectForm\":\"Author\",\"evidenceQuote\":\"Book Author\"}\n";
                return new CompletionResponse(content, 10, 10, "stop", "req", "exec");
            }
            @Override public EmbedResponse embed(EmbedRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        LlmExtractionAdapter adapter = new LlmExtractionAdapter(
            client, "model", 512, null, "extraction-v1", contract);
        var result = adapter.extract(new org.synanton.extraction.engine.TextSegment(
            "chunk", "Book Author", 0),
            new ExtractionContext("demo", "doc", Set.of("Book", "Author"), "version"));

        assertThat(result.relations()).isEmpty();
        assertThat(result.quarantined()).anySatisfy(line ->
            assertThat(line.reasonCode()).isEqualTo("DOMAIN_RANGE"));
    }

    private static CompiledExtractionContract loadContract() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("repo root not found");
        Path p0 = root.resolve("docs/implementation/it-books/p0");
        return new P0ContractLoader().load(p0.resolve("it-books.spec.json"),
            p0.resolve("it-books.template.json"), p0.resolve("it-books.report.json"));
    }
}
