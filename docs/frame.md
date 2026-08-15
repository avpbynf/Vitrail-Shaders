# The frame

A shader pack does not describe one image. It describes a **chain of passes**, each reading what
earlier passes wrote, and each expecting to run at a particular moment relative to the world being
drawn. This page is about where those moments are in the game's own frame, and how the buffers behind
them work.

## Where the game's own frame has its seams

The world render is one sequence, and the points a pack cares about are the gaps in it:

1. the opaque chunk group
2. **after opaque blocks**
3. solid features: block entities, most of what is not terrain
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

That split is what lets the pack's own translucent geometry (water, glass) be drawn into the
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

Everything else (which passes flip, which targets are copied, which are cleared) exists to keep
that sentence true.

Two things follow that are worth knowing because they are invisible when wrong:

**A wrong parity looks like a correct image.** A pass reading the wrong half gets either the clear
colour or the previous frame's content. Both look like an image. Nothing is logged. That is why
buffer parity is checked outside the game rather than judged on screen.

**A target a pack keeps across frames is not a fault.** Packs use one for temporal accumulation:
the clear skips it, so the first frame of a session reads the clear and every later frame reads
what the previous frame left. The engine reports such a read as historical rather than flagging it.

Targets belong to a **place** (the root, or a dimension directory) and never to a pack as a
whole. A pack can declare its format table in one dimension folder only, and then other dimensions
see fewer targets.

**Removing or skipping a pass shifts the parity of every pass after it.** That is why the schedule
is built over the passes that actually run and never trimmed afterwards, and it is the reason a
pack's enable expression has to be evaluated exactly the way the reference evaluates it: read one
enable expression too strictly, the pass disappears, and every read after it lands on the wrong
half without a single message.

