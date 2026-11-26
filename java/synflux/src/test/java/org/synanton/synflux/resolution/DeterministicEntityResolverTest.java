package org.synanton.synflux.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicEntityResolverTest {

    private final CanonicalCatalog catalog = new CanonicalCatalog("it-books-canonical-v1", List.of(
        new CanonicalConcept("c-book", "Think Python: How to Think Like a Computer Scientist",
            "Book", List.of("Think Python")),
        new CanonicalConcept("c-author", "Allen B. Downey", "Author", List.of("Allen Downey")),
        new CanonicalConcept("c-ds", "Data Structure", "DataStructure", List.of()),
        new CanonicalConcept("c-topic", "Computer Networks", "Topic", List.of()),
        new CanonicalConcept("c-model-a", "Model A", "Algorithm", List.of("Model")),
        new CanonicalConcept("c-model-b", "Model B", "Concept", List.of("Model"))));

    private final DeterministicEntityResolver resolver = new DeterministicEntityResolver(catalog);

    @Test
    void resolvesExactNormalizedLabelAndPreservesMentionIdentity() {
        ResolutionResult result = resolver.resolve(new MentionRef(
            "mention-1", "  think python: how to think like a computer scientist ", "Book"));

        assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.mentionId()).isEqualTo("mention-1");
        assertThat(result.canonicalId()).isEqualTo("c-book");
        assertThat(result.canonicalLabel()).isEqualTo("Think Python: How to Think Like a Computer Scientist");
        assertThat(result.matchMethod()).isEqualTo("EXACT_LABEL");
        assertThat(result.normalizedSurface())
            .isEqualTo("think python: how to think like a computer scientist");
        assertThat(result.catalogVersion()).isEqualTo("it-books-canonical-v1");
        assertThat(result.catalogDigest()).isEqualTo(catalog.digest());
    }

    @Test
    void resolvesExplicitAliasButDoesNotInventCanonicalIdentity() {
        ResolutionResult result = resolver.resolve(new MentionRef("mention-2", "Allen Downey", "Author"));

        assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.canonicalId()).isEqualTo("c-author");
        assertThat(result.matchMethod()).isEqualTo("ALIAS");
        assertThat(result.catalogDigest()).hasSize(64);
    }

    @Test
    void typeMismatchIsExplicitAndNotResolved() {
        ResolutionResult result = resolver.resolve(new MentionRef("mention-3", "Allen Downey", "Concept"));

        assertThat(result.status()).isEqualTo(ResolutionStatus.TYPE_MISMATCH);
        assertThat(result.canonicalId()).isEqualTo("c-author");
        assertThat(result.reason()).contains("PROPOSED_TYPE_Concept").contains("EXPECTED_Author");
    }

    @Test
    void metadataAndNearMissesRemainUnresolved() {
        assertThat(resolver.resolve(new MentionRef("m1", "CC-BY-NC-3.0", "Identifier")).status())
            .isEqualTo(ResolutionStatus.UNRESOLVED);
        assertThat(resolver.resolve(new MentionRef("m2", "Data Structures", "DataStructure")).status())
            .isEqualTo(ResolutionStatus.UNRESOLVED);
        assertThat(resolver.resolve(new MentionRef("m3", "Authors", "Author")).status())
            .isEqualTo(ResolutionStatus.UNRESOLVED);
    }

    @Test
    void ambiguousAliasIsNotForcedToAnIdentity() {
        ResolutionResult result = resolver.resolve(new MentionRef("m4", "model", "Algorithm"));

        assertThat(result.status()).isEqualTo(ResolutionStatus.AMBIGUOUS);
        assertThat(result.canonicalId()).isNull();
        assertThat(result.reason()).isEqualTo("MULTIPLE_ALIAS_TARGETS");
    }

    @Test
    void resolutionPrecisionAndRecallAreMeasuredSeparately() {
        List<Expected> fixture = List.of(
            new Expected(new MentionRef("m1", "Think Python", "Book"), "c-book"),
            new Expected(new MentionRef("m2", "Allen Downey", "Author"), "c-author"),
            new Expected(new MentionRef("m3", "Data Structure", "DataStructure"), "c-ds"),
            new Expected(new MentionRef("m4", "Computer Networks", "Topic"), "c-topic"),
            new Expected(new MentionRef("m5", "CC-BY-4.0", "Identifier"), null),
            new Expected(new MentionRef("m6", "Allen B. Downey", "Concept"), null),
            new Expected(new MentionRef("m7", "Model", "Algorithm"), null),
            new Expected(new MentionRef("m8", "Data Structures", "DataStructure"), null));

        int resolved = 0;
        int correct = 0;
        int incorrect = 0;
        int unresolved = 0;
        for (Expected expected : fixture) {
            ResolutionResult result = resolver.resolve(expected.mention());
            if (result.status() == ResolutionStatus.RESOLVED) {
                resolved++;
                if (expected.canonicalId() != null && expected.canonicalId().equals(result.canonicalId())) {
                    correct++;
                } else {
                    incorrect++;
                }
            } else {
                unresolved++;
            }
        }

        assertThat(resolved).isEqualTo(4);
        assertThat(correct).isEqualTo(4);
        assertThat(incorrect).isZero();
        assertThat(unresolved).isEqualTo(4);
        assertThat((double) correct / resolved).isEqualTo(1.0);
        assertThat((double) correct / 4).isEqualTo(1.0);
    }

    @Test
    void aliasCollisionsRemainAmbiguousRegardlessOfCatalogOrder() {
        CanonicalCatalog reversed = new CanonicalCatalog("it-books-canonical-v1",
            catalog.concepts().reversed());
        DeterministicEntityResolver reversedResolver = new DeterministicEntityResolver(reversed);

        assertThat(reversedResolver.resolve(new MentionRef("m", "Model", "Algorithm")).status())
            .isEqualTo(ResolutionStatus.AMBIGUOUS);
    }

    @Test
    void canonicalLabelTakesDeterministicPrecedenceOverAlias() {
        CanonicalCatalog collision = new CanonicalCatalog("collision-v1", List.of(
            new CanonicalConcept("label-owner", "Shared", "Concept", List.of()),
            new CanonicalConcept("alias-owner", "Other", "Concept", List.of("Shared"))));
        DeterministicEntityResolver collisionResolver = new DeterministicEntityResolver(collision);

        ResolutionResult result = collisionResolver.resolve(new MentionRef("m", "shared", "Concept"));

        assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.canonicalId()).isEqualTo("label-owner");
        assertThat(result.matchMethod()).isEqualTo("EXACT_LABEL");
    }

    @Test
    void duplicateNormalizedAliasesOnOneConceptDoNotCreateAmbiguity() {
        CanonicalCatalog duplicateAlias = new CanonicalCatalog("duplicate-v1", List.of(
            new CanonicalConcept("c-book", "Think Python", "Book",
                List.of("Think Python", "think python"))));
        DeterministicEntityResolver duplicateResolver = new DeterministicEntityResolver(duplicateAlias);

        ResolutionResult result = duplicateResolver.resolve(new MentionRef("m", "THINK PYTHON", "Book"));

        assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.canonicalId()).isEqualTo("c-book");
    }

    @Test
    void nfcIsAppliedToCatalogAndMentionsAndPunctuationIsNotStripped() {
        CanonicalCatalog nfc = new CanonicalCatalog("nfc-v1", List.of(
            new CanonicalConcept("c-cafe", "Caf\u00e9", "Concept", List.of())));
        DeterministicEntityResolver nfcResolver = new DeterministicEntityResolver(nfc);

        assertThat(nfcResolver.resolve(new MentionRef("m1", "Cafe\u0301", "Concept")).status())
            .isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(nfcResolver.resolve(new MentionRef("m2", "Caf\u00e9.", "Concept")).status())
            .isEqualTo(ResolutionStatus.UNRESOLVED);
    }

    @Test
    void digestChangesWhenLabelTypeOrAliasChanges() {
        String base = catalog.digest();
        assertThat(new CanonicalCatalog("it-books-canonical-v1", List.of(
            new CanonicalConcept("c-ds", "Data Structures", "DataStructure", List.of())))
            .digest()).isNotEqualTo(base);
        assertThat(new CanonicalCatalog("it-books-canonical-v1", List.of(
            new CanonicalConcept("c-ds", "Data Structure", "Concept", List.of())))
            .digest()).isNotEqualTo(base);
        assertThat(new CanonicalCatalog("it-books-canonical-v1", List.of(
            new CanonicalConcept("c-ds", "Data Structure", "DataStructure", List.of("DS"))))
            .digest()).isNotEqualTo(base);
    }

    @Test
    void catalogDigestIsStableAcrossConceptOrder() {
        CanonicalCatalog reversed = new CanonicalCatalog("it-books-canonical-v1",
            catalog.concepts().reversed());
        assertThat(reversed.digest()).isEqualTo(catalog.digest());
    }

    private record Expected(MentionRef mention, String canonicalId) {}
}
