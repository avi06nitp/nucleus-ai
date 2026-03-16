export type SessionStatus = 'PENDING' | 'RUNNING' | 'PR_OPEN' | 'IN_REVIEW' | 'MERGED' | 'FAILED';

export interface Project {
  name: string;
  repo: string;
  path: string;
  defaultBranch: string;
  jiraProjectKey?: string;
  agentType: string;
  runtime: string;
  sessionPrefix?: string;
  createdAt?: string;
}

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
