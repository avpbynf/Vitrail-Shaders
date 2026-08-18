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

- **The far terrain of Distant Horizons is fogged at the distance it stands at, instead of being
  treated as sky.** That mod draws its distant land into images of its own and paints only the
  colour back onto the picture, so everything a pack works out from distance, fog, the point a depth
  of field focuses on, what water knows is behind it, found nothing there and answered as though the
  sky went all the way down. Its depth is now written into the world's own before the pack reads
  any of it, converted on the way: the two are drawn through the same camera but not between the
  same clip planes, and copied across untouched a hill a thousand blocks off would have landed a
  few paces from the player's face. Packs are also told the far terrain is there, which is the
  switch most of them put their own distant-land code behind.

  Two limits worth knowing. What stands beyond the plane the game itself stops drawing at, which is
  four times the render distance or the cloud distance, whichever is farther, still reads as sky:
  there is no number left in the depth buffer past that point. And the far terrain is coloured by
  Distant Horizons and not by the pack, so it is lit its own way and only its shape is the pack's to
  work with.

  This needs a build of Distant Horizons newer than 3.2.0-b. That one still ends the game on the
  first frame for the reason `INSTALL.md` gives, and no version of Vitrail changes it.

- **A mob and a piece of armour take the relief their resource pack draws for them.** A skin and an
  armour layer are textures of their own rather than sprites in an atlas, so the maps a material
  resource pack ships beside them were never read at all and both names came back flat: every mob
  stayed matte while the terrain around it had relief. They are now read from whichever texture the
  draw is really using, the same way the atlases already were, and the log names each texture that
  answers as the skin first comes on screen. A skin downloaded from the skin server rather than
  shipped by the resource pack has no maps beside it and is left alone.

- **A normal map on a mob, on armour or on a chest is read the right way round.** The two values a
  pack works a bump out from, the middle of the sprite a face is mapped to and the direction the
  texture runs in over it, were the same constant for every face of every piece: a pack lighting a
  mob through them tilted every bump on it the same way, whichever way the face was really turned.
  Both are now worked out over each polygon and carried on its corners, on the mobs, on the block
  entities, on a held item and on the hand alike. Of the hundred and fifty entity programs the eight
  packs tested ship between them, seventy-five read the sprite middle and sixty-one read the
  direction. What it changes on screen depends on the resource pack as well: a mob with no normal
  map beside its skin has nothing to tilt, and finding that map is the entry above.

- **A mob flashes again when it is hurt, and a creeper whitens before it goes off.** The colour the
  game lays over a mob it has just damaged was reaching the pack as one number for the whole world,
  so a pack that paints the flash itself painted nothing: the red hit, the white of a creeper about
  to explode and the tint on a mob standing in fire all went missing the moment the pack took the
  mobs over. It is now read off the mesh, one value per vertex, which is where every one of the eight
  packs tested expects to find it.

- **A pack can tell one kind of mob from another again, and a chest from the block it stands in.**
  The three numbers a pack compares against a mob type, a block entity and the item being drawn were
  reaching it as one value for the whole world, because they were handed over per draw and a draw
  carries a whole batch of pieces at once. They now ride on the geometry itself, one value per
  vertex, out of the pack's own `entity.properties`, `block.properties` and `item.properties`. What
  it changes on screen is whatever the pack wrote on those numbers: the subsurface scattering some
  packs give to living things alone, the treatment of an end crystal and of its beam. Seven of the
  eight packs tested read at least one of the three, one of them only in the shadow map; the eighth
  declares one and never reads it. The lightning bolt is not among them and stays as it was: the game
  draws it with a mesh this engine does not read.

  The entity mesh only carries the three while the pack is drawing the entities or the hand, so
  turning either switch now rebuilds the world, exactly as the terrain switch already did. It does
  not ask for a restart.

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
  one of them looks the game's light map up with it, which is where it shows on screen. They reach
  the pack's shadow map as well, where every draw of them used to be dropped, on the light of the
  spot rather than at that full brightness. What that puts into the map is colour and not depth, so
  an eye lays no shadow of its own; what it changes is what a pack reading the map's colour finds
  there.
- **The wind a breeze throws and the swirl over a charged creeper are painted by the pack now**,
  where the game kept its own shader for both. What held them back is a texture matrix of the
  game's own that scrolls them, and that matrix is answered out of the game's transforms now, so a
  breeze scrolls rather than sitting frozen on one frame of its offset and two breezes on screen
  keep their own. The swirl writes every image the pack declares for its entities: on five of the
  eight packs tested that is between two and four images rather than the single one it would
  otherwise have reached.

### Changed

