# Installing

Vitrail is a client-only mod. It does nothing on a server and does not need to be
installed on one.

## Getting the jar

The built jar is attached to the [releases](https://github.com/avpbynf/Vitrail-Shaders/releases),
one per tag, built by the tag itself rather than uploaded by hand, and mirrored to
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/vitrail-shaders) by that
same run, so whichever of the two places you take it from serves the same file. A version
carrying an identifier after a dash is marked as a pre-release, which is what it
is. Building it yourself gives the same thing for whatever commit you are on, and
[CONTRIBUTING.md](CONTRIBUTING.md) says how.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.32-beta or later in the 26.2 line |
| or Fabric Loader | 0.19.3 or later, with Fabric API |
| Sodium | 0.9.x, the build for whichever loader is in front |
| Java | 25, to build (the game brings its own runtime) |

One jar for both loaders: each loader reads its own metadata out of it and
ignores the rest. On Fabric, two modules of Fabric API are declared as
required, and they are the whole of what this mod takes from it: the key
mapping and the client tick, which is what the settings screen is opened by.
Nothing of the world's rendering goes through Fabric API.

Sodium is declared as a required dependency, so the game will refuse to start
without it. Do not update it past 0.9.x: it has no stable API for what a shader
engine needs from it.

## Installing into an instance

Copy the jar into the `mods` folder of a NeoForge or Fabric 26.2 instance, next
to Sodium. For a CurseForge instance that is:

```
<instances>/<instance name>/mods/
```

Remove any other shader engine from that folder first. Iris and Vitrail both want
to own the frame, and there is no reason to have both. An older Vitrail goes too:
the per-loader `vitrail-neoforge-*` and `vitrail-fabric-*` jars carry the same
mod as the merged one, and a loader that finds it twice refuses to start.

Shader packs go into the `shaderpacks/` folder at the root of the instance, the
same folder OptiFine and Iris use, zipped or unpacked. Two files of the mod's own
live in a `vitrail/` folder next to `mods/`. It writes `pack.txt` itself, which is
which pack is loaded and whether shaders are on at all. It only ever reads
`options.txt`, the engine's own switches, which is yours to write. A pack's own
settings are kept in neither. They go beside the pack, in the file the reference
engine reads and writes for the same pack, so they follow you from one engine to
the other. What each of those files holds is in
[the settings screen](docs/settings-screen.md).

None of them has to exist, and without a pack picked nothing is drawn. The screen
that picks one is in the video settings, under Vitrail in the list of pages, on the
`I` key, or, on NeoForge, behind the Config button in the mod list. The pack folder
is watched, so a pack dropped into it turns up in the list on its own. Nothing
inside a pack is watched: edit a shader by hand while the game runs, press `R`, and
the pack is read again from disk, whole. The jar never needs rebuilding for any of
it.

## Switching the graphics backend to Vulkan

Vitrail targets the Vulkan renderer. With the game closed, edit `options.txt` at
the root of the instance:

```
preferredGraphicsBackend:"vulkan"
```

The same setting is reachable in game under Options, Video Settings, where it is
called Graphics API and the entry to pick reads "Prefer Vulkan (Experimental)".
Either way the change only takes effect on the next start, which the game says
itself when you pick it.

**A game that came up on the wrong backend hides it, twice over.** Asked for
Vulkan and unable to bring it up, the game falls back to OpenGL within the same
run and keeps your setting, so `options.txt` goes on saying `vulkan` while the
session is not on it: the file cannot answer the question. And a game that dies
before it reaches the title screen leaves a mark that the *next* start reads,
which sets `preferredGraphicsBackend` back to `default` and saves over whatever
you had written. Either way Vitrail loads, reads the pack and draws nothing of
it: the game keeps its own image. So the answer is on the screen rather than in
the file. The mod says which backend the game came up on at startup, says it as
an error when that backend is not Vulkan, and says it once more in chat the first
time a world is shown, naming the setting to change.

## Other mods

Vitrail hooks the frame through public NeoForge events where the game offers
them, and through mixins where it does not, which on Fabric is everywhere: the
points a NeoForge event is posted at are reached there by a mixin on the very
line that posts it, so both loaders run the same ordered work. Sodium's chunk
renderer is one of those hooks and has no API for any of it, which is why its
version is pinned above. The whole list is the mixin config shipped in the jar.

**Chloride** is no longer required. What it was load bearing for, the OpenGL
loading window NeoForge opens before the game exists, Vitrail refuses on its own
now, and the two refusals nest if you run both. What Chloride does change is what
reaches this engine: five entries of `config/chloride-client.toml` decide it.
`tileEntities`, `entities` and `monsters`, under `[culling]`, take block entities,
mobs and other entities out by distance before the pack is ever asked, so a chest,
a sign, a boat or a mob that is not there is those settings rather than anything
the pack does.
`chests` and `beds`, under `[fastBlocks]`, draw those blocks by a path this
engine's final pass then covers over, so they go invisible with no other symptom.
Which of the five a given Chloride writes on is its own business and changes with
its versions, which is why none of them is described here as on or off: with
Chloride installed, Vitrail reads that file at startup and names in the log each
one that is on, with what it costs and what to set it to.

**Distant Horizons** works, and which build depends on the loader. On Fabric,
3.2.0-b installs from the launcher like any other mod. On NeoForge that same build
unwraps a GPU texture into an OpenGL handle as the lightmap renders, its NeoForge
wrapper alone doing so, and dies on the first frame of a world with a
`ClassCastException` naming `VulkanGpuTexture`: there the [patched build][dh-build]
is the one to install, by hand and with no other Distant Horizons jar beside it.
It reports itself as `3.2.1-b-dev`, the same string an upstream development build
carries, so bug reports made with it belong on this tracker and not with the
Distant Horizons team. While a pack is up, Vitrail hands its far terrain to the
pack's own programs, and serves its depth beside the world's, which is the
arrangement packs are written against. With shaders off, that mod draws its far
terrain itself and this engine is not involved.

[dh-build]: https://gitlab.com/avpbynf/distant-horizons/-/releases/vitrail-26.2-1

## Going back

To return to OpenGL, set the line back:

```
preferredGraphicsBackend:"opengl"
```

To remove Vitrail entirely, delete its jar from `mods/`, and delete the `vitrail/`
folder if you do not want to keep the shader files. Nothing else is written and no
game file is modified, so putting Iris back is just a matter of dropping its jar
in again.
