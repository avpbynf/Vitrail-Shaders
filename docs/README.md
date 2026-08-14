# Vitrail, explained

Vitrail loads OptiFine-format shader packs on Minecraft's native Vulkan renderer. This
directory is the long-form documentation: what a pack actually is, what the engine does with
it, why some things work and others do not, and how to develop against it.

The short version lives in the [README](../README.md) and the install steps in
[INSTALL](../INSTALL.md). This is the part that answers "why".

## Where to start

| If you want to | Read |
| --- | --- |
| Know why the format is OptiFine's, and how this sits next to other engines | [Why this exists](why.md) |
| Work out why your pack looks wrong, starting from what you see | [Pack compatibility](compatibility.md) |
| Understand what a shader pack is made of | [The pack format](pack-format.md) |
| Understand how legacy GLSL reaches a Vulkan GPU | [Translation](translation.md) |
| Understand when each program runs during a frame | [The frame](frame.md) |
| Understand the sky and the shadow map | [Sky and shadows](sky-and-shadows.md) |
| Change a pack's own settings, and know where they are kept | [The settings screen](settings-screen.md) |
| Work on the engine | [Developing](developing.md) |

### Going deeper

The pages above explain the engine to someone using it. `internals/` is the level below, for
someone changing it: the same subjects, with the mechanisms, the constraints they come from, and
the traps that were paid for once already.

| Subject | Page |
| --- | --- |
| Reading a pack: paths, includes, conditions, settings | [Pack loading](internals/pack-loading.md) |
| Colour targets: formats, the flip, what the backend refuses | [Render targets](internals/render-targets.md) |
| Drawing terrain on a mesh the engine does not own | [Terrain](internals/terrain.md) |
| Serving uniforms, and how a value is proved correct | [Uniforms](internals/uniforms.md) |
| Textures a pack supplies, and treating its paths as untrusted | [Pack textures](internals/pack-textures.md) |
| The normal and specular maps a resource pack ships | [Material maps](internals/material-maps.md) |
| What the game's graphics API offers and closes | [The game's graphics API](internals/game-graphics-api.md) |

## The one idea the whole project rests on

Shader packs are written in OpenGL-era GLSL, against an OpenGL-era pipeline. The game
renders through Vulkan. Those two facts are not compatible, and every design decision here
follows from how that gap is closed.

Vitrail closes it **before a program draws, never while it is drawing**. Every GLSL unit a pack ships
is rewritten into Vulkan GLSL, then handed to the compiler the game already embeds, which turns it
into SPIR-V. No frame is ever spent translating something that is already on screen, and by the time
a program has drawn once there is no legacy GLSL behind it.

Where the pauses come from is worth knowing, because "once" is not the same as "at selection". The
chain is translated when the pack is chosen. The programs that draw the world and the sky are
translated on demand, at the first frame that needs them - so the first frame of a place does wait
on one. And **changing dimension is a full reload**, because a dimension directory replaces the root
rather than layering over it: the whole pack is read, translated and its colour targets allocated
again. That is the hitch at the portal, and the log names it as it happens.

That choice has consequences worth knowing about, because they explain most of what you will
observe:

- **The cost is paid at load, not per frame.** Some of it lands at selection and the rest in the
  frames just after, because pipelines are compiled one per frame on purpose rather than all at
  once. Until the last one is ready the chain draws nothing at all and the game's own image is what
  you see - which is also what happens for a moment after every resource reload.
- **A pack that cannot be translated fails loudly**, not as a corrupt image twenty minutes later.
  When Vitrail refuses something, the log names it.
- **Uniform and sampler binding is decided up front**, so a pack that asks for something the
  engine does not have gets a documented stand-in rather than undefined memory.

## How to know what the engine currently draws

Not every family of geometry goes through the pack yet. Rather than repeat a list here that
would quietly go stale, the engine states it itself: when a place first draws, it logs which
families still come from the game, already tone mapped, and are carried across by the full-screen
layer. **That line is the authority.** Anything a page here says about scope is written to agree
with it, never to replace it.

Two things about that line rather than one, since a reader who does not find it should know why.
It names what still comes from the *game*, so the families that do go through the pack are the ones
it does not name. And it does not always appear: a place whose plan has no layer in it, or a run
with the layer switched off, says something else instead, because there the targets simply keep
their clear colour.

The visible consequence of a family not going through the pack is always the same, and it is
worth recognising: that geometry is composited in flat, carrying the game's own lighting,
with no normal and no material id for the pack's later passes to work from. So it does not
receive the pack's lighting, and passes that classify pixels by material can misread it.
That symptom, and what to do about it, is
[Anything that moves](compatibility.md#anything-that-moves).

## A note on sources

Shader packs are written against [Iris](https://github.com/IrisShaders/Iris), so Iris is the
authority on what a pack means, and this documentation follows it. Iris is LGPL-3.0, the same
licence as Vitrail, and what is reused from it is credited in [NOTICE](../NOTICE).

OptiFine defined the pack format and is used here only as a specification oracle: a fact, an
ordering, a defect. No OptiFine code, structure, or naming is reproduced, and none of the
behaviour described here was copied from it.
