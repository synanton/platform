export type NeighborDirection = "INCOMING" | "OUTGOING";

export interface EntityNode {
  id: string;
  label: string | null;
  type: string | null;
}

export interface EntityNeighbor {
  entity: EntityNode;
  relation: string;
  direction: NeighborDirection;
}

export interface EntityEvidence {
  relation: string;
  neighbor_entity_id: string;
  chunk_id: string;
  source_version: string;
  offset_start: number;
  offset_end: number;
}

export interface EntityNeighborsResponse {
  entity: EntityNode;
  neighbors: EntityNeighbor[];
  evidence: EntityEvidence[];
  truncated: boolean;
}
