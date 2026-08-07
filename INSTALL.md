# Installing

Vitrail is a client-only mod. It does nothing on a server and does not need to be
installed on one.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.32-beta or later in the 26.2 line |
| Sodium | 0.9.x for NeoForge (`sodium-neoforge-0.9.1+mc26.2` or compatible) |
| Chloride | any 26.2 build, to run on the Vulkan backend at all |
| Java | 25, to build (the game brings its own runtime) |

Sodium is declared as a required dependency, so the game will refuse to start
without it. Do not update Sodium past 0.9.x: it has no stable API for what a
shader engine needs from it.

Chloride is not declared as a dependency and is needed all the same, because
without it the Vulkan backend has no window to draw into. NeoForge shows an
early loading screen, and the game takes that window over rather than making one
of its own, so the window it inherits was created for OpenGL and the Vulkan
surface fails at boot with `GLFW error 65540 ... requires the window to have the
client API set to GLFW_NO_API`. Chloride is what makes that window Vulkan
capable.

Worth knowing because the failure hides itself: a Vulkan boot that fails
downgrades `preferredGraphicsBackend` to `default`, and the dying process writes
`options.txt` on the way out, after and over any edit you made. The game then
starts in OpenGL and Vitrail loads and compiles without drawing the world's
passes. If the picture is unchanged, check the backend in the log before
anything else.

Two of Chloride's own settings have to come off in `config/chloride-client.toml`.
`fastBlocks` draws chests and beds by a path this engine's final pass then covers
over, so they go invisible with no other symptom; `culling.tileEntities` decides
on its own which of the same objects are drawn at all, and comes off with it.

Fabric is not supported. The module exists in the build but is empty.

## Building

```
gradlew.bat build
```

The first build decompiles Minecraft and takes a couple of minutes. After that it
is a few seconds. The jar lands in two places, they are identical:

```
build/libs/vitrail-neoforge-0.1.0+mc26.2.jar
neoforge/build/libs/vitrail-neoforge-0.1.0+mc26.2.jar
```

To run the mod in a development client instead of installing it:

```
gradlew.bat :neoforge:runClient
```

## Installing into an instance

Copy the jar into the `mods` folder of a NeoForge 26.2 instance, next to Sodium.
For a CurseForge instance that is:

```
<instances>/<instance name>/mods/
```

Remove any other shader engine from that folder first. Iris and Vitrail both want
to own the frame, and there is no reason to have both.

Shader packs go into the `shaderpacks/` folder at the root of the instance, the
same folder OptiFine and Iris use, zipped or unpacked. The mod keeps its own
files in a `vitrail/` folder next to `mods/`:

```
pack.txt       which pack of the folder to load, by whole or partial name,
               or none to load nothing at all
options.txt    engine switches, one NAME=value per line; wins over the settings
```

What you change in a pack's own settings screen is not kept there. It goes beside
the pack, in `shaderpacks/<pack file name>.txt`, which is the file Iris reads and
writes for the same pack: one file per pack, shared, so settings follow you from
one engine to the other.

Earlier versions kept those settings in `vitrail/settings/` instead. The first time
a pack is loaded after the move its old file is read, written out beside the pack,
and renamed to `.txt.migrated` on the spot. Nothing is asked of you, and a profile
you had chosen comes over too: the old file stored only what differed from it, so
the profile is turned back into the values it names on the way.

Two cases leave your old file exactly where it is, both named in the log. One is a
file naming a profile the pack no longer declares, because what it stored is only
the difference from a set of values nothing can rebuild; the log gives the profile
name. The other is a file that could not be read or written at all, usually a folder
this game cannot write to. Neither loses anything, and both are tried again at the
next load.

None of these files has to exist, and without them nothing is drawn: a pack is
loaded once one is picked, in the screen or in `pack.txt`, and never before.
What is picked is then drawn whole. `options.txt` is there to take a stage back
out again, which is how a wrong picture is bisected without a rebuild. It reads
nine names:

```
terrain=off      hands the chunk passes back to the game's own shader
shadow=off       stops the second pass over the world from the light
sky=off          hands the sky back to the game's own shaders
entities=on      draws the opaque entities with the pack's own program.
                 The one line here that is OFF unless it is written
chain=off        stops the composites and the final from drawing at all
seed=off         stops the game's finished frame being painted in under the chain
passes=N         cuts the chain to its first N passes, or to a list of names
dump=NAME        prints the values one program was handed, decoded
screen=settings  opens the settings screen on the pack rather than on the list
```

One more name is held back rather than handed to the pack as a setting:
`profile=NAME` picks a whole profile the pack declares, and the settings screen
greys its own profile selector out for as long as that line is there. Everything
else in the file is a setting of the pack, by its own name.

Each of the first six is a stage that can be taken in or out on its own, which is
what tells a wrong gbuffer from a wrong composite. `dump=` is the one that answers
what no picture can, since a value can be non zero, plausible and wrong.

A settings screen covers all of it in game: the I key, the Config button in the
mod list, or the icon in the pause menu. It opens on the pack list, reads each
pack's own menu layout, and imports the settings file Iris left in
`shaderpacks/` when a pack has none here yet. The list starts with None, which
turns every pack off and leaves the game drawing its own image. A setting reaches
the world when Apply is pressed and at no other moment: leaving the screen drops
what was clicked and never applied. A program that fails to compile is reported
in the log and the game keeps its own rendering rather than crashing.

Nothing is watched for changes, neither `vitrail/` nor `shaderpacks/`. Edit any
of it by hand while the game runs, then press Reload in the settings screen and
the pack is read again from disk, whole; the jar never needs rebuilding for any
of this.

## Switching the graphics backend to Vulkan

Vitrail targets the Vulkan renderer. With the game closed, edit `options.txt` at
the root of the instance:

```
preferredGraphicsBackend:"vulkan"
```

The same setting is reachable in game under Options, Video Settings; either way the
change only takes effect on the next start.

## Going back

To return to OpenGL, set the line back:

```
preferredGraphicsBackend:"opengl"
```

To remove Vitrail entirely, delete its jar from `mods/`, and delete the `vitrail/`
folder if you do not want to keep the shader files. Nothing else is written and no
game file is modified, so putting Iris back is just a matter of dropping its jar
in again.
