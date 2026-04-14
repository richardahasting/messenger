# Mesh Relay — Architecture and Operational Contracts

This document is the authoritative reference for what the messenger daemon
**guarantees**, what it **does not guarantee**, and what **breaks permanently**
when specific failure modes are hit. Read the Failure Contracts section before
operating, debugging, or extending this system.

---

## 1. System Overview

Each node in the mesh runs a single-process Java daemon (JDK 21 virtual threads,
no framework). The daemon exposes four HTTP endpoints and a background polling
loop. All persistent state lives in OpenBrain's PostgreSQL `messages` table on
macmini (192.168.0.226:3000). Nodes themselves are stateless except for
in-process rate-limit windows and per-thread locks.

```
Sender                  OpenBrain (PostgreSQL)       Recipient
------                  ----------------------       ---------
POST /relay  ──store──▶  messages row (pending)
             ◀──id────
POST /wake   ─────────────────────────────────────▶  /wake HTTP
                                                      MessagePoller.wake()
                                                       ▼
                         get_inbox (pending)  ◀──────  poll()
                         markDelivered()               │
                         markArchived()  ◀─────────────│── process()
```

**Nodes:**

| Node | Address | Role |
|------|---------|------|
| linuxserver | 192.168.0.225:13007 | Dev server, Java daemon |
| macmini | 192.168.0.226:13007 | OpenBrain host, scheduler hub |
| macbook-air | 192.168.0.62:13007 | Dev workstation |
| gemma-small | 192.168.0.226:13008 | Ollama Gemma4 (small) |
| gemma-large | 192.168.0.62:13009 | Ollama Gemma4 (large) |

---

## 2. Message State Machine

Messages in the `messages` table transition through exactly three states:

```
  [pending] ──markDelivered()──▶ [delivered] ──markArchived()──▶ [archived]
      │                                │
      │                                │  ← NO REVERSE TRANSITION EXISTS
      ▼                                ▼
  get_inbox returns this          get_inbox does NOT return this
  message on every poll           message on any subsequent poll
```

**The missing transition is an architectural constraint, not a bug.**
The OpenBrain messages API has no `reset_to_pending` or `mark_undelivered`
operation. Once `markDelivered()` succeeds, the message is permanently
claimed by this node. See §4.3 for consequences.

---

## 3. Core Contracts

### 3.1 Durability Contract

**Claim:** A message is durable after `storeMessage()` returns successfully.

**Mechanism:** `OpenBrainStore.storeMessage()` makes a synchronous POST to
`/mcp` with `tool=send_message`. The call blocks until OpenBrain acknowledges
the write. The commit is to PostgreSQL on macmini — it survives restarts of
any mesh node, including the sender.

**Boundary:** Durability is only guaranteed when `storeMessage()` returns a
valid `StoreResult{messageId, threadId}`. Any exception from that call means
**the message was not stored**. `RelayHandler` and `BroadcastHandler` both
treat this as a hard failure and return HTTP 503 to the caller — do not
silently swallow it.

