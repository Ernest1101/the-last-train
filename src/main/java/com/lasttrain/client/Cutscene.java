package com.lasttrain.client;

import com.lasttrain.entity.BlindOneEntity;
import com.lasttrain.entity.TrainEntity;
import com.lasttrain.game.HouseBuilder;
import com.lasttrain.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The ending cutscenes. The camera leaves the player and cuts between a few fixed shots:
 * <ul>
 *     <li>escaped: the wagon at the platform, the Blind One coming out of the front door, then the Blind One
 *     on the platform screaming after the train as it disappears;</li>
 *     <li>missed: the train leaving the station without you;</li>
 *     <li>freed: the house catches fire from the inside while the camera rises up and away from it.</li>
 * </ul>
 * The ending text waits until the cutscene has faded to black.
 */
public final class Cutscene {
	private static final int ESCAPE_LENGTH = 190;
	private static final int MISSED_LENGTH = 110;
	private static final int FREED_LENGTH = 230;
	private static final int FADE = 22;

	public record Frame(Vec3 eye, float yRot, float xRot) {
	}

	private static int kind = -1;
	private static int ticks;
	private static int length;
	private static int trainId = -1;
	private static Vec3 door = Vec3.ZERO;
	private static Vec3 spot = Vec3.ZERO;
	private static Vec3 house = Vec3.ZERO;
	private static Vec3 trainFallback = Vec3.ZERO;
	/** The ending that arrived while the cutscene was still running. */
	private static int queuedEnding = -1;

	private Cutscene() {
	}

	public static void start(int newKind, int train, Vec3 doorPos, Vec3 spotPos, Vec3 houseCentre) {
		Minecraft mc = Minecraft.getInstance();
		if (active() && kind == newKind) {
			return; // already watching it
		}
		kind = newKind;
		ticks = 0;
		length = newKind == ModNetwork.ENDING_ESCAPED ? ESCAPE_LENGTH : newKind == ModNetwork.ENDING_MISSED ? MISSED_LENGTH : FREED_LENGTH;
		trainId = train;
		door = doorPos;
		spot = spotPos;
		house = houseCentre;
		trainFallback = train() != null ? train().position() : spotPos;
		queuedEnding = -1;
	}

	public static boolean active() {
		return kind >= 0;
	}

	/** Called when the server says the game is over: show the text now, or after the cutscene. */
	public static boolean deferEnding(int ending) {
		if (!active()) {
			return false;
		}
		queuedEnding = ending;
		return true;
	}

	public static void reset() {
		kind = -1;
		queuedEnding = -1;
	}

	public static void tick(Minecraft mc) {
		if (!active()) {
			return;
		}
		if (mc.player == null) {
			reset();
			return;
		}
		TrainEntity train = train();
		if (train != null) {
			trainFallback = train.position();
		}
		if (kind == ModNetwork.ENDING_FREED) {
			burn(mc);
		}
		if (++ticks >= length) {
			int ending = queuedEnding;
			reset();
			if (ending >= 0) {
				mc.setScreen(new EndingScreen(ending));
			}
		}
	}

	/** 0..1 black over the picture: fades in at the very start and out at the end. */
	public static float blackout(float partialTicks) {
		if (!active()) {
			return 0.0f;
		}
		float t = ticks + partialTicks;
		float in = 1.0f - Mth.clamp(t / 8.0f, 0.0f, 1.0f);
		float out = Mth.clamp((t - (length - FADE)) / FADE, 0.0f, 1.0f);
		return Math.max(in, out);
	}

	@Nullable
	public static Frame cameraFrame(float partialTicks) {
		if (!active()) {
			return null;
		}
		float t = ticks + partialTicks;
		Vec3 train = trainPos(partialTicks);
		if (kind == ModNetwork.ENDING_ESCAPED) {
			return escape(t, train, partialTicks);
		}
		if (kind == ModNetwork.ENDING_MISSED) {
			// standing on the platform, watching its lights go
			Vec3 eye = new Vec3(spot.x - 6.0 + t * 0.02, spot.y + 1.6, spot.z + 1.0);
			return look(eye, train.add(0.0, 2.0, 0.0));
		}
		// freed: the silent house from the path, then rising up and away from it into the night
		if (t < 90) {
			float p = t / 90.0f;
			Vec3 eye = new Vec3(door.x + 1.5, door.y + 1.0, door.z - 7.0 + p * 1.2);
			return look(eye, new Vec3(door.x, door.y + 6.0, door.z + 4.0));
		}
		float p = ease(Mth.clamp((t - 90) / (length - 90.0f), 0.0f, 1.0f));
		Vec3 near = new Vec3(door.x, door.y + 6.0, door.z - 14.0);
		Vec3 far = new Vec3(door.x, door.y + 30.0, door.z - 46.0);
		return look(near.lerp(far, p), house);
	}

