package com.lasttrain.entity;

import com.lasttrain.game.GameSession;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The Double: something wearing a player's face. It copies one player (a friend, or you when you are alone)
 * and follows whoever it haunts around the house: it stands still while you look at it and creeps closer
 * when you don't, and it calls out in your friend's name. Get too close and it drops the act. It shrieks,
 * and the Blind One hears that.
 */
public class DoppelgangerEntity extends PathfinderMob {
	private static final EntityDataAccessor<Optional<UUID>> COPY = SynchedEntityData.defineId(DoppelgangerEntity.class, EntityDataSerializers.OPTIONAL_UUID);
	/** Counts down while it shrieks, then it is gone. 0 while it pretends. */
	private static final EntityDataAccessor<Integer> REVEAL = SynchedEntityData.defineId(DoppelgangerEntity.class, EntityDataSerializers.INT);
	public static final int REVEAL_LENGTH = 24;
	public static final int LIFETIME = 20 * 120;
	public static final float REVEAL_DISTANCE = 3.5f;
	/** How loud the shriek is, as a noise radius for the Blind One. */
	public static final float SHRIEK_RADIUS = 26.0f;
	/** It keeps about this far away, like someone waiting for you to catch up. */
	private static final float KEEP_DISTANCE = 6.0f;
	private static final double LOOK_THRESHOLD = 0.94;
	public static final int CALLS = 6;

	@Nullable
	private UUID haunted;
	private int repath;
	private int callCooldown = 160;

	public DoppelgangerEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setInvulnerable(true);
		this.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
		if (this.getNavigation() instanceof GroundPathNavigation navigation) {
			navigation.setCanOpenDoors(true);
		}
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.28)
				.add(Attributes.FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(COPY, Optional.empty());
		this.entityData.define(REVEAL, 0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
	}

	/** Puts on {@code copied}'s face (skin, name, what they hold) and starts following {@code haunted}. */
	public void imitate(Player copied, Player haunted) {
		this.entityData.set(COPY, Optional.of(copied.getUUID()));
		this.haunted = haunted.getUUID();
		if (copied != haunted) {
			// a friend's name tag over their head, as players have
			this.setCustomName(copied.getName());
			this.setCustomNameVisible(true);
		}
		this.setItemSlot(EquipmentSlot.MAINHAND, copied.getMainHandItem().copyWithCount(1));
	}

	/** Whose skin it wears. */
	public Optional<UUID> copied() {
		return this.entityData.get(COPY);
	}

	@Nullable
	public UUID haunted() {
		return this.haunted;
	}

	/** Ticks left of the shriek, 0 while it still pretends. */
	public int revealTicks() {
		return this.entityData.get(REVEAL);
	}

	public boolean isRevealed() {
		return this.revealTicks() > 0;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide) {
			return;
		}
		int reveal = this.revealTicks();
		if (reveal > 0) {
			this.getNavigation().stop();
			this.entityData.set(REVEAL, reveal - 1);
			if (reveal == 1) {
				((ServerLevel) this.level()).sendParticles(ParticleTypes.LARGE_SMOKE, this.getX(), this.getY() + 1.0, this.getZ(),
						30, 0.3, 0.6, 0.3, 0.02);
				this.discard();
			}
			return;
		}
		Player target = this.haunted != null ? this.level().getPlayerByUUID(this.haunted) : null;
		if (target == null || !target.isAlive() || target.isSpectator() || this.tickCount > LIFETIME) {
			this.discard(); // nobody left to fool
			return;
		}
		this.getLookControl().setLookAt(target, 30.0f, 30.0f);
		if (this.distanceTo(target) < REVEAL_DISTANCE) {
			this.reveal(target);
			return;
		}
		boolean seen = this.isLookedAtBy(target);
		if (seen) {
			this.getNavigation().stop(); // just a friend standing in the corridor
		} else if (--this.repath <= 0) {
			this.repath = 10;
			boolean targetSafe = GameSession.safeRoomAt(this.level(), target.position()) != null;
			if (this.distanceTo(target) > KEEP_DISTANCE && !targetSafe) {
				this.getNavigation().moveTo(target, 1.0);
			} else {
				this.getNavigation().stop();
			}
		}
		if (!seen && --this.callCooldown <= 0) {
			this.callCooldown = 300 + this.random.nextInt(400);
			this.callOut(target);
		}
	}

	/** "Come here, I found the key": in the copied friend's name. Alone, you only hear a whisper. */
	private void callOut(Player target) {
		Component name = this.getCustomName();
		if (name != null) {
			target.sendSystemMessage(Component.literal("<").append(name).append("> ")
					.append(Component.translatable("message.lasttrain.doppel_call." + this.random.nextInt(CALLS)))
					.withStyle(ChatFormatting.WHITE));
		} else {
			this.level().playSound(null, this.getX(), this.getY(), this.getZ(), ModSounds.WHISPER, SoundSource.HOSTILE, 0.8f, 1.1f);
		}
	}

	/** Drops the act: a shriek in the player's face that the whole floor can hear. */
	public void reveal(Player target) {
		if (this.isRevealed()) {
			return;
		}
		this.entityData.set(REVEAL, REVEAL_LENGTH);
		this.setCustomNameVisible(false);
		this.getNavigation().stop();
		this.level().playSound(null, this.getX(), this.getY(), this.getZ(), ModSounds.DOPPEL_SCREAM, SoundSource.HOSTILE, 2.5f, 1.0f);
		if (target instanceof ServerPlayer player) {
			ModNetwork.sendDoppelScare(player, this.copied().orElse(player.getUUID()));
		}
		NoiseSystem.emit((ServerLevel) this.level(), this.position(), SHRIEK_RADIUS, null);
	}

	private boolean isLookedAtBy(Player player) {
		Vec3 eye = player.getEyePosition();
		Vec3 toMe = this.getEyePosition().subtract(eye);
		double distance = toMe.length();
		if (distance < 0.5) {
			return true;
		}
		double dot = player.getViewVector(1.0f).dot(toMe.scale(1.0 / distance));
		return dot > LOOK_THRESHOLD && player.hasLineOfSight(this);
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (!this.level().isClientSide && source.getEntity() instanceof Player player) {
			this.reveal(player); // hitting it is as good as walking up to it
		}
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
