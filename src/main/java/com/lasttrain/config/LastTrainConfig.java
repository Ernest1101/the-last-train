package com.lasttrain.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lasttrain.LastTrain;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/lasttrain.json - created with the defaults on first launch. */
public final class LastTrainConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static LastTrainConfig instance = new LastTrainConfig();

	/** Easy / normal / nightmare: scales the monster, the loot and the train (see {@link Difficulty}). */
	public Difficulty difficulty = Difficulty.NORMAL;
	/** How many days the curse lasts before the nightmare. */
	public int curseDays = 3;
	/** Multiplies how often creepy sounds and screamers happen (0 turns them off). */
	public double scareFrequency = 1.0;
	/** The tall figure that jumps at you when you look at it. */
	public boolean watcherEnabled = true;
	/** The Double: something with a player's face that follows you around the house. */
	public boolean doppelgangerEnabled = true;
	/** "Save and Quit" needs five presses while cursed. */
	public boolean exitGuardEnabled = true;
	/** The Blind One's speed and hearing, 1.0 = normal. */
	public double monsterSpeed = 1.0;
	public double monsterHearing = 1.0;
	/** Seconds the train waits at the platform. */
	public int trainWaitSeconds = 20;

	public enum Difficulty {
		EASY(0.85, 0.8, 0, 4, 2, 15),
		NORMAL(1.0, 1.0, 0, 0, 0, 0),
		NIGHTMARE(1.15, 1.3, 1, -4, -2, -8);

		/** Multiplies the monster's speed and hearing. */
		public final double speed;
		public final double hearing;
		/** How angry the monster is before anyone has died. */
		public final int startAnger;
		/** Bottles and batteries hidden in the house, on top of the usual 8 and 4. */
		public final int extraBottles;
		public final int extraBatteries;
		/** Seconds added to how long the train waits. */
		public final int trainWaitBonus;

		Difficulty(double speed, double hearing, int startAnger, int extraBottles, int extraBatteries, int trainWaitBonus) {
			this.speed = speed;
			this.hearing = hearing;
			this.startAnger = startAnger;
			this.extraBottles = extraBottles;
			this.extraBatteries = extraBatteries;
			this.trainWaitBonus = trainWaitBonus;
		}
	}

	public static LastTrainConfig get() {
		return instance;
	}

	public Difficulty difficulty() {
		return this.difficulty != null ? this.difficulty : Difficulty.NORMAL;
	}

	/** The monster's speed: the slider times the difficulty. */
	public double effectiveMonsterSpeed() {
		return this.monsterSpeed * this.difficulty().speed;
	}

	public double effectiveMonsterHearing() {
		return this.monsterHearing * this.difficulty().hearing;
	}

	public int effectiveTrainWaitSeconds() {
		return Math.max(8, this.trainWaitSeconds + this.difficulty().trainWaitBonus);
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(LastTrain.MOD_ID + ".json");
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				LastTrainConfig loaded = GSON.fromJson(reader, LastTrainConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | RuntimeException e) {
				LastTrain.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}
		instance.curseDays = Math.max(1, instance.curseDays);
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(instance, writer); // also adds options that are new in this version
		} catch (IOException e) {
			LastTrain.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
