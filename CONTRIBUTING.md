# Contributing

## Branches and releases

`main` is the only long-lived branch and it stays buildable. Work on a topic branch and
merge back when it compiles and runs.

Releases are tags on `main`, named `v<major>.<minor>.<patch>`, with the target Minecraft
version in the artifact name rather than in the tag. There is no release branch.

## Commits

One logical change per commit. Short imperative subject, no trailing period, 72 columns
or less. A body only when the reason is not obvious from the diff, and then it explains
why rather than what.

```
Add fullscreen pass hook for the Vulkan backend
Fix binding allocation colliding on location 7
```

No merge commits from `main` into a topic branch, rebase instead. No trailers.

## Encoding and text

UTF-8 without BOM everywhere, accents included. A BOM breaks several tools in this
project's toolchain, in particular anything that feeds files to a GLSL compiler.

Line endings are normalised to LF in the repository by `.gitattributes`. Do not commit
files with CRLF.

## Code

Java: standard formatting, tabs for indentation to match Minecraft and Sodium sources,
so that diffs against decompiled code stay readable. Comments in English, sparingly, and
only where the reason for the code is not evident.

GLSL: parameters that change the rendering go in a marked block at the top of the file,
one `#define` per setting with its unit and useful range. Anything a user might want to
tune must be reachable without reading the shader body.

## Building

```
gradlew.bat build
```

Requires JDK 25. Artifacts land in `build/libs`.

## Publishing

Not set up yet. When it is, it will need the Modrinth and CurseForge project ids, a
changelog generated from tags, and a check that the built jar declares the right
Minecraft and loader ranges.
