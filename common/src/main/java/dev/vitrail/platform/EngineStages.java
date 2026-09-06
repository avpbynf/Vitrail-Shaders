package dev.vitrail.platform;

import dev.vitrail.cache.ModuleCache;
import dev.vitrail.glsl.TranslationCache;
import dev.vitrail.HostReport;
import dev.vitrail.render.EntityDraw;
import dev.vitrail.render.HandDraw;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.PackChoice;
import dev.vitrail.render.pbr.PbrAtlases;
import dev.vitrail.render.pbr.PbrTextures;
import dev.vitrail.render.RenderScale;
import dev.vitrail.render.ShadowGeometry;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.render.TerrainSampler;
import dev.vitrail.screen.SettingsKey;
import dev.vitrail.sodium.EntityMeshSerializer;
import dev.vitrail.sodium.ShadowTerrain;
import dev.vitrail.Vitrail;

import org.joml.Matrix4fc;
import net.minecraft.world.phys.Vec3;

/**
 * The moments a loader module has to call this engine at, and everything that happens at each one.
 * <p>
 * The other half of {@link VitrailPlatform}: that one is what the engine asks of the loader, this
 * one is what the loader owes the engine. Both loaders reach the same moments by different means,
 * NeoForge through public events and Fabric through mixins on the very lines those events are
 * posted from, so what has to be identical is not the way in but the ordered work behind it. It is
 * written once here rather than once per loader because the order of these lines IS the design:
 * two copies would drift, and a family drawn a stage too early or too late is a picture that looks
 * right and is not.
 * <p>
 * Nothing here is a frame's worth of work in itself. Each method is the short ordered list of what
 * belongs at one point of the frame, and every line of it says why it is at that point rather than
 * the next.
 */
public final class EngineStages {

	private EngineStages() {
	}

	/**
	 * Once, with the game and its device up, and before any world is entered. Not before any frame:
	 * a pack takes seconds to read, and the one thing this moment must not do is hold up the render
	 * thread between the boot's resource reload and the first frame, where the game uploads its
	 * atlases against uniforms only a drawn frame provides. Each loader module says how it gets
	 * there; the Fabric mixin carries the measurement.
	 */
	public static void clientSetup() {
		// The backend rides on the line that was already here rather than taking one of its own. It
		// is the first question any report about a missing picture is answered by, and the game says
		// it far higher up in a log this line is what somebody searches for.
		Vitrail.logger().info("Client setup reached on the {} backend, Sodium is {}",
				HostReport.backend(),
				Vitrail.platform().isModLoaded("sodium") ? "present" : "missing");

		// And what an install decides that this mod cannot, said before the pack is read so that it
		// stands above whatever the pack has to say for itself.
		HostReport.say(Vitrail.platform().gameDirectory());

		// Before the pack and not with it: a serializer is a fact about two vertex formats and knows
		// nothing of a pack, and Sodium keeps it in a cache keyed on the pair. Without it, every mob
		// Sodium draws through its own cuboid writer would reach the mesh with the identifiers left
		// at whatever the arena held. EntityMeshSerializer says the rest.
		EntityMeshSerializer.register();

		// Before the pack, because the first pack read is the one whose translation is worth
		// keeping. The cache knows nothing of a game and takes its directory from here, which is
		// what lets its whole package go on compiling and running without one.
		openTranslationCache();

		// The report of the pack goes with the reading of it, in PackChain, where which pack is
		// being drawn is known.
		PackChoice.load(Vitrail.platform().gameDirectory());
	}

	/**
	 * Puts the translation cache under the game directory, named for the edition whose translations
	 * it holds.
	 * <p>
	 * The edition is the mod's version and the game's, and it names a whole set of keys at once: a
	 * translator that emits one word differently answers differently to every key it ever held, so
	 * a RELEASE takes its predecessor's folder away rather than filling a second one beside it.
	 * Two builds declaring one version share the folder and every key in it, which is every build
	 * made between two releases; what answers that in the workshop is deleting the folder by hand.
	 * The branch a build was made on is cut out of the version first, by
	 * {@link Vitrail#cacheVersion()}, so that a topic build is one of those two builds and not the
	 * owner of a folder of its own.
	 * The loader is not in the name, and does not need to be: the two loaders run the same
	 * translator over the same text, and what does differ between them is in the key of every
	 * entry.
	 */
	private static void openTranslationCache() {
		TranslationCache.install(
				Vitrail.platform().gameDirectory().resolve(Vitrail.MOD_ID),
				Vitrail.cacheVersion() + "+mc" + Vitrail.platform().minecraftVersion());

		if (!TranslationCache.installed()) {
			Vitrail.logger().warn("No translation cache this run, so every pack load translates "
					+ "from scratch: {}", TranslationCache.problem());
		}
	}

