package org.synanton.synflux.chunking;

import org.junit.jupiter.api.Test;
import synanton.extraction.v1.DocumentElement;
import synanton.extraction.v1.DocumentElementType;
import synanton.extraction.v1.ElementLocation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStructureBuilderTest {

    private final DocumentStructureBuilder builder = new DocumentStructureBuilder();

    private static DocumentElement heading(String text, int level) {
        return DocumentElement.newBuilder()
            .setId("h-" + level + "-" + text.hashCode())
            .setType(DocumentElementType.ELEMENT_HEADING)
            .setText(text)
            .setLevel(level)
            .build();
    }

    private static DocumentElement para(String id, String text) {
        return DocumentElement.newBuilder()
            .setId(id)
            .setType(DocumentElementType.ELEMENT_PARAGRAPH)
            .setText(text)
            .build();
    }

    private static DocumentElement paraOnPage(String text, int page) {
        return DocumentElement.newBuilder()
            .setId("p-" + text.hashCode())
            .setType(DocumentElementType.ELEMENT_PARAGRAPH)
            .setText(text)
            .setLocation(ElementLocation.newBuilder().setPage(page).build())
            .build();
    }

    // ─── Basic structure ──────────────────────────────────────────────────────

    @Test
    void emptyInputProducesNoSections() {
        assertThat(builder.build(List.of())).isEmpty();
    }

    @Test
    void singleH1ProducesOneTopLevelSection() {
        var sections = builder.build(List.of(
            heading("Introduction", 1),
            para("p1", "First paragraph.")
        ));
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Introduction");
        assertThat(sections.get(0).elements()).hasSize(1);
    }

    @Test
    void h2UnderH1IsNested() {
        var sections = builder.build(List.of(
            heading("Chapter 3", 1),
            para("p1", "Intro."),
            heading("3.1 Gateway", 2),
            para("p2", "Gateway text.")
        ));

        assertThat(sections).hasSize(1);
        SectionNode chapter = sections.get(0);
        assertThat(chapter.heading()).isEqualTo("Chapter 3");
        assertThat(chapter.elements()).hasSize(1); // only "Intro."

        assertThat(chapter.children()).hasSize(1);
        SectionNode sub = chapter.children().get(0);
        assertThat(sub.heading()).isEqualTo("3.1 Gateway");
        assertThat(sub.elements()).hasSize(1); // "Gateway text."
    }

    @Test
    void siblingH2sAreChildrenOfSameH1() {
        var sections = builder.build(List.of(
            heading("Chapter 3", 1),
            heading("3.1 Gateway", 2),
            para("p1", "Gateway text."),
            heading("3.2 Scheduling", 2),
            para("p2", "Scheduler text.")
        ));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).children()).hasSize(2);
        assertThat(sections.get(0).children().get(0).heading()).isEqualTo("3.1 Gateway");
        assertThat(sections.get(0).children().get(1).heading()).isEqualTo("3.2 Scheduling");
    }

    @Test
    void multipleTopLevelH1s() {
        var sections = builder.build(List.of(
            heading("Chapter 1", 1),
            para("p1", "C1 body."),
            heading("Chapter 2", 1),
            para("p2", "C2 body.")
        ));

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).heading()).isEqualTo("Chapter 1");
        assertThat(sections.get(1).heading()).isEqualTo("Chapter 2");
    }

    // ─── sectionPath ─────────────────────────────────────────────────────────

    @Test
    void sectionPathIncludesAllAncestorHeadings() {
        var sections = builder.build(List.of(
            heading("Chapter 3", 1),
            heading("3.1 Gateway", 2),
            heading("3.1.1 Auth", 3),
            para("p1", "Auth details.")
        ));

        SectionNode auth = sections.get(0).children().get(0).children().get(0);
        assertThat(auth.sectionPath())
            .containsExactly("Chapter 3", "3.1 Gateway", "3.1.1 Auth");
    }

    @Test
    void topLevelSectionPathContainsOnlyOwnHeading() {
        var sections = builder.build(List.of(
            heading("Introduction", 1),
            para("p1", "Some text.")
        ));
        assertThat(sections.get(0).sectionPath()).containsExactly("Introduction");
    }

    // ─── Page tracking ────────────────────────────────────────────────────────

    @Test
    void pageStartAndEndDerivedFromElements() {
        var sections = builder.build(List.of(
            heading("Section", 1),
            paraOnPage("First paragraph.", 3),
            paraOnPage("Second paragraph.", 5)
        ));

        SectionNode section = sections.get(0);
        assertThat(section.pageStart()).isEqualTo(3);
        assertThat(section.pageEnd()).isEqualTo(5);
    }

    // ─── Elements before first heading ────────────────────────────────────────

    @Test
    void elementsBeforeFirstHeadingAreIgnoredAsOrphanedRoot() {
        // Elements before any heading attach to the synthetic root and do not appear
        // as a named section — this is intentional (they are preamble content).
        var sections = builder.build(List.of(
            para("p0", "Preamble before any heading."),
            heading("Chapter 1", 1),
            para("p1", "Chapter body.")
        ));

        // Only the named chapter appears as a top-level section.
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Chapter 1");
    }
}
