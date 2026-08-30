# The sky and the shadow map

These two are grouped because they share a problem: both are drawn by the game through paths that
do not look like ordinary geometry, and both force the engine to answer questions the terrain never
raises.

## The shadow map

### A pack asks for a shadow map by shipping a program

There is no flag. If a pack serves no shadow program, and none is reachable through the fallback
tree, there is no shadow stage at all.

That refusal is not thrift, it is safety. The shadow stage runs at the end of the frame, and with
no program to serve it, the pass the renderer opens is the game's own target, so the stage would
paint the world, seen from the light, over the finished image. For the same reason, when a refusal
arrives in the middle of a session the map is emptied rather than left frozen: a stale map is
served to the pack as if it were current.

### The map is square, at the size the pack asked for

The resolution is the pack's own directive, 1024 unless it says otherwise, and it is the one image
of the frame that is not sized from the window: it does not grow with the screen and it does not
shrink with the render scale.

A player can ask for a fraction of it with the Shadow Map Scale slider, which is off by default.
The slider rewrites the pack's own declaration of the size before a line of it is translated, so
the pack computes its filter radius, its bias and its texel coordinates against the map it really
gets: where the pack smooths its shadows a smaller map is a wider penumbra rather than a coarser
one, and where it takes a single sample there is no radius to widen and the edge simply coarsens.
Moving the slider reloads the pack. What that trade is worth against the others is [The render
scale](render-scale.md); the engine says at load when the setting is in force, and the line where
the map is allocated says the size it came out at.

### The light view is a rotation about the camera

The shadow frustum is centred on the player, not on the sun. This matters when reading a pack:
distances in a shadow program are relative to the viewer, and the map covers a region that follows
them.

### Distortion belongs to the pack, and the engine must not help

Packs distort the shadow map so that resolution concentrates near the viewer. That distortion is
written entirely in the pack's own GLSL: it scales the depth component in its shadow vertex stage
and undoes it in its lighting include.

The engine's only obligation is to store the depth window the pack expects, term for term. Any
engine-side attempt to correct the distortion fights the pack and produces a wrong result that
looks like a shadow bias problem.

Two consequences worth knowing, and they are not the same one.

The shadow family binds its own catalogue of fixed-function matrices rather than the camera's,
because during the shadow pass the model view and the projection *are* the shadow pair: a pack's
shadow vertex stage says `gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex` without ever naming
a shadow. Handed the camera pair it would draw the map from the player's eye, which is a shadow map
of the wrong thing and looks like a shadow map all the same.

Separately, that stage typically multiplies the inverse shadow matrices by the fixed-function
transform helper and counts on the product collapsing. It only collapses if the inverses and the
pair under the helper are the *same frame's*, so the catalogue answers from the pair being drawn
with, this frame's, while the published shadow matrices are the previous frame's. That is a
consequence of drawing the map a frame ahead, described further down.

### Shadow depth uses the opposite convention from the scene

The scene is drawn in reversed Z (cleared to zero, tested greater-or-equal) because that is what
the game does and it is better conditioned in floating point. The shadow map is stored in the
legacy forward convention, cleared to one and tested less-or-equal.

The reason is that nothing converts it on the way out. The *scene's* depth is turned round once, in
the image, when the copies handed to the pack are taken. The shadow map is copied too (that is how
the pack gets a view without translucents), but it is never converted, so it has to be **stored** in
the convention the pack expects. The two are the same rule, hand the pack the window it reads in,
applied at different places.

### A shadow sampler implies a comparison the game's sampler cannot express

This was the hard wall of the whole feature, and it is worth stating precisely.

In GLSL, a shadow sampler carries a hardware depth comparison: the sampler is configured with a
compare mode, and a read returns the *result of a comparison* rather than a depth. The game's
sampler abstraction has no such mode: it exposes address modes, filters, anisotropy and a maximum
level of detail, and nothing else.

