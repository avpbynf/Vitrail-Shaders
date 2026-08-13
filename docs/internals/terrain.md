# Terrain

[The frame](../frame.md) says where a pack's passes sit in the game's render and how the buffers
between them behave. This page is about what fills those buffers first: the terrain, the one
geometry family whose vertices Vitrail does not build. They arrive already meshed and cached by the
game's chunk renderer, and almost everything below follows from having to draw someone else's mesh
with someone else's shader.

## Drawing on a mesh the engine does not own

Chunk geometry is built, packed and cached by Sodium. Vitrail meshes nothing and rewrites none of
that packing code. It takes hold at a handful of points instead, each of them one method with no
state of its own:

- the per-pass pipeline compile, memoised by render pass, which is short-circuited so a chunk pass
  is drawn with a pipeline built from the pack's program rather than the renderer's own;
- the accessor that answers with the chunk vertex format currently in use, which is substituted so
  that every consumer builds against a format with room for what packs need;
- the call that opens the renderer's own render pass, wrapped so that the pass carries the colour
  targets the pack's program writes instead of the single attachment the renderer would have opened;
- the draw context, where the program's uniform block and its samplers are bound into the pass the
  renderer has just opened;
- the sites where a quad is written into a mesh - the block renderer and the fluid renderer - and
  the vertex object the translucent sorter copies, which together are how the block id, the block's
  position and its light emission reach the mesh at all;
- the two pieces of section state the shadow stage walks the world with.

The first two are what lets a pack's program run at all, and they are the ones anybody would guess.
The rest are what makes what it draws correct, and leaving them out of a mental model of this area
is how a program that compiles ends up drawing the wrong picture.

Two properties of the format substitution are constraints, not details.

**The format may only change where nothing holds the old one.** Every caller of the accessor - the
section manager, the mesh builder, the per-region device resources - keeps whatever it was handed
when it was constructed, and the last of those three is built on demand at a region's first upload,
so it goes on asking all through a session. A format that moved between two of those calls would let
meshes built at one stride land in an arena sized for another, and nothing in the renderer would
notice. So the engine takes the answer at the single instant the chunk renderer is being built again
from nothing, and merely repeats it everywhere else. Turning the terrain switch on or off asks the
game to rebuild the world, which is the same door F3+A uses; it does not ask for a restart.

**Everything the pipeline declares must be bound.** Descriptor flushing walks the entries of the
bound pipeline, so uniform and texture bindings the chunk renderer emits unconditionally for its own
shader are simply never looked at, and are harmless. The other direction is not tolerated: something
declared and left unbound raises. That is the good direction for a failure to point.

## What the compact vertex carries, and what it does not

The chunk vertex is deliberately small, and every bit of it is spoken for: an interleaved quantised
position, a colour, a texture coordinate, and one word holding light, material bits and the section
index. Four elements. Several of the things a pack asks for are already inside them, in a form worth
knowing before writing any shader glue.

**The colour already contains lighting.** The encoder multiplies the block tint by ambient occlusion
before writing it, and face shading is baked in the same way. Two consequences: no translated
program will ever produce "flat unlit albedo" as long as vertex colour comes from there, and "the
sides are darker than the tops" is true before any normal exists and therefore proves nothing about
one. Any test of a normal has to be an A/B on the same scene from the same camera.

**Unless the pack asked for the two apart.** `separateAo=true` in `shaders.properties` says the pack
wants the occlusion where it can see it: the tint goes into the colour untouched and the coefficient
into the alpha, which is free because block geometry never uses that alpha for anything else. Six of
the eight packs of the test corpus write the line, and it is the reference's behaviour and not an
option of this engine's. Read otherwise, the occlusion lands in the albedo and is then reflected,
exposed and graded by everything downstream, which is a picture that looks plausible and is wrong in
a way no screenshot shows.

It is a property of the MESH, and that has a consequence worth stating plainly: **a colour written
that way can only be read by a program of the pack's own.** The game's own chunk shader multiplies
the vertex colour into the texture and then alpha tests the product, so an occlusion left sitting in
that alpha punches holes through every cutout block on screen. So the answer is not simply "what the
pack asked for": it is "what the pack asked for, while this engine is really the one drawing the
terrain". The warm-up is what makes the difference matter, a chain warming one program a frame after
every load and every resource reload, and the game draws the world during it. The answer is
therefore polled on the client tick, and the tick it moves on has every section built again - which
is also what covers two packs that both draw the terrain and disagree about the directive, where
nothing else about the format would have changed.

