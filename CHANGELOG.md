# Changelog

What each version changed, written for somebody who runs the mod rather than builds it. Why a
change was made is in the commit that made it, and where it is a mechanism rather than a decision
it is in `docs/`.

A version number here, the tag that released it and the number inside the jar are one number: the
release workflow refuses a tag that disagrees with `mod_version` in `gradle.properties` rather than
publishing a jar named after one thing and built from another.

Everything is a pre-release while the version stays under `1.0.0`. Nothing here is a promise about
what the next one holds.

## Unreleased

### Fixed

- **Leaves keep the same self-shadow wherever the camera stands.** The shadow map is drawn
  with Sodium's chunk renderer, which keeps one filled batch of draw commands per region and
  pass and only refills it when the region's sections change or the camera crosses a section
  boundary in or next to the region. The camera's draw fills that batch without the faces the
  camera cannot see, and where the light and the camera kept the same sections the shadow draw
  took the camera's batch as it stood: the faces between the sun and the leaves were missing
  from the map, and a canopy's self-shadow came and went region by region as the player walked,
  every tree lighter or darker according to where they stood, where Iris showed one shadow. The
  shadow draw now keeps filled batches of its own, as Iris does.

- **Photon draws.** Three things stood between its files and its picture. Its atmosphere table
  is a three-dimensional volume of half floats asked to clamp, and the flat atlas that stands in
  for a volume took one byte a texel and a repeating volume only, so the three deferred passes
  reading the table, the sky map, the clouds and the shading itself, were dropped; the atlas now
  keeps a blob's channel type and lays a clamped volume out with its edges repeated. Its volumes
  are read through macros standing for the sampler's name, which the rewrite of those reads did
  not follow; it does. And a volume laid over the name of a colour target for one stage took the
  plain `sampler2D` reads of that name in the same stage with it, where the reference renames
  only a `sampler3D` of that name: Photon's composites read its cloud noise where the scene should
  have been, and every pixel came out red.

- **Every target starts from its clear colour when a pack loads or the window resizes**, both
  halves of it and whether or not the pack asks for a clear every frame. A half nothing drew into
  while the shaders were still compiling could keep whatever the driver had left in it, and a
  pack that keeps a target from one frame to the next then read that as its history. The
  reference clears everything once at that moment, and so does this.

- **A pack that writes a constant through a macro is no longer refused whole for it.** A
  `const` whose initialiser the Vulkan compiler would not take loses the keyword at load, and
  whether it would was judged on the names written on the line: a macro's name passed whatever
  it stood for, so a macro over a value this engine had already had to demote left a constant
  the compiler then refused, and one pass refusing takes the whole pack down. The macro is now
  read through. Photon writes its fog colours that way, and was refused at load on its blending
  pass.

- **A condition continued over several lines with a backslash is read whole.** The pack's
  conditionals decide which includes are followed, and one continued past its first line was
  decided on that fragment alone, which stood for true. Photon undefines its waving switches
  under one of those, so the include the switches guard was skipped and its terrain and shadow
  programs compiled against a function that was not there.

- **Photon's shadows are lit again.** The compute programs a pack attaches to its full screen
  passes were named in the log and skipped, so whatever they prepared for the pass after them was
  never there: Photon computes its sky lighting in one, and everything in shadow, the hand first,
  was black for the lack of it. Those programs now run, right before the pass they belong to. A
  compute whose pass the pack ships no fragment program for, and one attached to a setup program,
  are still skipped, and the log still names them.

- **Photon's distant water is no longer red, and no longer striped.** A pack may hand a stage a
  struct of values in one go, and Photon hands its water program its fog coefficients that way.
  That struct reached the other stage wrong, so the fog Photon's reflections computed with it
  saturated on every lake past a hundred blocks. A struct handed between stages is now taken
  apart and put back together the way a matrix already was.

- **An array handed between a pack's stages arrives whole.** Photon hands its sky harmonics to
  its deferred shading as an array of nine values, and the two values declared after it reached
  that stage read off the wrong slots. An array is now taken apart and put back together the way
  a matrix and a struct are.

- **AstraLex's window panes no longer bloom blue over their walls.** Its glass reflections march
  a ray across the depth of the world, and on some steps the depth read back was not the image's,
  so the ray landed on a pixel it never reached and the pane lit up with it, on some frames and
  not others. A lookup through a sampler that carries no mipmap chain is now asked for the base
  level of its image outright, which is what the reference's filtering always answered.

- **A mob standing just past the pack's shadow reach for entities keeps its shadow while any
  of it still reaches inside.** A pack that stops its moving casters short of the terrain
  (Complementary does, at an eighth of its shadow distance) had that reach measured on the
  caster's position, so a mob whose body reached back inside the distance lost its whole shadow
  where Iris drew it. The reach is measured on the caster's bounding box now, as Iris measures
  it: the difference is the caster's half width, under a block for most mobs and a few for the
  largest, and the shadow lands as far out as it does there.

- **A mob the player cannot see casts no shadow.** With Sodium's entity culling on, which is
  its default, a creature standing where the player's view does not reach, behind the player
  first of all, was left out of the shadow map even where its shadow fell in plain sight, and
  it came back the moment the player turned towards it. The casters that move are now chosen
  after the light's own walk of the world, off what the light sees, as Iris does.

- **The shadow map stopped where the camera's fog did.** With Sodium's fog occlusion on, rain
  and water shortened the walk that fills the shadow map along with the one that draws the
  world, so a far hill still casting into the view dropped out of the map. The light's walk is
  no longer shortened by the camera's fog.

## 0.9.0-beta

### Added

- **A Cache Cap slider on the video settings page**, how much compiled-shader disk this
  engine may keep, 128 to 2048 mebibytes in steps of 128. `512 MiB` is the old hardcoded
  ceiling and the default. Moving it applies at once, with no pack reload: lowering may drop
  unused cached shaders. Written in `vitrail/module-cache-ceiling.txt`.

