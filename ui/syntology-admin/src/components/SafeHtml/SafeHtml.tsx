import DOMPurify from "dompurify";
import { useMemo } from "react";

const CONFIG = {
  FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "link", "meta"],
  FORBID_ATTR: ["onerror", "onload", "onclick", "onmouseover", "onfocus", "onblur"],
  ALLOW_DATA_ATTR: false,
};

export function SafeHtml({ html }: { html: string }) {
  const clean = useMemo(() => DOMPurify.sanitize(html, CONFIG), [html]);
  return <div dangerouslySetInnerHTML={{ __html: clean }} />;
}
