# Vitrail, explained

Vitrail loads OptiFine-format shader packs on Minecraft's native Vulkan renderer. This
directory is the long-form documentation: what a pack actually is, what the engine does with
it, why some things work and others do not, and how to develop against it.

The short version lives in the [README](../README.md) and the install steps in
[INSTALL](../INSTALL.md). This is the part that answers "why".

## Where to start

| If you want to | Read |
| --- | --- |
| Know whether your pack works, and why it does not | [Pack compatibility](compatibility.md) |
| Understand what a shader pack is made of | [The pack format](pack-format.md) |
| Understand how legacy GLSL reaches a Vulkan GPU | [Translation](translation.md) |
| Understand when each program runs during a frame | [The frame](frame.md) |
| Understand the sky and the shadow map | [Sky and shadows](sky-and-shadows.md) |
| Work on the engine | [Developing](developing.md) |

## The one idea the whole project rests on

Shader packs are written in OpenGL-era GLSL, against an OpenGL-era pipeline. Minecraft 26.2
renders through Vulkan. Those two facts are not compatible, and every design decision here
follows from how that gap is closed.

Vitrail closes it **once, at load time**. Every GLSL unit a pack ships is rewritten into
Vulkan GLSL when the pack is selected, then handed to the compiler the game already embeds,
which turns it into SPIR-V. Nothing is translated while the game is running: by the time the
first frame is drawn, there is no legacy GLSL left anywhere.

That choice has consequences worth knowing about, because they explain most of what you will
observe:

- **Selecting a pack is slow, and drawing with it is not.** The cost is paid in one visible
  pause instead of being spread across the first minutes of play.
- **A pack that cannot be translated fails loudly at selection**, not as a corrupt image
  twenty minutes later. When Vitrail refuses something, the log names it.
- **Uniform and sampler binding is decided up front**, so a pack that asks for something the
  engine does not have gets a documented stand-in rather than undefined memory.

## How to know what the engine currently draws

Not every family of geometry goes through the pack yet. Rather than repeat a list here that
would quietly go stale, the engine states it itself: at startup it logs which families it
draws with the pack's own programs and which ones still come from the game already tone
mapped. **That line is the authority.** Anything a page here says about scope is written to
agree with it, never to replace it.

The visible consequence of a family not going through the pack is always the same, and it is
worth recognising: that geometry is composited in flat, carrying the game's own lighting,
with no normal and no material id for the pack's later passes to work from. So it does not
receive the pack's lighting, and passes that classify pixels by material can misread it.
[Pack compatibility](compatibility.md) explains where that bites, pack by pack.

## A note on sources

Shader packs are written against [Iris](https://github.com/IrisShaders/Iris), so Iris is the
authority on what a pack means, and this documentation follows it. Iris is LGPL-3.0, the same
licence as Vitrail, and what is reused from it is credited in [NOTICE](../NOTICE).

OptiFine defined the pack format and is used here only as a specification oracle: a fact, an
ordering, a defect. No OptiFine code, structure, or naming is reproduced, and none of the
behaviour described here was copied from it.
