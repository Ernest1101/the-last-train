package com.lasttrain.client;

import com.lasttrain.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Black screen with the ending text fading in line by line. */
public class EndingScreen extends Screen {
	private static final int LINE_DELAY = 50;
	private final Component[] lines;
	private int ticks;

	public EndingScreen(int ending) {
		super(Component.translatable("ending.lasttrain.title"));
		String key = ending == ModNetwork.ENDING_ESCAPED ? "escaped" : ending == ModNetwork.ENDING_FREED ? "freed" : "missed";
		this.lines = new Component[]{
				Component.translatable("ending.lasttrain." + key + ".1"),
				Component.translatable("ending.lasttrain." + key + ".2"),
				Component.translatable("ending.lasttrain." + key + ".3"),
				Component.translatable("ending.lasttrain." + key + ".4"),
		};
	}

	@Override
	public void tick() {
		this.ticks++;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
		g.fill(0, 0, this.width, this.height, 0xFF000000);
		float t = this.ticks + partialTicks;
		int y = this.height / 2 - this.lines.length * 9;
		for (int i = 0; i < this.lines.length; i++) {
			float alpha = Mth.clamp((t - 20 - i * LINE_DELAY) / 30.0f, 0.0f, 1.0f);
			if (alpha > 0.02f) {
				int a = (int) (alpha * 255) << 24;
				boolean last = i == this.lines.length - 1;
				g.pose().pushPose();
				if (last) {
					g.pose().translate(this.width / 2.0f, y + i * 18, 0);
					g.pose().scale(2.0f, 2.0f, 1.0f);
					g.drawCenteredString(this.font, this.lines[i], 0, 0, a | 0xC02020);
				} else {
					g.drawCenteredString(this.font, this.lines[i], this.width / 2, y + i * 18, a | 0xDDDDDD);
				}
				g.pose().popPose();
			}
		}
		if (t > 20 + this.lines.length * LINE_DELAY + 40) {
			g.drawCenteredString(this.font, Component.translatable("ending.lasttrain.close"), this.width / 2, this.height - 30, 0x808080);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
