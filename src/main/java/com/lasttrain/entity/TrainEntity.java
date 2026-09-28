package com.lasttrain.entity;

import com.lasttrain.game.GameSession;
import com.lasttrain.game.HouseBuilder.TrainGeometry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * The last train. Rolls in along +X, brakes to a stop at the platform, waits, then leaves.
 * Players board it by right-clicking it or by climbing onto it while it stands.
 * The model is ~25 blocks long; the entity position is the middle of the passenger wagon.
 */
public class TrainEntity extends Entity implements GeoEntity {
	public enum Phase { ARRIVING, STOPPED, DEPARTING }

	private static final EntityDataAccessor<Boolean> MOVING = SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.BOOLEAN);
	private static final RawAnimation MOVE = RawAnimation.begin().thenLoop("animation.train.move");
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.train.idle");

	public static final int WAIT_TICKS = 20 * 20;
	private static final double MAX_SPEED = 1.1;
	private static final double BRAKE = 0.006;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	private Phase phase = Phase.ARRIVING;
	private double stopX;
	private double speed = MAX_SPEED;
	private int timer;
	private double departStartX;
	private boolean boarded;
	private boolean resultSent;

	public TrainEntity(EntityType<?> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setYRot(-90.0f);
		this.yRotO = -90.0f;
	}

	/** The train always travels towards +X and stops with its wagon door at {@code stopX}. */
	public void setStopX(double stopX) {
		this.stopX = stopX;
	}

	public Phase getPhase() {
		return this.phase;
	}

	@Override
	protected void defineSynchedData() {
		this.entityData.define(MOVING, true);
	}

	@Override
	public void tick() {
		super.tick();
		this.setYRot(-90.0f);
		if (this.level().isClientSide) {
			return;
		}
		switch (this.phase) {
			case ARRIVING -> {
				double distance = this.stopX - this.getX();
				this.speed = Math.min(this.speed, Math.max(0.03, Math.sqrt(2.0 * BRAKE * Math.max(distance, 0.0))));
				if (distance <= this.speed) {
					this.setPos(this.stopX, this.getY(), this.getZ());
					this.speed = 0.0;
					this.phase = Phase.STOPPED;
					this.timer = com.lasttrain.config.LastTrainConfig.get().effectiveTrainWaitSeconds() * 20;
					this.playSound(SoundEvents.BELL_BLOCK, 3.0f, 0.7f);
				} else {
					this.moveAlong();
				}
			}
			case STOPPED -> {
				this.timer--;
				this.boardClimbers();
				if (this.boarded && this.timer > 60) {
					this.timer = 60; // someone got on - close the doors
				}
				if (this.timer == 100 || this.timer == 60) {
					this.playSound(SoundEvents.BELL_BLOCK, 3.0f, 0.5f);
				}
				if (this.timer <= 0) {
					this.phase = Phase.DEPARTING;
					this.departStartX = this.getX();
					this.playSound(SoundEvents.IRON_DOOR_CLOSE, 3.0f, 0.5f);
					GameSession.onTrainDeparting(this);
				}
			}
			case DEPARTING -> {
				if (this.speed < 0.3) {
					this.boardClimbers(); // last chance to jump on
				}
				this.speed = Math.min(1.6, this.speed + 0.01);
				this.moveAlong();
				double travelled = this.getX() - this.departStartX;
				if (!this.resultSent && travelled > 40.0) {
					this.resultSent = true;
					List<ServerPlayer> riders = this.getPassengers().stream()
							.filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast).toList();
					GameSession.onTrainLeft(this, riders);
				}
				if (travelled > 260.0) {
					this.ejectPassengers();
					this.discard();
				}
			}
		}
		this.entityData.set(MOVING, this.speed > 0.01);
		if (this.speed > 0.05 && this.tickCount % 8 == 0) {
			this.playSound(SoundEvents.MINECART_RIDING, (float) Math.min(2.5, this.speed * 2.5), 0.45f);
		}
	}

	private void moveAlong() {
		this.setPos(this.getX() + this.speed, this.getY(), this.getZ());
	}

	/** Anybody standing on or hanging onto the train while it's (almost) stopped gets on. */
	private void boardClimbers() {
		for (Player player : this.level().getEntitiesOfClass(Player.class, this.getTrainBox().inflate(0.4), EntitySelector.NO_SPECTATORS)) {
			if (!player.isPassenger() && player instanceof ServerPlayer serverPlayer) {
				this.board(serverPlayer);
			}
		}
	}

	/** The full length of the train (the entity hitbox only covers the wagon's middle). */
	public AABB getTrainBox() {
		double x = this.getX();
		return new AABB(x + TrainGeometry.BACK, this.getY(), this.getZ() - 1.6, x + TrainGeometry.FRONT, this.getY() + 3.8, this.getZ() + 1.6);
	}

	/** Seats the player in the wagon if the train is (almost) standing still. */
	public void board(ServerPlayer player) {
		if (player.getVehicle() == this || this.phase == Phase.ARRIVING || (this.phase == Phase.DEPARTING && this.speed >= 0.3) || this.resultSent) {
			return;
		}
		if (player.startRiding(this, true)) {
			this.boarded = true;
			this.playSound(SoundEvents.IRON_DOOR_OPEN, 2.0f, 0.7f);
			GameSession.onBoarded(player, this);
		}
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
			this.board(serverPlayer);
		}
		return InteractionResult.sidedSuccess(this.level().isClientSide);
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().size() < 8;
	}

	@Override
	public double getPassengersRidingOffset() {
		return 1.35; // wagon floor is 1 block above the rails
	}

	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction move) {
		// spread passengers along the wagon instead of stacking them in the middle
		int index = this.getPassengers().indexOf(passenger);
		double along = index < 0 ? 0.0 : ((index % 4) - 1.5) * 1.6;
		double side = index < 4 ? -0.6 : 0.6;
		move.accept(passenger, this.getX() - along, this.getY() + this.getPassengersRidingOffset() + passenger.getMyRidingOffset(),
				this.getZ() + side);
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public AABB getBoundingBoxForCulling() {
		return this.getTrainBox().inflate(2.0);
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 320.0 * 320.0;
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	public Packet<ClientGamePacketListener> getAddEntityPacket() {
		return new ClientboundAddEntityPacket(this);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "wheels", 10, state ->
				state.setAndContinue(this.entityData.get(MOVING) ? MOVE : IDLE)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}

	@Override
	public void playSound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
		this.level().playSound(null, this.getX(), this.getY(), this.getZ(), sound, SoundSource.NEUTRAL, volume, pitch);
	}
}
