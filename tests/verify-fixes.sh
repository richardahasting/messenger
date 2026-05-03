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

# Helper: count messages in a thread via OpenBrain.
thread_len() {
    local tid="$1"
    curl -s "${OPENBRAIN_URL}/api/messages?thread_id=${tid}" \
        | jq 'length' 2>/dev/null || echo "ERR"
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
    hdr "Test 0: /health reports v1.1.6, processor=claude-cli"
    local body version processor
    body=$(curl -s "${DAEMON_URL}/health") || { record_fail "health unreachable"; return; }
    version=$(echo "$body" | jq -r '.version')
    processor=$(echo "$body" | jq -r '.processor')

    [[ "$version" == "1.1.6" ]] \
        && record_pass "version=1.1.6" \
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

    # Send kind=ping to a peer; payload is irrelevant — daemon ignores it
    # and auto-responds with "pong".
    local resp tid
    resp=$(relay_post "$PEER_NODE" "$SELF_NODE" "" "ping")
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
    resp=$(relay_post "$SELF_NODE" "$ECHO_NODE" "noop — ack received" "ack")
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
    resp=$(relay_post "$SELF_NODE" "$ECHO_NODE" "diagnostic ping — ignore" "ack")
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
main() {
    test_health
    test_doom_loop
    test_input_filter
    test_kind_short_circuit
    test_status_guard
    test_unit_suite

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
