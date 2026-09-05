# Developing

This page describes how work on Vitrail is verified, and why the loop is shaped the way it is.
Most of it is the consequence of a single property, so that comes first.

## Most of the engine runs without the game

Reading a pack, translating its GLSL, evaluating the uniforms it expects and reading its
settings files name no Minecraft class. Those four source trees compile and run on their own,
against a directory of real shader packs, with no game process, no window and no GPU.

That is a deliberate constraint, not an accident, and it is worth understanding what it buys:

- A translator regression is caught in seconds, instead of through a play session.
- Every translation unit in a pack becomes a non-regression suite from the first line of a change.
- A failure names a translation unit. An in-game failure only names an image.

The cost of breaking it is larger than it looks. Importing a Minecraft class into one of those
trees does not merely add a dependency, it removes a whole class of checks from everything that
transitively touches it. Treat such an import as a design decision to be argued, not a convenience.

There are two deliberate exceptions, both excluded from the standalone build: the report a pack
gets at its first load, which writes through the mod's own logger, and the choice of graphics
backend, which is a session's and reads the game's options. Two files are a maintainable seam.
A growing list is a leak.

## What a contributor can run, and what they cannot

Be aware of an asymmetry before you start.

**The build gates travel with a clone.** They are declared in the build script, so they apply to
everyone, and they are described below.

**The corpus suite does not.** It runs against real shader packs, and shader packs are not
redistributable, so they are not in this repository. The harness that drives them is absent for a
separate reason: it lives outside the versioned tree, so a hosted job could neither run it nor even
compile it, and wiring it in as a source set would break a fresh clone that has none of it. There is
no hosted job that can run it either. It is local by construction, not by neglect.

The practical consequence: a change under the pack, translation, uniform or settings trees can be
argued from the build and from reading, but the measurement that would settle it is one only a
maintainer with a pack corpus can take. Say which of the two your change rests on rather than
leaving it implied.

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
- **The uniform block.** Layout, coercion, and the depth conversion against an independent matrix
  implementation: none of which needs a pack. The expression grammar and its dependency graph are
  checked the same way, then run over the declarations the corpus really contains, which does.
- **Path confinement.** What a path written by a pack can reach.

Two of those run on a bare clone: the uniform block invariants and path confinement. Everything else
wants the corpus.

## What makes a measurement trustworthy

These rules are the difference between a number and evidence.

**The unit is the program with its stages linked, never the file.** A declaration written once in a
shared include reaches every program that includes it; a declaration behind a dead branch reaches
none. Counting files tells you neither, and the error runs in both directions.

**Liveness is measured, not guessed.** A declaration behind a branch the default settings do not
take is not a requirement, because the compiler never sees it, but it becomes one the moment a
setting moves. Dormant declarations are counted in their own column: folding them in inflates the
total, dropping them hides a future requirement.

**Requirements are read on the translated text, not the pack's text.** Three forms reach the
pipeline: a name the pack declares, a builtin the pack never declares and the translator emits
itself, and a legacy transform helper that expands into a matrix product and needs a position
without ever naming one. Only the translated text has all three.

**A tool that asserts exits non-zero on the first broken invariant; a tool that only prints is not a
check yet.** Both kinds live here, and telling them apart is the reader's job before quoting either:
one is a gate, the other is a reading. Several of the measuring tools (the ones that report how much
of the corpus translates, or what each target resolves to) never fail at all by design, because
what they produce is a number to compare against the last one rather than a yes or a no. Running one
of those and seeing no error means nothing was asserted, not that everything held.

**Every invariant has a negative control.** Two of them ship as a flag on the tool they belong to,
documented as *must exit non-zero* and naming the packs they are expected to break on; the rest are
gestures spelled out beside the tool, a forced setting or a line put back, to be taken by hand.
Those tools live on the maintainer's bench rather than in this tree, so what a reader here can take
away is the rule itself: if a control reports nothing, the checker has stopped reading, and the
green run beside it meant nothing. What controls exist is in what the tools print, which cannot
fall out of date the way a list here would.

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

**Compiler warnings, minus four categories.** Deprecation is off because the game and the loader
deprecate faster than a mod can follow, and the noise would bury everything else. The annotation
processing category is off because it reports which processor claimed what, which is a property of
how this build is wired and not of anything written here. The class-file category is off because
every occurrence comes from an annotation missing from a dependency's own jar. The serialisation
category is off because it asks for a serial id on exceptions nothing serialises. Note the shape of
those arguments: a category is excluded when its findings *cannot be about this code*. "There are a
lot of them" is not an argument.

**Javadoc linting as errors, everything but the missing-comment category**, so references, tags,
malformed HTML and accessibility all fail the build. This matters more here than in most projects
because the documentation carries the design: a reference that stops resolving is a piece of the
design lost, and nothing says so until someone goes looking. What it caught in practice was exactly
that: a link pointing at a GLSL function name rather than the method that emits it, and orphaned
comments stacked above the wrong member, one of which asserted the opposite of its neighbour in the
same file. These are not cosmetic categories. They are rotten-documentation detectors.

