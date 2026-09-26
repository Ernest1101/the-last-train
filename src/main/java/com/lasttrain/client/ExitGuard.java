package com.lasttrain.client;

import com.lasttrain.mixin.PauseScreenAccessor;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * While the curse is running, leaving isn't that easy: "Save and Quit" has to be pressed five times,
 * each press answers with a whisper, and then one last message fills the screen before the game lets go.
 */
public final class ExitGuard {
	public static final int PRESSES = 5;
	private static int presses;
	private static boolean allowed;

	private ExitGuard() {
	}

	private static boolean cursed() {
		Minecraft mc = Minecraft.getInstance();
		return com.lasttrain.config.LastTrainConfig.get().exitGuardEnabled && mc.level != null
				&& (CurseClient.day > 0 || mc.level.dimension() == ModRegistry.NIGHTMARE);
	}

	/** @return true when the disconnect was swallowed */
	public static boolean intercept(PauseScreen screen, Button button) {
		if (allowed || !cursed()) {
			allowed = false;
			presses = 0;
			return false;
		}
		presses++;
		Minecraft mc = Minecraft.getInstance();
		if (presses < PRESSES) {
			if (button != null) {
				button.active = true;
				button.setMessage(Component.translatable("exit.lasttrain.press" + presses).withStyle(ChatFormatting.DARK_RED));
			}
			if (mc.player != null) {
				mc.player.playSound(presses == PRESSES - 1 ? com.lasttrain.registry.ModSounds.WHISPER : SoundEvents.SOUL_ESCAPE, 1.0f, 0.5f);
			}
			return true;
		}
		presses = 0;
		mc.setScreen(new WarningScreen(screen));
		return true;
	}

	/** The last thing you see before the game lets you go. */
	private static final class WarningScreen extends Screen {
		private static final int LENGTH = 70;
		private final PauseScreen pause;
		private int ticks;

		WarningScreen(PauseScreen pause) {
			super(Component.translatable("exit.lasttrain.final"));
			this.pause = pause;
		}

		@Override
		protected void init() {
			if (this.minecraft != null && this.minecraft.player != null) {
				this.minecraft.player.playSound(com.lasttrain.registry.ModSounds.BLIND_SCREAM, 1.0f, 0.7f);
			}
		}

		@Override
		public void tick() {
			if (++this.ticks >= LENGTH) {
				allowed = true;
				((PauseScreenAccessor) this.pause).lasttrain$disconnect();
			}
		}

		@Override
		public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
			g.fill(0, 0, this.width, this.height, 0xFF000000);
			int jx = this.ticks % 3 == 0 ? (int) ((Math.random() - 0.5) * 6) : 0;
			g.pose().pushPose();
			g.pose().translate(this.width / 2.0f + jx, this.height / 2.0f - 16, 0);
			g.pose().scale(2.5f, 2.5f, 1.0f);
			g.drawCenteredString(this.font, Component.translatable("exit.lasttrain.final"), 0, 0, 0xC01010);
			g.pose().popPose();
			if (this.ticks > 25) {
				g.drawCenteredString(this.font, Component.translatable("exit.lasttrain.final_sub"), this.width / 2, this.height / 2 + 20, 0x888888);
			}
		}

		@Override
		public boolean shouldCloseOnEsc() {
			return false;
		}
	}
}
