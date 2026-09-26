package com.lasttrain.entity;

import com.lasttrain.game.GameSession;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * The nursery doll. It never moves while someone is looking at it. Look away and it may be somewhere else,
 * sitting, facing the wall, arms raised, its head turned round - or it comes for you, little step by little
 * step, and freezes mid-step the moment you look back. If it reaches you unseen it grabs your leg and
 * laughs, loud enough for the Blind One to hear. Sooner or later it ends up next to your bed.
 */
public class DollEntity extends PathfinderMob implements GeoEntity {
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.doll.idle");
	/** The head turned all the way round: the body faces away, the face still looks at you. */
	private static final RawAnimation TURNED = RawAnimation.begin().thenLoop("animation.doll.turned");
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.doll.walk");
	private static final RawAnimation SIT = RawAnimation.begin().thenLoop("animation.doll.sit");
	private static final RawAnimation CORNER = RawAnimation.begin().thenLoop("animation.doll.corner");
	private static final RawAnimation ARMS = RawAnimation.begin().thenLoop("animation.doll.arms");

	public static final int POSE_STAND = 0;
	public static final int POSE_SIT = 1;
	/** Standing with its back to you, head bowed. */
	public static final int POSE_CORNER = 2;
	/** Arms up, like a child that wants to be picked up. */
	public static final int POSE_ARMS = 3;

	private static final EntityDataAccessor<Boolean> HEAD_TURNED = SynchedEntityData.defineId(DollEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> POSE = SynchedEntityData.defineId(DollEntity.class, EntityDataSerializers.INT);
	/** Creeping up on someone. */
	private static final EntityDataAccessor<Boolean> WALKING = SynchedEntityData.defineId(DollEntity.class, EntityDataSerializers.BOOLEAN);
	/** Somebody is looking at it: whatever it was doing stops dead, mid-step. */
	private static final EntityDataAccessor<Boolean> WATCHED = SynchedEntityData.defineId(DollEntity.class, EntityDataSerializers.BOOLEAN);

	/** How close it has to get to grab a leg. */
	public static final double GRAB_REACH = 1.6;
	/** The laugh after the grab carries this far (a noise radius for the Blind One). */
	public static final float LAUGH_RADIUS = 20.0f;
	private static final int CREEP_TIME = 20 * 25;
	private static final int REST_AFTER_GRAB = 20 * 40;
	private static final int REST_AFTER_CREEP = 20 * 15;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private int moves;
	/** Ticks left of creeping up on {@link #quarry}. */
	private int creeping;
	private int rest = 20 * 5;
	@Nullable
	private Player quarry;

	public DollEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setInvulnerable(true);
		this.setPersistenceRequired();
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(HEAD_TURNED, false);
		this.entityData.define(POSE, POSE_STAND);
		this.entityData.define(WALKING, false);
		this.entityData.define(WATCHED, false);
	}

	public boolean isHeadTurned() {
		return this.entityData.get(HEAD_TURNED);
	}

	public int getDollPose() {
		return this.entityData.get(POSE);
	}

	public boolean isCreeping() {
		return this.entityData.get(WALKING);
	}

