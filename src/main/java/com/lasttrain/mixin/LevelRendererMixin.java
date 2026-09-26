package com.lasttrain.mixin;

import com.lasttrain.client.RedMoon;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	/** The second texture bound in renderSky is the moon (the first is the sun). */
	@ModifyArg(method = "renderSky", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V",
			ordinal = 1), index = 1)
	private ResourceLocation lasttrain$redMoon(ResourceLocation texture) {
		return RedMoon.active() ? RedMoon.TEXTURE : texture;
	}
}
