# Developing

This page describes how work on Vitrail is verified, and why the loop is shaped the way it is.
Most of it is the consequence of a single property, so that comes first.

## Most of the engine runs without the game

Reading a pack, translating its GLSL, and evaluating the uniforms it expects name no Minecraft
class. Those three source trees compile and run on their own, against a directory of real shader
packs, with no game process, no window and no GPU.

That is a deliberate constraint, not an accident, and it is worth understanding what it buys:

- A translator regression is caught in seconds, instead of through a play session.
- Every translation unit in a pack becomes a non-regression suite from the first line of a change.
- A failure names a translation unit. An in-game failure only names an image.

The cost of breaking it is larger than it looks. Importing a Minecraft class into one of those
trees does not merely add a dependency, it removes a whole class of checks from everything that
transitively touches it. Treat such an import as a design decision to be argued, not a convenience.

There is one deliberate exception: a single file refers to the mod's own entry class and is
excluded from the standalone build. One file is a maintainable seam. A growing list is a leak.

## What a contributor can run, and what they cannot

Be aware of an asymmetry before you start.

**The build gates travel with a clone.** They are declared in the build script, so they apply to
everyone, and they are described below.

**The corpus suite does not.** It runs against real shader packs, and shader packs are not
redistributable, so they are not in this repository and neither is the harness that drives them.
There is no hosted job that can run it either. It is local by construction, not by neglect.

The practical consequence: a change under the pack, translation or uniform trees can be argued
from the build and from reading, but the measurement that would settle it is one only a maintainer
with a pack corpus can take. Say which of the two your change rests on rather than leaving it
implied.

## What the out-of-game checks cover

By family, so you can tell whether a change is in scope:

- **Pack reading and settings expansion.** Which settings a pack declares and what its defines
  expand to. Everything else sits on this: a setting read wrongly silently changes which passes
  exist at all.
- **Translation and compilation of every unit**, handed to the same compiler front end the game
  uses, with the game's own options. Errors are classified rather than counted, so a regression
  names a construct.
- **Colour target allocation.** Declared formats, promotions and replacements, which targets get
  doubled, and which sampler names the backend would not know how to serve.
- **Frame chain and buffer parity.** Which pass reads which half of a doubled target. The invariant
  is one sentence: a read lands on the half produced by the last write before it, and on the
  primary half if there was none.
- **Vertex input contracts**, per geometry family, checked down to the location the element
  actually occupies in the compiled SPIR-V.
- **The uniform block.** Layout, coercion, the depth conversion against an independent matrix
  implementation, the expression grammar and its dependency graph.
- **Path confinement.** What a path written by a pack can reach. This one needs no corpus at all.

## What makes a measurement trustworthy

These rules are the difference between a number and evidence.

**The unit is the program with its stages linked, never the file.** A declaration written once in a
shared include reaches every program that includes it; a declaration behind a dead branch reaches
none. Counting files tells you neither, and the error runs in both directions.

**Liveness is measured, not guessed.** A declaration behind a branch the default settings do not
take is not a requirement, because the compiler never sees it - but it becomes one the moment a
setting moves. Dormant declarations are counted in their own column: folding them in inflates the
total, dropping them hides a future requirement.

**Requirements are read on the translated text, not the pack's text.** Three forms reach the
pipeline: a name the pack declares, a builtin the pack never declares and the translator emits
itself, and a legacy transform helper that expands into a matrix product and needs a position
without ever naming one. Only the translated text has all three.

**Every tool exits non-zero on the first broken invariant.** A tool that only prints is not a check
yet.

**Every invariant has a negative control, and the control ships with it.** Two exist as flags, each
documented as *must exit non-zero*, with the packs they are expected to break on named. Run them:
if a control reports nothing, the checker has stopped reading, and the green run beside it meant
nothing.

**A rule is confronted with a second, independent reading of itself.** The frame chain is rewritten
from the specification without consulting the classes that implement it, then the two answers are
placed side by side, pass by pass. Measuring a rule with the code that implements it proves
nothing; two separately written readings are the cheap way to catch an interpretation that is
wrong on both sides at once.

**A test is worth what it catches when you break the rule it watches.** When adding an assertion,
break the thing it guards once and confirm it fires. An unexercised check is not evidence.

**A tolerance is a contract, not a measurement.** Thresholds are set well above what the code
achieves, on purpose, and the measured figure goes in the assertion message so drift stays visible
while the check is still green.

## What the build refuses

`gradlew build` does not merely compile. It fails rather than prints, and everything is in the
build script, so it is systematic for anyone who clones. See CONTRIBUTING for the list; what
follows is why each gate exists.

**Compiler warnings, minus three categories.** Deprecation is off because the game and the loader
deprecate faster than a mod can follow, and the noise would bury everything else. The class-file
category is off because every occurrence comes from an annotation missing from a dependency's own
jar. The serialisation category is off because it asks for a serial id on exceptions nothing
serialises. Note the shape of those arguments: a category is excluded when its findings *cannot be
about this code*. "There are a lot of them" is not an argument.

