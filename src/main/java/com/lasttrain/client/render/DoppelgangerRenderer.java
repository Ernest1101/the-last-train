package com.lasttrain.client.render;

import com.lasttrain.entity.DoppelgangerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Draws the Double as the player it copies: their skin, their arm width, stretching and twitching when it shrieks. */
public class DoppelgangerRenderer extends HumanoidMobRenderer<DoppelgangerEntity, PlayerModel<DoppelgangerEntity>> {
	private final PlayerModel<DoppelgangerEntity> wide;
	private final PlayerModel<DoppelgangerEntity> slim;

	public DoppelgangerRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
		this.wide = this.model;
		this.slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
	}

	@Nullable
	private static PlayerInfo info(UUID uuid) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		return connection != null ? connection.getPlayerInfo(uuid) : null;
	}

	/** The skin of that player, or the default one for their UUID. */
	public static ResourceLocation skinOf(UUID uuid) {
		PlayerInfo info = info(uuid);
		return info != null ? info.getSkinLocation() : DefaultPlayerSkin.getDefaultSkin(uuid);
	}

	private static UUID copied(DoppelgangerEntity entity) {
		return entity.copied().orElse(entity.getUUID());
	}

	@Override
	public void render(DoppelgangerEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light) {
		PlayerInfo info = info(copied(entity));
		this.model = info != null && "slim".equals(info.getModelName()) ? this.slim : this.wide;
		super.render(entity, yaw, partialTicks, poseStack, buffers, light);
	}

	@Override
	public ResourceLocation getTextureLocation(DoppelgangerEntity entity) {
		return skinOf(copied(entity));
	}

	@Override
	protected void scale(DoppelgangerEntity entity, PoseStack poseStack, float partialTicks) {
		float s = 0.9375f; // players are drawn at this scale
		poseStack.scale(s, s, s);
		if (entity.isRevealed()) {
			float t = 1.0f - (entity.revealTicks() - partialTicks) / DoppelgangerEntity.REVEAL_LENGTH;
			poseStack.scale(1.0f - 0.2f * t, 1.0f + 0.5f * t, 1.0f - 0.2f * t);
		}
	}

	@Override
	protected void setupRotations(DoppelgangerEntity entity, PoseStack poseStack, float bob, float yaw, float partialTicks) {
		super.setupRotations(entity, poseStack, bob, yaw, partialTicks);
		if (entity.isRevealed()) {
			float twitch = (float) Math.sin((entity.tickCount + partialTicks) * 5.3) * 9.0f;
			poseStack.mulPose(Axis.ZP.rotationDegrees(twitch));
		}
	}
}
