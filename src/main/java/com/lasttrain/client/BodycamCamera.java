package com.lasttrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Chest-mounted camera motion: constant handheld drift, heavy footstep sway, jitter when the
 * monster is near and a violent shake when it strikes. Holding your breath steadies the camera.
 * Applied from {@link com.lasttrain.mixin.GameRendererMixin} to both the world and the hand.
 */
public final class BodycamCamera {
	private BodycamCamera() {
	}

	public static void apply(PoseStack poseStack, float partialTicks) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (!ClientState.bodycamEnabled || player == null || Cutscene.active()) {
			return; // the cutscene is shot from a tripod
		}
		float t = (player.tickCount + partialTicks) / 20.0f;
		float steady = ClientState.holding ? 0.35f : 1.0f;

		float yaw = drift(t * 0.9f, 1.3f) * 0.5f * steady;
		float pitch = drift(t * 1.1f, 7.1f) * 0.4f * steady;
		float roll = drift(t * 0.7f, 3.7f) * 0.4f * steady;

		if (ClientState.exhausted) {
			// gasping for air
			pitch += Mth.sin(t * 7.0f) * 1.2f;
			roll += Mth.sin(t * 3.5f) * 0.6f;
		}

		float walk = Mth.lerp(partialTicks, player.walkDistO, player.walkDist) * Mth.PI;
		float bob = Mth.lerp(partialTicks, player.oBob, player.bob);
		roll += Mth.sin(walk) * bob * 3.0f;
		pitch += Math.abs(Mth.cos(walk)) * bob * 3.0f;
		yaw += Mth.sin(walk * 0.5f) * bob * 1.2f;

		float jitter = ClientState.fear * 0.6f + ClientState.scare(partialTicks) * 5.0f;
		if (jitter > 0.0f) {
			yaw += Mth.sin(t * 97.0f) * Mth.sin(t * 13.0f) * jitter;
			pitch += Mth.sin(t * 83.0f + 1.0f) * Mth.sin(t * 17.0f) * jitter;
			roll += Mth.sin(t * 71.0f + 2.0f) * jitter;
		}

		roll += spinRoll + strafeRoll;

		poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
		poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
		poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
	}

	// ---- roll when turning and strafing (same behaviour as spb-revamped's CameraRoll)
	private static final float LOOK_ROLL = 7.0f;
	private static final float STRAFE_ROLL = 7.0f;
	private static float prevYaw = Float.NaN;
	private static float rotAmount;
	private static float spinRoll;
	private static float strafeRoll;

	/** Once per frame, before the world is rendered. The camera leans into turns and sideways steps. */
	public static void updateFrame(float partialTicks) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || !ClientState.bodycamEnabled) {
			prevYaw = Float.NaN;
			spinRoll = strafeRoll = rotAmount = 0.0f;
			return;
		}
		float yaw = player.getViewYRot(partialTicks);
		float delta = mc.getDeltaFrameTime();
		if (Float.isNaN(prevYaw)) {
			prevYaw = yaw;
		}
		rotAmount += yaw - prevYaw;
		spinRoll = frameLerp(spinRoll, rotAmount * 0.1f * LOOK_ROLL, 0.8f, delta);
		rotAmount = frameLerp(rotAmount, 0.0f, 0.5f, delta);
		spinRoll = Mth.clamp(spinRoll, -20.0f, 20.0f);

		// sideways speed relative to where the player looks
		double angle = Math.toRadians(360.0f - player.getYRot());
		var v = player.getDeltaMovement();
		float sideways = (float) (v.x * Math.cos(angle) - v.z * Math.sin(angle));
		strafeRoll = frameLerp(strafeRoll, -sideways * 5.0f * STRAFE_ROLL, 0.8f, delta);
		strafeRoll = Mth.clamp(strafeRoll, -20.0f, 20.0f);
		prevYaw = yaw;
	}

	/** Frame-rate independent lerp: keeps (1 - keep^frames) of the way to the target. */
	private static float frameLerp(float from, float to, float keep, float frames) {
		return Mth.lerp(1.0f - (float) Math.pow(keep, frames), from, to);
	}

	/** Smooth pseudo-noise in roughly [-1, 1]. */
	private static float drift(float t, float seed) {
		return Mth.sin(t + seed) * 0.5f + Mth.sin(t * 2.3f + seed * 1.7f) * 0.3f + Mth.sin(t * 4.7f + seed * 2.9f) * 0.2f;
	}
}
