import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { loginWithCredentials } from "../services/auth";

export function Login() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await loginWithCredentials(username, password);
      navigate("/dashboard");
    } catch {
      setError("Invalid username or password.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-brand-900 to-slate-800 px-4">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-md rounded-xl bg-white p-8 shadow-xl space-y-6"
      >
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Syntology Demo</h1>
          <p className="text-sm text-slate-500 mt-1">Sign in to continue.</p>
        </div>
        {error && (
          <div className="rounded bg-red-50 border border-red-200 text-red-700 text-sm px-3 py-2">
            {error}
          </div>
        )}
        <label className="block text-sm">
          <span className="font-medium text-slate-700">Username</span>
          <input
            className="mt-1 w-full rounded border border-slate-300 px-3 py-2"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="alice"
            autoFocus
            autoComplete="username"
          />
        </label>
        <label className="block text-sm">
          <span className="font-medium text-slate-700">Password</span>
          <input
            type="password"
            className="mt-1 w-full rounded border border-slate-300 px-3 py-2"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
            autoComplete="current-password"
          />
        </label>
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-lg bg-brand-500 py-2.5 text-white font-medium hover:bg-brand-700 disabled:opacity-60"
        >
          {loading ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