	/**
	 * At the end of every client tick, in a world or out of one. The one moment of the engine that
	 * is not a point of the frame, and it is listed here for the same reason the others are: what
	 * is asked once a tick has to be the same list on both loaders, and each of them reaches it
	 * through an event of its own.
	 */
	public static void clientTick() {
		SettingsKey.poll();
		HostReport.sayInWorld();

		// A tick rather than a point of a load, because a load has no single end to hang a line on:
		// the composites compile on the render thread and the leftover families on a pool, over the
		// minute after. The call is silent until the compiler has been quiet for a while, and what
		// it then prints is that load's totals.
		ModuleCache.say();
	}

	/**
	 * While the level's frame graph is being built, which is the one moment the model view and the
	 * camera position of this frame are both to hand and neither has been pushed anywhere yet.
	 * <p>
	 * The frame graph carries no pass of ours, only this reading: the shadow map is drawn
	 * at the end of the frame, for the next one, and this is what the stage needs from here.
	 * <p>
	 * It is also the top of the level frame, which is a second thing entirely and is why the first
	 * line below has nothing to do with the two arguments.
	 */
	public static void frameGraphSetup(Matrix4fc modelView, Vec3 cameraPosition) {
		// First, and before the shadow question below can send this line home: the graph is being
		// BUILT here and not executed, so it is the first point of a level frame where no render pass
		// is open. That is what the material maps of a plain texture need, and it is the reason they
		// cannot be read where they are wanted - inside the bind of a draw already being recorded.
		// Nothing to do on a frame that met no new image, which is every frame but the few where a
		// mob first comes on screen.
		PbrTextures.load();

		if (!TerrainDraw.shadows()) {
			return;
		}

		ShadowTerrain.capture(modelView, cameraPosition);

		// The pack's shadow compute, HERE at the head of the frame and not beside the shadow map
		// that feeds it, and the placement is the parity contract. Complementary's floodfill
		// ping-pongs on frameCounter % 2, writing one half and having this frame's gbuffers read
		// the other. Dispatched at the end of the frame it runs under the WRITER's parity, and
		// every reader is a frame late forever after: the coloured light smears and snaps as the
		// player walks. Run here it shares the frame, and so the parity, of its readers, which is
		// the moment Iris gives it inside its own shadow render. The volumes it propagates are the
		// previous frame's shadow-geometry writes, one frame late like the shadow map itself.
		PackChain.dispatchShadowCompute();
	}

	/**
	 * With the opaque chunk passes done and not one entity drawn yet, which is where the window the
	 * entities are served in opens.
	 */
	public static void afterOpaqueBlocks() {
		// Opens the one window the entities are served in. It has to be a window, because the
		// screen is drawn by the same feature renderers, with the same pipelines and into the same
		// target, out of a submit storage GameRenderer hands them after the level: nothing about one
		// of those draws says it is not an entity, and only the moment does. The hand is not the
		// other one this keeps out: HandDraw submits it inside the level with a mark of its own, so
		// it is served rather than excluded.
		EntityDraw.opaqueFeatures(true);
	}

