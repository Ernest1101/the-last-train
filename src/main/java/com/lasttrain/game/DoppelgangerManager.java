package com.lasttrain.game;

import com.lasttrain.config.LastTrainConfig;
import com.lasttrain.entity.DoppelgangerEntity;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Now and then sends a Double after one of the players: it appears in the gallery on their floor,
 * out of sight, wearing a friend's face (or theirs, if they play alone).
 */
final class DoppelgangerManager {
	/** Nobody is fooled in the first minute; after that one every 1-2.5 minutes. */
	private static final int FIRST_DELAY = 20 * 60;
	private static final int GAP = 20 * 60;
	private static final int RANDOM_GAP = 20 * 90;
	static final double MIN_DISTANCE = 12.0;
	static final double MAX_DISTANCE = 34.0;

	private int cooldown = FIRST_DELAY;

	void tick(GameSession session, List<ServerPlayer> players) {
		if (!LastTrainConfig.get().doppelgangerEnabled || --this.cooldown > 0) {
			return;
		}
		ServerLevel level = session.level();
		RandomSource random = level.random;
		this.cooldown = GAP + random.nextInt(RANDOM_GAP);
		AABB house = new AABB(session.layout().houseMin(), session.layout().houseMax()).inflate(16.0);
		if (!level.getEntitiesOfClass(DoppelgangerEntity.class, house).isEmpty()) {
			return;
		}
		List<ServerPlayer> candidates = new ArrayList<>(players);
		java.util.Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
		for (ServerPlayer player : candidates) {
			if (spawnFor(session.level(), session.layout(), player, players) != null) {
				return;
			}
		}
	}

	/**
	 * A Double for {@code haunted}, in the gallery on their floor, 12-34 blocks away and out of their sight.
	 * Null if they are somewhere it can't come (their bedroom, the basement, the attic, outside).
	 */
	@Nullable
	static DoppelgangerEntity spawnFor(ServerLevel level, HouseBuilder.Layout layout, ServerPlayer haunted, List<ServerPlayer> everyone) {
		if (!haunted.isAlive() || haunted.isSpectator() || haunted.level() != level
				|| !layout.isInsideHouse(haunted.position()) || GameSession.safeRoomAt(level, haunted.position()) != null) {
			return null;
		}
		int local = haunted.getBlockY() - layout.origin().getY();
		int floor;
		if (local >= 1 && local < HouseBuilder.STOREY) {
			floor = 1;
		} else if (local >= HouseBuilder.STOREY + 1 && local < 2 * HouseBuilder.STOREY) {
			floor = HouseBuilder.STOREY + 1;
		} else {
			return null;
		}
		RandomSource random = level.random;
		for (int attempt = 0; attempt < 60; attempt++) {
			BlockPos pos = layout.origin().offset(1 + random.nextInt(HouseBuilder.WIDTH - 1), floor, 11 + random.nextInt(3));
			if (!standable(level, pos)) {
				continue;
			}
			Vec3 at = Vec3.atBottomCenterOf(pos);
			double distance = at.distanceTo(haunted.position());
			if (distance < MIN_DISTANCE || distance > MAX_DISTANCE || canSee(level, haunted, at)) {
				continue;
			}
			DoppelgangerEntity double_ = ModRegistry.DOPPELGANGER.create(level);
			if (double_ == null) {
				return null;
			}
			List<ServerPlayer> friends = new ArrayList<>();
			for (ServerPlayer other : everyone) {
				if (other != haunted && other.isAlive() && !other.isSpectator()) {
					friends.add(other);
				}
			}
			ServerPlayer copied = friends.isEmpty() ? haunted : friends.get(random.nextInt(friends.size()));
			double_.moveTo(at.x, at.y, at.z, random.nextFloat() * 360.0f, 0.0f);
			double_.imitate(copied, haunted);
			level.addFreshEntity(double_);
			return double_;
		}
		return null;
	}

	private static boolean standable(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
				&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
				&& level.getBlockState(pos.below()).isSolid();
	}

	/** In front of them and nothing in between. */
	private static boolean canSee(ServerLevel level, ServerPlayer player, Vec3 at) {
		Vec3 eye = player.getEyePosition();
		Vec3 target = at.add(0.0, 1.5, 0.0);
		Vec3 to = target.subtract(eye).normalize();
		if (player.getViewVector(1.0f).dot(to) < 0.2) {
			return false;
		}
		return level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
	}
}
