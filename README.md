<p align="center">
  <img src="neoforge/src/main/resources/vitrail.png" width="128" alt="">
</p>

<h1 align="center">Vitrail</h1>

<p align="center">
  OptiFine-format shader packs, on Minecraft's native Vulkan renderer.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.2-2E6FD9?style=flat-square" alt="Minecraft 26.2">
  <img src="https://img.shields.io/badge/loader-NeoForge-1B2A6B?style=flat-square" alt="NeoForge">
  <img src="https://img.shields.io/badge/renderer-Vulkan-7B3FA0?style=flat-square" alt="Vulkan">
  <img src="https://img.shields.io/badge/licence-LGPL--3.0-F2B134?style=flat-square" alt="LGPL-3.0-only">
  <img src="https://img.shields.io/badge/status-in%20development-6E7681?style=flat-square" alt="In development">
</p>

---

Minecraft 26.2 ships a native Vulkan renderer alongside the OpenGL one. The
shader ecosystem grew up on OpenGL and has not crossed over yet: the packs, the
engines that load them and the habits of the people who write them all assume
that renderer.

Vitrail keeps the two sides compatible while that changes. It loads existing
OptiFine-format packs, unmodified, on the Vulkan backend, so that turning Vulkan
on does not mean giving up the packs you already use.

## Status

**It draws the world's terrain through a pack's own programs, and runs the
pack's whole chain of passes around it, in the frame order the format
prescribes.** Point it at a pack and it will resolve the includes, apply the
settings, work out which program serves what for every dimension, rewrite each
program into GLSL a Vulkan compiler accepts, allocate the colour targets the
pack declares, and run its frame in the pack's own order: the deferred passes
between the opaque world and the translucents, then the composites and the
`final`. The three chunk passes, solid, cutout and translucent, are drawn on
Sodium's meshes with the pack's `gbuffers_terrain` and `gbuffers_water`,
carrying the block ids of `block.properties`, per-quad normals, the pack's
alpha tests and every draw buffer those programs declare; water blends into
the pack's own targets at the exact point of the frame the format puts it,
reading the depth of the world as it stood before the translucents. The
renderer rasterises with a reversed Z a pack knows nothing about, and every
depth a pack writes or samples is converted to the convention it was written
against.

Across the eight packs surveyed, 1738 of their 1863 compilation units compile,
and 743 of their 785 programs compile with their stages linked together. Of the
45117 uniform block members those programs declare between them, 33147 are
answered, including the ones a pack writes for itself as expressions in its own
`shaders.properties`. A settings screen reads the pack's own menu layout and
writes one file per pack.

What it cannot do yet: no shadow map is drawn, so everything a pack reads from
`shadowtex` is white; and the sky, the entities and the particles still come
from the game rather than from the pack's own programs, so a pack composes over
an image the game tone mapped once already. Those two steps are what decides
how close a pack looks to its OpenGL self, and they are what comes next.

## How it works

A shader pack is GLSL written against OpenGL conventions that no Vulkan driver
will accept. Vitrail rewrites it: version bump, `varying` and `attribute` into
`in` and `out`, loose uniforms gathered into a block, `gl_FragData[N]` into
outputs declared one per colour attachment, legacy sampler calls, fixed-function
builtins.

That rewriting happens **once, when the pack is loaded**. What reaches the
driver afterwards is SPIR-V, and there is no translation layer left between the
game and the GPU. The distinction matters: this is a compiler that runs at load
time, not a shim that runs per frame.

The SPIR-V itself is not ours. Minecraft already embeds shaderc and SPIRV-Cross,
and its device accepts an arbitrary shader source, so the game does its own
compilation, reflection and binding assignment on our output exactly as it does
on its own shaders.

## Why the OptiFine format

Not because it is elegant, but because that is where the work is. Packs have
been written against the OptiFine conventions for more than a decade, by a lot
of people, and that is still where nearly all of the community writes today.
Minecraft moving to Vulkan does not make any of that work worse. It just makes
it unrunnable, and asking every author to port to a new format is asking them to
throw it away.

So inventing a format was the obvious alternative, and it was rejected on
purpose. Following conventions that have held for ten years means the
specification already exists, there is a corpus of real packs to test against,
and there is an unambiguous definition of done. A new format closes the door on
all three permanently.

It also means an author can keep shipping one pack through the move to Vulkan
rather than maintaining two. None of this rules out supporting a Vulkan-native
format later, if one appears and people write for it; it is simply not the
problem worth solving first.