	private static Frame escape(float t, Vec3 train, float partialTicks) {
		if (t < 55) {
			// the wagon at the platform, doors closing
			Vec3 eye = new Vec3(train.x - 7.5 + t * 0.06, train.y + 2.3, train.z + 5.0);
			return look(eye, train.add(0.0, 1.7, 0.0));
		}
		BlindOneEntity monster = monster();
		Vec3 m = monster != null ? monster.getPosition(partialTicks) : spot;
		if (t < 118) {
			// on the path in front of the house: something comes out of the door and walks straight at you
			float p = (t - 55) / 63.0f;
			boolean out = monster != null && m.z < door.z + 0.8;
			double z = door.z - 5.0 + p * 0.7;
			if (out) {
				z = Math.min(z, m.z - 3.2); // backing away as it comes
			}
			Vec3 eye = new Vec3(door.x, door.y + 1.9 - p * 0.3, z);
			Vec3 target = out ? m.add(0.0, 1.4, 0.0) : door.add(0.0, 1.2, 0.0);
			return look(eye, target);
		}
		// behind it on the platform: it stands at the edge screaming after the train running away into the dark
		float p = (t - 118) / (length - 118.0f);
		Vec3 eye = new Vec3(spot.x - 2.2 - p * 0.6, spot.y + 2.1, spot.z + 2.6 + p * 0.6);
		return look(eye, train.add(0.0, 1.8, 0.0));
	}

	/** Where the flames go, in the order they catch: from the front door up the wall, then the roof. */
	private static final java.util.List<net.minecraft.core.BlockPos> FIRE = new java.util.ArrayList<>();
	private static int lit;
	private static final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> FIRE_STATE = new java.util.HashMap<>();

