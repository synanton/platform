export interface GraphNode {
  id: string;
  label: string;
  type: string;
}

export interface GraphEdge {
  source: string;
  target: string;
  label: string;
}

export interface OntologyGraph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface OntologyVersion {
  versionId: string;
  tenantId: string;
  version: string;
  label: string;
  description: string | null;
  graphUri: string | null;
  status: string;
  createdAt: string;
}

export interface EntityType {
  uri: string;
  label: string;
  superTypes: string[];
  properties: Record<string, string>;
}

export interface AuthSession {
  username: string;
  tenantId: string;
  token: string;
  uid: number;
  gids: number[];
  exp: number;
}

export interface AclGrant {
  grantId: string;
  resourcePath: string;
  permission: string;
  source: string;
  subjectType: string;
  createdAt: string;
}
