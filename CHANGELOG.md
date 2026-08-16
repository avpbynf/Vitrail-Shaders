# Changelog

What each version changed, written for somebody who runs the mod rather than builds it. Why a
change was made is in the commit that made it, and where it is a mechanism rather than a decision
it is in `docs/`.

A version number here, the tag that released it and the number inside the jar are one number: the
release workflow refuses a tag that disagrees with `mod_version` in `gradle.properties` rather than
publishing a jar named after one thing and built from another. The stores add the loader after a
plus, because each of them files an upload per loader and has nowhere else to put the distinction.

Everything is a pre-release while the version stays under `1.0.0`. Nothing here is a promise about
what the next one holds.

## 0.3.0-alpha.1

### Changed

- **Mobs, block entities, the enchantment shine and the player's hand are now painted into the
  pack's own images**, where before their colour reached the pack through the game's, eight bits a
  channel. Packs that pack two values into each channel of a wider image were reading one back in
  place of the other: under Bliss an enderman came out lit instead of black.
- The line each program prints at its first draw now names the image it really samples where the
  pack asks for the atlas, so a picture that disagrees with the wiring can be told apart from one
  that agrees with it.

### Fixed

- The enchantment shine is no longer dropped by packs that draw it into an image of their own. It
  was being refused outright, on a question that only applies to the pieces whose colour still
  travels through the game.
- Picking None while a pack is drawing no longer leaves the terrain in stretched coloured spikes
  until the game is restarted. The world was being drawn through a pipeline built for the wider
  vertex the pack needs, over a mesh that had already gone back to the narrower one.
- Your hand no longer turns and stretches along with the screen inside a nether portal or under
  nausea, where it should hold still in front of the camera. The pack was being handed the whole
  screen distortion as the hand's walk bob, and it is the hand's clip position a pack writes
  through that. Walking and being hit were already right.

## 0.2.0-alpha.1

### Added

- **Fabric.** One jar per loader, carrying the same engine. Everything that draws is shared; what
  differs is the way in, which on NeoForge is a public event and on Fabric a mixin on the very line
  that event is posted from. Fabric API is used for the settings screen and for nothing else.
- The stores now receive both jars, each filed under its own loader, with its own dependencies.
- The mod is on Modrinth beside CurseForge.

### Changed

- The loader-independent half of the mod, which is most of it, moved into a module of its own. No
  behaviour changes with it: the NeoForge jar carries the same classes it carried before, name for
  name once the packages are renamed.
- The ordered work of each stage of the frame is written once, where both loaders call into it,
  rather than once per loader. The order of those lines is the design rather than a detail, and two
  copies of it would drift.

### Fixed

- An enchanted item held in the hand is drawn by the pack's own program instead of the game's.
- A translucent block held in the hand is served by the water pass, as it is in the reference.
- The view matrix a draw was prepared with is read where the game prepared it, rather than
  reconstructed.

## 0.1.0-alpha.1

First release. OptiFine-format shader packs on Minecraft's native Vulkan renderer, NeoForge only.