Bound naively, a shadow sampler becomes an ordinary sampler and the comparison means nothing. The
symptom is not a crash: the entire world comes back uniformly in shadow, which looks exactly like a
badly drawn shadow map, and sends you looking in the wrong place.

The engine answers it on two roads. Where the names behind the declaration are the shadow map's
own, the declaration keeps its spelling, the lookup compiles to a depth-reference sample, and the
binding slips a comparison sampler made in Vulkan's own terms under the name, past the game's
abstraction: linear filtering, clamped edges, and the LEQUAL sense the format specifies, agreeing
with the forward depth window where nearer to the light is smaller. It is the pair Iris binds when
a pack asks for its hardware shadow filtering, and a projective comparison needs nothing more
there: the call is the division and the comparison in one.

Everything else compared, and every compute program, takes the arithmetic road: the comparison is
stripped from the declaration and each *plain* read is rewritten into a comparison emitted in the
translated shader, the same four texels compared and then blended by the same bilinear weights the
hardware would use. Only the plain lookups: a projective or gathered comparison divides or spreads
before it compares, which needs a different expression and not a different name, so it is left as
it stands and counted instead. A launch can send every unit down this road with a file
`vitrail/soft-shadow-compare` in the game directory or `-Dvitrail.softShadowCompare=true`, for the
day the comparison sampler is suspected of a wrong image: bound wrong it does not fail, it hands
back a credible fraction of the wrong thing.

Two ordering traps come with it. The comparison samplers have to be collected *before* the depth
rewrite runs, because uniforms are only lifted afterwards and the set that decides would otherwise
be empty. And legacy shadow-sampling calls have to be handled where they are born: by the time
identifier rewriting has fused them into a plain texture call, the depth conversion can no longer
recognise them.

### Two maps, one render

A pack can read the shadow map with translucent geometry included or excluded. The second image is
a *copy* taken between the opaque and translucent halves of the shadow stage, not a second render.
The translucent shadow pass is served through the fallback tree, unblended and without alpha test.

Two of the three names are fixed: one always reads the map without translucents, the other never
does. Only the bare `shadow` moves, and it moves when a program also declares the water-shadow name:
then that name reads the map with the translucents in it and `shadow` falls back to the one
without. No pack of the corpus writes it at all.

Coloured light through stained glass does not rest on that swap. It rests on the pair plus the
shadow colour buffer: a point occluded in one image and clear in the other has something translucent
between it and the light, and the tint comes from the colour buffer.

### What goes into the map is decided in its own namespace

The directives that describe shadow colour buffers (their format, whether they are cleared, and to
what colour) must be indexed separately from the ordinary colour target directives. Indexed
together, a directive naming shadow colour buffer zero would silently decide the format of colour
target zero.

The clear colour matters even when clearing is switched off, because it is then what the buffer
starts with. Depth always clears, regardless.

One pipeline trap: under dynamic rendering, the shadow pipeline's colour state has to name the
attachment's actual format, and the axis that bites is the **channel count** rather than the bit
depth. A state naming four channels against the single-channel buffer a pack asked for is the
pipeline refused outright, by name and in the middle of the world.

### What moves is submitted a second time, for the light

The terrain reaches the map through the chunk renderer's own lists. Everything that moves (mobs,
the player, and the block entities a section carries beside its mesh) does not: the game clears
both lists on the line after it submits them, and the shadow stage stands at the end of the frame,
so by the time it runs there is nothing left to read. The map is therefore filled from a second
walk of the world, with its own submission storage and its own feature dispatcher, which is also
what Iris does and for a reason that holds here too: the camera's lists were culled against the
camera, and what belongs in a shadow map is mostly what the camera cannot see.

Those pieces are drawn with one program for the lot, `shadow_entities`, whatever the camera would
have used for them. A chest and a mob are submitted through the same pipelines, so the mark that
buys a block entity its own program against the camera buys nothing against the light. Every row
discards at a tenth and none of them blends: what a map wants of a translucent surface is the depth
that surface stands at, not that depth mixed with the one behind it.

