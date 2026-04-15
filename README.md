# Messenger

Agent-to-agent messaging daemon for a distributed multi-agent mesh network. Stores messages in OpenBrain (PostgreSQL), delivers wake-up pings over HTTP, and processes incoming messages via pluggable AI processors.

## Architecture

**Claim-check pattern**: full message content lives in OpenBrain permanently. The daemon only carries lightweight wake-up pings between nodes. A failed ping is not a delivery failure — the background poller catches up every 10 minutes.

```
  linuxserver ──────── macmini ──────── macbook-air
  (port 13007)        (port 13007)      (port 13007)
       │                  │                  │
       └──── OpenBrain (PostgreSQL, macmini:3000) ────┘
                     messages table
```

### Nodes

| Node | IP | OS | Role |
|------|----|----|------|
| linuxserver | 192.168.0.225 | Ubuntu | Primary dev server, PostgreSQL, Nginx, Docker |
| macmini | 192.168.0.226 | macOS | OpenBrain host, Ollama, scheduler, agent-mesh hub |
| macbook-air | 192.168.0.62 | macOS | Development workstation |
| gemma-small | 192.168.0.226:13008 | macOS | Ollama Gemma4 agent (text-only) |
| gemma-large | 192.168.0.226:13009 | macOS | Ollama Gemma4 agent (text-only) |

### Node Personalities

Each node has a distinct voice to prevent echo-chamber conversations:

- **linuxserver** — Conservative engineer. Questions assumptions, checks edge cases, prefers proven approaches.
- **macmini** — Pragmatic risk-taker. Bias toward action, "ship it and iterate."
- **macbook-air** — Contrarian / tenth man. Challenges consensus, plays devil's advocate.

Personalities are defaults per node name, overridable via `personality` in config.

## Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/relay` | POST | Store message in OpenBrain, send wake-up to target peer |
| `/broadcast` | POST | Store broadcast, wake all peers |
| `/wake` | POST | Receive wake-up from a peer, trigger immediate poll |
| `/health` | GET | Node status JSON |
| `/ping` | GET | Liveness probe |

## Message Processors

Pluggable `MessageProcessor` interface. Auto-selected with priority fallback:

1. **ClaudeCliProcessor** — `claude -p` CLI with `--resume` for session continuity. Uses Max subscription (free). Full tool access.
2. **GemmaProcessor** — Local Ollama gemma4:e4b. Free, works offline. Text-only responses.
3. **logging()** — Safe no-op fallback.

### Session Management

Claude CLI sessions have a configurable TTL (default 240 minutes). Before eviction:
1. Session is summarized via Claude
2. Summary stored in OpenBrain as a `session-context` thought
3. New sessions for the same thread load prior context from OpenBrain

## Configuration

Bootstrap config (`config.json`, gitignored):
```json
{
  "node_name": "linuxserver",
  "openbrain_url": "http://192.168.0.226:3000"
}
```

Full config stored in OpenBrain (fetched at startup, cached locally):
```json
{
  "listen_port": 13007,
  "processor": null,
  "claude_model": "claude-sonnet-4-6",
  "claude_timeout_minutes": 12,
  "session_ttl_minutes": 240,
  "reaper_interval_minutes": 5,
  "personality": null,
  "peers": [
    {"name": "macmini", "url": "http://192.168.0.226:13007"},
    {"name": "macbook-air", "url": "http://192.168.0.62:13007"}
  ]
}
```

API key via `OPENBRAIN_KEY` env var or `openbrain_key` in config.json. Never hardcoded.

## Building

```bash
mvn package            # Build JAR + run tests
mvn package -DskipTests  # Build only
mvn test               # Run tests only (159 tests)
```

Produces `target/messenger.jar` (~58K). Zero external runtime dependencies — JDK 21 only.

## Running

