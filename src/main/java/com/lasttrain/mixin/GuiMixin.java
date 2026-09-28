package com.lasttrain.mixin;

import com.lasttrain.client.Cutscene;
import com.lasttrain.client.HudOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An ending cutscene has no HUD at all: no hotbar, crosshair, hearts or chat - only the letterbox bars. */
@Mixin(Gui.class)
public class GuiMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void lasttrain$cutsceneHud(GuiGraphics g, float partialTicks, CallbackInfo ci) {
		if (Cutscene.active()) {
			HudOverlay.renderCutscene(g, g.guiWidth(), g.guiHeight(), partialTicks);
			ci.cancel();
		}
	}
}