For the same reason the invariant that ties a geometry pass to the passes around it is a **count of
flips**, not an equality. A translucent geometry pass writes the pre-deferred side flipped once per
deferred pass that wrote that target. The simpler rule ("the seeded half and the translucent half
are the same") is false as soon as one deferred pass writes the seeded target, which is a common
case rather than an exotic one.

### Clear colours are part of the contract

They are not an implementation detail, because a pack can legitimately read a target nothing has
written yet. The first colour target clears to the fog colour with alpha forced to one, the second
clears to **opaque white**, and the rest to transparent black, except a target whose format gained
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
- a copy taken one step earlier still, **before the player's own hand**, so that a pass can ask what
  the hand is held in front of,
- and, for shadows, the depth from the light's point of view, which follows a different convention
  entirely (see [Sky and shadows](sky-and-shadows.md)).

Aliasing the pre-translucent copy onto the ordinary depth is a mistake with no visible signature:
the effect still appears, still has the right shape, and reads the wrong distance.

The pre-hand copy is the same mistake at a smaller scale, and it only exists where the engine draws
the hand itself. With the hand left to the game it is drawn after the whole chain has run, so
nothing at all comes between the two moments, the two copies are the same image, and only one is
allocated and converted. Turning the hand on is what adds the second image; the second conversion is
paid only on the frames a hand is really on screen, which rules out third person, a hidden
interface, a sleeping player, a spectator and a panorama capture.

The scene copies are converted from the reversed-Z convention the game renders with into the legacy
convention packs expect. The world itself keeps being drawn in reversed Z, so its depth precision is
unaffected; only the copy handed to the pack is converted. The shadow map is not converted at all:
it is *stored* in the convention the pack reads, which comes to the same thing by another road.

## The seed, and why anything not drawn through the pack looks flat

The pack's chain has to start from something. Whatever the game has drawn (which includes every
family that does not go through the pack's own programs) is brought into the pack's targets by a
full-screen pass, the seed.

**It is worth knowing exactly what the seed stands in for, because it is not the ordinary
arrangement.** In the reference implementation the world is never written into the game's colour
texture at all: that texture stays untouched until the final pass writes it, and the first colour
target starts from a fog-colour clear that everything then accumulates onto. What is shared with the
game there is the **depth** texture and only that: the pack's targets are built around the main
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
those channels, which is why a family that comes in this way can be treated as a surface to fog by
a pack whose water composites work that way. That is the seed's own version of the symptom, and it
applies to a family only for as long as the seed is its road in: a mob served by the pack's own
program takes a different one, with a fault of its own described in
[pack compatibility](compatibility.md).

**A pixel the pack's own geometry has covered must not be seeded over.** That is what the coverage
mask is for, and it carries a **depth** rather than a flag: every program of the pack drawn before
the seed writes into it the value it handed the depth attachment, and the seed compares that with
the world's depth as it stands. A pixel nothing has been drawn over since compares equal and is the
pack's; a pixel the game has drawn a feature onto compares closer and is the seed's. Where the pack
wrote nothing at all the mask holds a value outside zero to one, which every real depth is in front
of, so those pixels take the game's picture through the same comparison.

That is one comparison for two questions, and the second one is what a flag could not answer. The
game still draws pieces of its own in front of the pack's geometry, and they have to arrive; the
pack's own geometry must not be repainted. Only a depth tells those two pixels apart.

**The entities used to be the exception and are not any more.** Their first draw buffer went to the
game's target and reached the pack through the seed, which cost the albedo one trip through eight
bits a channel: a pack that packs two values into each channel of a wider target lost the first of
them there. They write the mask now and take that buffer in the pack's own targets, which was not
open to them while the mask was a flag - the cut then compared the world's depth with one taken
before a single feature was drawn, and a mob standing in front of a block moves that depth by
construction, so every pixel of it answered "the game drew in front" whatever mask it wrote.

So a flat, unlit mob is not a mask bug, and before reading it as one, check which of two things you
are looking at. The family goes through the pack out of the box, so the first question is whether
somebody wrote `entities=off` in `vitrail/options.txt`, which hands every entity straight back to
the game's shader. Failing that, an entity that still looks flat is one the pack's own program did
not reach: the log names the reason at the moment it happens. Neither case is the coverage mask.

Which target is seeded is the first draw buffer of the pass that draws the terrain, resolved through
the fallback tree, and it is not always target zero: one pack of the corpus serves its terrain
through the textured gbuffers program, whose draw buffers start at the fifth target.

## Where the seed does not reach

The seed brings in what the game has drawn *by the time it runs*. Anything the game draws later is
not in it.

That would have taken the game's translucent features with it (the player's own body among them,
which is drawn there), since they land in the game's target and the pack's final pass overwrites it.
They are not lost: the engine redirects the game's colour output for the length of that phase and
composes what it catches onto the pack's target afterwards. It is a second full-screen layer,
bracketing one phase rather than standing at one seam.

### The hand is the one family the engine has to move rather than intercept

The player's own hand is drawn *after* the level returns, which is after the chain has run: the game
paints it straight onto the finished, tone mapped image. No seam catches it, because there is no
seam left. A layer of the kind above cannot help either: what it would catch is a hand that has
already been lit by the game's shader over an image the pack finished.

So the engine does not intercept the hand, it **moves** it. The game's own submission
is suppressed and the hand is submitted twice from inside the level: the solid pass among the game's
opaque features, before the deferred stage, and the blending pass at the end of the world, before
the composites. Where each lands is what decides which half of every target it writes, and both are
in the picture the composites read. This is where the reference puts them too.

Moving it costs three things worth knowing. The hand needs a projection of its own (the head-up
field of view, and a clip depth squeezed to an eighth so that an arm held against a wall is not cut
in half by it, which is the number packs know as `MC_HAND_DEPTH`), so it is the one family whose
`gl_ProjectionMatrix` is not the frame's. It needs a second feature renderer, because the game's own
is already mid-frame at both of those moments and refuses to be re-entered. And it splits the depth
copy above in two, since from the moment the hand is in the world's depth a pack asking what lies
behind it has to be given the depth from before it was drawn.

## Why a family cannot simply be switched over

There is a trap here that looks like an easy win and is a regression, and it explains the shape of
the whole project.

