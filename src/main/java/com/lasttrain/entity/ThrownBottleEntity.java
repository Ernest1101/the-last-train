package com.lasttrain.entity;

import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/** A thrown bottle: smashes on impact with a loud noise that lures the Blind One away. */
public class ThrownBottleEntity extends ThrowableItemProjectile {
	private static final byte SMASH = 3;
	public static final float NOISE_RADIUS = 18.0f;

	public ThrownBottleEntity(EntityType<? extends ThrownBottleEntity> type, Level level) {
		super(type, level);
	}

	public ThrownBottleEntity(Level level, LivingEntity owner) {
		super(ModRegistry.THROWN_BOTTLE, owner, level);
	}

	@Override
	protected Item getDefaultItem() {
		return ModRegistry.THROWABLE_BOTTLE;
	}

	@Override
	protected void onHit(HitResult result) {
		super.onHit(result);
		if (this.level() instanceof ServerLevel level) {
			level.broadcastEntityEvent(this, SMASH);
			level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1.6f, 0.9f);
			// no source: the monster doesn't know who threw it, it just goes to look
			NoiseSystem.emit(level, this.position(), NOISE_RADIUS, null);
			this.discard();
		}
	}

	@Override
	public void handleEntityEvent(byte event) {
		if (event == SMASH) {
			ItemParticleOption particle = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(this.getDefaultItem()));
			for (int i = 0; i < 12; i++) {
				this.level().addParticle(particle, this.getX(), this.getY(), this.getZ(),
						(this.random.nextDouble() - 0.5) * 0.2, this.random.nextDouble() * 0.2, (this.random.nextDouble() - 0.5) * 0.2);
			}
		} else {
			super.handleEntityEvent(event);
		}
	}
}
