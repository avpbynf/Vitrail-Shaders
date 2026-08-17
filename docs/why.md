# Why this exists

Minecraft renders through Vulkan now, and not one shader pack that exists today runs on it. Two
choices follow from that, and this page is the reasoning behind both: keeping the OptiFine format
rather than inventing one, and writing an engine at all when others are already in the area.

## Why the OptiFine format

Not because it is elegant, but because that is where the work is. Packs have been written against
the OptiFine conventions for more than a decade, by a lot of people, and that is still where nearly
all of the community writes today. Minecraft moving to Vulkan does not make any of that work worse.
It just makes it unrunnable, and asking every author to port to a new format is asking them to throw
it away.

So inventing a format was the obvious alternative, and it was rejected on purpose. Following
conventions that have held for ten years means the specification already exists, there is a corpus
of real packs to test against, and there is an unambiguous definition of done. A new format closes
the door on all three permanently.

It also means an author can keep shipping one pack through the move to Vulkan rather than
maintaining two. None of this rules out supporting a Vulkan-native format later, if one appears and
people write for it; it is simply not the problem worth solving first.

The cost was measured before any code was written, against a corpus of real, widely used packs, and
development is tested against that corpus continuously. Which packs those are, and what has actually
been seen with each, is [pack compatibility](compatibility.md). The packs themselves are not
redistributable and are not in this repository.

## Related work

Vitrail is not the first attempt at running shaders on this renderer, and it is not competing with
the projects below.

- **[Iris](https://github.com/IrisShaders/Iris)** is the reference implementation for
  OptiFine-format packs and the reason this project is LGPL-3.0 as well. It targets OpenGL, which is
  where the overwhelming majority of packs are still played. Where the format's own documentation
  runs out, Iris is the authority this engine is checked against; the parts adapted from it are
  credited in [NOTICE](../NOTICE).
- **[Sulkan](https://github.com/mravatins/sulkanShaders)** is an open source Vulkan shader engine
  for Minecraft, GPLv3, built as a Fabric mod. It was already running on the Vulkan renderer when
  this project started, and reading where it hooks into the game was useful. None of its code is
  reused here: its licence would not allow it without relicensing all of Vitrail.
- **[Aperture](https://github.com/IrisShaders/Aperture-Example-Pack)**, from the Iris team, is a
  newer engine whose packs are written in Slang. Its example pack is public. It is a clean break
  from the OptiFine format rather than a way to keep running what already exists.

None of them covers the narrow case this one is built for: a pack written years ago, running as it
is, on the renderer that now ships with the game.
