# Shader DevTools

Current version support is for `26.2-snapshot-1`

Client-side fabric mod for working on Minecraft core shaders, post shaders, post effects, and render pipelines without restarting the game or reloading the resource pack.


https://github.com/user-attachments/assets/3cfe6218-7ea1-4d3d-81db-04d28ee53dd6


## Features
- Reload shaders, both core and post, on demand without having to fully reload the resource pack
- Live editing of shader files in-game with syntax highlighting
- When a shader exists in multiple packs (including vanilla), ability to edit each of those individually (except vanilla shaders, which are read-only) and force load them
- Ability to quickly visualize what a shader is rendering
- Parsing of post effect JSON files, and the ability to edit these too
- Parsing and visualization of all render pipelines
- Ability to override any value in the `Globals` uniform buffer
- Shader debug logging via `dbg(...)` inside core and post shaders, with output written to the Minecraft log

### Shader Debug Logging

You can add calls like `dbg("time=" + GameTime);` to supported shader programs and the value will be written to `logs/latest.log`.

Notes:
- Works on both OpenGL and Vulkan
- ASCII string literals are supported
- Top-level string concatenation is supported, so `"abc" + someValue` works
- Common scalar and vector values are converted to text automatically

Warning:
- `dbg(...)` is still expensive in hot shaders, especially fragment shaders
- Frequently changing values such as `GameTime` can reduce FPS if logged aggressively

## License

MIT
