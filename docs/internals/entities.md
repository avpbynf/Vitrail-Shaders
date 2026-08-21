# Entities

[Terrain](terrain.md) is about drawing on a mesh the engine does not own. This page is about the
other half of that problem: geometry the game meshes itself, one frame at a time, out of every
renderer at once. Mobs, block entities, held items, armour, the player's own arm and the foil over
an enchanted thing all arrive through one road, batched together, and by the time anything is drawn
nothing left in a draw says which of them a given vertex belongs to. Almost everything below follows
from that, and from the vertex arriving twenty bytes short of everything a pack asks of it.

## The door is a group of draws, not a draw

The game hands its immediate geometry to `RenderTypeFeatureRenderer.executeGroup`, which walks the
draws of one group and asks `PreparedRenderType.drawFromBuffer` for each of them. That call looks
like the door and cannot be: it opens a render pass per draw, in a try-with-resources, with one
colour attachment, so nothing writing more than one target can be written from inside it. Its caller
has no pass open at all and holds the whole group, which is what lets the engine open one pass over
a **run** of draws and hand the pack every draw buffer it asked for.

A run and not the group. One pass carries one set of attachments and one pipeline's worth of state,
so the pass lasts exactly as long as consecutive draws keep asking for the same program, and
anything else closes it: another program, geometry the engine does not serve, the end of the group.
Nothing is reordered to make runs longer, because the order the game walks its draws in is the order
things overlap in. A group whose last draw was the engine's is closed at the return of the group,
which is not tidiness: the encoder allows one pass at a time, so a pass left standing does not leak,
it makes the next thing the game draws throw.

**What decides which program serves a draw is the `RenderPipeline`**, not the render type and not
the texture. That is the reference's key too, and the reason is that a render type is made per
texture, so there are as many of them as there are mobs on screen, while the pipelines are a fixed
table the game builds once.

Three things do belong to the draw rather than to the pass, and are set again for every draw of a
run: the image, since one pipeline draws every mob on screen and each of them brings its own skin;
the scissor, both ways round, since a rectangle left standing from the previous draw would cut
whatever comes next down to it; and the game's own per-draw transform block, which is what a pack
reads as `gl_TextureMatrix[0]` and which two breezes on screen carry two of inside one run.

One family never reaches this door. The particle renderer implements the feature interface directly
instead of extending the class that owns `executeGroup`, so it has an `executeGroup` of its own,
patched by the particle family's own door, and it is a family apart for that reason alone.

### The three windows

Nothing about a draw says which submission it came from. One dispatcher draws the level's features
and, out of a submit storage the game hands it after the level is finished, the screen's. The hand
used to be in that late call too and is moved out of it, submitted inside the level with a
dispatcher of its own, so the screen is what the windows still have to keep out; everything arrives
with the same pipelines and the same target either way. So the engine reads
the **moment** instead, bracketing the game's own events: the level's opaque features, the level's
translucent features, and the engine's own walk of the world for the light. That was measured rather
than reasoned about. Served all the time, every item in the inventory was drawn with
`gbuffers_entities` under the world's camera matrix, so an inventory came out empty and the item in
hand swayed with the walk.

Inside the light's walk there is no such thing as handing a draw back. Everywhere else a refusal
ends with the game drawing the thing itself, on the target its render type names; at the end of a
frame that target is the finished picture, so a caster handed back would be painted across the image
the player is looking at, once a frame, for as long as the reason lasted. A refusal there becomes a
**drop**, and what it costs is a caster missing from the map rather than a mark on the screen. The
log says which of the two happened, because a reader told the game took over goes looking for
geometry lit by the wrong engine when what is really there is geometry missing from the map.

Every refusal says why, once per reason and per load, and says how long it lasts. The ones that
answer a question about the frame come and go, so the same entity is the pack's on one frame and the
game's on the next, which reads as a flicker; the ones settled by the load hold until the pack is
read again, and steady is the worse of the two to look at because it looks deliberate.