**Known blocker:** `send_message` on macmini currently returns `None` instead
of `{id, thread_id}` (thought #701). Until resolved, `storeMessage()` throws
on every call. Outbound relay is non-functional while this bug is open.

### 3.2 Delivery Contract

**Claim:** Every stored message addressed to a live node will eventually be
processed, even if the wake-up ping is never received.

**Mechanism — fast path (wake-up ping):**
1. After storing, sender calls `sendWakeup()` to `POST /wake` on the target node.
2. `/wake` calls `MessagePoller.wake()`, which spawns a virtual thread running
   `triggerPoll()` immediately.
3. `triggerPoll()` calls `get_inbox` and processes any pending messages.
4. `sendWakeup()` retries up to 3 times with backoffs of 0/1/2 seconds.
5. A failed wake-up (all retries exhausted) is **not a delivery failure**. The
   sender returns HTTP 200 with `wakeup_sent: false`. The message is safe in
   OpenBrain.

**Mechanism — slow path (scheduled poll):**
- `MessagePoller.run()` calls `triggerPoll()` every 10 minutes regardless of
  wake-up state.
- On daemon startup, `triggerPoll()` fires immediately to catch any messages
  that arrived while the node was down.

**Maximum undetected latency:** If the wake-up ping is never delivered and the
slow path is the only recovery mechanism, message delivery latency is bounded
by the poll interval (10 minutes) plus processing time.

**Broadcast delivery:** `POST /broadcast` stores a single message with
`to_node=all`, then fans out wake-up pings to all peers concurrently using
virtual threads. Peers that miss their wake-up will catch up on their next
scheduled poll. Partial wake-up failure is reported in the response but is not
a delivery failure for the stored message.

### 3.3 Processing Contract (Partial Guarantee)

**Claim:** A message whose `markDelivered()` call succeeded will be processed
**at most once**. A message in `pending` state at the time of a poll will be
processed **at least once** (eventually).

**Together:** The system provides **at-most-once processing after claim, and
at-least-once delivery before claim.** There is no exactly-once guarantee.

**The gap:** The window between `markDelivered()` succeeding and
`markArchived()` succeeding is unprotected. A crash, OOM, or processor
exception in that window leaves the message permanently in `delivered` state.
It will **not** be retried. See §4.3.

---

## 4. Failure Contracts

### 4.1 OpenBrain Unreachable at Store Time

**Scenario:** `storeMessage()` throws (network error, HTTP non-200, or the
known `None` response bug).

**Effect:** The HTTP handler returns 503. No message is stored. No wake-up is
sent. The caller must retry from scratch.

**Recovery:** None needed — the system is consistent. No orphaned state exists.

### 4.2 Wake-up Ping Failure (All Retries Exhausted)

**Scenario:** `sendWakeup()` returns `false` after 3 attempts.

**Effect:** Sender returns HTTP 200 with `wakeup_sent: false`. The message is
stored and durable. The recipient's daemon is either down or unreachable.

**Recovery:** Automatic. The recipient's next scheduled poll (within 10 minutes
of coming back online) will pick up the message via `get_inbox`. No manual
intervention required.

### 4.3 Processing Failure After markDelivered() — THE MANUAL RECOVERY TRAP

**Scenario:** `markDelivered()` succeeds (message transitions `pending →
delivered`), then `processor.process()` throws an exception.

**Effect (critical):**
- The message is permanently in `delivered` state.
- `get_inbox` will **never** return this message again — it is invisible to the
  normal poll path.
- `markArchived()` is **not called** — the message is not archived.
- The sender receives no notification. The conversation thread appears to stall
  silently from the sender's perspective.
- The log will contain: `message stays 'delivered', manual recovery required`
  with the `messageId` and `thread_id`.

**Why this cannot be auto-recovered:**
The OpenBrain messages API exposes no `reset_to_pending` or
`mark_undelivered` operation. There is no compensating transaction available
to the daemon.

**Detection:**
```bash
# On the recipient node's log:
grep "manual recovery required" /var/log/messenger/messenger.log
# Or look for delivered-but-not-archived messages in OpenBrain:
# SELECT id, from_node, to_node, status, created_at
# FROM messages WHERE status = 'delivered' AND updated_at < NOW() - INTERVAL '30 minutes';
```

**Recovery options (in order of preference):**

1. **Archive the message** (discard it — processing will not be retried):
   ```
   POST http://192.168.0.226:3000/mcp
   {"tool": "mark_archived", "message_id": <id>}
   ```
   Use this when the original message content is retrievable from the thread
   history and can be resent if needed.

2. **Re-send the original message** from the sender using `POST /relay` with
   the same content. The new message gets a new `messageId` and re-enters the
   pipeline from `pending`.

3. **mark_pending (not yet available):** If macmini adds `mark_pending` to the
   OpenBrain MCP, `OpenBrainStore.resetDelivered()` will implement it and
   MessagePoller will automatically retry instead of dead-lettering. Feature
   request is tracked in OpenBrain. When available, this becomes option 1.

