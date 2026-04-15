"""MCP server wrapping the Messenger daemon REST API.

Exposes messenger operations as MCP tools so Claude Code can relay
messages, broadcast, check health, and query the node's status.
The messenger daemon must be running on localhost (port from config.json).
"""

import json
import socket
from pathlib import Path

import httpx
from mcp.server.fastmcp import FastMCP

_config_path = Path(__file__).parent / "config.json"
_config = json.loads(_config_path.read_text())
_node_name = _config.get("node_name", "unknown")

# Messenger daemon runs on port 13007 by default;
# actual port is fetched from OpenBrain config at daemon startup,
# but 13007 is the standard across all nodes.
MESSENGER_URL = "http://localhost:13007"

mcp = FastMCP("messenger")


def _get(path: str, timeout: int = 10) -> dict:
    with httpx.Client(timeout=timeout) as client:
        r = client.get(f"{MESSENGER_URL}{path}")
        r.raise_for_status()
        return r.json()


def _post(path: str, data: dict, timeout: int = 30) -> dict:
    with httpx.Client(timeout=timeout) as client:
        r = client.post(f"{MESSENGER_URL}{path}", json=data)
        r.raise_for_status()
        return r.json()


# --- Messaging ---

@mcp.tool()
def msg_relay(to: str, content: str) -> dict:
    """Send a message to a specific node. Stores in OpenBrain, wakes the target.

    Args:
        to: Target node name (e.g. 'macmini', 'macbook-air', 'gemma-small')
        content: The message to send
    """
    return _post("/relay", {"to": to, "from": _node_name, "content": content})


@mcp.tool()
def msg_broadcast(content: str) -> dict:
    """Broadcast a message to all nodes. Stores in OpenBrain, wakes all peers.

    Args:
        content: The message to broadcast
    """
    return _post("/broadcast", {"from": _node_name, "content": content})


@mcp.tool()
def msg_wake(target: str) -> dict:
    """Send a wake-up ping to a specific node, triggering an immediate inbox poll.

    Args:
        target: Node name to wake (e.g. 'macmini')
    """
    # Look up peer URL from health to find the right address
    health = _get("/health")
    peers = health.get("peers", [])
    # Wake is sent directly to the target, not through our daemon
    return _post("/wake", {"from": _node_name})


# --- Status ---

@mcp.tool()
def msg_health() -> dict:
    """Get this node's health: name, uptime, peers, last poll time, messages processed."""
    return _get("/health")


@mcp.tool()
def msg_ping() -> dict:
    """Quick liveness check — is the local messenger daemon running?"""
    return _get("/ping")


# --- Ask User (via Telegram) ---

@mcp.tool()
def msg_ask_user(question: str, sender: str = "claude-agent", timeout: int = 900) -> dict:
    """Send a question to Richard via Telegram and wait for his reply.

    Blocks until Richard replies or the timeout expires.
    Use this when you need human input, approval, or a decision.

    Args:
        question: The question to ask
        sender: Who is asking (e.g. 'linuxserver', 'deploy-script')
        timeout: Max seconds to wait for reply (default 900 = 15 min)
    """
    ask_host = "localhost"
    ask_port = 7802
    timeout = min(timeout, 900)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout + 30)
    try:
        sock.connect((ask_host, ask_port))
        payload = json.dumps({"question": question, "sender": sender, "timeout": timeout}) + "\n"
        sock.sendall(payload.encode())

        data = b""
        while b"\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk

        if not data:
            return {"error": "no response from bot"}
        return json.loads(data.decode().strip())
    except socket.timeout:
        return {"error": "timeout", "message": f"No reply within {timeout}s"}
    except ConnectionRefusedError:
        return {"error": "connection_refused", "message": "Telegram bot ask server not running on port 7802"}
    except Exception as e:
        return {"error": str(e)}
    finally:
        sock.close()


if __name__ == "__main__":
    mcp.run()
