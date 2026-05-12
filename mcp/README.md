# Shader DevTools MCP

This is a tiny stdio MCP server for a running Shader DevTools client.

The Minecraft client opens a local control server at `http://127.0.0.1:34783` by default. You can override it with JVM system properties:

- `-Dcsdt.control.host=127.0.0.1`
- `-Dcsdt.control.port=34783`

Run the MCP server from the repository root:

```powershell
python mcp/csdt_mcp_server.py
```

Tools:

- `refresh_shaders`: queues a full shader reload in the running client.
- `take_screenshot`: saves a screenshot through Minecraft's normal screenshot path and returns the absolute file path.
