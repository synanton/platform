#!/usr/bin/env python3
"""Validate a synthetic IT Books gold manifest without running extraction."""
from __future__ import annotations
import argparse, json, sys
from pathlib import Path

A1 = {
 'AUTHORED_BY': ({'Book'}, {'Author'}),
 'HAS_CHAPTER': ({'Book'}, {'Chapter'}),
 'HAS_SECTION': ({'Chapter'}, {'Section'}),
 'COVERS_TOPIC': ({'Book','Chapter','Section'}, {'Topic'}),
 'DEFINES': ({'Chapter','Section'}, {'Concept','Definition'}),
 'USES': ({'Chapter','Section','Algorithm','DataStructure','Database','DistributedSystem'},
          {'Algorithm','Concept','DataStructure','Database','DistributedSystem'}),
 'DEPENDS_ON': ({'Algorithm','Concept','DataStructure','Database','DistributedSystem'},
                {'Algorithm','Concept','DataStructure','Database','DistributedSystem'}),
 'PART_OF': ({'Chapter','Section','Topic'}, {'Book','Chapter','Section'}),
}
TYPES = {'Book','Author','Chapter','Section','Algorithm','DataStructure','Concept','Database','DistributedSystem','Topic','Definition'}
BOUNDARY = {'EXACT_HEADING_BOUNDARY','ALLOWED_SPLIT'}
OVERLAP = {'NONE','ALLOWED'}
JUDGMENT = {'ACCEPTED','REJECTED','UNJUDGED','OMITTED'}
EXPECTATION = {'VALID','INVALID','REQUIRES_MAPPING_REVIEW'}
RETRIEVAL = {'CONDITIONAL','NOT_RUN','SUPPORTED'}

class GoldError(ValueError): pass

def req(node, name, where='root'):
    if name not in node: raise GoldError(f'{where}: missing {name}')

def validate(doc):
    for key in ('schemaVersion','fixture','metadataPolicy','chunks','mentions','relations','negativeAssertions','retrieval','predicatePolicy'):
        req(doc,key)
    if doc['schemaVersion'] != 'synthetic-gold-v1': raise GoldError('unsupported schemaVersion')
    f=doc['fixture']
    for key in ('fixtureVersion','documentId','sourcePath','sourceSha256','mediaType','license','synthetic'):
        req(f,key,'fixture')
    if f['synthetic'] is not True: raise GoldError('fixture.synthetic must be true')
    if not isinstance(f['sourceSha256'],str) or len(f['sourceSha256'])!=64 or any(c not in '0123456789abcdef' for c in f['sourceSha256']):
        raise GoldError('fixture.sourceSha256 must be 64 lowercase hex')
    if f['license'] != 'CC0-1.0': raise GoldError('fixture.license must be CC0-1.0')
    chunks={}
    for c in doc['chunks']:
        for key in ('chunkId','parentId','headingLevel','sectionPath','boundaryPolicy','allowedOverlap','requiredConcepts'):
            req(c,key,'chunk')
        if not c['chunkId'].startswith('gold:'): raise GoldError('chunk IDs must be fixture-local gold:*')
        if c['chunkId'] in chunks: raise GoldError(f"duplicate chunk {c['chunkId']}")
        if c['boundaryPolicy'] not in BOUNDARY: raise GoldError('invalid chunk boundaryPolicy')
        if c['allowedOverlap'] not in OVERLAP: raise GoldError('invalid chunk allowedOverlap')
        if c['headingLevel'] not in range(1,7): raise GoldError('headingLevel must be 1..6')
        chunks[c['chunkId']]=c
    for c in doc['chunks']:
        if c['parentId'] is not None and c['parentId'] not in chunks:
            raise GoldError(f"chunk {c['chunkId']} has unknown parent {c['parentId']}")
    mentions={}
    for m in doc['mentions']:
        for key in ('id','surface','type','chunkId','span','evidence'):
            req(m,key,'mention')
        if not m['id'].startswith('gold:'): raise GoldError('mention IDs must be fixture-local gold:*')
        if m['id'] in mentions: raise GoldError(f"duplicate mention {m['id']}")
        if m['type'] not in TYPES: raise GoldError(f"invalid mention type {m['type']}")
        if m['surface'] in {'Author','Authors','Edition','Document ID','SPDX-License-Identifier'}:
            raise GoldError(f"metadata label or metadata value cannot be a mention: {m['surface']}")
        if m['chunkId'] not in chunks: raise GoldError(f"mention {m['id']} references unknown chunk")
        s=m['span']
        req(s,'start','mention span'); req(s,'end','mention span')
        if not isinstance(s['start'],int) or not isinstance(s['end'],int) or s['start']<0 or s['end']<=s['start']:
            raise GoldError(f"invalid span for {m['id']}")
        mentions[m['id']]=m
    relations={}
    for r in doc['relations']:
        for key in ('id','subjectMentionId','predicate','objectMentionId','judgment','ontologyExpectation','evidence','reason'):
            req(r,key,'relation')
        if not r['id'].startswith('gold:'): raise GoldError('relation IDs must be fixture-local gold:*')
        if r['id'] in relations: raise GoldError(f"duplicate relation {r['id']}")
        if r['predicate'] not in A1: raise GoldError(f"predicate outside pinned A1: {r['predicate']}")
        if r['judgment'] not in JUDGMENT: raise GoldError(f"invalid relation judgment {r['judgment']}")
        if r['ontologyExpectation'] not in EXPECTATION: raise GoldError('invalid ontologyExpectation')
        if r['subjectMentionId'] not in mentions or r['objectMentionId'] not in mentions:
            raise GoldError(f"relation {r['id']} references unknown mention")
        st=mentions[r['subjectMentionId']]['type']; ot=mentions[r['objectMentionId']]['type']
        if r['ontologyExpectation']=='VALID':
            domains,ranges=A1[r['predicate']]
            if st not in domains or ot not in ranges:
                raise GoldError(f"relation {r['id']} marked VALID but violates A1 domain/range")
        relations[r['id']]=r
    if not doc['negativeAssertions']: raise GoldError('at least one negative assertion is required')
    neg=set()
    for n in doc['negativeAssertions']:
        for key in ('id','kind','reason'):
            req(n,key,'negativeAssertion')
        if n['id'] in neg: raise GoldError(f"duplicate negative assertion {n['id']}")
        neg.add(n['id'])
        if n['kind'] not in {'NO_MENTION','NO_RELATION'}: raise GoldError('invalid negative assertion kind')
    for q in doc['retrieval']:
        if q.get('harnessStatus') not in RETRIEVAL: raise GoldError('retrieval harnessStatus must be CONDITIONAL, NOT_RUN or SUPPORTED')
        for cid in q.get('expectedChunkIds',[]):
            if cid not in chunks: raise GoldError(f"retrieval references unknown chunk {cid}")
    pp=doc['predicatePolicy']
    allowed=pp.get('allowedPredicates')
    if set(allowed or []) != set(A1): raise GoldError('predicatePolicy.allowedPredicates must equal the pinned A1 set')
    return {'chunks':len(chunks),'mentions':len(mentions),'relations':len(relations),'negativeAssertions':len(neg),'retrieval':len(doc['retrieval'])}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--manifest',type=Path,required=True)
    args=ap.parse_args()
    try:
        doc=json.loads(args.manifest.read_text())
        print(json.dumps(validate(doc),sort_keys=True))
    except Exception as e:
        print(f'FAIL: {e}',file=sys.stderr); return 1
    return 0
if __name__=='__main__': sys.exit(main())