For emergency state reset via direct database access, see **§10 Emergency
Operational Procedures**. Direct DML is not a contract mechanism — it bypasses
the state machine and must only be used by operators with full context.

**Consumers of this system** (including gemma-large and future processors)
must be designed with the understanding that they may receive a given message
exactly once and processing failures are not automatically retried.

### 4.4 Thread Lock Timeout

**Scenario:** A message is being processed in thread T. A second message
arrives on thread T. The second message waits for the per-thread lock for
more than 12 minutes without acquiring it.

**Effect:** The waiting message is **skipped for this poll cycle**. Its state
remains `pending`. It will be retried on the next poll (within 10 minutes).

**Not a stuck-message condition** — the message will be retried automatically.
However, if the first message is hung indefinitely (e.g., a Claude CLI call
that never returns), subsequent messages on that thread will accumulate 10-
minute delays per cycle until the hung processor either completes or the
daemon is restarted.

**Recovery:** Restart the daemon. The hung virtual thread is killed. The
claimed (delivered) message from the hung call enters §4.3 territory and
requires manual recovery. The waiting messages are still `pending` and will
be processed normally on the next poll.

### 4.5 Rate Limit Triggered

**Scenario:** A sender sends more than 3 messages within a 10-minute sliding
window.

**Effect:** The 4th+ message is **archived without processing**. This is
permanent — the message will not be retried, as archiving removes it from the
`get_inbox` results. The log will contain:
```
Rate limited sender=<node> (>3 msgs/10m) — archiving thread_id=<id> without processing
```

**Important:** Rate limiting silently discards messages. The sender receives
no error response — the message appeared stored and the wake-up was sent.
There is no feedback mechanism to the sender.

**Recovery:**
- Rate-limited messages cannot be recovered from the recipient side.
- The sender must resend after the 10-minute window expires (sliding window,
  not fixed).
- Consider whether the rate is legitimate before tuning `RATE_LIMIT_MAX` and
  `RATE_LIMIT_WINDOW` in `MessagePoller`.

### 4.6 OpenBrain Unreachable at Poll Time

**Scenario:** `pollPendingMessages()` fails (network error or non-200 response).

**Effect:** `poll()` returns without processing any messages. No messages are
marked delivered or archived. All messages remain in `pending` state.

**Recovery:** Automatic. The next scheduled poll (10 minutes) or the next
wake-up ping will retry. No messages are lost or stuck.

---

## 5. Concurrency Model

### 5.1 Poll Coalescing

`triggerPoll()` uses two `AtomicBoolean` flags (`pollInFlight`, `pollPending`)
to ensure:
- At most one poll runs at a time.
- A wake-up that arrives during an in-flight poll is not lost.
- N concurrent wake-ups collapse into at most 2 sequential poll runs.

This is a **bounded work guarantee**: a burst of N messages causes exactly 2
poll cycles (the in-flight one, plus one follow-up), not N cycles. The
consequence is that messages arriving in the second half of the burst may
wait until the follow-up poll rather than being processed in the first.

### 5.2 Per-Thread Serialization

Messages with the same `thread_id` are processed one at a time, in order of
delivery from `get_inbox`. This guarantees that a processor handling message
B in a conversation will always see the effects of message A fully committed
to OpenBrain first.

Messages with different `thread_ids` are processed concurrently with no
ordering constraint.

Locks are `ReentrantLock` instances created on demand, stored in a
`ConcurrentHashMap<Long, ReentrantLock>`. They are removed from the map
after the lock is released (if no other thread is waiting), preventing
unbounded map growth on long-running daemons with many unique threads.

### 5.3 Virtual Threads

All request handlers and the polling loop run on JDK 21 virtual threads.
Blocking I/O (OpenBrain HTTP calls, Claude CLI subprocess, wake-up pings)
does not block OS threads. The `HttpClient` is shared across all handlers
and is thread-safe.

---

