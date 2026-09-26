package com.lasttrain.client.render;

import com.lasttrain.LastTrain;
import com.lasttrain.block.GrandfatherClockBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/** The clock: GeckoLib swings the pendulum; the hands are set here from the time of day. */
public class GrandfatherClockRenderer extends GeoBlockRenderer<GrandfatherClockBlockEntity> {
	public GrandfatherClockRenderer(BlockEntityRendererProvider.Context context) {
		super(new DefaultedBlockGeoModel<>(LastTrain.id("grandfather_clock")) {
			@Override
			public void setCustomAnimations(GrandfatherClockBlockEntity clock, long instanceId, AnimationState<GrandfatherClockBlockEntity> state) {
				super.setCustomAnimations(clock, instanceId, state);
				if (clock.getLevel() == null) {
					return;
				}
				// day time 0 is 6 in the morning; one in-game hour is 1000 ticks
				double hours = (clock.getLevel().getDayTime() % 24000L) / 1000.0 + 6.0;
				double minuteTurn = (hours % 1.0) * Math.PI * 2.0;
				double hourTurn = ((hours % 12.0) / 12.0) * Math.PI * 2.0;
				CoreGeoBone hour = this.getAnimationProcessor().getBone("hand_hour");
				CoreGeoBone minute = this.getAnimationProcessor().getBone("hand_minute");
				if (hour != null) {
					hour.setRotZ((float) hourTurn);
				}
				if (minute != null) {
					minute.setRotZ((float) minuteTurn);
				}
			}
		});
	}
}
