# Render targets and the chain

[The frame](../frame.md) says when each pass runs, which targets are doubled and what governs a
read. This page is the level under it: how a target gets a format at all, where the declarations
that decide it are hidden, what the backend refuses when a pass carries several attachments, and
which parts of the mechanism are engine decisions rather than transcriptions of the pack. It is
written for someone changing this code, not for someone using a pack.

## The format table is a set of decisions, not a set of renames

A pack writes an OpenGL internal format name. The game's `GpuFormat` carries Vulkan names. Mapping
one onto the other looks like a lookup table and is not, because several properties of the target
enum force choices the pack never made.

**The three-component formats are named by the game and left out here.** `GpuFormat` carries the
plain three-channel forms (eight, sixteen and thirty-two bit, normalised, integer and float) and
not one of them is among the constants a target may be given. They are all but never usable as a
colour attachment on desktop hardware, and a target the driver refuses to attach fails with nothing
to read in the log, so a pack asking for one is given the four-channel variant. That is a promotion,
decided by the engine, in one table nothing goes round.

**Promotion changes what sampling returns, and no pack can see it coming.** In OpenGL, sampling a
three-component texture always yields an alpha of one. Promoted to four channels, it yields
whatever is actually in the buffer, which is whatever the clear put there. So a promoted target
starts opaque instead of transparent, and the promotion is named in the log. Without that, a pack
that multiplies by the sampled alpha loses the whole target and nothing anywhere reports an error.

**That correction applies to the engine's default and not to a colour the pack wrote.** A target the
pack gave a clear colour to is cleared to exactly that colour, alpha included, promoted or not: the
pack wrote four components and is handed the four it wrote. The correction only decides the default
the engine stands in with when the pack named nothing, which is opaque black on a promoted target
and transparent black otherwise. The shadow colour buffer answers the same way, and it is worth
saying because it was the one place that did not: a correction meant to fill a blank is not a
correction once it starts overruling something the pack was explicit about.

**Bytes per pixel are read off a table, never recomputed from the channel widths.** A packed word
(two-ten-ten-ten, eleven-eleven-ten) is four bytes whatever its channels suggest, so arithmetic over
the channels gets wrong exactly the formats a pack reaches for. In the game the figure is
`GpuFormat.blockSize()`. The plan that estimates a pack's memory outside the game cannot call it,
runs against no device, and carries the number beside each of its own constants instead: a second
table, and the one place here where two of them have to agree by hand.

The set of names a pack may write is the reference implementation's, and it includes several
formats from before the modern set that have no equivalent at all. Each one is mapped to the
nearest modern format that keeps the kind of data it carried: the small fixed-point relics land on
the eight-bit four-channel format, which is strictly more precision than they asked for, and the
shared-exponent one lands on a float format, since an eight-bit target would not hold it. None of
this is a rename either.

**Filtering follows the reference:** linear on every target except an integer one, where linear
filtering is not defined. Getting this wrong is invisible on a target that is only ever sampled at
texel centres and obvious on one that is not.

Two questions the game gives no way to ask: whether the driver accepts the packed eleven-eleven-ten
float format as a colour attachment, and whether it can filter a thirty-two bit float format
linearly. Neither `GpuDevice` nor `DeviceInfo` exposes a format capability query, so there is no
graceful path. The only defence is ordering: the format is named in the log **before** the
allocation is attempted, so that when a driver dies the last line written names the culprit.

## Where the format directives live, and why a naive reader finds none

Formats are not declared in `shaders.properties` in practice. They are typed constants sitting in
the GLSL, one per target.

The trap is that those lines are **always inside a block comment**, and necessarily so: a format
name is not a GLSL expression, and a compiler that saw the line would reject the file. A reader
that strips comments before scanning therefore finds no format declarations at all, allocates every
target as eight-bit, and produces an image that is still plausible: dimmer highlights, banded
normals, nothing that reads as an error.

So the directive grammar ignores comments entirely, and must not be tightened to look more like a
parser than it is. What it accepts is narrow in a different way: the type is matched by prefix
against a closed list, the key has to be a single word, and the value is whatever lies between the
equals sign and the first semicolon, unvalidated. Widening any of those to be helpful changes which
declarations are found, which changes formats, which changes the image.

A pack commonly declares the same target's format more than once, guarded by one of its own
settings, each occurrence in its own comment block. At most one of them means anything, and which
one was decided before the fold ever runs: the expander leaves the branches nobody took standing,
so that what is scanned is the file the compiler would have seen, and marks them dead. So the fold
reads every occurrence and applies only the live ones. Reading them all would take a format from a
branch the pack's own settings switched off, which is a plausible image at the wrong precision.

## The fold order

Directives are collected across a pack's programs in one fixed order, and the last live declaration
of a name wins. The order is the reference implementation's, and it is not alphabetical or by
directory: shadow composites, begin, prepare, the gbuffer programs (the final pass is among them),
deferred, composite.

