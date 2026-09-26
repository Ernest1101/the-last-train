package com.lasttrain.client;

/** Client-side mirror of what the HUD, camera and shader need. */
public final class ClientState {
	public static float breath = 1.0f;
	public static boolean holding;
	public static boolean exhausted;
	/** 0..1, how close the nearest Blind One is. Smoothed. */
	public static float fear;
	public static int scareTicks;
	public static final int SCARE_LENGTH = 40;
	public static boolean bodycamEnabled = true;
	/** Hiding in a wardrobe: the view shrinks to the gap between the doors. */
	public static boolean hidden;
	/** The lens cracked after dying in the house. */
	public static boolean lensCracked;
	/** "NO SIGNAL" frames while a Watcher is near. */
	public static int noSignalTicks;
	/** 0..1 how bright the flashlight is this frame (flickers on a weak battery). */
	public static float flashlight;

	private ClientState() {
	}

	public static float scare(float partialTicks) {
		return scareTicks <= 0 ? 0.0f : Math.min(1.0f, (scareTicks - partialTicks) / (float) SCARE_LENGTH * 1.5f);
	}

	public static void reset() {
		breath = 1.0f;
		holding = false;
		exhausted = false;
		fear = 0.0f;
		scareTicks = 0;
		hidden = false;
		lensCracked = false;
		noSignalTicks = 0;
		flashlight = 0.0f;
	}
}
