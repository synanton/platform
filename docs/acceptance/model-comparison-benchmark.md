# Model comparison benchmark proposal

**Status:** proposed; no benchmark run, model change, or production change.
**Independent of:** #724 acceptance and #723 execution.
**Baseline:** `qwen3-extraction` / Qwen3-4B-Instruct-2507, regex remains the
production provider.

## Goal

Determine whether a larger model or JSON-Schema constrained decoding materially
improves extraction reliability without collapsing semantic quality. The primary
question is semantic reliability; JSON validity is a separate, lower-level
measure.

## Non-goals

- No production provider, extraction default, chunking, prompt, ontology,
  promotion, filtering, or #723 change.
- No use of HOLDOUT rows.
- No full-document replay as part of this proposal.
- No model replacement before the comparison is reviewed.

## Candidates

| ID | Model | Decoding | Purpose |
|---|---|---|---|
| A | Qwen3-4B-Instruct-2507 | Current prompting | Baseline |
| B | Qwen3-4B-Instruct-2507 | JSON Schema constrained | Isolate constrained decoding |
| C | Qwen3-8B-FP8 | JSON Schema constrained | Test larger-model capability with an official FP8 artifact |
| D | Qwen3-8B-FP8, optional | Current prompting | Separate model from decoding mode |

The current GPU is a 16GB RTX 5060 Ti. The 8B candidate is the official
**`Qwen/Qwen3-8B-FP8`** checkpoint, not a community quantization. FP8 reduces
weight memory versus BF16, but KV cache and runtime overhead still must be
measured at the chosen context/concurrency. No 14B candidate is proposed for
this hardware tier.

### Selected 8B artifact

| Field | Value |
|---|---|
| Repository | `Qwen/Qwen3-8B-FP8` |
| Immutable revision | `220b46e3b2180893580a4454f21f22d3ebb187d3` |
| Quantization | Official FP8, block size 128 |
| Runtime | vLLM; verify the deployed vLLM version supports this checkpoint before launch |
| Status | Selected for the proposal; not downloaded or served |

Do not reuse the BF16 repository revision. Do not silently substitute a
community conversion. Verify the installed vLLM version and actual GPU
footprint before the benchmark; a 16GB card is a measurement point, not a
guarantee.

## Input set

Use only the existing reviewed **DEV** chunks from the Track A sample; do not
read or use HOLDOUT. Keep the exact chunk bytes, prompt template, ontology
vocabulary, temperature, and output schema identical across configurations.
Record source version, chunk ordinal, prompt SHA-256, model revision, vLLM
version, quantization, context limit, and output cap for every call.

The 20 DEV chunks are sufficient for a controlled, low-cost comparison; this is
not a claim of production-scale representativeness.

## Output contract

Use one JSON Schema for all constrained runs:

```json
{
  "type": "object",
  "properties": {
    "preEntities": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "surfaceForm": {"type": "string"},
          "proposedType": {"type": "string"},
          "charStart": {"type": "integer"},
          "charEnd": {"type": "integer"}
        },
        "required": ["surfaceForm", "proposedType", "charStart", "charEnd"],
        "additionalProperties": false
      }
    },
    "relations": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "subjectForm": {"type": "string"},
          "predicate": {"type": "string"},
          "objectForm": {"type": "string"},
          "evidenceQuote": {"type": "string"}
        },
        "required": ["subjectForm", "predicate", "objectForm", "evidenceQuote"],
        "additionalProperties": false
      }
    }
  },
  "required": ["preEntities", "relations"],
  "additionalProperties": false
}
```

Constrained runs use vLLM JSON-Schema-guided generation if the deployed vLLM
version supports it; otherwise the experiment is blocked on a verified
constrained-decoding path. Constraining syntax must not be counted as evidence
of semantic correctness.

## Procedure

1. Pin and hash the model artifacts, vLLM image, schema, prompt, DEV inputs, and
   decoding parameters.
2. Warm the model, then run each configuration over the same DEV chunks with
   temperature 0.
3. Capture raw responses and `finish_reason`/usage for every call; use the #766
   capture boundary for parse/incomplete failures.
4. Parse and schema-validate before semantic scoring.
5. Score semantic quality separately from syntax.
6. Record GPU memory, wall-clock latency, tokens/s, and any queueing.
7. Repeat a small subset once to expose nondeterminism; do not tune on HOLDOUT.

