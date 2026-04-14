# OpenBrain MCP Rollback Procedure

**When to use:** The `send_message` MCP handler returns `None` instead of `{"id": N, "thread_id": N}`.
This procedure rolls back the macmini OpenBrain service to the last-known-good commit.

All commands run **on macmini** unless noted.

---

## 1. Identify the broken commit

```bash
cd ~/projects/openbrain-mcp
git log --oneline -10
```

Find the commit that introduced the `send_message` handler change. The return envelope
bug is in the tool handler — look for commits touching `tools/messaging.py` or similar.

---

## 2. Stop the service

OpenBrain may run under **launchd** (macOS native), **gunicorn** (Python WSGI), or
**Docker**. Identify which is running before stopping:

```bash
# Check launchd
launchctl list | grep -i openbrain

# Check gunicorn
pgrep -la gunicorn | grep openbrain

# Check Docker
docker ps --filter name=openbrain
```

Proceed to the matching section below.

---

## Service Management — launchd (macOS native)

Find the plist label first:

```bash
launchctl list | grep openbrain
```

Unload (replace label with actual value):

```bash
launchctl unload ~/Library/LaunchAgents/com.hastingtx.openbrain-mcp.plist
```

After rolling back code (step 3), reload:

```bash
launchctl load ~/Library/LaunchAgents/com.hastingtx.openbrain-mcp.plist
```

Verify the port is free after unload / before reload:

```bash
lsof -i :3000
```

Log location:

```bash
# Application logs
tail -f ~/Library/Logs/openbrain-mcp.log

# launchd output (stdout/stderr captured by launchd)
launchctl log show --predicate 'senderImagePath contains "openbrain"' --last 1h
```

If the plist file is not in `~/Library/LaunchAgents/`:

```bash
find ~/Library/LaunchAgents /Library/LaunchAgents -name '*openbrain*' 2>/dev/null
```

---

## Service Management — gunicorn (direct process)

If OpenBrain runs as a standalone gunicorn process (not under launchd):

**Stop:**

```bash
# Graceful shutdown — waits for in-flight requests to finish
pkill -TERM -f "gunicorn.*openbrain"

# If still running after 5s, hard kill
pkill -KILL -f "gunicorn.*openbrain"

# Confirm stopped
pgrep -la gunicorn
```

**After rolling back code (step 3), restart:**

Find the original startup command in the process manager or a `start.sh` script:

```bash
ls ~/projects/openbrain-mcp/{start,run,server}.sh 2>/dev/null
cat ~/projects/openbrain-mcp/Procfile 2>/dev/null
```

Typical restart (adjust workers / bind as needed):

```bash
cd ~/projects/openbrain-mcp
source venv/bin/activate       # if using a virtualenv
gunicorn -w 2 -b 0.0.0.0:3000 app:app --daemon --log-file ~/Library/Logs/openbrain-gunicorn.log
```

Verify it bound to port 3000:

```bash
lsof -i :3000
```

**gunicorn reload without downtime** (if the rollback is to a change gunicorn can
hot-reload — e.g. a Python file change with no dependency changes):

```bash
pkill -HUP -f "gunicorn.*openbrain"
```

---

## Service Management — Docker

If OpenBrain runs in a Docker container:

**Identify the container:**

```bash
docker ps --filter name=openbrain --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

**Stop:**

```bash
docker stop openbrain-mcp      # replace with actual container name/id
```

**Roll back the image:**

```bash
# Option A — rebuild from rolled-back source (after step 3):
cd ~/projects/openbrain-mcp
docker build -t openbrain-mcp:rollback .

# Option B — use a previously tagged image (fastest, no build needed):
docker images openbrain-mcp    # see available tags
# Then use the tag in the run command below
```

**Start with rolled-back image:**

```bash
# Option A (rebuilt from source):
docker run -d \
  --name openbrain-mcp \
  -p 3000:3000 \
  --env-file ~/projects/openbrain-mcp/.env \
  openbrain-mcp:rollback

# Option B (prior tag, e.g. :stable or :20260410):
docker run -d \
  --name openbrain-mcp \
  -p 3000:3000 \
  --env-file ~/projects/openbrain-mcp/.env \
  openbrain-mcp:stable
```

**View logs:**

```bash
docker logs -f openbrain-mcp
```

**If using docker-compose:**

```bash
cd ~/projects/openbrain-mcp
docker compose down
# Edit docker-compose.yml to pin image tag, or rebuild from rolled-back source
docker compose up -d
docker compose logs -f
```

---

## 3. Roll back the code

### Option A — Revert (preferred, keeps history clean)

```bash
cd ~/projects/openbrain-mcp
git revert HEAD --no-edit
```

This creates a new commit that undoes the last change. Safe if the repo is shared.

### Option B — Checkout known-good commit (faster, detaches HEAD)

```bash
cd ~/projects/openbrain-mcp
git checkout <good-commit-sha>
```

Replace `<good-commit-sha>` with the SHA from step 1 that predates the breakage.

### Option C — Stash uncommitted changes and reset

```bash
cd ~/projects/openbrain-mcp
git stash
git reset --hard <good-commit-sha>
```

---

## 4. Restart the service

Return to the matching service management section above and follow the restart steps.

Wait ~5 seconds for the process to initialize, then verify:

```bash
curl -s http://localhost:3000/health
```

---

## 5. Verify send_message is fixed

```bash
BRAIN_KEY=$(grep OPENBRAIN_KEY ~/projects/messenger/.env | cut -d= -f2)

curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "x-brain-key: $BRAIN_KEY" \
  -d '{
    "tool": "send_message",
    "from_node": "test",
    "to_node": "test",
    "content": "rollback verification"
  }'
```

**Expected response:** `{"id": <N>, "thread_id": <N>}` — both fields present, both integers.

**Broken response:** `None`, `null`, `{}`, or missing `id`/`thread_id` keys.

If broken: repeat from step 1, targeting an earlier commit.

---

## 6. Clean up the test message (optional)

```bash
MSG_ID=$(curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "x-brain-key: $BRAIN_KEY" \
  -d '{"tool":"send_message","from_node":"test","to_node":"test","content":"cleanup-check"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "x-brain-key: $BRAIN_KEY" \
  -d "{\"tool\": \"mark_archived\", \"message_id\": $MSG_ID}"
```

---

## 7. Notify linuxserver

Once `send_message` is confirmed fixed, send a mesh message so linuxserver can
proceed with end-to-end testing:

```bash
curl -s -X POST http://192.168.0.225:13007/relay \
  -H "Content-Type: application/json" \
  -d '{"from":"macmini","to":"linuxserver","content":"send_message fix confirmed — end-to-end test unblocked"}'
```

Also update OpenBrain thoughts #701 and #722 to reflect the fix.

---

## Regression test (linuxserver, after macmini confirms fix)

Run the messenger unit tests to validate the migration layer parses the new
response envelope correctly:

```bash
cd ~/projects/messenger
mvn test -pl . -Dtest=OpenBrainStoreTest -q
```

All tests should pass before enabling `storeMessage()` in production.
Remove the `BLOCKED` comment from `OpenBrainStore.java` once confirmed.