**Light arrives raw, and the scale belongs to the texture matrix.** The pair is carried as the game
stores it, a level times sixteen per channel, and the smooth pipeline interpolates between those, so
it is a number from nought to two hundred and forty and not a texture coordinate. What turns it into
one is `gl_TextureMatrix[1]`, which a pack multiplies it by itself: a scale of 1/256 puts a level on
its own texel of a sixteen-by-sixteen lightmap and a translation of 1/32 puts it on that texel's
centre, which matters because the map is filtered linearly. Dividing in the vertex prologue instead,
and leaving that matrix at identity, is the mistake to avoid - it lands every level on a texel edge,
and it hands a pack reading the coordinate without the matrix a different number from the one every
pack is written against.

**The section index rides in the same word.** Translating by it is not optional: without that
translation every section of a region stacks at the region's corner. It is a separate quantity from
the region offset that arrives by push constant, and both are needed.

**The texture-coordinate shrink has to be reproduced.** A high bit of each texture-coordinate
component selects a direction, and a small per-axis bias is added along it. The bias comes from the
atlas size and from a sub-texel precision that is written in as a constant: the renderer's own value
is the same on every platform but one, and nothing here asks the device, because the number is only
right if it is the number the mesh was built against. Without it, a corner falling exactly on a
sprite boundary samples the neighbouring sprite of the atlas, which shows up as a fringe along the
top of grass blocks. The engine hands that bias to the shader as a block member the translator asks
for, alongside the depth-conversion constant.

What the format does not carry is the interesting half: **no normal, no block id, no mid texture
coordinate, no mid block, no tangent.** Packs declare all five as a matter of course, so none of them
is an optional extra, and the engine appends an element for each. The mesh therefore doubles: twenty
bytes the renderer packs and twenty this engine adds after them.

One further difference to keep in mind when comparing images: the chunk renderer samples the
lightmap at the vertex, while a pack handed a lightmap coordinate samples it at the fragment. That
is not a defect - it is finer - but it means two images can differ for a reason that has nothing to
do with the change under test.

## Where the missing quantities travel, and why spare bits were not enough

The instinct is to add fields to the vertex and fill them at mesh time. That means editing the
innermost, most optimised code of another project, which is exactly the kind of coupling that breaks
on every release. So widening something already packed is what gets tried first - and the attempt is
worth knowing, because it works for everything opaque and is quietly wrong for everything
translucent.

The packing routine gives a whole byte to the material and leaves bits free above it, both inside
that byte and in the word the caller hands over. Anything written there survives as far as the
encoder **only on the path where the caller reaches the encoder**. A translucent quad does not take
that path: it is handed to the sorter and returns before the push, and the sorter writes it out
later under a constant material. Whatever was in those bits is gone by then, and nothing says so. A
fluid does not take it either, having a renderer of its own.

So nothing rides on the material. What survives every path is **the vertices**, which the sorter
copies field by field: a field put on the vertex object by mixin is carried through that copy and
reaches the encoder whichever road the quad took. The block id, the block's own position and its
light emission all travel that way, and the encoder turns the last two into the offset from a vertex
to the middle of its block.

**Three of the five are properties of the quad rather than of a corner**, and are computed in the
encoder from the corners it already has. The middle of the sprite is their mean. The normal is
Newell's sum over the loop, which is what makes it right for a plant drawn as a cross, a sloped
fluid surface and any model that is not a box: a face direction offers six axes and a seventh value
meaning none, and not one of them describes those. The tangent of the texture mapping comes from the
same corners and their texture coordinates, with a sign saying which way the third axis of that
frame turns; a pack rebuilds its bitangent from the tangent and the normal together, and where it
reads that sign, the sign decides whether a bump lights as a bump or as a dent. A pack is free not
to read it, and Body Camera does not: it crosses the tangent with the normal and keeps the result
unscaled, which is one of the two chiralities applied to every quad, so the frame it builds is
inverted wherever the mapping runs the other way.

**A mapping too flat to yield a direction is where this engine parts from Iris**, the reference it
follows. Iris keeps a direction wherever it can: where the determinant is exactly nought it puts one
in place of the reciprocal and carries on, and where the tangent still comes out as nothing it keeps
whatever tangent it last computed, a value its encoder holds for every section a worker builds. This
engine refuses in both cases, and on a wider test in each: it refuses a small area where Iris
refuses none at all, and a sum of absolute components before normalising where Iris tests an exact
zero after. The quad then gets an axis taken from its own face normal, and keeps whatever handedness
one of its triangles managed to measure, or the majority answer when neither did. The gap is
narrower than it sounds, because Iris strips the normal's component out of every tangent it packs
and substitutes an axis of its own when nothing is left; but it is real, and it runs both ways,
since this answer depends on the quad alone where that one depends on the order its bucket was
filled in, and the two substituted axes are not the same axis. Nothing in the graphics API forces
any of it.

