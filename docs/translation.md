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

Every loop and every recursion whose trip count depends on pack content is bounded, and where there
is a depth limit there is a bound on **total work** beside it. Depth alone bounds nothing, and that
is the single most important rule in the subsystem. Each part of it was learned the hard way.

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

**Depth reads are converted, and not by the translator.** The game renders in reversed Z; packs
expect the legacy convention. Nothing in the translated text is rewritten for it: the *image* the
pack samples is converted instead, once, when the depth is taken. That is not a shortcut, it is the
only thing that works. A lookup can only be rewritten if it can be found, and it can only be found
by the name of its sampler - so a pack helper taking `sampler2D depth` as a parameter and called
with a colour target on one line and a depth texture two lines below cannot be served both ways
without writing the body twice. Converting the image makes every lookup right whatever name it was
reached through, including the ones through a macro or a local that no rewrite could ever see. The
translator only *counts* those lookups, and the count is what turns the blind spot into a number.

Note that publishing a legacy-convention matrix does not disable reversed Z for the game's own
rasterisation - the world keeps its depth precision, and only the copy the pack reads is converted.

## Bindings and locations are not emitted

The translator emits no explicit binding or location qualifier anywhere, with one exception, because
the game assigns bindings by SPIR-V reflection and rewrites them afterwards. Emitting them produces
mass overlapping-location errors. The game's compiler switches on the options that auto-bind
uniforms and auto-map locations, and that is exactly what makes emitting nothing work: they are not
inert here, they are what assigns what the emitter deliberately leaves unassigned.

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
and shifts the locations of everything after it.

The two sides are reconciled in two different ways, and it is worth not confusing them. A varying
the *engine* names - there is one, the fog coordinate - has to be declared by both stages or by
neither, so it is decided at program assembly. The pack's own varyings are not unified that way:
they are reconciled in the other direction, by striking from the later stage the inputs nothing
upstream writes.

A consequence for measurement: a per-unit check cannot see this class of defect at all, because it
never pairs a vertex stage with its fragment stage.

Two more rules on lifting. A declaration sitting in a dead branch must **not** be lifted out of it,
because lifting makes it unconditional and can collide with a same-named ordinary global in the
live branch. And a scan for a statement boundary has to be bounded on both sides, or an
unterminated declaration makes it sweep the rest of the file once per declaration; running out of
budget has to be told apart from reaching the start of the file, because the first means "give up on
this declaration" and the second is a real answer. Guessing a boundary would erase valid code.

## Deciding where a fragment stage writes

The directive that names attachments is resolved by the reference implementation's rules, and three
of the four are counter-intuitive:

- **The last occurrence wins**, not the first.
- **The directive must open a block comment.** A line-comment form, or prose before it on the same
  line, does not count.
- There is **no fallback to an earlier occurrence**: if the last one fails that test, there is no
  directive at all.
- **The search runs only over the lines a branch actually took.** The reference implementation is
  handed a source its preprocessor has already been over, so a branch nobody takes is simply not
  there. Here the text is still whole, so liveness has to be applied deliberately to get the same
  answer - and the difference is not academic, because the idiom packs use is one directive per
  branch. Read them all and you take the last one *written* rather than the one that *holds*.

Two spellings exist, compared by position, the later one applying; they differ in form, and only
one of them can name targets beyond the single-digit range.

When no directive exists at all, the reference implementation infers a single attachment and raises
a flag. Vitrail deliberately does not infer, leaving the list empty. A program that writes nowhere
instead of writing to the first colour target produces a black image or a missing effect **with no
error at all**, which makes this the first place to look when an image is wrong and nothing is
logged.

One deliberate asymmetry: the attachment list and the count of declared fragment outputs follow
different rules. **Attachments are read on live lines only**, so a directive in a branch nobody
takes decides nothing - while the **output count includes every branch** and knowingly
over-declares. That looks inconsistent and is not, because the two failure modes are not equal: an
attachment claimed from a dead branch sends writes to the wrong target and flips it afterwards,
whereas an over-declared output costs nothing and an under-declared one fails to compile.

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
  directory yields an empty program set rather than falling back to the base set, because emptying
  a folder is the only way a pack has of saying "nothing here" and reading the base set instead
  would overrule it. A directory that is named and *absent* does fall back.
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

**Where the reference has a known bug that packs are tuned against, the bug is reproduced rather
than fixed.** Two cases are worth knowing because they look like defects here and are not.

A pack can declare separate half-lives for how fast wetness rises and how fast it dries. The
declared **fall** does not take effect: the engine matches the reference, where it is not reachable.
Correcting that in isolation would change the look of every pack tuned against the observed
behaviour.

Likewise, a time uniform keeps varying in the Nether and the End although the game gives them a
fixed time, because that is what the reference does and packs derive angles from it. It is those two
worlds by name, not the class of fixed-time worlds: any other dimension declaring a fixed time gets
one.

The rule this illustrates is worth stating on its own: **compatibility is with the reference's
behaviour, not with its documentation.** A divergence here is paid in packs that render wrongly,
so a fix must be argued as a compatibility break, not slipped in as a correction.

**A name the engine cannot supply is written as an explicit zero and named in the log.** The member
exists, its value is defined, and the shortfall is announced rather than left to whatever was in
memory.

The announcement is split into separate buckets rather than counted, because a program can be short
in several different ways at once and each line says which. Three of them are names the block could
not be given: one this engine owes, one the pack declared for itself and none of whose declarations
survived, and one waiting on machinery that does not run yet. Underneath those sits the dangerous
one, which is not a gap at all: a name the table answers with a **stand-in**, which counts as
supplied everywhere else. A zero that arrived through a registered source looks exactly like a
measured value, and no screenshot will ever show it.

Packs can also define their own uniforms as expressions over others. Those form a dependency graph
that is validated: a cycle is refused by naming the uniforms involved, a broken uniform withdraws
its dependents by name rather than being silently replaced by zero mid-graph, and a custom uniform
that shadows a builtin name is refused rather than resolved by precedence.

## What resists, and whose fault it is

Not every remaining failure is a translation gap, and the distinction is worth making.

**Closed by the game's own rendering API.** Compute shaders, shader storage buffers, storage images,
and one- and three-dimensional samplers. Same conclusion, three different mechanisms, and it is
worth knowing which:

- **Samplers are refused by name.** The compiler takes one as two-dimensional or as a cube, or as a
  texel buffer where the pipeline declared that name as a uniform rather than as a sampler, and
  rejects every other dimensionality.
- **Compute has nowhere to go.** The game's shader-type enumeration carries a vertex stage and a
  fragment stage and nothing else, and the device exposes no way to precompile anything but a render
  pipeline.
- **Storage buffers and storage images are worse than refused: they are ignored.** Reflection asks
  for uniform buffers, sampled images, outputs and inputs, and never enumerates them. They pass
  compilation and are then bound to nothing.

No amount of translation work makes any of the three pass. Vulkan itself supports all of them; it is
the layer above that does not.

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
