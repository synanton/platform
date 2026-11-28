package org.synanton.synflux.chunking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnicodeWhiteSpaceTest {

    @Test
    void includesUax44WhiteSpacePropertyCodePoints() {
        assertThat(UnicodeWhiteSpace.UAX44_DATA_VERSION).isEqualTo("15.1.0");
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x0009)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x000A)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x000D)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x0020)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x0085)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x00A0)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x1680)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x2000)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x200A)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x2028)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x2029)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x202F)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x205F)).isTrue();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x3000)).isTrue();
    }

    @Test
    void excludesCodePointsOutsideWhiteSpaceProperty() {
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x0000)).isFalse();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x0041)).isFalse();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x180E)).isFalse();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x200B)).isFalse();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x202E)).isFalse();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0xFEFF)).isFalse();
        assertThat(UnicodeWhiteSpace.isWhiteSpace(0x1F600)).isFalse();
    }

    @Test
    void bridgesPermittedCanonicalWhitespaceIncludingLf() {
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\n")).isTrue();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\n\u00A0\u2000\u202F\n")).isTrue();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\u3000")).isTrue();
    }

    @Test
    void doesNotBridgeNonLfLineEndingsInCanonicalV1() {
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\u000B")).isFalse();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\u000C")).isFalse();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\r")).isFalse();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\r\n")).isFalse();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\u0085")).isFalse();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\u2028")).isFalse();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\u2029")).isFalse();
        assertThat(UnicodeWhiteSpace.isBridgeableGap("\n\u00A0\u0085\n")).isFalse();
    }
}
