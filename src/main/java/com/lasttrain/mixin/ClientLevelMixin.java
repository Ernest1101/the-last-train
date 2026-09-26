package com.lasttrain.mixin;

import com.lasttrain.client.RedMoon;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	@Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
	private void lasttrain$bloodSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
		if (RedMoon.active()) {
			Vec3 sky = cir.getReturnValue();
			cir.setReturnValue(new Vec3(Math.max(sky.x, 0.2), sky.y * 0.3, sky.z * 0.3));
		}
	}
}