- **A Shadow Map Scale slider under Video Settings**, on the mod's page beside Max Shadow
  Distance. The shadow map is drawn at the chosen fraction of the resolution the pack asked
  for, 25 to 100 percent, and the setting outlives whichever pack is chosen. Moving it reloads
  the pack, which is what lets the pack be told the smaller number instead of being left to
  work against a map it cannot see.

  It is a setting of its own rather than something the FSR Render Scale takes along with it,
  because of what it does to the picture. The pack works out its shadow blur, its shadow bias
  and where it reads the map from the size it was given, so a smaller map is a less defined
  shadow, softer where the pack smooths its own and coarser where it does not, and an image
  the pack's author never saw. That is a trade worth offering and not worth making on
  somebody's behalf, so at 100 percent, which is where it starts, the pack gets exactly the
  map it asked for and nothing of the setting runs. The engine says at load when the setting
  is in force.

  What the render scale reaches and what it leaves alone is now written down, in
  `docs/render-scale.md`.

- **A switch that fills the shadow map from a box around you rather than the tight
  silhouette.** An empty file `vitrail/box-shadow-cull` in the game folder, or
  `-Dvitrail.boxShadowCull=true`. The silhouette stays the default, which is what other loaders
  draw. The box was tried as the default, to stop leaf blocks popping at the sun's edge as you
  move, and measuring it is why it did not stay: on one wooded scene it drew 875 sections where
  the silhouette draws 322, and the frame ran twelve to twenty-eight percent slower depending on
  how much stood around. It also stops bounding anything at all once Shadow Distance reaches
  your render distance, which is where the slider sits at its maximum.

  What the box covers up is a defect of this engine and the switch is not its fix: leaves can go
  a frame without their shadow while you walk, if you run a mod that narrows what the game
  considers visible. That is being fixed at its cause instead.

- **A switch that hands every sine and cosine a pack writes back to the graphics driver.** An
  empty file `vitrail/driver-trig` in the game folder, or `-Dvitrail.driverTrig=true`. They are
  normally replaced at load with a helper that keeps its accuracy over a whole world coordinate,
  where a driver's own can shed the low bits and show as shimmer. The switch exists so the two
  can be compared in one world rather than across two builds, and the log says which of them a
  pack load ended up with either way. A pack that defines its own sine keeps it in both states.

### Changed

- **Compute shaders now hit the compiled-module cache on a pack reload.** They used to miss
  every time because the disk key hashed the load number the debug name carries. That number
  is out of the key. Computes are compiled by this engine, not by the game's shader compiler;
  what is kept is the compiled module and its bind table, the same files graphics shaders
  already write under `vitrail/modules`. Vulkan pipelines are built each load as before.
- **F3 names the pack the way Iris does.** The profile line is the scanned name or Custom, with
  how many options sit outside it, and the shadow line is Sodium's `C: a/b D: d` without this
  engine's own cull dialect.
- **The compiling overlay no longer covers F3.** While the debug screen is open, "Compiling
  shaders..." rides the Vitrail F3 line instead of the corner. Close F3 and the corner overlay
  is as it was.
- **Video-settings labels tell the truth about reload.** Titles are shorter so they fit the
  row. Each tooltip says whether the change applies at once, at the next frame, or reloads
  the pack.

- **Loading a pack a second time no longer compiles its shaders again.** The compiled module
  and the table of what it binds are kept under
  `vitrail/modules` in the game folder and read back when the same shader comes round again, so
  a warm load does no compiling and no inspecting at all. Editing a shader or moving a pack
  setting changes what is asked for, and so does updating the mod, the game or the loader, and
  anything that has changed is built from scratch as before. Each release keeps its own folder
  and takes the older ones away with it, the whole thing is capped, and the log says how many
  shaders came off the disk, how many were built and how much is stored.

  **It removes work rather than time.** Measured on one machine: a warm load compiles none of
  the two hundred odd shaders a cold one compiles, and the wait before the pack draws is the
  same to the second. What a pack load really costs sits somewhere neither this nor the log
  line is looking.
- **Loading a pack again no longer rewrites its shaders from scratch.** Turning a pack's GLSL
  into the dialect this backend speaks is one of the three things a pack load pays for, and it
  is the same answer every time the same pack comes back. What it came to is kept under
  `vitrail/translations` in the game folder and read back instead. Editing a shader, moving a
  pack setting, changing the shadow map scale, updating the mod or the game, or switching one of
  the mod's own measurement switches all change what is asked for, and anything that has changed
  is rewritten as before. Each version keeps its own folder and takes the older ones away with
  it, the whole thing is capped at a quarter of a gigabyte, and the log says how many programs
  came off the disk and how many were rewritten. It removes work rather than time: the wait
  before a pack draws is the same, and what changes is that none of it is spent here.
- **A shadow lookup a pack asks the hardware to compare is now compared by the hardware.** A
  pack declaring `sampler2DShadow` used to have that comparison rebuilt in shader
  arithmetic on every tap, a dozen instructions where a comparison sampler pays one, and a
  shadow's blur loop makes many taps per pixel. The comparison now rides the sampler, the pair
  Iris binds when a pack asks for its hardware shadow filtering, which every pack that declares
  the type does; the picture it computes is the same blend of the same four texels. For a
  machine where the sampler road is suspected of a wrong image, a file
  `vitrail/soft-shadow-compare` in the game directory, or `-Dvitrail.softShadowCompare=true`,
  puts the arithmetic back in one launch, and removing it again takes a pack reload.
- **A patch of this mod that stops fitting the game now refuses to start it, instead of drawing a
  wrong picture.** Those patches used to be dropped without a word when a game or Sodium update
  moved what they attach to, and what reached the screen was an effect quietly missing or a
  surface lit by the wrong program, with nothing in the log pointing at it. The failure now names
  itself at launch. The one exception is the line this mod adds to the F3 overlay, which is
  allowed to go missing because nothing on screen depends on it.
