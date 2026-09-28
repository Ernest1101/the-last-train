package com.lasttrain.mixin;

import com.lasttrain.client.Cutscene;
import com.lasttrain.client.RewindEffect;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During the tape rewind the camera leaves the player and flies back along the recorded path;
 * during an ending cutscene it cuts between fixed shots (and the player's own body becomes visible).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	private boolean detached;

	@Inject(method = "setup", at = @At("TAIL"))
	private void lasttrain$rewind(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		Cutscene.Frame shot = Cutscene.cameraFrame(partialTick);
		if (shot != null) {
			this.detached = true;
			this.setRotation(shot.yRot(), shot.xRot());
			this.setPosition(shot.eye());
			return;
		}
		RewindEffect.Frame frame = RewindEffect.cameraFrame(partialTick);
		if (frame != null) {
			this.setRotation(frame.yRot(), frame.xRot());
			this.setPosition(frame.eye());
		}
	}
}
