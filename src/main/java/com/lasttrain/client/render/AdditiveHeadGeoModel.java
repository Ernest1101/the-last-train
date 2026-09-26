package com.lasttrain.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * Turns the "head" bone towards where the entity looks, on top of what the animation does with it.
 * (GeckoLib's own head turning replaces the animated rotation, which flattens head-heavy animations.)
 */
public class AdditiveHeadGeoModel<T extends GeoAnimatable> extends DefaultedEntityGeoModel<T> {
	public AdditiveHeadGeoModel(ResourceLocation assetSubpath) {
		super(assetSubpath, false);
	}

	@Override
	public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> animationState) {
		super.setCustomAnimations(animatable, instanceId, animationState);
		CoreGeoBone head = this.getAnimationProcessor().getBone("head");
		EntityModelData data = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
		if (head != null && data != null) {
			head.setRotX(head.getRotX() + data.headPitch() * Mth.DEG_TO_RAD);
			head.setRotY(head.getRotY() + data.netHeadYaw() * Mth.DEG_TO_RAD);
		}
	}
}
