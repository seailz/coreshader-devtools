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

## License

MIT