- **A texture's chain of shrunken copies is rebuilt only when its base has changed.** Packs that
  blur or expose through those copies asked for the whole chain to be rebuilt before every pass
  that reads one, even when nothing had touched the image in between; two readers in a row now
  pay one rebuild instead of two. The copies read are the same ones. Not measured, so this says
  work removed and not frames gained.
- **Two pieces of per-frame work on the graphics card are gone, and the picture is the same.**
  The far terrain's own depth image is emptied by the pass that draws into it rather than by a
  command of its own, which also spares the full stop of the card that such a command carries.
  The voxel volumes that follow the camera now copy only the part of themselves that survives
  the move, where they used to copy the whole volume. Neither has been measured, so this says
  work removed and not frames gained.

- **A handful of values handed to shaders are worked out once a frame instead of once for
  every program that asks for them.** Where the sun and the moon are, which way is up, the
  End's flash, and the two matrices a pack multiplies its vertices by were all rebuilt from
  scratch each time a shader asked, which for a large pack is dozens of times a frame out of
  numbers that had not moved in between. They are kept and reused until something they are
  built from actually changes. The values handed over are the same ones, to the bit, and this
  has not been measured either, so again it is work removed rather than frames gained.
- **The same picture with less repeated work inside the frame.** A pack's geometry program
  re-bound every texture it declares for every chunk region, mob piece and particle batch it
  drew, where only the two to four that follow the image in hand can change inside one pass;
  the rest are now written once, when the pass opens. Beside it: the far terrain's sections are
  collected into a list sized from the count Distant Horizons gives rather than grown into one,
  and handed on without a copy; a vertex format is searched for this engine's own elements only
  until the first one it has not got; and a pass is asked for its name only once everything
  cheaper about it has already matched. Whether the frame rate moves is a machine's to measure.

### Fixed

- **AstraLex night skies are round again.** A pack that interpolates a 3x3 matrix between
  vertex and fragment, AstraLex's planet among them, was stretched into an oval: this backend
  numbers varyings by count, and a matrix occupies several slots. Each matrix is now split into
  one vector per column before compile. Complementary never declared one, so it did not show
  the oval.
- **Turning Distant Horizons on reloads Complementary and then draws it.** The pack used to
  be reread with Distant Horizons present and then refused, because four uniforms stayed
  outside a block, so the world dropped to vanilla. The machine symbols are now installed
  before the pack is opened, so those uniforms enter the block and the reload draws the
  pack. The toggle still reloads; it does not keep the pack on without one.

- **The button that opens a pack's settings is no longer dead until the pack has been loaded
  once.** It was live only while a pack was already drawing, so the first pack anybody picks on
  a fresh install could not be configured at all: the only way in was to load it at whatever
  profile its author shipped, which on a small machine is the one launch that hurts. It is now
  live for the pack the list has selected, and pressing it reads that pack, which is parsing and
  no work on the graphics card, and puts its settings on screen while the loaded pack goes on
  drawing. Setting a heavy pack to its low profile before ever loading it now costs one load
  instead of two. A pack that lays no settings out says so instead of opening an empty page.
- **Breaking a block no longer paints flat black cracks over it.** The overlay that spreads as a
  block is mined was left to the game, and it multiplies its texture onto what is already drawn;
  it was landing on an empty layer instead of on the picture, so every crack came out opaque
  black from the first stage rather than darkening the face as it broke. It is drawn through the
  pack now, on `gbuffers_damagedblock` and its fallback onto the terrain, which is what a pack
  expects to be asked for.
- **A pack written with the old shadow lookups could be translated wrong, without a word.**
  Wrapping those lookups shifted the translator's own note of which words are macro names,
  which could rename one of them or leave a local variable reading a global value instead.
  Nothing in the tested packs reached it.
- **A pack that changes with how humid a biome is no longer sees a desert everywhere.** The
  `rainfall` value handed to shaders was always nought, so puddles, wetness and fog that
  scale with humidity stayed at their driest in a swamp and a jungle alike.
- **Changing shaderpack without restarting no longer leaves the buffers of the pack before it
  in play.** The names a pack gives its `bufferObject` blocks were remembered for the whole
  session rather than for the pack that wrote them, so a pack loaded after another could have a
  block bound to a buffer the earlier one had declared, at whatever size and contents that one
  asked for, or keep a program running against a block nothing binds at all. Complementary
  Ultra with world-space reflections on is the pack that declares such a block, and quitting
  the game between two packs was the only thing that cleared it.
- **A shadow compute pass that leaves its size to the engine now covers the screen.** A pack
  can say how much work such a program does in several ways, and only the one where it
  writes the count out itself was read. A program using any other ran as a single small
  tile: it filled a few hundred texels, and everything past them kept whatever the frame
  before had left, so the effect showed up in one square at the corner and was stale over
  the rest of the picture. Every pack tested here writes the count out, so only packs
  outside that set could see it. Two smaller things come with it: a pack asking for no work
  at all, which is how one of those forms switches a pass off, now dispatches nothing rather
  than one tile, and a program whose size cannot be read at all is left out with a line in
  the log naming it, rather than guessed at.
- **The log says why a pack is drawing the world with the game's own terrain shader.**
  When nothing of the pack reaches the chunk mesh, three quite different things can have
  happened: the pack was read and asks for none of the extra vertex data, or its load never
  got as far as its chunk programs, or the mesh was built before the pack had been read. All
  three used to share one line that named none of them, and the third only showed itself a
  world later, as an error about two lists of attributes that said nothing about the order
  that made them differ. Each now says which one it was, where it happened.
