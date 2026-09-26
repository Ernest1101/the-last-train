package com.lasttrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.lasttrain.LastTrain;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Bodycam OSD (REC, camera id, timestamp, viewfinder corners) and the breath meter. */
public final class HudOverlay {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss");
	private static final int OSD = 0xE6FFFFFF;

	private HudOverlay() {
	}

	public static void render(GuiGraphics g, float partialTicks) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();

		if (RewindEffect.active() || RewindEffect.tail() > 0) {
			renderRewind(g, font, w, h, mc.player.tickCount);
		} else if (ClientState.bodycamEnabled) {
			boolean blink = (mc.player.tickCount / 15) % 2 == 0;
			if (blink) {
				g.fill(14, 14, 20, 20, 0xFFE02020);
			}
			g.drawString(font, "REC", 24, 13, OSD, false);
			g.drawString(font, "BODYCAM LT-0413", 14, 25, 0xB0FFFFFF, false);
			String stamp = LocalDateTime.now().format(STAMP);
			g.drawString(font, stamp, w - 14 - font.width(stamp), h - 22, OSD, false);
			if (CurseClient.day > 0) {
				Component dayText = Component.translatable(CurseClient.night ? "hud.lasttrain.night" : "hud.lasttrain.day",
						CurseClient.day, CurseClient.totalDays);
				boolean danger = CurseClient.day >= CurseClient.totalDays || CurseClient.night;
				int color = danger ? (blink ? 0xFFE02020 : 0xFF901010) : 0xB0FFFFFF;
				g.drawString(font, dayText, w - 14 - font.width(dayText), 13, color, false);
			} else {
				String battery = "BAT " + batteryPercent(mc) + "%";
				g.drawString(font, battery, w - 14 - font.width(battery), 13, 0xB0FFFFFF, false);
			}

			int c = 0x90FFFFFF;
			int len = 14;
			int m = 8;
			g.fill(m, m, m + len, m + 1, c);
			g.fill(m, m, m + 1, m + len, c);
			g.fill(w - m - len, m, w - m, m + 1, c);
			g.fill(w - m - 1, m, w - m, m + len, c);
			g.fill(m, h - m - 1, m + len, h - m, c);
			g.fill(m, h - m - len, m + 1, h - m, c);
			g.fill(w - m - len, h - m - 1, w - m, h - m, c);
			g.fill(w - m - 1, h - m - len, w - m, h - m, c);
		}

		renderHidden(g, w, h);
		renderLensCrack(g, w, h);
		renderNoSignal(g, font, w, h);
		renderScreamer(g, w, h);
		renderDeath(g, font, w, h, partialTicks);

