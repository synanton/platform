#!/usr/bin/env bash
set -euo pipefail
URL="${TEI_URL:-http://localhost:30801}"
body=$(curl -fsS --max-time 10 -X POST "$URL/v1/embeddings" \
  -H 'Content-Type: application/json' \
  -d '{"input":"synanton demo preflight","model":"synanton-bge-small-embedding"}')
python3 - "$body" <<'PY'
import json,sys
d=json.loads(sys.argv[1]); dim=len(d['data'][0]['embedding'])
assert dim==384, f"TEI dimension {dim}, expected 384"
print(f"TEI preflight OK: model={d.get('model')} dim={dim}")
PY