**A piece this engine has no shadow row for is dropped rather than handed back**, and that is a
divergence. Iris binds its shadow framebuffer for the whole of its stage, so a pipeline its table
has no key for keeps the game's own shader and still writes the map. Here the target is chosen per
draw, and steering the game's own pipeline onto the map's attachments is refused by format in the
middle of the draw, so there is nothing to hand back to. What it costs is a caster that casts no
shadow; the alternative costs worse, the pass open at that moment carrying the finished picture, so
the caster would be painted across the frame the player is looking at.

The ground oval under a mob is not in the map and not under the mob either, for as long as the pack
draws a map: the dispatcher never submits it then, which is Iris's rule. A pack with a map lights the
ground under a mob with that map, and the game's oval on top of it would be a second shadow no pack
author ever saw under their own pack. Under a pack without a map the oval is the only shadow a mob
has, and it stays, drawn with the translucent entity program as Iris draws it.

The two halves of that second walk are gathered at different moments, and it is not a tidiness.
The entities are worked out before the light's own walk of the sections, since nothing that decides
whether one is kept moves with that walk. The block entities can only be gathered after it: what
says which sections to ask is the set of render lists the walk has just filled, and the terrain
renderer hands them over off those lists. Asked any earlier, the question has the camera's answer or
none at all.

Which families reach the map at all is the pack's to decide, through six keys of
`shaders.properties` and one `const float` of its source. Those keys and the trap in two of them
are described once, under [the pack format](pack-format.md); they are not repeated here.

### The far terrain is drawn from the light too, out of sections kept from the frame

A seventh family reaches the map. Where Distant Horizons is running and the pack ships a
`dh_shadow`, its distant land is drawn into the map with that program, at the two moments the
world's own chunk groups are drawn there. It has a key of its own, `dhShadow.enabled`, and it is the
one key of this family that is ON when a pack says nothing.

**It is held by three words rather than one**, and the two extra ones are not its own: its opaque
half is drawn where `shadowTerrain` lets the opaque world in, and its water half where
`shadowTranslucent` lets the near water in. That follows from where the mod hangs its own draws.
They fire off the head of the call the shadow stage makes for each chunk group, and under Iris that
call is inside the same two tests, so a pack that keeps the world out of its map keeps the distant
land out with it.

The geometry is not asked for a second time. That mod hands its terrain over once a frame, inside
its own pass, which stands among the game's opaque chunks and is long over by the time the map is
drawn at the tail of the frame. So the sections are kept as they arrive and drawn again from the
light, in the pack's own shadow pair rather than in the volume that mod rasterises its own picture
in.

**What that costs is which sections are in the map**, and it is the one place this engine's map
holds less than Iris's. Iris gets a second list: its shadow pass makes the mod build one, culled
against a frustum of the light's that Iris binds for the length of that pass, and that mod culls
nothing at all for a shadow pass when nobody binds one. Either way its map holds distant land the
camera cannot see. Kept sections are the camera's list by construction, so a hill behind the camera
lays no shadow on the ground in front of it.

### Culling for the light

The map should only contain what the light can see, which means a second visibility walk per frame,
from the light's point of view. That turns out to be the hardest part, and the reasons are specific
enough to be worth recording.

The terrain renderer's visibility pass cannot simply be run twice in the same frame. Three separate
mechanisms block it: the rebuild flag has already been consumed by the camera's walk, so a second
call returns the camera's list unchanged; setting the flag by hand routes to a path that reads an
asynchronous occlusion structure which may hold nothing for that frame, handing the light an empty
world; and forcing the synchronous walk overflows, because each region keeps a single render list
that only resets itself on the *first* walk of a frame: the second walk carries the same frame
number, so the reset does not happen and the light's sections pile on top of the camera's until the
list is full.

That last fact also kills the obvious workaround. Saving and restoring the list container hands
back objects whose *contents* are the light's, because the lists are per-region singletons rather
than values.

