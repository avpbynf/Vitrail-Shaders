# Contributing

## Branches

`main` is the only long-lived branch and it stays buildable. Work on a topic branch, then rebase it
onto `main` and fast-forward. The history is linear and carries no merge commit at all. There is no
release branch.

A tag is `v` followed by whatever `mod_version` in `gradle.properties` holds, and that line
is where the version lives. Nothing derives one from the other: a human types the tag, and
the release workflow refuses it when the two disagree rather than publishing a jar named
after one and built from the other. The target Minecraft version is in the artifact name
and comes from the same file.

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

Comments in English, and there are a lot of them: the density is deliberate rather than
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
catalogue meant to be a bug rather than a preference. Two of its warnings are promoted to join them,
`StringSplitter` and `OperatorPrecedence`; the rest are worth reading and not worth blocking on, so
`gradlew build -PlintReport` prints them and lets the build through.

That flag lets those remaining warnings through and every compiler warning with them, since it drops
`-Werror`. A run under it is a listing, not a check, and the build says so on the way past. What it
cannot let through is anything javac calls an error, which is the javadoc lint and the two
promotions.

The first of the two is why every `split` here passes a limit: given a pattern and nothing else the
call cannot say which of two readings of an empty field it wants. Which reading each of the two
limits is, why either check was promoted, and what neither of them covers, are in
[developing](docs/developing.md).

`checkText` covers the two things no compiler sees: a byte order mark, which PowerShell writes
unless told not to, and typographic punctuation. Why each of those is a gate, and what the second
one really catches, is in [developing](docs/developing.md).

The vendored stareval sources under `uniform/expr/kroppeb/` are left out of the javadoc lint and
out of the static analyser, promotions included. The code is its author's, and bending borrowed
code to this project's taste only makes the next comparison with upstream harder to read. Nothing
guards that package, so a change made there is worth reading twice.

Run it before pushing rather than after. `main` staying buildable is a promise kept by whoever
pushes.

## Publishing

Pushing a tag is what publishes. Rewrite the pack table at the head of
[docs/compatibility.md](docs/compatibility.md) against whatever has been seen since the
last one, bump `mod_version` in `gradle.properties`, land those commits on `main` the way
every other commit lands, push `main`, then tag that commit `v` plus the same version and
push the tag. The order matters: a tag push carries its own objects and nothing else, so
tagging before the branch is pushed publishes a commit that is on no branch.

`.github/workflows/release.yml` takes it from there. It runs the same `gradlew build`
anyone runs, then creates a GitHub release under the tag's own name and attaches the jar
that build produced, so what is downloaded is what this history compiles rather than what
a machine had lying in `build/libs`.

The same job then mirrors it to CurseForge, which is why there is no second workflow: a
release this one creates is authored by the token it runs under, and such a release starts
no further workflow, so a file listening for it would never wake. The mirror needs two
secrets set in the repository settings, `CURSEFORGE_ID` and `CURSEFORGE_TOKEN`, and without
either it says so and ends green rather than failing a release over it. The id is not a
secret in any real sense (it is on the project's own page), and it lives there only so that
both halves of the same configuration are found in the same place.

The release body is what CurseForge is given as the changelog, read back at the moment the
run reaches it. A body written by hand after the tag went out therefore reaches CurseForge
by running the workflow again on that tag, from the Actions tab, and by no other road.

**Run it once for a given tag.** A second run rebuilds the same tag, and the archives here
are built to be reproducible, so it offers CurseForge a file whose hash it already holds;
CurseForge rejects a duplicate at processing while the run itself still ends green, which
means a changelog corrected on the second attempt quietly never lands. On the GitHub side
the same run replaces the asset rather than adding one, which resets what the old asset had
counted.

A version carrying an identifier after a dash, `0.2.0-rc.1` and the like, is published
as a pre-release. One without is published as a release.

The release body is GitHub's own generated notes, which list merged pull requests and
new contributors and end on a changelog link. This history is rebased and fast-forwarded
rather than merged through pull requests, so those lists are empty and the body arrives as
the link alone: the first release came out that way. Which is to say: a release worth
reading is one whose body is written by hand afterwards, which the release page takes
without rebuilding anything.

GitHub and CurseForge are the two places a build goes, and nothing else is automated.
