"""Separate semantic model proposals from deterministically validated extractions."""
from __future__ import annotations
from dataclasses import dataclass, field
from surface_anchoring import anchor_surface
from offline_validation import norm_predicate

@dataclass(frozen=True)
class ModelEntityProposal:
    surface: str
    proposed_type: str
    context: str | None = None

@dataclass(frozen=True)
class ModelRelationProposal:
    subject: str
    predicate: str
    object: str
    evidence: str | None = None

@dataclass(frozen=True)
class ModelProposal:
    entities: tuple[ModelEntityProposal, ...] = ()
    relations: tuple[ModelRelationProposal, ...] = ()

@dataclass(frozen=True)
class ValidatedEntity:
    mention_id: str
    surface: str
    proposed_type: str
    chunk_char_start: int
    chunk_char_end: int
    char_start: int
    char_end: int
    chunk_id: int | None = None
    model_surface: str | None = None

@dataclass(frozen=True)
class ValidatedRelationship:
    subject_id: str
    predicate: str
    object_id: str
    evidence_start: int
    evidence_end: int
    evidence: str

@dataclass(frozen=True)
class ValidatedExtraction:
    entities: tuple[ValidatedEntity, ...]
    relationships: tuple[ValidatedRelationship, ...]
    rejected: tuple[dict, ...] = field(default_factory=tuple)

def validate_proposal(proposal: ModelProposal, chunk_text, *, chunk_start=0,
                      allowed_types=frozenset(), allowed_predicates=frozenset(), chunk_id=None,
                      ontology_context=None):
    if not isinstance(proposal, ModelProposal):
        raise TypeError('validate_proposal requires ModelProposal')
    entities=[]; rejected=[]; by_surface={}
    for e in proposal.entities:
        if e.proposed_type not in allowed_types:
            rejected.append({'kind':'entity','surface':e.surface,'reason':'TYPE_NOT_ALLOWED'}); continue
        a=anchor_surface(chunk_text,e.surface,context=e.context,chunk_start=chunk_start)
        if a['status']!='RESOLVED':
            rejected.append({'kind':'entity','surface':e.surface,'reason':a['reason']}); continue
        mid=f'm{len(entities)+1}'
        ve=ValidatedEntity(mid,a['surface'],e.proposed_type,a['chunk_char_start'],a['chunk_char_end'],a['char_start'],a['char_end'],chunk_id,e.surface)
        entities.append(ve); by_surface.setdefault(a['surface'].lower(),[]).append(ve)
    rels=[]
    for r in proposal.relations:
        if norm_predicate(r.predicate) not in allowed_predicates:
            rejected.append({'kind':'relation','reason':'PREDICATE_NOT_ALLOWED','predicate':r.predicate}); continue
        ss=by_surface.get((r.subject or '').lower(),[]); oo=by_surface.get((r.object or '').lower(),[])
        if len(ss)!=1 or len(oo)!=1 or ss[0].mention_id==oo[0].mention_id:
            rejected.append({'kind':'relation','reason':'AMBIGUOUS_OR_MISSING_ENDPOINT','subject':r.subject,'object':r.object}); continue
        if not r.evidence:
            rejected.append({'kind':'relation','reason':'MISSING_EVIDENCE'}); continue
        positions=[]; start=0
        while True:
            i=chunk_text.find(r.evidence,start)
            if i<0: break
            positions.append(i); start=i+1
        if len(positions)!=1:
            ontology_only = bool(ontology_context) and r.evidence in ontology_context
            rejected.append({'kind':'relation','reason':'AMBIGUOUS_EVIDENCE' if positions else ('ONTOLOGY_CONTEXT_NOT_EVIDENCE' if ontology_only else 'EVIDENCE_NOT_FOUND')}); continue
        p=positions[0]
        rels.append(ValidatedRelationship(ss[0].mention_id,norm_predicate(r.predicate),oo[0].mention_id,chunk_start+p,chunk_start+p+len(r.evidence),r.evidence))
    return ValidatedExtraction(tuple(entities),tuple(rels),tuple(rejected))

def summarize_rejections(validated):
    """Count rejection reasons for per-attempt evaluation output."""
    from collections import Counter
    counts=Counter((x.get('kind'),x.get('reason')) for x in validated.rejected)
    return [{'kind':k,'reason':r,'count':n} for (k,r),n in sorted(counts.items())]
