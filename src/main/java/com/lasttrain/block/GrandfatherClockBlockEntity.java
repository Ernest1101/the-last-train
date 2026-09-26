package com.lasttrain.block;

import com.lasttrain.registry.ModRegistry;
import com.lasttrain.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** The swinging pendulum (GeckoLib) and the tick-tock you hear from a few rooms away. */
public class GrandfatherClockBlockEntity extends BlockEntity implements GeoBlockEntity {
	private static final RawAnimation SWING = RawAnimation.begin().thenLoop("animation.grandfather_clock.swing");
	/** clock.ogg is one tick and one tock, two seconds long. */
	private static final int SOUND_PERIOD = 40;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public GrandfatherClockBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.CLOCK_ENTITY, pos, state);
	}

	public static void clientTick(Level level, BlockPos pos, BlockState state, GrandfatherClockBlockEntity clock) {
		if (level.getGameTime() % SOUND_PERIOD == Math.floorMod(pos.asLong(), SOUND_PERIOD)) {
			level.playLocalSound(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, ModSounds.CLOCK, SoundSource.BLOCKS, 0.45f, 1.0f, false);
		}
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "pendulum", 0, state -> state.setAndContinue(SWING)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.cache;
	}
}