	public boolean isWatched() {
		return this.entityData.get(WATCHED);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 10.0)
				.add(Attributes.MOVEMENT_SPEED, 0.28)
				.add(Attributes.FOLLOW_RANGE, 24.0);
	}

	@Override
	public void tick() {
		super.tick();
		if (!(this.level() instanceof ServerLevel level)) {
			return;
		}
		List<Player> players = level.getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(40.0),
				p -> EntitySelector.NO_SPECTATORS.test(p) && !p.isCreative());
		boolean watched = this.isSeenByAnyone(players);
		this.entityData.set(WATCHED, watched);
		if (this.rest > 0) {
			this.rest--;
		}
		if (this.creeping > 0) {
			this.creep(level, watched);
			return;
		}
		if (this.tickCount % 20 == 10) {
			if (!watched && this.rest == 0 && this.random.nextFloat() < 0.3f) {
				this.startCreeping(players);
				if (this.creeping > 0) {
					return;
				}
			}
			this.maybeTurnHead(level, players, watched);
		}
		if (this.tickCount % 60 == 0) {
			this.maybeMove(level, players, watched);
		}
	}

	// ---------------------------------------------------------------- creeping up

	private void startCreeping(List<Player> players) {
		Player nearest = null;
		for (Player player : players) {
			if (Math.abs(player.getY() - this.getY()) < 3.0 && this.distanceTo(player) < 18.0
					&& GameSession.safeRoomAt(this.level(), player.position()) == null
					&& (nearest == null || this.distanceTo(player) < this.distanceTo(nearest))) {
				nearest = player;
			}
		}
		if (nearest == null) {
			return;
		}
		this.quarry = nearest;
		this.creeping = CREEP_TIME;
		this.entityData.set(HEAD_TURNED, false);
		this.entityData.set(POSE, POSE_STAND);
		this.entityData.set(WALKING, true);
	}

	private void creep(ServerLevel level, boolean watched) {
		Player target = this.quarry;
		if (--this.creeping <= 0 || target == null || !target.isAlive() || target.level() != level
				|| GameSession.safeRoomAt(level, target.position()) != null) {
			this.stopCreeping(REST_AFTER_CREEP);
			return;
		}
		if (watched) {
			// caught: not a hair moves, not even the last bit of a step
			this.getNavigation().stop();
			this.setDeltaMovement(0.0, Math.min(0.0, this.getDeltaMovement().y), 0.0);
			this.xxa = 0.0f;
			this.zza = 0.0f;
			return;
		}
		if (this.distanceTo(target) < GRAB_REACH) {
			this.grab(level, target);
			return;
		}
		if (this.tickCount % 5 == 0) {
			this.getNavigation().moveTo(target, 1.0);
		}
	}

	private void stopCreeping(int rest) {
		this.creeping = 0;
		this.quarry = null;
		this.rest = rest;
		this.getNavigation().stop();
		this.entityData.set(WALKING, false);
	}

	/** It got to you: a tiny hand closes round your ankle, and it laughs out loud. */
	private void grab(ServerLevel level, Player victim) {
		victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3, false, false));
		if (victim instanceof ServerPlayer player) {
			ModNetwork.sendScare(player);
		}
		level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), ModSounds.GIGGLE, SoundSource.HOSTILE, 2.0f, 0.6f);
		NoiseSystem.emit(level, victim.position(), LAUGH_RADIUS, null);
		this.stopCreeping(REST_AFTER_GRAB);
		// and it is gone again - somewhere behind you, never where you are looking
		List<Player> players = level.getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(40.0), EntitySelector.NO_SPECTATORS);
		BlockPos fallback = null;
		for (int attempt = 0; attempt < 12; attempt++) {
			BlockPos away = this.findHidingSpot(level, victim, 9.0, 9.0);
			if (away == null) {
				continue;
			}
			fallback = fallback == null ? away : fallback;
			this.appear(level, away, victim);
			if (!this.isSeenByAnyone(players)) {
				return;
			}
		}
		// right behind you, then; failing that anywhere away from your feet
		Vec3 look = victim.getViewVector(1.0f).multiply(1.0, 0.0, 1.0).normalize();
		for (double back = 6.0; back >= 3.0; back -= 1.0) {
			BlockPos behind = BlockPos.containing(victim.position().subtract(look.scale(back)));
			for (int dy = 1; dy >= -2; dy--) {
				BlockPos pos = behind.above(dy);
				if (level.getBlockState(pos.below()).isSolid() && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()) {
					this.appear(level, pos, victim);
					if (!this.isSeenByAnyone(players)) {
						return;
					}
				}
			}
		}
		if (fallback != null) {
			this.appear(level, fallback, victim);
		}
	}

	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
		// porcelain feet on the floorboards
		this.playSound(SoundEvents.BONE_BLOCK_STEP, 0.25f, 1.9f);
	}

	// ---------------------------------------------------------------- moving about unseen

	private void maybeMove(ServerLevel level, List<Player> players, boolean watched) {
		if (players.isEmpty() || watched || this.random.nextFloat() > 0.35f) {
			return;
		}
		this.moves++;
		BlockPos target = null;
		boolean bedside = this.moves % 4 == 0;
		if (bedside) {
			target = GameSession.randomBedside(level, this.random);
		}
		if (target == null) {
			Player victim = players.get(this.random.nextInt(players.size()));
			target = this.findHidingSpot(level, victim, 4.0, 10.0);
			bedside = false;
		}
		if (target == null) {
			return;
		}
		Vec3 from = this.position();
		this.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
		if (this.isSeenByAnyone(players)) {
			this.teleportTo(from.x, from.y, from.z); // would have been seen moving
			return;
		}
		this.appear(level, target, level.getNearestPlayer(this, 40.0));
		if (bedside) {
			this.entityData.set(POSE, POSE_STAND); // by the bed it just stands and watches you sleep
		}
		level.playSound(null, this.getX(), this.getY(), this.getZ(), bedside ? ModSounds.LULLABY : ModSounds.GIGGLE,
				SoundSource.HOSTILE, bedside ? 0.7f : 0.8f, 1.0f);
	}

	/** Takes up a new place and a new pose: sitting, facing away, arms up, or just standing there. */
	private void appear(ServerLevel level, BlockPos at, @Nullable Player watcher) {
		this.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		this.entityData.set(HEAD_TURNED, false);
		int pose = switch (this.random.nextInt(6)) {
			case 0, 1 -> POSE_STAND;
			case 2, 3 -> POSE_SIT;
			case 4 -> POSE_CORNER;
			default -> POSE_ARMS;
		};
		this.entityData.set(POSE, pose);
		if (watcher != null) {
			Vec3 d = watcher.position().subtract(this.position());
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0) + (pose == POSE_CORNER ? 180.0f : 0.0f);
			this.setYRot(yaw);
			this.yHeadRot = yaw;
			this.yBodyRot = yaw;
		}
	}

	/**
	 * While nobody looks, it may turn its back on the nearest player - all but the head, which turns
	 * all the way round so the face keeps looking at them. You notice when you look back.
	 */
	private void maybeTurnHead(ServerLevel level, List<Player> players, boolean watched) {
		if (this.isHeadTurned() || watched || this.random.nextFloat() > 0.25f) {
			return;
		}
		Player nearest = level.getNearestPlayer(this, 40.0);
		if (nearest == null) {
			return;
		}
		Vec3 d = nearest.position().subtract(this.position());
		float away = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0) + 180.0f;
		this.setYRot(away);
		this.yHeadRot = away;
		this.yBodyRot = away;
		this.entityData.set(POSE, POSE_STAND);
		this.entityData.set(HEAD_TURNED, true);
		level.playSound(null, this.getX(), this.getY(), this.getZ(), ModSounds.CREAK, SoundSource.HOSTILE, 0.35f, 1.8f);
	}

	private boolean isSeenByAnyone(List<Player> players) {
		Vec3 me = new Vec3(this.getX(), this.getY() + 0.4, this.getZ());
		for (Player player : players) {
			Vec3 to = me.subtract(player.getEyePosition());
			double distance = to.length();
			if (distance < 1.0) {
				return true;
			}
			if (player.getViewVector(1.0f).dot(to.scale(1.0 / distance)) > 0.45 && player.hasLineOfSight(this)) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private BlockPos findHidingSpot(ServerLevel level, Player victim, double minDistance, double spread) {
		for (int attempt = 0; attempt < 20; attempt++) {
			double angle = this.random.nextDouble() * Math.PI * 2.0;
			double distance = minDistance + this.random.nextDouble() * spread;
			int x = (int) Math.floor(victim.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(victim.getZ() + Math.sin(angle) * distance);
			for (int dy = 1; dy >= -2; dy--) {
				BlockPos pos = new BlockPos(x, victim.getBlockY() + dy, z);
				if (level.getBlockState(pos.below()).isSolid() && level.getBlockState(pos).isAir()) {
					return pos;
				}
			}
		}
		return null;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(net.minecraft.world.entity.Entity entity) {
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		// 3 ticks of transition: the head snaps round rather than turning
		controllers.add(new AnimationController<>(this, "idle", 3, state -> {
			// watched, it freezes mid-pose: the animation itself stops
			state.getController().setAnimationSpeed(this.isWatched() && this.isCreeping() ? 0.0 : 1.0);
			if (this.isCreeping()) {
				return state.setAndContinue(WALK);
			}
			if (this.isHeadTurned()) {
				return state.setAndContinue(TURNED);
			}
			return switch (this.getDollPose()) {
				case POSE_SIT -> state.setAndContinue(SIT);
				case POSE_CORNER -> state.setAndContinue(CORNER);
				case POSE_ARMS -> state.setAndContinue(ARMS);
				default -> state.setAndContinue(IDLE);
			};
		}));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}
}
