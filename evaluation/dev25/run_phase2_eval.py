#!/usr/bin/env python3
"""GPU-7 Phase 2 DEV evaluation with immutable provenance.

Every provider call records: request_id, run_id, doc_id, chunk_id, offsets,
model/provider identifiers, prompt/schema hashes, settings, timestamps, usage,
state/error, raw response path and response hash. Results are written per
document so interrupted runs remain auditable.
"""
from __future__ import annotations
import argparse, csv, hashlib, json, pathlib, re, sys, time, uuid
import grpc
ROOT=pathlib.Path('/home/aminin/workspace/synanton/gpu-runtime')
PLAT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'/'gpu7-check'))
import gpu7_check as g
from relation_scoring import score_relations, norm_surface
TYPES=("MaintenanceSystem, Component, Protocol, Procedure, Material, Organization, Person, Role, Product, Document, Standard, Metric, Measurement, Location, Time, Algorithm, DataStructure, Concept, Event, Problem")
PREDS=("has_component, part_of, tested_every, replaced_every, signs, records, documents, performed_on, logs, verifies, replaced_at, requires, repeats_every, applied_to, is_a, used_in, improves_over, discussed_in, same_as, seeks, visits, combines, implements, uses, has, related_to, computes, measures, obeys, matches")
PROVIDER='opencode'; LOGICAL='synanton-external-chat'
def sha(text): return hashlib.sha256(text.encode()).hexdigest()
def parse(content):
    s=(content or '').strip()
    if s.startswith('```'):
        s=s.split('\n',1)[-1]; s=s.rsplit('```',1)[0]
    a=s.find('{'); b=s.rfind('}')
    if a<0 or b<=a: raise ValueError('no JSON object')
    return json.loads(s[a:b+1],strict=False)