The final pass writes into the game's colour texture, covering every pixel. So if one family were
routed into the pack's targets while the others stayed with the game, the chain would receive a
world missing everything that stayed behind, and the final pass would then erase what stayed
behind as it wrote. Moving one family in isolation makes the image *worse*, not partially better.

That is the structural reason families move over one at a time, each with its own switch, rather
than being patched in: each one enters the frame at a different seam.

### The first draw buffer moved, and the reason is worth keeping

An intermediate arrangement kept **attachment zero on the game's colour target** while the further
attachments went to the targets the pack's draw buffers named. It had a beautiful verification
(nothing changes on screen, so the invariant is that the image does not move by a pixel), and it was
right for the step it was taken at.

It does not hold any more, and what killed it is worth knowing. What a terrain program puts in draw
buffer zero is not a colour, it is whatever the pack packed there, and the game's colour target is
eight bits a channel. One pack of the corpus packs two values into each channel of a sixteen-bit
target; the trip through the game's target quantised its albedo away entirely, and what came back
was the encoded normal being read as albedo. So the first draw buffer now goes to the pack on every
half of the world, and keeping it on the game's target survives only as a **demotion**, for the
cases where there is nowhere else to send it: no chain running, no answer in the plan, or an opaque
half that could not be given a coverage mask.

There is a fourth, and it is about a pass that blends. Blending used to be the whole of what earned
the first draw buffer, on the footing that a pass which blends is drawn over a picture the seed has
already put there. That holds for the world's water, the weather, the clouds and everything else
drawn after the seed; it does not hold for a pass drawn before it. The sky is the one family that
blends before the seed and keeps the buffer all the same, because it draws opaque pieces of its own
(the disc, and the horizon cone with it) that mark those pixels against the seed. Not everywhere: the
cone comes with two conditions of its own, set out under
[the horizon gap](sky-and-shadows.md#the-horizon-gap), and where it is not drawn the seed repaints
whatever stands in the band it would have closed: the lower half of the stars, the sunrise, a rising
or setting sun. The hand's solid pass has no such sibling at all, and writes no mask of its own
either, so its first draw buffer stays on the game's target and reaches the picture through the
seed. That last one is the only piece drawn before the seed still in that position.

That last one is a **divergence**, and it is the seed's price rather than a reading of Iris: Iris
binds every gbuffers program to the pack's own draw buffers, the hand included, so the hand's colour
never leaves the pack's target there. Here it makes the trip through the game's eight-bit target,
which is a quantisation and not a loss of the picture. Three things do cost the picture, and none of
them shows up as an error: a half-transparent hand pixel blends against the game's target, which
holds no world while the chain is running, so it is tinted by the clear rather than by what stands
behind it; a hand piece drawn with a pipeline that writes no depth, with nothing of its own pass
writing depth under it, is discarded by the seed's cut, the mask and the world's depth both holding
what the geometry behind the hand left; and the hand's *other* draw buffers still go straight to the
pack, where the seed empties the ones its terrain program shares, so a pack whose hand program
writes a normal or a specular map cannot light the hand from them over its own terrain.

**And it is not forced**, which is worth writing down rather than discovering twice. There are two
ways out and neither has been taken. The mask is open to the hand now that it carries a depth: a
hand row would write the same squeezed value the depth attachment receives, and the cut would
compare it with itself, where the flag it used to be could not have helped. Or the reference's only
constraint is that the hand precede the deferred stage, and the seed is this engine's own with no
counterpart there, so drawing the hand's solid pass *between* the seed and the deferred stage would
keep the reference's moment and let the pack own the first draw buffer. The second is a change to
the order of the frame rather than to this rule.

## Reading the plan before running it

The chain is decided at load, from the pack's declarations, and it can be recomputed outside the
game from the same inputs: how many passes run, which targets end up doubled, which are flipped at
the end of the frame, which half the final pass reads from.

That makes a useful habit possible, and it is the one recommended for any change in this area:
compute the plan first, then run the game and compare it against what the engine logs. If they
disagree, the engine has diverged from the plan and the pack is not the suspect.
