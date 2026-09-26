package com.lasttrain.client.render;

import com.lasttrain.LastTrain;
import com.lasttrain.entity.BlindOneEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class BlindOneRenderer extends GeoEntityRenderer<BlindOneEntity> {
	public BlindOneRenderer(EntityRendererProvider.Context context) {
		super(context, new AdditiveHeadGeoModel<>(LastTrain.id("blind_one")));
		this.shadowRadius = 0.5f;
	}
}
