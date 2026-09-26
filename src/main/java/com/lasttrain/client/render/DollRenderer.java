package com.lasttrain.client.render;

import com.lasttrain.LastTrain;
import com.lasttrain.entity.DollEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class DollRenderer extends GeoEntityRenderer<DollEntity> {
	public DollRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<>(LastTrain.id("doll")));
		this.shadowRadius = 0.2f;
	}
}
