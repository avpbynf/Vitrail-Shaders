# The frame

A shader pack does not describe one image. It describes a **chain of passes**, each reading what
earlier passes wrote, and each expecting to run at a particular moment relative to the world being
drawn. This page is about where those moments are in the game's own frame, and how the buffers behind
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
10. translucent features drawn after the terrain, then **after translucent particles**

Then the world render ends, and one more seam comes after all of them: **after level**, which fires
once the level renderer is done and before anything else touches the main target.

Two of those names read the wrong way round, which is worth fixing in your head once: the features
are drawn *before* the water, so the event named after the features fires before the one named after
the blocks.

The terrain renderer does not move any of this. Every decision below is anchored to one of those
points, and a pass placed at the wrong one produces an image that is plausible and wrong rather
than an error.

## The chain is cut in two around the translucent world

A pack's passes are not all run at the same moment. The chain is split, and each half hangs off one
of the seams above:

- the setup, begin and prepare stages, the seed, and the deferred stages run at **after opaque
  features**, which is before the world's translucent geometry;
- the composite stages and the final pass run at **after level**, once the whole world render is
  done.

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
clears to **opaque white**, and the rest to transparent black - except a target whose format gained
an alpha channel on its way to the device, which clears to opaque black instead, since in OpenGL
the three-channel texture the pack wrote against always sampled as an alpha of one. All of that is
the default: a pack that names a clear colour of its own is handed exactly what it named.

That second one is worth remembering: a pack whose final pass reads the second target renders a
white screen when nothing has written it. That is a prediction you can make in advance, not an
accident to debug.

### A copy is not a conversion

Targets are copied only between two halves of one doubled target, where the format is the same on
both sides by construction. Copying between two *different* formats passes every check and hands
back nonsense: the texture copy reinterprets bits rather than converting them, and the only thing
checked on the way in is that both formats carry a colour aspect. The game's colour target is
eight-bit RGBA and a typical pack target is a packed float triple; both are thirty-two bits per
pixel and hold completely different things.

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

The two scene copies are converted from the reversed-Z convention the game renders with into the
legacy convention packs expect. The world itself keeps being drawn in reversed Z, so its depth
precision is unaffected; only the copy handed to the pack is converted. The shadow map is not
converted at all - it is *stored* in the convention the pack reads, which comes to the same thing by
another road.

## The seed, and why anything not drawn through the pack looks flat

The pack's chain has to start from something. Whatever the game has drawn - which includes every
family that does not go through the pack's own programs - is brought into the pack's targets by a
full-screen pass, the seed.

**It is worth knowing exactly what the seed stands in for, because it is not the ordinary
arrangement.** In the reference implementation the world is never written into the game's colour
texture at all: that texture stays untouched until the final pass writes it, and the first colour
target starts from a fog-colour clear that everything then accumulates onto. What is shared with the
game there is the **depth** texture and only that - the pack's targets are built around the main
target's own depth, which is what keeps depth testing coherent between the game's passes and the
pack's without a single copy.

So the seed is not a general-purpose way of moving an image about. It is the stand-in for the
families that do not yet draw through the pack, and every one of its costs below is the cost of
standing in for them. It goes away as they arrive.

This is where the flatness comes from, and it is worth being precise about it, because it explains
several symptoms that look unrelated:

**The seeded image is already finished.** It carries the game's own lighting, its tone mapping, its
gamma and its fog. A pack that exposes, grades or fogs is therefore working on an image that has
already been through all of that once.

**The seeded image has no geometry information.** There is no normal and no material id behind
those pixels, only colour. Passes that classify pixels by material read whatever the clear left in
those channels, which is why a mob can be treated as a surface to fog by a pack whose water
composites work that way.

**A pixel the pack's own terrain has covered must not be seeded over.** That is what the coverage
mask is for: it is compared against the depth left by the pack's own geometry, so a pixel the pack
drew belongs to the pack and a pixel it did not belongs to the seed. Comparing against the wrong
depth - one the game has since cleared - makes the mask wrong everywhere, and things the game drew
in front of the terrain vanish.

**The entities are the exception, and it is deliberate rather than a gap.** They write no mask, so
their pixels are seeded over on purpose: the seed is cut against a depth taken before the game draws
a single feature, and an entity is by definition in front of that depth, so a mask claiming those
pixels would take them from the very image that carries the entity's colour. What that costs is one
trip through eight bits a channel for the albedo, and what it buys is every other draw buffer, which
is where a pack keeps the normal and the material it lights an entity by.

So a flat, unlit mob is not a mask bug, and before reading it as one, check which of two things you
are looking at. The commoner by far is that the family is **off by default**: it is turned on with
`entities=on` in the engine's own options file, and with it off every entity comes straight from the
game's shader. With it on, an entity that still looks flat is one the pack's own program did not
reach - the log names the reason at the moment it happens. Neither case is the coverage mask.

Which target is seeded is the first draw buffer of the pass that draws the terrain, resolved through
the fallback tree, and it is not always target zero: one pack of the corpus serves its terrain
through the textured gbuffers program, whose draw buffers start at the fifth target.

## Where the seed does not reach

The seed brings in what the game has drawn *by the time it runs*. Anything the game draws later is
not in it.

That would have taken the game's translucent features with it - the player's own body among them,
which is drawn there - since they land in the game's target and the pack's final pass overwrites it.
They are not lost: the engine redirects the game's colour output for the length of that phase and
composes what it catches onto the pack's target afterwards. It is a second full-screen layer,
bracketing one phase rather than standing at one seam.

## Why a family cannot simply be switched over

There is a trap here that looks like an easy win and is a regression, and it explains the shape of
the whole project.

The final pass writes into the game's colour texture, covering every pixel. So if one family were
routed into the pack's targets while the others stayed with the game, the chain would receive a
world missing everything that stayed behind - and the final pass would then erase what stayed
behind as it wrote. Moving one family in isolation makes the image *worse*, not partially better.

That is the structural reason families move over one at a time, each with its own switch, rather
than being patched in: each one enters the frame at a different seam.

### The first draw buffer moved, and the reason is worth keeping

An intermediate arrangement kept **attachment zero on the game's colour target** while the further
attachments went to the targets the pack's draw buffers named. It had a beautiful verification -
nothing changes on screen, so the invariant is that the image does not move by a pixel - and it was
right for the step it was taken at.

It does not hold any more, and what killed it is worth knowing. What a terrain program puts in draw
buffer zero is not a colour, it is whatever the pack packed there, and the game's colour target is
eight bits a channel. One pack of the corpus packs two values into each channel of a sixteen-bit
target; the trip through the game's target quantised its albedo away entirely, and what came back
was the encoded normal being read as albedo. So the first draw buffer now goes to the pack on every
half of the world, and keeping it on the game's target survives only as a **demotion**, for the
cases where there is nowhere else to send it: no chain running, no answer in the plan, or an opaque
half that could not be given a coverage mask.

## Reading the plan before running it

The chain is decided at load, from the pack's declarations, and it can be recomputed outside the
game from the same inputs: how many passes run, which targets end up doubled, which are flipped at
the end of the frame, which half the final pass reads from.

That makes a useful habit possible, and it is the one recommended for any change in this area:
compute the plan first, then run the game and compare it against what the engine logs. If they
disagree, the engine has diverged from the plan and the pack is not the suspect.
