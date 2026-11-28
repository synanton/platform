package org.synanton.synflux.chunking;

final class UnicodeWhiteSpace {

    static final String UAX44_DATA_VERSION = "15.1.0";

    private static final int[] RANGES = {
        0x0009, 0x000D,
        0x0020, 0x0020,
        0x0085, 0x0085,
        0x00A0, 0x00A0,
        0x1680, 0x1680,
        0x2000, 0x200A,
        0x2028, 0x2029,
        0x202F, 0x202F,
        0x205F, 0x205F,
        0x3000, 0x3000,
    };

    private UnicodeWhiteSpace() {
    }

    static boolean isWhiteSpace(int codePoint) {
        int low = 0;
        int high = RANGES.length / 2 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int start = RANGES[mid * 2];
            int end = RANGES[mid * 2 + 1];
            if (codePoint < start) {
                high = mid - 1;
            } else if (codePoint > end) {
                low = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    static boolean isBridgeableGap(String value) {
        return value.codePoints().allMatch(cp -> cp != '\r' && isWhiteSpace(cp));
    }
}
