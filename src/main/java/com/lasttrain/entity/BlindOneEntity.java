package com.lasttrain.entity;

import com.lasttrain.game.BreathManager;
import com.lasttrain.game.GameSession;
import com.lasttrain.game.HideManager;
import com.lasttrain.game.HouseBuilder;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.registry.ModRegistry;
import com.lasttrain.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
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
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The Blind One. It has no eyes; it finds you three ways:
 * <ul>
 *   <li><b>hearing</b> - every noise (see {@link com.lasttrain.noise.NoiseSystem}) turns its head and draws it in;</li>
 *   <li><b>clicking</b> - it clicks its tongue and listens to the echo: a clear click, a few blocks, and it knows
 *       where you stand. You hear the clicks coming. Crouch and the echo barely catches you; out of its line, not at all;</li>
 *   <li><b>touch</b> - bump into it and it has you.</li>
 * </ul>
 * And it has moods rather than a sniff-and-roar routine:
 * <ul>
 *   <li><b>wander</b> - it walks the house room to room, breathing, clicking now and then;</li>
 *   <li><b>lurk</b> - sometimes it just stops in a doorway or a corner and waits, completely silent;</li>
 *   <li><b>stalk</b> - a noise: its head snaps round, a burst of clicks, and it creeps towards it, slower the closer it gets;</li>
 *   <li><b>hunt</b> - it knows where you are: it runs, clicking fast;</li>
 *   <li><b>search</b> - it lost you: it goes to where you were, on in the way you were heading, clicks through the
 *       rooms around and taps the wardrobes. If you breathe in there, it may find you. Then a scream, and it gives up.</li>
 * </ul>
 * It remembers where it has caught people before and comes back to those places more often.
 */
public class BlindOneEntity extends Monster implements GeoEntity {
	public static final int MOOD_WANDER = 0;
	public static final int MOOD_STALK = 1;
	public static final int MOOD_HUNT = 2;
	public static final int MOOD_SEARCH = 3;
	public static final int MOOD_LURK = 4;

	private static final EntityDataAccessor<Integer> MOOD = SynchedEntityData.defineId(BlindOneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> RUNNING = SynchedEntityData.defineId(BlindOneEntity.class, EntityDataSerializers.BOOLEAN);

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.blind_one.idle");
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.blind_one.walk");
	private static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.blind_one.run");
	private static final RawAnimation LISTEN = RawAnimation.begin().thenLoop("animation.blind_one.listen");
	private static final RawAnimation LURK = RawAnimation.begin().thenLoop("animation.blind_one.lurk");
	private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.blind_one.attack");

	private static final double WALK_SPEED = 0.8;
	private static final double CREEP_SPEED = 0.55;
	private static final double RUN_SPEED = 1.3;
	private static final double HUNT_SPEED = 1.45;

	/** How far a click "sees" a standing player / a crouching one. */
	public static final double ECHO_RANGE = 5.0;
	public static final double ECHO_RANGE_CROUCHING = 2.5;
	private static final int SEARCH_TIME = 20 * 22;
	private static final int MAX_HOT_SPOTS = 5;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	// ---- what it knows
	@Nullable
	private Vec3 noisePos;
	private float noiseStrength;
	private int noiseAge;
	@Nullable
	private Player prey;
	private int preyTicks;
	/** Where the prey was last sensed, and which way it was going. */
	@Nullable
	private Vec3 lastKnown;
	private Vec3 lastHeading = Vec3.ZERO;
	/** Places where it caught somebody: it goes back to look. */
	private final ArrayDeque<BlockPos> hotSpots = new ArrayDeque<>();

	// ---- what it is doing
	private int mood = MOOD_WANDER;
	/** Head snapped round towards a new noise, a burst of clicks, before it moves. */
	private int alertTicks;
	private int searchTicks;
	@Nullable
	private Vec3 searchCenter;
	private int lurkTicks;
	private int clickTimer = 40;
	private int attackCooldown;
	private boolean running;
	/** How many times it has tapped a wardrobe with somebody inside (for the tests). */
	public int wardrobeTaps;
	@Nullable
	private Vec3 lastOutsidePos;

	public BlindOneEntity(EntityType<? extends Monster> type, Level level) {
		super(type, level);
		this.setMaxUpStep(1.0f);
		this.xpReward = 0;
		this.setPersistenceRequired();
		if (this.getNavigation() instanceof GroundPathNavigation navigation) {
			navigation.setCanOpenDoors(true);
		}
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
				.add(Attributes.MAX_HEALTH, 300.0)
				.add(Attributes.MOVEMENT_SPEED, 0.25)
				.add(Attributes.ATTACK_DAMAGE, 40.0)
				.add(Attributes.FOLLOW_RANGE, 64.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(MOOD, MOOD_WANDER);
		this.entityData.define(RUNNING, false);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
		this.goalSelector.addGoal(2, new HuntGoal());
		this.goalSelector.addGoal(3, new StalkGoal());
		this.goalSelector.addGoal(4, new SearchGoal());
		this.goalSelector.addGoal(5, new LurkGoal());
		this.goalSelector.addGoal(6, new WanderGoal());
		this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.6, 0.004f));
	}