Two consequences that are easy to get wrong from first principles:

- **Setup programs declare nothing.** The reference files them with the compute programs and never
  with the programs the fold walks, so whatever a setup program contains, it contributes no format.
- **Shadow composites are folded even though they never enter the ping-pong.** They sit at the head
  of the order, and they run against the shadow targets with a flip counter of their own, so their
  attachment directives name shadow colour targets and never scene colour targets. Their format
  declarations still count.

There are also directives the reference *registers* and never dispatches: the consumer is wired up
but the handler behind it is empty. They look implementable from the outside and are dead in
practice, so implementing them would create a divergence in the one direction that matters: packs
are written against observed behaviour, not against the documented format. This is the general
reason target behaviour is settled by reading the reference rather than by reasoning about what a
directive ought to do.

## Sizes, and the one that cannot be honoured

A target can ask to be smaller than the screen. The value is read with one rule that is entirely
typographic: **a value containing a decimal point is a fraction of the screen, and a value without
one is a count of pixels.** Nothing else distinguishes them.

The value is frequently not a literal at all but the name of one of the pack's own settings, so
substitution has to happen before the value is read. Skipped, the read fails and the target
silently falls back to full screen: the pack keeps working and its reflections are quietly at the
wrong resolution.

A scaled target also cannot join the game's colour target inside one render pass, because a pass
has a single render area and every attachment has to match the first (see below), and attachment
zero is the game's target at screen size. A pack that scales a target its geometry writes therefore
has that pass fall back to the game's target alone: the attachments the pack named for it are
dropped as a set rather than one by one, so every draw buffer of that pass is written nowhere. The
log names the program and says exactly that, which is the point of dropping them at load: the
alternative is the encoder throwing in the middle of a frame.

## The flip convention: one set, and one place allowed to consult it

The rule is a single set of target indices. **A target in the set is read from the alternate half
and written to the primary half; a target outside the set is the reverse.** The reference carries
this same information twice, once for reading and once for writing, with an inversion between them;
its own flipper shows that the underlying state is one set and that a flip is a remove-else-add.
One set is the form to keep, because two copies of one fact is one of them being wrong later.

Two facts about who flips:

- **Geometry passes write the side they read and flip nothing.** They paint over the world rather
  than filter it, so alternating would be meaningless. Only full-screen passes flip.
- **Stage-level pre-flip directives belong to no program.** The reference plays them when it
  constructs each stage's renderer, before its loop, whether or not that stage has any valid source
  in this place. So they are applied at stage opening, driven by the stage's rank rather than by
  which family supplied it, and a stage the place does not provide still opens and still flips. The
  shadow composite stage is excluded, since it addresses the shadow targets.

That last one has a sharp edge: a target flipped by such a directive and written by nobody must
still be added to the doubled set. Otherwise the alternate half it now names does not exist, and
every read of it falls back to the primary half without a word.

Because a wrong parity looks exactly like a correct image, the containment rule is structural
rather than a matter of care: **everything that decides a flip lives in the plan and the schedule,
and no other part of the engine is allowed to replay that walk.** The plan unfolds it once, a pass
carries the frozen answer, and the frame replays what it was given. A second implementation of the
walk somewhere else would agree with the first for exactly as long as nobody adds a pass.

## What the backend refuses

All of these manifest at the first draw rather than at load, which is why they are checked at load
instead. They are constraints of the game's render pass API, not policies of this engine.

**Attachments.** `RenderPassDescriptor` pushes attachments in order, with an explicit "unused" form
for a hole. `CommandEncoder` refuses a descriptor with no attachment at all and asserts that the
first one is present, so **index zero can never be a hole** and every pad goes at the tail. It also
refuses a descriptor with no render area: there is no default, and the message says only that the
render area must be provided.

**Common size.** Every attachment has to be the size of the first. This is what makes a pass mixing
targets of two sizes impossible; it is not a rule the engine gives itself, and it is the reason the
scaled-target case above ends the way it does.

**Per-target state.** `RenderPass` requires the pipeline's colour target state count to equal the
descriptor's attachment count, then compares formats attachment by attachment, holes excluded. The
mismatch message does not name either format, so it cannot tell you which of the two sides is
wrong. On the pipeline side, states are held in a fixed array of eight and the builder's unindexed
setter always writes slot zero: three calls in a row leave one state, and the failure surfaces as
the count mismatch above. Use the indexed form. The builder also refuses blend functions that
differ between targets, which is why a pack's per-target blend directive is inexpressible here and
is named at load rather than silently ignored.

**How many attachments a pass actually has** is not the number of draw buffers the pack named. It
is the larger of the draw buffer count and the number of outputs the fragment stage declares,
capped at eight, with holes padding the tail on both sides at once. Packs that declare more outputs
than the directive gives them targets exist, so the two counts genuinely differ.