		if (ClientState.breath < 0.999f || ClientState.holding) {
			int barW = 90;
			int x = (w - barW) / 2;
			int y = h - 62;
			int fillColor = ClientState.exhausted ? 0xFFC03030 : ClientState.holding ? 0xFF9FD4FF : 0xFFE0E0E0;
			Component label = Component.translatable(ClientState.exhausted ? "hud.lasttrain.out_of_breath"
					: ClientState.holding ? "hud.lasttrain.holding_breath" : "hud.lasttrain.breath");
			g.drawCenteredString(font, label, w / 2, y - 11, fillColor);
			g.fill(x - 1, y - 1, x + barW + 1, y + 4, 0xA0000000);
			g.fill(x, y, x + Math.round(barW * ClientState.breath), y + 3, fillColor);
		}
	}

	private static int batteryPercent(Minecraft mc) {
		for (net.minecraft.world.item.ItemStack stack : mc.player.getInventory().items) {
			if (stack.getItem() instanceof com.lasttrain.item.FlashlightItem) {
				return Math.round(com.lasttrain.item.FlashlightItem.charge(stack) * 100);
			}
		}
		if (mc.player.getOffhandItem().getItem() instanceof com.lasttrain.item.FlashlightItem) {
			return Math.round(com.lasttrain.item.FlashlightItem.charge(mc.player.getOffhandItem()) * 100);
		}
		return 37;
	}

	/** Inside a wardrobe: everything black except the gap between the doors. */
	/** Inside a wardrobe you see the real thing (the model is hollow, with louvred doors); just say how to get out. */
	private static void renderHidden(GuiGraphics g, int w, int h) {
		if (!ClientState.hidden) {
			return;
		}
		g.drawCenteredString(Minecraft.getInstance().font, Component.translatable("hud.lasttrain.hiding"), w / 2, h - 30, 0x80FFFFFF);
	}

	private static void renderLensCrack(GuiGraphics g, int w, int h) {
		if (!ClientState.lensCracked || !ClientState.bodycamEnabled) {
			return;
		}
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		g.blit(LastTrain.id("textures/gui/lens_crack.png"), 0, 0, w, h, 0.0f, 0.0f, 256, 256, 256, 256);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
	}

	private static void renderNoSignal(GuiGraphics g, Font font, int w, int h) {
		if (ClientState.noSignalTicks <= 0) {
			return;
		}
		g.fill(0, 0, w, h, 0xFF000000);
		java.util.Random random = new java.util.Random();
		for (int i = 0; i < 400; i++) {
			int x = random.nextInt(w);
			int y = random.nextInt(h);
			int c = 0x40 + random.nextInt(0xA0);
			g.fill(x, y, x + 2, y + 1, 0xFF000000 | (c << 16) | (c << 8) | c);
		}
		g.pose().pushPose();
		g.pose().translate(w / 2.0f, h / 2.0f - 8, 0);
		g.pose().scale(2.0f, 2.0f, 1.0f);
		g.drawCenteredString(font, Component.translatable("hud.lasttrain.no_signal"), 0, 0, 0xFFFFFFFF);
		g.pose().popPose();
	}

	/** "REW" over the rewinding tape, then "PLAY". */
	private static void renderRewind(GuiGraphics g, Font font, int w, int h, int ticks) {
		boolean rewinding = RewindEffect.active();
		String label = rewinding ? "\u25C0\u25C0 REW" : "\u25B6 PLAY";
		if (rewinding || ticks % 10 < 7) {
			g.pose().pushPose();
			g.pose().translate(18, 16, 0);
			g.pose().scale(2.0f, 2.0f, 1.0f);
			g.drawString(font, label, 0, 0, 0xF0FFFFFF, true);
			g.pose().popPose();
		}
		if (rewinding) {
			// the tape counter runs backwards
			int counter = Math.max(0, 5999 - ticks * 37 % 6000);
			String time = String.format("%d:%02d:%02d", counter / 3600, counter / 60 % 60, counter % 60);
			g.drawString(font, time, w - 14 - font.width(time), h - 22, 0xE6FFFFFF, false);
		}
	}

	/** The Double's face: the copied player's skin, with holes where the eyes and mouth should be. */
	private static void renderDoppelFace(GuiGraphics g, int w, int h, int jx, int jy) {
		ResourceLocation skin = com.lasttrain.client.render.DoppelgangerRenderer.skinOf(CurseClient.doppelSkin);
		float grow = 1.0f + (CurseClient.DOPPEL_LENGTH - CurseClient.screamerTicks) * 0.03f;
		int size = (int) (Math.min(w, h) * 0.9f * grow) / 8 * 8;
		int p = size / 8;
		int x = (w - size) / 2 + jx;
		int y = (h - size) / 2 + jy;
		g.fill(0, 0, w, h, 0xFF000000);
		g.blit(skin, x, y, size, size, 8.0f, 8.0f, 8, 8, 64, 64);
		g.blit(skin, x, y, size, size, 40.0f, 8.0f, 8, 8, 64, 64); // hat layer
		g.fill(x + p, y + 4 * p, x + 3 * p, y + 5 * p + p / 2, 0xFF000000);
		g.fill(x + 5 * p, y + 4 * p, x + 7 * p, y + 5 * p + p / 2, 0xFF000000);
		int pupil = Math.max(2, p / 4);
		g.fill(x + 2 * p - pupil, y + 4 * p + p / 2, x + 2 * p + pupil, y + 4 * p + p / 2 + pupil, 0xFFFF2020);
		g.fill(x + 6 * p - pupil, y + 4 * p + p / 2, x + 6 * p + pupil, y + 4 * p + p / 2 + pupil, 0xFFFF2020);
		// the mouth tears open far below the chin
		int stretch = (CurseClient.DOPPEL_LENGTH - CurseClient.screamerTicks) * p / 6;
		g.fill(x + 2 * p + p / 2, y + 6 * p, x + 5 * p + p / 2, y + 8 * p + stretch, 0xFF000000);
		if (CurseClient.screamerTicks % 4 < 2) {
			g.fill(0, 0, w, h, 0x60FF0000);
		}
	}

	private static void renderScreamer(GuiGraphics g, int w, int h) {
		if (CurseClient.screamerTicks <= 0) {
			return;
		}
		if (CurseClient.screamerFace == CurseClient.DOPPEL_FACE) {
			renderDoppelFace(g, w, h, (int) ((Math.random() - 0.5) * 20), (int) ((Math.random() - 0.5) * 20));
			return;
		}
		ResourceLocation face = LastTrain.id("textures/gui/screamer_" + CurseClient.screamerFace + ".png");
		// the face lunges at the camera and shakes
		boolean watcher = CurseClient.screamerFace == CurseClient.WATCHER_FACE;
		int length = watcher ? CurseClient.WATCHER_LENGTH : CurseClient.SCREAMER_LENGTH;
		float grow = 1.0f + (length - CurseClient.screamerTicks) * (watcher ? 0.01f : 0.04f);
		int size = (int) (Math.max(w, h) * grow);
		int jx = (int) ((Math.random() - 0.5) * 24);
		int jy = (int) ((Math.random() - 0.5) * 24);
		g.fill(0, 0, w, h, 0xFF000000);
		g.blit(face, (w - size) / 2 + jx, (h - size) / 2 + jy, size, size, 0.0f, 0.0f, 256, 256, 256, 256);
		if (CurseClient.screamerTicks % 4 < 2) {
			g.fill(0, 0, w, h, 0x50FF0000);
		}
		if (watcher) {
			Font font = Minecraft.getInstance().font;
			g.pose().pushPose();
			g.pose().translate(w / 2.0f + jx / 3.0f, h * 0.8f, 0);
			g.pose().scale(3.0f, 3.0f, 1.0f);
			g.drawCenteredString(font, Component.translatable("hud.lasttrain.soon_the_end"), 0, 0, 0xFFE01010);
			g.pose().popPose();
		}
	}

	private static void renderDeath(GuiGraphics g, Font font, int w, int h, float partialTicks) {
		int t = CurseClient.deathTicks;
		if (t < 0) {
			return;
		}
		if (t < CurseClient.DEATH_BLACKOUT) {
			int alpha = Math.min(160, t * 2);
			g.fill(0, 0, w, h, (alpha << 24) | 0x300000);
			return;
		}
		g.fill(0, 0, w, h, 0xFF000000);
		int since = t - CurseClient.DEATH_BLACKOUT;
		if (since > 20) {
			int a = Math.min(255, (since - 20) * 6);
			g.pose().pushPose();
			g.pose().translate(w / 2.0f, h / 2.0f - 20, 0);
			g.pose().scale(3.0f, 3.0f, 1.0f);
			g.drawCenteredString(font, Component.translatable("hud.lasttrain.you_died"), 0, 0, (a << 24) | 0xC01010);
			g.pose().popPose();
		}
		if (since > 80) {
			int a = Math.min(255, (since - 80) * 4);
			g.drawCenteredString(font, Component.translatable("hud.lasttrain.not_the_end"), w / 2, h / 2 + 20, (a << 24) | 0xAAAAAA);
		}
	}
}
