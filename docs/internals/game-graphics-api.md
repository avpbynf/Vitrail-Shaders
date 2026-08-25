# The game's graphics API

This page is about Minecraft 26.2 itself, not about Vitrail: what its graphics layer offers to
someone who wants to attach a shader engine to it, what it refuses, and where it is silent. It is
the reference one would have liked to have before starting.

Everything here is a property of the game. What Vitrail builds on top of it is described in
[the frame](../frame.md), [translation](../translation.md) and
[render targets](render-targets.md); what a pack expects is
[the pack format](../pack-format.md).

## Where a renderer can get in

The world render ends inside the game renderer, immediately after the level renderer returns. At
that moment the frame graph has been executed and **no render pass is open**, which is what makes a
full-screen pass over the finished world possible at all. That is the cheapest attachment point in
the frame, and it is the one to reach for first. NeoForge posts a public event on that very line, so
reaching it there needs no mixin; Fabric offers nothing at that point, so it is reached by a mixin
on the same line. What matters is the line rather than the door.

It comes with a deadline. Only a few statements later, the game clears the depth texture of the
main target: the world's depth does not survive past that point. Anything that needs scene depth
has to take its copy during the event, or arrange to be earlier by hooking the frame graph setup
instead. Nothing warns about this: a pass reading a cleared depth produces an image, not an error.

Size is the other thing to get right at the seam. The main target is resized in one place, at the
head of the frame, when the window state differs. A hook that runs after that, in the same frame,
sees the correct dimensions by simply comparing its own against the main target's, every frame.
Do **not** listen to the window resize event instead: it is posted before the main target actually
moves, it also fires when only the interface scale changes, and the panorama capture resizes the
main target without going through the resize path at all. The per-frame comparison catches all
three; the event catches none of them correctly.

## The compiler is already in the game

The game embeds a GLSL front end and a SPIR-V cross compiler, and exposes them: the device's
precompile entry point accepts an **arbitrary shader source** rather than only the one it would
have loaded from resource packs, and does the rest: compilation, reflection, and the binding
remapping that follows from it.

The rule this produces is short: do not write a SPIR-V compiler, and do not vendor one. Everything
a shader engine needs is reachable through that one entry point, and anything built beside it would
have to reproduce the game's own reflection conventions exactly in order to be usable.

The corresponding trap is that setting a pipeline on a pass compiles it on demand if it is not in
the cache, using the source the *game* would have used, which for an identifier that exists in no
resource pack means no source at all. What follows is half loud and half silent, and the halves are
in different places. The compile writes two errors to the log: one names the stage and the
identifier whose source could not be found, the other names the pipeline and the shader that came
back invalid. It then hands back an invalid pipeline, which throws only later, when a pass sets it,
with a message that names nothing. So the log already holds the answer that the stack trace does
not, and every pipeline must be precompiled with its real source before the first pass of the frame
opens.

## Pipelines and modules are memoised by identity, not by source

Both the precompile path and the on-demand path go through the same lookup, keyed by the pipeline's
identity. That has one pleasant consequence and one sharp one.

**Precompiling every frame is free.** The lookup is a get-or-compute, so re-offering an unchanged
pipeline costs a map probe. This is not an optimisation to skip: it is what keeps the engine correct
across reloads, since the cache is emptied whenever the game reapplies its shader manager, which
includes an ordinary resource reload. Nothing may be compiled once at startup and assumed to
survive.

**A changed source under an unchanged identity is invisible.** The key does not include the shader
text. Two different sources offered under the same identifier resolve to whichever was compiled
first, and the second one is silently discarded: the visible symptom being a shader that behaves
like the previous one, with nothing logged and no compile error to look at. Identity therefore has
to vary with whatever varies in the source; if a pack selection can change the text, the pack must
be part of the identity.

**And a pipeline that reaches the pass uncompiled compiles against the wrong source.** Binding one
goes through the same get-or-compute, but hands it the game's *default* shader source rather than
whatever the caller would have offered. For a pipeline of a mod's own that source has nothing under
the identifier, so the compile finds no text and the bind then throws about a pipeline that is not
valid. Precompiling is therefore not an optimisation and not optional: every pipeline a frame will
bind has to be offered, with its own source, before the frame's first pass opens.

## The encoder's guard does not cross encoders

The device hands out a **new encoder wrapper on every call**, over a single backend. The flag that
enforces "close the existing render pass before creating a new one" lives on the wrapper, not on the
backend. Two encoders in the same frame therefore do not see each other, the guard never fires, and
the result is a nested begin-rendering that nothing on the Java side detects. Taking a fresh wrapper
for a clear or a copy is cheap and perfectly correct where the caller already knows no pass is open;
what it is not is a check. Whatever holds a pass open is the only thing that knows it.

Where the encoder does check, it checks loudly. Opening a render pass validates the number of
attachments against the device limit, that there is at least one, that each carries the render
attachment usage, that the attachment sizes agree, and that the render area fits inside them. Every
one of those throws. None of them degrades quietly, which makes this one of the few places in the
frame where a mistake announces itself.

