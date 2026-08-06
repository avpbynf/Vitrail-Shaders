# The sky and the shadow map

These two are grouped because they share a problem: both are drawn by the game through paths that
do not look like ordinary geometry, and both force the engine to answer questions the terrain never
raises.

## The shadow map

### A pack asks for a shadow map by shipping a program

There is no flag. If a pack serves no shadow program, and none is reachable through the fallback
tree, there is no shadow stage at all.

That refusal is not thrift, it is safety. The shadow stage runs at the end of the frame, and with
no program to serve it, the pass the renderer opens is the game's own target - so the stage would
paint the world, seen from the light, over the finished image. For the same reason, when a refusal
arrives in the middle of a session the map is emptied rather than left frozen: a stale map is
served to the pack as if it were current.

### The light view is a rotation about the camera

The shadow frustum is centred on the player, not on the sun. This matters when reading a pack:
distances in a shadow program are relative to the viewer, and the map covers a region that follows
them.

### Distortion belongs to the pack, and the engine must not help

Packs distort the shadow map so that resolution concentrates near the viewer. That distortion is
written entirely in the pack's own GLSL - it scales the depth component in its shadow vertex stage
and undoes it in its lighting include.

The engine's only obligation is to store the depth window the pack expects, term for term. Any
engine-side attempt to correct the distortion fights the pack and produces a wrong result that
looks like a shadow bias problem.

Two consequences worth knowing, and they are not the same one.

The shadow family binds its own catalogue of fixed-function matrices rather than the camera's,
because during the shadow pass the model view and the projection *are* the shadow pair - a pack's
shadow vertex stage says `gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex` without ever naming
a shadow. Handed the camera pair it would draw the map from the player's eye, which is a shadow map
of the wrong thing and looks like a shadow map all the same.

Separately, that stage typically multiplies the inverse shadow matrices by the fixed-function
transform helper and counts on the product collapsing. It only collapses if the inverses and the
pair under the helper are the *same frame's* - so the catalogue answers from the pair being drawn
with, this frame's, while the published shadow matrices are the previous frame's. That is a
consequence of drawing the map a frame ahead, described further down.

### Shadow depth uses the opposite convention from the scene

The scene is drawn in reversed Z - cleared to zero, tested greater-or-equal - because that is what
the game does and it is better conditioned in floating point. The shadow map is stored in the
legacy forward convention, cleared to one and tested less-or-equal.

The reason is that nothing converts it on the way out. The *scene's* depth is turned round once, in
the image, when the copies handed to the pack are taken. The shadow map is copied too - that is how
the pack gets a view without translucents - but it is never converted, so it has to be **stored** in
the convention the pack expects. The two are the same rule, hand the pack the window it reads in,
applied at different places.

### A shadow sampler implies a comparison this backend cannot express

This was the hard wall of the whole feature, and it is worth stating precisely.

In GLSL, a shadow sampler carries a hardware depth comparison: the sampler is configured with a
compare mode, and a read returns the *result of a comparison* rather than a depth. The game's
sampler abstraction has no such mode - it exposes address modes, filters, anisotropy and a maximum
level of detail, and nothing else.

Bound naively, a shadow sampler becomes an ordinary sampler and the comparison means nothing. The
symptom is not a crash: the entire world comes back uniformly in shadow, which looks exactly like a
badly drawn shadow map, and sends you looking in the wrong place.

The engine instead strips the comparison from the declaration and rewrites each *plain* read into a
comparison emitted in the translated shader, in the sense the format specifies and every pack is
written against. It agrees with the forward depth window, where nearer to the light is smaller.

Only the plain lookups. A projective or gathered comparison divides or spreads before it compares,
which needs a different expression and not a different name, so it is left as it stands and counted
instead - a call that silently kept a hardware comparison is exactly what the rewrite exists to
stop.

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
does. Only the bare `shadow` moves, and it moves when a program also declares the water-shadow name
- then that name reads the map with the translucents in it and `shadow` falls back to the one
without. No pack of the corpus writes it at all.

Coloured light through stained glass does not rest on that swap. It rests on the pair plus the
shadow colour buffer: a point occluded in one image and clear in the other has something translucent
between it and the light, and the tint comes from the colour buffer.

### What goes into the map is decided in its own namespace

The directives that describe shadow colour buffers - their format, whether they are cleared, and to
what colour - must be indexed separately from the ordinary colour target directives. Indexed
together, a directive naming shadow colour buffer zero would silently decide the format of colour
target zero.

