# Messenger Protocol v1.2 — Conversation Completion + Progress Visibility (DRAFT)

Status: **DRAFT for review.** Supersedes the content-pattern guards in v1.1.5/v1.1.6
and resolves the deferred work in issues #10 (`in_reply_to`) and #11
(architectural skip-on-reply). Adopts **interpretation B** of the design
discussion: every message carries a payload — `kind=reply` is "response
content + terminator flag," not a separate ACK control frame.

## Goals

1. Replace content-pattern ack detection (`ACK_PATTERN` in v1.1.5/v1.1.6) with
   a **protocol-level** terminator that does not depend on what Claude happens
   to emit.
2. Make retransmits safe — receiver dedupe handles duplicate inbound. There
   is **no automatic retry** in this protocol; see "Failure handling" below.
3. Provide **progress visibility** for long tasks so the requester can
   distinguish *slow* from *wedged* without waiting for an absolute deadline.
4. Keep the wire shape compatible with the existing thread model. No new
   transport, no new endpoint.

## Wire format

`/relay` POST body grows the following optional fields:

| Field                     | Type                                  | Default                                                  | Purpose                                              |
|---------------------------|---------------------------------------|----------------------------------------------------------|------------------------------------------------------|
| `seq_id`                  | string (sender-assigned)              | unset → daemon stamps `<from>:<thread>:<counter>`        | Idempotency key for the receiver's dedup cache       |
| `ack_policy`              | enum `REQ_ACK \| NO_ACK`              | `REQ_ACK` for `kind=action`; `NO_ACK` for `kind=info`    | Does the sender expect a response?                   |
| `reply_policy`            | enum `REPLY \| NO_REPLY`              | `REPLY` for `kind=action`/`ping`; `NO_REPLY` otherwise   | May the receiver auto-respond to *this message*?     |
| `respond_by`              | RFC-3339 timestamp                    | unset → no absolute deadline                              | Sender's hard cutoff. Caller picks per-task ("32s for lake level, 1h for server reconfig"). |
| `update_interval_seconds` | int                                   | unset → no progress beats                                 | Sender's request: "while working, beat me every N seconds." Daemon clamps to **[30, 120]**. |

