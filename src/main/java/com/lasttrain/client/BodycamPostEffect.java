package com.lasttrain.client;

import com.lasttrain.LastTrain;
import foundry.veil.api.client.render.CameraMatrices;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.post.PostProcessingManager;
import foundry.veil.fabric.event.FabricVeilPostProcessingEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Drives the Veil post pipeline {@code lasttrain:bodycam}
 * (assets/lasttrain/pinwheel/post/bodycam.json): fisheye, chromatic aberration, grain,
 * vignette and glitches that get worse with fear.
 */
public final class BodycamPostEffect {
	public static final ResourceLocation PIPELINE = LastTrain.id("bodycam");
	private static int retryCooldown;

	private BodycamPostEffect() {
	}

	public static void init() {
		FabricVeilPostProcessingEvent.PRE.register((name, pipeline, context) -> {
			if (!PIPELINE.equals(name)) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			float partialTicks = mc.getFrameTime();
			float time = mc.player != null ? (mc.player.tickCount + partialTicks) / 20.0f : 0.0f;
			pipeline.setFloat("Time", time % 3600.0f);
			pipeline.setFloat("Fear", ClientState.fear);
			pipeline.setFloat("Breath", ClientState.holding ? 1.0f - ClientState.breath * 0.5f : 0.0f);
			pipeline.setFloat("Scare", ClientState.scare(partialTicks));
			pipeline.setFloat("Flashlight", ClientState.flashlight);
			pipeline.setFloat("Rewind", RewindEffect.intensity(partialTicks));
			updateCameraUniforms(pipeline, mc, partialTicks);
		});
	}

	/** spb-revamped defaults. */
	private static final float MOTION_BLUR_STRENGTH = 0.5f;
	private static final float DISTORTION_STRENGTH = 1.0f;
	private static final Matrix4f PREV_VIEW = new Matrix4f();
	private static final Matrix4f PREV_PROJ = new Matrix4f();
	private static final Vector3f PREV_CAMERA = new Vector3f();
	private static boolean hasPrevious;

	/**
	 * Last frame's camera for the per-pixel motion blur (reprojection by depth), plus the game time
	 * the VHS noise scrolls with.
	 */
	private static void updateCameraUniforms(PostPipeline pipeline, Minecraft mc, float partialTicks) {
		CameraMatrices camera = VeilRenderSystem.renderer().getCameraMatrices();
		if (!hasPrevious) {
			PREV_VIEW.set(camera.getViewMatrix());
			PREV_PROJ.set(camera.getProjectionMatrix());
			PREV_CAMERA.set(camera.getCameraPosition());
			hasPrevious = true;
		}
		pipeline.setMatrix("prevViewMat", PREV_VIEW);
		pipeline.setMatrix("prevProjMat", PREV_PROJ);
		pipeline.setVector("prevCameraPos", PREV_CAMERA.x, PREV_CAMERA.y, PREV_CAMERA.z);
		pipeline.setFloat("MotionBlurStrength", MOTION_BLUR_STRENGTH);
		pipeline.setFloat("DistortionStrength", DISTORTION_STRENGTH);
		long gameTime = mc.level != null ? mc.level.getGameTime() : 0L;
		pipeline.setFloat("GameTime", ((gameTime % 24000L) + partialTicks) / 24000.0f);

		PREV_VIEW.set(camera.getViewMatrix());
		PREV_PROJ.set(camera.getProjectionMatrix());
		PREV_CAMERA.set(camera.getCameraPosition());
	}

	/** Keeps the pipeline active while in a world and the effect is enabled. */
	public static void tick(Minecraft mc) {
		PostProcessingManager manager;
		try {
			manager = VeilRenderSystem.renderer().getPostProcessingManager();
		} catch (RuntimeException e) {
			return; // Veil isn't ready yet
		}
		boolean want = ClientState.bodycamEnabled && mc.level != null;
		boolean active = manager.isActive(PIPELINE);
		if (want && !active) {
			if (--retryCooldown <= 0) {
				retryCooldown = 100;
				if (!manager.add(PIPELINE)) {
					LastTrain.LOGGER.warn("Could not enable post pipeline {}", PIPELINE);
				}
			}
		} else if (!want && active) {
			manager.remove(PIPELINE);
		}
	}
}