	/**
	 * Once the opaque terrain, the entities, the block entities and the opaque particles are drawn,
	 * and before anything translucent is. That is where the OptiFine model puts the deferred stage,
	 * and it is where Iris puts it too. No render pass is open there, and the game itself relies on
	 * that: three lines later it copies a depth between two targets, which refuses outright inside
	 * a pass.
	 * <p>
	 * Runs the half of the pack's chain that belongs before the world's translucents: the begins,
	 * the prepares, the scene seed and the deferred stage. Then redirects the game's translucent
	 * features into the layer that hands them to the pack's image.
	 * <p>
	 * The placement itself and not a refinement of it. BSL's {@code gbuffers_water} reads
	 * {@code gaux1}, which its own {@code deferred} writes, and discards every fragment where it
	 * reads nought: with the whole chain running after the world, that read finds a clear colour and
	 * the water is thrown away in its entirety.
	 */
	public static void afterOpaqueFeatures() {
		// First, and before anything of this engine draws: everything after this point is either the
		// world's translucents or, once the level returns, the screen.
		EntityDraw.opaqueFeatures(false);

		// Then the far terrain's depth, taken into the pack's window before its water half is drawn
		// and before the deferred stage below reads it: this boundary is where Iris takes its own
		// copy without the translucent LODs. PackChain.takeDistantDepth says what the frames
		// without a far terrain read instead.
		PackChain.takeDistantDepth();

		// Then the depth the pack reads past the hand with, which has to be taken while the hand is
		// still not in it, and the hand's solid half. The order of these three lines is the whole of
		// where the hand belongs in the frame: after the game's own opaque features, which the window
		// above has just closed, and BEFORE the deferred stage the last line runs. That is exactly
		// where Iris puts it, copyPreHandDepth included: beginHand copies, renderSolid draws, and
		// beginTranslucents then runs the deferreds behind both
		// (mixin/MixinLevelRenderer.java:277-283, pipeline/IrisRenderingPipeline.java:1051-1073).
		// Drawn after the deferreds instead, the hand would write gbuffers nothing would ever read.
		PackChain.markPreHandDepth();
		HandDraw.drawSolid();
		PackChain.drawBeforeTranslucents();
		PackChain.openFeatures();

		// Last, and after the deferred stage above rather than beside it: what this window opens is
		// the half of the entities that blends onto the target that stage has just composed.
		EntityDraw.translucentFeatures(true);
	}

	/**
	 * Once the game has drawn its translucent features, the player's own body among them. They go
	 * into the game's target, which the pack's final overwrites, so without the layer they simply
	 * vanish.
	 */
	public static void afterTranslucentFeatures() {
		// First, and before the layer is composed: closing the window closes any pass a group left
		// open, and composing opens one of its own where the encoder allows only one at a time.
		EntityDraw.translucentFeatures(false);
		PackChain.closeFeatures();
	}

	/**
	 * Once the level renderer is done and before anything else touches the main target, outside of
	 * any render pass the game has open.
	 */
	public static void afterLevel() {
		// The hand's blending half, before the chain and never after it: what it draws has to be in
		// the picture the composites read, and this stage is the last moment it can be. Iris draws it
		// at the same place, one line ahead of its own finalizeLevelRendering
		// (mixin/MixinLevelRenderer.java:170-179).
		HandDraw.drawTranslucent();

		// Nothing is drawn when no pack can be: the game's own image is a better answer than
		// anything this mod could put over it, and the reason is already said, once in the log
		// and again on the settings screen through PackChoice.lastError.
		PackChain.draw();

		// After the chain and not before: the composites above read the map the previous frame
		// drew, and this draws the next frame's over it. The end of the frame is the whole
		// culling design, see ShadowTerrain.
		ShadowTerrain.draw();
	}

	/**
	 * While the level is still standing and the device still alive, which is what makes it the place
	 * to hand back what a pack costs. Leaving a world is the other moment everything a pack costs
	 * may be handed back, and the only one the player reaches twenty times a session.
	 */
	public static void leaveWorld() {
		PackChain.leaveWorld();
	}

	/**
	 * At the end of the session, and while the device is still alive: everything below has to go
	 * back before the renderer is shut down.
	 */
	public static void closeClient() {
		PackChain.close();
		// Its own line and not part of the chain's: what it holds is a second feature renderer and
		// the buffers under it, which belong to the session rather than to a pack, and which have to
		// go back while the device is still alive.
		HandDraw.close();

		// The light's walk holds a third one, for the same reason and with the same lifetime: the
		// map is filled from a submission of its own, so it carries its own dispatcher and its own
		// buffers, and they go back here rather than with any pack.
		ShadowGeometry.close();

		// The render scale's scaled world and its quad belong to the session too: they outlive
		// every pack on purpose, so the end of the session is the one caller that frees them.
		RenderScale.close();

		// The block atlas sampler a pack's terrain binds, one per anisotropy the player asked for.
		// Same lifetime and same reason: it belongs to the device rather than to any pack, and a
		// sampler outliving its device is a handle into freed memory.
		TerrainSampler.release();

		// And the material maps, which belong to the resource pack rather than to any shader pack:
		// nothing in a pack's lifetime touches them, so nothing but the end of the session does. Both
		// doors, the atlases and the plain textures, and neither of them is the other's business.
		PbrAtlases.close();
		PbrTextures.close();
	}
}
