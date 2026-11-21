#!/usr/bin/env python3
"""Run the registered 12+3 chunk 32K truncation pilot with provenance."""
from __future__ import annotations
import argparse,csv,hashlib,json,pathlib,re,sys,time,uuid,urllib.request
import grpc
ROOT=pathlib.Path('/home/aminin/workspace/synanton/gpu-runtime'); PLAT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'/'gpu7-check')); import gpu7_check as g
from extraction_pipeline import ALLOWED_TYPES,ALLOWED_PREDICATES,preflight_output_budget
TYPES=", ".join(sorted(ALLOWED_TYPES)); PREDS=", ".join(sorted(ALLOWED_PREDICATES))
def sha(s): return hashlib.sha256(s.encode()).hexdigest()
def main():
 ap=argparse.ArgumentParser(); ap.add_argument('--outdir',required=True); ap.add_argument('--max-tokens',type=int,default=32768); ap.add_argument('--cap-usd',type=float,default=5.0); a=ap.parse_args()
 dev=PLAT/'evaluation/dev25'; sel=list(csv.DictReader(open(dev/'experiments/truncation-pilot-selection.tsv'),delimiter='\t')); man={r['doc_id']:r for r in csv.DictReader(open(dev/'manifest.tsv'),delimiter='\t')}
 # live provider limit verification
 key=None
 for line in (ROOT/'deployments/external/.env').read_text().splitlines():
  if line.startswith('OPENCODE_API_KEY='): key=line.split('=',1)[1].strip()
 req=urllib.request.Request('https://opencode.ai/zen/v1/models',headers={'Authorization':'Bearer '+key,'User-Agent':'synanton/1.0 (Synthesis & Semantics App)'})
 with urllib.request.urlopen(req,timeout=30) as r: models=json.load(r)
 meta=next(m for m in models['data'] if m.get('id')=='deepseek-v4-pro'); limit=int(meta['limit']['output']); source='live:opencode/zen/v1/models:deepseek-v4-pro'
 errors=preflight_output_budget([{'recommended_max_tokens':a.max_tokens}],limit,source)
 if errors: raise SystemExit('BLOCKED '+json.dumps(errors))
 out=pathlib.Path(a.outdir); (out/'raw').mkdir(parents=True,exist_ok=True)
 prompt=(PLAT/'evaluation/prompts/llm_extraction_system.txt').read_text().replace('{allowed_types}',TYPES).replace('{allowed_predicates}',PREDS)
 pb,rpc=g.load_stubs(); stub=rpc.GPUExecutionServiceStub(g.channel('localhost:9090',ROOT/'deployments/external/certs','gpu7-smoke',False))
 prov=open(out/'provenance.jsonl','a',encoding='utf-8'); total_in=total_out=0
 for row in sel:
  did=row['doc_id']; src=dev/'extracted'/f'{did}.txt'; text=src.read_text() if src.exists() else (PLAT/man[did]['source_path']).read_text()
  chunk=text[int(row['chunk_start']):int(row['chunk_end'])]
  if sha(chunk)!=row['source_chunk_sha256']: raise SystemExit(f'chunk hash mismatch {row["selection_id"]}')
  rid=f'p32k-{uuid.uuid4()}'
  payload={'model':'synanton-external-chat','messages':[{'role':'system','content':prompt},{'role':'user','content':chunk}],'temperature':0,'max_tokens':a.max_tokens,'stream':False,'thinking':{'type':'disabled'},'reasoning_effort':'low'}
  req=pb.ExecutionRequest(request_id=rid,tenant_id='smoke-tenant',model='synanton-external-chat',model_version='1',operation=pb.SYNTHESIZE,payload=json.dumps(payload).encode())
  t=time.monotonic(); rec={'request_id':rid,'selection_id':row['selection_id'],'doc_id':did,'chunk_id':row['chunk_id'],'model_id':'deepseek-v4-pro','provider':'opencode','max_tokens':a.max_tokens,'provider_limit':limit,'provider_limit_source':source,'chunk_sha256':row['source_chunk_sha256']}
  try:
   r=stub.Execute(req,timeout=300); rec['latency_s']=time.monotonic()-t; rec['state']=pb.ExecutionState.Name(r.state); rec['usage']={'input':r.usage.input_tokens,'output':r.usage.output_tokens}; rec['upstream_request_id']=r.upstream_request_id; rec['error_code']=r.error.code; rec['error_message']=r.error.message
   raw=r.result.decode('utf-8','replace') if r.result else ''; rec['response_sha256']=sha(raw); rp=out/'raw'/f'{row["selection_id"]}.json'; rp.write_text(raw); rec['raw_response_path']=str(rp.relative_to(out)); total_in+=r.usage.input_tokens; total_out+=r.usage.output_tokens
   content=json.loads(raw)['choices'][0]['message'].get('content') or ''; ch=json.loads(raw)['choices'][0]; rec['finish_reason']=ch.get('finish_reason')
   try:
    s=content.strip(); x=s.find('{'); y=s.rfind('}'); obj=json.loads(s[x:y+1]); rec['json_valid']=True
    ents=obj.get('preEntities',[]) or []; rels=obj.get('relations',[]) or []
    schema=True
    for e in ents:
     schema &= isinstance(e.get('surfaceForm'),str) and e.get('proposedType') in ALLOWED_TYPES and isinstance(e.get('charStart'),int) and isinstance(e.get('charEnd'),int)
     if schema: schema &= 0<=int(e['charStart'])<int(e['charEnd'])<=len(chunk) and chunk[int(e['charStart']):int(e['charEnd'])]==e['surfaceForm']
    for rr in rels:
     pred=re.sub(r'[^a-z0-9]+','_',(rr.get('predicate') or '').lower()).strip('_'); schema &= pred in ALLOWED_PREDICATES and isinstance(rr.get('subjectForm'),str) and isinstance(rr.get('objectForm'),str) and isinstance(rr.get('evidenceQuote'),str)
    rec['schema_valid']=bool(schema); rec['entities']=len(ents); rec['relations']=len(rels)
   except Exception as ex:
    rec['json_valid']=False; rec['schema_valid']=False; rec['parse_error']=f'{type(ex).__name__}: {ex}'
  except Exception as ex:
   rec['state']='CLIENT_ERROR'; rec['error_message']=f'{type(ex).__name__}: {ex}'; rec['json_valid']=False; rec['schema_valid']=False
  rec['estimated_cost_usd']=(total_in*1.74+total_out*3.48)/1e6
  prov.write(json.dumps(rec)+'\n'); prov.flush(); print(json.dumps({k:rec.get(k) for k in ('selection_id','state','finish_reason','json_valid','schema_valid','entities','relations','usage','latency_s','estimated_cost_usd')}),flush=True)
  if rec['estimated_cost_usd']>a.cap_usd: print('STOP cap'); break
 prov.close(); print('SUMMARY',json.dumps({'calls':len(sel),'input':total_in,'output':total_out,'estimated_cost_usd':round((total_in*1.74+total_out*3.48)/1e6,4),'provider_limit':limit}))
if __name__=='__main__': main()
