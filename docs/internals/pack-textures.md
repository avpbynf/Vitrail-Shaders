# Textures a pack supplies itself

[The pack format](../pack-format.md) states the rules this subsystem obeys. This page is the level
below: why each rule exists, what breaks without it, and how a file shipped inside a pack gets from
an archive entry to a sampler a shader reads. Much of it is a security page, because a texture
directive is the one place where a downloaded text file names a file on the player's disk and the
engine opens it.

## Two families of declaration, and both are read conditionally

A pack declares a texture of its own under one of two key families. One names a texture by name and
claims that name in every stage; the other names a stage and a sampler, and claims the name in that
stage alone.

What the value means is decided by **how many words it holds**, not by what they say. A single word
is the path of an image. The longer forms declare a raw blob, with its shape, its dimensions and its
pixel format spelled out - six words for one axis, seven for two, eight for three. Any other count
is a line nothing can honour, whatever the words in it look like.

Both families are read through the pack's own preprocessor conditionals, using the same condition
stack and evaluator the GLSL goes through, because packs do put texture declarations behind their
own settings. Read flat, a declaration sitting in a dead branch takes a live colour target away from
the pass that reads it - and that is not a missing texture, it is a pass reading a file where the
frame expected an image the frame itself produced.

## A declared name stops meaning a render target

This is the point of the feature and the source of its worst failure mode. Sampler names in a pack
are shared between two very different things: the colour targets the frame writes and reads
([The frame](../frame.md)), and whatever the pack binds itself. A declaration rebinds the name to
the file, so a program sampling it reads the pack's own image rather than the scene.

Two details follow, and the first is a place where the two families genuinely differ.

A colour target answers to more than one spelling, a legacy name beside its modern one. The family
that **names a stage** resolves across those spellings, so a declaration written under either name
is honoured under both. The family that names a texture on its own does **not**: it is resolved and
reported under the exact spelling it was written with, so a declaration written under a target's
legacy name leaves every program that samples the modern name still reading the target. That
asymmetry is worth knowing before concluding that a declaration was ignored.

Second, where both families claim one name, the form naming a stage is taken first, being the more
precise of the two.

## Every path a pack writes is untrusted input

A shader pack is a file someone downloaded, and a texture directive is a line of that file naming a
file to open. Such a path is exactly as untrusted as an include specification, and it reaches the
same filesystem.

Two things have to be true of it before anything opens it. It has to be **normalised**, so that the
segments meaning "go up a level" are resolved rather than carried along; and the result has to be
**confined**, checked to still sit inside the pack's shader root after that normalisation. Without
both, a path that climbs out of the pack has the engine read a file the game process can reach and
hand its bytes to a shader as a picture. That is an arbitrary read driven by downloaded content, and
it happens while the client is still starting up.

The parade is not a check but a **single resolution road**. Texture paths go through the same
resolution an include takes, rather than being given one of their own, because a second road has to
be found and secured separately and is the one that gets forgotten. The same door serves the
directive naming a pack's noise image, which is why one omission there covered more than one family
of directive.

One detail of that road is easy to get backwards and silent when wrong. A path written with a
leading slash means "relative to the shader root", and the slash has to come off **before**
resolution rather than after: handed something that looks absolute, path resolution throws the base
away, searches from the root of the archive and finds nothing. A texture that is not found reads
black rather than raising, so the mistake never announces itself - and packs do write their texture
paths in that shape.

A path carrying a namespace is a different case: it names a resource the game owns and hands out
through its own manager, not a file of the pack. It is refused on that ground before the pack is
searched, because looking for it inside the pack first would report it as a missing file and send
whoever reads the log to the wrong place.

## Matching without case, and where that fallback sits

Packs are authored on filesystems where a name disagreeing with the file in case still opens. Inside
a zip it does not, so the same pack loads as a folder and loses a file once archived - the pack is
not wrong in one shape and right in the other, it is the two shapes that disagree. Resolution
therefore falls back to a case-insensitive match against the listing of the parent directory, built
on demand and cached per directory, and the hits are counted so that a pack relying on it can be
named rather than silently accommodated.

The ordering is what keeps the fallback harmless: confinement is decided on the normalised path
first, so the fallback only ever lists the parent of a path already inside the pack. Placed before
the check, it would be a second and weaker resolution road, which is the very thing the single-road
rule exists to prevent.

## A cap on the file does not cap the decode

Pack files are read under a ceiling on bytes, and that ceiling says nothing whatever about what
decoding one costs. Compression is the reason: an image of uniform colour and enormous dimensions
compresses to almost nothing and asks for gigabytes once decoded, and a lookup table of a few dozen
kilobytes expands to tens of megabytes with nothing in the file announcing it.

So an image is decoded in two steps rather than one. The dimensions are in the header, in the first
bytes of the file; the pixels are the allocation. Going through a reader that exposes the header
before the pixels is what creates a moment in which a refusal is still possible - a single call that
turns a file into an image offers no such moment, and that is the entire argument for the more
awkward interface. Two bounds are applied there: a limit per side, which is what a device will
accept, and a limit on **total texels**, which is what the memory actually is and what nothing else
in the pack states.

## A texture that cannot be served costs only that texture

Allocation is attempted and caught per texture. The name that fails reads one black pixel and is
named in the log, where a catch placed around the whole set let one texture a device refused bring
down every colour target of the pack - a black screen instead of a missing lookup table. The same
arbitration governs the shadow map: one feature is not worth the pack.

A declaration whose file is missing, unreadable, or **shorter than the length its own declaration
announces** is refused here as well, rather than bound to something. That is a deliberate divergence
from Iris, which logs and leaves such a sampler on the default texture unit, so the shader reads
whatever happens to be bound there. What comes out is a plausible image built on the wrong data.
Black is a question a player asks; a plausible wrong image is not.

