# Uniforms

A shader pack reads the world through named values: where the camera is, what time it is, which
matrices the frame was drawn with, how wet the ground is. This page is about how those values reach
a shader, and (the longer half) about how you find out whether one of them is right.

The second half is the part worth reading even if you never touch the engine. A uniform that is
wrong does not look wrong. It looks like a slightly different picture, and every rule below exists
because somebody read one of those as though it were correct.

Two things about uniforms are settled in [Translation](../translation.md) and not repeated here:
the block belongs to the **program** rather than to a single GLSL unit, since the two stages of a
program would otherwise lift different members in different orders under one name; and a name the
engine cannot supply is written as an explicit zero and said out loud in the log rather than left
to whatever was in memory.

## The block, and why its size is what writing it costs

Every value a program reads lives in one uniform buffer, laid out in std140, in the order the
translation fixed. The order is carried out of the translation rather than worked out again, since
the order *is* the layout.

The bytes go through the game's own std140 builder, the one it uses for its own blocks, so that the
layout is the game's answer rather than a reading of the specification. That contact is deliberately
thin, and it is not total: two places restate the rules instead of deferring to them, and both are
worth having in mind, since an unnamed exception is how two implementations of one layout drift
apart without anybody seeing it. The first is here. The second is the walk that measures a block,
further down.

**A three component vector aligns on sixteen bytes and consumes twelve.** The member after it starts
twelve bytes later, at its own alignment, not sixteen. The builder's own vec3 helper skips a fourth
float on the way out, so going through it would put every member behind the first vec3 four bytes
past where the shader reads it. That one call is therefore written out by hand (align on sixteen,
then three floats) while the builder's padded form stays in use exactly where the stride really is
sixteen, which is a matrix column and an array element. Which of the two is right was settled by
measuring what the compiler does with a vec3 followed by a float, not by arguing from the
specification.

Arrays are the other trap, and a worse one. The arity of an array member lives only in the
declaration text: the type is the element type either way. A matrix array read as a single matrix
puts everything behind it hundreds of bytes early, and what sits behind it is, among other things,
every uniform the pack declared for itself. Each element of an array starts on a sixteen byte
boundary, including the first, so a single element array still pays the stride; and the padding
lands **after** the last element too, because an array's size is its stride times its length rather
than wherever its last element happens to stop.

None of that is checked by a compiler or by a validation layer. A block that is a few bytes short,
or whose members have slid by four, produces a plausible image in which every value stands in for
another.

So the size is not measured. **The size is what the write costs**: the same walk over the same
resolved members runs into a sink that writes nothing and keeps only the position, and its final
position is the block's size. That sink is the second restatement of the rules: writing nothing, it
has no builder to defer to, so it carries the alignments itself, transcribed from the game's own
size calculator. It is the one duplication here that cannot be removed, and running the write is
what keeps it to arithmetic in a single class rather than a second reading of the members. Sizing a
block by a second pass over the members is exactly how the
two halves drift apart, one of them gains a case for a shape and the other does not, and there is
nothing here to keep in step because there is only one walk. Every pass's block is then cut out of
one buffer at its own offset with that size, so a size that disagreed with the write would either
truncate a block or run into the next one.

One consequence of holding the values ourselves rather than setting them one at a time: **the type
the pack declared decides what is written, not the name**. Packs disagree with each other about
whether a given flag is a bool, an int or a float, and each is served, because a value is
registered once under the shape the engine holds it in and converted at the member. Iris fixes the
type when the uniform is registered, before it knows what any program declares, so a mismatch there
disables the uniform outright.

## The frame advances once, at a named point

Nothing frame-varying is computed when it is read. The whole of what the engine can answer this
frame is read out of the game into fields at one named point, before the first block of the frame
is written, and every pass of that frame then sees the same numbers.

This is not an optimisation, and the reason it matters is not obvious until you look at what
depends on it:

- **Previous-frame matrices only mean anything if the whole engine agrees where a frame ends.** A
  supplier that shifted its history the first time somebody read it would hand the second pass of a
  frame a "previous" that is the current one, and every motion vector built from it would be zero.
- **A smoothed value that stepped on being read would integrate once per pass** rather than once
  per frame, so it would converge at a speed that depends on how many passes the pack happens to
  declare. The same value would fade at different rates under two packs at the same frame rate.
- **A pass that reprojects would reproject against itself.**

Iris steps its history on read and gets away with it because it uploads per program. Vitrail writes
one block per pass, so the boundary has to be a place in the code rather than a convention, and it
is. The frame may be opened by the chain or, earlier, by a geometry program that runs during the
world; whichever comes first opens it, and the second one finds it already open.