One refusal is worth naming on its own, because reading it the obvious way costs two rows. Two of
the game's four output targets exist only while its improved transparency is on, and an absent one
resolves to the main target while the render type goes on naming it. Asked by identity, the
translucent item and the culled translucent entity rows were refused on every machine with improved
transparency off, which is the default, although the game was drawing them onto the very target the
engine had open. Between them those two carry every experience orb, every translucent item sheet and
the translucent type of every living entity. The question is therefore asked of what the target
**resolves** to. The reference never meets it: it turns improved transparency off as soon as shaders
are enabled.

## A format of its own, and the one instant it comes into force

The game's entity vertex holds six elements: a position, a colour, a texture coordinate, an overlay
coordinate, a light map coordinate and a normal. The engine appends three, and it appends them to a
**format object of its own** rather than lengthening the game's.

That is not a preference. The chunk renderer declares the game's entity format as its own constant,
beside a stride its compiler folds into every caller; it cancels the game's compile for every cuboid
of a model part and writes at that stride into a buffer of its own; and its push reserves room at
the builder's stride and copies the run over **raw** whenever the two formats are the same object.
Lengthening the game's field leaves that identity test passing and copies more bytes a vertex than
were ever written. It was measured rather than deduced: with the game's field lengthened, on one
bench and with only the jar changing, the spider, the enderman, the warden, the charged creeper and
the player's own arm were all gone, and only the enderman's particles were left hanging where its
body should have been.

A separate object fails that identity test, so the same push takes the slow road, which asks the
chunk renderer's serializer registry for a pair of formats. **Registering one is not optional.** Left
with no pair the registry generates a serializer that copies the elements the two formats share, and
the bytes after them keep whatever the arena last held, so a pack would read one mob's identifier
off another's leavings and light it through a tangent that was never written.

It buys a second thing for nothing. The builder picks its eleven-argument fast path for an entity
vertex by that same identity test and writes at literal offsets; under a separate object the test is
false, the vertex falls through to the eleven setters, and every one of those begins at the call
where the engine writes. The reference has to disarm the same path with a patch of its own.

### One answer, at the pipeline

The exchange is answered at the `RenderPipeline`: a pipeline declaring the game's entity format
reports the extended one instead. That is one door and not two. A draw of the level takes its format
from the render type, which is the pipeline's first binding; the feature renderer hands that to the
staging buffer's append; and the builder of the draw is built with what comes back. So the mesh, the
staging buffer and the pipeline's vertex input all follow from one answer and there is nowhere for
them to fall out of step. The reference needs a second interception because it swaps at the builder
instead.

