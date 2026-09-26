package com.lasttrain.client;

import com.lasttrain.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * After a death in the house the bodycam "rewinds the tape": once you respawn, the camera flies back
 * along the last seconds before the death, the way a VHS tape rewinds, then cuts to the bedroom.
 * The camera path is recorded here every tick; the server only says when a death counts.
 */
public final class RewindEffect {
	/** Frames of the camera path kept (6 seconds). */
	private static final int HISTORY = 20 * 6;
	/** How long the playback takes. */
	public static final int LENGTH = 60;
	/** "PLAY" and the jolt of the tape stopping after the rewind. */
	public static final int TAIL = 16;

	public record Frame(Vec3 eye, float yRot, float xRot) {
	}

	private static final ArrayDeque<Frame> HISTORY_FRAMES = new ArrayDeque<>();
	private static List<Frame> tape = List.of();
	/** The server said this death counts; waiting for the respawn. */
	private static boolean pending;
	/** Playback tick, -1 when not rewinding. */
	private static int ticks = -1;
	private static int tail;

	private RewindEffect() {
	}

	/** The player just died in the house: freeze what the camera saw. */
	public static void onDeath() {
		if (HISTORY_FRAMES.size() < 10) {
			return;
		}
		tape = new ArrayList<>(HISTORY_FRAMES);
		HISTORY_FRAMES.clear();
		pending = true;
	}

	public static boolean active() {
		return ticks >= 0;
	}

	public static int tail() {
		return tail;
	}

	public static void reset() {
		HISTORY_FRAMES.clear();
		tape = List.of();
		pending = false;
		ticks = -1;
		tail = 0;
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			reset();
			return;
		}
		if (tail > 0) {
			tail--;
		}
		if (ticks >= 0) {
			if (++ticks > LENGTH) {
				ticks = -1;
				tail = TAIL;
				tape = List.of();
			}
			return;
		}
		if (pending) {
			// the death screen is gone and we are back in the bedroom: roll the tape
			if (player.isAlive() && mc.screen == null) {
				pending = false;
				ticks = 0;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.REWIND, 1.0f, 0.9f));
			}
			return;
		}
		if (player.isAlive()) {
			HISTORY_FRAMES.addLast(new Frame(player.getEyePosition(), player.getYRot(), player.getXRot()));
			while (HISTORY_FRAMES.size() > HISTORY) {
				HISTORY_FRAMES.removeFirst();
			}
		}
	}

	/** 0..1 for the shader: full while rewinding, fading out over the tail. */
	public static float intensity(float partialTicks) {
		if (ticks >= 0) {
			return 1.0f;
		}
		return tail > 0 ? Mth.clamp((tail - partialTicks) / TAIL, 0.0f, 1.0f) * 0.8f : 0.0f;
	}

	/** Where the camera is during the rewind, or null when it belongs to the player. */
	@Nullable
	public static Frame cameraFrame(float partialTicks) {
		if (ticks < 0 || tape.size() < 2) {
			return null;
		}
		float progress = Mth.clamp((ticks + partialTicks) / LENGTH, 0.0f, 1.0f);
		// from the moment of death backwards, speeding up like a real tape
		float back = progress * progress * (3.0f - 2.0f * progress);
		float at = (tape.size() - 1) * (1.0f - back);
		int i = Mth.clamp((int) at, 0, tape.size() - 2);
		float f = at - i;
		Frame a = tape.get(i);
		Frame b = tape.get(i + 1);
		Vec3 eye = a.eye().lerp(b.eye(), f);
		// the picture jumps a little, as on a worn tape
		float shake = (float) Math.sin((ticks + partialTicks) * 2.7) * 0.6f;
		return new Frame(eye, Mth.rotLerp(f, a.yRot(), b.yRot()) + shake, Mth.lerp(f, a.xRot(), b.xRot()));
	}
}
