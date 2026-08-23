# Translation

Shader packs are written in OpenGL-era GLSL. The game renders through Vulkan. This page is
about how the first becomes the second, and what does not survive the trip.

## Once, at load time

Every GLSL unit a pack ships is rewritten before it can draw, then handed to the compiler the game
already embeds, which produces SPIR-V and performs reflection and binding remapping itself. The
chain's own units go at selection; the programs that draw the world and the sky are translated on
demand, at the first frame of a place that needs them. What is translated is never *patched*
afterwards: a setting that moves rebuilds its units from the pack's source, and so does a change of
dimension, which rebuilds the lot.

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

**The include graph is a graph, not a tree.** There is no include-once (see
[the pack format](pack-format.md) for why there must not be), so the same file is legitimately
re-expanded from many sites, and only the packs' own guards limit it. Everything below follows from
that.

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
stop the client from starting, including a pack that was never selected, since reporting reads
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

**`const` on a variable whose initialiser is not a constant expression is stripped.** Vulkan
refuses a global `const mat3` initialised from `transpose(...)`, or from a uniform, which OpenGL
drivers took as merely immutable. The keyword comes off and the value stays. A declaration whose
initialiser is only literals and type constructors is left alone, because an array size still
needs a real constant. Parameters keep `const`: that spelling means immutable, not compile-time.

**Names that collide with newer builtins are renamed.** A function a pack defines can collide with
a builtin introduced after the version the pack targets, and the error does not name the collision:
it complains about overload precision qualifiers. Renaming is triggered only on names the pack
actually defines, so lengthening the reserved list costs nothing.

**Depth reads are converted, and not by the translator.** The game renders in reversed Z; packs
expect the legacy convention. The translated text does carry depth conversion, at three fixed sites:
the built-in fragment depth, a write to the built-in output depth, and the clip depth in the vertex
epilogue. Even there it is not exhaustive: a built-in reached through a subscript, or handed whole
to a function, or written with a compound assignment, cannot be rewritten where it stands, and those
are counted rather than guessed at. What the translator never rewrites at all is a **lookup through
a sampler**. Those are served by converting the *image* instead, once, when the depth is taken.

**One uniform becomes a sampler, because its value never comes back from the card.**
`centerDepthSmooth` is the depth at the middle of the screen, faded by the pack's own half-life,
and it is what a depth of field focuses on. It is accumulated in a one-texel image that a pass of
the engine's own draws each frame, so it cannot be a member of the uniform block: the declaration
is taken off its statement, a one-texel sampler is declared in its place, and every use of the name
becomes a lookup at the middle of that texel. The declaration alone moves, so a statement that
declares other names beside it keeps them. This is the reference's own answer, and it is limited
the same way: only a program drawn over a full screen quad is rewritten, so a geometry program that
declares the name reads a zero. Under the reference that zero is what a uniform nothing ever writes
comes to; here the value table answers the name with it deliberately, which is the same bytes and
keeps it out of the list of names the engine failed to supply.

That is not a shortcut, it is the only thing that works. A lookup can only be rewritten if it can be
found, and it can only be found
by the name of its sampler, so a pack helper taking `sampler2D depth` as a parameter and called
with a colour target on one line and a depth texture two lines below cannot be served both ways
without writing the body twice. Converting the image makes every lookup right whatever name it was
reached through, including the ones through a macro or a local that no rewrite could ever see. The
translator only *counts* those lookups, and the count is what turns the blind spot into a number.

Note that publishing a legacy-convention matrix does not disable reversed Z for the game's own
rasterisation: the world keeps its depth precision, and only the copy the pack reads is converted.

## Bindings and locations are not emitted

The translator emits no explicit binding or location qualifier anywhere, with one exception, because
the game assigns bindings by SPIR-V reflection and rewrites them afterwards. Emitting them produces
mass overlapping-location errors. The game's compiler switches on the options that auto-bind
uniforms and auto-map locations, and that is exactly what makes emitting nothing work: they are not
inert here, they are what assigns what the emitter deliberately leaves unassigned.

