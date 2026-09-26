package com.lasttrain.client.render;

import com.lasttrain.LastTrain;
import com.lasttrain.entity.WatcherEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

public class WatcherRenderer extends GeoEntityRenderer<WatcherEntity> {
	public WatcherRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<>(LastTrain.id("watcher"), true));
		this.addRenderLayer(new AutoGlowingGeoLayer<>(this)); // the eyes glow in the dark
		this.shadowRadius = 0.0f;
	}
}