- **A chunk vertex only carries what the pack loaded actually reads.** Every vertex of every section
  used to carry the block id, the middle of the sprite, the offset to the middle of the block, the
  normal, the tangent and a second copy of the colour, whichever of those the pack asked for: forty
  four bytes each, whether four of them were read or none. The pack's own chunk programs are now read
  first and the mesh is built from what they name, so a vertex costs between twenty four and forty
  bytes. Of the eight packs tested, one drops sixteen bytes a vertex, one twelve, one eight and the
  other five four. It is video memory and the bandwidth of every chunk draw, so it is paid twice a
  frame as soon as a shadow map is being filled. Switching between two packs that read different
  names rebuilds the world, as switching the terrain off already did.

- **The normal and the tangent of a chunk vertex travel in one word instead of two.** The tangent is
  at a right angle to the normal, so once the normal is known there is nothing left of it but an
  angle and the direction the frame turns in, which is how the engine the packs are written against
  has always stored the pair. That is the last four bytes of the paragraph above, on every pack that
  lights the terrain by a normal, and it is where the upper bound of forty comes from. A pack reads
  the same two directions as before: the normal comes back four times closer than it did, and the
  tangent, which every normal map on the terrain is read through, is now exactly perpendicular to it
  rather than a few thousandths off, at the price of being up to nine tenths of a degree round the
  face from where it was.

- **The settings screen is the one pack authors already know.** It is the screen of the engine packs
  are written against, ported rather than approximated: the same list of packs with the shaders
  toggle at its head, the same pages of settings with the value of each in a sunken box beside its
  name, the same Cancel, Apply and Done, the same buttons and panels drawn from the same texture. A
  right click walks a value backwards, shift and a click give back the value the pack ships, a slider
  is dragged and only writes when it is let go, and a setting waiting to be applied is marked in
  amber. Hovering a setting brings up the pack's own words about it in a panel at the bottom.
- **The settings screen is reached from the video settings now**, under Vitrail in the list of pages
  on the left, and the icon this mod used to add to the title screen and the pause menu is gone with
  it. Both of those menus lead to the video settings already, so it is the same number of clicks from
  either and it is where anything about how the world is drawn is looked for. The `I` key and the
  Config button of the mod list still open the screen as before.
- **Closing the screen applies what you changed**, where it used to leave without writing. Cancel is
  the button that throws the changes away, and Apply is the one that writes without closing.
- **A pack is dropped straight onto the screen**, and a settings file exported and imported through
  the platform's own file window. Clicking a pack in the list now only selects it, so a folder of
  eight can be looked through without paying for eight loads; Apply and Done are what make a
  selection real.
- **The eye at the bottom right, and F1, take the screen away** so that the world behind it can be
  looked at while a setting is judged. Escape brings it back.
- **The button that reads a pack again is a small circular arrow** at the bottom left of the screen,
  mirroring the eye at the bottom right and, like it, shown in a world only, where it used to be a
  word on the button row shown everywhere. Editing a pack's shaders by hand and pressing it is still
  the way to see the change without restarting.
- **`R` reads the pack again without opening anything**, which is the key the engine packs are written
  against binds for it, and the real loop: edit a shader in your editor, come back to the game, press
  it. The game feeds no key while a screen is open, so from inside the settings screen the button is
  what reaches it. It says in the chat which of the two happened, and a reading that failed says why,
  in red.
- **The blur behind the settings screen fades out with the screen** instead of hanging at full width
  for half a second after the eye has taken the widgets away and then going out in one frame. How
  wide the blur is does not come from the screen in this game, only whether there is one, so the
  fade was fading nothing.
- What this engine has to say and the reference has not, a load that failed, how many settings
  `vitrail/options.txt` is holding down and how many passes this backend could not build, is on one
  line at the bottom left.

### Fixed

- **Mellow draws instead of showing you the game's own picture.** A pass may read a value that the
  step before it never sends, which a pack gets away with elsewhere and this engine refused: the
  refusal cost the pass, and a pack cannot lose a pass without losing the whole picture, so what you
  saw was the game with no shader on it at all. The value is now handed over rather than the pass
  being thrown away. Mellow is the pack it cost, on one of its lighting passes, and it draws now.

- **A pass reading its neighbour's value, which showed as a shader that simply looks wrong.** The
  other half of the same pairing, and the half that said nothing: a value a pass sends on and the
  next one never asks for used to push everything sent after it one place along, so the pass read
  the wrong value under the right name. Nothing was refused and nothing was logged, which is what
  made it hard to see. Sixteen passes across Bliss and Mellow were reading at least one value that
  way: the enchantment shine under Mellow, and under Bliss the block-breaking crack along with the
  post-processing steps that carry the sun's direction and the exposure, in every dimension.

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