The clock is quantised to the millisecond, because that is the time step every smoothed value
integrates over and an unquantised one puts all of them slightly off the reference for no visible
reason. The running time a pack reads accumulates those frame durations rather than sampling a wall
clock, so it stops while the game is paused, which is what a pack driving noise from it expects.

A handful of values are properties of the **pass** rather than of the frame: the depth convention of
the target being drawn into, the model view the pass draws with and the projection it draws under,
the colour it modulates by, and which stage of the frame it is. Those are set beside each block
write and dropped rather than carried over, though not in the same place: the frame boundary drops
the model view, the projection and the colour, and the chain drops the render stage before it
writes its own blocks, being the only reader left that could hold a stale one. Left
standing from the pass before, the render stage would tell every full-screen pass of the frame that
it was drawing the moon, because that value sits in the same table a full-screen pass shares with a
geometry one.

Changing world drops all of it, the clock included. Nothing carried from the previous frame means
anything once the camera has jumped a dimension's worth of coordinates, and a frame duration
measuring the whole load would be integrated into every value the pack accumulates over one.

## Smoothed values and the decisecond

Some uniforms are exponentially smoothed towards their raw value, over half-lives that come partly
from the pack's own directives and partly from constants no directive reaches. **The unit of that
half-life is the decisecond**, so getting it wrong by a factor of ten produces a value that moves,
that looks smoothed, and that is not.

Three details decide the result and none of them is obvious:

- The rise and the fall are **separate** half-lives, chosen on whether the new value is above the
  accumulator. Separate does not mean that both are the pack's. Wetness is the case to know: its
  fall is a constant here, and the pack cannot change it, because both of the directive names it
  would write (the one that reads as the rise and the one that reads as the fall) are registered
  against the rise, so the second one it writes only overwrites the first. That is the reference's
  behaviour, reproduced on purpose. Honouring the pack's own fall directive instead would be the
  more sensible reading, and it would change the look of the three packs of the corpus that declare
  one, in opposite directions: BSL asks for 5 deciseconds against this 200, and both Complementary
  for 300.
- There is no smoothing at all on the first value, which is set outright. Otherwise a fresh
  accumulator would spend its first seconds climbing out of zero.
- A half-life of zero gives an infinite decay constant, hence a factor of one, hence no smoothing.
  That falls out of the arithmetic rather than needing a case. It needs a frame that lasted,
  though: the same arithmetic on a duration of zero is an infinity times a nought, which is a NaN,
  and a frame clock quantising to the millisecond hands out zeroes. A frame that measures nothing
  therefore holds every accumulator where it stands instead of folding into it.

**One smoothed value is not accumulated on this side at all.** `centerDepthSmooth` is the depth at
the middle of the screen, and it lives in a one-texel image on the card: a pass of the engine's own
folds this frame's depth into it, and the translation turns the pack's declaration of the name into
a lookup in that texel. Only the factor above is computed here, from the same half-life reading, so
that a value fading on the card and a value fading in a table cannot come to disagree about what a
decisecond is. `docs/translation.md` describes the rewrite.

The accumulators outlive the frame state, since the value table is built once for the process while
the frame state is built per pack. They are therefore dropped explicitly on a pack load and on a
world change, or the ground stays wet across a dimension and a freshly loaded pack spends its first
seconds fading from a number the previous pack left behind.

The fall that no directive reaches is one instance of a policy rather than a one-off: **a known
defect of the reference implementation is reproduced on purpose** wherever packs are tuned against
the behaviour they observe rather than the behaviour the documentation describes. The policy itself,
and why correcting such a defect is the regression, is in [Translation](../translation.md).

## The camera position is shifted, and so is the previous one

Far from the origin, a single precision position stops resolving the differences a pack cares
about, and anything driven by world position (noise, grain, triplanar mapping) visibly degrades.
So the published camera position is kept inside a range a float can still resolve, by subtracting a
shift.

Three properties of that shift are observable by a pack, and all three are reproduced from the
reference rather than chosen:

- **Only X and Z are ever shifted.** The altitude and the raw Y are the same number, so flying
  straight up never triggers anything.
- The shift moves in **whole multiples** of the range rather than by the overshoot. At least one
  pack depends on that; it is not a rounding preference.
- It triggers on two conditions, not one: the position leaving the range, and a jump between two
  frames larger than a teleport threshold.

