# The maps a resource pack ships beside its blocks

A shader pack that lights a surface by anything finer than its colour reads two more images per
block: a **normal map**, which tilts the surface inside the face, and a **specular map**, which says
what the surface is made of. Neither comes from the shader pack. They come from the **resource
pack**, as `bricks_n.png` and `bricks_s.png` beside `bricks.png`, and a pack that declares the two
sampler names gets whatever the resource pack happens to ship.

This page is the mechanism. The symptom that sends a reader here - a wall that stays perfectly flat
with a material pack installed - is in [Pack compatibility](../compatibility.md).

## The layout is the whole trick

Each atlas the game stitches gets up to two companion images: the same width, the same height, the
same number of mip levels, and every sprite at the same place inside them. A pack samples all three
with one texture coordinate and never learns that they are three textures.

That is not an optimisation, it is the only shape that works. The coordinates a chunk mesh carries
are atlas coordinates, computed by the stitcher for the albedo; there is no second set. Anything but
an exact copy of the layout would read a different block's material.

The companions are built when an atlas is stitched, which is once per atlas per resource reload. An
atlas the resource pack ships nothing for costs one lookup per sprite and no memory at all: **no
image is decoded and no texture is created**, so an install with no material pack pays nothing. When
maps are found, the log names the atlas and how many of its sprites answered.

## What is read at draw time, and what is not

The two names are bound off **the image the pass is really drawing with**, not off the family of the
pass. That is what lets the block atlas, the item atlas, the particle atlas and every entity texture
answer for themselves: the terrain reads the block atlas's companions, a particle reads whichever
atlas that layer came off, and neither has to be told which it is.

Only the geometry programs are served. A composite that declares `normals` reads a flat texel, which
is what it does under Iris too - the two names are added to the world's programs and to nothing else.

A sprite the resource pack ships no map for reads the same flat value the whole companion is cleared
to: a normal pointing straight out of the face with nothing occluded, and a material that is smooth
in nothing, reflects like an ordinary dielectric and emits nothing. Those exact values matter, and
they are not a taste - they are the ones every pack is written to treat as "no data".

## The mipmap is the one formula that had to be translated

The game's own mip reduction averages a **colour**: it takes the three colour channels through the
sRGB curve, averages in light, and comes back. Applied to a normal map that is simply wrong. Red and
green there are the two components of a vector and blue is a coverage, and none of the three is a
brightness that the eye perceives on a curve. The companions are therefore reduced by a plain
arithmetic average, channel by channel.

The specular map goes one step further, and only where the resource pack declares the labPBR
convention in `optifine/texture.properties`. Two of its channels change **meaning** at a threshold:
one is a reflectance below 230 and a metal index above it, the other a porosity below 65 and a
subsurface amount above it. Averaging across that boundary invents a material that is in neither
class - a half-metal, or a stone that bleeds light. So those channels are averaged only among the
texels of the class that wins the quad, and the same reasoning decides how the map is filtered: a
sampler that blends two of its texels would do at draw time exactly what the reduction refuses to do
at load. With no declaration, both maps take the plain average and both are filtered like the atlas.

## Two details that are easy to get wrong

**The sprites are padded.** The stitcher gives every sprite a border of replicated edge texels, wide
enough that a mip level cannot blend one sprite into its neighbour. The companions reproduce that
border; left at the flat value, a map would be pulled back towards flat at the edge of every sprite
wherever the sampler reaches past the texel it is centred on. The width of that border is not a
constant and is exposed nowhere, so it is recovered from the sprite's own first texture coordinate.

**A map is scaled to its sprite, not to itself.** A resource pack may draw its maps at a different
resolution from its blocks. The map is resampled to the sprite's size first, by point sampling when
the target is a whole multiple of the source and by a weighted average otherwise.

## What is not done

**The maps do not animate.** A sprite whose albedo has frames gets the first frame of its map, held
still. Flowing water keeps a moving surface and a fixed normal. Nothing in the game's API forbids the
rest - the animation states exist and are public - so this is a gap that has not been paid for, not
a limit of the backend.

**Textures that are not atlases have no maps.** An entity skin is a texture of its own rather than a
sprite in an atlas, so nothing is built for it and the two names read the flat value on every entity
program.