The way out is to draw the map at the end of the frame, for the next frame: advance the frame
counter, force the synchronous walk that never consults the asynchronous structure, draw, and mark
the lists for rebuild so the next camera walk starts clean. One extra walk per frame, no save, no
restore.

The price is a one-frame lag on shadows. That is a deliberate divergence from the reference
implementation, and it is the first thing to suspect for any shadow artefact.

Two more things fall out of drawing at the end of the frame. The chain has already closed the
frame, so the shadow programs' preparation must not re-open it: otherwise previous-frame uniform
values advance twice and the colour targets are cleared over what the chain just wrote. And the
map's own clear has to move into the shadow stage's opening, because its contents cross the frame
boundary.

Finally, per-face batch culling has to be disabled for the shadow pass, and the pipeline state is
not enough to do it: batches choose which faces to submit before any pipeline exists, and a face
the camera cannot see is exactly the face standing between the sun and the ground.

### The shape the light measures a section against

The obvious shape is the box the map is drawn in, and it is the wrong one. The map only ever gets
sampled where the camera can see, so a section that cannot drop anything onto anything visible pays
for a draw whose result nothing reads. What replaces it is the camera's own volume swept along the
light: keep the faces of the camera frustum whose inward normal points towards the light, since they
are its far side as the light sees it; drop the faces looking at the light, since a caster in front
of those still casts into view; and close the silhouette with a plane swept along the light for every
edge between a kept face and a dropped one. With the sun overhead that removes the lid of the camera
frustum and keeps its floor, which is exactly right: what stands above you casts down into what you
see, what stands below you does not.

The assumption underneath is worth knowing, because it is where the shape stops being conservative:
the map is taken to be read for direct shadowing and for volumetrics, not for light bouncing off a
caster you cannot see. That is the reference's assumption, and packs are written against it.

**The shape is the pack's to choose**, through `shadow.culling`, and the four states and their words
are described once under [the pack format](pack-format.md). The distance is a separate axis and
composes with all of them: whichever shape is chosen, the box a shadow distance asks for is cut out
of it, so the sweep and the bound never have to know about each other.

Two things about the arithmetic are recorded here because a reader will look for them. The planes are
pulled out of the camera's view projection, and the clip volume that matrix targets decides what
comes out: the reference works against OpenGL, minus one to one, while this engine rasterises with a
reversed Z over zero to one. The matrix handed to the extraction is therefore the **published**
projection, the one already put into the pack's volume once a frame, and not the one the frame was
drawn with; against the published one the reference's six lines are right exactly as they stand, and
the extraction converts nothing. Both ways of doubting that cost something and neither shows on
screen: handed the drawn matrix, those lines lose the far plane outright and keep every section
behind it, while converting a second time leaves the far plane right by accident and pushes the near
plane from `n` out to `2nf / (n + f)`. And the planes come out in camera-relative world space rather
than in the light's clip space, which is also the space the boxes arrive in, so no further conversion
is owed anywhere: the light's own volume never enters the cull at all.

What this buys is read off the log, where the shadow stage prints how many sections the light walked,
how many the camera walked and which shape was used. The debug screen carries only the counts,
`Shadows: C: a/b D: d`, and not the shape. The picture is the place it will NOT show, because keeping
a section too many costs a draw and not a pixel.

All of the above is about the terrain. What moves is culled separately, and there is an open
divergence there worth stating plainly, because it is a gap and not a workaround. A caster is
measured against the light's own frustum, the box the map is drawn in, and where the pack asked for a
shorter reach, that reach is cut as an axis-aligned box about the camera tested on the caster's
position. Iris instead measures the movers against the same swept shape as the terrain, rebuilt at
a shorter distance, and tests the caster's bounding box against it
(`shadows/ShadowRenderer.java:536-541` then `:703`). The two keep-sets are different shapes, so the
difference runs both ways: a caster inside the light's frustum and inside the box but outside that
narrower frustum is kept here and dropped there, and one whose box grazes Iris's bound while its
position sits outside ours is the reverse. Nothing makes Iris's shape impossible here; it has simply
not been written yet, and since the terrain moved to the swept shape the two halves of the walk no
longer measure against the same thing.

