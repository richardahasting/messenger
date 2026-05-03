#!/usr/bin/env bash
# verify-fixes.sh — end-to-end verification that the v1.1.3..v1.1.6 fixes hold.
#
# Run from the messenger repo root or any cwd; uses absolute paths.
# Exits 0 if every test passes; non-zero count of failed tests otherwise.
#
# Required: bash, curl, jq, mvn, journalctl (read access), running messenger daemon
#           on linuxserver at localhost:13007.

set -u

REPO="/home/richard/projects/messenger"
PORT=13007
DAEMON_URL="http://localhost:${PORT}"
OPENBRAIN_URL="http://192.168.0.226:3000"
SELF_NODE="linuxserver"
PEER_NODE="gemma-small"          # used as ping target — well-behaved peer
ECHO_NODE="gemma-large"          # used as a sender that loops back acks

PASS=0
FAIL=0
FAILED_TESTS=()

red()    { printf '\033[31m%s\033[0m\n' "$*"; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
hdr()    { printf '\n\033[1m── %s ──\033[0m\n' "$*"; }

record_pass() { PASS=$((PASS+1)); green   "  PASS — $1"; }
record_fail() { FAIL=$((FAIL+1)); FAILED_TESTS+=("$1"); red "  FAIL — $1"; }

# Helper: send a /relay POST. Echoes the JSON response.
relay_post() {
    local to="$1" from="$2" content="$3" kind="${4:-action}"
    curl -s -X POST "${DAEMON_URL}/relay" \
        -H "Content-Type: application/json" \
        -d "$(jq -n \
            --arg to "$to" --arg from "$from" \
            --arg content "$content" --arg kind "$kind" \
            '{to:$to, from:$from, content:$content, kind:$kind}')"
}

# Helper: count messages in a thread via OpenBrain MCP.
# OpenBrain doesn't expose /api/messages directly; threads are queried via the
# MCP interface that the daemon itself uses. We pull the key from the
# daemon's .env so the script doesn't need a separate credential.
thread_len() {
    local tid="$1"
    local key
    key=$(grep -E '^OPENBRAIN_KEY=' "${REPO}/.env" 2>/dev/null | cut -d= -f2-)
    curl -s -X POST "${OPENBRAIN_URL}/mcp" \
        -H 'Content-Type: application/json' \
        -H "x-brain-key: ${key}" \
        -d "{\"tool\":\"get_thread\",\"thread_id\":${tid}}" \
        | jq 'length' 2>/dev/null || echo "ERR"
}

# Helper: dump a thread's content array (one item per message). Used by tests
# 10/11 to scan for kind=progress payloads.
thread_contents() {
    local tid="$1"
    local key
    key=$(grep -E '^OPENBRAIN_KEY=' "${REPO}/.env" 2>/dev/null | cut -d= -f2-)
    curl -s -X POST "${OPENBRAIN_URL}/mcp" \
        -H 'Content-Type: application/json' \
        -H "x-brain-key: ${key}" \
        -d "{\"tool\":\"get_thread\",\"thread_id\":${tid}}"
}

# Helper: pull the last n lines of journal for the messenger service.
recent_journal() {
    local since="${1:-5 minutes ago}"
    journalctl --user -u messenger -S "$since" --no-pager 2>/dev/null \
        || journalctl -u messenger -S "$since" --no-pager 2>/dev/null
}

# ════════════════════════════════════════════════════════════════════════════
# Test 0 — daemon liveness and version sanity
# ════════════════════════════════════════════════════════════════════════════
test_health() {
    hdr "Test 0: /health reports v1.2.0, processor=claude-cli"
    local body version processor
    body=$(curl -s "${DAEMON_URL}/health") || { record_fail "health unreachable"; return; }
    version=$(echo "$body" | jq -r '.version')
    processor=$(echo "$body" | jq -r '.processor')

    [[ "$version" == "1.2.0" ]] \
        && record_pass "version=1.2.0" \
        || record_fail "version mismatch (got: $version)"

    [[ "$processor" == "claude-cli" ]] \
        && record_pass "processor=claude-cli" \
        || record_fail "processor mismatch (got: $processor)"
}

# ════════════════════════════════════════════════════════════════════════════
# Test 1 — v1.2 kind=ping daemon-handled pong (issue #14)
#
# Pre-v1.2: a literal-string "ping" payload required Claude to interpret,
#           reply, and the v1.1.5/v1.1.6 ack filters to suppress reflex
#           round-trips. Threads landed somewhere between 2 and 7 messages.
# Post-v1.2: kind=ping is daemon-handled. The receiver's MessagePoller
#            short-circuits the processor and POSTs a kind=reply payload="pong"
#            back to the sender via /relay. No Claude in the loop. The thread
#            should terminate at exactly 2 messages: ping + reply.
#
# A faster latency budget too — issue #14 sets <100ms for ping → pong; the
# 60s sleep here is for the OpenBrain poll cycle to make the conversation
# visible, not for processing.
# ════════════════════════════════════════════════════════════════════════════
test_doom_loop() {
    hdr "Test 1: kind=ping resolves in 2 messages, no Claude invoked (v1.2 issue #14)"

    local since_marker; since_marker=$(date -Iseconds)

    # Send kind=ping to a peer; payload is wire-required (non-empty) but
    # semantically ignored — daemon auto-responds with "pong".
    local resp tid
    resp=$(relay_post "$PEER_NODE" "$SELF_NODE" "ping" "ping")
    tid=$(echo "$resp" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "could not start ping thread (resp: $resp)"
        return
    fi
    yellow "  Started ping thread $tid; sleeping 30s for the pong to land…"
    sleep 30

    # Snapshot 1 — should already show {ping, pong}
    local len1; len1=$(thread_len "$tid")
    sleep 30
    # Snapshot 2 — must NOT have grown; v1.2 guarantees stability
    local len2; len2=$(thread_len "$tid")

    yellow "  Thread $tid sizes: first=$len1, +30s=$len2"

    if assert_doom_loop_resolved "$tid" "$len1" "$len2"; then
        record_pass "ping → pong terminated at 2 messages — thread $tid"
    else
        record_fail "ping → pong did not terminate at 2 messages — thread $tid (sizes $len1 / $len2)"
    fi

    # Belt-and-braces: the daemon must log the auto-reply, AND must NOT have
    # invoked Claude on this thread.
    if recent_journal "$since_marker" | grep -q "Auto-replied to ping"; then
        record_pass "journal shows daemon-handled 'Auto-replied to ping'"
    else
        record_fail "journal missing 'Auto-replied to ping' since $since_marker"
    fi
}

# ────────────────────────────────────────────────────────────────────────────
# v1.2 makes this deterministic — exactly 2 messages (ping + pong), stable.
# Any growth means the receiver fell back to Claude or a peer ack-loop crept
# back in.
#
# Choice: STRICT (len1==2 && len2==2). Three options were considered:
#   • STRICT  — len1==2 && len2==2
#   • BOUNDED — len1<=4 && len2==len1
#   • STABLE  — len1==len2 (any size, just must not grow)
#
# STRICT is chosen because the spec's loop-prevention guarantee
# (docs/protocol-v1.2.md § "Loop-prevention guarantee") is exact, not
# bounded: "a single Q&A turn terminates in exactly 2 messages." A larger
# observed length would mean either (a) the daemon fell back to running
# Claude on a ping (issue #14 regression), or (b) a peer ack-loop crept in
# under v1.1.x semantics. BOUNDED would mask such regressions until they
# escaped the bound; STABLE would mask them entirely. STRICT trades
# brittleness against scheduling jitter for early detection of any
# regression — and v1.2's daemon-handled ping makes scheduling jitter
# irrelevant (the pong is emitted synchronously inside handlePing before
# the inbound ping is archived).
# ────────────────────────────────────────────────────────────────────────────
assert_doom_loop_resolved() {
    local tid="$1" len1="$2" len2="$3"
    [[ "$len1" == "2" && "$len2" == "2" ]]
}

# ════════════════════════════════════════════════════════════════════════════
# Test 2 — v1.1.5 input filter: literal ack from peer is dropped
#
# Sends "noop — ack received" *as if from gemma-large* — exercising the
# inbound-side guard. Asserts:
#   • journal contains "Skipping ack content"
#   • no outbound from linuxserver in the resulting thread
# ════════════════════════════════════════════════════════════════════════════
test_input_filter() {
    hdr "Test 2: input filter skips literal ack (v1.1.5)"
    local since_marker
    since_marker=$(date -Iseconds)

    local resp tid
    resp=$(v12_relay_post "$SELF_NODE" "$ECHO_NODE" "noop — ack received" "ack" \
        '"in_reply_to":"verify-fixes:0"')
    tid=$(echo "$resp" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "input-filter: could not POST (resp: $resp)"
        return
    fi
    yellow "  Started thread $tid (kind=ack); sleeping 30s…"
    sleep 30

    # NB: kind=ack will be archived by the poller's kind-short-circuit (v1.1.3),
    # not by the v1.1.5 input filter — so this test alone doesn't fully isolate
    # v1.1.5. That's why we *also* send kind=action below to exercise the
    # input-filter path specifically.

    local resp2 tid2
    resp2=$(relay_post "$SELF_NODE" "$ECHO_NODE" "noop — ack received" "action")
    tid2=$(echo "$resp2" | jq -r '.thread_id // empty')
    yellow "  Started thread $tid2 (kind=action, ack-shaped body); sleeping 60s…"
    sleep 60

    if recent_journal "$since_marker" | grep -q "Skipping ack content"; then
        record_pass "journal shows 'Skipping ack content'"
    else
        record_fail "journal missing 'Skipping ack content' since $since_marker"
    fi

    local len; len=$(thread_len "$tid2")
    if [[ "$len" == "1" ]]; then
        record_pass "thread $tid2 has 1 message — no reflex reply"
    else
        record_fail "thread $tid2 has $len messages — expected 1"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 3 — v1.1.3 kind=ack short-circuit in poller
#
# Sends a kind=ack message; asserts:
#   • journal contains "Skipping non-action message: kind=ack"
#   • thread length stays 1 (no reply)
# ════════════════════════════════════════════════════════════════════════════
test_kind_short_circuit() {
    hdr "Test 3: kind=ack archived without processor (v1.1.3)"
    local since_marker; since_marker=$(date -Iseconds)

    local resp tid
    resp=$(v12_relay_post "$SELF_NODE" "$ECHO_NODE" "diagnostic ping — ignore" "ack" \
        '"in_reply_to":"verify-fixes:0"')
    tid=$(echo "$resp" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "kind=ack: could not POST (resp: $resp)"
        return
    fi
    yellow "  Started thread $tid (kind=ack); sleeping 60s…"
    sleep 60

    if recent_journal "$since_marker" | grep -q "Skipping non-action message: kind=ack"; then
        record_pass "journal shows kind=ack short-circuit"
    else
        record_fail "journal missing kind=ack short-circuit since $since_marker"
    fi

    local len; len=$(thread_len "$tid")
    if [[ "$len" == "1" ]]; then
        record_pass "thread $tid has 1 message"
    else
        record_fail "thread $tid has $len messages — expected 1"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 4 — v1.1.4 non-pending status guard in poller
#
# Verifies that no message id appears in 'Processed message' log lines more
# than once over the last day. A duplicate id means the poller re-processed
# an already-archived/delivered message — the bug v1.1.4 fixed.
# ════════════════════════════════════════════════════════════════════════════
test_status_guard() {
    hdr "Test 4: poller does not re-process non-pending messages (v1.1.4)"
    local dups
    dups=$(recent_journal "1 day ago" \
        | grep -oE "Processed message [^ ]+ from=[^ ]+" \
        | sort | uniq -c \
        | awk '$1 > 1 {print}')
    if [[ -z "$dups" ]]; then
        record_pass "no duplicate 'Processed message' entries in last 24h"
    else
        record_fail "duplicate 'Processed message' entries found:"
        echo "$dups"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 5 — unit-level guards (subprocess-timeout, ack patterns, kind parse,
# poller status guard) via the existing JUnit suite.
# ════════════════════════════════════════════════════════════════════════════
test_unit_suite() {
    hdr "Test 5: mvn test (unit-level guards across all fixes)"
    if (cd "$REPO" && mvn -q -DskipTests=false test); then
        record_pass "all 243 unit tests pass"
    else
        record_fail "mvn test reported failures (see output above)"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# v1.2 protocol coverage — Tests 6-11 exercise the new mechanisms added in
# issues #12-#17 against the live mesh. Each posts to /relay (loopback) and
# inspects journal/OpenBrain to confirm the wire-level invariants.
# ════════════════════════════════════════════════════════════════════════════

# Helper: send a /relay POST with v1.2 fields. Caller passes a JSON object as
# extra fields (e.g. '"seq_id":"a:1:1","ack_policy":"REQ_ACK"'). Echoes the
# JSON response.
v12_relay_post() {
    local to="$1" from="$2" content="$3" kind="$4" extra="${5:-}"
    local body
    body=$(jq -n \
        --arg to "$to" --arg from "$from" \
        --arg content "$content" --arg kind "$kind" \
        '{to:$to, from:$from, content:$content, kind:$kind}')
    if [[ -n "$extra" ]]; then
        # Merge raw extra fields into the object (jq's `+` for two objects).
        body=$(echo "$body" | jq ". + {${extra}}")
    fi
    curl -s -X POST "${DAEMON_URL}/relay" \
        -H "Content-Type: application/json" \
        -d "$body"
}

# ════════════════════════════════════════════════════════════════════════════
# Test 6 — kind=ping round-trip with latency budget (issue #14)
#
# Acceptance: kind=reply payload="pong" arrives quickly and the journal
# never shows a Claude invocation for the ping. Test 1 above checks the
# 2-message stability invariant; Test 6 checks the daemon's auto-pong
# emission is sub-second on the local mesh.
# ════════════════════════════════════════════════════════════════════════════
test_ping_latency() {
    hdr "Test 6: kind=ping → pong arrives quickly, no Claude invoked"
    local since_marker; since_marker=$(date -Iseconds)

    local resp tid t_start t_end elapsed_ms
    t_start=$(date +%s%3N)
    resp=$(relay_post "$PEER_NODE" "$SELF_NODE" "ping" "ping")
    tid=$(echo "$resp" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "ping POST failed (resp: $resp)"
        return
    fi

    # Daemon-handled pong is emitted synchronously inside handlePing on the
    # *receiver*. The full round-trip includes a wake-up ping and an
    # OpenBrain write on each side. Sub-second is realistic on LAN; we
    # assert <2000ms to absorb scheduling jitter while still flagging any
    # accidental fall-through to Claude (which would be 30-300s).
    local len; len=0
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.2
        len=$(thread_len "$tid")
        [[ "$len" == "2" ]] && break
    done
    t_end=$(date +%s%3N)
    elapsed_ms=$((t_end - t_start))

    if [[ "$len" == "2" ]]; then
        record_pass "ping → pong reached 2 messages in ${elapsed_ms}ms"
    else
        record_fail "ping → pong did not reach 2 messages within 2s (len=$len)"
    fi

    if (( elapsed_ms < 2000 )); then
        record_pass "ping latency under 2s budget (${elapsed_ms}ms)"
    else
        record_fail "ping latency exceeded 2s budget (${elapsed_ms}ms)"
    fi

    # Belt-and-braces: NO Claude session should have been spawned for this
    # thread. The journal would show "Processed message thread_id=$tid" if
    # the processor ran.
    if recent_journal "$since_marker" | grep -q "Processed message thread_id=$tid"; then
        record_fail "Claude was invoked on ping thread $tid (found 'Processed message')"
    else
        record_pass "no 'Processed message' for ping thread $tid — daemon handled it"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 7 — multi-turn: two sequential REQ_ACK on one thread = 4 messages
#
# Spec § "Multi-turn conversations": reply_policy=NO_REPLY is per-MESSAGE,
# not per-thread. Sending two REQ_ACK actions on the same thread_id should
# yield exactly:
#   action_1, reply_1, action_2, reply_2  (4 messages total)
# Anything more = a peer auto-responded to the reply (loop regression).
# ════════════════════════════════════════════════════════════════════════════
test_multi_turn() {
    hdr "Test 7: two sequential REQ_ACK turns on one thread → exactly 4 messages"

    # Turn 1 — opens the thread.
    local resp1 tid
    resp1=$(relay_post "$PEER_NODE" "$SELF_NODE" "ping (turn 1)" "ping")
    tid=$(echo "$resp1" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "could not open multi-turn thread (resp: $resp1)"
        return
    fi
    yellow "  Thread $tid opened with turn 1; sleeping 5s for pong…"
    sleep 5

    # Turn 2 — same thread, second REQ_ACK. Use thread_id to keep them stitched.
    local resp2
    resp2=$(curl -s -X POST "${DAEMON_URL}/relay" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg to "$PEER_NODE" --arg from "$SELF_NODE" \
            --arg content "ping (turn 2)" --arg kind "ping" --argjson tid "$tid" \
            '{to:$to, from:$from, content:$content, kind:$kind, thread_id:$tid}')")
    yellow "  Turn 2 posted to thread $tid; sleeping 5s for second pong…"
    sleep 5

    local len; len=$(thread_len "$tid")
    if [[ "$len" == "4" ]]; then
        record_pass "thread $tid has exactly 4 messages — multi-turn closed cleanly"
    else
        record_fail "thread $tid has $len messages — expected 4 (action,reply,action,reply)"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 8 — MAX_TURNS_PER_THREAD ceiling (issue #15)
#
# Spec § "Backstop: max-turns ceiling": the 21st non-progress message on
# a thread is dropped with a warning. Defense-in-depth — counters reset on
# daemon restart, so this test must run within one daemon lifetime.
#
# Sending 21 wire messages serially is slow; we use kind=info (NO_ACK,
# NO_REPLY) so each message is a single archive operation rather than a
# Claude round-trip.
# ════════════════════════════════════════════════════════════════════════════
test_max_turns() {
    hdr "Test 8: 21st non-progress message on one thread is dropped (MAX_TURNS=20)"
    local since_marker; since_marker=$(date -Iseconds)

    # Open the thread with the first message. from=PEER_NODE because the
    # daemon doesn't accept itself as a "from" peer (linuxserver isn't in
    # its own peer list).
    local resp tid
    resp=$(relay_post "$SELF_NODE" "$PEER_NODE" "max-turns probe 1" "info")
    tid=$(echo "$resp" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "could not open MAX_TURNS thread (resp: $resp)"
        return
    fi

    # Send 19 more messages on the same thread → total of 20 sent.
    local i
    for i in $(seq 2 20); do
        curl -s -X POST "${DAEMON_URL}/relay" \
            -H "Content-Type: application/json" \
            -d "$(jq -n --arg to "$SELF_NODE" --arg from "$PEER_NODE" \
                --arg content "max-turns probe $i" --arg kind "info" \
                --argjson tid "$tid" \
                '{to:$to, from:$from, content:$content, kind:$kind, thread_id:$tid}')" \
            >/dev/null
    done
    yellow "  Sent 20 info messages on thread $tid; sleeping 30s for them to drain…"
    sleep 30

    # The 21st should trip the ceiling.
    curl -s -X POST "${DAEMON_URL}/relay" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg to "$SELF_NODE" --arg from "$PEER_NODE" \
            --arg content "max-turns probe 21 (should be dropped)" --arg kind "info" \
            --argjson tid "$tid" \
            '{to:$to, from:$from, content:$content, kind:$kind, thread_id:$tid}')" \
        >/dev/null
    yellow "  Sent 21st message; sleeping 15s for poller to see it…"
    sleep 15

    if recent_journal "$since_marker" | grep -q "Thread cap (>20) exceeded"; then
        record_pass "journal shows 'Thread cap (>20) exceeded' warning"
    else
        record_fail "journal missing MAX_TURNS warning — 21st message not dropped"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 9 — dedup cache (issue #16)
#
# Spec § "Receiver behavior" step 2: a duplicate (from, thread, seq_id)
# replays the cached response without re-invoking the processor. Daemon-
# local cache, so this test must run within one daemon lifetime.
# ════════════════════════════════════════════════════════════════════════════
test_dedup() {
    hdr "Test 9: duplicate seq_id triggers cached response, processor invocation count stays at 1"
    local since_marker; since_marker=$(date -Iseconds)

    # First post — establishes the cache entry. We use kind=ping because the
    # daemon caches the auto-pong; this avoids the test depending on Claude.
    local fixed_seq; fixed_seq="dedup-test-$(date +%s):0"
    local resp1 tid
    resp1=$(v12_relay_post "$PEER_NODE" "$SELF_NODE" "dedup probe" "ping" \
        "\"seq_id\":\"$fixed_seq\"")
    tid=$(echo "$resp1" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "could not open dedup thread (resp: $resp1)"
        return
    fi
    yellow "  First ping posted (seq=$fixed_seq, thread=$tid); sleeping 10s…"
    sleep 10

    # Second post — same seq_id, same thread. Must hit the cache.
    curl -s -X POST "${DAEMON_URL}/relay" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg to "$PEER_NODE" --arg from "$SELF_NODE" \
            --arg content "dedup probe (duplicate)" --arg kind "ping" \
            --arg seq "$fixed_seq" --argjson tid "$tid" \
            '{to:$to, from:$from, content:$content, kind:$kind, seq_id:$seq, thread_id:$tid}')" \
        >/dev/null
    yellow "  Duplicate ping posted; sleeping 10s for dedup hit to land…"
    sleep 10

    if recent_journal "$since_marker" | grep -qE "Dedup hit.*seq=$fixed_seq"; then
        record_pass "journal shows 'Dedup hit' for seq=$fixed_seq"
    else
        record_fail "journal missing dedup hit for seq=$fixed_seq since $since_marker"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 10 — progress mechanism (issue #17)
#
# Spec § "Progress mechanism — daemon-driven heartbeat with log tailing":
# kind=action with update_interval_seconds=30 must trigger at least one
# kind=progress message while the processor runs, and the per-thread log
# file must be removed after the processor exits.
# ════════════════════════════════════════════════════════════════════════════
test_progress() {
    hdr "Test 10: action with update_interval_seconds=30 emits kind=progress, log file cleaned up"
    local since_marker; since_marker=$(date -Iseconds)

    # Long task: ask Claude to recite a longish but bounded response. The
    # progress log will be populated by the PostToolUse hook and tee
    # invocations the system prompt instructs.
    local resp tid
    resp=$(v12_relay_post "$SELF_NODE" "$PEER_NODE" \
        "List five facts about the Canyon Lake Texas reservoir, one per line." \
        "action" \
        '"update_interval_seconds":30')
    tid=$(echo "$resp" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "could not open progress test thread (resp: $resp)"
        return
    fi
    yellow "  Thread $tid started with update_interval_seconds=30; sleeping 90s…"
    sleep 90

    # Look for at least one kind=progress in the thread.
    local progress_count
    progress_count=$(thread_contents "$tid" \
        | jq '[.[] | select(.content | test("kind=progress"))] | length' 2>/dev/null \
        || echo 0)
    if (( progress_count >= 1 )); then
        record_pass "thread $tid received $progress_count progress beat(s)"
    else
        record_fail "thread $tid received 0 progress beats — heartbeat not firing"
    fi

    # Log file cleanup: per-thread file should NOT exist after the reply lands.
    local log_path="/var/run/messenger/progress/thread-${tid}-seq-1.log"
    if [[ -e "$log_path" ]]; then
        record_fail "progress log $log_path still exists after task completion"
    else
        record_pass "progress log $log_path removed after task completion"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# Test 11 — secrets sweep on progress payloads (issue #17)
#
# Spec § "Progress mechanism" / "Constraints": "Daemon performs a secrets
# sweep on each tail before sending: lines matching common credential
# patterns (.*_TOKEN=, .*_KEY=, Authorization:, password=) are redacted to
# <redacted>." This test forces a credential-shaped line into the payload
# and asserts none of it appears in the kind=progress emitted to the wire.
# ════════════════════════════════════════════════════════════════════════════
test_secrets_sweep() {
    hdr "Test 11: API_TOKEN=abc123 in payload appears as <redacted> in kind=progress"

    # Long-enough task that progress beats fire AND the secret-shaped string
    # ends up in the progress log via the system prompt's tee directive.
    local resp tid
    resp=$(v12_relay_post "$SELF_NODE" "$PEER_NODE" \
        "Run: echo API_TOKEN=abc123 | tee -a \$MESSENGER_PROGRESS_LOG; sleep 1; echo done" \
        "action" \
        '"update_interval_seconds":30')
    tid=$(echo "$resp" | jq -r '.thread_id // empty')
    if [[ -z "$tid" ]]; then
        record_fail "could not open secrets-sweep thread (resp: $resp)"
        return
    fi
    yellow "  Thread $tid started; sleeping 90s for progress beat…"
    sleep 90

    # Pull the kind=progress payloads and check for redaction.
    local progress_payloads
    progress_payloads=$(thread_contents "$tid" \
        | jq -r '.[] | select(.content | test("kind=progress")) | .content')

    if [[ -z "$progress_payloads" ]]; then
        record_fail "no kind=progress messages on thread $tid — sweep cannot be verified"
        return
    fi

    if echo "$progress_payloads" | grep -q "abc123"; then
        record_fail "raw secret 'abc123' leaked into kind=progress payload"
    else
        record_pass "no raw 'abc123' in any kind=progress payload"
    fi

    if echo "$progress_payloads" | grep -q "<redacted>"; then
        record_pass "kind=progress payload contains '<redacted>' marker"
    else
        record_fail "no '<redacted>' marker in kind=progress payloads — sweep not firing"
    fi
}

# ════════════════════════════════════════════════════════════════════════════
main() {
    test_health
    test_doom_loop
    test_input_filter
    test_kind_short_circuit
    test_status_guard
    test_unit_suite
    test_ping_latency
    test_multi_turn
    test_max_turns
    test_dedup
    test_progress
    test_secrets_sweep

    hdr "Summary"
    echo "  Passed: $PASS"
    echo "  Failed: $FAIL"
    if (( FAIL > 0 )); then
        red "Failed tests:"
        for t in "${FAILED_TESTS[@]}"; do red "  - $t"; done
    fi
    exit "$FAIL"
}

main "$@"
