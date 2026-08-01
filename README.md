# Vitrail

Minecraft 26.2 ships a native Vulkan renderer, but shader packs still require
OpenGL: Iris does not run on Vulkan, and the engines that do run on Vulkan use
their own pack formats. Vitrail aims to close that gap by loading existing
OptiFine-format shader packs on the Vulkan backend.

Pack GLSL is translated once, when the pack is loaded, and compiled to SPIR-V.
There is no translation layer at runtime.

Status: early. The engine gets a pass of its own into the frame and draws a full
screen triangle with it. The GLSL for that pass lives in `vitrail/` in the game
directory and is compiled at startup, so editing it and restarting is enough to
change what shows up. No pack format, no uniforms, nothing else yet.
