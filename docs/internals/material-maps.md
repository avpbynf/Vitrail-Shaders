# The maps a resource pack ships beside its blocks

A shader pack that lights a surface by anything finer than its colour reads two more images per
block: a **normal map**, which tilts the surface inside the face, and a **specular map**, which says
what the surface is made of. Neither comes from the shader pack. They come from the **resource
pack**, as `bricks_n.png` and `bricks_s.png` beside `bricks.png`, and a pack that declares the two
sampler names gets whatever the resource pack happens to ship.

This page is the mechanism. The symptom that sends a reader here (a wall that stays perfectly flat
with a material pack installed) is in [Pack compatibility](../compatibility.md).

## The layout is the whole trick

Each atlas the game stitches gets up to two companion images: the same width, the same height, the
same number of mip levels, and every sprite at the same place inside them. A pack samples all three
with one texture coordinate and never learns that they are three textures.

That is not an optimisation, it is the only shape that works. The coordinates a chunk mesh carries
are atlas coordinates, computed by the stitcher for the albedo; there is no second set. Anything but
an exact copy of the layout would read a different block's material.

The companions are built when an atlas is stitched, which is once per atlas per resource reload:
at startup and again after every resource reload, F3+T included, and never at world load. An atlas
the resource pack ships nothing for costs two lookups per sprite, one per map, and no memory at all:
**no image is decoded and no texture is created**, so an install with no material pack pays nothing.
When maps are found, the log names the atlas and how many of its sprites answered.

## What is read at draw time, and what is not

The two names are bound off **the image the pass is really drawing with**, not off the family of the
pass. That is what lets the block atlas, the item atlas and the particle atlas each answer for
themselves: the terrain reads the block atlas's companions, a particle reads whichever atlas that
layer came off, and neither has to be told which it is.

Only the geometry programs are served, which is what Iris does too. What a composite declaring one
of the names reads is not the same on both sides: here it reads a flat texel, where Iris leaves the
sampler unassigned and it falls to whatever texture unit nought holds.

A sprite the resource pack ships no map for reads the same flat value the whole companion is cleared
to: a normal pointing straight out of the face with nothing occluded, and a material that is nought
in every channel: no smoothness, no reflectance, no porosity, no emission. Those exact values
matter, and they are not a taste: they are the ones Iris falls back on, and each reads as the
absence of the thing it names.

## The mipmap is the one formula that had to be translated

The game's own mip reduction averages a **colour**: it takes the three colour channels through the
sRGB curve, averages in light, and comes back. Applied to a normal map that is simply wrong. Red and
green there are the two components of a vector and blue is a coverage, and none of the three is a
brightness that the eye perceives on a curve. The companions are therefore reduced by a plain
arithmetic average, channel by channel.

The specular map goes one step further, and only where the resource pack declares the labPBR
convention in `optifine/texture.properties`. Three of its channels change **meaning** at a
threshold: a reflectance below 230 and a metal index above it, a porosity below 65 and a subsurface
amount above it, and an emission that is a fraction below 255 and nothing at all at 255. Averaging
across one of those boundaries invents a material that is in neither class: a half-metal, or a
stone that bleeds light. So those channels are averaged only among the texels of the class that wins
the quad. With no declaration, both maps take the plain average.

The same reasoning should decide how the map is **filtered**, and here it only gets half way. A
sampler that blends two texels of it does at draw time exactly what the reduction refuses to do at
load, so the specular map is read with nearest filtering under labPBR. Nearest *inside* a mip level
is all the game's sampler cache can express: the backend picks the mode between levels from the
sampler's maximum lod alone, and the only way to ask for nearest there is to give up mipmapping
altogether, which is worse. So a distant surface, where two levels are blended, crosses the
thresholds after all. A surface close enough to look at does not.

## Two details that are easy to get wrong

**The sprites are padded.** The stitcher gives every sprite a border of replicated edge texels, wide
enough that a mip level cannot blend one sprite into its neighbour. The companions reproduce that
border; left at the flat value, a map would be pulled back towards flat at the edge of every sprite
wherever the sampler reaches past the texel it is centred on. The width of that border is not a
constant and is exposed nowhere, so it is recovered from the sprite's own first texture coordinate.

**A map is scaled to its sprite, not to itself.** A resource pack may draw its maps at a different
resolution from its blocks. The map is resampled to the sprite's size first, by point sampling when
the target is a whole multiple of the source and by a weighted average otherwise.

## The second door: a texture that is no atlas

An entity skin and an armour layer are textures of their own rather than sprites in an atlas, so
none of the mechanism above applies to them: there is no slot to land in, no border to replicate and
no companion to stitch. `creeper_n.png` beside `creeper.png` is read whole and uploaded whole, at
its own resolution, with no mip chain, because the albedo beside it has none either.

Iris keeps exactly this pair of doors, one loader per texture class, and picks between them from the
albedo the draw has bound. Here the pick is made by the answer rather than by the class: the atlas
maps are built against one image and follow it alone, so an image no atlas answers for falls through
to this second door on its own.

**A map is read one frame after it is first wanted.** The want is discovered while a draw is being
recorded, inside a render pass, where a texture cannot be created; so a skin met for the first time
is remembered, answered with the flat value for that frame, and read at the top of the next one.
Iris defers the same read at the same place and for the same reason. What either engine shows is one
frame of a mob without relief, at the moment it first comes on screen.

**What has no map on either side** is an image the game builds rather than reads: the light map, the
overlay, and a player skin that came down over HTTP rather than out of the resource pack. The engine
names each texture that does answer, one line each, as it is read.

A held item is **not** in this second group, and it is worth saying because it looks like it should
be: item textures are sprites in an atlas of their own, so they are served like any other sprite.

## What is not done

This is work not done rather than a limit of the backend, and it is a thing Iris does.

**The maps do not animate.** A sprite whose albedo has frames gets the first frame of its map, held
still, so flowing water keeps a moving surface and a fixed normal. Iris gives its companion sprites
their own animation states and ticks them with the atlas. Nothing in the game's API forbids the same
here: those animation states are public and the game drives its own atlases through them.

