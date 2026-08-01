# Vitrail

Minecraft 26.2 ships a native Vulkan renderer, but shader packs still require
OpenGL: Iris does not run on Vulkan, and the engines that do run on Vulkan use
their own pack formats. Vitrail aims to close that gap by loading existing
OptiFine-format shader packs on the Vulkan backend.

Pack GLSL is translated once, when the pack is loaded, and compiled to SPIR-V.
There is no translation layer at runtime.

## Status

Early. Nothing here loads a shader pack yet, and there is no reason to install
it unless you are working on it. What exists is the first milestone: proof that
the engine can get a pass of its own into the frame and put its own pixels on
the screen.

Concretely, the mod draws one full screen triangle after the level is rendered,
on the game's own render pass machinery. The GLSL for that pass lives in
`vitrail/` in the game directory, outside the jar, and is compiled to SPIR-V at
startup through the compiler Minecraft already embeds. Editing `overlay.fsh` and
restarting is enough to change what appears, with no rebuild.

That is the whole of it: no pack format, no multiple passes, no uniforms beyond
the ones the game already binds, no interface. Those come later.

The hook is a NeoForge event rather than a mixin, so nothing reaches into
Minecraft or Sodium internals at this point. NeoForge is the only loader
implemented; the Fabric module exists in the build but is empty.

## Building and installing

See [INSTALL.md](INSTALL.md).