The game's own post-processing pass is the model worth following for the shape of a pass: save the
projection state, open a pass on the output target, set the pipeline, bind the default uniforms, set
the uniform blocks, bind each input texture by name, draw a full-screen triangle, restore. Notably,
it declares no barrier and no layout transition, which is the subject of the next section.

## Synchronisation: nothing to write, and a price to know

Every GPU texture in the game lives in the general Vulkan layout for its whole life. The only
transition that ever happens to one is from undefined at construction, and the layout passed when a
texture is used as a colour attachment is the same as the one passed when it is sampled. There is no
per-texture layout tracking anywhere, so there is nothing that can drift out of step from one frame
to the next.

On top of that, submitting a render pass ends it with a **full memory barrier**: all commands to
all commands, memory read and memory write. The other operations that touch textures (clearing,
copying, uploading) end the same way. That is still what the game does for its own passes.

Passes this engine labels (`Vitrail ...`) close with a narrower barrier instead, naming what the
rest of the frame samples of the pass **and** what it writes over it. Both halves are needed and only
the first is obvious: two Vulkan passes writing one image are ordered by nothing, where the bound
framebuffer of OpenGL orders them for free, and the emptying of a target now rides the load-op of
a pass rather than being a clear of its own, which makes it one of those writes. Consecutive
geometry that writes the same colour and depth images stays in one pass, which is what Iris does by
leaving `defaultFB` bound (`IrisRenderingPipeline.bindDefault`). A later composite, a copy, or a
different framebuffer ends that hold first. Mip chains are filled with `vkCmdBlitImage` on the
frame's command buffer, the Vulkan form of `glGenerateMipmap`, rather than a pass per level.

The practical consequence is that reading in pass N+1 what pass N wrote still requires no
synchronisation code of our own: close, open, bind. But the barrier exists only if the pass is
genuinely closed, since it is closing that submits. A pass left open by an early return or an
exception path skips its barrier and stays open besides, so passes belong in try-with-resources
without exception.

The price still scales with the number of GPU stops rather than with what they read: each closed
pass, each standalone clear, each copy. Folding a clear into a load-op, blitting a mip chain, and
holding matching geometry in one pass are how this engine spends fewer of those stops. Note that
this is a structural statement, not a measured one: comparing two frames rendered from different
viewpoints measures nothing, so any figure has to come from the same position, orientation and
world seed with the chain switched on and off.

## What the API does not do

**A copy does not convert.** The texture-to-texture copy checks sizes and usages, and any two
textures with the same footprint pass. The backend then reinterprets the bits: the game's colour
target and a typical pack target are the same width per pixel and hold entirely different things.
Anything that crosses formats must go through a full-screen draw, and the copy path is only for
exact format matches (see [the frame](../frame.md) for where that shows up in practice).

**A texture cannot be cleared during a pass.** The standalone clear refuses to be called while a
pass is open, and additionally demands the copy-destination usage on the texture. Clearing through
an attachment's load operation, by contrast, costs nothing: it is a load-clear rather than work.
So the practical rule is that clears are decided when a pass opens; a clear needed in the middle of
one is really a pass boundary in disguise.

**Nothing detects a leaked GPU resource.** Forgetting to destroy a render target's buffers produces
no message of any kind. The only witness available is the Vulkan validation layer, which has to be
turned on deliberately.

**Render targets themselves are not closed off**, which is the counterweight to all of the above:
the game's texture-backed target type is public and instantiable, so owning targets needs no mixin,
and its textures are created with usages that allow the same texture to be a colour attachment in
one pass and a sampled input in the next without being recreated.

## Coordinates keep the OpenGL convention end to end

This is the question every port of an OpenGL-era renderer asks, and the answer is that there is
nothing to flip.

The Vulkan viewport the game sets is **not** inverted: it starts at zero and has a positive height.
The flip happens once, at the very last moment, in the blit to the swapchain, where the destination
Y range is reversed while the source is not. Everything upstream of that blit (the main target and
any target a mod owns) keeps the OpenGL orientation, origin at the bottom left.

So a full-screen shader that maps a corner to both its texture coordinate and its clip position
sees texture coordinate zero at the bottom left of the screen, which is exactly the convention
OptiFine-format packs are written against. No coordinate flip is needed for them anywhere, and one
added "to be safe" is a defect.

## Samplers and uniform blocks are paired by reflection

The pipeline builder has **no method for declaring a sampler**. Declarations go through a bind group
layout instead (a named sampler, or a named uniform block with its type), which is then attached to
the pipeline. Binding happens at draw time, by name, on the pass.

What ties the two together is reflection over the compiled SPIR-V, and the asymmetry it creates is
the single most useful thing to know here:

- **The loop starts at the shader.** For each uniform block and each sampler reflection finds, the
  compiler looks the name up in the layout, and a name that is not there is a compile failure that
  names it.
- **The reverse produces nothing.** A layout entry the shader does not use is never visited, so it
  is neither an error nor a warning.

Over-declaring is therefore free and under-declaring fails at compile time, which means the safe
direction is a generous layout. That is what makes it practical to serve a large uniform surface
without declaring it pipeline by pipeline.