### The experiment that separates the two failure modes

If shadows look wrong, one test tells you which half is broken. Clear the map's depth to the
opposite value. If the scene goes entirely dark, the map is being read and the comparison has the
right sense. If nothing changes, it is not being read at all.

More generally: a shadow pass is proved to run by forcing one of the pack's own settings: the
shadow distance, or the sun path rotation, which visibly turns the shadows. Never by eye, and never
with a test shader.

## The sky

### The sky is not one thing

The interception point that works for ordinary geometry does not work here, because the sky
renderer opens its own render passes, with its own pipelines and vertex buffers built once. Clouds
are outside that class again, and the last section says how far outside.

Worse, the sky is not one vertex format. Four formats serve the eight pieces, and between them they
carry position, colour and texture coordinates and nothing else: no normal, no lightmap, no
overlay. So the vertex elements a translated program must declare are the answer of the *pass*, not
of the family. The End's own sky is the piece that makes the point: it is the only one whose mesh
carries a texture coordinate *and* a colour, and it falls to the same program file as the sun and
the moon, whose mesh carries no colour at all. That one file read for the End and then bound to the
sun's format is refused outright; read for the sun and bound to the End's it is not refused at all,
and simply never sees the colour. So it is read once per format it can be drawn against, and never
bound to a format it was not read for.

That is forced by how vertex inputs bind. The match is by name and asymmetric in both directions: a
name the stage declares that the bound format does not carry makes the program refused, while a
format element the stage does not declare shifts every element after it, silently. Exactly the
bound format's elements have to be declared: no more, no less.

There is a subtlety on top: a declared but unread input can be optimised out of the compiled
module, and rebinding only counts survivors, so dropping one shifts locations. Position is safe,
since a vertex stage cannot avoid computing one out of it; a sky program that ignores colour or
texture coordinates would lose them.

The only way to be sure is to compile each sky program once per format it can be drawn against and
read the disassembly back, checking every element is still at the location its position gives it.
**That check exists for the sky as well as for the entity family**, in the out-of-game harness:
each sky program is compiled once per format it can be drawn against and the disassembly read back,
off the game.

### How a pack tells the elements apart

The format splits them by which program they fall to: geometry carrying a texture goes to the
textured sky program, everything untextured to the basic one, and the clouds to a program of their
own that the sky renderer never reaches. Untextured is not
the same as bare: the sunrise band carries a vertex colour and still falls to the basic program,
because the split is on the texture and on nothing else. The engine recognises
each element by the label the game gives its own render pass (an answer the game hands out at
exactly the moment the answer is needed, costing no second table that could drift from the first)
and carries the format, topology and blending of the game's pipeline alongside it.

Beyond that, a uniform tells the shader which stage of rendering is running. That uniform is the
only way a pack can distinguish the sky disc, the dark plane under the world, the sunrise band, the
stars, the sun and the moon, since two files serve all six.

The End's two pieces answer that uniform with the *custom sky* stage, and that is read off the
reference rather than chosen. The reference sets a stage at the head of each overworld method of the
sky renderer and sets none in either End method; what it does set, once, is the custom-sky stage at
the head of the whole sky pass, as a heuristic for sky mods drawing before the game does. The End
branch never reaches a method that replaces it, so custom sky is what a pack reads there, for the
End's own sky and for its flash alike. The flash is worth naming for what it is not: it is a clock,
seeded every six hundred ticks and fading in and out over a stretch drawn at random, not an event
anything in the world sets off.

### The pass matrix is the body's position

This one is easy to get wrong and the symptom is memorable.

The game places the sun, the moon, the stars and the sunrise band by pushing a day rotation onto
its own transform stack and then drawing a straight quad above the camera. **The rotation is the
position.** A pack reads that matrix as the fixed-function model-view and the camera separately,
and uses both at once.

