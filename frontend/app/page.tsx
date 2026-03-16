'use client';

import { useState, useEffect, useCallback } from 'react';
import { Atom, Plus } from 'lucide-react';
import { Client } from '@stomp/stompjs';
import { Session, SessionStatus, Project } from '../types/session';
import SessionCard from '../components/SessionCard';
import SpawnModal from '../components/SpawnModal';
import LogsModal from '../components/LogsModal';

const COLUMNS: { status: SessionStatus; label: string }[] = [
  { status: 'PENDING', label: 'PENDING' },
  { status: 'RUNNING', label: 'RUNNING' },
  { status: 'PR_OPEN', label: 'PR OPEN' },
  { status: 'IN_REVIEW', label: 'IN REVIEW' },
  { status: 'MERGED', label: 'MERGED' },
  { status: 'FAILED', label: 'FAILED' },
];

const COLUMN_ACCENT: Record<SessionStatus, string> = {
  PENDING: 'border-yellow-500',
  RUNNING: 'border-blue-500',
  PR_OPEN: 'border-purple-500',
  IN_REVIEW: 'border-orange-500',
  MERGED: 'border-green-500',
  FAILED: 'border-red-500',
};

export default function Dashboard() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectFilter, setProjectFilter] = useState<string>('');
  const [showSpawn, setShowSpawn] = useState(false);
  const [logsSession, setLogsSession] = useState<Session | null>(null);

  const fetchSessions = useCallback(async () => {
    try {
      const res = await fetch('/api/sessions');
      if (!res.ok) return;
      const data: Session[] = await res.json();
      setSessions(data);
    } catch (err) {
      console.error('Failed to fetch sessions', err);
    }
  }, []);

  const fetchProjects = useCallback(async () => {
    try {
      const res = await fetch('/api/projects');
      if (!res.ok) return;
      const data: Project[] = await res.json();
      setProjects(data);
    } catch (err) {
      console.error('Failed to fetch projects', err);
    }
  }, []);

  // Initial load
  useEffect(() => {
    fetchSessions();
    fetchProjects();
  }, [fetchSessions, fetchProjects]);

  // WebSocket for live updates
  useEffect(() => {
    const client = new Client({
      brokerURL: 'ws://localhost:8080/ws/sessions',
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe('/topic/sessions', (msg) => {
          try {
            const updated: Session = JSON.parse(msg.body);
            setSessions((prev) => {
              const idx = prev.findIndex((s) => s.sessionId === updated.sessionId);
              if (idx >= 0) {
                const next = [...prev];
                next[idx] = updated;
                return next;
              }
              return [...prev, updated];
            });
          } catch (err) {
            console.error('WS parse error', err);
          }
        });
      },
    });

    client.activate();
    return () => { client.deactivate(); };
  }, []);

  const filteredSessions = projectFilter
    ? sessions.filter((s) => s.projectName === projectFilter)
    : sessions;

  const sessionsFor = (status: SessionStatus) =>
    filteredSessions.filter((s) => s.status === status);

  return (
    <div className="min-h-screen" style={{ backgroundColor: '#0A0F1E' }}>
      {/* Top bar */}
      <header
        className="flex items-center justify-between px-6 py-4 border-b border-gray-800"
        style={{ backgroundColor: '#1A2236' }}
      >
        <div className="flex items-center gap-2">
          <Atom size={24} className="text-blue-400" />
          <span className="text-white font-semibold text-lg tracking-tight">Nucleus AI</span>
        </div>

        <div className="flex items-center gap-3">
          {/* Project filter dropdown */}
          {projects.length > 0 && (
            <select
              value={projectFilter}
              onChange={(e) => setProjectFilter(e.target.value)}
              className="px-3 py-1.5 rounded-lg bg-[#0A0F1E] border border-gray-700 text-white text-sm focus:outline-none focus:border-blue-500"
            >
              <option value="">All projects</option>
              {projects.map((p) => (
                <option key={p.name} value={p.name}>
                  {p.name}
                </option>
              ))}
            </select>
          )}

          <button
            onClick={() => setShowSpawn(true)}
            className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium transition-colors"
          >
            <Plus size={16} />
            Spawn Agent
          </button>
        </div>
      </header>

      {/* Kanban board */}
      <main className="flex gap-4 p-6 overflow-x-auto">
        {COLUMNS.map(({ status, label }) => (
          <div
            key={status}
            className={`flex-shrink-0 w-72 rounded-xl flex flex-col border-t-2 ${COLUMN_ACCENT[status]}`}
            style={{ backgroundColor: '#111827' }}
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-gray-800">
              <span className="text-xs font-semibold text-gray-400 tracking-widest">{label}</span>
              <span className="text-xs text-gray-500 bg-gray-800 px-2 py-0.5 rounded-full">
                {sessionsFor(status).length}
              </span>
            </div>
            <div className="flex-1 overflow-y-auto p-3">
              {sessionsFor(status).length === 0 ? (
                <p className="text-gray-700 text-xs text-center mt-6">No sessions</p>
              ) : (
                sessionsFor(status).map((session) => (
                  <SessionCard
                    key={session.sessionId}
                    session={session}
                    onViewLogs={setLogsSession}
                  />
                ))
              )}
            </div>
          </div>
        ))}
      </main>

      {/* Modals */}
      {showSpawn && (
        <SpawnModal
          onClose={() => setShowSpawn(false)}
          onSpawned={fetchSessions}
        />
      )}
      {logsSession && (
        <LogsModal
          session={logsSession}
          onClose={() => setLogsSession(null)}
        />
      )}
    </div>
  );
}