`in_reply_to` (issue #10) is mandatory on `kind=reply`, `kind=ack`, and
`kind=progress`; it points at the `seq_id` of the message being responded to.

## kind enum (extended)

| kind       | Existing? | Processor invoked? | Default `ack_policy` | Default `reply_policy` | Counts toward MAX_TURNS? | Notes                                                                 |
|------------|-----------|--------------------|----------------------|------------------------|--------------------------|-----------------------------------------------------------------------|
| `action`   | yes       | **yes**            | `REQ_ACK`            | `REPLY`                | yes                      | The only kind that runs Claude. Carries a real task payload.         |
| `reply`    | **NEW**   | no                 | `NO_ACK`             | `NO_REPLY`             | yes                      | Response payload to a prior `REQ_ACK`. Poller archives + delivers.   |
| `ack`      | yes (narrowed) | no            | `NO_ACK`             | `NO_REPLY`             | yes                      | Empty-payload delivery confirmation when no response is being produced (e.g. processor returned `[no output]`). |
| `progress` | **NEW**   | no                 | `NO_ACK`             | `NO_REPLY`             | **no**                   | Periodic liveness/status beat from a working processor. See "Progress mechanism" below. |
| `info`     | yes       | no                 | `NO_ACK`             | `NO_REPLY`             | yes                      | Broadcasts, one-way notifications.                                   |
| `ping`     | **NEW**   | no (daemon-handled)| `REQ_ACK`            | `REPLY`                | yes                      | Daemon auto-responds with `kind=reply payload="pong" reply_policy=NO_REPLY`. Never reaches Claude. |

## Sender behavior

```
1. seq_id = thread_local_counter++
2. POST /relay with kind, seq_id, ack_policy, reply_policy, [respond_by],
                    [update_interval_seconds]
3. (no blocking wait, no retry — return after POST returns 200)
4. on receiving a thread message with reply_policy == NO_REPLY:
       this MESSAGE is terminal — receiver will not auto-respond.
       The thread itself stays open; the application may start a new turn
       on the same thread by sending a fresh REQ_ACK.
```

There is **no `wait_for_ack`**, **no automatic retry**, and **no blocking
primitive**. Replies arrive asynchronously through the existing poll loop;
the application reads them at its own cadence (Telegram bot inspects the
thread when a user looks; Lenny inspects when its own turn comes).

## Failure handling — caller polls

Replaces the v1.1 retry-policy section. Callers detect failure modes by
inspecting the thread directly:

| Caller observes                                                   | Meaning                                                                 |
|-------------------------------------------------------------------|-------------------------------------------------------------------------|
| `kind=reply` with matching `in_reply_to`                          | Done. Read payload.                                                      |
| `kind=progress` arrived within `update_interval_seconds × 3`      | Still working. Keep waiting.                                            |
| `now > respond_by` and no reply                                   | Hard deadline missed. Give up.                                           |
| **No `kind=progress` arrived for `update_interval_seconds × 3`**  | **Wedged.** Give up early — don't wait until `respond_by`.              |

**Grace multiplier = 3×.** Rationale: a 30-second update that turns into a
1-minute update is normal scheduling jitter; a 30-second update that turns
into a 5-minute update is clearly wedged. 3× draws that line cleanly.

The caller picks the timeout for each task individually — 32 seconds for a
lake-level lookup, 1 hour for a server reconfig. The protocol does not
prescribe; it provides the signals.

## Receiver behavior

```
1. extract (from, thread_id, seq_id, kind, ack_policy, reply_policy,
            respond_by, update_interval_seconds)
2. if (from, thread_id, seq_id) in dedup_cache:
       resend cached_response[key]
       return                                        # NEVER re-process
3. dispatch on kind:
       action:
           clamped = clamp(update_interval_seconds, 30, 120)
           start progress-beat timer if update_interval_seconds was set
           run processor with env MESSENGER_PROGRESS_LOG=<per-thread path>
           on processor exit: cancel timer; rm -f log file
       reply | ack:
           archive; deliver to any waiter blocked on seq_id
       progress:
           archive (transient — auto-pruned on matching reply); update
           caller-side liveness clock
       info:
           archive
       ping:
           emit kind=reply payload="pong" reply_policy=NO_REPLY
4. if ack_policy == REQ_ACK and processor produced output:
       respond with kind=reply
                    in_reply_to=seq_id
                    reply_policy=NO_REPLY
   else if ack_policy == REQ_ACK and processor produced no output:
       respond with kind=ack reply_policy=NO_REPLY
5. cache_response[(from, thread_id, seq_id)] = whatever-we-just-sent
```

## Loop-prevention guarantee

By construction:
- A receiver runs the processor **only** on `kind=action`.
- A receiver auto-responds **only** when `ack_policy=REQ_ACK`.
- An auto-response **always** carries `reply_policy=NO_REPLY`.
- A `reply_policy=NO_REPLY` message **cannot** elicit any auto-response.
- A `kind=progress` message **cannot** elicit any response (it is `NO_REPLY` by definition).

Therefore: **a single Q&A turn terminates in exactly 2 messages (request +
reply), plus 0–N progress beats while the processor is working.** No content
inspection required.

## Multi-turn conversations

`reply_policy=NO_REPLY` is a **per-message** terminator, not a per-thread
one. The thread itself stays open after a NO_REPLY message lands; the
application is free to start a new turn by sending a fresh REQ_ACK on the
same `thread_id`. The receiver's session memory (already keyed off
`thread_id` in messenger today) carries context across turns.

```
A → B: thread=42 seq=1 kind=action REQ_ACK   "what's the lake level?"
B → A: thread=42 seq=2 kind=reply  NO_REPLY in_reply_to=1  "907 ft, 99.8% full"
                       ↑ B's processor will not auto-respond. Loop closed for this turn.
A → B: thread=42 seq=3 kind=action REQ_ACK   "has it changed in 24h?"
                       ↑ A's application starts a new turn — same thread_id,
                         so B's session memory keeps context.
B → A: thread=42 seq=4 kind=reply  NO_REPLY in_reply_to=3  "up 0.3 ft"
```

## Progress mechanism — daemon-driven heartbeat with log tailing

The processor (Claude CLI, Gemma, shell scripts, anything else) does **not**
need to know about the timer. The daemon owns the heartbeat. The processor
writes to a known log file; the daemon tails that file on a fixed schedule
and emits the tail as the progress payload.

### Receiver-side flow