	// ------------------------------------------------------------------ senses

	/** Called by the noise system. {@code strength} is 1 at the source and 0 at the edge of the radius. */
	public void hear(Vec3 pos, float strength, @Nullable Entity source) {
		if (this.isDeadOrDying()) {
			return;
		}
		HouseBuilder.SafeRoom safeRoom = GameSession.safeRoomAt(this.level(), pos);
		if (safeRoom != null) {
			// it can hear you in there, but it can only come as far as the door
			pos = safeRoom.entrance();
			if (source == this.prey) {
				this.prey = null;
			}
			source = null;
		}
		if (source instanceof ServerPlayer hidden && HideManager.isHidden(hidden)) {
			// breathing inside a wardrobe: only right next to it can it tell where that came from
			int anger = GameSession.anger(this.level());
			if (this.distanceTo(hidden) < 3.0f && this.random.nextFloat() < 0.25f + 0.2f * anger) {
				this.dragOut(hidden);
				return;
			}
			source = null;
		}
		boolean fromPrey = source != null && source == this.prey;
		// while busy with a fresh noise, ignore much quieter ones (unless it's the prey)
		if (!fromPrey && this.noisePos != null && this.noiseAge < 40 && strength < this.noiseStrength * 0.5f) {
			return;
		}
		boolean fresh = this.noisePos == null || this.noiseAge > 60;
		this.noisePos = pos;
		this.noiseStrength = strength;
		this.noiseAge = 0;
		this.lurkTicks = 0; // whatever it was waiting for, this is it

		if (source instanceof Player player && (fromPrey || this.distanceTo(player) < 4.5f)) {
			this.sense(player, pos);
		} else if (fresh && this.prey == null) {
			// the head snaps round, a burst of clicks, then it goes
			this.alertTicks = 8;
			this.clickTimer = 0;
		}
	}

	/** It has you: from the noise you made, the echo of a click or a bump. */
	private void sense(Player player, Vec3 at) {
		if (this.prey != player) {
			this.playSound(ModSounds.BLIND_SNARL, 1.6f, 0.9f + this.random.nextFloat() * 0.2f);
			this.remember(player.blockPosition());
		}
		// where you stand and which way you were going - on the ground: a breath comes from head height,
		// a footstep from the floor, and that difference is not you climbing
		Vec3 feet = player.position();
		if (this.lastKnown != null) {
			Vec3 step = new Vec3(feet.x - this.lastKnown.x, 0.0, feet.z - this.lastKnown.z);
			if (step.lengthSqr() > 0.01) {
				this.lastHeading = step.normalize();
			}
		}
		this.prey = player;
		this.preyTicks = 60;
		this.noisePos = at;
		this.noiseAge = 0;
		this.lastKnown = feet;
		this.alertTicks = 0;
		this.lurkTicks = 0;
		this.searchTicks = 0;
	}