**Clears and copies.** Clearing a colour texture is refused while a pass is open and requires the
target to carry both render-attachment and copy-destination usage. Allocation and clearing
therefore happen outside any pass, and being outside one is the caller's job, not something the
refusal will catch. The device hands back a **fresh encoder wrapper on every call**, over one
backend, and the "in a render pass" guard is a field of the wrapper: an encoder taken for a clear
knows nothing of a pass another wrapper opened. Each site here takes its own encoder, which is
cheap and correct where it stands, and worth nothing as a check. That a copy does not convert
formats is covered in [The frame](../frame.md).

**Allocation.** The game's render target allocates depth only when asked, wires the usage set that
the clear and copy paths require, and throws outside a range bounded by the device's maximum
texture size. Resizing destroys and recreates the buffers, so any texture view held across a resize
is dead and nothing says so.

**Synchronisation is free and it is not cheap.** The Vulkan command encoder places a global memory
barrier after every render pass, and again after every clear and every copy. Writing a target in
one pass and reading it in the next therefore requires nothing at all from this engine, and images
stay in one layout end to end with no transitions to manage. The other side of that coin is that a
chain of N passes is N full pipeline serialisations, and clears and copies add their own; that is
the real cost model for anything that adds a pass.

## The device renumbers fragment output locations

[Translation](../translation.md) covers the emitter side: outputs are the one thing that keeps an
explicit location, and their order has to be forced because the backend rewrites each output's
location with its rank in a reflection list. What that costs on the target side is worth spelling
out separately, because two of the consequences are about attachments rather than about GLSL.

**A hole is not left empty, it renumbers.** Declaring outputs zero and two, with nothing at one,
does not produce a gap: everything above the hole drops a slot, and every write after it lands on
the wrong attachment. Hence outputs are declared from zero with no gaps regardless of what the pack
declared.

**The cap of eight applies to how many draw buffer entries are kept, not to how high they may
count.** A pipeline holds eight colour target states and a ninth output has nowhere to land, so the
list is truncated to eight entries. Filtering the entries by *value* instead (dropping those that
name a target above some index) is a different operation and a wrong one: a pack may legitimately
name a high-numbered target, and removing that entry shifts every attachment declared after it by
one.

**Ordering has to survive the rest of the header.** The function that names each output once, in
increasing order, is declared in the header and called as the first statement of the entry point.
It lives in the header rather than next to the entry point because a helper written earlier in the
file that touches an output would otherwise be the first place the compiler meets an output name,
and would take rank zero whatever the entry point does. For the same reason, any wrapper the engine
adds around the pack's entry point (the alpha test epilogue, for instance, which reads the alpha
of output zero) has to be emitted **after** both the declarations and the ordering function. A
wrapper emitted above them reorders every attachment in the program, and the result is a complete,
convincing, wrong image.

## Identity, caching and reload

Two caches sit behind a pass, they are keyed differently, and only one of them is safe by
construction.

**The shader module cache is keyed by identifier, stage and defines: never by source.** Two loads
that ask for the same identifier get the first one's SPIR-V back, with nothing recompiled and
nothing logged. That is silent whenever the two programs declare the same samplers, which is the
common case, and it bites precisely during the hot reload that this area is developed with. So
identifiers carry the load they belong to. They also carry the pass: the same pack file can serve
two passes whose translated text differs (one carrying a discard the other does not), and one
identifier for two texts serves the second whatever the first compiled.

**The pipeline cache is keyed by instance**, not by the identifier a pipeline was built with. Two
pipelines built at the same location with different formats therefore do not collide, which is
exactly what a reload that changes a target's format needs. The price is that the old instances
stay resident until the cache is cleared, so each reload leaves pipelines behind.

Clearing that cache waits for the queue to drain, destroys the pipelines and empties the module
cache with them. That gives the one usable signal there is: after a clear, asking for a pipeline
returns a **new instance**, so comparing the instance handed back for the frame's first program
against the previous frame's is how the engine notices that a resource reload happened, without
re-requesting every pipeline every frame.

The corollary is unpleasant and worth knowing before designing around it: **the pipelines of a pack
being unloaded cannot be handed back.** The cache is indexed by object and the only way to remove
one entry is to empty the whole cache, which would destroy the game's own pipelines while the
current frame's command buffer still references them. Targets, buffers and geometry are released;
pipelines and modules wait for the next resource reload.

## See also

- [The frame](../frame.md): pass ordering, the read rule, clear colours, the seed
- [Translation](../translation.md): how a pack's GLSL becomes what these passes run
- [The pack format](../pack-format.md): where declarations come from and how programs are resolved
- [Sky and shadows](../sky-and-shadows.md): the shadow targets, which follow their own conventions
- [Developing](../developing.md): what can be measured outside the game, and what cannot
