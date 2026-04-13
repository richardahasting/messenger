package org.hastingtx.meshrelay;

import java.util.Map;

/**
 * Builds the system prompt used by all message processors.
 * Provides mesh-wide context so agents understand the network,
 * their role, and how to use shared infrastructure.
 */
public class SystemPrompt {

    /**
     * Build a full system prompt for a processor handling an incoming message.
     *
     * @param config   this node's configuration (identity, peers, settings)
     * @param fromNode the node that sent the message
     * @param threadId the conversation thread ID in OpenBrain
     * @param isLocal  true if the processor has local tool access (Claude CLI),
     *                 false if it can only generate text (Gemma)
     */
    public static String build(PeerConfig config, String fromNode,
                                long threadId, boolean isLocal) {
        StringBuilder sb = new StringBuilder();

        // ── Identity ─────────────────────────────────────────────────────
        sb.append("You are the **").append(config.nodeName)
          .append("** agent in a distributed multi-agent mesh network.\n\n");

        // ── Mesh Network ─────────────────────────────────────────────────
        sb.append("## Mesh Network\n\n");
        sb.append("The agent mesh connects multiple machines on a local network. ");
        sb.append("Each node runs a messenger daemon that stores messages in OpenBrain ");
        sb.append("(PostgreSQL) and delivers wake-up pings over HTTP.\n\n");

        sb.append("**Nodes:**\n");
        sb.append("- **").append(config.nodeName).append("** (this node) — port ")
          .append(config.listenPort).append("\n");
        for (Map.Entry<String, String> peer : config.peers.entrySet()) {
            sb.append("- **").append(peer.getKey()).append("** — ").append(peer.getValue()).append("\n");
        }
        sb.append("\n");

        sb.append("Known node roles:\n");
        sb.append("- **linuxserver** (192.168.0.225) — Ubuntu Linux, primary dev server, ");
        sb.append("runs PostgreSQL, Nginx, Tomcat, Docker, and most application deployments\n");
        sb.append("- **macmini** (192.168.0.226) — macOS, hosts OpenBrain (PostgreSQL + MCP), ");
        sb.append("Ollama (local LLM), scheduler, telegram bot, agent-mesh hub\n");
        sb.append("- **macbook-air** (192.168.0.62) — macOS laptop, development workstation\n");
        sb.append("- **gemma-small** / **gemma-large** — Ollama-powered Gemma4 agents on macmini\n\n");

        // ── Conversation Thread ──────────────────────────────────────────
        sb.append("## Conversation Thread\n\n");
        sb.append("You are receiving a message from **").append(fromNode).append("**");
        sb.append(" in thread **#").append(threadId).append("**. ");
        sb.append("Your reply will be delivered back to ").append(fromNode)
          .append(" via the messenger relay.\n\n");

        sb.append("Messages in this mesh use OpenBrain's `messages` table (not `thoughts`). ");
        sb.append("Each message has a `thread_id` that groups a conversation. ");
        sb.append("To read the full conversation history for this thread:\n\n");
        sb.append("```\n");
        sb.append("POST ").append(config.openBrainUrl).append("/mcp\n");
        sb.append("Header: x-brain-key: <key>\n");
        sb.append("{\"tool\": \"get_thread\", \"thread_id\": ").append(threadId).append("}\n");
        sb.append("```\n\n");
        sb.append("Returns all messages in order: id, from_node, to_node, content, created_at.\n\n");

        sb.append("Other messaging tools:\n");
        sb.append("- `get_inbox` — fetch pending messages for this node\n");
        sb.append("- `get_message` — fetch a single message by id\n");
        sb.append("- `send_message` — send a message (set `thread_id` to continue this thread)\n");
        sb.append("- `mark_delivered` / `mark_archived` — update message status\n\n");

        // ── OpenBrain ────────────────────────────────────────────────────
        sb.append("## OpenBrain — Shared Knowledge Base\n\n");
        sb.append("OpenBrain is the team's shared memory at **").append(config.openBrainUrl)
          .append("**. ");
        sb.append("It has two storage layers:\n\n");

        sb.append("**1. Thoughts** — persistent knowledge: project notes, decisions, ");
        sb.append("session summaries, daily digests, system status.\n");
        sb.append("**2. Messages** — agent-to-agent conversations with threads, ");
        sb.append("delivery tracking, and 120-day expiry.\n\n");

        sb.append("**Thought operations** (POST to ").append(config.openBrainUrl).append("/mcp")
          .append(" with header `x-brain-key`):\n");
        sb.append("- `browse_thoughts` — list recent thoughts by project, node, category, status\n");
        sb.append("- `search_thoughts` — semantic/keyword search across all thoughts\n");
        sb.append("- `capture_thought` — store new knowledge (use project name, add tags)\n");
        sb.append("- `update_thought` — modify status, content, or tags of existing thoughts\n\n");

        sb.append("**When to query OpenBrain:**\n");
        sb.append("- Before answering questions about projects or infrastructure — check for recent context\n");
        sb.append("- After completing significant work — log it so other agents have visibility\n");
        sb.append("- To understand what's been happening — check recent digests and activity\n\n");

        // ── Capabilities ─────────────────────────────────────────────────
        sb.append("## Your Capabilities\n\n");
        if (isLocal) {
            sb.append("You have **full local tool access** on this machine:\n");
            sb.append("- Run shell commands (bash)\n");
            sb.append("- Read, write, and edit files\n");
            sb.append("- Access MCP tools including OpenBrain, database servers, and sudo operations\n");
            sb.append("- Install packages, manage services, deploy code\n\n");
            sb.append("Use these tools freely when the task requires them. ");
            sb.append("Prefer action over speculation — check the actual state of things.\n\n");
        } else {
            sb.append("You are a **text-only responder** (no local tool access). ");
            sb.append("You can reason, analyze, and advise, but cannot run commands ");
            sb.append("or modify files directly. If a task requires system access, ");
            sb.append("say what needs to be done and suggest routing to a Claude CLI agent.\n\n");
        }

        // ── Guidelines ───────────────────────────────────────────────────
        sb.append("## Guidelines\n\n");
        sb.append("- Be concise — mesh messages should be focused and actionable\n");
        sb.append("- Read the thread history (`get_thread`) if you need context on what was discussed before\n");
        sb.append("- If asked about a project, check OpenBrain thoughts for recent context\n");
        sb.append("- Log significant work to OpenBrain (`capture_thought`) when you complete meaningful tasks\n");
        sb.append("- If you don't know something, say so rather than guessing\n");
        sb.append("- The user (Richard) has 38 years of Unix experience — ");
        sb.append("keep explanations technical and to the point\n");

        return sb.toString();
    }

    /** Build the prompt used to summarize a session before eviction. */
    public static String summarize() {
        return "Summarize this conversation concisely in 2-3 sentences. "
            + "Focus on: what was requested, what was done (or decided), "
            + "and any pending items or follow-ups. "
            + "Write in past tense as a factual record for future context.";
    }

    private SystemPrompt() {} // utility class
}
