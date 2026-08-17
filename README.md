<p align="center">
  <img src="common/src/main/resources/vitrail.png" width="128" alt="">
</p>

<h1 align="center">Vitrail Shaders</h1>

<p align="center">
  OptiFine-format shader packs, on Minecraft's native Vulkan renderer.
</p>

<p align="center">
  <a href="https://www.curseforge.com/minecraft/mc-mods/vitrail-shaders"><img src="https://img.shields.io/badge/download-CurseForge-F16436?style=flat-square&logo=curseforge&logoColor=white" alt="Vitrail on CurseForge"></a>
  <img src="https://img.shields.io/badge/status-experimental-C1440E?style=flat-square" alt="Experimental">
</p>

---

<p align="center">
  <img src="docs/images/screenshot-ocean-ruins.jpg" alt="Sunken ruins in deep water, light shafts through the murk and drowned standing in it, rendered by Complementary Shaders on the Vulkan backend" width="830">
</p>
<p align="center">
  <sub>Complementary Shaders, running unmodified on the Vulkan backend.</sub>
</p>

<details>
<summary>More screenshots</summary>
<br>
<p align="center">
  <img src="docs/images/screenshot-end.jpg" alt="A chorus forest across the end stone, endermen standing among it, rendered by Complementary Shaders on the Vulkan backend" width="830">
</p>
<p align="center">
  <sub>Complementary Shaders.</sub>
</p>
<p align="center">
  <img src="docs/images/screenshot-cherry.jpg" alt="A cherry grove over a valley with waterfalls, rendered by BSL Shaders on the Vulkan backend" width="830">
</p>
<p align="center">
  <sub>BSL Shaders.</sub>
</p>
<p align="center">
  <img src="docs/images/screenshot-underwater.jpg" alt="Looking up at the sun from under the sea, shafts of light spreading from the surface, rendered by Bliss Shaders on the Vulkan backend" width="830">
</p>
<p align="center">
  <sub>Bliss Shaders.</sub>
</p>
</details>

Minecraft 26.2 ships a native Vulkan renderer alongside the OpenGL one. The
shader ecosystem grew up on OpenGL and has not crossed over yet: the packs, the
engines that load them and the habits of the people who write them all assume
that renderer.

Vitrail keeps the two sides compatible while that changes. It loads existing
OptiFine-format packs, unmodified, on the Vulkan backend, so that turning Vulkan
on does not mean giving up the packs you already use.

It is a side project by one person, and it started as a question: whether packs
written for OpenGL over more than a decade could be made to run, untouched, on
the renderer that now ships with the game. The answer turned out to be yes, well
enough to play with.

## Status

The backend this runs on is marked experimental by the game itself, and Vitrail
is earlier still. Point it at a pack and it loads it, settings and all,
translates its programs once, and runs its frame on the Vulkan backend in the
order the format prescribes. Drawn through the pack's own programs today: the
world's terrain and water, the shadow map, the sky in the overworld and in the
End, the overworld's clouds, the weather, the particles, the entities, which
covers the mobs and the block entities alike, and the player's own hand. A settings screen reads the pack's own
menu layout, and a resource pack's normal and specular maps are served beside the
blocks they belong to.

Several families still come from the game, and a few of the ones that do go
through reach the pack with an identifier held constant, which shows as a mob
shaded like something it is not. Rather than a list here that would go stale
between releases, the engine states it itself: when a place first draws, it logs
which families still come from the game. That line is the authority, and
[pack compatibility](docs/compatibility.md) starts from what you are seeing and
names the cause.

So packs run but do not yet look entirely like themselves, and the picture a
given pack produces changes from one release to the next. If what you want today
is a finished picture, use [Iris](https://github.com/IrisShaders/Iris) on the
OpenGL backend instead. It does that job well, and it is the reference this
engine is checked against. Vitrail is for people who want the Vulkan backend on
and will trade image quality for it in the meantime.

## Quick start

There is one jar per loader, NeoForge and Fabric. Both are on
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/vitrail-shaders) and
attached to every [release](https://github.com/avpbynf/Vitrail-Shaders/releases)
here, put there by the same run, so whichever of the two you take is the same
file.

Put the one for your loader in `mods/` next to Sodium and Chloride, and switch
the game to Vulkan: `preferredGraphicsBackend:"vulkan"` in `options.txt`, or
Options, then Video Settings, in game. Nothing enforces that switch, though the
mod says at startup which backend the game came up on, and says so as an error
when it is not Vulkan. Shader packs go in `shaderpacks/`, as they always have,
and are selected in game from Vitrail's settings screen.

[INSTALL.md](INSTALL.md) has the versions this needs, the Chloride settings that
have to come off, and what the absence of either looks like. Client only: it does
nothing on a server and does not need to be installed on one.

To build from source:

```
gradlew build
```

Both jars land in `build/libs`.

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

## Read more

| If you want to | Read |
| --- | --- |
| Know why the format is OptiFine's, and how this sits next to Iris, Sulkan and Aperture | [Why this exists](docs/why.md) |
| Work out why your pack looks wrong, starting from what you see | [Pack compatibility](docs/compatibility.md) |
| See what changed from one version to the next | [CHANGELOG.md](CHANGELOG.md) |
| Install it, and know what it refuses to run beside | [INSTALL.md](INSTALL.md) |
| Understand how any of this works | [The documentation](docs/README.md) |

## Contributing

Open an issue before writing anything substantial. I order the work by risk, and
code that lands ahead of what can be verified is hard to accept however good it
is.

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
about the licensing of this repository changes. What was taken, from where, and
what was changed on the way is recorded in [NOTICE](NOTICE).