**MoltenVK is the exception that makes the order of those declarations load-bearing.** Metal numbers
a sampler by the binding the compiler assigned from the order it first met the name, not by how
many the shader actually samples, and it only accepts 0 through 15. A shader that declares twenty
unused names ahead of the one texture the body reads still hands that texture binding 17, and the
pipeline is refused. The translator therefore writes the names a program samples first in the
header, unused declarations after. A program that samples more than sixteen textures is still
refused there: that is Metal's own cap, the same one Iris documented for macOS.

The asymmetry does not extend to the draw. A sampler that is declared and used, but not bound when
the draw happens, throws, so the layout can be generous while the binding cannot be sloppy.

Two constraints come with it. Names must match **character for character** between the GLSL, the
layout declaration and the bind call; there is no normalisation anywhere along that path. And a
sampler's dimensionality is checked against the kind of layout entry its name matched: a name
declared as a sampler has to be two-dimensional or cube, while a name declared as a uniform with a
format has to be a buffer, and is bound as a texel buffer rather than as an image. Three survive,
then, but not interchangeably, and everything outside that set is rejected by name, which is why
several pack features are hard refusals rather than unfinished work, as
[translation](../translation.md) explains.

Finally, explicit binding and set qualifiers must not be written into the GLSL at all: the compiler
assigns them and an intermediate module rewrites them afterwards. The same reflection machinery also
decides how a vertex stage's outputs meet a fragment stage's inputs, with an asymmetry of its own
that is sharper still: that one is in [translation](../translation.md).

## Compute and storage images: the facade vs the backend

The Java device has no compute. Its shader-type enumeration is a vertex stage and a fragment stage,
precompile accepts a render pipeline and nothing else, a texture's usage bits are copy, sample,
attachment and cubemap, and bind-group reflection enumerates uniform buffers and sampled images.
A pack compute unit that went through that path would compile as a vertex shader or not at all,
and a storage image it declared would be bound to nothing. That is still true of the facade, and it
is why nothing of a pack's compute goes through it: the pipeline is built against the backend below,
and the walk is widened around the facade at three points. The reflected entry list gains the
storage images and blocks it never enumerates, the layout emits a storage type for those names
instead of a combined sampler or a uniform buffer, and the descriptor written at bind time carries
the VMA handle and the three-dimensional view. `IRIS_FEATURE_CUSTOM_IMAGES` is posed on that road
being open, and it is the only capability define this engine poses today.

The Vulkan backend behind that facade already has the rest. The device object hands out the
`VkDevice`, the VMA allocator, and a graphics queue created with both the graphics and compute
bits. It also creates a dedicated compute queue when the hardware has a spare family, and it
enables `VK_KHR_push_descriptor` as a required extension. The GLSL compiler it embeds is shaderc,
which has a compute kind the game never passes only because the Java enumeration has no constant
for it. None of that needs a mixin: those methods are public.

What does need going around is texture creation. The usage-bit mapping never sets the Vulkan
storage bit, so a storage image has to be allocated through VMA rather than through
`createTexture`. That is an extension of how the device is used, not of the device class.

None of this is inference. A compute shader built with that shaderc, dispatched against a storage
image allocated through VMA, writes texels the same frame reads back unchanged: the road is open,
and nothing of it was ever in the backend's way. The stage that walks it is `PackCompute`, which
compiles a pack's `shadowcomp` and dispatches it at the head of the frame. Which programs it serves,
and why that moment rather than beside the shadow map that feeds it, is answered where the pack's
chain is.

## Small things that cost a lot to rediscover

- **The shared sampler cache hands out shared objects.** Never close one; the cache owns it.
- **Pass label suppliers are called more than once per pass per frame** when the driver exposes
  debug checkpoints. Use constants, never build a label by concatenation.
- **The static output texture overrides are consulted by every immediate draw.** Leaving them
  pointing at a mod's textures sends the player's hand and the whole interface into them.
- **Resizing a render target destroys and replaces its textures and views.** Any reference held
  across a resize is dead, and opening a pass on a closed view throws (loudly, but in the middle of
  rendering).
- **Free GPU resources on the client *stopping* event, not the stopped one.** The first is posted
  while the device is still alive; the second comes after the renderer has been shut down.
- **Clearing through the attachment costs nothing; clearing the texture is another operation
  entirely.** A clear colour named on the attachment becomes a load operation, which the hardware
  does as it opens the pass. The standalone texture clear is a command: it wants the copy-destination
  usage on the texture and refuses to be called while a pass is open.
- **Sodium filters what it intercepts by pipeline namespace.** Pipelines under a namespace of your
  own go past its pipeline-layout interception untouched, which is what makes it safe to build them
  differently from its own. It is also a fact about one version of another mod, so it is worth
  re-checking whenever that one moves.
- **Sodium scissors each region inside a pass.** Reusing that pass for the next family without
  putting the viewport back and disabling the scissor clips those draws to the last region's
  rectangle. The leftover half of the screen keeps the previous image, which reads as a band
  that copies the top onto the bottom.
