package org.synanton.common.security.sanitizer;

import java.util.List;

/**
 * Shared XSS payload corpus for sanitiser tests. Downstream services reuse this from test fixtures.
 */
public final class SanitizerTestKit {

    private SanitizerTestKit() {}

    public static List<String> owaspEvasionPayloads() {
        return List.of(
                "<script>alert(1)</script>",
                "<SCRIPT>alert(1)</SCRIPT>",
                "<img src=x onerror=alert(1)>",
                "<svg/onload=alert(1)>",
                "javascript:alert(1)",
                "<a href=\"javascript:alert(1)\">x</a>",
                "<iframe src=\"javascript:alert(1)\"></iframe>",
                "<body onload=alert(1)>",
                "<div style=\"background:url(javascript:alert(1))\">",
                "<math><mi//xlink:href=\"data:x,<script>alert(1)</script>\">",
                "<scr<script>ipt>alert(1)</script>",
                "\"'><script>alert(1)</script>",
                "<img src=\"x\" onerror=\"alert(String.fromCharCode(88,83,83))\">",
                "<svg><script>alert&#40;1&#41;</script>",
                "<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">x</a>",
                "<link rel=stylesheet href=\"javascript:alert(1)\">",
                "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
                "<object data=\"javascript:alert(1)\">",
                "<embed src=\"javascript:alert(1)\">",
                "<form action=\"javascript:alert(1)\"><input type=submit>",
                "<input onfocus=alert(1) autofocus>",
                "<video><source onerror=\"alert(1)\">",
                "<details open ontoggle=alert(1)>",
                "<marquee onstart=alert(1)>",
                "\\u003cscript\\u003ealert(1)\\u003c/script\\u003e"
        );
    }

    public static List<String> fuzzVariants(int count) {
        List<String> base = owaspEvasionPayloads();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String seed = base.get(index % base.size());
            out.add(seed + " " + index);
            if (out.size() >= count) {
                break;
            }
        }
        while (out.size() < count) {
            out.add("<script>alert(" + out.size() + ")</script>");
        }
        return List.copyOf(out);
    }
}