- **The game no longer crashes when AsyncParticles draws its GPU-accelerated particles under a
  shaderpack.** AsyncParticles can move its particles to the GPU and record their draws itself,
  into the very pass this mod had opened over the pack's colour targets, with pipelines built
  for a single target: the frame died on a colour attachment count mismatch a few seconds into
  the world. Those draws now take the pack's own particle programs, rebuilt over
  AsyncParticles' vertex layout, which is what Iris draws them with as well.

## 0.8.1-beta

### Changed

- **The link under an empty pack folder opens the CurseForge shader search.** It used to open
  the shader page on Modrinth, where this mod is not published.

### Fixed

- **The game no longer crashes when resources reload with a pack loaded.** Moving
  Anisotropic Filtering or Texture Filtering under Video Settings, switching resource packs
  and pressing F3 + T all reload every resource, and any of them took a world with a
  shaderpack on straight to the crash screen, on "Pipeline is not valid".
  The sky is drawn first in the frame and was the piece that fell over, but nothing the pack
  draws was safe. The reload now costs what it always should have, a few frames while the
  programs compile again.

- A pack swapped while the engine was still warming up could kill the worker that compiles
  the leftover programs ahead of their first draw. Those programs then paid for themselves
  at the first frame that needed them, which is a stutter where there had been none.

- **Mellow draws again.** Since 0.7.5-beta the pack loaded, laid out its settings and then
  drew nothing at all, the game's own picture staying on screen. One of its fullscreen
  passes was refused at compile, and a pack one of whose passes does not compile draws
  nothing at all. What that pass builds its outline from is a number its own settings fix
  before anything is compiled, and the engine was treating it as one that could still
  change. Any pack that writes a constant that way was open to the same.

- **E-LITE compiles whole.** Twenty of its programs were refused, because one value the
  engine supplies reached the shader a second time where the pack had already named it
  itself. A line that names several values at once is now read to its end, so nothing is
  supplied over a name the pack wrote.

- **A pack could come up with something wrong smeared across the picture, and stay that way
  until it was loaded again.** Not a flicker and not a glitch that passes: a patch of colour
  or noise that is simply there, on a machine where it happens and never on the one next to
  it. What was behind it: the images a pack draws into are wiped once when they are built, at
  every pack load and again whenever the window changes size, and that wipe was ticked off
  the list the moment it was scheduled instead of when it happened. One frame that drew none
  of the pack lost it, and the seconds a pack spends compiling are exactly those frames. The
  images a pack carries from one frame to the next are never scheduled again after that, so
  they kept whatever the graphics driver had left lying in them.

## 0.8.0-beta

### Added

- **An FSR Render Scale slider under Video Settings**, on the mod's page beside Max Shadow
  Distance. The world renders at the chosen fraction of the window, 25 to 100 percent, and the
  finished image is brought back to full size with AMD FidelityFX Super Resolution 1.0 before
  the interface is drawn, so menus, chat and the F3 screen stay sharp whatever the scale.
  Moving the slider takes effect immediately, the setting outlives whichever pack is chosen,
  and at 100 percent, or with shaders off, nothing changes at all.

- **A pack's coloured lighting runs.** The storage images, storage buffers and shadow compute
  pass behind it are served, and `IRIS_FEATURE_CUSTOM_IMAGES` is announced, so the voxel
  lighting of Complementary's top profiles, Euphoria Patches' Colored Lighting among them,
  draws instead of switching itself off behind a red overlay: lamps and torches colour the
  world around them. A pack that requires custom images loads instead of being refused. And
  that light holds still as you move: the glow of ores and lava used to flicker at every block
  crossed, worst going up, a ring of dark on each jump and a shimmer in upward flight against
  nothing at all on the way down, with a placed torch never showing it only because the
  ordinary block light covers it over.

- **A crash during startup no longer takes the graphics backend with it.** The game resets the
  preferred graphics API and the fullscreen mode after any startup that did not finish, whatever
  crashed, and nothing of this mod draws off Vulkan, so one crash used to cost a restart to set
  it back by hand. A selector under Video Settings picks what to come back to, Vulkan unless
  told otherwise.

- **A switch that puts the game's own wait back after every render pass.** A pack's passes end on
  a wait naming what the next one reads and writes, rather than on the game's wait for the whole
  of memory. That is what makes the frame cheaper, and it is also the kind of thing a driver can
  honour by accident: a wrong image on one machine and a right one on another is exactly what a
  missing wait looks like. An empty file `vitrail/full-pass-barrier` in the instance, or
  `-Dvitrail.fullPassBarrier=true`, puts the wide wait back on both roads where one was traded
  away, the close of a pack's pass and the filling of a mip chain. Slower, and it cannot be the
  cause of anything, so an image that comes right with it has named the problem.

- **The log names the first full frame's GPU stops.** One line per pack load, always on: how many
  render passes opened, how many textures were cleared and copied, then the labels, most frequent
  first. That is the number a Mac trace of queue submits is counting.

- **The F3 screen says what the shadow map cost.** Two lines beside the version and the pack:
  how many world sections were drawn into the map out of how many are loaded, at what render
  distance, and what the walk for the light measured against. They are Iris's lines for the same
  thing, in the same shape, so a screenshot under one reads against a screenshot under the
  other, and nothing has to be switched on for them to appear.

### Changed

- **Chloride is no longer required, on either loader.** One thing was behind that requirement:
  NeoForge opens a loading window before the game exists, that window carries an OpenGL context,
  and the game takes it over instead of making one, so the Vulkan surface was asked for on a
  window built for OpenGL. Vitrail now refuses that window itself when the backend is Vulkan,
  takes it off FML's hands at the end of mod loading, and closes it once the game has drawn its
  first frame. Fabric opens no such window and never needed the help. Chloride remains worth
  installing, its settings are still read and reported, and running it alongside changes nothing.