## 6. HTTP Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /relay` | POST | Store message in OpenBrain, send wake-up to target peer |
| `POST /broadcast` | POST | Store message, fan out wake-ups to all peers concurrently |
| `POST /wake` | POST | Wake-up ping receiver — triggers immediate poll |
| `GET /health` | GET | Liveness check — returns node name, last poll time, processed count |

### /relay Request
```json
{"to": "macmini", "from": "linuxserver", "content": "message text"}
```

### /relay Response (always HTTP 200 if message stored)
```json
{"thread_id": 462, "message_id": 91, "stored": true, "wakeup_sent": true}
```
`wakeup_sent: false` is not an error — the message is stored and will be
delivered on the recipient's next poll.

### /broadcast Request
```json
{"from": "linuxserver", "content": "broadcast text"}
```

### /broadcast Response
```json
{"thread_id": 463, "delivered": ["macmini"], "failed": ["macbook-air"]}
```

---

## 7. Configuration

Configuration is loaded from a JSON file at daemon startup. Path is passed
as the first command-line argument (default: `config.json`). Per-instance
lock files prevent multiple daemons binding the same port.

Key fields in `PeerConfig`:
- `nodeName` — identity of this node
- `port` — HTTP port to bind
- `openBrainUrl` — base URL for OpenBrain MCP API (e.g. `http://192.168.0.226:3000`)
- `openBrainKey` — `x-brain-key` header value
- `peers` — map of `{nodeName: baseUrl}` for all known peers

---

## 8. Known Issues and Constraints

| Issue | Component | Status |
|-------|-----------|--------|
| `send_message` returns None on macmini — outbound relay blocked | OpenBrain server | Open (thought #701) |
| No `reset_to_pending` API — post-claim failures require manual recovery | OpenBrain API | By design, document and handle |
| Rate-limit discard is silent to sender | MessagePoller | By design, undocumented to sender |
| Broadcast wake-up has no retry logic (unlike point-to-point relay) | BroadcastHandler | Known gap |
| Per-sender rate limit resets on daemon restart (in-memory only) | MessagePoller | Known limitation |

---

## 9. Message Processor Interface

`MessageProcessor` is a `@FunctionalInterface`. The daemon wires in the
implementation at startup. Current implementations:

| Implementation | Behavior |
|----------------|----------|
| `MessageProcessor.logging()` | Logs content to stdout, no response sent |
| `GemmaProcessor` | Forwards to local Ollama Gemma4 |
| `ClaudeCliProcessor` | Invokes `claude -p` as a subprocess |

**Contract for implementors:**
- Throw any `Exception` to signal processing failure. The poller will log the
  failure and leave the message in `delivered` state (§4.3 applies).
- Do not swallow exceptions silently if the message was not processed — the
  caller cannot distinguish a silent failure from success.
- `process()` must not assume it will be called again for the same message.
  There is no retry — design for at-most-once execution.

---

## 10. Emergency Operational Procedures

These procedures bypass the normal state machine and must only be executed by
an operator who has verified the root cause. They are not contract mechanisms
and should not be referenced in code.

### EOP-1: Reset a stuck "delivered" message to "pending"

**When:** A message is stuck in `delivered` state (processor crashed after
`markDelivered()`, daemon was restarted, root cause is now resolved) and you
want it retried rather than discarded.

**Prerequisites:** Confirm the processor failure was transient and is resolved.
Check the dead-letter thought in OpenBrain (tagged `dead-letter`,
`thread:<id>`) for the failure reason.

**Procedure (requires macmini PostgreSQL access):**
```sql
-- Identify the stuck message first:
SELECT id, from_node, to_node, status, created_at, updated_at
FROM messages
WHERE status = 'delivered'
  AND updated_at < NOW() - INTERVAL '30 minutes';

-- Reset to pending (triggers next poll to pick it up):
UPDATE messages SET status = 'pending' WHERE id = <message_id>;
```

**After execution:** The message will be picked up on the next poll cycle
(within 10 minutes) or immediately if you send a wake-up ping to the recipient
node (`POST http://<node>:13007/wake {}`).

**Note:** If the processor fails again, the message will dead-letter a second
time. Fix the root cause before resetting.
