package com.lasttrain.mixin;

import com.lasttrain.LastTrain;
import com.lasttrain.client.CurseClient;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VillagerRenderer.class)
public class VillagerRendererMixin {
	private static final ResourceLocation LASTTRAIN$FACELESS = LastTrain.id("textures/entity/faceless_villager.png");

	/** From day two of the curse the villagers have no faces. */
	@Inject(method = "getTextureLocation(Lnet/minecraft/world/entity/npc/Villager;)Lnet/minecraft/resources/ResourceLocation;",
			at = @At("RETURN"), cancellable = true)
	private void lasttrain$faceless(Villager villager, CallbackInfoReturnable<ResourceLocation> cir) {
		if (CurseClient.day >= 2) {
			cir.setReturnValue(LASTTRAIN$FACELESS);
		}
	}
}
