#!/usr/bin/env python3
import json
import os
import sys
import traceback
import urllib.error
import urllib.request


CONTROL_URL = os.environ.get("CSDT_CONTROL_URL", "http://127.0.0.1:34783").rstrip("/")

TOOLS = [
    {
        "name": "refresh_shaders",
        "description": "Queue a full shader refresh in the running Minecraft client.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
    {
        "name": "take_screenshot",
        "description": "Take a screenshot in the running Minecraft client and return the saved path.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
]


def main():
    for line in sys.stdin:
        if not line.strip():
            continue

        try:
            request = json.loads(line)
            response = handle_request(request)
        except Exception as exception:
            traceback.print_exc(file=sys.stderr)
            response = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {
                    "code": -32603,
                    "message": repr(exception),
                },
            }

        if response is not None:
            sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
            sys.stdout.flush()


def handle_request(request):
    method = request.get("method")
    request_id = request.get("id")

    if method == "initialize":
        return result(request_id, {
            "protocolVersion": "2024-11-05",
            "capabilities": {
                "tools": {},
            },
            "serverInfo": {
                "name": "shader-devtools",
                "version": "0.1.0",
            },
        })

    if method == "notifications/initialized":
        return None

    if method == "tools/list":
        return result(request_id, {"tools": TOOLS})

    if method == "tools/call":
        params = request.get("params") or {}
        name = params.get("name")
        if name == "refresh_shaders":
            payload = call_control("/refresh-shaders", timeout=10)
            return tool_result(request_id, payload)
        if name == "take_screenshot":
            payload = call_control("/screenshot", timeout=40)
            return tool_result(request_id, payload)
        return error(request_id, -32602, f"Unknown tool: {name}")

    if method == "ping":
        return result(request_id, {})

    return error(request_id, -32601, f"Unknown method: {method}")


def call_control(path, timeout):
    request = urllib.request.Request(
        CONTROL_URL + path,
        method="POST",
        headers={
            "Accept": "application/json",
            "User-Agent": "shader-devtools-mcp/0.1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = response.read().decode("utf-8")
            return json.loads(data)
    except urllib.error.HTTPError as exception:
        body = exception.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            payload = {"ok": False, "error": body}
        payload["status"] = exception.code
        return payload
    except (OSError, urllib.error.URLError, TimeoutError) as exception:
        return {
            "ok": False,
            "error": f"Unable to reach Shader DevTools control server at {CONTROL_URL}: {exception}",
        }


def tool_result(request_id, payload):
    return result(request_id, {
        "content": [
            {
                "type": "text",
                "text": json.dumps(payload, indent=2),
            },
        ],
        "isError": not payload.get("ok", False),
    })


def result(request_id, payload):
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "result": payload,
    }


def error(request_id, code, message):
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": code,
            "message": message,
        },
    }


if __name__ == "__main__":
    main()
