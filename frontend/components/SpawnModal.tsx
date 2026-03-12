'use client';

import { useState, useEffect } from 'react';
import { X } from 'lucide-react';
import { Project } from '../types/session';

interface Props {
  onClose: () => void;
  onSpawned: () => void;
}

export default function SpawnModal({ onClose, onSpawned }: Props) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectName, setProjectName] = useState('');
  const [ticketId, setTicketId] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    fetch('/api/projects')
      .then((r) => r.json())
      .then((data: Project[]) => {
        setProjects(data);
        if (data.length > 0) setProjectName(data[0].name);
      })
      .catch(() => {/* ignore — user can type project name manually */});
  }, []);

  const selectedProject = projects.find((p) => p.name === projectName);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    try {
      const res = await fetch('/api/sessions/spawn', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          projectName,
          ticketId,
          agentType: selectedProject?.agentType ?? 'claude',
        }),
      });
      if (!res.ok) throw new Error(`Server responded ${res.status}`);
      onSpawned();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to spawn agent');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div
        className="w-full max-w-md rounded-xl p-6 relative"
        style={{ backgroundColor: '#1A2236' }}
      >
        <button
          onClick={onClose}
          className="absolute top-4 right-4 text-gray-400 hover:text-white transition-colors"
        >
          <X size={18} />
        </button>

        <h2 className="text-white font-semibold text-lg mb-5">Spawn Agent</h2>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div>
            <label className="block text-sm text-gray-400 mb-1">Project</label>
            {projects.length > 0 ? (
              <select
                value={projectName}
                onChange={(e) => setProjectName(e.target.value)}
                className="w-full px-3 py-2 rounded-lg bg-[#0A0F1E] border border-gray-700 text-white text-sm focus:outline-none focus:border-blue-500"
              >
                {projects.map((p) => (
                  <option key={p.name} value={p.name}>
                    {p.name} ({p.repo})
                  </option>
                ))}
              </select>
            ) : (
              <input
                type="text"
                value={projectName}
                onChange={(e) => setProjectName(e.target.value)}
                required
                placeholder="nucleus-ai"
                className="w-full px-3 py-2 rounded-lg bg-[#0A0F1E] border border-gray-700 text-white placeholder-gray-600 text-sm focus:outline-none focus:border-blue-500"
              />
            )}
          </div>

          {selectedProject && (
            <div className="text-xs text-gray-500 -mt-2 px-1">
              Agent: <span className="text-gray-400">{selectedProject.agentType}</span>
              &nbsp;·&nbsp;
              Runtime: <span className="text-gray-400">{selectedProject.runtime}</span>
              &nbsp;·&nbsp;
              Branch: <span className="text-gray-400">{selectedProject.defaultBranch}</span>
            </div>
          )}

          <div>
            <label className="block text-sm text-gray-400 mb-1">Ticket ID</label>
            <input
              type="text"
              value={ticketId}
              onChange={(e) => setTicketId(e.target.value)}
              required
              placeholder="JIRA-421"
              className="w-full px-3 py-2 rounded-lg bg-[#0A0F1E] border border-gray-700 text-white placeholder-gray-600 text-sm focus:outline-none focus:border-blue-500"
            />
          </div>

          {error && <p className="text-red-400 text-sm">{error}</p>}

          <button
            type="submit"
            disabled={loading || !projectName}
            className="mt-1 w-full py-2 rounded-lg bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white font-medium text-sm transition-colors"
          >
            {loading ? 'Spawning...' : 'Spawn Agent'}
          </button>
        </form>
      </div>
    </div>
  );
}