Those assigned numbers follow the order the compiler first meets each name in the shader. Unused
sampler declarations still consume a number if they sit in front of a used one, which on MoltenVK
is a Metal sampler index above 15 and a refused pipeline. Sampled names are therefore declared
first in the header, unused after; see [the graphics API](internals/game-graphics-api.md).

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
block of the same name ends up with different members in different orders in the two stages, and
the engine binds a buffer by name. At most one stage can then read what it thinks it reads. Both
stages of a program therefore get a single block holding the union of their uniforms, in a
canonical order.

**Varyings.** They are matched by name, and the two failure directions are not symmetric: a varying
the fragment declares without the vertex emitting it is refused loudly, while the reverse is silent
and shifts the locations of everything after it.

The two sides are reconciled in two different ways, and it is worth not confusing them. A varying
the *engine* names (there are two, the fog coordinate and the colour a hurt mob flashes) has to be
declared by both stages or by neither, so it is decided once at program assembly and written into
both headers from the one answer. The pack's own varyings are not unified that way. They are
brought into agreement by three passes over the pair, in this order, and the order matters because
each one changes what the next one sees:

1. An input the later stage declares that nothing upstream writes is **struck out**, where its body
   never reads it. That is the cheapest answer, because it changes nothing else.
2. What the strike could not take, because the body does read it, is **owed by the stage before**,
   which declares it and assigns it its zero. This is the reference's own patch, taken whole,
   including the assignment: under the reference these varyings hold zero rather than whatever the
   stage happened to leave in them.
3. An output the earlier stage hands on that the later one never declares is **withheld**: the
   `out` and its interpolation qualifier come off and a plain global of the same name and type is
   left behind, so the body's own writes keep compiling. The reference does nothing here, and has
   no reason to: it links two stages the way OpenGL does, where an output nobody reads is legal.
   What forces it here is a backend that pairs the two lists by counting rather than by name.

A consequence for measurement, and it is sharper than it looks: **a per-unit check cannot see this
class of defect at all**, because it never pairs a vertex stage with its fragment stage. Neither
can a check that compiles both stages in one invocation of a desktop GLSL compiler, which is the
trap worth naming: that links them the OpenGL way, where both of these shapes are legal, so it
reports the same clean number before and after either fix. Only compiling each stage on its own and
pairing the two reflected interfaces answers.

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
  answer, and the difference is not academic, because the idiom packs use is one directive per
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
takes decides nothing, while the **output count includes every branch** and knowingly
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

Which directory a program is looked up in is decided before any of that, and the three rules there
are easy to get wrong: a dimension directory replaces the base set rather than merging with it, the
condition is the directory's existence and not its contents, and the base set is not necessarily the
root. They belong to the format rather than to the translator, and they are in
[the pack format](pack-format.md).

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

The format appears to offer a pack two half-lives for wetness, one for how fast it rises and one for
how fast it dries. Both directives land on the **rise**, and whichever is read last sets it, so a
pack writing a drying time is quietly changing how fast wetness comes *on*. The fall is real and is
a constant no pack can reach, so the two are not the same rate; they are simply not both settings.

The engine matches the reference here, and the corpus says why that is not just deference. The packs
that declare a drying time disagree with the constant in **both directions** and by wildly different
amounts: one asks for a fall many times faster, two ask for one half again slower. Honouring the
declaration would change how all three look, in opposite directions, and every one of them was tuned
against the behaviour they actually get.

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
in several different ways at once and each line says which. Two of them are names the block could
not be given: one this engine owes, and one the pack declared for itself and none of whose
declarations survived. Underneath those sits the dangerous one, which is not a gap at all: a name
the table answers with a **stand-in**, which counts as supplied everywhere else. A zero that
arrived through a registered source looks exactly like a measured value, and no screenshot will
ever show it.

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
block does not produce a black area: it produces a plausible image in which every value stands in
for another. That class of defect is caught by the layout check, not by looking at the screen.

Neither instrument substitutes for the other.

## An implementation trap worth repeating

Any map that is **iterated** must have a defined iteration order. A common immutable-map factory
deliberately randomises iteration with a per-process salt, which makes uniform block members and
vertex attributes come out in a different order on each launch: a defect that reproduces only
across process boundaries, and looks like nondeterministic hardware behaviour. Tables that are
iterated go through an insertion-ordered map; the randomising factory is for tables queried by key
only. It also rejects null values, which matters wherever a table legitimately holds a "no parent"
entry.
