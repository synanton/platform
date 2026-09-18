## Datasets

### 1. PIRE (PDF Information Retrieval Evaluation) Dataset

https://huggingface.co/datasets/Wikit/PIRE

**The most direct match for your needs.**
This dataset is specifically built to evaluate PDF parsing and chunking strategies for retrieval.

| Attribute   | Details                                                      |
| ----------- | ------------------------------------------------------------ |
| **Scale**   | 100 PDF files                                                |
| **Content** | Comes with a dedicated set of queries and relevance judgments |
| **Purpose** | Specifically designed to evaluate parsing, chunking, and retrieval quality on PDFs |
| **Access**  | Hugging Face: `Wikit/PIRE`                                   |
| **Tooling** | Has a complete benchmark codebase: `bench-chunknorris-acl2025` |

**Why it fits**: It directly supports comparing different chunking strategies (fixed-size vs. semantic) on PDF documents for retrieval tasks.

------

### 2. RAG-Multi-Corpus

https://github.com/udayallu/RAG-Multi-Corpus

**Enterprise-grade multi-format dataset, with PDF as the primary format.**
A synthetic enterprise dataset built specifically for benchmarking RAG systems.

| Attribute         | Details                                                      |
| ----------------- | ------------------------------------------------------------ |
| **Total Docs**    | 241 files (across 5 fictional enterprises)                   |
| **PDF Count**     | Every organization provides documents in PDF format          |
| **Other Formats** | HTML, DOCX, PPTX, Markdown (perfect for comparing extraction vs. raw text) |
| **Queries**       | **786 queries** covering 6 types (descriptive, analytical, comparative, boolean, temporal, procedural) |
| **Domains**       | Automotive, Cloud Services, Academia, Tech, Banking          |
| **Access**        | GitHub: `udayallu/RAG-Multi-Corpus`                          |

**Why it fits**: You can directly **compare the same content** in PDF vs. plain Markdown to isolate the effect of chunking strategies. It comes with pre-built queries and ground truth.

------

### 3. OHR-Bench

https://huggingface.co/datasets/opendatalab/OHR-Bench/tree/main

**Large-scale PDF benchmark with 8,500+ pages and 8,498 QA pairs.**
A benchmark designed to evaluate OCR influence on RAG pipelines, but equally useful for chunking strategies.

| Attribute      | Details                                                      |
| -------------- | ------------------------------------------------------------ |
| **PDF Pages**  | 8,500+ pages                                                 |
| **QA Pairs**   | 8,498                                                        |
| **Domains**    | Textbooks, Law, Finance, Newspapers, Manuals, Academia, Administration |
| **Annotation** | Human-verified structured data per page (text, tables, formulas, figures, reading order) |
| **Access**     | Hugging Face: `opendatalab/OHR-Bench` (includes `pdfs.zip` and `qas_v2.json`) |

**Why it fits**: Massive scale and diverse layouts allow you to test chunking robustness across vastly different visual document structures.

------

## II. Other Notable Resources

| Dataset         | Description                                                  | Best For                                                     |
| --------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **ChunkNorris** | 300 question-document pairs (100 PDFs × 3 questions). Includes *multi-chunk* scenarios (32 complex questions requiring cross-page reasoning). | Testing parsers (Marker, Open-Parse, Docling, PyPDF) and chunking strategies side-by-side. |
| **CUAD**        | 510 legal contracts, 13,000+ QA pairs focused on specific clauses and legal concepts. | Testing **long-document, structured legal text** chunking.   |
| **MOAMOB**      | 2 long industrial Korean documents, 71 QA pairs on predictive maintenance. Requires **cross-page reasoning** and structural references. | Stress-test for structural-aware chunking.                   |
| **HRDH**        | ~1,500 PDF academic papers from arXiv (avg. 7.1 pages each). | Academic/Scientific literature retrieval evaluation.         |

------

## III. Quick Start Guides

### Option A: Use PIRE directly (Fastest Path)

bash

```
# 1. Clone the benchmark code
git clone https://github.com/wikit-ai/bench-chunknorris-acl2025.git

# 2. Download the PIRE dataset from Hugging Face
# Visit https://huggingface.co/datasets/Wikit/PIRE

# 3. Run comparison experiments
# The codebase natively supports comparing different parsers and chunking strategies.
```



### Option B: Use RAG-Multi-Corpus (Enterprise Scenario)

bash

```
git clone https://github.com/udayallu/RAG-Multi-Corpus.git
# Data is located in /data, organized by organization folders.
# Each folder contains PDF + Markdown + HTML versions of the same documents.
# Queries are located in the Q&A files.
```



### Option C: Use RAGChunkEval (General Framework)

A dedicated tool that lets you compare sliding-window-merge vs semantic-structure strategies directly.

bash

```
git clone https://github.com/sitong721/RAGChunkEval.git

# Split a PDF
python -m ragchunk_eval.cli split --input test/pdf/example.pdf --method auto --output chunks.json

# Benchmark the chunking strategy
python -m ragchunk_eval.cli benchmark --input test/pdf/example.pdf
```



------

## IV. Summary / Selection Matrix

| If your priority is...                       | Recommended Dataset                               |
| -------------------------------------------- | ------------------------------------------------- |
| **Quick validation** of chunking strategies  | **PIRE** (100 PDFs, manageable size)              |
| **Enterprise RAG** evaluation (multi-format) | **RAG-Multi-Corpus** (multi-domain, multi-format) |
| **Large-scale, diverse** benchmarks          | **OHR-Bench** (8,500+ pages, 8,498 QAs)           |
| **Legal / Contracts**                        | **CUAD** (510 contracts, highly structured)       |
| **Academic papers**                          | **HRDH** (~1,500 arXiv papers)                    |
| **Cross-page reasoning** challenges          | **MOAMOB** (long docs, complex structure)         |

------

### Recommended Workflow for your Benchmark

1. Use **PIRE** for rapid iterative testing of your semantic vs. flat-text chunking implementations.
2. Once validated, scale up to **RAG-Multi-Corpus** to run your final enterprise-grade comparison (especially useful since  it provides both PDF and plain text versions of the same content,  allowing you to isolate chunking effects from parsing artifacts).

Let me know if you need the actual Python evaluation scripts or a detailed metric calculation template for these datasets!