How far those refusals reach is not known. Every `uv` rectangle in the game's own block models with
an axis of no extent belongs to a face that has no extent of its own, the edge-on side of a flat
element, so it covers no pixel, and whether such a quad reaches the encoder at all was not measured;
the threshold is in atlas coordinates besides, which a resource pack moves.

Those fields are written whether or not a pack is drawing. The renderer ignores what it does not
read, and they go nowhere at all when the format has no element for them, so nothing written that
way is ever read back; what the writing costs on a section built with no pack drawing has not been
measured. What settles it is the other side: making them conditional adds a second switch that has
to agree with the one the format already follows.

The format layout follows two rules. A vertex size must be a multiple of four, so each new element
costs a whole word even where the value needs less; that is arithmetic, not a choice. And the four
original elements are re-declared at the offsets they already occupied rather than repacked from
zero, so any padding the renderer left between two of them survives untouched.

**The new elements are last, and that is structural rather than tidy.** The renderer's own shader
declares the four elements it knows and ignores the rest. By the rule in the next section, an
element the stage does not declare shifts every element after it - and there is nothing after the
last one. That single placement decision is what lets the renderer keep drawing through a format it
was never told about.

**They are all appended, never chosen per pack**, where the reference builds its format out of the
names a pack's compiled programs really reference. The reason this engine could not do the same was
that its format was settled before any pack was chosen, and that is no longer so: the format now
follows the pack. What is left is a plain difference in what the vertex costs, and it has not been
closed. Seven packs of the test corpus read the sprite middle and eight read the tangent, so what a
conditional would save is small either way.

The encoding itself matches the reference implementation term for term: the id plus one, shifted up
by one bit, with the low bit flagging a fluid, and a default of minus one so that an unmapped block
decodes to minus one rather than to a real number. The vertex stage declares an unsigned input and
the translator injects the decode in whichever type the pack declared it with - see
[Translation](../translation.md). One GLSL detail forces the shape of that injection: a global
initialiser must be a constant expression, so the variable is declared bare at file scope and filled
in the prologue.