**Javadoc reference and tag linting, as errors.** This matters more here than in most projects
because the documentation carries the design: a reference that stops resolving is a piece of the
design lost, and nothing says so until someone goes looking. What it caught in practice was exactly
that - a link pointing at a GLSL function name rather than the method that emits it, and orphaned
comments stacked above the wrong member, one of which asserted the opposite of its neighbour in the
same file. These are not cosmetic categories. They are rotten-documentation detectors.

**Static analysis, contributing only the checks it rates as errors** - the part of the catalogue
meant to be a bug rather than a preference. Its warnings are worth reading and not worth blocking
on, so a build flag prints them and lets the build through.

**A text check**, for the two things no compiler sees: a byte order mark, which reaches a GLSL
compiler as a stray character in front of the version directive; and typographic punctuation where
a straight quote or a plain hyphen is meant.

**The rule behind all of it:** a gate blocks only if it is objective and mechanically fixable.
Anything requiring taste stays in the editor. A check that cries wolf gets routinely bypassed, and
once bypassing is a habit, it protects nothing.

**Measure before arming a gate, not after.** Every gate here was run over the whole repository
first, and the count of genuine findings decided whether it became blocking. Propose a gate with
its finding list attached.

**And do not believe an empty report.** The first static-analysis run reported nothing, which was
false: the redirection sent standard error to the terminal while the compiler writes diagnostics
there. The result was only trusted after a planted defect - a self-assignment and a mistyped format
string - came back flagged. A new gate is proven by a planted defect, not by a clean run.

## The in-game loop

Some things have no text form to check: pipeline creation and binding, texture uploads, frame
timing, the shadow region following the player, and anything the game draws rather than a pack
program. Those are verified by running the game and reading its log.

The loop is scripted end to end - write the driving files, build, install, stop, relaunch, wait,
filter the log - and the parts worth knowing are these.

**Wait for a marker in the log, never for a fixed duration.** A duration is either too short, which
reads as a failure, or too long, which taxes every run. And the marker must be one that every
branch emits: waiting on a line only recent code prints means waiting out the whole timeout while
the game is in fact running and fine.

**Put the failure lines in the marker set too.** Compilation failure, nothing to draw, preparation
failure. Otherwise the case you most want to see quickly is the one that takes longest.

**Make every automation failure announce itself.** A quick-play argument naming a world that no
longer exists leaves the game at the menu silently, which reads as a slow start rather than an
error. That is the expensive kind of failure, and the loop is written to rule it out.

**Predict the run before launching it.** The chain tool accepts the same setting overrides the game
does and recomputes the schedule from them: how many passes, which targets end up doubled, which
are flipped at end of frame, which half the final pass reads. If the log does not match, the engine
has diverged from the plan, and the pack is not the suspect.

**Shader sources under the instance are read live.** Changing GLSL needs no rebuild. Only engine
changes need one.

## Rules that were paid for

Each of these cost a wrong conclusion before it was written down.

**Never judge a pass by eye without first reading what the shader does with its default settings.**
Two different packs produced an image judged correct while the effect under test was switched off
by default.

**To prove a pass runs, force one of the pack's own settings. Do not write a test shader.** Forcing
a setting exercises the real program through the real chain. A test shader proves that a test
shader runs.

**A black screen is also what a pass that does not draw produces.** Any test whose success
criterion is "nothing visible" has to be made self-verifying: split the output, put the quantity
under test on one half and on the other a witness that cannot be black by construction. Then the
result reads as "the witness drew and the error is below a threshold" rather than "the screen was
black". Better still, multiply a small error by a constant before displaying it, and a visual
result becomes a bound on the underlying quantity.

**A wrong buffer parity looks like a correct image.** A pass reading the wrong half of a doubled
target gets a clear colour or the previous frame's content. Both look like an image, nothing is
raised, and the screen becomes plausible and false. The in-game criterion does not prove parity;
only the out-of-game cross-check does.

**Wrong-way depth does not look wrong either.** It looks like an effect that is switched off. One
pack went a long time without blurring anything for exactly that reason.

**Silent output reordering produces a frame that looks like a frame.** The game does not preserve
the location a fragment stage declares: it asks SPIR-V reflection for the outputs and writes each
one's rank over the declared decoration, and reflection answers in order of *first use*, not of
declaration. A stage that writes its second output before its first ends up with them swapped, with
nothing in the log. The fix is structural - declare all outputs in the header from zero with no
gaps, and name each once in increasing order from a function called as the first statement of main,
so rank equals location again and the game's rewrite becomes the identity.

**Read the session log before reading the code.** The engine already announces most of what goes
wrong, and a defect in the image is usually explained by a line printed at the time.

**When two observations are compared, the artifact must prove they are simultaneous.** Reading a
smoothed value beside a biome name from captures taken moments apart nearly produced a wrong
conclusion about a lookup table, because the player was moving and the smoothing has a half-life of
about a second.

**Prefer the fact a test actually closes over the one you hoped it would.** A test that confirms
the sun is where it should be closes the time uniform, the rotation order and the model-view
matrix. It does not close the sun position uniform, because the pack recomputes its own direction.
Saying so is what stops the next reader treating it as covered.
