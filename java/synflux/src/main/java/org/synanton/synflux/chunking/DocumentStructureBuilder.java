package org.synanton.synflux.chunking;

import synanton.extraction.v1.DocumentElement;
import synanton.extraction.v1.DocumentElementType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts a flat, reading-order {@code DocumentElement} list into a hierarchical section tree.
 *
 * <p>Algorithm: maintain a stack of open sections. When a HEADING element is encountered,
 * pop all sections at an equal or deeper level, then open a new section as a child of
 * whatever section is now on top of the stack. Non-heading elements are added to the
 * innermost open section.
 */
public class DocumentStructureBuilder {

    public List<SectionNode> build(List<DocumentElement> elements) {
        if (elements == null || elements.isEmpty()) return List.of();

        AtomicInteger idSeq = new AtomicInteger();

        // Synthetic root so all top-level sections have a parent to attach to.
        MutableSection root = new MutableSection(null, 0, List.of(), "root");
        Deque<MutableSection> stack = new ArrayDeque<>();
        stack.push(root);

        for (DocumentElement el : elements) {
            if (el.getType() == DocumentElementType.ELEMENT_HEADING) {
                int level = el.getLevel() > 0 ? el.getLevel() : 1;

                // Close all sections at same or deeper level.
                while (stack.size() > 1 && stack.peek().headingLevel >= level) {
                    stack.pop();
                }

                List<String> parentPath = stack.peek().sectionPath;
                List<String> sectionPath = new ArrayList<>(parentPath);
                sectionPath.add(el.getText());

                MutableSection section = new MutableSection(
                    el.getText(), level, List.copyOf(sectionPath),
                    "s" + idSeq.getAndIncrement()
                );
                section.pageStart = pageOf(el);
                section.pageEnd   = pageOf(el);

                stack.peek().children.add(section);
                stack.push(section);
            } else {
                MutableSection current = stack.peek();
                current.elements.add(el);
                int page = pageOf(el);
                if (page > 0) {
                    if (current.pageStart < 0) current.pageStart = page;
                    current.pageEnd = Math.max(current.pageEnd, page);
                }
            }
        }

        return root.children.stream().map(MutableSection::toNode).toList();
    }

    private static int pageOf(DocumentElement el) {
        return el.hasLocation() ? el.getLocation().getPage() : -1;
    }

    // ─── Mutable builder state ────────────────────────────────────────────────

    private static final class MutableSection {
        final String id;
        final String heading;
        final int headingLevel;
        final List<String> sectionPath;
        final List<DocumentElement> elements = new ArrayList<>();
        final List<MutableSection> children  = new ArrayList<>();
        int pageStart = -1;
        int pageEnd   = -1;

        MutableSection(String heading, int headingLevel, List<String> sectionPath, String id) {
            this.id           = id;
            this.heading      = heading;
            this.headingLevel = headingLevel;
            this.sectionPath  = sectionPath;
        }

        SectionNode toNode() {
            return new SectionNode(
                id, heading, headingLevel, sectionPath,
                List.copyOf(elements),
                children.stream().map(MutableSection::toNode).toList(),
                pageStart, pageEnd
            );
        }
    }
}
