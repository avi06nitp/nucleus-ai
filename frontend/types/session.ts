export type SessionStatus = 'PENDING' | 'RUNNING' | 'PR_OPEN' | 'IN_REVIEW' | 'MERGED';

export type AgentType = 'claude' | 'openai';

export interface Session {
  id: string;
  ticketId: string;
  projectName: string;
  branchName: string;
  agentType: AgentType;
  status: SessionStatus;
  spawnedAt: string;
  prUrl?: string;
}