def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--model',required=True); ap.add_argument('--outdir',required=True)
    ap.add_argument('--only',default=None); ap.add_argument('--chunk',type=int,default=12000)
    ap.add_argument('--max-tokens',type=int,default=8192)
    ap.add_argument('--input-usd-per-m',type=float,default=0.30)
    ap.add_argument('--output-usd-per-m',type=float,default=1.20)
    ap.add_argument('--cap-usd',type=float,default=3.50)
    args=ap.parse_args()
    dev=PLAT/'evaluation/dev25'; out=pathlib.Path(args.outdir); (out/'raw'/args.model).mkdir(parents=True,exist_ok=True); (out/'documents').mkdir(exist_ok=True)
    run_id=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())+'-'+uuid.uuid4().hex[:8]
    prompt_tpl=(PLAT/'evaluation/prompts/llm_extraction_system.txt').read_text(); prompt=prompt_tpl.replace('{allowed_types}',TYPES).replace('{allowed_predicates}',PREDS)
    manifest=list(csv.DictReader(open(dev/'manifest.tsv'),delimiter='\t'))
    if args.only:
        ids=set(args.only.split(',')); manifest=[r for r in manifest if r['doc_id'] in ids]
    pb,rpc=g.load_stubs(); stub=rpc.GPUExecutionServiceStub(g.channel('localhost:9090',ROOT/'deployments/external/certs','gpu7-smoke',False))
    prov=open(out/'provenance.jsonl','a',encoding='utf-8')
    def write_prov(rec): prov.write(json.dumps(rec,ensure_ascii=False)+'\n'); prov.flush()
    total_in=total_out=0
    for row in manifest:
        did=row['doc_id']; doc_out=out/'documents'/f'{did}.json'
        if doc_out.exists():
            print(json.dumps({'doc_id':did,'skipped':'existing'})); continue
        tp=dev/'extracted'/f'{did}.txt'; text=tp.read_text() if tp.exists() else (PLAT/row['source_path']).read_text()
        gold=json.loads((PLAT/row['gold_record']).read_text())
        entities=[]; emap={}; rels=[]; rset=set(); valid_chunks=0; total_chunks=0; rejected=0
        for ci,start in enumerate(range(0,len(text),args.chunk)):
            chunk=text[start:start+args.chunk]; total_chunks+=1
            rid=f'p2v2-{run_id}-{did}-c{ci}'
            payload={'model':LOGICAL,'messages':[{'role':'system','content':prompt},{'role':'user','content':chunk}],'temperature':0,'max_tokens':args.max_tokens,'stream':False,'thinking':{'type':'disabled'},'reasoning_effort':'low'}
            req=pb.ExecutionRequest(request_id=rid,tenant_id='smoke-tenant',model=LOGICAL,model_version='1',operation=pb.SYNTHESIZE,payload=json.dumps(payload).encode())
            rec={'request_id':rid,'run_id':run_id,'doc_id':did,'chunk_id':ci,'chunk_start':start,'chunk_end':start+len(chunk),'chunk_sha256':sha(chunk),'model_id':args.model,'provider':PROVIDER,'logical_model':LOGICAL,'prompt_sha256':sha(prompt_tpl),'schema':'extraction-v1','temperature':0,'max_tokens':args.max_tokens,'thinking':'disabled','reasoning_effort':'low'}
            t=time.monotonic()
            try:
                r=stub.Execute(req,timeout=240); rec['latency_s']=time.monotonic()-t; rec['state']=pb.ExecutionState.Name(r.state); rec['usage']={'input':r.usage.input_tokens,'output':r.usage.output_tokens}; rec['upstream_request_id']=r.upstream_request_id; rec['error_code']=r.error.code; rec['error_message']=r.error.message
                raw=r.result.decode('utf-8','replace') if r.result else ''
                rec['response_sha256']=sha(raw)
                raw_path=out/'raw'/args.model/f'{did}-c{ci:03d}.json'; raw_path.write_text(raw)
                rec['raw_response_path']=str(raw_path.relative_to(out)); rec['timestamp']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
                total_in+=r.usage.input_tokens; total_out+=r.usage.output_tokens
                write_prov(rec)
                if r.state!=pb.SUCCESS: continue
                obj=parse(json.loads(raw)['choices'][0]['message'].get('content') or ''); valid_chunks+=1
            except Exception as e:
                rec['state']='CLIENT_ERROR'; rec['error_message']=f'{type(e).__name__}: {e}'; rec['latency_s']=time.monotonic()-t; rec['timestamp']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()); write_prov(rec); continue
            local={}
            for e in obj.get('preEntities',[]) or []:
                surf=(e.get('surfaceForm') or '').strip(); typ=(e.get('proposedType') or '').strip()
                if not surf or typ not in TYPES: rejected+=1; continue
                try: gs,ge=start+int(e.get('charStart')),start+int(e.get('charEnd'))
                except: rejected+=1; continue
                if ge<=gs or ge>len(text) or text[gs:ge]!=surf:
                    pos=text.find(surf,start,start+len(chunk))
                    if pos<0: rejected+=1; continue
                    gs,ge=pos,pos+len(surf)
                if (gs,ge) in emap: continue
                mid=f'm{len(entities)+1}'; emap[(gs,ge)]=mid
                entities.append({'mention_id':mid,'surface_form':surf,'char_start':gs,'char_end':ge,'proposed_type':typ})
                local.setdefault(surf.lower(),mid)
            for rr in obj.get('relations',[]) or []:
                s=(rr.get('subjectForm') or '').strip().lower(); o=(rr.get('objectForm') or '').strip().lower(); pred=re.sub(r'[^a-z0-9]+','_',(rr.get('predicate') or '').strip().lower()).strip('_')
                if pred not in PREDS or s not in local or o not in local or local[s]==local[o]: rejected+=1; continue
                k=(local[s],pred,local[o])
                if k in rset: continue
                rset.add(k); rels.append({'subject_id':local[s],'predicate':pred,'object_id':local[o]})
            est=(total_in*args.input_usd_per_m+total_out*args.output_usd_per_m)/1e6
            if est>args.cap_usd:
                write_prov({'run_id':run_id,'stop':'cap_exceeded','estimated_cost_usd':est,'timestamp':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}); raise SystemExit(f'cap exceeded: {est}')
        metrics={'doc_id':did,'model_id':args.model,'chunks':total_chunks,'valid_chunks':valid_chunks,'entities':len(entities),'relations':len(rels),'rejected':rejected}
        if entities:
            pe=[(e['char_start'],e['char_end']) for e in entities]; ge=[(int(e['char_start']),int(e['char_end'])) for e in gold['entities']]
            used=set(); tp=0
            for x in pe:
                for i,y in enumerate(ge):
                    if i not in used and x==y: used.add(i); tp+=1; break
            p=tp/len(pe) if pe else 0; r=tp/len(ge) if ge else 0; metrics['entity']={'tp':tp,'pred':len(pe),'gold':len(ge),'p':p,'r':r,'f1':(2*p*r/(p+r) if p+r else 0)}
        metrics['relation']=score_relations(entities,[{'subject_id':x['subject_id'],'predicate':x['predicate'],'object_id':x['object_id']} for x in rels],gold['entities'],gold['relationships'])
        doc_out.write_text(json.dumps(metrics,indent=2)); print(json.dumps({'doc_id':did,'chunks':total_chunks,'valid':valid_chunks,'entities':len(entities),'relations':len(rels),'e_f1':round(metrics.get('entity',{}).get('f1',0),3),'r_f1':round(metrics['relation']['f1'],3)}),flush=True)
    prov.close()
    # aggregate existing docs in outdir
    docs=[json.loads(p.read_text()) for p in sorted((out/'documents').glob('*.json'))]
    summary={'run_id':run_id,'model_id':args.model,'documents':len(docs),'input_tokens':total_in,'output_tokens':total_out,'estimated_cost_usd':round((total_in*args.input_usd_per_m+total_out*args.output_usd_per_m)/1e6,4),'documents_detail':docs}
    (out/f'summary-{args.model}.json').write_text(json.dumps(summary,indent=2)); print('SUMMARY',json.dumps({k:v for k,v in summary.items() if k!='documents_detail'}))
if __name__=='__main__': main()
