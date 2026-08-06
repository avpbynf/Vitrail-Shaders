# The frame

A shader pack does not describe one image. It describes a **chain of passes**, each reading what
earlier passes wrote, and each expecting to run at a particular moment relative to the world being
drawn. This page is about where those moments are in Minecraft 26.2, and how the buffers behind
them work.

## Where the game's own frame has its seams

The world render is one sequence, and the points a pack cares about are the gaps in it:

1. the opaque chunk group
2. **after opaque blocks**
3. solid features - block entities, most of what is not terrain
4. **after opaque features**
5. translucent features
6. **after translucent features**
7. outlines
8. the translucent chunk group
9. **after translucent blocks**

The terrain renderer does not move any of this. Every decision below is anchored to one of those
points, and a pass placed at the wrong one produces an image that is plausible and wrong rather
than an error.

## The chain is cut in two around the translucent world

A pack's passes are not all run at the same moment. The chain is split:

- the setup, begin and prepare stages, the seed, and the deferred stages run **before** the world's
  translucent geometry;
- the composite stages and the final pass run **after** it.

That split is what lets the pack's own translucent geometry - water, glass - be drawn into the
pack's targets on the halves that the deferred stages have already written, which is exactly what
several packs assume when they sample a colour target from inside their water program.

The consequence for a pack author: a deferred pass sees the opaque world, and a composite sees the
world with translucents in it. Placing an effect in the wrong stage is not a subtle difference.

## Colour targets, and the rule that governs every read

A pack declares colour targets and writes into them by naming attachments in its fragment stages.
Targets that are both read and written in the same frame are **doubled**: there are two buffers
behind one name, and passes alternate between them so that a pass never reads the buffer it is
writing.

One sentence governs the whole mechanism:

> A read lands on the half produced by the last write before it, and on the primary half if there
> was none.

Everything else - which passes flip, which targets are copied, which are cleared - exists to keep
that sentence true.

Two things follow that are worth knowing because they are invisible when wrong:

**A wrong parity looks like a correct image.** A pass reading the wrong half gets either the clear
colour or the previous frame's content. Both look like an image. Nothing is logged. That is why
buffer parity is checked outside the game rather than judged on screen.

**A target a pack keeps across frames is not a fault.** Packs use one for temporal accumulation:
the clear skips it, so the first frame of a session reads the clear and every later frame reads
what the previous frame left. The engine reports such a read as historical rather than flagging it.

Targets belong to a **place** - the root, or a dimension directory - and never to a pack as a
whole. A pack can declare its format table in one dimension folder only, and then other dimensions
see fewer targets.

**Removing or skipping a pass shifts the parity of every pass after it.** That is why the schedule
is built over the passes that actually run and never trimmed afterwards, and it is the reason a
pack's enable expression has to be evaluated exactly the way the reference evaluates it: read one
enable expression too strictly, the pass disappears, and every read after it lands on the wrong
half without a single message.

For the same reason the invariant that ties a geometry pass to the passes around it is a **count of
flips**, not an equality. A translucent geometry pass writes the pre-deferred side flipped once per
deferred pass that wrote that target. The simpler rule - "the seeded half and the translucent half
are the same" - is false as soon as one deferred pass writes the seeded target, which is a common
case rather than an exotic one.

### Clear colours are part of the contract

They are not an implementation detail, because a pack can legitimately read a target nothing has
written yet. The first colour target clears to the fog colour with alpha forced to one, the second
clears to **opaque white**, and the rest to transparent black.

That second one is worth remembering: a pack whose final pass reads the second target renders a
white screen when nothing has written it. That is a prediction you can make in advance, not an
accident to debug.

### A copy is not a conversion

Targets are copied only where formats match exactly. The size and usage checks pass on any two
textures of the same width, and the backend then reinterprets the bits - the game's colour target
and a typical pack target are both thirty-two bits per pixel and hold completely different things.

This is why the game's image is brought into a pack target by a **full-screen draw** and never by a
texture copy.

## Depth is not one image either

A pack reads scene depth under several names, and they are not interchangeable:

- the depth of the world as drawn,
- a copy taken **before** the translucent geometry, so that a pass can ask what is behind the
  water,
- and, for shadows, the depth from the light's point of view, which follows a different convention
  entirely - see [Sky and shadows](sky-and-shadows.md).

Aliasing the pre-translucent copy onto the ordinary depth is a mistake with no visible signature:
the effect still appears, still has the right shape, and reads the wrong distance.

All of these are converted from the reversed-Z convention the game renders with into the legacy
convention packs expect. The world itself keeps being drawn in reversed Z, so its depth precision
is unaffected; only the copy handed to the pack is converted.

## The seed, and why anything not drawn through the pack looks flat

The pack's chain has to start from something. Whatever the game has drawn - which includes every
family that does not go through the pack's own programs - is brought into the pack's targets by a
full-screen pass, the seed.

This is where the flatness comes from, and it is worth being precise about it, because it explains
several symptoms that look unrelated:

**The seeded image is already finished.** It carries the game's own lighting, its tone mapping, its
gamma and its fog. A pack that exposes, grades or fogs is therefore working on an image that has
already been through all of that once.

**The seeded image has no geometry information.** There is no normal and no material id behind
those pixels, only colour. Passes that classify pixels by material read whatever the clear left in
those channels, which is why a mob can be treated as a surface to fog by a pack whose water
composites work that way.

**A pixel the pack's own geometry has covered must not be seeded over.** That is what the coverage
mask is for: it is compared against the depth left by the pack's own geometry, so a pixel the pack
drew belongs to the pack and a pixel it did not belongs to the seed. Comparing against the wrong
depth - one the game has since cleared - makes the mask wrong everywhere, and things the game drew
in front of the terrain vanish.

Which target is seeded is decided by following the fallback tree, not by naming target zero. Some
packs allocate no target zero at all.

## Where the seed does not reach

The seed brings in what the game has drawn *by the time it runs*. Anything the game draws later,
and anything drawn into a texture the pack's final pass then overwrites, is not covered by it.

## Why a family cannot simply be switched over

There is a trap here that looks like an easy win and is a regression, and it explains the shape of
the whole project.

The final pass writes into the game's colour texture, covering every pixel. So if one family were
routed into the pack's targets while the others stayed with the game, the chain would receive a
world missing everything that stayed behind - and the final pass would then erase what stayed
behind as it wrote. Moving one family in isolation makes the image *worse*, not partially better.

What holds instead is to keep **attachment zero on the game's colour target** while the further
attachments go to the targets the pack's draw buffers name. The visible image does not move by a
pixel, and that is exactly the invariant that verifies the step: nothing should change on screen,
while the pack's later passes start receiving real normals and real material ids where they were
reading clear values.

It also means the verification is not visual. What proves the step is that those targets stop
appearing in the load-time list of targets read before anything wrote them.

That is the structural reason families move over one at a time, each with its own switch, rather
than being patched in: each one enters the frame at a different seam, and a full-screen layer can
only ever pick up what is already in the buffer when it runs.

## Reading the plan before running it

The chain is decided at load, from the pack's declarations, and it can be recomputed outside the
game from the same inputs: how many passes run, which targets end up doubled, which are flipped at
the end of the frame, which half the final pass reads from.

That makes a useful habit possible, and it is the one recommended for any change in this area:
compute the plan first, then run the game and compare it against what the engine logs. If they
disagree, the engine has diverged from the plan and the pack is not the suspect.
