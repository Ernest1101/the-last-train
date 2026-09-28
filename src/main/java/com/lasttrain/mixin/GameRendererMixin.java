package com.lasttrain.mixin;

import com.lasttrain.client.BodycamCamera;
import com.lasttrain.client.RewindEffect;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void lasttrain$bodycamShake(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
		BodycamCamera.apply(poseStack, partialTicks);
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void lasttrain$bodycamFrame(float partialTicks, long finishNanos, PoseStack poseStack, CallbackInfo ci) {
		BodycamCamera.updateFrame(partialTicks);
	}

	/** No hands in the rewound footage: the camera isn't where the player is. */
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void lasttrain$noHandsWhileRewinding(PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
		if (RewindEffect.active() || com.lasttrain.client.Cutscene.active()) {
			ci.cancel();
		}
	}
}
