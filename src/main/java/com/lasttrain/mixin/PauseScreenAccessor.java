package com.lasttrain.mixin;

import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PauseScreen.class)
public interface PauseScreenAccessor {
	@Invoker("onDisconnect")
	void lasttrain$disconnect();
}