The clear colour matters even when clearing is switched off, because it is then what the buffer
starts with. Depth always clears, regardless.

One pipeline trap: under dynamic rendering, the shadow pipeline's colour state has to name the
attachment's actual format, and the axis that bites is the **channel count** rather than the bit
depth. A state naming four channels against the single-channel buffer a pack asked for is the
pipeline refused outright, by name and in the middle of the world.

### Culling for the light

The map should only contain what the light can see, which means a second visibility walk per frame,
from the light's point of view. That turns out to be the hardest part, and the reasons are specific
enough to be worth recording.

The terrain renderer's visibility pass cannot simply be run twice in the same frame. Three separate
mechanisms block it: the rebuild flag has already been consumed by the camera's walk, so a second
call returns the camera's list unchanged; setting the flag by hand routes to a path that reads an
asynchronous occlusion structure which may hold nothing for that frame, handing the light an empty
world; and forcing the synchronous walk overflows, because each region keeps a single render list
that only resets itself on the *first* walk of a frame - the second walk carries the same frame
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
frame, so the shadow programs' preparation must not re-open it - otherwise previous-frame uniform
values advance twice and the colour targets are cleared over what the chain just wrote. And the
map's own clear has to move into the shadow stage's opening, because its contents cross the frame
boundary.

Finally, per-face batch culling has to be disabled for the shadow pass, and the pipeline state is
not enough to do it: batches choose which faces to submit before any pipeline exists, and a face
the camera cannot see is exactly the face standing between the sun and the ground.

### The experiment that separates the two failure modes

If shadows look wrong, one test tells you which half is broken. Clear the map's depth to the
opposite value. If the scene goes entirely dark, the map is being read and the comparison has the
right sense. If nothing changes, it is not being read at all.

More generally: a shadow pass is proved to run by forcing one of the pack's own settings - the
shadow distance, or the sun path rotation, which visibly turns the shadows. Never by eye, and never
with a test shader.

## The sky

### The sky is not one thing

The interception point that works for ordinary geometry does not work here, because the sky
renderer opens its own render passes, with its own pipelines and vertex buffers built once. Clouds
are outside that class again.

Worse, the sky is not one vertex format. Three formats serve the six pieces, and between them they
carry position, colour and texture coordinates and nothing else - no normal, no lightmap, no
overlay. So the vertex elements a translated program must declare are the answer of the *pass*, not
of the family.

That is forced by how vertex inputs bind. The match is by name and asymmetric in both directions: a
name the stage declares that the bound format does not carry makes the program refused, while a
format element the stage does not declare shifts every element after it, silently. Exactly the
bound format's elements have to be declared - no more, no less.

There is a subtlety on top: a declared but unread input can be optimised out of the compiled
module, and rebinding only counts survivors, so dropping one shifts locations. Position is safe,
since a vertex stage cannot avoid computing one out of it; a sky program that ignores colour or
texture coordinates would lose them.

The only way to be sure is to compile each sky program once per format it can be drawn against and
read the disassembly back, checking every element is still at the location its position gives it.
**That check exists for the entity family and does not exist yet for the sky.** It is written here
as a debt, not as a guarantee: the sky needs it before it is believed, not after.

### How a pack tells the elements apart

The format splits them by which program they fall to: geometry carrying a texture goes to the
textured sky program, everything untextured to the basic one, clouds to their own. Untextured is not
the same as bare: the sunrise band carries a vertex colour and still falls to the basic program,
because the split is on the texture and on nothing else. The engine recognises
each element by the label the game gives its own render pass - an answer the game hands out at
exactly the moment the answer is needed, costing no second table that could drift from the first -
and carries the format, topology and blending of the game's pipeline alongside it.

Beyond that, a uniform tells the shader which stage of rendering is running. That uniform is the
only way a pack can distinguish the sky disc, the dark plane under the world, the sunrise band, the
stars, the sun and the moon, since two files serve all six.

### The pass matrix is the body's position

This one is easy to get wrong and the symptom is memorable.

The game places the sun, the moon, the stars and the sunrise band by pushing a day rotation onto
its own transform stack and then drawing a straight quad above the camera. **The rotation is the
position.** A pack reads that matrix as the fixed-function model-view and the camera separately,
and uses both at once.

Answer both from the same source and the rotation cancels: the sun sits at noon all night. So the
pass supplies its own model-view, which feeds the fixed-function model-view, its inverse, the
combined transform and the normal matrix - and *not* the camera uniform. The sky disc, the one
element the game pushes nothing for, keeps the frame's matrix.

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