```bash
# Set the API key
export OPENBRAIN_KEY=your-key-here
# Or use .env file (gitignored)
echo "OPENBRAIN_KEY=your-key" > .env && source .env

# Start
java -jar target/messenger.jar config.json

# Or with nohup
nohup java -jar target/messenger.jar config.json > ~/logs/messenger.log 2>&1 &
```

### systemd (linuxserver)

```bash
sudo cp messenger.service /etc/systemd/system/
sudo systemctl enable --now messenger
```

### launchd (macOS)

```bash
# Edit com.hastingtx.messenger.plist — set OPENBRAIN_KEY
cp com.hastingtx.messenger.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.hastingtx.messenger.plist
```

### MCP Server (Claude Code integration)

The messenger includes an MCP server so Claude Code can send messages, broadcast, and check health directly via tool calls.

#### Install

```bash
cd ~/projects/messenger
python3 -m venv .mcp-venv
.mcp-venv/bin/pip install mcp httpx
```

#### Register with Claude Code

Add to `~/.claude/mcp.json`:
```json
{
  "messenger": {
    "command": "/path/to/projects/messenger/.mcp-venv/bin/python",
    "args": ["/path/to/projects/messenger/mcp_server.py"]
  }
}
```

Or via CLI:
```bash
claude mcp add messenger /path/to/projects/messenger/.mcp-venv/bin/python -- /path/to/projects/messenger/mcp_server.py
```

#### MCP Tools

| Tool | Description |
|------|-------------|
| `msg_relay` | Send a message to a specific node (stores in OpenBrain, wakes target) |
| `msg_broadcast` | Broadcast to all nodes |
| `msg_wake` | Send wake-up ping to trigger immediate inbox poll |
| `msg_health` | Node status: uptime, peers, last poll, messages processed |
| `msg_ping` | Quick liveness check |
| `msg_ask_user` | Ask Richard a question via Telegram, wait for reply (up to 15 min) |

The MCP server talks to the local messenger daemon on port 13007. The daemon must be running.

### Multi-instance (same machine)

Use separate config files. Lock files are derived from config filename:
```bash
java -jar target/messenger.jar config-gemma-small.json  # lock: ~/.messenger-gemma-small.lock
java -jar target/messenger.jar config.json              # lock: ~/.messenger.lock
```

## CLI Tools

Two companion CLI tools on linuxserver (`~/bin/`):

### msg — Mesh Messenger

```bash
msg send macmini "check disk usage"
msg inbox
msg thread 462
msg history gemma-small 5
msg ask --thread 462 "should I kill the process?"
msg notify --thread 462 "task complete"
msg broadcast "all nodes: report status"
msg                    # interactive mode
```

`ask` routes through Telegram (5-min timeout, waits for Richard's reply).
`notify` sends via Telegram Bot API (fire and forget, no timeout).
`send richard` aliases to `notify`.

### brain — OpenBrain Explorer

```bash
brain recent 10
brain search "port conflict"
brain project messenger 5
brain projects
brain stats
brain digest
brain capture "fixed the thing" --project infra --tags fix
brain                  # interactive mode
```

## Concurrency

- **Coalescing polls**: N concurrent wake-ups collapse into at most 2 sequential poll runs.
- **Per-thread serialization**: Messages with the same `thread_id` are processed sequentially. Different threads run concurrently.
- **Per-sender rate limiting**: 3 messages per 10 minutes per sender. Excess messages are archived without processing.
- **Single-instance lock**: OS-level file lock prevents duplicate daemons per config.

## Tests

159 tests covering:
- `JsonTest` — Recursive-descent parser, escape/unescape, null safety, error handling
- `OpenBrainStoreTest` — Messages-table API parsing, store response, inbox formats
- `MessagePollerTest` — Request parsing, missing fields, special characters
- `BroadcastHandlerTest` — Content validation, response format
- `PeerConfigTest` — Config parsing, defaults, overrides
- `SessionManagementTest` — Session entry lifecycle, summary formats, context loading
- `SystemPromptTest` — Personality per node, thread context, capability awareness
- `RateLimitTest` — Sliding window, expiration, sender independence
