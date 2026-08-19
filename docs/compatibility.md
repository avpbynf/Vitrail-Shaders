# Pack compatibility

## The packs this engine is measured against

These are the packs I run against, and this table says what I have actually seen with each rather
than what is expected of it. **A pack that is not here is not thereby unsupported**: it is a pack I
have not loaded, and the format is the format. Nothing in this table is a judgement of the pack;
every line is a note about this engine.

**A row that says I have not looked means exactly that, and never means it works.** Rewriting this
table is one of the steps of cutting a release, which is the only thing that keeps it from going
quietly stale.

| Pack | What I have seen |
| --- | --- |
| BSL v10.1.3 | Drawn whole, and the one watched most closely. Terrain, water, shadow map, sky, clouds, weather, particles, mobs and the held hand all go through it. |
| Complementary Unbound r5.8.1 | Drawn whole, and watched as closely. Its colour targets, its deferred chain and its shadow map all come up; the log prints how many targets it allocated and at what size. Its two top profiles are the exception, and the pack announces it itself: see [the pack asks for Iris](#the-pack-asks-for-iris). |
| Complementary Reimagined r5.8.1 | Drawn whole, seen beside Unbound, and visually as close to it as the two packs are to each other. Same top-profile exception as Unbound. |
| Bliss v2.1.2 | Drawn, water included. The flat wrong colours its mobs and its held arm used to come out in are gone: that was this engine sending their first output through a target of the game's, eight bits to a channel where the pack stacks two values in sixteen, and both now write the pack's own. It is the pack that reads the light map raw where BSL and Complementary multiply the matrix in, which is why the far terrain's pair is served normalised. |
| Sildur's Vibrant Extreme v2.01 | Drawn, except for its water, which is an open case here. It is the pack that exercises the paths least travelled: it keeps the overworld's programs at the root of `shaders/` and gives the other two dimensions folders of their own, several families reach its textured program through the fallback tree rather than shipping one, and the target its terrain writes first is not target zero. |
| Mellow v3.3 | Drawn, and it exercises two more of them: it ships a three-dimensional volume as a raw blob, and it asks for a single-channel shadow buffer. |
| Body Camera v1.6.1 | Drawn. It is one of the packs that branches on the star flag in the sky, so it is worth reading [the sky goes flat](#the-sky-goes-flat) alongside. |
| Reverie Beta v0.9 | **Refused at load, and the log names what it asked for.** It declares features it cannot be drawn without, and this engine serves no feature flag at all, so it refuses every such declaration whatever it names rather than half drawing the pack. That is a gap here and not a fault of the pack: Iris draws it. See [the pack was refused](#the-pack-was-refused). |

Start from what you are seeing. Each symptom below names its cause, and says how to confirm it
rather than guess.

| What you see | Go to |
| --- | --- |
| Nothing of the pack is drawn, the world looks vanilla | [The pack was refused](#the-pack-was-refused) |
| A red full-screen message tells you to install Iris | [The pack asks for Iris](#the-pack-asks-for-iris) |
| An effect does nothing at all | [The effect never ran](#the-effect-never-ran) |
| Blocks have no relief, however smooth the pack promises | [Everything is flat](#everything-is-flat) |
| Water is missing, or looks like the game's | [The water](#the-water) |
| A hard straight line across the sky near the horizon | [The horizon line](#the-horizon-line) |
| Something that moves looks flat, unlit, or the wrong colour | [Anything that moves](#anything-that-moves) |
| The sky turns into a flat grey sheet at sunrise | [The sky goes flat](#the-sky-goes-flat) |
| Blocks wave, glow or cast wrong shadows after switching packs | [You just changed packs](#you-just-changed-packs) |
| The terrain is uniformly too dark | [Terrain that is too dark](#terrain-that-is-too-dark) |
| Distant land is there but reads as sky: no fog, wrong focus | [The far terrain is flat](#the-far-terrain-is-flat) |

**Before anything else, read the log.** The engine announces what it refused, what it could not
serve, and which program it chose for each pass. Most of what follows is already printed there in
words at the moment it happened.

## The pack was refused

The engine refuses a pack rather than drawing something wrong with it, and it names the reason.

**A pack can be refused for using a graphics feature this backend does not have.** Compute shaders,
shader storage buffers, storage images, and one- or three-dimensional samplers are closed by the
game's own compiler, not by missing effort here. A pack built around voxel lighting or GPU-side
data structures will hit this.

Of the packs used for testing, Reverie is the one in that position, and it says so itself. A pack
can declare the features it cannot be drawn without, and Reverie declares several. That line is read
before any of its programs is translated, so the refusal names what the pack asked for and did not
get (the log lists them) rather than the symptom that would have come later: a storage block,
which compiles but never enters a bind group, so the draw would go against nothing.

**Any such declaration is refused, whatever it names**, and that is wider than the paragraph above:
this engine serves no feature flag at all, so it has nothing to check a name against. It also
defines no `IRIS_FEATURE_`, which is what a pack reads to find out whether it should take the path
it wrote for a renderer that has none: the declaration a pack marks optional rather than required.

Iris draws Reverie. It refuses a required flag only when the name is unknown to it or the hardware
cannot serve it, and it has built every one of the ones Reverie asks for: some outright, some
wherever the driver supports them.

**A single pass can be refused without the pack being refused.** If a program's fragment stage
genuinely *reads* an input its vertex stage does not provide, that one program fails to link and the
engine falls back to the game's rendering for that surface. You get the game's version of that one
thing, not a hole.

Declaring one is not enough to cost the pass. An input the fragment body never reads is struck from
the stage before it is compiled, precisely so that a declaration alone does not refuse a program
that would have worked. What is left after that is a real mismatch.

It usually comes from a body shared between programs, so that a fragment stage ends up reading a
varying the vertex stage it was actually paired with never wrote. Where that lands on a full-screen
pass the cut above handles it, since a quad reads none of them; where it lands on a geometry program
that really does read one, the pass is refused. **The log names which program was refused and which
inputs did not match**: that line, not this page, is what tells you whether a given pack is
affected today, and it is the only thing that will.

## The pack asks for Iris

The image dims and a red message of the pack's own says the feature you turned on is not supported
and asks you to switch to Iris. This is not the engine refusing anything: the pack is running, the
message is one of its passes, and it is drawn because a capability test in its code came out false.

The test reads capability defines. This engine announces itself the way Iris does, but a
capability define is a promise, so it defines only what the backend actually serves, and it
serves no `IRIS_FEATURE_` at all; the section above says why the features behind those names are
closed. A pack that finds the announcement without the capability concludes it is running on
OptiFine, the only renderer in that position when the pack was written, and words its message for
it. Read "OptiFine" as "not Iris" and the message is accurate.

Complementary is the pack of the test set that does this. Its colored lighting, which its two top
profiles Very High and Ultra turn on, is voxel lighting: storage images filled by the geometry
passes and a compute pass that spreads the light, all behind `IRIS_FEATURE_CUSTOM_IMAGES`. Finding
that define absent, the pack switches its colored lighting off, draws the message over the frame,
and leaves every other setting of the profile applied, so the image behind the overlay is the
pack's own and correct. Any profile from High down draws without the message, and so do the two
top ones once their Colored Lighting setting is turned back off.

## The effect never ran

This is the most common false alarm, and it is worth checking before anything else.

**Packs ship their showcase effects switched off.** Depth of field, motion blur, custom sun discs
and auto exposure are routinely off in a pack's default settings. Judging "the blur is broken" at
default settings is judging a pass that never executed.

Two examples from the test packs: BSL gates both depth of field and motion blur off by default, and
its own sun and moon disc and its exposure path are behind settings it ships commented out. So a
full-screen darkening on BSL is not exposure, and the sun disc you see is the game's.

**How to confirm:** turn the pack's own setting on, and check the pass appears in the chain listing
the engine prints when the pack loads. If forcing a setting changes nothing in that listing,
settings have stopped reaching the pack, which is a different and more serious problem.

## Everything is flat

**Blocks that should have relief are perfectly smooth, and turning the pack's advanced materials on
changes nothing.** Relief does not come from the shader pack. It comes from the **resource pack**,
which has to ship a `bricks_n.png` beside its `bricks.png`, and most resource packs ship none. A
pack asking for a normal map that nobody supplies reads a perfectly valid flat one and never
complains.

**How to confirm:** the engine names what it found **at every resource load** (at startup, and
again after F3+T), one line per atlas and per map, saying how many of its sprites answered. Look
near the top of the log rather than around world load. No line means either that no resource pack in
the stack ships a map, or that building them failed, and the failure says so on its own line just
above.

**A mob and a piece of armour are named one at a time, and later.** Their maps are not stitched into
an atlas, so they are read the first time the skin is drawn rather than at the resource load: expect
those lines around the moment a mob first comes on screen, one per texture, naming the texture
itself. A line is printed at the top of a frame and the mob wears its relief in that same frame, so
what a player sees is one flat frame: the one the mob first appeared in.

One place relief still goes missing even with a material pack installed: **an animated block's map
does not animate**. It is named work not done, with what it costs, in
[Material maps](internals/material-maps.md#what-is-not-done), which is also where the rest of the
mechanism lives.

## The water

Water has two distinct failure shapes, and they look nothing alike.

**Water missing entirely: you see the lake bed through an empty surface.** Some packs sample a
colour target before shading the water and discard the fragment when that sample fails a test. If
the read lands on a buffer that still holds the clear, every water fragment is discarded. BSL does
this, which is why the translucent chunk pass is run on the buffers the pack's own deferred stage
wrote rather than on the ones it was given.

**Water that looks like the game's.** That is the fallback working: the pack's water program was
refused for a stage mismatch, so the game drew its own. The log names it.

Water can also be the game's for a much simpler reason: some packs ship a water program in one
dimension only. Sildur's has one at the root, so in other dimensions the water is the game's, drawn
outside the chain entirely.

## The horizon line

**A straight edge across the sky, with a paler band under it, wherever the distant horizon is
clear.** This is a property of the game rather than of your pack.

The game builds its sky as two discs, one above the camera and one below, with a fixed radius. That
leaves a wedge near the horizon covered by nothing, and above sea level the lower disc is not drawn
at all, so *everything* below the upper disc's rim is uncovered. Whatever fills that wedge is what
you see, and a straight rim makes a straight line. It does not take a mountain: anywhere the terrain
does not stand in the way will do.

Vitrail fills it the way the reference implementation does, by drawing geometry the game does not
have: an inverted octagonal cone between the two planes, drawn with the pack's own basic sky
program. If you see the line, that geometry is not reaching your pack's shader.

There are two cases where it is deliberately not drawn, and the log says so in the second: a pack
that switches the sky disc off has taken away the pass the cone rides in, and a frame where the
world's own geometry has not marked the pixels it wrote gets no cone, because one drawn there would
cut the ground out of the picture instead.

The full mechanism is in [Sky and shadows](sky-and-shadows.md#the-horizon-gap).

## Anything that moves

**Something that moves looks flat, unlit, and out of place against the terrain.** It is one of the
families still drawn by the game and composited in, already tone mapped, carrying the game's own
lighting rather than the pack's. Which families those are has shrunk milestone by milestone, and the
engine names the ones left in the log when a place first draws rather than on this page, which would
go out of date between two of them. What follows is what each family that IS served costs.

**The rain, the snow and the quad particles are no longer among them.** They go through the pack's
own programs out of the box, both halves of the particles, and `weather=off` or `particles=off` in
`vitrail/options.txt` hands either family back to the game if you need to compare.

**The mobs and the block entities go through the pack out of the box, both halves of them.** The
body of a mob, a chest, a conduit, an armour piece and everything that blends over one of those is
drawn with the pack's own program: they are lit as the pack lights the world, and the shadows the
terrain casts fall on them. The player's own body in third person is drawn by the same rows, so it
is the pack's too. `entities=off` in `vitrail/options.txt` hands the whole family back to the game
if you need to compare, and with it the glint an enchantment puts over what a mob holds or wears.
What is left is named in the log rather than on this page, by the line the close of this section
points at.

**The hand you are holding has a switch of its own, and it goes through the pack out of the box
too.** It is drawn inside the level, in two passes, served by `gbuffers_hand` and
`gbuffers_hand_water`. With `hand=off` it goes back where the game draws it, which is after the
whole chain has finished: not merely lit by the game but painted onto an image the pack had already
completed, so nothing the pack does to the world reaches it and nothing it draws reaches a
composite.
The engine squeezes the hand's depth the way the reference does, and a pack that divides it back out
with `MC_HAND_DEPTH` gets the same eighth it expects. What blends in the hand goes through the water
pass with the arm, a held translucent block included, so both are the pack's.

**Both passes write the pack's own draw buffers, the first one included**, which is what the
reference does with them. The solid pass earns that the way the world's opaque geometry does, by
writing the coverage mask that keeps the scene seed off the pixels it drew; the water pass is drawn
after the deferred stage, onto a picture the chain has already composed, and takes the buffer
outright. So a pack that writes a normal or a specular map from `gbuffers_hand` can light the hand
from them, and a sleeve or any half-transparent layer blends against what stands behind it.

What a `gbuffers_hand` does not get is this frame's scene depth. `depthtex0` and `depthtex1` are
answered with the far plane, the image of the opaque world not having been taken when the pass is
drawn, and handing it the previous frame's would be wrong by one frame of camera movement.
`depthtex2` is the exception and is a real copy, taken one line before the pass is drawn, which is
the name a pack reads to see what the hand it is holding stands in front of.

**The mobs and the block entities are drawn into the pack's own shadow map**, so they cast as
well as receive, and the log names the shadow passes one by one when a place first draws. A mob's
glowing eyes are drawn into it too and are the one thing there that casts nothing: neither pipeline
the game gives them writes depth, so what they reach is the map's colour and not the depth a pack
reads its shadows from. Two things take that back: a pack can ask for fewer casters than the
default and is given what it asks for, and a draw whose pipeline this engine has no shadow row for
is left out of the map rather than guessed at: the log says so, by name, for each one. **The rain,
the snow, the particles, the hand and the glint of an enchantment are never in it**: they have the
pack's light on them and nothing under them. The glint costs less than the list suggests, sitting
on a body that fills the map on its own, so what is missing there is a tint on a shape the map
already has; and it is the one where the reference does the same thing for its own reason,
cancelling the foil while the map is filled. Receiving and casting are two different things here,
and the second is the shorter list.

**Turn the game's improved transparency off if the rain or the translucent particles do not change.**
It is a video setting of its own, which the Fabulous preset turns on everywhere except macOS. With
it on, the game draws both of those into targets of its own and composes them itself, and this
engine hands them back rather than attach the pack's targets beside an image it does not read. The
log says so in those words, once for the rain and once for the particles. The entities are
unaffected.

Two consequences follow from how that geometry reaches the pack, and both are worth recognising
rather than reporting as separate bugs:

- **The values a pack reads off a polygon are this piece's own, and the ones that describe a BLOCK
  are constants.** The middle of the sprite a face is mapped to and the tangent of that mapping are
  worked out over each polygon and handed over on its corners, which is what a normal map on a mob
  or on a piece of armour is read through; so are the three a pack compares against a kind of mob,
  the block a block entity stands in and the item being drawn. What stays constant is the material
  id and the offset to the middle of a block, and there the reference does the same: an entity is
  not a block and has neither. The held hand shows whatever the mobs show, arriving by the same door
  and in the same vertex format.
- A pack can allocate a colour target for a family that is not drawn through it. BSL allocates one
  for glowing entities alone, and its deferred pass samples that target, so the chain reads a clear
  across a whole target.

The engine names the families that still come from the game, in the log, when a place first draws.
That line is the authority; this page does not duplicate it. A place drawn without a seed does not
print it, and says so on a line of its own instead.

What can still send one of these families back to the game for the seed's sake is narrower than it
was, and the log names it by program: a fragment stage the translation could not place the coverage
mask in falls back on the game's target, and there its own first draw buffer has to be the one the
seed paints, or the game keeps its shader for that half rather than carry the pack's albedo into a
target it never asked for.

## The sky goes flat

**A flat grey or white sheet across the sky, typically at sunrise or sunset.**

Several packs recognise the game's stars by a vertex colour whose three channels are equal and
non-zero. Hand such a pack a plain white vertex colour on a sky pass and it takes its star branch,
painting the whole disc flat. Sildur's and Body Camera both do this.

The engine multiplies the draw's colour modulator into the value the pack reads rather than
substituting white, precisely so that branch is not taken by accident. All of the sunrise band's
colour lives in that modulator (its mesh is white fading to transparent), so substituting white
would both flatten the band and trip the star test.

**So if you do see this, the modulator is not reaching the pack.** That is the thing to check, and
it is not something a pack setting can cause.

## You just changed packs

**Blocks wave when they should not, glow, or cast a shadow that spills past them, and placing then
breaking a block fixes that spot.**

Block numbers travel on the vertex, and no two packs number blocks alike. Chunk sections meshed
while the previous pack was loaded keep the numbers they were built with, so a stone wall can land
inside the new pack's waving-foliage range. Breaking a block rebuilds that section, which is why it
appears to fix it.

The engine rebuilds the world when the table moves and says so in the log. **No image diagnosis
after a hot pack change is worth anything until that rebuild has happened**: check for that line
before investigating anything else.

## Terrain that is too dark

One cause worth naming, because it used to be the answer here and is no longer. Packs can set a
directive asking that ambient occlusion be delivered separately from vertex colour. Left unread it
puts occlusion inside the albedo, which the whole chain then reflects, exposes and moves: a
plausible image that is uniformly too dark, and Bliss was the pack it showed on. **The directive is
read now**, and the mesh keeps occlusion out of the colour where a pack asks for it.

So terrain that is still uniformly too dark is worth reporting rather than attributing to this. It
is diagnosed by comparing two builds at the same camera and the same world time on a corner where
occlusion is strong, not by toggling settings: switching shadows off makes it worse rather than
better.

## The far terrain is flat

**Distant Horizons is drawing its distant land, you can see it, and the pack treats it as though it
were sky.** No fog on it, a depth of field focused past it, water that does not know it is behind
it. It looks like a pack fault and it is not one.

That mod draws its far terrain into images of its own and paints only the colour back onto the
picture: nothing of it reaches a pack by itself. Vitrail hands the geometry to the pack instead,
drawn with the pack's own `dh_terrain` and `dh_water` programs into the pack's own targets, and
serves its depth beside the world's under the `dhDepthTex` names, which is the arrangement packs
are written against. The pack's own distant-land code does the rest: everything it works out on
screen, the fog, the shadows it computes from the LOD depth, the occlusion, runs on the far
terrain from there.

**The log says whether that happened**, and there are three things to look for. Two `Distant
Horizons found` lines, on the first frames a pack is drawn with that mod running, one for its
volume and one for its geometry. The two far terrain passes being drawn with the pack's programs,
one line each the first time they draw. And the far terrain's depth being converted into the
pack's window, whenever those two images are allocated, so the line comes back after a resize.
When the far terrain goes back to that mod instead, the engine says so and names the reason on the
same line: a pack serving nothing for it is the common one, and every other refusal has a line of
its own shape.

Two limits stay whatever the log says. Past Distant Horizons' own far plane there is nothing
drawn, and the picture there is the pack's sky, exactly as without the mod. And the far terrain
does not enter the pack's shadow map yet, a pack's `dh_shadow` not being served, so what shades it
is what its programs compute from the depth rather than a shadow the map carries.

## What packs ask for that is unusual

A short reference, if you are writing a pack or wondering why yours is treated differently.

- **Where a pack keeps its programs is not fixed, and it need not be uniform inside one pack.**
  Sildur's keeps the overworld's at the root of `shaders/` and gives the Nether and the End folders
  of their own. A dimension folder *replaces* the base set rather than layering over it: the full
  rule, including what an empty folder means, is in [the pack format](pack-format.md).
- **A pack need not ship the program a family asks for.** Sildur's ships no terrain, lit-textured,
  entity or hand program; those reach its textured program through the fallback tree, several
  levels deep. BSL ships no lit-textured, particle, item, line or lightning program.
- **Target zero is not special to every pack.** The target the game's image is seeded into is the
  first draw buffer of whatever program ends up drawing the terrain, and for Sildur's that is the
  textured gbuffers program, whose draw buffers start at the fifth target. Anything that assumes
  target zero is wrong for it.
- **A pack can supply its own textures**, including a three-dimensional volume as a raw blob, as
  Mellow does. Since the backend refuses a declared three-dimensional sampler, the volume is laid
  flat onto a two-dimensional atlas and reads are rewritten to interpolate two slices.
- **A pack can ask for an unusual shadow buffer format.** Mellow asks for a single-channel one,
  which is why the shadow pipeline's colour state is built from the attachment rather than
  hardcoded.
- **The light draws into two colour buffers, `shadowcolor0` and `shadowcolor1`**, which is what the
  reference serves a pack that does not ask for more. A shadow program is given the buffers its own
  draw buffers name, and a directive naming a buffer past those two is thrown away whole and
  answered with the pair, which is again what the reference does. **Where a program names none, or
  names more buffers than it writes outputs, it is given only as many as it writes**: a buffer
  short of the reference. That is deliberate: Vulkan leaves an attachment no fragment writes
  undefined for the whole draw, where the GL these packs were written against leaves it standing,
  and what a pack reads out of an untouched shadow buffer is the white a coloured shadow multiplies
  by. The second buffer is not decoration: one pack of the corpus writes the tint of its light
  shafts there and reads it back for every ray that reaches through something translucent, so a
  buffer it could not write filled every body of water with white.
- **A pack can ask the engine not to draw a piece of the sky** because it draws that piece itself,
  inside one of its own programs. Four such requests are honoured (the sun, the moon, the stars and
  the sky disc), and honouring the last two is a **deviation from both references**, which take out
  only the sun and the moon. It costs some packs the stars the references leave them,
  and the NOTICE says so. The fifth request in that family is not one of those four and reads the
  other way round: `clouds` takes `off`, `fast` or `fancy` rather than a boolean, and it overrules
  the user's own cloud setting so that the pack's cloud program is handed the geometry it was
  written for. It is honoured only where this engine really draws the clouds, because with the
  game's own shader behind it `off` would take the clouds away and put nothing in their place.
- **Most packs write `clouds=off`**, six of the eight measured, and it is not a refusal of clouds
  but a redirection: they draw their own, volumetric, inside a composite. Complementary goes further
  and ships a `gbuffers_clouds` that discards outright unless its own cloud style is set to the
  vanilla one. So a pack whose clouds visibly change when the engine starts drawing them is the
  exception rather than the rule, and a pack whose vanilla clouds vanish is usually doing what it
  meant to.
- **Settings are declared in the GLSL, not in a manifest**, and packs disable whole programs from
  their properties file using preprocessor conditions on their own settings. Both Complementary
  packs do this, which is why a flat read of that file reports passes as active that the pack
  switched off.
