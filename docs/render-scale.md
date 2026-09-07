# The render scale, and what it covers

**Render Scale**, under Video Settings on Vitrail's own page, draws the world at a fraction of
the window and brings the finished picture back to full size before the interface. It is the
setting to reach for when a pack runs but runs slowly, and it is worth knowing exactly how far it
reaches, because a lot of a frame is not measured in pixels at all.

Two things about it before anything else. At 100 percent it does not run: the world is drawn at
the window's own size and the upscale is never even started. And it only engages while a pack is
drawing, so with shaders off it changes nothing either.

## What follows it

Nearly everything with a screen-sized life, and it follows without being told. The whole mechanism
is the size of the game's main render target, and the rest of the frame reads its size off that
one object each frame rather than off the window:

- the pack's colour targets, and the depth images the engine converts for it;
- the translucency targets the game's own frame graph describes;
- `viewWidth` and `viewHeight`, which is what a pack computes its own texel sizes from, so a pass
  that samples its neighbours keeps sampling the right ones;
- the entity outline target, which is resized alongside by hand because it is the one screen-sized
  target allocated outside the frame's pool.

So a composite chain of thirty passes runs on thirty smaller images, and none of those passes had
to be told about it.

**The interface never follows.** The scaled picture is drawn up onto the window-sized colour
texture before any widget lands on it, so menus, chat, the F3 screen and the hotbar stay at the
window's own resolution whatever the slider says.

## What does not follow it

**The shadow map is the one screen-sized-feeling thing that keeps its own number**, and it is not
an oversight. A pack asks for a square map by its own directive, 1024 unless it says otherwise,
and everything it then does with shadows is computed from that number: its filter radius, its
depth bias, and the coordinates it reads the map at. A map allocated at any other size than the
one the pack was told is not a coarser shadow, it is a picture computed against an image that
does not exist.

That is why it has a slider of its own, **Shadow Map Scale**, beside the render scale and
defaulting to the whole map, and why moving it reloads the pack. What the slider changes is the
number the pack is TOLD, by rewriting the declaration the pack makes of it before a line is
translated, so the pack recomputes everything against the map it really gets. That is what makes
a smaller map a wider penumbra: the filter radius is a fraction of the map, so it covers more of
the world as the map shrinks.

**That holds while the pack smooths its shadows, and not otherwise.** A pack taking a single
sample has no radius to widen, so a smaller map simply gives it a coarser edge, and packs ship
quality profiles that turn shadows on without their filter. Either way the pack is computing
against the map it has, which is the part that matters; what a player sees at the low end is not
the same in both cases.

It is a real trade and a player has to make it deliberately; dragging it along behind a slider
about the window would hand it to somebody who only wanted the world smaller. The engine says at
load that the setting is in force, and the line where the map is allocated says the size it came
out at.

It composes with the pack's own setting rather than replacing it. Four packs of the test corpus
already offer this resolution as a slider in their own screen, and what this scales is whatever
the pack and the player between them settled on there.

The panorama capture does not follow it either, and never will: it renders the world at 4096
square down a path that is not the ordinary frame at all, so the scale never sees it.

## The upscale is a fixed cost

The picture is brought back up in two passes, AMD's FidelityFX Super Resolution 1.0: an
edge-adaptive spatial upsample into a window-sized image, then a contrast-adaptive sharpen onto
the game's colour texture.

**Both of those run at the window's resolution, not at the scaled one.** They write every pixel of
the window whatever the slider says, so what they cost does not shrink as the scale goes down. It
is a fixed addition, paid once per frame, that buys back the sharpness. Lowering the scale from 70
to 50 makes the world cheaper and leaves the upscale exactly where it was.

## Folding the frames together

A world drawn small loses the thin things first, and those are exactly what crawls as the camera
moves: distant leaves, fences, the far edges of terrain. The spatial upscale above cannot put them
back, because it only ever sees one frame and the detail is not in it.

**Temporal Fold takes it from the frames before instead.** Several small pictures of a moving scene
do not carry the same detail as each other, so blending them recovers some of what any one of them
dropped, and the picture at a low scale settles rather than crawling. Each pixel is matched to where
it stood a frame ago, which the engine works out by reprojecting the depth through the two cameras,
and the value found there is held to the range of the pixels around it before it is mixed in. That
clamp is what keeps a match that landed on the wrong surface from dragging a colour across an edge.

It is off until asked for, and the checkbox greys out at a scale of 100 percent, where there is
nothing to rebuild and the pass would be spent for nothing.

**What it costs is two more passes and three more images**, on top of the fixed cost above. One pass
writes the match at the render size into a two-channel image, and one folds at the window's size
into a pair of half-float images, a pair because a fold cannot read the image it is writing. At
1920x1080 that pair alone is about 32 MiB.

**What it does not carry is anything that moves on its own.** The match is worked out from the
camera alone, so a mob walking across a still screen is matched to where its pixels were rather than
to where it was, and it keeps a faint trail. The clamp bounds how far that can go, which is why the
result reads as a slight softness on moving things rather than as a smear behind them.

## Per-pass costs do not shrink either

The scale buys fragment work and nothing else. A frame also pays for things counted per pass, per
draw or per object, and a smaller picture makes none of them cheaper:

- the number of passes the pack runs, and the pipeline binds and barriers between them;
- the uniforms written for each program, one block filled per program however few pixels it covers;
- the geometry submitted. The world is walked, culled and drawn from the same sections at the same
  render distance, into fewer pixels.

A pack that is slow because it runs forty composites will still run forty of them at 50 percent. A
pack that is slow because each of them reads six full-screen textures is the kind the scale helps
most.

## Deciding what to lower

In the order of how much they change what is drawn rather than how it is drawn:

- **Shadow Distance** changes what is drawn at all: terrain and entities past it are not
  submitted to the light. That is geometry not walked, not culled and not rasterised.
- **Shadow Map Scale** keeps the same geometry and rasterises it into a smaller map, and tells
  the pack that is what it got. It costs less defined shadow edges, softer or coarser depending
  on whether the pack smooths them, and a pack reload each time it moves.
- **Render Scale** keeps the whole frame and rasterises it into fewer pixels, then buys the
  sharpness back with a fixed pass at full size.
- **The pack's own settings** are the last and often the largest: a pack's own shadow, volumetric
  and reflection quality settings are what decide how many passes there are to pay for.

None of these has a number attached here on purpose. What each costs depends on the pack, the
machine and where you are standing, and the honest way to know is to try one at a time and watch
the frame rate the game itself reports.
