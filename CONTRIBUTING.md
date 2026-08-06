# Contributing

## Branches

`main` is the only long-lived branch and it stays buildable. Work on a topic branch, then rebase it
onto `main` and fast-forward. The history is linear and carries no merge commit at all. There is no
release branch.

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

No trailers.

## Encoding and text

UTF-8 without BOM everywhere, accents included. A BOM breaks several tools in this
project's toolchain, in particular anything that feeds files to a GLSL compiler.

Line endings are normalised to LF in the repository by `.gitattributes`. Do not commit
files with CRLF.

## Code

Java: standard formatting, tabs for indentation to match Minecraft and Sodium sources,
so that diffs against decompiled code stay readable.

Comments in English, and there are a lot of them - the density is deliberate rather than
accidental. A class says what it is for and what goes wrong without it, a line says why it
is where it is and what would break if it moved, and a figure says what was measured to
arrive at it. What is not wanted is the other kind, the comment that restates the line
below it.

## Verifying a change

Where a change lands decides how it can be checked, and the split is worth knowing before
writing anything.

`pack/`, `glsl/` and `uniform/` use no Minecraft class at all. That is deliberate: it lets
them be compiled and run on their own, outside the game, against a corpus of real packs, which is
how a translation regression is caught in seconds instead of in a play session. Keep the property. A
Minecraft import in one of those three packages costs more than it looks.

There is exactly one exception, and it is worth knowing before you try the standalone compile: a
single file reaches out of the three trees for the logger, and the out-of-game build drops that file
rather than dragging the rest in behind it.

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

The JDK it wants is pinned in `gradle.properties`, along with the Minecraft and loader versions.
Artifacts land in `build/libs`.

## What the build refuses

`gradlew build` is also the check, and it fails on warnings rather than printing them. Not on
all of them: four categories are off. Deprecation, because Minecraft and NeoForge deprecate faster
than a mod can follow and the noise would bury everything else; annotation processing, which reports
which processor claimed what and is a property of how the build is wired; the category that only
ever reports annotations missing from a dependency's own jar; and the one that asks for a
serialVersionUID on exceptions nothing serialises.

Javadoc is linted for everything but missing comments, so broken references, malformed tags,
malformed HTML and accessibility all fail the build. That matters more here than it would elsewhere.
The javadoc carries the design, so a reference that no longer resolves is a piece of the design lost,
and nothing says so until someone goes looking.

Error Prone runs alongside javac and contributes the checks it rates as errors, the part of its
catalogue meant to be a bug rather than a preference. Its warnings are worth reading and not
worth blocking on, so `gradlew build -PlintReport` prints them and lets the build through.

That flag lets every other warning through with them: it drops `-Werror`, so the compiler warnings
stop failing the build as well. A run under it is a listing, not a check, and the build says so on
the way past. The javadoc lint is the exception and stays armed, its findings being errors rather
than warnings.

`checkText` covers the two things no compiler sees: a byte order mark, which PowerShell writes
unless told not to, and typographic punctuation. Why each of those is a gate, and what the second
one really catches, is in [developing](docs/developing.md).

The vendored stareval sources under `uniform/expr/kroppeb/` are left out of the javadoc lint.
Their javadoc is their author's, and bending borrowed code to this project's taste only makes the
next comparison with upstream harder to read.

Run it before pushing rather than after. `main` staying buildable is a promise kept by whoever
pushes.

## Publishing

Not set up yet. When it is, it will need the Modrinth and CurseForge project ids, a
changelog and the tag convention to generate it from, and a check that the built jar
declares the right Minecraft and loader ranges.