It is also the whole answer to what becomes of a draw a loaded pack does not serve: it keeps the
game's own pipeline, and that pipeline is handed this format too. What makes that safe is that the
three appended elements go **last**, for the reason spelled out for the chunk mesh in
[vertex inputs are matched by name](terrain.md#vertex-inputs-are-matched-by-name-and-one-direction-is-silent):
the game's entity vertex stage declares the six names it knows, the rebinding pass counts only the
names it finds while pipeline creation counts every element, and an element the stage skips shifts
the location of everything after it. There has to be nothing after them.

### Why the answer settles rather than being read live

The reference reads its own gate live, at every buffer and at every call for a format, and can
afford to: it runs against a backend that rebuilds the vertex array from the pipeline's bindings at
the draw. Here the backend bakes the stride into the compiled pipeline, and the pipeline cache is an
identity map that nothing empties but a resource reload. A live answer would leave a mesh built under
one answer bound by a pipeline compiled under the other, which is the wrong stride by another road.

So there is one answer in force and one call that moves it. Its instant is the head of the chunk
renderer being built again from nothing, which is reached from the extract that consumes the world
rebuild: after the previous frame was submitted and presented, and before this one has bound a single
pipeline, so emptying the compiled pipelines there has nothing in flight to tear down. Emptying them
is owed only where the answer really moved, and it is owed: the game precompiles every static
pipeline at every resource load and caches it by identity, so the entity ones standing at that
moment were compiled under the other answer.

**Asking is all a switch does.** Moving one raises the same world rebuild F3+A raises and lets the
next extract answer it; the format follows the settled reading and never the switch, which is what
makes it safe for an entity program that threw to stop being offered at once, mid-frame, without the
mesh moving under a builder. Both switches are read, the entity one and the hand's, because the hand
is drawn from this same mesh: `entities=off hand=on` still needs the elements on the vertex, and the
shadow map's casters are already behind the entity switch.

## What rides on the vertex, and what stays a uniform

**The three identifiers.** A pack tells one entity, block entity or held item apart by three
numbers, and they are carried on the mesh rather than in the uniform block. That is forced by
batching: a group hands back the draw the previous submission went into whenever the render type is
the same instance and that type consolidates, which every quad type does, so a uniform would have to
break the batch at every change of identifier, one draw per mob. The element carries four unsigned
lanes of which three are read, which is the reference's own shape and not a round number picked for
looks, a vertex having to be a whole number of words wide anyway.

Nought is not "unknown" and minus one is. Nought is what all three are worth outside any entity,
block entity or item; minus one is what a table answers for a name the pack never mapped, and the
lane holds it unsigned, so a pack reads the largest a short holds rather than minus one. Both are the
reference's numbers and the packs are written against them.

The only moment those answers exist is the **submission**, during the level walk, so each is taken
around the dispatcher rather than around a renderer, frozen onto the submission node, put back while
that node is turned into vertices, and written on each vertex from there. The held item is dropped
with the entity that was holding it and not at the end of the item itself, an item being submitted
inside that call and closing its own window first. Two names of the pack's entity file are not entity
types at all and are its way of asking a question the registry has no key for, the player the camera
is looking out of and a zombie villager being cured; the conversion is asked before the type, so a pack that
named both gets the conversion. The block entity's number is the pack's number for the **block
state** it stands in, out of the block table rather than a table of its own, which is where the
reference reads it too, and it is reached through an accessor because the render state keeps that
field to itself.

**The overlay colour is not a uniform on this mesh.** The overlay coordinate is the hit flash and the
damage tint, and it is the fourth element and not the fifth: the light map is the one after it. What
reads it is not a name of the prologue at all. The wrapper the translation puts around the pack's
own `main` fetches the texel it points at and hands the colour on as `entityColor`, with the same
guard the reference carries against packs that assume the colour is nought without a flash. That is
where the reference takes it from as well.

What is left in the uniform table for those four names is the right answer rather than a stand-in.
Outside a pass drawn from this mesh, a composite or the terrain or the sky, the reference hands over
a uniform too, and the numbers here are its numbers. Inside one, the name never reaches a table on
either engine. So none of the four is listed among the stand-ins, which is what that list looks like
when it works: what is still listed beside them is the held item, the block in front and the vehicle,
whose tables are read and live and whose asking is not there.

**What a pack still reads as a constant on this mesh is the block id**, and the reference does not
serve it here either: an entity is not a block state and has no id to travel on. A pack branching on
it takes the same branch for every draw, and the log names it at every load.

## The middle of a sprite and its tangent belong to the polygon

The other two appended elements are the middle of the sprite a polygon is mapped to and the
direction the texture's U axis runs in over it, with the handedness of the frame that direction
builds with the normal. Neither is a property of a corner; the four corners of a quad carry one pair
and one tangent between them.

**Both roads into the mesh work them out, and the same arithmetic serves both.** A mob is written by
the chunk renderer at the game's own stride and converted by the serializer; a block entity, a held
item and the hand are written vertex by vertex through the builder and filled by a patch there. The
two see completely different memory and would otherwise have carried two copies of the arithmetic,
which for a tangent is not a tidiness argument: the handedness in the fourth component decides
whether a bump lights as a bump or as a dent, so two copies that drifted apart would light one half
of the picture inside out and nothing would say a word. The sprite's middle is the mean of the
corners' texture coordinates, which each road works out as it walks them and which has nothing in it
to get wrong.

The two roads do part on one thing, and they part where the reference parts. On the chunk renderer's
road the normal is left exactly as it was written, its cuboid writer having already put a face's own
normal on all four corners, so there is nothing a quad could be asked that its corners do not agree
on. On the builder's road a **quad** is measured against a normal taken across its own two diagonals,
which is the whole quad's answer rather than one corner's when the four are not quite coplanar, and a
**triangle** keeps the normal each corner was given and has its corners flattened onto that normal's
plane first. That split is the reference's, and its own note says why: to allow smooth-shaded
triangles.

Two refusals are this engine's own, a third guard is the reference's carried over, and all three
are about the same thing.

- **A quad of no area gives no normal.** The reference normalises whatever the cross product gave;
  here a squared length below a threshold is refused and the caller keeps the normal the game wrote.
  Normalising nought is a value that reaches the colour through the whole tangent frame.
- **A mapping too flat gives no tangent.** Same threshold, same reason, and the value written instead
  is an **axis**. The reference has no single answer to follow: its chunk-renderer road gives up and
  writes a whole word of minus one, its builder road has no test at all and packs what came out,
  which for a tangent of no length is nought. Neither is usable, because every pack normalises what
  it reads: nought is a division by nought whose result travels into the colour, and minus one on
  all three axes is a direction pointing nowhere the texture runs. The axis costs such a polygon a
  tangent along X and nothing else, and it is the same value the prologue hands a mesh carrying no
  tangent at all.
- **Three corners sharing a texture coordinate leave no gradient to invert.** The reference carries
  the same guard and the same substitute value; the point of it is to keep the branch out of the
  division.

One divergence runs the other way. **The game's normal is left as written where the reference writes
its own back.** It does that only inside the level render, and outside it the question never arises
because it does not extend the format there at all; here the format is settled once and bound
wherever the game declares the entity one, so a write-back would reach geometry the reference never
touches, an item drawn into an inventory screen among it. What it costs is the handedness and not the
direction: the face normal is not used to flatten a quad's corners, so it reaches the tangent for the
sign alone, and a quad whose corners were given a normal that is not the face's keeps a tangent
pointing the right way and may get that sign turned over. No entity geometry of the game reaches that
road in that state.

Two mechanics of the builder road are worth knowing before touching it. **A polygon is only finished
one vertex late**, the corners after the one in hand not existing yet, so each vertex leaves its
offset behind and the polygon is filled in at the next vertex or at the build. And **offsets, never
pointers**: the arena grows with a reallocation when the next vertex would not fit, and every pointer
handed out before that moves with it. A stand-in is written on every vertex all the same, so a buffer
whose last polygon is a corner short hands the pack the substitute rather than whatever the arena
held.

## Two halves, and the tables that are twins of one

The game itself splits this geometry. A submitted model goes to the solid submits or to the
translucent ones on whether its render type blends, and the two are executed on opposite sides of the
event the deferred stage runs at. So one table keyed by pipeline carries the split without a column
for it, a pipeline either declaring a blend function or not, and a row offered in the other half's
window is refused.

**What the two halves cost is not the same thing, and that is the whole of why they are two.** The
opaque half is drawn before the deferred stage, so it takes its first draw buffer by writing the
coverage mask, which is what keeps the scene seed off the pixels it wrote: the mask carries the depth
the fragment left, so the seed reads a mob's own depth back at every pixel of it, finds nothing drawn
in front, and leaves the pixel alone. The blending half is drawn after the stage, onto a colour
target the chain has already composed, which is the position the world's own water is in: it takes
that buffer outright, owes no mask, and reads the far side of every target.

Two rows do not follow that sort and had to be checked rather than assumed. The water mask is peeled
off by identity **before** the blend is looked at, so it is in neither half. And a mob's ground oval
never goes through the sort at all: it goes into a phase of its own that the translucent execution
runs first. It reaches the door regardless, its renderer inheriting the same `executeGroup`, and that
was proved rather than assumed, since it is the one that could plausibly have had a group loop of its
own the way the particles do. Where it is submitted at all, that is: while the pack draws a shadow
map the oval is kept off the mob at the dispatcher, which is the reference's rule, so under a pack
with a map no oval reaches any door, and under a pack without one the oval is the only shadow a mob
has and keeps its row.

From that one table three more are derived, row for row but one, and the reason is always
the same: a row too many is a compiled module nobody selects, a row too few is geometry silently
drawn by the game in the middle of geometry the pack drew.

- **The block entities.** The same pipelines asking for `gbuffers_block` instead, which matters
  beyond the name because that program falls back on the terrain where the entity one does not, so a
  chest is lit as the block it is even on a pack that ships no such file. The threshold follows the
  program and not the phase. A skull is where the program and the phase really part company, and it
  is not a corner case: it is a block entity, it draws with an entity pipeline the reference pins to
  the entity program, so it takes the entity program and the block entity phase at once. Three rows
  are pinned that way, and the test for them is a **list** rather than the blend, because one of the
  three blends and still asks for the writing half's name.
- **The hand, twice.** Nothing about a pipeline decides which of the two hand programs answers; the
  pass does, and what separates the passes is which items go into them, settled a step earlier at the
  submission. Every hand row discards at a tenth, the solid ones included, which is the reference's
  answer and not an inheritance from the pipeline.
- **The shadow map**, which the next section is about. It takes the eye tables' rows as well, and
  the one row derived nowhere is the ground oval's twin, for the reason given there.

Two families are in tables of their own because the reference keys them differently, and a twin is
exactly what would destroy that.

**The eyes.** The reference reaches them by a constant that consults nothing, so an eye is an eye on
a mob, in a chest's draw and in the hand alike, and derived into the other tables they would ask for
the block or the water program where it asks for neither. They are also the one family drawn at
**full light**: the light map names are answered with a constant and the sampler behind them with one
white texel. Without it a pack whose eye program multiplies by the light map draws an enderman's eyes
as dark as the block it stands on, and the additive blend the reference hangs on that program name is
what would make that darkness the thing being added. The element is still declared and still bound
either way, because the head has to declare the whole format; what changes is one line of it.

**The glint.** It is the only piece served here that is not drawn from an entity mesh, and the only
one whose pipeline carries more than one render type's worth of answers. It is four compiled pieces
of one program name, one per moment, because the side of the deferred stage is baked into a piece and
the glint arrives on both: which carrier goes where is the game's sort, an enchanted book being
submitted among the solid features foil and all, an enchanted armour piece and a trident and a shield
among the translucent ones. It is in the opaque half's coverage mask and could not be left out: it
blends onto the pixels the piece under it just wrote, and those pixels are the pack's target now.

Because those tables are keyed by pipeline and a texture is all that separates two rows, **two
origins must not land in one draw**. There are two ways they could. The obvious one is the equality
match inside the group's draw lookup; the one that costs a review is above it, where the group hands
back the previous draw without consulting that lookup at all whenever the render type is the same
instance. Both are answered by refusing the reuse when the origin has changed. It costs one draw per
alternation rather than one draw in all, the lookup answering with the first match, so geometry that
really alternates pays each time it comes back; geometry that does not alternate pays nothing. No
pair in the game is known to reach it, which is not a reason to leave it open: the cost of being
wrong is silent and the cost of the guard is one draw.

Three families in this window stay the game's, and are carried in flat by the full-screen layer: the
beacon beam, the lightning, and the text of a name plate or a sign. All three bind a mesh this door
cannot decode. That is not taken on trust either. Every piece states the format it claims, and the
claim is compared against what the pipeline really binds before the pack is read for it, because the
failure it guards is the silent one from
[the terrain page](terrain.md#vertex-inputs-are-matched-by-name-and-one-direction-is-silent): a
picture that stays a picture and reads its texture coordinates out of the light map.

## Casters in the shadow map

The map is filled from a **second walk of the world**, not from a second reading of the frame's own.
That is forced twice over. The game clears the two lists this would have read on the line after it
submits them, and the shadow stage stands at the very end of the frame, so both are empty by the time
it runs. And the camera's lists were culled against the **camera's** frustum, where what has to be in
a shadow map is what the light can see, which is mostly what the camera cannot. The reference walks
twice for the same reasons, with a state, a storage and a dispatcher of its own; a dispatcher holds
one prepared frame and reuses it, so borrowing the game's would re-enter the frame it is in the
middle of.

Seen from the light the tables collapse to **one program** for the lot. The block entity mark buys
`gbuffers_block` against the camera and buys nothing here: the reference's shadow table is keyed on
the same pipelines and answers one key for every entity one, so there is no block row for anything
this engine draws.

The ground oval has no twin because nothing submits it while the map is drawn: the dispatcher keeps
it off the mob for as long as the pack draws a map, and the light's walk submits through that same
dispatcher, so a row would be a module nobody selects. The reference's shadow table has no key for
it either. The eyes are in, and what they reach is worth knowing, because it is not the obvious
answer. Neither eye pipeline writes depth, and the table keeps the write exactly, so an eye paints
into the map's colour and lays nothing under itself: it is not an occluder and it darkens no
shadow. What the row buys is a pack that reads the map's colour seeing the eyes where it
used to see a dropped draw. They take the ordinary caster's row rather than the full light of their
camera side, the reference's shadow key being declared with the light map where the two camera keys
are declared full bright.

Nothing blends into the map, whatever the pipeline a row was made from says, and every shadow program
of the reference is declared with blending off. What a map wants of a translucent surface is the
depth that surface stands at, not that depth mixed with the one behind it.

**The depth state is turned round and not replaced**, which is this repository's standing rule about
conventions applied to one more place. The game rasterises under a reversed depth and the map stores
the forward window, so the comparison is mirrored and the depth bias is negated, the two windows
running in opposite directions; the **write is kept exactly**. Keeping it is what stops the two rows
that are not a plain depth write, an armour decal and a banner's pattern, from becoming occluders in
the map, which would be a decal and a pattern casting a shadow of their own over the surface they lie
on. The reference has nothing to turn round: its shadow projection is a plain forward one built the
same way as its camera's, so its map runs in the same direction as its scene.

**The warm-up frames are where the two halves of this page answer differently, and it is deliberate.**
A camera row is refused while the chain is still compiling, because the chain draws nothing at all
then and a frame that wrote the pack's targets would be a frame with no entity in it. A shadow row is
not asked, and the reason is not the camera's read backwards: a caster is written into the map and
never into a colour target, so the chain's final is not the road it takes back and the state of the
chain settles nothing about it. Whether the map is worth filling on those frames is a separate
question, and the terrain half of the very same map answers it by filling throughout: a map holding
the world and nothing alive in it is the worse of the two to leave standing.

Two culling details differ from the reference and are gaps rather than workarounds. Where a pack asks
for a shorter reach for the casters that move, the reference rebuilds a whole second shadow frustum
at that distance and tests a caster's bounding box against it; here the light's own frustum is kept
and the reach alone is cut, as a box about the camera measured on the caster's position. The two
keep-sets are not nested, so the difference runs both ways. And the player flag is read as the
reference reads it, which is not as a flag: where the pack allows the entities they are all extracted
and the player is one of them, and the player directive is what is left when it refuses them. Read
additively instead, the player would be kept out of every default map there is, its own directive
being off by default.

## The switch, and what off gives back

`entities=off` in the engine's own settings file hands the family back to the game. The line is on by
default, so reaching that state means somebody wrote it, and it is announced in the log all the same,
because what it costs does not announce itself on the screen the way the terrain's does. A mob keeps
being drawn, lit and tone mapped by the game and carried in flat by the scene seed, which reads as a
pack that lights mobs oddly rather than as a family nobody served. **The glint goes back with them**,
both of the halves of it drawn in the world, this switch carrying the two picture pieces and the hand
switch the other two.

The hand has a line of its own, and what it costs is worth two sentences rather than one, because half
of it is not a shader: off, the hand is drawn where the game draws it, which is after the pack's whole
chain has run, so it is not merely lit by the game but painted over an image the pack has already
finished, absent from every gbuffer and from every depth a composite reads.

Neither switch does anything but ask. The mesh follows the settled reading described above, so a
program that threw stops being offered inside the frame it threw in, while the format waits for the
rebuild.

Where a piece's outputs land, and which half of a doubled target each of them writes, is
[render targets](render-targets.md).