## Metrics

### Syntax and generation

- JSON parse success rate.
- Schema compliance rate.
- `finish_reason=length` rate.
- Missing-usage frequency.
- Prompt/completion/total tokens.
- Latency, tokens/s, peak GPU memory.

### Semantics

- Entity candidate precision/recall against available DEV labels or a small
  explicitly labelled spot-check.
- Subject/object validity and type accuracy.
- Predicate correctness.
- Evidence-quote sufficiency.
- Relation candidate precision/recall and accepted-relation rate.
- False-positive promotion count where graph validation is exercised.

The relation acceptance rate is an outcome metric, not the sole optimization
target. A model that emits perfect JSON with worse relations has not improved.

## Expected decision

The benchmark produces a comparison report at
`docs/acceptance/model-comparison-results-YYYY-MM-DD.md` and an explicit
recommendation: keep 4B, adopt constrained decoding only, evaluate 8B further,
or stop. No production switch follows automatically from any result.

## Manual model download on node2/node3

These commands are for an operator preparing the experiment on a GPU/host node.
They do not change the running deployment or provider. Downloading a model is
not the same as serving it.

### Preconditions

- Run on the node that has the target GPU and the `/mnt/local-fast/models`
  host directory used by the vLLM hostPath.
- Confirm free disk and VRAM first:

```bash
nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free --format=csv
df -h /mnt/local-fast
```

- The current vLLM pod is pinned to `node3`; node2 is only relevant if the
  experimental deployment is placed there. Do not move the production pod.
- Obtain a Hugging Face read token through the normal operator credential
  channel. Do not commit tokens, put them in manifests, or paste them into
  shell history.

### Install the Hugging Face CLI

Use an isolated tool environment, for example:

```bash
python3 -m venv /tmp/hf-download-venv
. /tmp/hf-download-venv/bin/activate
python -m pip install --upgrade "huggingface_hub[cli]" hf_transfer
```

### Authenticate

Prefer the non-echoing environment form:

```bash
read -r -s -p 'HF token: ' HF_TOKEN
export HF_TOKEN
export HF_HOME=/mnt/local-fast/hf-cache
export HF_HUB_ENABLE_HF_TRANSFER=1
```

Do not write the token to a checked-in file. If the host policy requires
`hf auth login`, use its interactive prompt; never pass the token on the
command line.

### Download and pin the artifact

Use the exact official Qwen3-8B instruct repository selected by the benchmark
owner. The model card and repository revision must be recorded before download.
The example below uses the official Qwen3-8B repository name; substitute the
verified 2507-instruct artifact if that is the chosen candidate:

```bash
export MODEL_REPO="Qwen/Qwen3-8B-FP8"
export MODEL_REVISION="220b46e3b2180893580a4454f21f22d3ebb187d3"
export MODEL_DIR="/mnt/local-fast/models/qwen3-8b-fp8"

mkdir -p "$MODEL_DIR"
hf download "$MODEL_REPO" \
  --revision "$MODEL_REVISION" \
  --local-dir "$MODEL_DIR" \
  --max-workers 4
```

The selected artifact is already an official FP8 checkpoint. Do not substitute
the BF16 repository or an unverified community quantization. On the 16GB GPU,
measure weights, KV cache, and runtime overhead at the intended context and
concurrency; if FP8 does not fit, reduce scope or stop rather than silently
changing precision.

### Verify the download

```bash
test -s "$MODEL_DIR/config.json"
test -s "$MODEL_DIR/tokenizer_config.json"
find "$MODEL_DIR" -maxdepth 1 -type f -printf '%f %s\n' | sort
python - "$MODEL_DIR/config.json" <<'PY'
import json, pathlib, sys
config = json.loads(pathlib.Path(sys.argv[1]).read_text())
print(config.get("model_type"), config.get("architectures"))
PY
```

Record the repository, revision, file list, total bytes, and a checksum manifest
as experiment metadata. Do not commit model weights.

### Reference vLLM launch for an experiment

Use a separate experimental deployment/pod; do not edit the production
vLLM/provider configuration. The launch must record the model path, revision,
quantization, `--max-model-len`, `--max-num-seqs`, GPU memory utilization, and
served model name. Use a distinct experimental port/service and keep the
production endpoint untouched.

## Deliverables for this proposal

- This design note only.
- No benchmark execution, model deployment, provider change, or #723 action.