Answer both from the same source and the rotation cancels: the sun sits at noon all night. So the
pass supplies its own model-view, which feeds the fixed-function model-view, its inverse, the
combined transform and the normal matrix, and *not* the camera uniform. The sky disc and the End's
own sky, the two elements the game pushes nothing for, keep the frame's matrix, one per branch.

### Two pipeline facts that break the world if missed

The game's sky pipeline declares **no depth-stencil state at all**. A pack program handed the
ordinary state would write the sky into the depth buffer, and the world would then test against it.

And the sky disc's topology is a triangle fan, not quads. Any program substituted into a pass the
game opened inherits that pass's topology, so the geometry family has to answer it rather than
assume the terrain's.

A related trap: the sky pass is the game's, with no region and no push constants. Borrowing the
terrain's pipeline namespace pushes constants the pass cannot satisfy.

### Where the sky's output goes

Which buffers a sky program writes is keyed by program name, not by an enumeration of passes, since
the game's sky passes share a small set of programs.

Two things leave a piece on the game's own target: a pack serving no program for it at all, in which
case the game's own shader draws it, and a pack serving one the plan has no answer for. Either way
the piece reaches the pack's colour target through the full-screen layer instead of writing it.

**A program that declares no draw buffers is not that case, and used to be.** It is read as writing
colortex0, which is what Iris reads it as, so the pack's shader draws and its output lands in the
pack's own target like any other. Before that, such a program drew onto the attachment the game had
opened its own pass with, where nothing of the chain collected it: a pack whose cloud program says
nothing had no clouds at all, at any setting, and nothing said why.

It is then all eight or none. If any piece the game still draws would stay behind, the whole sky
keeps the game's target, because the layer is the only road left to a piece that stayed on it and the
pieces that claim every pixel they span cut the layer where they land. A pack serving a program for
the basic sky and none for the textured one (which the format allows) would otherwise get a sky
whose disc marks the whole frame and whose sun and moon are cut out of it.

All eight and not the branch in hand, which costs one thing worth naming: a place serving one branch
and not the other holds the served branch back too, though the two are never drawn together. Every
pack of the corpus answers both sky programs in the End, so nothing pays for it there today.

Because the sky is drawn before the world, the sky stage is more often than not what opens the
frame. Not always: `sky=off` in the options stops it, so does a pack that serves no sky program at
all, and so does the Nether, whose skybox is none and which opens no sky pass whatever. In those
cases the terrain opens the frame, as it always did.

**And a boss stops it, which is the one worth knowing before diagnosing anything in the End.** The
game builds no sky pass at all on a frame where a boss bar asks for world fog, and the ender
dragon's is the only bar in the whole game that asks: `GameRenderer.renderLevel` passes the negation
of that question as the argument `LevelRenderer.render` gates the whole pass on. The wither is not
one of them, whatever the screen does while it is up, and a boss bar a datapack raises can ask for
it. Nothing of the pack's sky runs
there, nothing of the game's does either, and no line is printed to say so, because from this
engine's side nothing happened. So the End of a world where the dragon is still alive is exactly
the place where a sky looks broken and is not: kill the dragon, or fly away from its bar, before
reading anything into what the sky does there.

The End is the case to be careful with, and in two ways at once. It draws no **disc**, and the disc
is the piece the horizon cone rides in: there is no band under the End's horizon to close, and
nothing there gates itself on the directive that gates the cone. The disc is also the piece that
cuts the layer in the overworld, so the End needs a piece of its own that cuts it, or its sky is
drawn into the pack's target and then painted over with the game's, which in the End holds nothing
but the frame's clear, the draw having been taken from it. The End's own sky is that piece.

**Which is why the cut is a property of the piece and not of its blending.** The obvious reading,
that a piece which writes outright claims its pixels and a piece that blends does not, is right for
seven of the eight and wrong for the End's sky, whose pipeline blends and whose mesh is nonetheless
opaque at every vertex behind a texture with no transparent texel in it. Read off the blend, the End loses its
sky to a flat fog colour and nothing in the log says so.

