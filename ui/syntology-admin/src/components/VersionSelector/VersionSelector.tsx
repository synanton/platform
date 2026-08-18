import { useEffect, useState } from "react";
import { fetchVersions } from "../../services/ontologyApi";
import type { OntologyVersion } from "../../types/ontology";
import { useOntologyContext } from "../../context/OntologyContext";

export function VersionSelector() {
  const { version, setVersion } = useOntologyContext();
  const [versions, setVersions] = useState<OntologyVersion[]>([]);

  useEffect(() => {
    fetchVersions()
      .then(setVersions)
      .catch(() => setVersions([]));
  }, [version]);

  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="font-medium text-slate-600">Version</span>
      <select
        className="rounded border border-slate-300 px-3 py-1.5 bg-white"
        value={version}
        onChange={(e) => setVersion(e.target.value)}
      >
        <option value="active">Active</option>
        {versions.map((v) => (
          <option key={v.versionId} value={v.version}>
            {v.version} ({v.status})
          </option>
        ))}
      </select>
    </label>
  );
}
