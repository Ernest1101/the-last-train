package com.lasttrain.client;

import com.lasttrain.registry.ModRegistry;
import com.lasttrain.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/** The low hum that never stops in the nightmare house. Gets louder when the Blind One is close. */
public class DroneSound extends AbstractTickableSoundInstance {
	private static DroneSound playing;

	private DroneSound() {
		super(ModSounds.DRONE, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
		this.looping = true;
		this.delay = 0;
		this.volume = 0.0f;
		this.relative = true;
		this.attenuation = SoundInstance.Attenuation.NONE;
	}

	/** Called every client tick. */
	public static void update(Minecraft mc) {
		boolean wanted = mc.level != null && mc.level.dimension() == ModRegistry.NIGHTMARE;
		if (wanted && (playing == null || playing.isStopped())) {
			playing = new DroneSound();
			mc.getSoundManager().play(playing);
		}
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.level.dimension() != ModRegistry.NIGHTMARE) {
			this.volume -= 0.02f;
			if (this.volume <= 0.0f) {
				this.stop();
			}
			return;
		}
		float target = 0.35f + ClientState.fear * 0.5f;
		this.volume += (target - this.volume) * 0.05f;
	}
}