```
On kind=action with update_interval_seconds set:
  logPath = /var/run/messenger/progress/thread-<id>-seq-<seq>.log
  setEnv("MESSENGER_PROGRESS_LOG", logPath) for the processor invocation
  schedule ScheduledFuture every clamp(update_interval_seconds, 30, 120) sec:
      tail = tailFile(logPath, lines=10, maxBytes=4096)
      if tail is empty: tail = "(no log activity)"
      relay kind=progress payload=tail in_reply_to=seq reply_policy=NO_REPLY
  on processor exit (success | failure | timeout):
      cancel ScheduledFuture
      rm -f logPath
On daemon startup:
  rm -f /var/run/messenger/progress/*.log    # clean stale files from prior run
```

### Processor contract

For Bash tool calls the system prompt directs Claude to pipe through `tee
-a $MESSENGER_PROGRESS_LOG`. For non-Bash tool calls (`Read`, `Grep`,
`Edit`, etc.) a `PostToolUse` hook in `~/.claude/settings.json` logs a
one-line summary per tool invocation. Both write to the same file.

### Constraints

- `update_interval_seconds` is **clamped to [30, 120]** by the daemon. Below
  30 floods the log/storage; above 120 defeats the purpose. Sender's
  request is advisory — daemon enforces sanity (TCP-window-scaling pattern).
- `kind=progress` payload capped at **4 KB**. One pathological log line
  shouldn't blow up wire size or storage.
- `kind=progress` is **excluded from `MAX_TURNS`** — a 1-hour task at 30s
  beats produces ~120 progress messages, which would otherwise trip the
  ceiling.
- `kind=progress` rows in OpenBrain are **auto-pruned** when the matching
  `kind=reply` lands. Their job ends when the task completes.
- Daemon performs a **secrets sweep** on each tail before sending: lines
  matching common credential patterns (`.*_TOKEN=`, `.*_KEY=`,
  `Authorization:`, `password=`) are redacted to `<redacted>`. Best-effort,
  not a substitute for the prompt's "don't log secrets" rule, but a defense
  in depth.

## SystemPrompt addition

Lands in `SystemPrompt.java` alongside the existing "Responding to
Acknowledgments" block (around line 105–110).

> **DRAFT — subject to refinement during integration testing.** Prompt
> engineering is empirical: this is a starting point, not a finished
> product. Tune wording in-place once we observe how Claude actually behaves
> under real load.

```
## Reporting progress on long tasks

For any task that may take more than ~30 seconds — file searches,
multi-step builds, server reconfigs, anything involving several tool
calls — pipe every Bash invocation through `tee -a $MESSENGER_PROGRESS_LOG`.
The requester reads the tail of that file to see that you are still
working and what stage you're on.

  ls -la /etc 2>&1 | tee -a $MESSENGER_PROGRESS_LOG
  mvn test 2>&1   | tee -a $MESSENGER_PROGRESS_LOG

When the task completes — whether it succeeds, fails, or you abandon it —
remove the log file:

  rm -f $MESSENGER_PROGRESS_LOG

Do not log secrets, tokens, or credentials. Do not write progress
narration by hand; let `tee` capture real output. Non-Bash tool calls
(Read, Grep, Edit, etc.) are logged automatically by the harness — you
don't need to echo them yourself.
```

## Hook configuration (`~/.claude/settings.json`)

Lands once on each node that runs a Claude-CLI processor. Activates only
when `$MESSENGER_PROGRESS_LOG` is set, so it's a no-op in normal interactive
sessions.

```jsonc
"hooks": {
    "PostToolUse": [{
        "matcher": "Read|Grep|Glob|Edit|Write|WebFetch|mcp__.*",
        "hooks": [{
            "type": "command",
            "command": "[ -n \"$MESSENGER_PROGRESS_LOG\" ] && jq -r --arg ts \"$(date -Iseconds)\" '\"\\($ts) \\(.tool_name) \\((.tool_input | tostring)[:200])\"' >> \"$MESSENGER_PROGRESS_LOG\" 2>/dev/null || true"
        }]
    }]
}
```

> **Resolved during issue #18 implementation**: Claude Code does not export
> `$CLAUDE_TOOL_NAME` / `$CLAUDE_TOOL_INPUT_SUMMARY` env vars to PostToolUse
> hooks. Hook event data arrives on **stdin as JSON** with fields
> `tool_name`, `tool_input`, `tool_response`, `hook_event_name`,
> `session_id`, `transcript_path`, `cwd`. The hook above pipes stdin through
> `jq` to extract `tool_name` and a 200-char prefix of `tool_input`. The
> `2>/dev/null || true` tail makes the hook fail-safe — if `jq` is missing
> or the JSON is malformed the hook is a no-op rather than blocking the
> tool call.

