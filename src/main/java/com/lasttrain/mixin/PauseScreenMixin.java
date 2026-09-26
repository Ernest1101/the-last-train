package com.lasttrain.mixin;

import com.lasttrain.client.ExitGuard;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public class PauseScreenMixin {
	@Shadow
	private Button disconnectButton;

	/** While cursed, "Save and Quit" only works on the fifth press. */
	@Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
	private void lasttrain$dontLeave(CallbackInfo ci) {
		if (ExitGuard.intercept((PauseScreen) (Object) this, this.disconnectButton)) {
			ci.cancel();
		}
	}
}
