package dev.vitrail.render;

/**
 * Whether two attachments of one pass may carry two blend functions, which is what a pack's
 * {@code blend.<program>.<buffer>} directive asks for and what it declares as
 * {@code PER_BUFFER_BLENDING}.
 * <p>
 * Vulkan writes one {@code VkPipelineColorBlendAttachmentState} per attachment and the game's
 * backend fills each of them from that slot's own {@code ColorTargetState}
 * ({@code VulkanRenderPipeline:178-191}), so the shape is already there. What is not is the
 * permission, and it comes in two halves.
 * <p>
 * The device's half. Without the {@code independentBlend} feature every element of that array has
 * to be identical, and the game asks for the feature nowhere. {@code VulkanBackendMixin} asks for
 * it, and answers here whether the device gave it.
 * <p>
 * The game's half. Its pipeline builder refuses two colour targets naming different blend functions
 * outright ({@code RenderPipeline:457-468}), before any backend is reached, which it has to while
 * the OpenGL road sets one function for the whole draw. {@code RenderPipelineBuilderMixin} lifts
 * that refusal on this answer and on the build being one of ours, both at once: the builder it
 * stands in is the one every pipeline of the process is built through, so the answer by itself
 * would carry the lift into the game's own pipelines and every other mod's. {@link #building} is
 * the second condition, raised on the thread for the length of one of our builds. Where the device
 * said no the builder still refuses, ours included, and the pass is given one function for every
 * attachment instead.
 * <p>
 * Iris withholds the same flag where its own API cannot part the attachments either, the driver
 * needing {@code ARB_draw_buffers_blend} or OpenGL 4.0 ({@code features/FeatureFlags.java:15} into
 * {@code gl/IrisRenderSystem.java:334-336}). Same rule, different question: a Vulkan device feature
 * and a GL extension are asked of two different APIs, and a machine can be told yes by one and no
 * by the other, so a pack refused here is not for that reason refused there.
 * <p>
 * Off until the device says otherwise, which is what the harness and any other caller with no
 * device to ask gets: a pack is then refused as it was before the feature was asked for, rather
 * than drawn with a promise nothing keeps.
 */
public final class BufferBlending {

	/**
	 * Whether the pipeline this thread is building is one of ours.
	 * <p>
	 * On the thread and not a field of the class, because our builds are not all on one: the
	 * pack-load worker builds the six families ahead of their first draw
	 * ({@code FamilyWarmup.start}) while the render thread builds whatever a first draw asks for
	 * and whatever another mod's mesh makes {@code GeometryProgram} reshape. A flag raised on the
	 * worker would lift the game's refusal under whatever the render thread happened to be building
	 * at that moment, which is the opposite of narrowing it.
	 */
	private static final ThreadLocal<Boolean> BUILDING = new ThreadLocal<>();

	private static volatile boolean served;

	private BufferBlending() {
	}

	/**
	 * Read where a pipeline's states are built, where a pack is refused, and where the symbol is
	 * posed.
	 */
	public static boolean served() {
		return served;
	}

	/** Set once by {@code VulkanBackendMixin}, at device creation and before any pack is read. */
	public static void serve(boolean independentBlend) {
		served = independentBlend;
	}

	/**
	 * Marks this thread as inside one of our own pipeline builds, and unmarks it. Paired in a
	 * finally by {@code GeometryProgram.part}, which is the only caller: an unmarked thread is what
	 * every builder this engine is not standing in has to look like.
	 */
	static void building(boolean building) {
		if (building) {
			BUILDING.set(Boolean.TRUE);
		} else {
			BUILDING.remove();
		}
	}

	/**
	 * The whole of what {@code RenderPipelineBuilderMixin} lifts the game's refusal on: a device
	 * that granted {@code independentBlend}, and a build of ours running on this thread.
	 */
	public static boolean parting() {
		return served && Boolean.TRUE.equals(BUILDING.get());
	}
}