- **The odd corners of a pack's properties read the way Iris reads them.** An `alphaTest.`, a
  `texture.noise` or a `dimension.properties` line written under a setting's `#if` now comes and
  goes with the setting, a world declared twice keeps the last declaration instead of the first,
  a page laid out twice keeps its last line instead of both, and a pack writing
  `shadow.enabled=false` gets what it asked for: nothing is ever drawn from the light. No known
  pack is changed by any of this today.

- **The settings screen reads a pack's layout the way Iris does.** The `*` token now pours out
  every setting no other page names, where the pack put it; a pack without a `screen=` line gets
  one page holding everything instead of an empty one; and a name Iris refuses to configure, a
  constant off its closed list, a toggle nothing tests or a value without a list of choices,
  shows the same blank it shows there instead of becoming a slider or a toggle Iris never had.

- **Opening a pack's settings no longer applies it.** Looking at another pack's pages used to
  load it first, on its own default profile, so opening a heavy pack to turn it down paid the
  heavy profile in full. The pages now open on the pack that was picked while the loaded one
  goes on drawing; Apply is the only thing that costs, and a profile chosen before applying is
  the one that lands.

- **The shadow walk no longer rotates Sodium's command ring a second time in the same frame.**
  The lists still reset so the light does not append onto the camera's; the ring waits for the
  next camera walk. Same shadows.

- **A pack's frame opens fewer GPU stops.** Clears ride as the first pass's load, leftover
  sampled-before-write clears share one empty pass per size, mip chains blit instead of drawing
  each level, consecutive geometry that writes the same images stays in one pass, ping-pong
  targets swap names instead of copying texels back, and our own passes end on a wait naming what
  the next one samples and what it writes, rather than on the game's wait for the whole of memory.
  Same picture. Written for the extra submits a Mac report was counting; whether the frame rate
  moves is still that machine's to measure.

- **The frame that rebuilds the pack's programs pays far fewer clears.** A dimension change, a
  world join or a pack switch re-prepares every geometry program the scene draws, and each one
  rebuilt its own copy of the same one-texel constants, one standalone clear per texture: about
  ninety full GPU stops on that single frame, felt as the hitch at a portal. The constants are
  now built once and shared, and the same frame measures nine.

- **Pass uniforms reuse scratch matrices** instead of allocating a model-view-projection and a
  normal matrix on every fill, Sodium region draws skip leftover sampled clears once nothing is
  still owed one, and the shadow sweep reuses its plane scratch. Same picture, same numbers.

- **Improved transparency is turned off while a pack draws**, the same refusal Iris makes. That
  option opens a second colour target for translucent items, and every leftover feature that
  writes it is an Immediate draw this engine would otherwise keep as its own GPU stop.

- **Leftover Immediate draws and Distant Horizons' GenericObjectRenderer stay in the geometry
  pass already open**, the way Iris leaves the default framebuffer bound. A leftover pass is
  never adopted: its viewport and scissor would be whoever opened it.

- **Geometry programs compile during the same warm-up as the chain**, before the pack is drawn:
  families translate on a worker while composites compile, and the world waits until the
  composites and the terrain are in the device cache. From there the leftover families compile
  in the background, described under Fixed, and the pulsing mark in the corner carries the
  state from the held world to the last word of the closing show.

- **The wait for a pack skips the world, not the HUD.** Several programs compile a frame instead
  of one, so the wait is not a two-frame-per-second vanilla picture of the same work.

### Fixed

- **The freezes on first encounters are gone.** A pack's programs for the entities, the sky, the
  clouds, the weather, the particles and the far terrain used to compile on the frame that first
  drew each of them: around half a second frozen per program on a cold driver cache, over a
  hundred programs on a big pack, spread across the first minutes of play and paid again behind
  every portal. They are now compiled in the background while you play, several families at
  once, and a frame only waits for a program it reached before the background did. While that
  work runs, the Vitrail mark pulses in the top-left corner of the screen with "Compiling
  shaders..." and the running count beside it, the way the autosave floppy used to blink; when
  the work ends, "Shaders compiled!" stands beside the mark for a moment and the corner fades
  back to the game. An empty file
  `vitrail/keep-first-draw-compiles` in the instance puts the old path back for a comparison.

- **A pack's shadow compute is no longer built when the pack switched it off.** A pack turns
  whole programs on and off from its own settings, and the compute pass behind coloured lighting
  was read and handed to the compiler whichever way that setting stood. Switched off, the pack
  also takes away the colour tables and the image formats that pass reads, so what reached the
  compiler was not a shader at all and it was refused. The refusal came out as a warning, and
  there was nothing on screen to say a pass had been meant to run. BSL with its multicoloured
  blocklight off, Bliss, and both Complementary outside their coloured lighting profiles were all
  in that state.

- **A pack's storage images keep the words it wrote around them.** Translation rebuilt those
  declarations from the type onwards and dropped `writeonly`, `readonly`, `restrict` and
  `coherent` on the way, whichever side of `uniform` the pack put them. On Vulkan the first of
  those is what makes an image with no declared format legal at all, and it is the one the packs
  that ship a voxel volume write.

- **Distant Horizons' far water no longer paints over you.** In third person, with far water
  behind, the character came out washed out or gone entirely, and the part of him it covered
  followed the part of the far water behind him. Everything drawn between the two halves of the
  far terrain was covered the same way: mobs on the ground, particles, and the hand. The far
  water is drawn after them and had no idea they were there. It now stands behind whatever the
  game drew before it. What a pack reads under `dhDepthTex0` and `dhDepthTex1` is still the far
  terrain and nothing else, with one thing given up: where the near world hides far water
  outright, those names answer with the far terrain behind the water rather than with the water,
  on a texel where neither is visible.

