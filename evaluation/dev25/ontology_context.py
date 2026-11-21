"""Deterministic ontology-only context retrieval; no model calls."""
from __future__ import annotations
import hashlib, json, re
from dataclasses import dataclass, asdict

@dataclass(frozen=True)
class OntologyItem:
    object_id: str
    kind: str
    name: str
    definition: str
    tenant: str
    ontology_version: str
    constraints: tuple[str, ...] = ()

@dataclass(frozen=True)
class ContextSnapshot:
    tenant: str
    ontology_version: str
    query_sha256: str
    top_k: int
    object_ids: tuple[str, ...]
    context: str
    context_sha256: str

def _tokens(text):
    return set(re.findall(r"[a-z0-9]+", (text or "").lower()))

def retrieve_ontology(query, items, *, tenant, ontology_version, top_k=8, max_context_chars=6000):
    eligible=[i for i in items if i.tenant==tenant and i.ontology_version==ontology_version]
    q=_tokens(query)
    scored=[]
    for item in eligible:
        score=len(q & _tokens(item.name+' '+item.definition+' '+' '.join(item.constraints)))
        if score>0: scored.append((score,item))
    scored.sort(key=lambda x:(-x[0],x[1].object_id))
    chosen=[]; blocks=[]; size=0
    for _,item in scored:
        block=f"[{item.kind}] {item.name}: {item.definition}"
        if item.constraints: block+=' Constraints: '+'; '.join(item.constraints)
        if size+len(block)>max_context_chars: break
        chosen.append(item); blocks.append(block); size+=len(block)
        if len(chosen)>=top_k: break
    context='\n'.join(blocks)
    return ContextSnapshot(tenant,ontology_version,hashlib.sha256((query or '').encode()).hexdigest(),top_k,tuple(i.object_id for i in chosen),context,hashlib.sha256(context.encode()).hexdigest())

def snapshot_json(snap):
    return json.dumps(asdict(snap),sort_keys=True)
