"""Arm/tenant/snapshot-scoped cache with observable boundary decisions."""
from __future__ import annotations
from dataclasses import dataclass

@dataclass(frozen=True)
class CacheKey:
    tenant: str
    arm_id: str
    source_input_hash: str
    ontology_snapshot_hash: str | None = None
    retrieval_version: str | None = None

class ScopedCache:
    def __init__(self):
        self._data={}; self.hits=0; self.misses=0; self.invalidations=0; self.rejected=[]
    def _validate(self,key):
        if not key.tenant: return 'MISSING_TENANT'
        if key.arm_id=='A' and (key.ontology_snapshot_hash or key.retrieval_version):
            return 'ARM_A_ONTOLOGY_KEY'
        if key.arm_id=='B' and (not key.ontology_snapshot_hash or not key.retrieval_version):
            return 'ARM_B_MISSING_SNAPSHOT'
        if key.arm_id not in ('A','B'): return 'INVALID_ARM'
        return None
    def get(self,key):
        reason=self._validate(key)
        if reason: self.rejected.append((key,reason)); self.misses+=1; return None
        if key in self._data: self.hits+=1; return self._data[key]
        self.misses+=1; return None
    def put(self,key,value):
        reason=self._validate(key)
        if reason: self.rejected.append((key,reason)); return False
        self._data[key]=value; return True
    def invalidate(self,**filters):
        keys=[k for k in self._data if all(getattr(k,f)==v for f,v in filters.items())]
        for k in keys: del self._data[k]
        self.invalidations+=len(keys); return len(keys)
    def stats(self):
        return {'hits':self.hits,'misses':self.misses,'invalidations':self.invalidations,'rejected_mismatches':len(self.rejected)}
