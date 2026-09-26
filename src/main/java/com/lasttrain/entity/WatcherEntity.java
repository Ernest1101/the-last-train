package com.lasttrain.entity;

import com.lasttrain.network.ModNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A tall black figure that stands far away and stares. It can't be hurt and never moves.
 * The moment a player looks straight at it, it vanishes and appears right in their face
 * ("SOON THE END") - a scare only, it deals no damage.
 */
public class WatcherEntity extends PathfinderMob implements GeoEntity {
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.watcher.idle");
	/** Someone almost looks at it: the head slowly leans over and stays there. */
	private static final RawAnimation TILT = RawAnimation.begin().thenPlayAndHold("animation.watcher.tilt");
	private static final EntityDataAccessor<Boolean> NOTICED = SynchedEntityData.defineId(WatcherEntity.class, EntityDataSerializers.BOOLEAN);
	/** cos of the angle within which it knows it is being noticed (wider than the look that makes it vanish). */
	private static final double NOTICE_THRESHOLD = 0.8;
	/** cos of the angle within which the player counts as looking at it. */
	private static final double LOOK_THRESHOLD = 0.985;
	public static final int LIFETIME = 20 * 45;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public WatcherEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setNoAi(true);
		this.setInvulnerable(true);
		this.setSilent(true);
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(NOTICED, false);
	}

	public boolean isNoticed() {
		return this.entityData.get(NOTICED);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 100.0);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide) {
			return;
		}
		if (this.tickCount > LIFETIME) {
			this.discard();
			return;
		}
		Player nearest = this.level().getNearestPlayer(this, 64.0);
		if (nearest != null) {
			// always facing whoever is closest
			Vec3 d = nearest.position().subtract(this.position());
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
			this.setYRot(yaw);
			this.yHeadRot = yaw;
			this.yBodyRot = yaw;
		}
		if (this.tickCount < 20) {
			return;
		}
		for (Player player : this.level().getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(64.0), EntitySelector.NO_SPECTATORS)) {
			if (!this.isNoticed() && this.lookDot(player) > NOTICE_THRESHOLD && player.hasLineOfSight(this)) {
				this.entityData.set(NOTICED, true);
			}
			if (player instanceof ServerPlayer serverPlayer && this.isLookedAtBy(player)) {
				ModNetwork.sendWatcherScare(serverPlayer);
				this.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT,
						this.getSoundSource(), 0.6f, 0.5f);
				this.discard();
				return;
			}
		}
	}

	/** How straight {@code player} looks at it (1 = right at it). */
	private double lookDot(Player player) {
		Vec3 toMe = new Vec3(this.getX(), this.getY() + this.getBbHeight() * 0.8, this.getZ()).subtract(player.getEyePosition());
		double distance = toMe.length();
		return distance < 0.5 ? 1.0 : player.getViewVector(1.0f).dot(toMe.scale(1.0 / distance));
	}

	private boolean isLookedAtBy(Player player) {
		Vec3 eye = player.getEyePosition();
		Vec3 toMe = new Vec3(this.getX(), this.getY() + this.getBbHeight() * 0.8, this.getZ()).subtract(eye);
		double distance = toMe.length();
		if (distance < 0.5) {
			return true;
		}
		double dot = player.getViewVector(1.0f).dot(toMe.scale(1.0 / distance));
		return dot > LOOK_THRESHOLD && player.hasLineOfSight(this);
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
		return true;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 10, state -> state.setAndContinue(this.isNoticed() ? TILT : IDLE)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}
}