Two different things can leave a piece on the game's own target, and they are worth telling apart.
Where a pack serves no program for a piece, the game's own shader draws it. Where the pack does
serve one but declares no draw buffers on it, **the pack's shader still draws** - it is the target
that stays the game's, the one attachment the game opened its own pass with. Either way that piece
reaches the pack's colour target through the full-screen layer instead of writing it.

It is then all six or none. If any piece the game still draws would stay behind, the whole sky keeps
the game's target, because the layer is the only road left to a piece that stayed on it and the
pieces that write outright cut the layer where they land. A pack declaring draw buffers on the basic
sky program and none on the textured one - which the format allows - would otherwise get a sky whose
disc marks the whole frame and whose sun and moon are cut out of it.

Because the sky is drawn before the world, the sky stage is more often than not what opens the
frame. Not always: `sky=off` in the options stops it, so does a pack that serves no sky program at
all, and so does the Nether, whose skybox is none and which opens no sky pass whatever. In those
cases the terrain opens the frame, as it always did.

The End is the case to be careful with. It opens sky passes of its own - it has its own sky, its own
flash, and a vertex format neither of the others uses - but it draws no **disc**, which is the piece
the pieces above are keyed to and the one the horizon cone rides in.

### The sun's path is tilted for the bodies as well as for the light

Packs declare a setting that rotates the sun's path, as a constant in their own GLSL, and it works
on both sides at once: the pack computes its own light direction from that constant, and the engine
turns the sun, the moon, the light direction and the shadow matrices by the same angle. Not
everything it serves - where *up* is does not move with the sun's path, and neither does the flash
in the End, which is a fact about a place rather than a property of the light. The game's celestial
bodies know nothing about any of it, so left alone the visible sun and the direction of the shadows
would disagree by exactly that angle, for the sun as much as for the moon.

They do not, because the rotation is pushed onto the celestial pose itself. The place matters and is
not interchangeable with any other: it goes in where the three bodies still share one matrix, after
the game has turned the celestial space and before it turns for the hour, so it tilts the whole
*path* rather than the body of a given moment. Drawing the bodies through a pack program would not
have fixed this on its own - the tilt is not something a shader can put back, since it changes where
the geometry goes.

The shadow matrices carry the same angle, but on their own axis, in the light's space. The two
rotations look alike written down and are not the same operation.

## The horizon gap

This deserves its own section, because the mechanism is unobvious and the wrong diagnosis is very
easy to reach.

### Vanilla leaves a wedge of sky uncovered

The sky disc is built at a fixed height above the camera, the dark disc at the same distance below,
and both have a fixed radius. Those constants put the rim of the sky disc a little under two
degrees above horizontal - and that rim is a *straight line* across the image.

Above sea level it is worse than a band. The dark disc is only drawn when the eye is below the
world's horizon height, so above it nothing whatsoever covers the sky under that rim. What hides the
rest is the terrain's own silhouette, which is why the wedge is only seen where the distant horizon
is clear - and why it is visible well before the top of a mountain.

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

The reference implementation does not fill the zone differently - it *removes* it, by drawing
geometry vanilla does not have: an inverted octagonal cone around the player, between exactly the
two planes vanilla leaves empty. Its own documentation says as much, at the head of the class that
draws it.

Vitrail does the same, in `render/HorizonCone.java`: an octagonal cone between the two planes,
drawn with the pack's basic sky program inside the pass the game opens for its disc, so it inherits
that pass's pipeline state, topology and depth convention.

Two conditions come with it, and neither is optional. The cone rides in the disc's pass, so it is
drawn only where the disc is - a pack that refuses the disc has drawn its own, and the Nether and
the End open no such pass at all. And it is drawn only where the world's opaque geometry marks the
pixels it wrote: the cone stands over the whole of the ground rather than over the sky, and marking
a pixel cuts the full-screen layer there, so a cone drawn while the world still reaches the pack's
target through that layer would cut the ground out of the picture. On the first frame of a world
nobody has answered that question yet, and the frame goes without its cone.

### The method lesson

The comparison against the reference correctly established the *mechanism* - the gap exists on both
sides and is invisible in the reference. The cause deduced from it was wrong.

**An image comparison tells you what differs. It never tells you why.** The why is read in the
reference's source, and in this case it was one search away from the start.

The acceptance criterion follows the same discipline: above the world's horizon height, with a
clear distant horizon, the sky must fade into the fog with no straight edge and no pale band, which
is what the reference produces on the same pack at the same altitude. "The image is plausible" is
not a criterion.