	/** Its tongue clicks; the echo shows it whoever stands close, in its line, not hidden. */
	private void click(double range) {
		this.playSound(ModSounds.BLIND_CLICK, 1.0f, 0.85f + this.random.nextFloat() * 0.3f);
		for (Player player : this.level().getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(range), EntitySelector.NO_CREATIVE_OR_SPECTATOR)) {
			double reach = player.isShiftKeyDown() ? Math.min(range, ECHO_RANGE_CROUCHING) : range;
			if (HideManager.isHidden(player) || GameSession.safeRoomTouching(this.level(), player.getBoundingBox()) != null
					|| this.distanceTo(player) > reach || !this.hasLineOfSight(player)) {
				continue;
			}
			this.sense(player, player.position());
			return;
		}
	}

	private void remember(BlockPos spot) {
		this.hotSpots.removeIf(p -> p.distSqr(spot) < 6 * 6);
		this.hotSpots.addLast(spot);
		while (this.hotSpots.size() > MAX_HOT_SPOTS) {
			this.hotSpots.removeFirst();
		}
	}

	/** Found somebody breathing in a wardrobe: tears the door open and pulls them out. */
	private void dragOut(ServerPlayer hidden) {
		HideManager.release(hidden);
		this.playSound(ModSounds.BLIND_SCREAM, 2.0f, 1.0f);
		this.sense(hidden, hidden.position());
		this.preyTicks = 100;
	}

	// ------------------------------------------------------------------ every tick

	@Override
	public void aiStep() {
		super.aiStep();
		if (this.level().isClientSide) {
			return;
		}
		this.noiseAge++;
		if (this.attackCooldown > 0) {
			this.attackCooldown--;
		}
		if (this.tickCount % 20 == 0) {
			// every death makes it faster
			double speed = 0.25 * com.lasttrain.config.LastTrainConfig.get().effectiveMonsterSpeed() * (1.0 + 0.1 * GameSession.anger(this.level()));
			if (this.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue() != speed) {
				this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
			}
		}
		// losing the prey: it keeps what it knew and starts searching
		if (this.prey != null && (--this.preyTicks <= 0 || !this.prey.isAlive() || this.prey.isCreative() || this.prey.isSpectator()
				|| HideManager.isHidden(this.prey)
				|| GameSession.safeRoomTouching(this.level(), this.prey.getBoundingBox()) != null)) {
			boolean inSafeRoom = GameSession.safeRoomTouching(this.level(), this.prey.getBoundingBox()) != null;
			this.prey = null;
			this.noisePos = null;
			if (this.lastKnown != null && !inSafeRoom) {
				this.searchCenter = this.lastKnown.add(this.lastHeading.scale(4.0));
				this.searchTicks = SEARCH_TIME;
			}
		}
		HouseBuilder.SafeRoom inside = GameSession.safeRoomTouching(this.level(), this.getBoundingBox());
		if (inside != null) {
			// never inside a player's room: step back out
			Vec3 back = this.lastOutsidePos != null ? this.lastOutsidePos : inside.entrance();
			this.teleportTo(back.x, back.y, back.z);
			this.getNavigation().stop();
		} else {
			this.lastOutsidePos = this.position();
		}
		if (this.alertTicks > 0) {
			this.alertTicks--;
			this.getNavigation().stop();
			if (this.noisePos != null) {
				this.getLookControl().setLookAt(this.noisePos.x, this.noisePos.y + 1.0, this.noisePos.z);
			}
		}
		// it can feel whoever bumps into it
		for (Player player : this.level().getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(0.3), EntitySelector.NO_CREATIVE_OR_SPECTATOR)) {
			if (GameSession.safeRoomTouching(this.level(), player.getBoundingBox()) != null || HideManager.isHidden(player)) {
				continue;
			}
			this.sense(player, player.position());
		}

		this.mood = this.prey != null ? MOOD_HUNT
				: this.noisePos != null ? MOOD_STALK
				: this.searchTicks > 0 ? MOOD_SEARCH
				: this.lurkTicks > 0 ? MOOD_LURK
				: MOOD_WANDER;
		// clicking: fast on the hunt, steady while stalking or searching, now and then while wandering, never lurking
		if (--this.clickTimer <= 0) {
			switch (this.mood) {
				case MOOD_HUNT -> {
					this.clickTimer = 14;
					this.click(ECHO_RANGE + 1.0);
				}
				case MOOD_STALK, MOOD_SEARCH -> {
					this.clickTimer = this.alertTicks > 0 ? 3 : 32 + this.random.nextInt(16);
					this.click(ECHO_RANGE);
				}
				case MOOD_WANDER -> {
					this.clickTimer = 90 + this.random.nextInt(80);
					this.click(ECHO_RANGE - 1.0);
				}
				default -> this.clickTimer = 20;
			}
		}
		this.entityData.set(MOOD, this.mood);
		this.entityData.set(RUNNING, this.running && !this.getNavigation().isDone());
	}

	private void tryAttack(Player target) {
		if (this.attackCooldown > 0 || this.distanceToSqr(target) > 2.4 * 2.4) {
			return;
		}
		this.attackCooldown = 30;
		this.swing(InteractionHand.MAIN_HAND);
		this.triggerAnim("attack", "attack");
		this.playSound(ModSounds.BLIND_ATTACK, 1.5f, 0.9f);
		if (target instanceof ServerPlayer serverPlayer) {
			ModNetwork.sendScare(serverPlayer);
		}
		this.doHurtTarget(target);
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		boolean hurt = super.hurt(source, amount);
		if (hurt && !this.level().isClientSide && source.getEntity() instanceof Player player) {
			this.sense(player, player.position());
			this.preyTicks = 100;
		}
		return hurt;
	}

	/** What it is up to (one of the MOOD_ constants). */
	public int getMood() {
		return this.entityData.get(MOOD);
	}

	/** Where it last heard something (null when it has forgotten). */
	@Nullable
	public Vec3 getNoisePos() {
		return this.noisePos;
	}

	@Nullable
	public Player getPrey() {
		return this.prey;
	}

	/** The places it remembers catching somebody. */
	public List<BlockPos> getHotSpots() {
		return List.copyOf(this.hotSpots);
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	// ------------------------------------------------------------------ its own sounds (nothing borrowed from the Warden)

	@Override
	@Nullable
	protected SoundEvent getAmbientSound() {
		// a wet, rattling breath - except when it lies in wait: then nothing at all
		return this.mood == MOOD_LURK ? null : ModSounds.BLIND_BREATHE;
	}

	@Override
	public int getAmbientSoundInterval() {
		return 160;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return ModSounds.BLIND_SNARL;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return ModSounds.BLIND_SCREAM;
	}

	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
		this.playSound(ModSounds.BLIND_STEP, this.running ? 0.9f : 0.45f, 0.9f + this.random.nextFloat() * 0.2f);
	}

	// ------------------------------------------------------------------ GeckoLib

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "main", 5, state -> {
			int mood = this.getMood();
			if (state.isMoving()) {
				return state.setAndContinue(this.entityData.get(RUNNING) || mood == MOOD_HUNT ? RUN : WALK);
			}
			return state.setAndContinue(switch (mood) {
				case MOOD_LURK -> LURK;
				case MOOD_STALK, MOOD_SEARCH, MOOD_HUNT -> LISTEN;
				default -> IDLE;
			});
		}));
		controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
				.triggerableAnim("attack", ATTACK));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}

	// ------------------------------------------------------------------ goals

	/**
	 * Notices when it has been trying to walk somewhere without getting anywhere (a door, a chair in the way):
	 * first it works the path out again, then it gives up on that target.
	 */
	private final class StuckWatch {
		private Vec3 last = Vec3.ZERO;
		private int ticks;
		private int strikes;

		void reset() {
			this.last = BlindOneEntity.this.position();
			this.ticks = 0;
			this.strikes = 0;
		}

		/** True once it is hopelessly stuck. */
		boolean check() {
			if (++this.ticks < 40) {
				return false;
			}
			this.ticks = 0;
			boolean moved = BlindOneEntity.this.position().distanceToSqr(this.last) > 0.3 * 0.3;
			this.last = BlindOneEntity.this.position();
			if (moved || BlindOneEntity.this.getNavigation().isDone()) {
				this.strikes = 0;
				return false;
			}
			if (++this.strikes == 1) {
				BlindOneEntity.this.getNavigation().recomputePath();
				return false;
			}
			return true;
		}
	}

	/** It knows where you are: it runs there, clicking fast, and strikes at what it can hear or feel. */
	private class HuntGoal extends Goal {
		private int repath;

		HuntGoal() {
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true; // (vanilla ticks goals every other tick; its timers count real ticks)
		}

		@Override
		public boolean canUse() {
			return BlindOneEntity.this.prey != null && BlindOneEntity.this.alertTicks == 0;
		}

		@Override
		public void start() {
			this.repath = 0;
			BlindOneEntity.this.running = true;
		}

		@Override
		public void stop() {
			BlindOneEntity.this.running = false;
		}

		@Override
		public void tick() {
			BlindOneEntity self = BlindOneEntity.this;
			Player target = self.prey;
			Vec3 heard = self.noisePos;
			if (target == null || heard == null) {
				return;
			}
			self.getLookControl().setLookAt(heard.x, heard.y + 1.0, heard.z);
			if (--this.repath <= 0) {
				this.repath = 5;
				self.getNavigation().moveTo(heard.x, heard.y, heard.z, HUNT_SPEED);
			}
			if (self.noiseAge < 30 || self.getBoundingBox().inflate(0.6).intersects(target.getBoundingBox())) {
				self.tryAttack(target);
			}
		}
	}

	/**
	 * A noise: it creeps towards it - quick from far away, slow and careful as it gets close - clicking, then
	 * listens around the spot for a while and lets it go.
	 */
	private class StalkGoal extends Goal {
		private final StuckWatch stuck = new StuckWatch();
		@Nullable
		private Vec3 target;
		private int moveTicks;
		private int lingerTicks;

		StalkGoal() {
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true; // (vanilla ticks goals every other tick; its timers count real ticks)
		}

		@Override
		public boolean canUse() {
			return BlindOneEntity.this.noisePos != null && BlindOneEntity.this.prey == null && BlindOneEntity.this.alertTicks == 0;
		}

		@Override
		public boolean canContinueToUse() {
			return BlindOneEntity.this.noisePos != null && BlindOneEntity.this.prey == null;
		}

		@Override
		public void start() {
			this.target = null;
			this.lingerTicks = 0;
		}

		@Override
		public void stop() {
			BlindOneEntity.this.running = false;
			BlindOneEntity.this.getNavigation().stop();
		}

		@Override
		public void tick() {
			BlindOneEntity self = BlindOneEntity.this;
			if (self.alertTicks > 0 || self.noisePos == null) {
				return;
			}
			if (self.noisePos != this.target) {
				this.target = self.noisePos;
				this.moveTicks = 0;
				this.lingerTicks = 0;
				this.stuck.reset();
			}
			if (this.lingerTicks > 0) {
				// standing where the noise was, head sweeping, clicking: then it lets go
				float yaw = self.getYRot() + (float) Math.sin(self.tickCount * 0.12) * 80.0f;
				double rad = Math.toRadians(yaw);
				self.getLookControl().setLookAt(self.getX() - Math.sin(rad) * 3, self.getEyeY() - 0.3, self.getZ() + Math.cos(rad) * 3);
				if (--this.lingerTicks == 0) {
					self.noisePos = null;
				}
				return;
			}
			this.moveTicks++;
			double distance = self.position().distanceTo(this.target);
			// far: hurry; loud and near: rush; otherwise the last few blocks slowly, carefully
			self.running = self.noiseStrength > 0.6f && distance < 10.0;
			double speed = self.running ? RUN_SPEED : distance > 8.0 ? WALK_SPEED : CREEP_SPEED;
			if (this.moveTicks % 10 == 1) {
				self.getNavigation().moveTo(this.target.x, this.target.y, this.target.z, speed);
			}
			self.getLookControl().setLookAt(this.target.x, this.target.y + 1.0, this.target.z);
			boolean arrived = self.position().distanceToSqr(this.target) < 2.25;
			boolean stuck = this.moveTicks > 20 && self.getNavigation().isDone() && !arrived || this.stuck.check();
			if (arrived || stuck || this.moveTicks > 400) {
				self.getNavigation().stop();
				self.running = false;
				this.lingerTicks = 60;
			}
		}
	}

	/**
	 * Lost you: to where you were, on a few blocks the way you were going, then through the rooms around,
	 * clicking - and it taps every wardrobe it passes. Then it screams and gives up.
	 */
	private class SearchGoal extends Goal {
		private final StuckWatch stuck = new StuckWatch();
		private final List<Vec3> spots = new ArrayList<>();
		/** Spots in front of a wardrobe: it stops there and listens. */
		private final List<Vec3> wardrobeSpots = new ArrayList<>();
		private int moveTicks;
		private int listenAtWardrobe;
		private boolean atWardrobe;

		SearchGoal() {
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true; // (vanilla ticks goals every other tick; its timers count real ticks)
		}

		@Override
		public boolean canUse() {
			BlindOneEntity self = BlindOneEntity.this;
			return self.searchTicks > 0 && self.searchCenter != null && self.prey == null && self.noisePos == null;
		}

		@Override
		public boolean canContinueToUse() {
			BlindOneEntity self = BlindOneEntity.this;
			return self.searchTicks > 0 && self.prey == null && self.noisePos == null;
		}

		@Override
		public void start() {
			BlindOneEntity self = BlindOneEntity.this;
			this.spots.clear();
			this.wardrobeSpots.clear();
			this.spots.add(self.searchCenter);
			// the wardrobes around first, then a couple of random places nearby
			BlockPos center = BlockPos.containing(self.searchCenter);
			for (BlockPos pos : BlockPos.betweenClosed(center.offset(-7, -1, -7), center.offset(7, 1, 7))) {
				BlockState state = self.level().getBlockState(pos);
				if (state.is(ModRegistry.WARDROBE) && state.getValue(com.lasttrain.block.WardrobeBlock.HALF)
						== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
					net.minecraft.core.Direction facing = state.getValue(com.lasttrain.block.WardrobeBlock.FACING);
					Vec3 front = Vec3.atBottomCenterOf(pos.relative(facing));
					this.spots.add(front);
					this.wardrobeSpots.add(front);
				}
			}
			for (int i = 0; i < 2; i++) {
				this.spots.add(self.searchCenter.add(self.random.nextInt(11) - 5, 0, self.random.nextInt(11) - 5));
			}
			this.next();
		}

		private void next() {
			BlindOneEntity self = BlindOneEntity.this;
			if (this.spots.isEmpty()) {
				self.searchTicks = 0;
				return;
			}
			Vec3 spot = this.spots.remove(0);
			this.atWardrobe = this.wardrobeSpots.contains(spot);
			this.listenAtWardrobe = 0;
			this.moveTicks = 0;
			this.stuck.reset();
			self.getNavigation().moveTo(spot.x, spot.y, spot.z, CREEP_SPEED + 0.1);
		}

		@Override
		public void tick() {
			BlindOneEntity self = BlindOneEntity.this;
			this.moveTicks++;
			if (this.listenAtWardrobe > 0) {
				// standing at the doors, head against them, tapping, listening
				if (--this.listenAtWardrobe == 0) {
					this.next();
				}
			}
			// a wardrobe right here? tap the doors and listen for breathing
			for (Player player : self.level().getEntitiesOfClass(Player.class, self.getBoundingBox().inflate(4.0), EntitySelector.NO_CREATIVE_OR_SPECTATOR)) {
				BlockPos wardrobe = HideManager.spotOf(player);
				if (player instanceof ServerPlayer hidden && wardrobe != null && self.tickCount % 10 == 0
						&& Vec3.atBottomCenterOf(wardrobe).distanceTo(self.position()) < 3.5) { // (paths end a block short)
					// breathing right behind the doors it is listening at: it will hear it; holding your breath, it almost never does
					float chance = BreathManager.isHolding(hidden) ? 0.02f : 0.5f + 0.08f * GameSession.anger(self.level());
					self.wardrobeTaps++;
					self.playSound(ModSounds.BLIND_CLICK, 1.2f, 0.7f);
					if (self.random.nextFloat() < chance) {
						self.dragOut(hidden);
						return;
					}
				}
			}
			if (this.listenAtWardrobe == 0 && (self.getNavigation().isDone() || this.stuck.check() || this.moveTicks > 200)) {
				if (this.atWardrobe) {
					this.atWardrobe = false;
					this.listenAtWardrobe = 60;
					self.getNavigation().stop();
				} else {
					this.next();
				}
			}
		}

		@Override
		public void stop() {
			BlindOneEntity self = BlindOneEntity.this;
			self.getNavigation().stop();
			if (self.prey == null && self.noisePos == null) {
				// nothing: a scream of frustration, and back to wandering
				self.playSound(ModSounds.BLIND_SCREAM, 1.4f, 0.8f);
				self.searchTicks = 0;
				self.searchCenter = null;
				self.lastKnown = null;
			}
		}
	}

	/** Sometimes it just stops in a doorway or a corner and waits, silent, head bowed. You may walk right into it. */
	private class LurkGoal extends Goal {
		LurkGoal() {
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true; // (vanilla ticks goals every other tick; its timers count real ticks)
		}

		@Override
		public boolean canUse() {
			BlindOneEntity self = BlindOneEntity.this;
			return self.lurkTicks > 0 && self.prey == null && self.noisePos == null && self.searchTicks == 0;
		}

		@Override
		public boolean canContinueToUse() {
			return this.canUse();
		}

		@Override
		public void start() {
			BlindOneEntity.this.getNavigation().stop();
		}

		@Override
		public void tick() {
			BlindOneEntity.this.lurkTicks--;
		}
	}

	/**
	 * Hearing nothing, it walks the house from room to room and down the galleries, opening doors, lingering
	 * a little - more often towards the places where it has caught people before. Now and then it stops to lurk.
	 */
	private class WanderGoal extends Goal {
		private final StuckWatch stuck = new StuckWatch();
		private int cooldown = 40;
		private int ticks;
		@Nullable
		private BlockPos target;

		WanderGoal() {
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true; // (vanilla ticks goals every other tick; its timers count real ticks)
		}

		private boolean quiet() {
			BlindOneEntity self = BlindOneEntity.this;
			return self.noisePos == null && self.prey == null && self.alertTicks == 0 && self.searchTicks == 0 && self.lurkTicks == 0;
		}

		@Override
		public boolean canUse() {
			if (!this.quiet() || --this.cooldown > 0) {
				return false;
			}
			BlindOneEntity self = BlindOneEntity.this;
			List<BlockPos> points = GameSession.patrolPoints(self.level());
			if (points.isEmpty()) {
				return false;
			}
			BlockPos here = self.blockPosition();
			boolean onStairs = false;
			for (BlockPos landing : GameSession.stairLandings(self.level())) {
				if (landing.distSqr(here) <= 4 * 4) {
					onStairs = true;
				}
			}
			// back to where it once caught somebody?
			if (!self.hotSpots.isEmpty() && self.random.nextFloat() < 0.35f) {
				BlockPos spot = self.hotSpots.stream().skip(self.random.nextInt(self.hotSpots.size())).findFirst().orElseThrow();
				BlockPos nearest = null;
				for (BlockPos point : points) {
					if ((nearest == null || point.distSqr(spot) < nearest.distSqr(spot))
							&& (Math.abs(point.getY() - here.getY()) < 3 || onStairs) && point.distSqr(here) > 6 * 6) {
						nearest = point;
					}
				}
				if (nearest != null) {
					this.target = nearest;
					return true;
				}
			}
			for (int attempt = 0; attempt < 16; attempt++) {
				BlockPos candidate = points.get(self.random.nextInt(points.size()));
				double distance = candidate.distToCenterSqr(self.position());
				boolean sameFloor = Math.abs(candidate.getY() - here.getY()) < 3;
				if (distance > 8 * 8 && distance < 40 * 40 && (sameFloor || onStairs)) {
					this.target = candidate;
					return true;
				}
			}
			this.cooldown = 10;
			return false;
		}

		@Override
		public void start() {
			this.ticks = 0;
			this.stuck.reset();
			BlindOneEntity.this.running = false;
			if (!BlindOneEntity.this.getNavigation().moveTo(this.target.getX() + 0.5, this.target.getY(), this.target.getZ() + 0.5, WALK_SPEED * 0.85)) {
				this.target = null; // no way there from here: pick another place straight away
			}
		}

		@Override
		public boolean canContinueToUse() {
			return this.quiet() && this.target != null && !BlindOneEntity.this.getNavigation().isDone() && this.ticks < 20 * 30;
		}

		@Override
		public void tick() {
			this.ticks++;
			if (this.stuck.check()) {
				BlindOneEntity.this.getNavigation().stop(); // try another room
			}
		}

		@Override
		public void stop() {
			BlindOneEntity self = BlindOneEntity.this;
			self.getNavigation().stop();
			boolean arrived = this.target != null;
			this.target = null;
			if (arrived && this.quiet() && self.random.nextFloat() < 0.15f) {
				self.lurkTicks = 20 * (15 + self.random.nextInt(20)); // stand here and wait, silent
				this.cooldown = 5;
				return;
			}
			// linger a while where it ended up before the next room (no pause if it never set off)
			this.cooldown = arrived ? 40 + self.random.nextInt(120) : 5;
		}
	}
}
