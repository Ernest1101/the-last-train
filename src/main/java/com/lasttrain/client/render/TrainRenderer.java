package com.lasttrain.client.render;

import com.lasttrain.LastTrain;
import com.lasttrain.entity.TrainEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

public class TrainRenderer extends GeoEntityRenderer<TrainEntity> {
	public TrainRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<>(LastTrain.id("train")));
		this.addRenderLayer(new AutoGlowingGeoLayer<>(this));
		this.shadowRadius = 0.0f;
	}

	@Override
	protected void applyRotations(TrainEntity train, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick) {
		// GeckoLib only passes a body rotation for living entities (0 otherwise), so use the train's own yaw
		super.applyRotations(train, poseStack, ageInTicks, Mth.rotLerp(partialTick, train.yRotO, train.getYRot()), partialTick);
	}
}
