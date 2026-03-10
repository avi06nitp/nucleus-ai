'use client';

import { useState, useEffect, useRef } from 'react';
import { X } from 'lucide-react';
import { Session } from '../types/session';

interface Props {
  session: Session;
  onClose: () => void;
}

export default function LogsModal({ session, onClose }: Props) {
  const [logs, setLogs] = useState<string>('Loading...');
  const logsEndRef = useRef<HTMLDivElement>(null);

  const fetchLogs = async () => {
    try {
      const res = await fetch(`/api/sessions/${session.id}/logs`);
      if (!res.ok) throw new Error(`Server responded ${res.status}`);
      const text = await res.text();
      setLogs(text || '(no logs yet)');
    } catch (err) {
      setLogs(err instanceof Error ? `Error: ${err.message}` : 'Failed to fetch logs');
    }
  };

  useEffect(() => {
    fetchLogs();
    const interval = setInterval(fetchLogs, 5000);
    return () => clearInterval(interval);
  }, [session.id]);

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div
        className="w-full max-w-2xl rounded-xl flex flex-col"
        style={{ backgroundColor: '#1A2236', maxHeight: '80vh' }}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-700">
          <div>
            <h2 className="text-white font-semibold">Logs — {session.ticketId}</h2>
            <p className="text-gray-400 text-xs mt-0.5">{session.branchName}</p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-white transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-5" style={{ backgroundColor: '#0A0F1E' }}>
          <pre className="font-mono text-xs text-green-400 whitespace-pre-wrap break-words leading-relaxed">
            {logs}
          </pre>
          <div ref={logsEndRef} />
        </div>

        <div className="px-5 py-3 border-t border-gray-700">
          <p className="text-gray-500 text-xs">Auto-refreshes every 5 seconds</p>
        </div>
      </div>
    </div>
  );
}