- **Entering a world with Distant Horizons no longer washes the distance out to the sky colour.**
  Until that mod has drawn its first frame, which on a world it has no cache for takes a moment,
  a pack was told how far the far terrain reached in the wrong unit, and the number it got was
  sixteen times too small: a dozen blocks or so, rather than the thousands that mod actually
  draws. A pack that fogs by that number then fogged out everything past the end of its own nose,
  so the whole distance came back as flat sky until the far terrain appeared. Bliss shows it
  plainly. The number no longer waits on that first frame.

  Two smaller things move with it. Turning that mod's rendering off now gives a pack back the
  game's own distance, which is what Iris gives it. And a pack that leaves its shadow planes for
  the engine to resolve gets a shadow box sized for whichever terrain is really being drawn. No
  pack shipped today asks for that on its default settings.

- **Opening a world no longer crashes while leftover families are still being translated.**
  The frame used to walk those program maps as soon as the composites and the terrain were
  ready, which is the same moment the worker is still filling them.

- **Keeping consecutive geometry in one pass no longer leaves Sodium's last region scissor on
  the next family.** The scissor is cleared each time the hold is reused, which is the band
  that copied the top of the screen onto the bottom as chunks streamed.

## 0.7.5-beta

### Added

- **A GPU profile of the frame, per render pass, in the log.** Start the game with
  `-Dvitrail.passTimings=5` among the JVM arguments and every five seconds the log gets a table of
  where the card's time went, one row per pass label, the game's passes and Sodium's beside the
  pack's, sorted by cost. Off unless asked for. What a slow pack costs is now a question the log
  answers instead of a frame counter.

### Fixed

- **A pack that declares many textures and samples few of them can compile on Apple Silicon.**
  Metal only has sixteen sampler slots, numbered by the order the shader first names each texture
  rather than by how many it actually reads, so a used texture sitting behind unused declarations
  used to land on slot 17 and the pipeline was refused. The names a program samples are now
  declared first. A program that samples more than sixteen textures still cannot run there.

- **A pack that marks a computed matrix as `const` loads instead of falling back to vanilla.**
  Vulkan will not take `transpose` of a literal, or a uniform, as a constant initialiser, and one
  such pass used to fail the compile and take the whole pack with it. The keyword comes off and
  the value stays. Seen on Lux v1.2, a BSL derivative.

- **Sodium 0.9.2 no longer crashes when a pack extends the chunk mesh.** Its new arena allocator
  was sized for Sodium's own twenty bytes and refused the wider mesh a pack needs. The allocator
  now takes the same format the rest of the renderer already asked for. 0.9.1 is unchanged.

## 0.7.4-beta

### Changed

- **On a backend other than Vulkan, nothing of the pack is drawn any more.** The pack is still
  read and still shown in its settings screen, so it can be picked and configured ahead of the
  restart, but the game keeps its own image: the passes used to run there and draw a picture that
  was credible and wrong. A chat line the first time a world is shown says so and names the setting
  to change, Graphics API to "Prefer Vulkan (Experimental)" under Video Settings, beside the error
  the log already carried.

### Fixed

- **Body Camera's film grain moves again.** The 0.7.3 hash rewrite took the frame time out of
  the grain, which writes it into the second argument of the idiom, and left a pattern stuck to the
  screen. The rewrite now only fires on a constant second argument; BSL's waving is untouched.

- **No more dark oval under a mob when the pack draws its own shadows.** The game's flat entity
  shadow was still drawn, lit as a translucent entity, under every mob the pack was already
  shading with its shadow map. It now goes away for as long as the pack has a map, which is what
  Iris does, and stays under a pack without one. The defect only showed with Entity Shadows on in
  the video settings.

## 0.7.3-beta

### Fixed

- **Grass and leaves no longer skip while they sway under BSL.** Packs that hash their waving
  with a huge sine were jumping on this backend and smooth on the reference. That hash is now
  taken from the vertex bits instead. Complementary was already smooth; it never wrote that
  idiom.

## 0.7.2-beta

### Fixed

- **The hand and the entities no longer stretch into giant triangles after a pack turns on or
  off.** The 0.7.1 entry below called what its crash fix left behind cosmetic and brief, and it
  was neither: the game's own entity pipelines keep the previous vertex layout until a resource
  reload, so anything they drew over the rebuilt mesh, the hand with its switch off, every mob
  and hand for the seconds a chain takes to compile, smeared across the screen and stayed that
  way for the rest of the session. The engine now takes those pipelines out of the device's cache
  the moment the layout moves and compiles them again on the spot; what left the cache is freed
  at the next reload, the one safe moment the crash fix established, so the crash stays gone.

## 0.7.1-beta

### Fixed

- **Removing, picking or reloading a shader pack in a running world no longer crashes the game.**
  The 0.7.0 entry below said the crash was reduced and not gone, and this closes what remained,
  which was two more faults of the same family. A reload freed the pack's images a moment before it
  stopped answering for them, so a pass of that very frame could still be handed them; and loading
  a pack that draws the entities or the hand emptied the graphics device's whole pipeline cache at
  an instant where work already recorded still named what was destroyed. Nothing empties that cache
  any more: the game does it itself on every resource reload, which is the one moment it is safe.
  What that leaves is visual, and worse than this entry first said: an entity or the hand drawn by
  the game's own shader can look wrong from then on, until a resource reload clears it, which the
  entry above is what removed. Tried at length on both loaders, turning packs off and on and
  switching between four of them, where one to four such gestures used to end the session.

## 0.7.0-beta

### Changed

- **Distant Horizons no longer draws its own ambient occlusion and fog while a pack is drawn.**
  Between them they are three full screen passes over an image nothing downstream reads, so their
  work was thrown away one pass later, and two of the three ran over the whole screen rather than
  leaving it early. Both are on unless the DH menu says otherwise, so an install that changed
  nothing was paying for them on every frame. They go back to whatever that menu says the moment a
  pack is unloaded.

