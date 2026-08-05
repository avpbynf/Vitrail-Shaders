# Contributing

## Branches

`main` is the only long-lived branch and it stays buildable. Work on a topic branch and
merge back when it compiles and runs. There is no release branch.

Nothing has been released and there are no tags yet, so there is no naming convention to
follow. The target Minecraft version is in the artifact name.

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
so that diffs against decompiled code stay readable.

Comments in English, and there are a lot of them: about a quarter of the lines under
`common/` are comment or javadoc, and that is the intended density rather than an
accident. A class says what it is for and what goes wrong without it, a line says why it
is where it is and what would break if it moved, and a figure says what was measured to
arrive at it. What is not wanted is the other kind, the comment that restates the line
below it.

## Verifying a change

Where a change lands decides how it can be checked, and the split is worth knowing before
writing anything.

`pack/`, `glsl/` and `uniform/` use no Minecraft class at all. That is deliberate: it lets
them be compiled and run on their own, outside the game, against a corpus of real packs,
which is how the translator is held to the 1863 translation units it is measured on and
how a regression is caught in seconds instead of in a play session. Keep the property. A
Minecraft import in one of those three packages costs more than it looks.

`render/` has no equivalent and cannot have one: it exists only inside a frame. A change
there is argued from the code and from the log it produces, and it is worth saying which
of the two a claim rests on rather than leaving it implied.

One rule is not negotiable, because a pack is downloaded content: a path a pack writes
never leaves the pack. `customTexture.x = ../../../secret.png` served an arbitrary PNG of
the disk to a shader until c1c50c0 confined it, the way an include was already confined.
Anything new that turns text from a pack into a file on disk goes through the same
confinement.

## Building

```
gradlew.bat build
```

Requires JDK 25. Artifacts land in `build/libs`.

## Publishing

Not set up yet. When it is, it will need the Modrinth and CurseForge project ids, a
changelog and the tag convention to generate it from, and a check that the built jar
declares the right Minecraft and loader ranges.
