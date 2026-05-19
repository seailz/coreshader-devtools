# Shader DevTools

Current version support is for `26.2-snapshot-8`

Client-side fabric mod for working on Minecraft core shaders, post shaders, post effects, and render pipelines without restarting the game or reloading the resource pack.


https://github.com/user-attachments/assets/3cfe6218-7ea1-4d3d-81db-04d28ee53dd6


## Features
- In-game Shader DevTools menu, available with `G` by default, with reload, browser, inspector, override, and log tools
- Reload core shaders, post shaders, or both on demand without fully reloading the resource pack
- Quick core shader reload keybind, available with `R` by default
- Searchable core shader and post effect list, with overridden entries shown first
- Live editing of shader and post effect files in-game, with GLSL and JSON syntax highlighting
- Create editable overrides for vanilla/resource-pack shader sources, save them, delete editable sources, and copy source text
- When a shader exists in multiple packs, inspect each source individually and force the active source without changing pack order
- Quickly visualize fragment shader output by replacing the shader result with a debug view
- Parse post effect JSON files, edit them, and inspect their target/pass graph while editing
- Force a post effect from its detail screen for debugging, then release it without restarting the client
- Search and inspect all registered render pipelines, including shader links, vertex formats, samplers, uniforms, defines, targets, and depth/stencil state
- Inspect uniform bindings live
- Manually override `Globals` uniform values 
- Inspect captured sampler bindings by pipeline, preview bound textures, read individual pixels, and copy 16x16 readbacks
- Live log viewer for the latest `logs/latest.log` lines
- On-screen debug summary for renderer, world, active post effect, globals overrides, and recent reload timings
- Shader debug logging via `dbg(...)` inside core and post shaders, with output written to the Minecraft log
- Local MCP server which can refresh shaders and take in-game screenshots

### Shader Debug Logging

You can add calls like `dbg("time=" + GameTime);` to supported shader programs and the value will be written to `logs/latest.log`. It works on
both OpenGL and Vulkan, supports ASCII, and you can do top level concatenation such as `dbg("pos=" + WorldPos);`. The mod will convert the value to a string itself.

### MCP

The repository also includes a MCP server at `mcp/csdt_mcp_server.py` with tools for `refresh_shaders` and `take_screenshot`.