The cost is known and measured. Eight packs were surveyed before any code was
written: they expect 274 distinct uniforms between them, and 85 percent of their
1863 compilation units survived a throwaway mechanical translation written to
find that number out. That survey is what the figure in the status above should
be read against. The packs themselves are not redistributable and are not in
this repository.

## The plan

Ordered by risk rather than by how much there is to show for it. Each step has
to end in something that can be looked at and judged, rather than in a claim
that it works.

1. Get a pass of our own into the frame, with GLSL from outside the jar.
2. The pass graph: our own render targets, chained, safe across a resize.
3. Pack loading: `shaders.properties`, includes, settings, program fallbacks.
4. The translator, ported to Java against the measured corpus.
5. The uniform surface, where compiling becomes rendering correctly.
6. Terrain coupling with Sodium.
7. The rest of the frame through the pack: the shadow map, the sky, the
   entities and the particles.

Steps 1 to 6 are done. Step 7 is where the remaining distance to the reference
image lives.

## Related work

Vitrail is not the first attempt at running shaders on this renderer, and it is
not competing with the projects below.

- **[Iris](https://github.com/IrisShaders/Iris)** is the reference
  implementation for OptiFine-format packs and the reason this project is
  LGPL-3.0 as well. It targets OpenGL, which is where the overwhelming majority
  of packs are still played. One table is borrowed from it, with credit in
  [NOTICE](NOTICE): the chain saying which program stands in for another when a
  pack does not ship it. Iris is the only authority for that chain, and the
  format's own documentation does not spell it out.
- **[Sulkan](https://github.com/mravatins/sulkanShaders)** is an open source
  Vulkan shader engine for Minecraft 26.2 and later, GPLv3, built as a Fabric
  mod. It was already running on the Vulkan renderer when this project started,
  and reading where it hooks into the game was useful. None of its code is
  reused here: its licence would not allow it without relicensing all of
  Vitrail, and a mechanical line comparison is run at every milestone to keep
  that claim honest.
- **[Aperture](https://github.com/IrisShaders/Aperture-Example-Pack)**, from the
  Iris team, is a newer engine whose packs are written in Slang. Its example
  pack is public. It is a clean break from the OptiFine format rather than a way
  to keep running what already exists.

None of them covers the narrow case this one is built for: a pack written years
ago, running as it is, on the renderer that now ships with the game.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Loader | NeoForge 26.2.0.32-beta or later in the 26.2 line |
| Sodium | 0.9.x for NeoForge |

The game also has to be running on its Vulkan backend rather than OpenGL, which
is a setting rather than a dependency: `preferredGraphicsBackend:"vulkan"` in
`options.txt`, or Options then Video Settings in game.

Client only. It does nothing on a server and does not need to be installed on
one. Fabric is not supported: the module exists in the build and is empty, which
is deliberate until NeoForge is proven.

Sodium must stay on 0.9.x. Every shader engine hooks its internals, and it has
no stable API for that.

Build and install instructions are in [INSTALL.md](INSTALL.md).

## Compatibility

Vitrail hooks the frame through public NeoForge events where the game offers
them, and through a small set of mixins where it does not: the matrices the
world is really drawn with, which are never stored anywhere the camera exposes,
and Sodium's chunk renderer, which is handed the pack's terrain programs, one
extra vertex element carrying the block id, and the render pass its draw
buffers need. Sodium has no API for any of that, which is why its version is
pinned.

Any mod that unwraps a GPU texture into an OpenGL handle will crash on the
Vulkan backend, with or without Vitrail. Distant Horizons 3.2.0-b does this and
dies on the first frame. This is not something Vitrail can work around.

Do not run another shader engine alongside it.

## Contributing

Open an issue before writing anything substantial. The milestones above are
ordered by risk rather than by how satisfying they are, and work that lands
ahead of its milestone usually cannot be verified yet, which makes it hard to
accept however good it is.

[CONTRIBUTING.md](CONTRIBUTING.md) covers the rest and is short. Two of its rules
bite people who skip it: files are UTF-8 without a BOM everywhere, because a BOM
breaks anything that feeds sources to a GLSL compiler, and line endings are LF.

## Licence

LGPL-3.0-only, in [LICENSE](LICENSE). Version 3 of the Lesser GPL is written as
a set of additional permissions on top of the ordinary GPL rather than as a
standalone document, so a copy of that one is in [GPL-3.0.txt](GPL-3.0.txt) as
well. Both are needed to read either.

Parts of the pack loader and of the value catalogue are adapted from Iris, which
is the reference for what a pack expects. Iris is LGPL-3.0 as well, so nothing
about the licensing of this repository changes. Every file concerned is listed,
one by one, in [NOTICE](NOTICE).
