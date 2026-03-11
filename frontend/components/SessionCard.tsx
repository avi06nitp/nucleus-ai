'use client';

import { useState, useEffect } from 'react';
import { ExternalLink, MessageSquare, FileText, RefreshCw } from 'lucide-react';
import { Session } from '../types/session';

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'bg-yellow-400',
  RUNNING: 'bg-blue-400',
  PR_OPEN: 'bg-purple-400',
  IN_REVIEW: 'bg-orange-400',
  MERGED: 'bg-green-400',
  FAILED: 'bg-red-400',
};

const AGENT_BADGE_COLORS: Record<string, string> = {
  claude: 'bg-orange-500/20 text-orange-300 border border-orange-500/30',
  openai: 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30',
};

function useElapsed(spawnedAt: string): string {
  const [elapsed, setElapsed] = useState('');

  useEffect(() => {
    const update = () => {
      const diff = Date.now() - new Date(spawnedAt).getTime();
      const mins = Math.floor(diff / 60000);
      const hours = Math.floor(mins / 60);
      if (hours > 0) setElapsed(`${hours}h ${mins % 60}m`);
      else if (mins > 0) setElapsed(`${mins}m`);
      else setElapsed('just now');
    };
    update();
    const interval = setInterval(update, 30000);
    return () => clearInterval(interval);
  }, [spawnedAt]);

  return elapsed;
}

interface Props {
  session: Session;
  onViewLogs: (session: Session) => void;
}

export default function SessionCard({ session, onViewLogs }: Props) {
  const [showMessageInput, setShowMessageInput] = useState(false);
  const [message, setMessage] = useState('');
  const [restoring, setRestoring] = useState(false);
  const elapsed = useElapsed(session.spawnedAt);

  const handleRestore = async () => {
    setRestoring(true);
    try {
      await fetch(`/api/sessions/${session.id}/restore`, { method: 'POST' });
    } catch (err) {
      console.error('Failed to restore session', err);
    } finally {
      setRestoring(false);
    }
  };

  const handleSendMessage = async () => {
    if (!message.trim()) return;
    try {
      await fetch(`/api/sessions/${session.id}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message }),
      });
      setMessage('');
      setShowMessageInput(false);
    } catch (err) {
      console.error('Failed to send message', err);
    }
  };

  return (
    <div className="rounded-lg p-4 mb-3 flex flex-col gap-2" style={{ backgroundColor: '#1A2236' }}>
      <div className="flex items-start justify-between">
        <div>
          <span className="font-bold text-white text-sm">{session.ticketId}</span>
          <p className="text-gray-400 text-xs mt-0.5">{session.branchName}</p>
        </div>
        <div className="flex items-center gap-1.5">
          <span
            className={`w-2 h-2 rounded-full ${STATUS_COLORS[session.status] ?? 'bg-gray-400'}`}
          />
        </div>
      </div>

      <div className="flex items-center justify-between">
        <span
          className={`text-xs px-2 py-0.5 rounded-full font-medium ${AGENT_BADGE_COLORS[session.agentType] ?? ''}`}
        >
          {session.agentType === 'claude' ? 'Claude' : 'OpenAI'}
        </span>
        <span className="text-gray-500 text-xs">{elapsed}</span>
      </div>

      {session.prUrl && (
        <a
          href={session.prUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-1 text-blue-400 text-xs hover:text-blue-300 transition-colors"
        >
          <ExternalLink size={12} />
          View PR
        </a>
      )}

      {showMessageInput && (
        <div className="flex gap-2 mt-1">
          <input
            type="text"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSendMessage()}
            placeholder="Type a message..."
            className="flex-1 text-xs px-2 py-1 rounded bg-[#0A0F1E] border border-gray-700 text-white placeholder-gray-600 focus:outline-none focus:border-blue-500"
          />
          <button
            onClick={handleSendMessage}
            className="text-xs px-2 py-1 rounded bg-blue-600 hover:bg-blue-700 text-white transition-colors"
          >
            Send
          </button>
        </div>
      )}

      <div className="flex gap-2 mt-1 flex-wrap">
        {session.status === 'FAILED' ? (
          <button
            onClick={handleRestore}
            disabled={restoring}
            className="flex items-center gap-1 text-xs text-red-400 hover:text-red-300 transition-colors disabled:opacity-50"
          >
            <RefreshCw size={12} className={restoring ? 'animate-spin' : ''} />
            {restoring ? 'Restoring…' : 'Restore'}
          </button>
        ) : (
          <button
            onClick={() => setShowMessageInput((v) => !v)}
            className="flex items-center gap-1 text-xs text-gray-400 hover:text-white transition-colors"
          >
            <MessageSquare size={12} />
            Send Message
          </button>
        )}
        <button
          onClick={() => onViewLogs(session)}
          className="flex items-center gap-1 text-xs text-gray-400 hover:text-white transition-colors"
        >
          <FileText size={12} />
          View Logs
        </button>
      </div>
    </div>
  );
}
