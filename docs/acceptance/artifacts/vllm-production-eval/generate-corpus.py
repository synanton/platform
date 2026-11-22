#!/usr/bin/env python3
"""Generate the non-repository documents in the #723 evaluation corpus.

The manifest (docs/acceptance/vllm-production-eval-corpus.tsv) pins the evaluated
files by SHA-256. This script regenerates the corpus content; PDF metadata may
make regenerated PDF bytes differ. Verify hashes before substituting a staged
corpus, or record a new manifest revision.
"""
from __future__ import annotations

import argparse
import html
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("out", nargs="?", default=str(Path(__file__).parent / "corpus"))
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    (out / "glossary-negative.txt").write_text(
        "Glossary\n\n"
        "Halcyon-7: a maintenance system.\n"
        "Auxiliary coolant loop: a component.\n"
        "Relief valve: a component.\n"
        "Ambient dew point: a measurement.\n"
        "Ninety days: a time interval.\n"
        "Two hundred seventy days: a time interval.\n",
        encoding="utf-8",
    )
    (out / "reference-index-negative.txt").write_text(
        "Reference Index\n\n"
        "Acme Corp. Globex Manufacturing. Initech Ltd. Northwind Logistics. "
        "Halcyon-7. Auxiliary coolant loop. Relief valve. Pressure-test log. "
        "Ninety days. Ambient dew point. This index contains defined terms only.\n",
        encoding="utf-8",
    )

    for src, name, title in [
        ("demo-data/documents/supply-chain-overview.txt", "supply-chain-overview.epub", "Supply Chain Overview"),
        ("demo-data/documents/vendor-agreements.txt", "vendor-agreements.epub", "Vendor Agreements"),
        ("demo-data/documents/acme-corp-profile.md", "acme-corp-profile.epub", "Acme Corp Profile"),
    ]:
        text = (REPO / src).read_text(encoding="utf-8", errors="replace")
        with zipfile.ZipFile(out / name, "w") as z:
            z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
            z.writestr(
                "META-INF/container.xml",
                '<?xml version="1.0"?>'
                '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                '<rootfiles><rootfile full-path="OEBPS/content.opf" '
                'media-type="application/oebps-package+xml"/></rootfiles></container>',
                compress_type=zipfile.ZIP_DEFLATED,
            )
            z.writestr(
                "OEBPS/content.opf",
                '<?xml version="1.0" encoding="UTF-8"?>'
                '<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">'
                f'<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>{html.escape(title)}</dc:title>'
                f'<dc:identifier id="bookid">{html.escape(name)}</dc:identifier><dc:language>en</dc:language>'
                '</metadata><manifest><item id="content" href="content.xhtml" '
                'media-type="application/xhtml+xml"/></manifest><spine><itemref idref="content"/></spine></package>',
                compress_type=zipfile.ZIP_DEFLATED,
            )
            z.writestr(
                "OEBPS/content.xhtml",
                '<?xml version="1.0" encoding="UTF-8"?>'
                '<html xmlns="http://www.w3.org/1999/xhtml"><head>'
                f'<title>{html.escape(title)}</title></head><body><pre>{html.escape(text)}</pre></body></html>',
                compress_type=zipfile.ZIP_DEFLATED,
            )

    for pages, stem, title in [
        (100, "large-supply-chain-100p", "Large Supply Chain Evaluation"),
        (120, "large-maintenance-120p", "Large Maintenance Evaluation"),
    ]:
        sections = []
        for i in range(pages):
            sections.append(
                '<div style="page-break-after: always;"><h1>'
                f'{html.escape(title)} — section {i + 1}</h1>'
                f'<p>Acme Corp supplies Widget-{i + 1} to Globex Manufacturing. '
                'Initech Ltd records the ambient dew point for every test. '
                'Halcyon-7 requires the auxiliary coolant loop. '
                'Relief valve inspection interval is ninety days.</p></div>'
            )
        (out / f"{stem}.html").write_text(
            "<!DOCTYPE html><html><body>" + "".join(sections) + "</body></html>",
            encoding="utf-8",
        )
        subprocess.run(
            ["soffice", "--headless", "--convert-to", "pdf", "--outdir", str(out), str(out / f"{stem}.html")],
            check=True,
        )

    for stem, final in [("large-supply-chain-100p", "large-final-100p"), ("large-maintenance-120p", "large-final-120p")]:
        base = out / f"{stem}.pdf"
        parts = [str(base)] * 6
        subprocess.run(["pdfunite", *parts, str(out / f"{final}.pdf")], check=True)

    print(f"generated corpus at {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