The part that is easy to get wrong is what happens **at** a shift. When the shift changes, the
current position and the previous frame's position both move by the same amount. A motion vector is
a difference of the two, so the difference has to survive the shift; shifting only the current
position leaves one frame in which the camera appears to have jumped the width of the range. That
is a single frame of wrong reprojection (one flash of motion blur) at a threshold crossing, which
is about as hard to find by looking as a defect gets.

An unshifted position exists alongside, because some values are differences taken against the
camera (a lightning bolt's position, a vehicle's) and those are in world coordinates. The shift
advances first in the frame, before anything reads it, so those differences are taken against this
frame's camera rather than the previous one's; otherwise both would lag, and only while the player
moves. And the first frame after a world change has no previous position at all, so it is seeded
with the current one rather than with the origin, which would be a motion vector the width of the
world.

## Uniforms the pack defines for itself

A pack can declare its own values as expressions over engine values and over each other. Some are
intermediates the shader never sees; some are exposed to it. They share one namespace and may refer
to each other in any order.

That is why they are resolved as a graph rather than as a list. A declaration several levels deep
(and real packs have them) evaluated out of order gives a plausible number rather than an error.
The graph is resolved once at load, and evaluated once a frame in dependency order, after the
engine values are current and before any program writes its block. Once a frame and never once per
program, for the same reason as everything else here: a smoothing function inside an expression
would otherwise advance every time a pass read it.

Three rules decide what happens when a pack gets it wrong, and all three exist so that a mistake
stays **named** instead of turning into a permanently wrong image:

- A declaration that does not parse, or that reads a name nothing answers, is dropped and named. It
  is never evaluated as zero.
- **Everything that depended on it is dropped and named with it.** Reading a zero from the middle of
  a graph is the failure this rule exists to prevent: the value it produces is in range, the image
  is only slightly different, and nothing in the log connects it to the declaration that actually
  broke.
- A declaration that shadows a name the engine already answers is refused, so a pack cannot quietly
  redefine what the engine means by a builtin.

A cycle is refused by naming the uniforms it runs through. This is a deliberate divergence: the
reference throws, and a pack that writes a cycle in one line should lose that line rather than the
frame. The same reasoning applies to an expression that throws while it is being evaluated: it and
its dependents stop being evaluated and hold their last value, both named, rather than taking the
whole pack down for the rest of the session over one expression that fails on one frame.

## What counts as proof

Here is the rule the whole of this half rests on.

**A value is proven when a test distinguishes it from the plausible neighbouring value, not when it
distinguishes it from zero.**

Zero is the easy failure. Zero is usually visible immediately: the effect is missing, the highlight
is absent, the screen is flat. Nobody ships a zero. What ships is the neighbour (the same quantity
in the wrong unit, in the wrong space, or off by a constant factor), and the neighbour renders an
image that a careful person will accept.

Two consequences follow directly:

- **The test is written before the observation, and it names the neighbour it rules out.** A test
  written afterwards is a description of what was seen. A value declared correct without a named
  neighbour is not closed and should be reopened.
- **Say what a test does not close.** A test that puts the pack's sun exactly where the game's sun
  is closes the time value, the order of the rotations and the view matrix, and it does *not* close
  the sun direction uniform, if the pack recomputes its own direction rather than reading it.
  Writing that down is what stops the next reader treating it as covered.

### The three families of near miss

**Wrong unit.** A half-life in seconds where the pack meant deciseconds gives a value that rises,
that settles, that looks smoothed, and that takes ten times too long. Nothing about the image says
"unit error"; it says "this pack is a bit sluggish". The test that catches it is a *timed* reading,
not a look.

**Wrong space.** A light direction handed over in world space instead of eye space is non-zero,
smoothly varying, and wrong. On a still frame it is indistinguishable from correct. The test that
separates them is to turn the camera without moving it: in eye space the specular highlight stays
on the light, in world space it follows the camera. Zero would have been caught in a second; the
neighbour needs a test designed to catch it.

**Constant factor.** The far plane a pack is told about is a quarter of the plane the game actually
clips at. Hand over the real one and fog still saturates, still in the right shape, still smoothly
with distance, at four times the distance. The image is entirely credible. And since every pack's
depth linearisation is written against the reference's quarter, correcting the number here would
make all of them wrong at once: this is a case where **matching the reference is the correct answer
and being right is the bug**.

### Prefer a tell that is geometric and binary

The best in-game tests for this class of value are not measurements. They are situations where the
neighbouring value produces the *opposite* result, not a smaller one.