### The sun's path is tilted for the bodies as well as for the light

Packs declare a setting that rotates the sun's path, as a constant in their own GLSL, and it works
on both sides at once: the pack computes its own light direction from that constant, and the engine
turns the sun, the moon, the light direction and the shadow matrices by the same angle. Not
everything it serves: where *up* is does not move with the sun's path, and neither does the flash
in the End, which is a fact about a place rather than a property of the light. The game's celestial
bodies know nothing about any of it, so left alone the visible sun and the direction of the shadows
would disagree by exactly that angle, for the sun as much as for the moon.

They do not, because the rotation is pushed onto the celestial pose itself. The place matters and is
not interchangeable with any other: it goes in where the three bodies still share one matrix, after
the game has turned the celestial space and before it turns for the hour, so it tilts the whole
*path* rather than the body of a given moment. Drawing the bodies through a pack program would not
have fixed this on its own: the tilt is not something a shader can put back, since it changes where
the geometry goes.

The shadow matrices carry the same angle, but on their own axis, in the light's space. The two
rotations look alike written down and are not the same operation.

## The horizon gap

This deserves its own section, because the mechanism is unobvious and the wrong diagnosis is very
easy to reach.

### Vanilla leaves a wedge of sky uncovered

The sky disc is built at a fixed height above the camera, the dark disc at the same distance below,
and both have a fixed radius. Those constants put the rim of the sky disc a little under two
degrees above horizontal, and that rim is a *straight line* across the image.

Above sea level it is worse than a band. The dark disc is only drawn when the eye is below the
world's horizon height, so above it nothing whatsoever covers the sky under that rim. What hides the
rest is the terrain's own silhouette, which is why the wedge is only seen where the distant horizon
is clear, and why it is visible well before the top of a mountain.

### What fills the gap is the actual defect

The gap is vanilla's, and vanilla leaves the clear colour in it. The reference implementation does
not: it covers the wedge with geometry of its own, so nothing of the gap is ever seen there.

In an engine where a full-screen layer paints the game's already tone-mapped image into that zone
instead, no later pass reprocesses it. The pack's sky is then cut by a hard straight edge with a
pale band under it. The two images do not differ by colour. They differ by the presence of an edge.

### Only geometry repairs it

This is the part that was learned the expensive way, by fixing two things that were each worth
fixing and neither of which could close it.

Clearing the colour target to the fog colour is correct. Removing the sky from the full-screen
layer is correct. **Neither can repair the horizon**, because both act on pixels that some pass
produced, and this wedge contains no geometry at all. There is no surface for the pack's shader to
run on.

The reference implementation does not fill the zone differently: it *removes* it, by drawing
geometry vanilla does not have: an inverted octagonal cone around the player, between exactly the
two planes vanilla leaves empty. Its own documentation says as much, at the head of the class that
draws it.

Vitrail does the same, in `render/HorizonCone.java`: an octagonal cone between the two planes,
drawn with the pack's basic sky program inside the pass the game opens for its disc, so it inherits
that pass's pipeline state, topology and depth convention.

Two conditions come with it, and neither is optional. The cone rides in the disc's pass, so it is
drawn only where the disc is: a pack that refuses the disc has drawn its own, and the Nether and
the End draw no disc at all. And it is drawn only where the world's opaque geometry marks the
pixels it wrote: the cone stands over the whole of the ground rather than over the sky, and marking
a pixel cuts the full-screen layer there, so a cone drawn while the world still reaches the pack's
target through that layer would cut the ground out of the picture. On the first frame of a world
nobody has answered that question yet, and the frame goes without its cone.

### The method lesson

The comparison against the reference correctly established the *mechanism*: the gap exists on both
sides and is invisible in the reference. The cause deduced from it was wrong.

**An image comparison tells you what differs. It never tells you why.** The why is read in the
reference's source, and in this case it was one search away from the start.

