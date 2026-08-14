package dev.vitrail.platform;

/**
 * The two methods of the game that NeoForge patches into a pair, named once so that a mixin can ask
 * for whichever of the two is in front of it.
 * <p>
 * NeoForge does not change either method, it adds an overload beside it with one argument more and
 * moves the body across; the original keeps its name and its shape and becomes a bridge that calls
 * the new one. The game itself is patched to call the wide one, so on NeoForge the narrow one is
 * live for nothing but a mod that still calls it, and on Fabric the wide one does not exist at all.
 * <p>
 * A mixin therefore names both and requires one, which is what Iris does for the same difference
 * ({@code MojLambdas} beside {@code NeoLambdas}). Naming both is also right rather than merely
 * convenient: on NeoForge the bridge binds too, and everything either of these two mixins does at
 * the head of one of these methods is exactly as right at the head of the other.
 * <p>
 * <strong>Both descriptors were read off the two game jars rather than written from the sources</strong>,
 * because a wrong one here does not fail to compile: it fails to bind, at which point Mixin refuses
 * the whole mod at startup, or worse binds only on the loader it was written on.
 */
public final class PatchedMethods {

	/** {@code LevelRenderer.addSkyPass}, as the bare game declares it. */
	public static final String SKY_PASS =
			"addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
					+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
					+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";

	/** The same, with the model view NeoForge hands it. */
	public static final String SKY_PASS_WIDENED =
			"addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
					+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
					+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
					+ "Lorg/joml/Matrix4fc;)V";

	/** {@code WeatherEffectRenderer.render}, as the bare game declares it. */
	public static final String WEATHER_RENDER =
			"render(Lnet/minecraft/world/phys/Vec3;"
					+ "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V";

	/** The same, with the level render state NeoForge hands it so that a mod can replace the curtain. */
	public static final String WEATHER_RENDER_WIDENED =
			"render(Lnet/minecraft/world/phys/Vec3;"
					+ "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
					+ "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V";

	private PatchedMethods() {
	}
}
