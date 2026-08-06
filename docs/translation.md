# Translation

Shader packs are written in OpenGL-era GLSL. Minecraft 26.2 renders through Vulkan. This page is
about how the first becomes the second, and what does not survive the trip.

## Once, at load time

Every GLSL unit a pack ships is rewritten when the pack is selected, then handed to the compiler
the game already embeds, which produces SPIR-V and performs reflection and binding remapping
itself. Nothing is translated while the game runs.

Two properties follow, and both are load-bearing:

**A translation unit is not stable across settings changes.** An include can sit inside a
conditional branch, so the include graph is a function of the resolved option values. Changing a
setting rebuilds the units; it does not patch them.

**Order at load matters.** The table of values that supplies a pack's defines has to be installed
*before* the pack is read, because those symbols decide which branches compile. Read the pack first
and it sees none of them. The same table is only meaningful once a world exists, so the reload path
has to be triggered on entering a world, not merely on a file changing.

## A pack is downloaded content

Every loop and every recursion whose trip count depends on pack content is bounded, and the bound
is on **total work**, never on nesting depth. This is the single most important rule in the
subsystem, and each part of it was learned the hard way.

**There is no include-once.** A pack's include graph is a graph, not a tree, and the same file is
legitimately re-expanded from many sites. Only the packs' own guards limit it.

**A depth bound does not bound an expander.** The work is exponential in the graph, not linear in
the depth: a small acyclic set of files can produce an enormous expansion. The bound has to be on
files expanded and lines written.

**A budget tested only when a file is opened does not bound a single oversized file.** It has to be
tested on every emitted line.

**Recursion budgets must travel through the expression evaluator.** Macro resolution and expression
evaluation are mutually recursive; resetting the budget on each hop lets two defines that reference
each other overflow the stack.

**Why bounding rather than catching.** Stack overflow and out-of-memory are errors, not runtime
exceptions, so a catch around pack reading does not see them. The fix is to make sure the error is
never reached, not to widen the catch. Otherwise a single malformed pack dropped in the folder can
stop the client from starting - including a pack that was never selected, since reporting reads
them all.

Two smaller rules in the same family. A macro whose value is an expression is folded to its
**value**, not to a truth value, or conditions built on derived settings evaluate wrongly. And
option tables are case sensitive, because GLSL identifiers are: a case-insensitive map silently
merges two distinct macros into one.

## What the translator rewrites

**Sampling calls, by tokenising rather than substituting text.** Rewriting a legacy shadow sampling
call into a modern one changes the parenthesis balance: the naive substitution adds an opening
parenthesis and never its match, and the compiler reports a syntax error far from the cause. Doing
it correctly means finding the matching close parenthesis, which means tokenising.

**Precision qualifiers are stripped.** They mean nothing on desktop, but two declarations of one
function that disagree about them are a real conflict.

**Names that collide with newer builtins are renamed.** A function a pack defines can collide with
a builtin introduced after the version the pack targets, and the error does not name the collision -
it complains about overload precision qualifiers. Renaming is triggered only on names the pack
actually defines, so lengthening the reserved list costs nothing.

**Depth reads are converted.** The game renders in reversed Z; packs expect the legacy convention.
The names a pack uses to read scene depth trigger the conversion. Note that publishing a
legacy-convention matrix does not disable reversed Z for the game's own rasterisation - the world
keeps its depth precision, and only the copy the pack reads is converted.

## Bindings and locations are not emitted

The translator emits no explicit binding or location qualifier anywhere, with one exception, because
the game assigns bindings by SPIR-V reflection and rewrites them afterwards. Emitting them produces
mass overlapping-location errors. Compiler options that auto-map bindings and locations change
nothing either way - there is nothing for them to do when the emitter places nothing.

The exception is fragment outputs, which keep their explicit location, because their **order** is
the only thing that says which write lands on which attachment.

That exception has a sharp edge worth knowing. The game does not preserve the location a fragment
stage declares: it asks reflection for the outputs and writes each one's rank over the declared
decoration, and reflection answers in order of *first use*, not of declaration. A stage that writes
its second output before its first ends up with them swapped, silently. The structural fix is to
declare all outputs in the header from zero with no gaps, and name each once in increasing order
from a function called as the first statement of main, so rank equals location and the game's
rewrite becomes the identity.

## The unit of translation is the program, not the file

This is the correction that mattered most, and it applies twice.

**Uniform blocks.** A vertex stage and a fragment stage each lift only the uniforms they use, so a
block of the same name ends up with different members in different orders in the two stages - and
the engine binds a buffer by name. At most one stage can then read what it thinks it reads. Both
stages of a program therefore get a single block holding the union of their uniforms, in a
canonical order.

**Varyings.** They are matched by name, and the two failure directions are not symmetric: a varying
the fragment declares without the vertex emitting it is refused loudly, while the reverse is silent
and shifts the locations of everything after it. Declaring a varying only when a unit happens to use
it makes the two stages disagree, so the varying set is decided at program assembly too.

A consequence for measurement: a per-unit check cannot see this class of defect at all, because it
never pairs a vertex stage with its fragment stage.

Two more rules on lifting. A declaration sitting in a dead branch must **not** be lifted out of it,
because lifting makes it unconditional and can collide with a same-named ordinary global in the
live branch. And a scan for a statement boundary has to be bounded on both sides, or an
unterminated declaration makes it sweep the rest of the file once per declaration; when the bound is
hit it must report "no statement start" rather than guessing, since a guess would erase valid code.

