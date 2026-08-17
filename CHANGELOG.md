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

## Unreleased

### Added

- **A mob flashes again when it is hurt, and a creeper whitens before it goes off.** The colour the
  game lays over a mob it has just damaged was reaching the pack as one number for the whole world,
  so a pack that paints the flash itself painted nothing: the red hit, the white of a creeper about
  to explode and the tint on a mob standing in fire all went missing the moment the pack took the
  mobs over. It is now read off the mesh, one value per vertex, which is where every one of the eight
  packs tested expects to find it. The three identifiers a pack tells one kind of mob, block entity
  or held item apart by are still one number apiece, which is a different hole and a wider one.

- **The sky of the End is painted by the pack now**, where the game kept its own shader for it. The
  box of sky and the flash that crosses it both go through the program the format keeps for the
  textured pieces of the sky, the one that already drew the sun and the moon. Until now that sky was
  the last piece the game still drew itself: a pack's own sky program never ran in the End, and only
  its post-processing reached what you saw there. The Nether is the one place left with none of the
  pack's sky in it, and there the game opens no sky pass at all.
- **Glowing eyes are painted by the pack now**, where the game kept its own shader for them: the eyes
  of a spider, an enderman, a phantom, a breeze and the ender dragon, and the bioluminescent parts of
  a warden, which the game draws the same way. They go through the program the format keeps for eyes
  alone, and they are added onto the mob's skin rather than laid over it, which is what makes an eye
  read as a light rather than as a sticker. They are also drawn at full brightness whatever the mob
  is standing in, as the game draws them: handed the light of the spot instead, a pack that shades by
  that light would have put out the eyes of anything standing in the dark, which is where they matter
  most. Six of the eight packs tested ask for that light in the program that serves their eyes, and
  one of them looks the game's light map up with it, which is where it shows on screen. What they do
  not do yet is darken the mob's own shadow.
- **The wind a breeze throws and the swirl over a charged creeper are painted by the pack now**,
  where the game kept its own shader for both. What held them back is a texture matrix of the
  game's own that scrolls them, and that matrix is answered out of the game's transforms now, so a
  breeze scrolls rather than sitting frozen on one frame of its offset and two breezes on screen
  keep their own. The swirl writes every image the pack declares for its entities: on five of the
  eight packs tested that is between two and four images rather than the single one it would
  otherwise have reached.

### Changed

- **The settings screen is the one pack authors already know.** It is the screen of the engine packs
  are written against, ported rather than approximated: the same list of packs with the shaders
  toggle at its head, the same pages of settings with the value of each in a sunken box beside its
  name, the same Cancel, Apply and Done, the same buttons and panels drawn from the same texture. A
  right click walks a value backwards, shift and a click give back the value the pack ships, a slider
  is dragged and only writes when it is let go, and a setting waiting to be applied is marked in
  amber. Hovering a setting brings up the pack's own words about it in a panel at the bottom.
- **Closing the screen applies what you changed**, where it used to leave without writing. Cancel is
  the button that throws the changes away, and Apply is the one that writes without closing.
- **A pack is dropped straight onto the screen**, and a settings file exported and imported through
  the platform's own file window. Clicking a pack in the list now only selects it, so a folder of
  eight can be looked through without paying for eight loads; Apply and Done are what make a
  selection real.
- **The eye at the bottom right, and F1, take the screen away** so that the world behind it can be
  looked at while a setting is judged. Escape brings it back.
- Resetting a pack's settings asks for shift to be held rather than for a confirmation screen, which
  is how the reference guards the same button.
- What this engine has to say and the reference has not, a load that failed, how many settings
  `vitrail/options.txt` is holding down and how many passes this backend could not build, is on one
  line at the bottom left.

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
