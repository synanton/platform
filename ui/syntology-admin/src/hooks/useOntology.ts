import { useEffect, useState } from "react";
import { fetchGraph } from "../services/ontologyApi";
import { useOntologyContext } from "../context/OntologyContext";

export function useOntology() {
  const { version, setGraph } = useOntologyContext();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchGraph(version)
      .then((data) => {
        if (!cancelled) {
          setGraph(data);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
          setGraph(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [version, setGraph]);

  return { loading, error };
}