## Deciding where a fragment stage writes

The directive that names attachments is resolved by the reference implementation's rules, and three
of the four are counter-intuitive:

- **The last occurrence wins**, not the first.
- **The search runs over text whose conditionals are not evaluated**, so a directive inside a dead
  branch still counts.
- **The directive must open a block comment.** A line-comment form, or prose before it on the same
  line, does not count.
- There is **no fallback to an earlier occurrence**: if the last one fails that test, there is no
  directive at all.

Two spellings exist, compared by position, the later one applying; they differ in form, and only
one of them can name targets beyond the single-digit range.

When no directive exists at all, the reference implementation infers a single attachment and raises
a flag. Vitrail deliberately does not infer, leaving the list empty. A program that writes nowhere
instead of writing to the first colour target produces a black image or a missing effect **with no
error at all**, which makes this the first place to look when an image is wrong and nothing is
logged.

One deliberate asymmetry: the attachment list and the count of declared fragment outputs follow
different rules. Attachments follow the reference (dead branches included, since the directive text
is unevaluated), while the output count includes every branch and knowingly over-declares. That is
because the two failure modes are not equal - over-declaring is free, under-declaring fails to
compile.

## Not everything in a pack is a program

A substantial part of a pack's files are include fragments, not compilable units. Some begin in the
middle of a declaration; others read a symbol the including file defines above them. They cannot
compile alone by construction, so any measurement over "all files" mixes two populations.

Which programs a pack actually serves is resolved through a fallback tree, where each program names
a parent and resolution walks up recursively. The tree and its transitivity are established by
reading the reference implementation, not from documentation.

Three rules there are easy to get wrong:

- **A dimension directory replaces the base set rather than merging with it.** A pack shipping two
  programs in a dimension directory has exactly two programs in that dimension, and everything else
  falls back *within* that directory.
- **The condition is the existence of the directory, not its contents.** An empty dimension
  directory yields an empty program set rather than falling back to the base set. This happens in
  real packs.
- **The base set is not necessarily the root.** It is the directory bound to the catch-all entry of
  the dimension mapping, and in practice most packs keep no programs at the root at all.

## Serving the uniforms a pack expects

The set of names a pack reads is not fixed: packs declare uniforms conditionally behind feature
flags, so whether a name is even referenced depends on which flags the engine promises.

**A feature flag is refused deliberately when the feature does not exist on this backend**, because
defining it changes which branch *every* program of the pack takes. The resulting failures are
honest rather than accidental. Registering the uniforms of an absent feature is nonetheless correct,
because the reference registers them unconditionally, and diverging there would be a compatibility
difference rather than a fix.

**Frame-varying values advance once per frame, at a single named point, not once per pass.** Two
passes of the same frame must receive identical numbers; otherwise a second pass reprojects against
itself, and any smoothing the pack does decays at a rate that depends on how many passes the pack
happens to have.

**Where the reference has a known bug that packs are tuned against, the bug is reproduced.** For the
wetness half-lives it writes both directives into the same field, so rise and fall share one
half-life. Correcting that would break packs written against the observed behaviour. The same
applies to a time uniform that keeps varying in dimensions where the game gives a fixed time.

**A name the engine cannot supply is written as an explicit zero and named in the log**, one line
per program. The member exists, its value is defined, and the shortfall is announced rather than
left to whatever was in memory. The announcement is split into separate buckets, so an unanswered
engine name, a pack declaration that could not be used, and a deliberate stop-gap are not confused
with one another.

Packs can also define their own uniforms as expressions over others. Those form a dependency graph
that is validated: a cycle is refused by naming the uniforms involved, a broken uniform withdraws
its dependents by name rather than being silently replaced by zero mid-graph, and a custom uniform
that shadows a builtin name is refused rather than resolved by precedence.

## What resists, and whose fault it is

Not every remaining failure is a translation gap, and the distinction is worth making.

**Closed by the graphics API.** Compute shaders, shader storage buffers, storage images, and
one- and three-dimensional samplers. The game's compiler accepts only two sampler dimensionalities,
so these are hard refusals rather than effort. No amount of translation work makes them pass.

**Defects in the pack itself.** A conditional directive with no name is a pack bug and stays a
failure.

**Missing values rather than missing translation.** A vanilla-style uniform a pack expects is closed
by supplying the value, not by changing the translator. It shows up in the unanswered list, not in
a compile error.

## Two things no single instrument can prove

An out-of-game check proves what **compiles**, never what **draws**. Attachment routing is
conformance on trust until an image exists.

And the converse: a wrong parity or a wrong layout looks like a correct image. A shifted uniform
block does not produce a black area - it produces a plausible image in which every value stands in
for another. That class of defect is caught by the layout check, not by looking at the screen.

Neither instrument substitutes for the other.

## An implementation trap worth repeating

Any map that is **iterated** must have a defined iteration order. A common immutable-map factory
deliberately randomises iteration with a per-process salt, which makes uniform block members and
vertex attributes come out in a different order on each launch - a defect that reproduces only
across process boundaries, and looks like nondeterministic hardware behaviour. Tables that are
iterated go through an insertion-ordered map; the randomising factory is for tables queried by key
only. It also rejects null values, which matters wherever a table legitimately holds a "no parent"
entry.