	/**
	 * The house burns. The fire is placed in this client's copy of the world only: it lights the house up and
	 * crackles like real fire, but the server never has it, so it can't spread anywhere.
	 */
	private static void burn(Minecraft mc) {
		net.minecraft.client.multiplayer.ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (ticks == 1) {
			planFire(level);
		}
		// slowly at first, then everything at once
		float heat = Mth.clamp((ticks - 5) / 160.0f, 0.0f, 1.0f);
		int target = (int) (FIRE.size() * heat);
		net.minecraft.world.level.block.state.BlockState fire = net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState();
		while (lit < target) {
			net.minecraft.core.BlockPos pos = FIRE.get(lit++);
			level.setBlock(pos, FIRE_STATE.getOrDefault(pos, fire), net.minecraft.world.level.block.Block.UPDATE_CLIENTS
					| net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
		}
		net.minecraft.util.RandomSource random = level.random;
		if (lit > 0 && ticks % 2 == 0) {
			for (int i = 0; i < 1 + (int) (heat * 5); i++) {
				net.minecraft.core.BlockPos pos = FIRE.get(random.nextInt(lit));
				level.addAlwaysVisibleParticle(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
						pos.getX() + random.nextDouble(), pos.getY() + 0.5, pos.getZ() + random.nextDouble(), 0.0, 0.08, 0.0);
				level.addAlwaysVisibleParticle(net.minecraft.core.particles.ParticleTypes.LAVA, true,
						pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
			}
		}
		if (ticks % 10 == 0 && lit > 0) {
			net.minecraft.core.BlockPos pos = FIRE.get(random.nextInt(lit));
			level.playLocalSound(pos, net.minecraft.sounds.SoundEvents.FIRE_AMBIENT, net.minecraft.sounds.SoundSource.BLOCKS,
					3.0f + heat * 4.0f, 0.6f + random.nextFloat() * 0.3f, false);
		}
		if (ticks == 60 || ticks == 120) {
			level.playLocalSound(house.x, house.y + 6.0, door.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,
					net.minecraft.sounds.SoundSource.BLOCKS, 2.5f, 0.5f, false); // a beam gives way
		}
	}

	private static void planFire(net.minecraft.client.multiplayer.ClientLevel level) {
		FIRE.clear();
		FIRE_STATE.clear();
		lit = 0;
		// the house origin: the centre the server sent is the middle of the (WIDTH, 2*STOREY, DEPTH) box
		net.minecraft.core.BlockPos o = net.minecraft.core.BlockPos.containing(house.x - HouseBuilder.WIDTH / 2.0 - 0.5,
				house.y - HouseBuilder.STOREY - 0.5, house.z - HouseBuilder.DEPTH / 2.0 - 0.5);
		java.util.Random random = new java.util.Random(o.asLong());
		java.util.List<java.util.Map.Entry<Double, net.minecraft.core.BlockPos>> plan = new java.util.ArrayList<>();
		net.minecraft.world.level.block.state.BlockState onWall = net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState()
				.setValue(net.minecraft.world.level.block.FireBlock.SOUTH, true);
		for (int x = -1; x <= HouseBuilder.WIDTH + 1; x++) {
			double fromDoor = Math.abs(x - HouseBuilder.DOOR_X);
			// up the front wall, starting at the door
			for (int y = 1; y <= 2 * HouseBuilder.STOREY; y++) {
				net.minecraft.core.BlockPos pos = o.offset(x, y, -1);
				if (!level.getBlockState(pos).canBeReplaced()) {
					continue;
				}
				boolean wall = level.getBlockState(pos.south()).isSolid();
				boolean floor = level.getBlockState(pos.below()).isSolid();
				// patches of flame, not a sheet of it: a cell burns if its patch does, and only some cells of a patch
				int patch = (x / 4) * 31 + (y / 3) * 17;
				boolean burningPatch = new java.util.Random(o.asLong() ^ patch).nextFloat() < 0.45f;
				if ((wall || floor) && (floor ? random.nextFloat() < 0.6f : burningPatch && random.nextFloat() < 0.45f)) {
					plan.add(java.util.Map.entry(y * 1.3 + fromDoor * 0.25 + random.nextDouble() * 4.0, pos));
					FIRE_STATE.put(pos, wall && !floor ? onWall : net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState());
				}
			}
			// the roof, last
			for (int z = 0; z <= HouseBuilder.DEPTH; z++) {
				for (int y = 2 * HouseBuilder.STOREY + 8; y > HouseBuilder.STOREY; y--) {
					net.minecraft.core.BlockPos pos = o.offset(x, y, z);
					if (!level.getBlockState(pos).isAir()) {
						if (level.getBlockState(pos.above()).isAir() && random.nextFloat() < 0.4f) {
							plan.add(java.util.Map.entry(16.0 + fromDoor * 0.15 + random.nextDouble() * 10.0, pos.above()));
						}
						break;
					}
				}
			}
		}
		plan.sort(java.util.Map.Entry.comparingByKey());
		plan.forEach(entry -> FIRE.add(entry.getValue()));
	}

	private static Frame look(Vec3 eye, Vec3 target) {
		Vec3 d = target.subtract(eye);
		float yRot = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float xRot = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new Frame(eye, yRot, xRot);
	}

	private static float ease(float x) {
		return x * x * (3.0f - 2.0f * x);
	}

	private static Vec3 trainPos(float partialTicks) {
		TrainEntity train = train();
		return train != null ? train.getPosition(partialTicks) : trainFallback;
	}

	@Nullable
	private static TrainEntity train() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || trainId < 0) {
			return null;
		}
		Entity entity = mc.level.getEntity(trainId);
		return entity instanceof TrainEntity train ? train : null;
	}

	/** The Blind One closest to the door - the one coming after the train. */
	@Nullable
	private static BlindOneEntity monster() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null) {
			return null;
		}
		BlindOneEntity best = null;
		double bestDistance = 40.0 * 40.0;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof BlindOneEntity monster && !monster.isRemoved()) {
				double d = monster.distanceToSqr(spot);
				if (d < bestDistance) {
					best = monster;
					bestDistance = d;
				}
			}
		}
		return best;
	}
}
