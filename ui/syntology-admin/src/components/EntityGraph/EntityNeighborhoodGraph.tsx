import type { EntityNeighbor, EntityNode } from "../../types/entityGraph";
import { nodeColor } from "../../utils/graphTransformer";

interface EntityNeighborhoodGraphProps {
  entity: EntityNode;
  neighbors: EntityNeighbor[];
  selectedKey: string | null;
  onSelect: (neighbor: EntityNeighbor) => void;
}

export function edgeKey(neighbor: EntityNeighbor): string {
  return `${neighbor.direction}|${neighbor.relation}|${neighbor.entity.id}`;
}

function truncate(value: string | null, max: number): string {
  if (!value) return "unknown";
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}

export function EntityNeighborhoodGraph({
  entity,
  neighbors,
  selectedKey,
  onSelect,
}: EntityNeighborhoodGraphProps) {
  const outgoing = neighbors.filter((neighbor) => neighbor.direction === "OUTGOING");
  const incoming = neighbors.filter((neighbor) => neighbor.direction === "INCOMING");
  const rows = Math.max(outgoing.length, incoming.length);
  const height = Math.max(360, 90 + rows * 46);
  const center = { x: 340, y: height / 2 };

  function yFor(index: number, count: number): number {
    if (count <= 1) return height / 2;
    return 55 + index * ((height - 110) / (count - 1));
  }

  return (
    <svg
      role="img"
      aria-label={`Entity graph for ${entity.label ?? entity.id}`}
      data-testid="entity-graph-svg"
      viewBox={`0 0 680 ${height}`}
      className="min-w-[680px] w-full"
      style={{ minHeight: height }}
    >
      <title>{`Entity graph for ${entity.label ?? entity.id}`}</title>
      <defs>
        <marker
          id="entity-arrow"
          viewBox="0 0 10 10"
          refX="9"
          refY="5"
          markerWidth="6"
          markerHeight="6"
          orient="auto-start-reverse"
        >
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#64748b" />
        </marker>
      </defs>

      {outgoing.map((neighbor, index) => {
        const y = yFor(index, outgoing.length);
        const key = edgeKey(neighbor);
        const selected = key === selectedKey;
        return (
          <g
            key={`${key}-${index}`}
            data-testid={`entity-edge-OUTGOING-${neighbor.entity.id}`}
            onClick={() => onSelect(neighbor)}
            className="cursor-pointer"
          >
            <line
              x1={center.x + 34}
              y1={center.y}
              x2={604}
              y2={y}
              stroke="transparent"
              strokeWidth={16}
            />
            <line
              x1={center.x + 34}
              y1={center.y}
              x2={604}
              y2={y}
              stroke={selected ? "#f59e0b" : "#94a3b8"}
              strokeWidth={selected ? 3 : 2}
              markerEnd="url(#entity-arrow)"
              pointerEvents="none"
            />
            <text
              x={(center.x + 620) / 2}
              y={(center.y + y) / 2 - 5}
              textAnchor="middle"
              fontSize={10}
              fill={selected ? "#b45309" : "#475569"}
              pointerEvents="none"
            >
              {truncate(neighbor.relation, 20)}
            </text>
            <text
              x={620}
              y={y + 4}
              textAnchor="middle"
              fontSize={10}
              fill="#334155"
              pointerEvents="none"
            >
              OUTGOING
            </text>
            <g pointerEvents="none">
              <circle cx={620} cy={y} r={26} fill={nodeColor(neighbor.entity.type ?? "")} opacity={0.15} />
              <circle cx={620} cy={y} r={20} fill={nodeColor(neighbor.entity.type ?? "")} />
              <text x={620} y={y + 4} textAnchor="middle" fontSize={9} fill="#fff">
                {truncate(neighbor.entity.label ?? neighbor.entity.id, 16)}
              </text>
            </g>
          </g>
        );
      })}

      {incoming.map((neighbor, index) => {
        const y = yFor(index, incoming.length);
        const key = edgeKey(neighbor);
        const selected = key === selectedKey;
        return (
          <g
            key={`${key}-${index}`}
            data-testid={`entity-edge-INCOMING-${neighbor.entity.id}`}
            onClick={() => onSelect(neighbor)}
            className="cursor-pointer"
          >
            <line x1={76} y1={y} x2={center.x - 34} y2={center.y} stroke="transparent" strokeWidth={16} />
            <line
              x1={76}
              y1={y}
              x2={center.x - 34}
              y2={center.y}
              stroke={selected ? "#f59e0b" : "#94a3b8"}
              strokeWidth={selected ? 3 : 2}
              markerEnd="url(#entity-arrow)"
              pointerEvents="none"
            />
            <text
              x={(center.x + 60) / 2}
              y={(center.y + y) / 2 - 5}
              textAnchor="middle"
              fontSize={10}
              fill={selected ? "#b45309" : "#475569"}
              pointerEvents="none"
            >
              {truncate(neighbor.relation, 20)}
            </text>
            <text x={60} y={y + 4} textAnchor="middle" fontSize={10} fill="#334155" pointerEvents="none">
              INCOMING
            </text>
            <g pointerEvents="none">
              <circle cx={60} cy={y} r={26} fill={nodeColor(neighbor.entity.type ?? "")} opacity={0.15} />
              <circle cx={60} cy={y} r={20} fill={nodeColor(neighbor.entity.type ?? "")} />
              <text x={60} y={y + 4} textAnchor="middle" fontSize={9} fill="#fff">
                {truncate(neighbor.entity.label ?? neighbor.entity.id, 16)}
              </text>
            </g>
          </g>
        );
      })}

      <g pointerEvents="none">
        <circle cx={center.x} cy={center.y} r={38} fill={nodeColor(entity.type ?? "")} opacity={0.16} />
        <circle cx={center.x} cy={center.y} r={30} fill={nodeColor(entity.type ?? "")} />
        <text x={center.x} y={center.y + 4} textAnchor="middle" fontSize={11} fontWeight={600} fill="#fff">
          {truncate(entity.label ?? entity.id, 20)}
        </text>
      </g>
    </svg>
  );
}
