# Installing

Vitrail is a client-only mod. It does nothing on a server and does not need to be
installed on one.

## Getting the jar

Built jars are attached to the [releases](https://github.com/avpbynf/Vitrail-Shaders/releases),
one per tag, built by the tag itself rather than uploaded by hand, and mirrored to
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/vitrail-shaders) by that
same run, so whichever of the two you take is the same file. A version
carrying an identifier after a dash is marked as a pre-release, which is what it
is. Building from source is below and gives the same thing for whatever commit you
are on.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.32-beta or later in the 26.2 line |
| Sodium | 0.9.x for NeoForge (`sodium-neoforge-0.9.1+mc26.2` or compatible) |
| Chloride | any 26.2 build, to run on the Vulkan backend at all |
| Java | 25, to build (the game brings its own runtime) |

Sodium and Chloride are both declared as required dependencies, so the game will
refuse to start without either of them. Do not update Sodium past 0.9.x: it has
no stable API for what a shader engine needs from it. Chloride is pinned to no
version at all, since nothing here calls into it.

Chloride is needed because without it the Vulkan backend has no window to draw
into. NeoForge shows an early loading screen, and the game takes that window over
rather than making one of its own, so the window it inherits was created for
OpenGL and the Vulkan surface fails at boot with `GLFW error 65540 ... requires
the window to have the client API set to GLFW_NO_API`. Chloride is what makes
that window Vulkan capable.

Worth knowing because the failure hides itself, and it hides itself twice over.
Asked for Vulkan and unable to bring it up, the game falls back to OpenGL within
the same run and keeps your setting, so `options.txt` goes on saying `vulkan`
while the session is not on it: the file cannot answer the question. And a game
that dies before it reaches the title screen leaves a mark that the *next* start
reads, which sets `preferredGraphicsBackend` back to `default` and saves over
whatever you had written. Either way Vitrail loads without drawing the world's
passes. It says which backend it came up on at startup, and says it as an error
when that backend is not Vulkan, so a picture that did not change is answered by
the log rather than by the file.

Some of Chloride's own settings decide what reaches this engine, in
`config/chloride-client.toml`, and they are entries inside its tables rather than
keys of their own. `tileEntities`, `entities` and `monsters`, under `[culling]`,
decide on their own which block entities and which mobs are drawn at all, by
distance: what they take out is never handed to the pack, so a chest, a sign or a
mob that is not there is those settings rather than anything the pack does.
`chests` and `beds`, under `[fastBlocks]`, draw those blocks by a path this
engine's final pass then covers over, so they go invisible with no other symptom.
Which of the five a given Chloride writes on is its own business and changes with
its versions, which is why none of them is described here as on or off.

There is nothing to go looking for by hand. Vitrail reads that file at startup
and, in the log, names each of them that is on with what it costs and what to set
it to, names any it could not find, and says so when the file itself is not
there.

Fabric is not supported. The module exists in the build but is empty.

## Other mods

Vitrail hooks the frame through public NeoForge events where the game offers
them, and through mixins where it does not. The load-bearing ones: the matrices
the world is really drawn with, which are never stored anywhere the camera
exposes; the game's sky renderer, which opens a pass of its own per sky element
and is handed the pack's program for that element, the colour targets the pack
sends it to, and the pack's word on whether that element is drawn at all; and
Sodium's chunk renderer, which is handed the pack's terrain programs, one extra
vertex element carrying the block id, and the render pass its draw buffers need.
Sodium has no API for any of that, which is why its version is pinned above.
Every family drawn since takes one or two more, and the whole list is the mixin
config shipped in the jar rather than anything summarised here.

Any mod that unwraps a GPU texture into an OpenGL handle will crash on the Vulkan
backend, with or without Vitrail. Distant Horizons 3.2.0-b does this and dies on
the first frame. This is not something Vitrail can work around.

## Building

```
gradlew.bat build
```

The first build decompiles Minecraft and takes a couple of minutes. After that it
is a few seconds. The jar lands in two places, they are identical, and it is named
for the version in `gradle.properties` and the Minecraft version beside it:

```
build/libs/vitrail-neoforge-<version>+mc<minecraft>.jar
neoforge/build/libs/vitrail-neoforge-<version>+mc<minecraft>.jar
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
out again, which is how a wrong picture is bisected without a rebuild. The names
it reads:

```
terrain=off      hands the chunk passes back to the game's own shader
shadow=off       stops the second pass over the world from the light
sky=off          hands the sky back to the game's own shaders
clouds=off       hands the clouds back too, and with them the pack's own
                 clouds directive, which most packs use to remove them
weather=off      hands the rain and the snow back to the game's own shader,
                 and with them the pack's own weather directive
particles=off    hands the quad particles back too, both halves of them
entities=off     hands the mobs and the block entities back, lit by the game
                 and carried in flat by the scene seed, and with them the glint
                 an enchantment puts over anything they hold or wear
hand=off         leaves the player's own hand where the game draws it, after
                 the whole chain has run, and the glint over what it holds
                 with it
chain=off        stops the composites and the final from drawing at all
seed=off         stops the game's finished frame being painted in under the chain
passes=N         cuts the chain to its first N passes, or to a list of names
dump=NAME        prints the values one program was handed, decoded
screen=settings  opens the settings screen on the pack rather than on the list
```

Every one of those that is a yes or a no is on until it is taken out: what this
engine can serve it serves, and only a disabling is written down. The last three
are values rather than switches and do nothing unless written.

One more name is held back rather than handed to the pack as a setting:
`profile=NAME` picks a whole profile the pack declares, and the settings screen
greys its own profile selector out for as long as that line is there. Everything
else in the file is a setting of the pack, by its own name.

Each of the switches is a stage that can be taken in or out on its own, which
is what tells a wrong gbuffer from a wrong composite. `dump=` is the one that
answers what no picture can, since a value can be non zero, plausible and wrong.

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

The same setting is reachable in game under Options, Video Settings, where it is
called Graphics API and the entry to pick reads "Prefer Vulkan (Experimental)".
Either way the change only takes effect on the next start, which the game says
itself when you pick it.

## Going back

To return to OpenGL, set the line back:

```
preferredGraphicsBackend:"opengl"
```

To remove Vitrail entirely, delete its jar from `mods/`, and delete the `vitrail/`
folder if you do not want to keep the shader files. Nothing else is written and no
game file is modified, so putting Iris back is just a matter of dropping its jar
in again.
