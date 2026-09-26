package com.lasttrain.client;

import com.lasttrain.LastTrain;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** From the second night of the curse (and always in the nightmare) the moon bleeds and the sky turns red. */
public final class RedMoon {
	public static final ResourceLocation TEXTURE = LastTrain.id("textures/environment/red_moon.png");

	private RedMoon() {
	}

	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return false;
		}
		if (mc.level.dimension() == ModRegistry.NIGHTMARE) {
			return true;
		}
		return CurseClient.day >= 2 && CurseClient.night;
	}
}
