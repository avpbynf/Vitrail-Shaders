# Pack compatibility

Start from what you are seeing. Each symptom below names its cause, and says how to confirm it
rather than guess.

| What you see | Go to |
| --- | --- |
| Nothing of the pack is drawn, the world looks vanilla | [The pack was refused](#the-pack-was-refused) |
| An effect does nothing at all | [The effect never ran](#the-effect-never-ran) |
| Water is missing, or looks like the game's | [The water](#the-water) |
| A hard straight line across the sky near the horizon | [The horizon line](#the-horizon-line) |
| Mobs, particles or the held item look flat and unlit | [Anything that moves](#anything-that-moves) |
| The sky turns into a flat grey sheet at sunrise | [The sky goes flat](#the-sky-goes-flat) |
| Blocks wave, glow or cast wrong shadows after switching packs | [You just changed packs](#you-just-changed-packs) |
| The terrain is uniformly too dark | [Terrain that is too dark](#terrain-that-is-too-dark) |

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
can declare the features it cannot be drawn without, and Reverie names four. That line is read
before any of its programs is translated, so the refusal names what the pack asked for and did not
get, rather than the symptom that would have come later - a storage block, which compiles but never
enters a bind group, so the draw would go against nothing.

**Any such declaration is refused, whatever it names**, and that is wider than the paragraph above:
this engine serves no feature flag at all, so it has nothing to check a name against. It also
defines no `IRIS_FEATURE_`, which is what a pack reads to find out whether it should take the path
it wrote for a renderer that has none - the declaration a pack marks optional rather than required.

Iris draws Reverie. It refuses a required flag only when the name is unknown to it or the hardware
cannot serve it, and it has built all four of the ones Reverie asks for: two outright, two wherever
the driver supports them.

**A single pass can be refused without the pack being refused.** If a program's fragment stage
genuinely *reads* an input its vertex stage does not provide, that one program fails to link and the
engine falls back to the game's rendering for that surface. You get the game's version of that one
thing, not a hole.

Declaring one is not enough to cost the pass. An input the fragment body never reads is struck from
the stage before it is compiled, precisely so that a declaration alone does not refuse a program
that would have worked. What is left after that is a real mismatch.

The usual cause is a shared header of varyings that every fragment stage of the pack includes,
full-screen passes among them, where only some of the geometry stages write them. **The log names
which program was refused and which inputs did not match** - that line, not this page, is what tells
you whether a given pack is affected today.

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

## The water

Water has two distinct failure shapes, and they look nothing alike.

**Water missing entirely - you see the lake bed through an empty surface.** Some packs sample a
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

**Mobs, particles, weather and the held item look flat, unlit, and out of place against the
terrain.** They are drawn by the game and composited in, already tone mapped, carrying the game's
own lighting rather than the pack's.

Two consequences follow, and both are worth recognising rather than reporting as separate bugs:

- That geometry arrives with **no normal and no material id**, so passes that classify pixels by
  material misread it. On packs whose water composites work that way, an entity can be treated as a
  surface to fog.
- A pack can allocate a colour target for a family that is not drawn through it. BSL allocates one
  for glowing entities alone, and its deferred pass samples that target - so the chain reads a clear
  across a whole target.

The engine states at startup which families go through the pack and which still come from the game.
That line is the authority; this page does not duplicate it.

## The sky goes flat

**A flat grey or white sheet across the sky, typically at sunrise or sunset.**

Several packs recognise the game's stars by a vertex colour whose three channels are equal and
non-zero. Hand such a pack a plain white vertex colour on a sky pass and it takes its star branch,
painting the whole disc flat. Sildur's and Body Camera both do this.

The engine multiplies the draw's colour modulator into the value the pack reads rather than
substituting white, precisely so that branch is not taken by accident. All of the sunrise band's
colour lives in that modulator - its mesh is white fading to transparent - so substituting white
would both flatten the band and trip the star test.

**So if you do see this, the modulator is not reaching the pack.** That is the thing to check, and
it is not something a pack setting can cause.

## You just changed packs

**Blocks wave when they should not, glow, or cast a shadow that spills past them - and placing then
breaking a block fixes that spot.**

Block numbers travel on the vertex, and no two packs number blocks alike. Chunk sections meshed
while the previous pack was loaded keep the numbers they were built with, so a stone wall can land
inside the new pack's waving-foliage range. Breaking a block rebuilds that section, which is why it
appears to fix it.

The engine rebuilds the world when the table moves and says so in the log. **No image diagnosis
after a hot pack change is worth anything until that rebuild has happened** - check for that line
before investigating anything else.

## Terrain that is too dark

One open case worth naming, because it is easy to misattribute. Packs can set a directive asking
that ambient occlusion be delivered separately from vertex colour. Where that directive is not
read, ambient occlusion ends up inside the albedo, and is then reflected, exposed and moved by the
whole chain - a plausible image that is uniformly too dark. Bliss is the pack where this shows.

It is diagnosed by comparing two builds at the same camera and the same world time on a corner
where occlusion is strong, not by toggling settings: switching shadows off makes it worse rather
than better.

## What packs ask for that is unusual

A short reference, if you are writing a pack or wondering why yours is treated differently.

- **Where a pack keeps its programs is not fixed.** Sildur's keeps them at the root of `shaders/`
  rather than in a dimension folder. A dimension folder *replaces* the base set rather than layering
  over it - the full rule, including what an empty folder means, is in
  [translation.md](translation.md).
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
- **A pack can ask the engine not to draw a piece of the sky** because it draws that piece itself,
  inside one of its own programs. Four such requests are honoured - the sun, the moon, the stars and
  the sky disc - and honouring the last two is a **deviation from both references**, which take out
  only the sun and the moon. It costs two packs of the corpus the stars the references leave them,
  and the NOTICE says so. The fifth request in that family, the one about clouds, is deliberately not
  honoured: no program here draws clouds yet, so obeying it would take the game's clouds away and put
  nothing in their place.
- **Settings are declared in the GLSL, not in a manifest**, and packs disable whole programs from
  their properties file using preprocessor conditions on their own settings. Both Complementary
  packs do this, which is why a flat read of that file reports passes as active that the pack
  switched off.
