# Installing

Vitrail is a client-only mod. It does nothing on a server and does not need to be
installed on one.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.32-beta or later in the 26.2 line |
| Sodium | 0.9.x for NeoForge (`sodium-neoforge-0.9.1+mc26.2` or compatible) |
| Java | 25, to build (the game brings its own runtime) |

Sodium is declared as a required dependency, so the game will refuse to start
without it. Do not update Sodium past 0.9.x: it has no stable API for what a
shader engine needs from it.

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
pack.txt       which pack of the folder to load, by whole or partial name
options.txt    engine switches, one NAME=value per line; wins over the settings
settings/      one file per pack, holding what differs from the pack's defaults
```

A settings screen covers all of it in game: the I key, the Config button in the
mod list, or the icon in the pause menu. It opens on the pack list, reads each
pack's own menu layout, and imports the settings file Iris left in
`shaderpacks/` when a pack has none here yet. Editing a pack's files or the
`vitrail/` ones while the game runs is also enough, changes are picked up
without a restart; the jar never needs rebuilding for any of this. A program
that fails to compile is reported in the log and the game keeps its own
rendering rather than crashing.

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