## A refusal must still consume the name it claimed

The rule here that looks like a nicety and is the opposite of one.

**The key claims the name; the rest of the line only decides whether anything can be put behind it.**
Once a directive has named a sampler, no word further along that line unsays it - not a misspelled
pixel format, not a word count the format gives no meaning to, not a file that is not there.

The reason is the collision described above. Hand the name back on a refusal and it falls through to
the colour target sharing that name: the pass reads the scene where the pack asked for its own
table. Nothing in the log connects to what the player sees, and the result is the picture nobody
questions. Claimed and refused, the sampler reads one black pixel instead, and the directive and its
reason are named.

Two shapes of key still claim nothing, and one of them is a rough edge rather than a design choice.
A key too broken to name a sampler at all has no name in it to claim. But a key naming an
**unrecognised stage** also claims nothing, even though the sampler after the dot is perfectly
readable - it is refused whole, so the sampler name falls back to the colour target and the pass
reads the scene. That is the exact failure this section exists to prevent, reached by the one door
still open, and it is worth knowing when a declaration seems to have been ignored: check the stage
name first.

A refused declaration of the family that claims a name everywhere diverts that name in every stage,
exactly as an honoured one would.

## A volume becomes an atlas of slices

This backend binds two-dimensional and cube samplers and nothing else, and the refusal is on the
**declared type**. That distinction is what makes the mechanism both necessary and possible.

To be exact about what "two-dimensional" means here: the check is on *dimensionality*, so the
shadow, array and multisample spellings all carry the same two dimensions and pass it - the
rectangle spelling does not, whatever it reads like. **That is wider than what the device can build
a view for**, which is a plain two-dimensional view or a cube one and nothing else. A pack declaring
an array or multisample sampler would therefore get past the declaration check and find nothing
behind the name. No pack of the corpus declares one, so this is written down as a hole rather than
as a symptom.

Necessary, because the compiler's reflection lists a module's whole resource list: a
three-dimensional sampler declared in a shared include and never sampled costs the program its
pipeline exactly as one read on every pixel does. Supplying a genuine volume would not help either,
since the type is the refusal.

Possible, because nothing inspects what actually sits behind the sampler. So the volume's slices are
laid out on a two-dimensional atlas, as square as they go; the declaration is rewritten to a
two-dimensional sampler under a forged name; and each read becomes a helper that samples two slices
and mixes them. [Translation](../translation.md) covers the rewriting machinery this rides on.

Two readers now have to agree texel for texel: the one spreading the pack's blob into the atlas, and
the one printing the addressing arithmetic into the shader. They agree by both coming from a single
layout computed once. Written twice, they would diverge somewhere, and a noise lookup that is wrong
looks exactly like a noise lookup that is right - there is no observation on screen that separates
them.

**The gutter is the part easy to leave out and impossible to see afterwards.** Each slice is laid
out with one texel of margin on all four sides, holding the wrapped copy of the opposite edge.
Without it, the hardware's bilinear tap at the edge of a tile reaches into the neighbouring tile,
which is a different depth entirely, and the result still looks like noise. With it, that tap reads
exactly what repeat addressing would have read on a real volume. Depth needs no gutter of its own:
nothing interpolates between tiles in hardware, and the helper does that half itself.

**The atlas needs a bound that the blob's does not give.** The blob is held under the ceiling on a
pack file and checked against the length its declaration announces, but the atlas is what those
texels are laid out *as*: a declared shape with one long axis and a thin one lays its slices out in
a line, so a modest file spreads to a width no device will allocate and that nothing in the file
said. The layout is therefore checked against a limit per side and on total texels before the volume
is served, and one that does not fit refuses the directive by name like any other refusal.

**Nothing moves unless everything can.** A name reached in any way other than the plain lookup the
helper replaces - taken as a function parameter, reached through a macro, sampled with an extra
argument - is counted and left exactly as it stands, declaration included. The program then stays
refused with the message it already had, rather than being rewritten into something that compiles
and reads wrong. Where one name carries more than one declaration, the first is served, since the
first is the one whose layout was printed into the shaders; spreading a later file over the first
one's tiling produces, once again, something that looks like noise.

**Divergence from Iris, and a deliberate one.** Iris rewrites a volume in the stage its directive
names. Under a GL backend that suffices: the untouched three-dimensional declarations elsewhere are
bound to nothing, which is tolerated. Vulkan refuses them, so the rename is applied in every program
carrying the declaration, whatever stage the directive named. It invents nothing - the pack named
exactly one file for that identifier, with its shape, size and format - and it is why a forged name
is answered without consulting the stage at all. Iris remains the authority on what a directive
means; see [the note on sources](../README.md#a-note-on-sources) for how that authority is used and
credited.

## Why a volume asked to be clamped is not laid out flat

The wrapping lives in two places that cannot be undone at the sampler: the gutter holds wrapped
copies of the far edge, and the printed helper wraps. Serving a volume the pack asked to clamp with
those would be right everywhere except within half a texel of its border, which is the exact shape
of a plausible wrong picture.

The trap is that a raw blob is filtered and clamped by default, unless the metadata beside it says
otherwise. That default is the format's rule rather than a taste, and a defensible one: a blob
carries a lookup table as often as an image, and a table read past its edge or between its entries
answers with a value that was never in it. A volume shipped without that metadata therefore lands in
the clamped case, its declaration stays three-dimensional, and every program carrying that
declaration fails to build.

Which is why the log says so in as many words at the moment the volume is read. The refusal that
follows cannot say it: "this program declares a sampler the backend will not take" does not point
at a missing metadata file, and a symptom that does not name its cause is the kind that costs
someone a day. [Pack compatibility](../compatibility.md) collects those symptoms from the other
end, starting from what is on screen.