There is one exemption, and it is a package: the vendored expression evaluator keeps its author's
javadoc. Bending borrowed code to this project's taste buys nothing and makes the next comparison
with upstream harder. A contributor working in that package is not caught by this gate.

That lint runs on the compiler, so it runs on every build, and the `javadoc` task is deliberately
not part of `check`: it would run the same doclint over the same sources a second time and catch
nothing the compile did not. It is configured all the same, with the missing-comment category off
and the same package exempt, because a task left to its own defaults disagrees with the gate on
both counts. It then fails on the vendored evaluator and reports the missing comments as a wall of
warnings, and whoever ran it reads a hole in the gate where the gate made a decision.

**Static analysis, contributing the checks it rates as errors** (the part of the catalogue meant to
be a bug rather than a preference) **and two of its warnings promoted to join them.** The rest are
worth reading and not worth blocking on, so a build flag prints them and lets the build through.

**A promotion is argued from the whole report, and by the same test a lint category has to pass:**
the findings have to be about this code. Two passed it, and both for the same reason, which is not
that they found a bug: neither of them did. It is that the line as written could not tell a reader
which of two behaviours it wanted.

`String.split` given a pattern and nothing else drops the empty fields at the *end* of a value and
keeps the ones in the middle, and answers a value that is nothing but separators with an *empty
array*, which is the half that throws. A handful of calls here depend on that drop and lose their
meaning without it; most are indifferent; none of them said which, because the one-argument form
cannot. `split(x, 0)` is the dropping reading and `split(x, -1)` the keeping one, so every call
writes the one it means. Beware the tool's own advice here, which is to reach for Guava's
`Splitter`: nothing in this tree uses Guava, and a limit is the smaller answer. The other check is
about a line a person wrote. It reports two shapes, and the milder one is `&&` mixed into `||`,
where the precedence is conventional and the parentheses only spare the reader a lookup. The one
worth the gate is a condition in front of a ternary, which parses as `(a || b) ? x : y` and reads as
`a || (b ? x : y)`. Nothing was wrong there either, and that was the point.

**Know what a gate does not cover before quoting it as one.** Three holes here, and each was
measured rather than reasoned about. The splitter check reports a call only where it can follow
every use of the array, and goes quiet as soon as the array is handed to another method: calls
written that way sat in this tree and in no report, and were found by grep. And the whole analyser
is pointed away from two packages, so a split written under `vitrail/mixin/` or under the vendored
`uniform/expr/kroppeb/` compiles green whatever the severity says. The second of those is the
deliberate price of leaving borrowed code as its author wrote it, and it is the one that hides a
parser. What keeps all three shapes honest is reading, not the build.

**Do not act on a dead-code finding without checking who calls it from outside the build.** Two
whole families of method here are called by something the analyser cannot see. The loader calls into
the mixins by reflection, and their parameters match the target whether the body reads them or not;
the out-of-game tools are not a module of this build at all, so anything only they call reads as
dead. Deleting one of those breaks something in silence, which is why the analyser is pointed away
from the mixin package rather than argued with case by case.

**That flag disarms every warning, not just the analyser's.** Asking for the report drops `-Werror`,
so the compiler categories above stop failing too: a run under it is a listing and not a check, and
a green one says nothing about whether an ordinary build passes. The build prints that itself
whenever the flag is on, rather than leaving it to be remembered from here.

**What it cannot disarm is anything javac reports as an *error*,** since `-Werror` has nothing to
say about those. Doclint is one, and so are the two promoted checks: a split written the short way
fails the build under the report flag as readily as without it. Worth knowing as a shape as well as
a fact: "this flag turns the checks off" is a claim about a build, and a build is the one thing that
answers it if you plant a defect and ask.

**Ask it of a compile that actually runs.** The listing and the warning the flag prints are produced
by the compiler, so a tree Gradle finds up to date or restores from the cache prints neither and
exits zero. That is a green run saying nothing whatever, which is the same trap as the empty report
below with a friendlier face. Force the compile when the point is to read the report.

**A text check**, for the two things no compiler sees: a byte order mark, which reaches a GLSL
compiler as a stray character in front of the version directive; and typographic punctuation, which
is wider than the quotes and dashes people expect: it also takes the single-glyph ellipsis and the
non-breaking space, and those are the two that surprise. The exact set is one line of the build
script.

**The rule behind all of it:** a gate blocks only if it is objective and mechanically fixable.
Anything requiring taste stays in the editor. A check that cries wolf gets routinely bypassed, and
once bypassing is a habit, it protects nothing.

**Measure before arming a gate, not after.** Every gate here was run over the whole repository
first, and the count of genuine findings decided whether it became blocking. Propose a gate with
its finding list attached.