Which numbers a pack attaches to which blocks is the pack's own table, described in
[The pack format](../pack-format.md). Because those numbers ride on the vertex, sections meshed
under one pack keep them after a change of pack, which is the mechanism behind
[a familiar symptom](../compatibility.md#you-just-changed-packs).

## Vertex inputs are matched by name, and one direction is silent

The compiler is handed the ordered list of the bound format's element names and rebinds the stage's
declared attributes onto locations from it. The match is by name, and it is asymmetric:

- a name the stage declares that the format does not carry makes the program fail to link, loudly,
  which is the harmless direction;
- an element present in the format that the stage does not declare shifts every location after it by
  one, with no message anywhere.

The cause of that silence is worth stating exactly, because it is not obvious from either side. The
rebinding pass advances its location counter only for attributes the stage actually declares, while
pipeline creation advances a location for every element of the format. The two counters agree as
long as the stage declares the whole format, which is precisely what the game's shaders and the
chunk renderer's shaders do - so neither project can ever meet the bug. Translated pack programs
declare varying subsets and would meet it constantly, and it presents as an image that is plausible
and wrong rather than as an error.

The remedy is not a subset but the whole thing. A translated vertex stage is emitted with **every**
element of the format it will be drawn against declared, whether the pack's body mentions it or not,
so the two counters cannot part company. Which format that is comes from the pass and never from the
file: the same `gbuffers_terrain.vsh` would read a chunk mesh under one renderer and a quad under
another, and the pack says nothing about it. What is checked outside the game is that invariant: the
vertex stage of a translated program declares exactly the elements of the format it will be drawn
against, no more and no less. There is a subtlety on top of
that, described for the sky in [Sky and shadows](../sky-and-shadows.md), where the same rule has to
be answered per pass rather than per family: a declared but unread input can be optimised out of the
compiled module, and rebinding counts only survivors.

## Three chunk passes, and why one is not enough

Chunk geometry is drawn in three passes - solid, cutout and translucent - and each compiles its own
pipeline, so each is substituted separately, on its own merits. The pack's program is looked up by
pass; a pass the pack ships nothing for keeps the game's own shader, and so does a pass that is none
of the three, since the pass type is a plain class rather than an enum and a mod adding one is a
thing it allows. Serving all three where a pack provides all three is still what you want, and the
reason is the transform.

The renderer's shader computes the clip position with a single product of projection and model-view.
A pack's program instead round-trips through the inverse model-view and back, and the engine's
epilogue then rewrites the depth component for the depth convention packs expect. Extra matrix
products plus an affine conversion mean two coplanar surfaces served by two different programs are
no longer guaranteed to resolve to the same depth pixel by pixel. Since cutout geometry commonly
sits flush against solid geometry - an overlay quad on a block face - a seam between the two is a
seam between two different transform paths. Serving all three passes makes the comparison
deterministic again; a depth bias would hide it without fixing it. A pack that leaves one of the
three to the game is exactly where that seam is to be expected.

The material byte's low bits look as though they belonged to this question and do not. One bit asks
for mipmaps and two index a small table of alpha thresholds, and those bits are the chunk renderer's
own business: its shader is the only thing that ever reads them, and it no longer even calls the
cutoff. A substituted pass never looks at them. The alpha test a translated program is drawn under
comes from one of two places instead: the pass's own default, which is the reference
implementation's value for that pass, or a line in the pack's properties file naming the program
that serves it, which wins over it. That second one is why a pack shipping a single
`gbuffers_terrain` for both the solid and the cutout pass moves both of them with one override.

There is a practical corollary for anyone choosing a witness block for a test: a block drawn in the
cutout pass never reaches a program that has only been substituted on the solid pass. Foliage is
cutout. Picking a leaf block to prove that block ids arrive is a test of nothing, and it looks like a
failure of the feature.

Where a terrain program's outputs land, and which half of a doubled target it writes, is
[render targets](../internals/render-targets.md).

## Push constants belong to a namespace

The game's Vulkan backend never fills the push-constant ranges of a pipeline layout - it creates the
layout with descriptor sets only. The chunk renderer repairs that for itself, from its own patch,
and **only for pipelines whose identifier namespace names the renderer**. It then pushes the region
offset, the region age and the region id into whatever layout is currently bound.

The consequence for a substituted pipeline is severe and easy to miss: a pipeline named outside that
namespace receives a push into a layout that has no range for it, the region offset never arrives,
and the entire terrain draws stacked near the world origin. The remedy costs nothing and is not
another patch - the test is a substring, so any namespace that contains the renderer's name is
enough.

The trap has a mirror image, and it bites in the other direction. A render pass the game opened for
itself has no region and no push constants; borrowing the terrain's namespace there pushes constants
the pass cannot satisfy. That case is covered in
[Sky and shadows](../sky-and-shadows.md).

## The view bob is in the projection, and packs expect it in the model-view

This one is invisible standing still, which is why it can survive a long time.

The game applies view bob, and the nausea effect with it, by multiplying into the **projection**
matrix. Packs are written against an era where those were pushed onto the model-view stack. So every
pack, without exception, receives a model-view carrying no bob and a projection carrying it.
Anything a pack projects to screen starting from a direction then slides at the rhythm of the walk
cycle: a sun or moon glow that should hold still wanders, a held torch's light shifts between steps.
A fixed effect moving with the bob is the signature of this and of nothing else.

The correction is confined to what is published to the pack. Three operations in the level render -
a multiply, a rotate and a scale - are intercepted, accumulated into a separate matrix, and then
**replayed unchanged**, so the game draws with exactly the matrix it would have drawn with and
nothing about its own image moves. What differs is only the uniforms: the projection handed to the
pack is the clean camera projection, the model-view handed to the pack is bob times view, and the
product of the two is the matrix the frame is actually drawn with. The order matters and is not
interchangeable - bob times view, not view times bob.

That confinement is a deliberate divergence from the reference implementation, which moves the bob
inside the game's own matrices instead. Doing it that way then forces the held item to be re-bobbed
by hand, since it is drawn with the same model-view and has to stay put. Keeping the change inside
the published uniforms means that problem never arises. It also means the reference's fourth
interception has no counterpart here, which is just as well: the method it wraps has a different
shape in this version of the game, and a patch descriptor that no longer matches its target fails
silently when injectors are not required.

Because the whole thing rests on having intercepted *every* operation that touches the projection,
it carries its own witness. Each frame, the clean projection is multiplied back by the accumulated
bob and compared against the matrix captured on its way to the device. If the game ever multiplies in
a term the interceptions do not see, the pack would receive an amputated projection together with a
model-view that does not compensate for it - a plausible image with wrong reprojection. Instead the
sharing is abandoned and the log says so. It is abandoned for the **session**, not for the frame,
and that is a decision rather than an omission: a later frame that happened to agree would publish
the clean projection against a model-view carrying no bob, so the terms would be in neither of the
two matrices the pack is handed, which is worse than the frame that failed. The capture is not dead
code once the defect is fixed; it is the control.

One symptom class is worth naming because it does not look related. A screen-space occlusion test
that reconstructs a world position from the depth buffer drifts relative to the image it is reading
when the projection carries a term the reconstruction does not. The visible result is light bleeding
through solid blocks - a lighting bug in appearance, a matrix bug in fact.