The acceptance criterion follows the same discipline: above the world's horizon height, with a
clear distant horizon, the sky must fade into the fog with no straight edge and no pale band, which
is what the reference produces on the same pack at the same altitude. "The image is plausible" is
not a criterion.

## The clouds

The clouds are grouped here because a pack reaches them through the same family of directives and
the same corner of the format, and they are kept apart from the sky because almost nothing the sky
section says is true of them.

### There is no cloud mesh

`CloudRenderer` never binds a vertex buffer. It fills a texel buffer with three bytes a face (a
cell in x, a cell in z, and a word carrying the facing and four flags), draws six indices a face,
and lets the vertex stage work out which corner of which face it is on from the vertex identifier.
Everything else it needs, the cloud colour, the offset to the cell the camera stands in and the size
of a cell, is one small uniform block beside it.

So there is no format to declare, no element that could go missing from the SPIR-V, and no atlas
going past on the way in. What replaces all of that is a head that reproduces the game's own
geometry in the pack's stage: the twenty four corners in facing order, the six normals, the six
shades, and `gl_Vertex`, `gl_Normal` and `gl_Color` defined in terms of them. That is also what the
reference does, and for the same reason: neither engine can hand a pack a mesh that does not exist.

Two consequences worth stating. **The pipeline has to declare the game's own two names**, spelled
its way, because the pass fills them by name against whatever pipeline is bound; a pipeline that
spelled either differently would be handed neither and the stage would read a buffer nothing filled.
And **the vertex identifier is spelled `gl_VertexID` and not `gl_VertexIndex`**, which is not a
preference: Vulkan has only the second and the game's OpenGL backend only the first, and the game's
own compiler defines the first into the second before handing anything to shaderc. Only the first
works on both sides.

### Fancy and flat differ by a culling

The game keeps two cloud pipelines. The fancy one draws a box a cell and culls back faces; the flat
one draws a single downward face a cell with culling off. So there are two programs over one
translation, and drawing the flat cloud with the fancy one's culling leaves the sky empty seen from
underneath, which is where clouds are looked at.

Which of the two is coming has to be known before the pass exists, since it decides which program
is prepared. It is taken off the argument the renderer was called with, not read back from the
user's settings, because a pack is allowed to overrule those.

### The `clouds` directive points the other way from the rest of its family

`sun`, `moon`, `stars` and `sky` are a pack saying "I draw that myself, do not draw it for me".
`clouds` takes `off`, `fast` or `fancy`, and it overrules the user's setting so that the pack's
cloud program is handed the geometry it was written for. It is applied at the head of the game's own
accessor rather than at the renderer, and that placement is the whole point: the frame graph reads
that accessor to decide whether to add a cloud pass at all, so a pack that switched them off has no
pass opened, no buffer filled and no draw thrown away.

It is honoured only where this engine really draws the clouds. With the game's own shader behind it,
`off` would take the clouds away and put nothing in their place, which is exactly why it went unread
for as long as nothing here drew one.

**Six packs of the eight measured write `clouds=off`**, and none of them means "no clouds": they
draw their own, volumetric, in a composite. Complementary goes further and ships a
`gbuffers_clouds` that discards outright unless its own cloud style is set to the vanilla one. A
pack whose vanilla clouds vanish when this is switched on is usually getting what it asked for, and
the pack to judge the work on is one that asks for nothing.

### Where the clouds land in the frame

They are drawn after the main pass, which settles two things that the sky has to argue about. The
scene seed has already run, so there is nothing left for a coverage mask to keep off them and they
do not write one. And the pack's own colour target already holds the world, which is what a
`gbuffers_clouds` expects to blend onto: the same position the translucent chunk pass is in, and
the same answer.

A pack that declares no draw buffer on the program is read as writing colortex0, as Iris reads it,
so its clouds land in the pack's own target like everything else. That is worth saying because it
was not always so: they used to stay on the game's target, where nothing of the chain collected
them, and a pack whose cloud program declares nothing had no clouds on screen at any setting.