- **Three things taken on every frame are now taken when something reads them.** The search for a
  lightning bolt walked every entity the world would draw, on every frame, to find the one kind of
  entity that is almost never there; it runs once a tick now, which is the rate the reference
  publishes that value at. The count of what the camera saw was taken on every frame for a line
  printed once per pack. And the pack's number for an item was spelt out again at every submission,
  where the number for a kind of entity had been remembered since it was written.

### Fixed

- **Turning the shaders off and back on, or picking another pack, takes the game down far less
  often.** Two rings the far terrain writes its section corners into were closed the moment a pack
  was unloaded, while a pass already recorded still held slices of them, and the graphics device was
  lost a few seconds later with nothing in the log to say why. They belong to the engine now rather
  than to the pack, so a load has nothing to close. It is not the whole of that crash: a second
  fault of the same family survives it, and where the pack could not be put back once before, it now
  takes somewhere between one and four goes. Issue 111 follows what is left, and until it is closed
  a pack changed in game remains a thing that can end the session.

- **The same pack now translates to the same text on every start.** Four tables the translator
  walks to write a shader were handed back in an order the runtime picks afresh each time the game
  starts: the varyings a stage is owed, the vertex inputs a mesh has not got, and the volume
  helpers a pack's three dimensional textures need. Two launches on the same pack could therefore
  produce shaders that differ only in the order of their declarations, which is the same shader to
  a reader and a different one to the game, so it compiled pipelines it already had. One warning
  line about mesh elements a program computes from and does not get also listed its names in a
  different order each run.

- **One character no editor should have written no longer costs the player every pack.**
  `vitrail/options.txt` was read as strict UTF-8, so a single byte from another encoding stopped
  ANY pack from loading, under a message that named neither the file nor the encoding. A byte order
  mark, which several editors add on their own, threw nothing and quietly ate the first setting of
  the file. Both now cost one character of one value at worst, and `vitrail/pack.txt` is read that
  way too. That file is also written through a temporary and moved into place, so a crash while it
  is being written can no longer leave half a line behind, which read as no pack chosen at all.

- **The shadow distance now bounds every chunk it is meant to, and not only the ones weighed one by
  one.** A group of chunks that merely reached into the distance was handed to the walk whole, so
  everything standing under that group was drawn into the shadow map however far past the distance
  it stood. Lowering the distance, from the pack or from the video settings, therefore saved less
  than it should have, on the second walk of the world, which is the most expensive thing a frame
  does here.

## 0.6.0-beta

### Added

- **Distant Horizons' far terrain now casts into the pack's shadow map.** A pack that ships a
  `dh_shadow` program has it drawn, from the light, over the same distant land its `dh_terrain`
  draws for the camera, so a far hill lays a shadow on the ground instead of only shading itself
  out of its own depth. Packs that write `dhShadow.enabled=false` are taken at their word, which is
  most of them; the one pack tried here that ships the program keeps it behind a setting of its own
  and it has to be switched on. It also follows the two words that govern the near world in the map,
  so a pack that keeps the world out of its own shadow map keeps the distant land out with it. What the map holds is the distant land the camera can see, which is
  narrower than under Iris: that mod gets a second list of it, built for the light, and there is no
  second list to be had here.

### Changed

- **Two things taken on every frame are no longer taken for a pack that cannot read them.** The
  copy the shadow map is read from as `shadowtex1` is sixty-four mebibytes a frame at a shadow map
  of 4096, and the depth kept from before the hand is a full screen image and a draw on every frame
  the hand is drawn. Each is now skipped where no file of the pack writes the name that reads it,
  which the log says out loud when it happens. Of the eight packs tried here, one skips the shadow
  copy and five skip the depth.

## 0.5.0-beta.1

### Added

- **The debug screen names the engine, the pack and its profile**, where Iris has always put those
  three lines. A screenshot taken with F3 open is how the question "what drew this, with what, set
  how" arrives, and until now a Vitrail frame and an Iris frame of the same pack read as the same
  picture. A pack that is not being drawn says which of the three reasons it is rather than reading
  as none at all: shaders switched off, a pack named that is not in the folder, or a pack the
  engine refused, the last of which sends you to the log rather than to the settings screen.

### Changed

- **One download for both loaders.** The release now carries a single jar,
  `vitrail-<version>+mc<minecraft>`, that runs on NeoForge and on Fabric alike; each loader reads
  its own metadata out of it and ignores the rest. It is the same engine the two per-loader jars
  carried, in one file, and the per-loader downloads stop being published. Nothing changes in
  game, and nothing changes about what has to sit next to it: Sodium, Chloride, and on Fabric the
  Fabric API. Upgrading means replacing: delete the old `vitrail-neoforge-*` or `vitrail-fabric-*`
  jar when this one goes in, because the two carry the same mod and a loader that finds it twice
  refuses to start.

### Fixed

- **Complementary no longer falls back to its oldest code paths.** The engine told packs it was
  Iris but never which one, and a pack that asks compares against zero: every "on an old Iris, do
  it the old way" branch in Complementary Reimagined and Unbound was live. The engine now
  announces the Iris release it behaves like, so those branches pick the modern side: rain and
  thunder fade over the pack's own timer instead of a hardcoded three seconds, the sun and moon
  stop leaning on a workaround for a renderer bug this engine never had, and reflections drop a
  workaround for a texture bug fixed before the release this engine mirrors.

- **On Fabric, the game no longer throws away every selected resource pack at boot when a slow
  mod set is installed.** Reading the shader pack used to hold up the very first frame, and when
  the boot's resource reload finished inside that pause the game stitched its atlases before it
  had a frame's uniforms to do it with: "Missing uniform Globals", and every resource pack was
  deselected. Seen with Distant Horizons installed, whose extra programs made the pack read long
  enough to lose the race. The pack is now read once the boot reload is over, which is where
  NeoForge always effectively had it.

