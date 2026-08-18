import { useOntologyContext } from "../../context/OntologyContext";

export function SearchBar() {
  const { searchTerm, setSearchTerm } = useOntologyContext();

  return (
    <input
      type="search"
      placeholder="Search by label or URI..."
      className="w-full max-w-md rounded border border-slate-300 px-3 py-2 text-sm"
      value={searchTerm}
      onChange={(e) => setSearchTerm(e.target.value)}
    />
  );
}
