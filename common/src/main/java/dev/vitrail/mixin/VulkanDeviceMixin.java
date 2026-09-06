package dev.vitrail.mixin;

import dev.vitrail.mixin.access.RenderPipelineAccessor;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.StalePipelines;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Lets the game's compiled entity pipelines leave the cache without dying, which is what
 * {@code EntityMesh.settle} needs when its answer moves in a running world: the pipelines standing
 * in {@code pipelineCache} were compiled against the bindings of the OTHER answer, their stride is
 * baked ({@code VulkanRenderPipeline:99}), and every draw they serve reads the mesh at the wrong
 * offsets from then on. Giant triangles off the hand and every mob, for as long as the session
 * lasts, is what that looks like.
 * <p>
 * <strong>Dropped from the map, never destroyed here.</strong> Destruction is the half of issue
 * 111 that lives in this engine: the cache's own emptying waits the device idle and there is no
 * instant of a running session where that is safe. What leaves the map cannot be looked up again,
 * so no NEW draw binds it; whatever an already-recorded frame still names stays alive in
 * {@code vitrail$setAside} until {@code clearPipelineCache}, which the game only reaches after
 * quiescing rendering, frees the whole cache anyway, and now frees these with it.
 * <p>
 * <strong>And the live pack's own pipelines are carried over that emptying.</strong> A resource
 * reload, F3+T included, empties the whole cache, and a pack's programs come from a shader archive
 * no resource reload touches: their pipelines were destroyed and compiled again from nothing, with
 * the world held back while it happened. Iris never meets the question, its pack programs never
 * entering this map at all. They are taken out at the head of the purge and put back at its tail,
 * so the emptying never sees them.
 * <p>
 * <strong>What this does NOT keep is what nothing should keep.</strong> Every load numbers its own
 * programs, so the pipelines of a load that has been replaced fail the test, stay in the map and are
 * freed here exactly as before, which is the only thing that ever frees them. A chain the engine
 * stopped drawing counts as replaced, and {@link PackChain#liveLoad} is the narrower question that
 * says so. And the purge inside {@code close} carries nothing whatever: the device is destroyed the
 * moment it returns, so a pipeline that left the map there would outlive its own device.
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin implements StalePipelines {

	/**
	 * Whether the live pack's pipelines cross a resource reload rather than being compiled again
	 * behind a held-back world. Turned off by {@code -Dvitrail.keepPackAcrossReload=false} rather
	 * than by a rebuild, so both roads come out of one jar, and the line at every purge names the
	 * one taken and counts what it did either way.
	 */
	@Unique
	private static final boolean VITRAIL$KEEP_ACROSS_RELOAD = Boolean.parseBoolean(
			System.getProperty("vitrail.keepPackAcrossReload", "true"));

	@Shadow
	@Final
	private Map<RenderPipeline, VulkanRenderPipeline> pipelineCache;

	/**
	 * Compiled pipelines dropped from the cache and owed a destroy, which only the safe purge pays.
	 * A list and not a set: one pipeline cannot be dropped twice, leaving the map with the drop as
	 * it does.
	 * <p>
	 * It grows one set per answer move and empties only at the next resource reload, and that
	 * bound is accepted rather than missed: the same move that adds a set also rebuilds the whole
	 * world mesh and compiles or drops a pack's entire chain, so what waits here is a sliver of
	 * what the move already paid, and the only earlier instant to free it is the unsafe one this
	 * class exists to avoid.
	 */
	@Unique
	private final List<VulkanRenderPipeline> vitrail$setAside = new ArrayList<>();

	/**
	 * The live pack's compiled pipelines while the purge runs, and empty at every other instant:
	 * they leave the map before the emptying reaches it and are put back the moment it is done.
	 */
	@Unique
	private final Map<RenderPipeline, VulkanRenderPipeline> vitrail$carriedOver =
			new LinkedHashMap<>();

	/** Whether the device is being destroyed, which is what makes its last purge final. */
	@Unique
	private boolean vitrail$closing;

	@Override
	public List<RenderPipeline> vitrail$dropEntityPipelines() {
		List<RenderPipeline> dropped = new ArrayList<>();
		Iterator<Map.Entry<RenderPipeline, VulkanRenderPipeline>> held =
				this.pipelineCache.entrySet().iterator();
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, VulkanRenderPipeline> entry = held.next();
			if (!declaresGameEntity(entry.getKey())) {
				continue;
			}

			this.vitrail$setAside.add(entry.getValue());
			dropped.add(entry.getKey());
			held.remove();
		}

		return dropped;
	}

	/**
	 * Whether this is one of the game's entity pipelines, read off the DECLARED formats and not the
	 * getter: {@code RenderPipelineMixin} rewrites the getter while the mesh carries, which would
	 * make a pack's own pipelines answer yes here. Those follow their chain, not this cache walk.
	 * By identity, the same question {@code EntityMesh.binding} asks.
	 */
	@Unique
	private static boolean declaresGameEntity(RenderPipeline pipeline) {
		@Nullable VertexFormat[] declared = ((RenderPipelineAccessor) pipeline).vitrail$declaredFormats();
		for (VertexFormat format : declared) {
			@SuppressWarnings("ReferenceEquality")
			boolean entity = format == DefaultVertexFormat.ENTITY;
			if (entity) {
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean vitrail$adopt(RenderPipeline pipeline, VulkanRenderPipeline compiled) {
		return this.pipelineCache.putIfAbsent(pipeline, compiled) == null;
	}

	/**
	 * Takes the live pack's pipelines out of the map before the emptying walks it, and counts them
	 * whether or not it takes them: a line that appeared on one road only would leave every reading
	 * taken on the other unable to say what the purge really carried.
	 * <p>
	 * Nothing is carried over the LAST purge of the session, the one {@code close} runs before it
	 * destroys the device: what leaves the map there is never looked up again, and a pipeline still
	 * alive at {@code vkDestroyDevice} is a child outliving its parent.
	 */
	@Inject(method = "clearPipelineCache", at = @At("HEAD"), require = 1)
	private void vitrail$carryTheLivePackOver(CallbackInfo callback) {
		// Anything a purge that threw before its tail left standing is DESTROYED with this purge
		// rather than put back, and the difference is the whole of it. Those keys left the map and
		// were never returned, so the chain missed on them the very next frame, compiled fresh ones
		// and filled the map with those; putting the old values back would overwrite live entries
		// with dead ones, leak the fresh pipelines the chain is still binding, and leave the two
		// disagreeing for the rest of the session. They join the set aside instead, which the tail
		// frees after the wait this purge already pays for.
		this.vitrail$setAside.addAll(this.vitrail$carriedOver.values());
		this.vitrail$carriedOver.clear();

		boolean carry = VITRAIL$KEEP_ACROSS_RELOAD && !this.vitrail$closing;
		int load = PackChain.liveLoad();
		String live = load == 0 ? null : "pipeline/pack/" + load + "/";
		int ofThePack = 0;
		int carried = 0;
		Iterator<Map.Entry<RenderPipeline, VulkanRenderPipeline>> held =
				this.pipelineCache.entrySet().iterator();
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, VulkanRenderPipeline> entry = held.next();
			// Counted for every load of the pack and not only the live one, or a session whose pack
			// was stopped would read "0 pipelines of the pack" off a purge destroying its whole set.
			if (!ofAPack(entry.getKey())) {
				continue;
			}

			ofThePack++;
			if (!carry || live == null || !entry.getKey().getLocation().getPath().startsWith(live)) {
				continue;
			}

			carried++;
			this.vitrail$carriedOver.put(entry.getKey(), entry.getValue());
			held.remove();
		}

		Vitrail.logger().info("Device purge: {} pipelines of the pack in the cache, {} carried over "
				+ "it{}, property=vitrail.keepPackAcrossReload", ofThePack, carried,
				this.vitrail$closing ? ", the device is closing" : "");
	}

	/**
	 * Latched at the head of {@code close}, which is the one purge nothing may survive: the device
	 * is destroyed the moment it returns.
	 */
	@Inject(method = "close", at = @At("HEAD"), require = 1)
	private void vitrail$deviceClosing(CallbackInfo callback) {
		this.vitrail$closing = true;
	}

	/**
	 * Whether this pipeline was built for a pack, whichever load it came from, read off the name it
	 * was built under: {@code PackPass} and {@code GeometryProgram} both number their stem with the
	 * load, so the name answers whose the pipeline is, and the load in it answers which reading made
	 * it.
	 * <p>
	 * It answers no for the engine's own, the depth window, the scene seed, the feature layer, the
	 * render scale and the far terrain's occlusion among them: none carries a load, all of them die
	 * at every purge as they always did, and each asks for its compile again on the next frame that
	 * wants it.
	 */
	@Unique
	private static boolean ofAPack(RenderPipeline pipeline) {
		Identifier location = pipeline.getLocation();
		String namespace = location.getNamespace();

		return (namespace.equals(Vitrail.MOD_ID) || namespace.startsWith(Vitrail.MOD_ID + "_"))
				&& location.getPath().startsWith("pipeline/pack/");
	}

	@Inject(method = "clearPipelineCache", at = @At("TAIL"), require = 1)
	private void vitrail$closeThePurge(CallbackInfo callback) {
		this.vitrail$setAside.forEach(VulkanRenderPipeline::destroy);
		this.vitrail$setAside.clear();
		// Put back into a map the emptying has just cleared, so nothing here can land on a key the
		// purge left standing.
		this.pipelineCache.putAll(this.vitrail$carriedOver);
		this.vitrail$carriedOver.clear();
	}
}