## Backstop: max-turns ceiling

Daemon enforces `MAX_TURNS_PER_THREAD` (default: **20**), excluding
`kind=progress`. Beyond this, new messages on the thread are dropped and a
warning is logged. Defense-in-depth — catches bugs the type system hasn't
yet excluded (e.g. a future `kind` introduced without analyzing its loop
properties).

## Dedup cache

| Property      | Value                                                            |
|---------------|------------------------------------------------------------------|
| Key           | `(from_node, thread_id, seq_id)`                                 |
| Value         | Last response sent for that key (or sentinel "processed-no-resp")|
| TTL           | Match thread expiry (currently `~90 days` per OpenBrain `expires_at`) |
| Storage       | In-memory `ConcurrentHashMap` on the daemon, lost on restart     |
| Cold-start    | After restart, dedup misses → message re-runs once. Acceptable because OpenBrain's `status=delivered` guard (v1.1.4) prevents the *poller* from picking it up a second time anyway. |

## Backwards compatibility

- Pre-v1.2 daemons don't emit `seq_id`/`ack_policy`/`reply_policy`/
  `respond_by`/`update_interval_seconds`. Receivers default missing fields
  per the table above so old → new traffic still works.
- Pre-v1.2 daemons don't understand `kind=reply`, `kind=ping`, or
  `kind=progress`. New senders must fall back to `kind=action` (with the
  existing v1.1.x `ACK_PATTERN` guards in place) when `peer.version <
  1.2.0`.
- The `ACK_PATTERN` content guards (v1.1.5/v1.1.6) stay in place during
  rollout. Removed in v1.3 after a two-week regression-free window with all
  mesh nodes on v1.2.

## Migration plan

Single release. The loop fix and the progress mechanism ship together as
v1.2.0; if integration testing surfaces issues, fix-up releases (v1.2.1+)
follow as needed. "Second ship is fixing what breaks."

1. **v1.2.0** — full implementation in one release:
   - Wire-format fields: `seq_id`, `ack_policy`, `reply_policy`,
     `respond_by`, `update_interval_seconds`, `in_reply_to`.
   - New kinds: `reply`, `progress`, `ping`.
   - `RelayHandler` parses new fields; `MessagePoller` dispatches on the
     extended `kind` enum; multi-turn semantics; dedup cache.
   - `MAX_TURNS_PER_THREAD = 20` ceiling (excluding `kind=progress`).
   - `ClaudeCliProcessor` sets `MESSENGER_PROGRESS_LOG` env var; daemon
     scheduler emits `kind=progress` with the log tail; cleanup on exit.
   - `SystemPrompt` addition (DRAFT — refine in test).
   - `~/.claude/settings.json` `PostToolUse` hook for non-Bash tool calls.
   - Outbound stamps new fields; inbound parses them; defaults preserve
     v1.1.x behavior on receipt.
   - `ACK_PATTERN` content guards (v1.1.5/v1.1.6) stay active throughout
     the v1.2 line as defense-in-depth.

2. **v1.3.0** — remove `ACK_PATTERN` content guards and the `SystemPrompt`
   "Responding to Acknowledgments" instruction. Both are superseded by
   v1.2 protocol-level mechanisms. Trigger: two weeks of v1.2 in
   production with no doom-loop regressions on any node.

## Open questions / known unknowns

1. **Prompt effectiveness.** The progress-logging system-prompt section is
   a draft. Test under real load before believing it works. Iterate.
2. **Hook portability.** ~~`$CLAUDE_TOOL_INPUT_SUMMARY` may not be a stable
   env var in current Claude Code releases~~ — **resolved in issue #18**:
   Claude Code passes hook event data via stdin as JSON, not env vars. The
   hook now uses `jq` to extract `tool_name` and a 200-char prefix of
   `tool_input` from stdin. See "Hook configuration" section above.
3. **Secrets sweep regex.** The redaction patterns above are illustrative;
   audit and harden during implementation.
4. **`kind=ping` vs. existing `/ping` HTTP endpoint.** They are different.
   `/ping` is daemon-local liveness (in `MeshRelay.java:149`). `kind=ping`
   is peer-to-peer over `/relay`. Names don't conflict (different
   namespaces); document clearly so future readers don't confuse them.
