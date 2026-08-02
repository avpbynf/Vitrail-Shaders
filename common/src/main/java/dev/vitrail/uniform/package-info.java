/**
 * The values a pack reads, and the block they are written into.
 * <p>
 * Nothing in this package may import {@code net.minecraft}, {@code com.mojang.blaze3d} or
 * {@code org.lwjgl}. JOML, the JDK and {@code dev.vitrail.glsl} only. That is not a matter of
 * taste: it is what lets the whole catalogue run in the off-game harness against a scripted
 * fixture, and measure its own coverage over the eight packs in seconds rather than through a
 * game session. The same property carried milestones 3 and 4.
 * <p>
 * Everything that comes from the running game arrives through {@link dev.vitrail.uniform.WorldState},
 * which is implemented against Minecraft in {@code dev.vitrail.render.FrameState} and against a
 * fixture in the harness. A value that needs something the interface does not carry is not fetched
 * from somewhere else: the interface gains an accessor, or the value stays at zero and is named.
 */
package dev.vitrail.uniform;