An effect the pack gates on far depth (a motion blur applied to the distance, a sky test that
looks for the far plane) inverts completely under the opposite depth convention: the foreground
blurs and the distance stays sharp, or the sky is fogged exactly like a solid block. There is no
threshold to read and no capture to compare. Either the horizon is blurred or the wall in front of
you is.

Two supporting rules from [Developing](../developing.md) apply throughout and are not repeated here:
force a pack setting rather than writing a test shader, and never let a test's success criterion be
"nothing visible".

### The decoded dump

The instrument that turns all of this from an argument into a reading: the engine can write one
program's whole block out as `name = value` text.

It is the **same walk** that fills the buffer, sent through a sink that writes text instead of
bytes. That is the only reason a line in it proves anything about what the shader was handed. A
second walk written to print values would be a second reading of the value table, and it could
disagree with the one that ran, which is precisely the class of defect being hunted. A member that
reached the buffer through a type conversion is printed after that conversion. A member nothing
supplies prints the zeroes it really writes, marked as such, because a zero that arrived through a
registered source is the one failure no screenshot can show.

**What buys that is holding the values in a block of our own.** An engine that sets each value
through the GL entry points, one at a time, has no single walk to tee off. It is not that the values
could not be read back (`glGetUniformfv` is there for it). It is that reading them back would be
the second walk the paragraph above rules out. Holding them costs one class rather than a redesign.

It names one program at a time, since the point is to read the file rather than to search it, and
not because two programs of one frame would say the same thing. They do not, on everything the named
program itself decides. What they do NOT tell apart is what a pass sets beside its block write. The
dump is taken as the frame opens, before any pass has written one, so the pass model view reads back
as the camera's, the modulating colour as white, and the render stage as whatever the last block of
the frame before left there, which is not the chain's own. The eight pieces of the sky dump alike on
exactly those. Taking the reading under one program and then under the other is how the rest gets
compared at all.
It is rewritten whole rather
than appended, roughly once a second rather than once a frame, so what is in it is always now: a
curve is taken by reading it repeatedly, which is exactly how a half-life is measured.

What that turns into a number:

- An expression whose value is calculable by hand at a chosen time of day. It is either exactly the
  number you computed or it is not, and the way it is wrong names the fault: an angle taken in
  degrees instead of turns, a function that is not vectorised over its arguments, a value that never
  changes because the expression is evaluated once.
- A half-life, read as a curve rather than as an impression.
- **Two tables that agree today.** A biome identifier and a biome category, or any pair where one is
  derived from the other, can be confused in a way that a single reading cannot see: they agree
  everywhere you happened to stand. The dump lets you go to the place where they must differ and
  read both at once.

And its limits, which matter as much:

- It says what the block was handed, **not what the shader did with it**. Attachment routing,
  sampler binding and everything downstream of the block are outside it.
- It cannot separate two textures that have been aliased onto one another, because both names read
  the same handed-over value. That takes two captures of the image, not a number.
- A comparison of two readings only means something if the readings are simultaneous. Reading a
  smoothed value beside a world state taken moments apart is how a lookup table nearly got blamed
  for a defect that was just the smoothing catching up.

### What no reading in the game can settle

Some things have no in-game discriminator at all, and the honest move is to say so in the delivery
rather than to find a test that almost works.

The generated noise image is the standing example: two different generators both produce something
that looks exactly like noise, and swapping the two loop axes produces a transposed image that also
looks exactly like noise. Only a fingerprint compared bit for bit decides, and that runs in an
out-of-game check which does not ship with this repository.

The same applies to depth precision, and the claim there has to be kept narrow. Publishing a
legacy-convention matrix does not switch reversed Z off for the game: the world is still rasterised,
tested and stored under it, so the scene keeps its precision where the reference gives it up for
everything the moment a pack is loaded. But the copy handed to the pack is converted forward, so
**the pack's own linearisation is no more precise than the reference's**. The gain is real and it is
there and nowhere else. The symptom that would confirm it (distant z-fighting appearing on one side
and not the other) needs the reference running on this backend, which it does not.

## Where this sits

- [Translation](../translation.md): how the block is assembled, what is written as zeroes, and why
  the program rather than the file is the unit.
- [The frame](../frame.md): where in the frame each program runs, and what the depth names mean.
- [Sky and shadows](../sky-and-shadows.md): the matrices the shadow passes are given.
- [Pack compatibility](../compatibility.md): what a wrong value looks like from the player's side.
- [Developing](../developing.md): the general verification rules this page specialises.
- [Documentation index](../README.md).