- **Bliss no longer stands its swamp fog over any shoreline.** Biomes were numbered by walking the
  level's registry, which hands them out alphabetically, while a pack compares against the numbers
  Iris hands out, which are the game's own declaration order. Six is swamp there and cold ocean
  here, so the green followed you along every coast. The numbering is now taken where Iris takes
  it, and a biome the game's own list never declares answers nought, as it does under Iris.

- **Distant Horizons terrain stops washing out flat under packs that read the light raw.** The far
  terrain family was handed raw light levels scaled by sixteen; Iris hands level i as
  (i + 0.5) / 16 and answers the fixed function light matrices with the identity, so a pack reading
  that light through the matrix or raw gets the same number either way. BSL and Complementary
  multiply the matrix in and were never affected. Bliss reads the pair raw, and from sky level one
  upward its light map saturated and its far terrain went flat.

- **Under Bliss, the game's sky no longer paints over the far terrain.** The engine fills the
  game's own frame back in wherever the world's depth stands in front of what a pack claimed, and
  the world's depth holds nothing where an LOD stands, the game never rasterising the far terrain
  itself: those pixels read as unanswered and were covered over the colour the Distant Horizons
  programs had just written. Only Bliss showed it, its sky pass being the one the translation
  cannot hand a coverage mask to, every other pack's sky claiming those pixels itself. The far
  terrain's own depth is read now.

- **The line printed when the game came up on OpenGL no longer says the pack draws nothing.** The
  passes do run on that backend, and what they draw is a picture both credible and wrong: the
  programs are translated against Vulkan's depth and clip conventions, so a sky cut in two or a
  misdrawn hand there is the backend and not the pack. The line owns that now, which is the point
  of it, a report filed against the pack being the one thing it exists to prevent.

## 0.4.1-beta.1

### Added

- **A Max Shadow Distance slider in the video settings, under Vitrail.** Pulling it in is the
  cheapest frame rate a shader pack has to offer: the world is walked a second time for the light
  every frame, and this decides how much of it. It goes from 32 chunks down to none at all. At none
  the shadow pass does not run: the shadow depth every pack reads is emptied to its far plane, so
  nothing casts a shadow, and the only thing left standing is a `shadowcolor` buffer the pack asked
  to keep between frames, which holds the last one drawn until the setting moves again.

- **A pack that fixes its own shadow render distance now gets it.** `shadowDistanceRenderMul` was
  read by no part of this engine, so the light gathered the whole world it could see whatever a pack
  asked. Seven of the eight packs tested declare that line, and the shadow walk now stops where they
  meant it to: Mellow and Body Camera at 96 blocks, Complementary and Reverie at 192, BSL at 256.
  How many of them that changes anything for depends on your render distance, since a shadow walk
  asking for more than the world you have loaded is left alone: at 12 chunks it bites on the shorter
  three and not on the rest. Those packs decide the distance, so the slider greys out and says so;
  it is the packs that stay silent, Sildur's among them, where it is the player's to move.

### Changed

- **The shadow map stops being drawn from parts of the world that cannot cast into what you are
  looking at.** The light used to walk everything inside the box its map covers, the ground behind
  you included, which is a second copy of the world drawn every frame for shadows nothing ever
  samples. It now walks the camera's own view swept along the sun instead: what stands between the
  sun and what you can see is kept, what could only ever shade your back is dropped. Expect more
  frames per second wherever shadows are on, and no change to the picture.

  The shape is the pack's to choose through `shadow.culling`, which is read now where it used to be
  ignored, together with the `voxelDistance` distance behind one of its states. A pack asking for a
  box about the camera instead of that sweep, or for no view frustum at all, gets what it asked for.
  The log line the shadow stage prints on the first frame of a pack now names the shape as well as
  the two counts, so the gain can be read off it.

### Fixed

- The texture filtering selector in the video settings no longer offers RGSS while a pack draws the
  world. That method is written into the terrain shader the game and Sodium ship, and neither of
  those two draws once the pack's own terrain program does, so the setting read as live and moved
  nothing at all. It comes back the moment no pack is drawing the terrain. Anisotropic filtering is
  untouched and goes on working under a pack.

- Naming a pack that is not in the Shader Packs folder now says so, in the chat line the reload key
  answers, on the settings screen and in the log, with the name that went unanswered. It used to
  read as if no pack had been asked for at all, so a renamed or deleted pack looked like a choice.

## 0.4.0-beta.1

### Added

- **The far terrain of Distant Horizons is drawn by the shader pack itself, lit and fogged like
  the land it meets.** That mod draws its distant land into images of its own and paints only the
  colour back onto the picture, so no pack ever saw it: no fog on it, a depth of field focused past
  it, water that did not know it was behind it. Its geometry is now handed to the pack's own
  `dh_terrain` and `dh_water` programs, drawn into the pack's own targets, and its depth is served
  beside the world's under the names packs read it by, which is the arrangement they are written
  against. The fog, the shadows and the occlusion on the distant land are the pack's own from
  there, and the seam where it meets the near terrain closes.

  Two limits worth knowing. A pack that ships no `dh_terrain` keeps Distant Horizons' own drawing,
  which no pack effect touches. And the far terrain does not enter the pack's shadow map yet, so
  what shades it is what the pack computes from its depth rather than a shadow the map carries.

  What this waits on is Distant Horizons and not Vitrail: that mod has to draw on this backend at
  all, which the version named in `INSTALL.md` does not, and it has to be one whose insides this
  engine can read, which the log says when it is not, naming that mod as installed but not in a
  shape its far terrain can be served from. The log names Distant Horizons as found when it is,
  and names each far terrain pass as the pack's programs take it over.

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
- The hitch when joining a world, crossing a dimension or switching packs is shorter. Every
  shader program used to clear its own copy of the same one-pixel textures each time the pack was
  rebuilt, about ninety GPU stops in a single frame, hardest felt where every stop is a queue
  submission of its own. They are now made once and shared.

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
