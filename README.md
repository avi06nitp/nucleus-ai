# Nucleus AI

## CLI — `nucleus`

A lightweight terminal tool for driving the Agent Orchestrator without opening the dashboard.

### Install

```bash
# Option 1: add cli/ to PATH (recommended)
export PATH="$PATH:$(pwd)/cli"

# Option 2: symlink into a directory already on PATH
ln -sf "$(pwd)/cli/nucleus" /usr/local/bin/nucleus
```

Requires **bash** and **curl** (standard on macOS/Linux).

### Quick-start

```bash
# Start from an existing agent-orchestrator.yaml
nucleus start

# Clone a repo, auto-detect settings, and start
nucleus start https://github.com/owner/repo

# Generate agent-orchestrator.yaml in the current directory
nucleus init
nucleus init --auto   # non-interactive

# Spawn an agent for a ticket
nucleus spawn my-app JIRA-421

# Watch all sessions
nucleus status

# Send a manual instruction to a running agent
nucleus send <session-id> "please revert the last commit"

# Kill or restore a session
nucleus session kill    <session-id>
nucleus session restore <session-id>

# Open the web dashboard
nucleus dashboard

# Tail logs
nucleus logs <session-id>
```

### Environment variables

| Variable      | Default                  | Description         |
|---------------|--------------------------|---------------------|
| `NUCLEUS_URL` | `http://localhost:8080`  | Backend base URL    |

### Config file

`~/.nucleus/config.yml` — created by `nucleus init` (interactive mode).

```yaml
# nucleus configuration
backend_url: http://localhost:8080
```