**And do not believe an empty report.** The first static-analysis run reported nothing, which was
false: the redirection sent standard error to the terminal while the compiler writes diagnostics
there. The result was only trusted after a planted defect (a self-assignment and a mistyped format
string) came back flagged. A new gate is proven by a planted defect, not by a clean run.

**Nor a report that ends on a round number.** javac prints a hundred warnings and stops, and the
first reading of the report the two promotions came out of stopped there; the figure behind it was
past twice as many, and it was read again in full before anything was armed. What makes this worth
writing down is that javac is not at fault: it emits a note saying how many there really are and
naming the flag that lifts the ceiling, and that note does not survive the trip through Gradle. What
reaches the terminal is the bare line "100 warnings", which reads like a count. The build raises
both ceilings now, warnings and errors alike. The shape outlives the fix, and the tell is the same
every time: a total that lands exactly on a tool's own limit is the tool talking about itself, and
the sentence that would have explained it is often being eaten by whatever sits in between.

## The in-game loop

Some things have no text form to check: pipeline creation and binding, texture uploads, frame
timing, the shadow region following the player, and anything the game draws rather than a pack
program. Those are verified by running the game and reading its log.

The loop is scripted end to end (write the driving files, build, install, stop, relaunch, wait,
filter the log), and the parts worth knowing are these.

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

**The narrowed synchronisation can be put back.** An empty file `vitrail/full-pass-barrier` in the
instance, or `-Dvitrail.fullPassBarrier=true` among the JVM arguments, closes every pass on the
game's full memory barrier and puts that same barrier between the levels of a mip chain. It is
slower and it cannot be the cause of a wrong image, so it is the first thing to ask of a machine
that draws one this one does not.

**The block atlas filter can be put back the way it was.** An empty file
`vitrail/legacy-terrain-filter` in the instance, or `-Dvitrail.legacyTerrainFilter=true`, sends a
pack's terrain and its shadow back through the game's filtered sampler, which is what this engine
bound before it took Iris's unfiltered one. It exists because what a filter can change is the
silhouette of cutout foliage, and an eye judges that badly across two launches and worse across
two builds. One world, one variable, and the Reload Shaders key between the two states: F3 + T
rebuilds the pipelines without reading the pack again, so it does not switch. The state is written
to the log once per pack load, at the first terrain the pack draws, whether the file is there or
not, so a reading always names the state it belongs to.

**The sine substitution can be taken off.** Every `sin` and `cos` a pack writes is replaced at load
time by a reduced-argument helper of the translation's own, whatever the argument. An empty file
`vitrail/driver-trig` in the instance, or `-Dvitrail.driverTrig=true` among the JVM arguments,
leaves the driver's own two in place instead, so what the replacement costs a frame can be read as
two measurements in one world rather than across two builds. The state is written to the log in
both directions at every load that installs a chain, with the call sites matched by the time the
line prints, so a reading always names the state it belongs to and says whether the pack had
anything for the switch to bite on. It is an instrument and not a setting to keep: without the
replacement a pack feeding whole world coordinates to a sine gets whatever the driver makes of
them.

**Where a pack load's time goes is in the log at every load that installs a chain.** A first
report prints beside the pack-opened line and carries the translation alone, that being all the
load itself runs; the modules follow on the first draws and the compile workers, counted at the
game's own compiler so that every road lands in the tally, and the report that closes the
background warmup reads both figures with the families in. What a first draw pays after that
report stays in the tally rather than in any line. The spans are summed per program across the
compile workers, so the two figures compare with each other rather than with the wall clock,
and the driver's own pipeline build is in neither. The split is what says whether a faster load
needs a translation cache or a reflection cache, which are different designs keyed on different
things.

**The card's time per pass is in the log on request.** Started with `-Dvitrail.passTimings=N`
among the JVM arguments, the game prints every N seconds a table of GPU time per render pass
label, the game's and Sodium's passes beside the pack's, sorted by cost, with the share of the pass
total each takes and how many times a frame it ran. The header compares three numbers: the sum of
the passes, the span from the first pass of the frame to the last, and the interval between frames.
The gap between the first two is copies, clears and barriers between passes; the gap to the third
is the CPU, the limiter or vertical sync. The timestamps are the device's own, read back a few
frames late without waiting, so the table costs nothing to speak of, and nothing at all when the
property is absent. A reading is only as good as the run around it: compare runs taken in the same
state, and remember that the game lowers its own frame rate after a while without input (the
inactivity limit in the video settings), which moves every per-second number and none of the
per-pass milliseconds.

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

**Silent output reordering produces a frame that looks like a frame.** A fragment stage can end up
with two of its outputs swapped and nothing in the log about it. The mechanism, and the structural
fix for it, are in [translation.md](translation.md): what belongs here is that this is a failure
mode no image will report.

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
