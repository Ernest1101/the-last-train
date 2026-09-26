package com.lasttrain.game;

import com.lasttrain.entity.BlindOneEntity;
import com.lasttrain.entity.DollEntity;
import com.lasttrain.registry.ModRegistry;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * GameTests for the bugs found in review. Run with {@code ./gradlew runGametest}.
 * Every test has its own batch so they run one after another (they share the curse and session state),
 * and builds its house far away from the others.
 */
public class LastTrainGameTests implements FabricGameTest {
	private static BlockPos far(int index) {
		return new BlockPos(20000 + index * 1200, 120, 20000);
	}

	private static void cleanUp(MinecraftServer server, BlockPos around) {
		GameSession.stop(server);
		for (ServerLevel level : server.getAllLevels()) {
			AABB area = new AABB(around).inflate(400, 80, 120);
			level.getEntitiesOfClass(BlindOneEntity.class, area).forEach(Entity::discard);
			level.getEntitiesOfClass(DollEntity.class, area).forEach(Entity::discard);
		}
	}

	private static void removePlayer(MinecraftServer server, ServerPlayer player) {
		CurseManager.data(server).stash.remove(player.getUUID());
		server.getPlayerList().remove(player);
	}

	/** Bug 3: rebuilding the house (relog, /lasttrain start twice) used to leave the old monster and doll behind. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_1_rebuild", timeoutTicks = 100)
	public void rebuildingTheHouseKeepsOneMonster(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = far(0);
		GameSession.start(level, feet, List.of());
		GameSession.start(level, feet, List.of());
		AABB area = new AABB(feet).inflate(90, 30, 60);
		int monsters = level.getEntitiesOfClass(BlindOneEntity.class, area).size();
		int dolls = level.getEntitiesOfClass(DollEntity.class, area).size();
		cleanUp(level.getServer(), feet);
		helper.assertTrue(monsters == 1, "expected 1 Blind One after rebuilding the house, found " + monsters);
		helper.assertTrue(dolls == 1, "expected 1 doll after rebuilding the house, found " + dolls);
		helper.succeed();
	}

	/** Bug 4: /lasttrain start set the time to 18000 absolute, throwing the curse back to day one. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_2_daycount", timeoutTicks = 100)
	public void manualGameKeepsTheDayCount(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		long before = level.getDayTime();
		level.setDayTime(2 * 24000L + 3000L); // morning of the third day
		BlockPos feet = far(1);
		GameSession.start(level, feet, List.of());
		long after = level.getDayTime();
		cleanUp(level.getServer(), feet);
		level.setDayTime(before);
		helper.assertTrue(after / 24000L == 2, "the day changed: day time " + after + " is day " + (after / 24000L + 1));
		helper.assertTrue(after % 24000L == 18000L, "not midnight: " + after % 24000L);
		helper.succeed();
	}

	/** Bugs 5 and 8: the basement and the attic belong to the house; the train starts inside the simulation distance. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_3_layout", timeoutTicks = 100)
	public void layoutBoundsAndTrainDistance(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HouseBuilder.Layout layout = HouseBuilder.build(level, far(2), 1);
		BlockPos o = layout.origin();
		boolean basement = layout.isInsideHouse(Vec3.atBottomCenterOf(o.offset(40, -5, 5)));
		boolean attic = layout.isInsideHouse(Vec3.atBottomCenterOf(o.offset(20, 13, 5)));
		boolean path = layout.isInsideHouse(Vec3.atBottomCenterOf(o.offset(30, 1, -5)));
		double trainDistance = layout.trainStopX() - layout.trainStart().x;
		helper.assertTrue(basement, "the basement counts as outside the house");
		helper.assertTrue(attic, "the attic counts as outside the house");
		helper.assertTrue(!path, "the path in front of the door counts as inside the house");
		// 8 chunks: inside even the smallest usual simulation distance
		helper.assertTrue(trainDistance <= 128.0, "the train starts " + trainDistance + " blocks away, may be in non-ticking chunks");
		helper.succeed();
	}

	/** Bug 5 in play: the fuse opens the door while you're in the basement - that must not count as escaping. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_4_basement", timeoutTicks = 100)
	public void standingInTheBasementIsNotEscaping(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos feet = far(3);
		GameSession.start(level, feet, List.of(player));
		GameSession session = GameSession.current();
		HouseBuilder.Layout layout = session.layout();
		BlockPos door = layout.exitDoor();
		BlockState state = level.getBlockState(door);
		((DoorBlock) state.getBlock()).setOpen(null, level, state, door, true);
		BlockPos basement = layout.origin().offset(40, -5, 5);
		player.teleportTo(level, basement.getX() + 0.5, basement.getY(), basement.getZ() + 0.5, 0.0f, 0.0f);
		helper.runAfterDelay(10, () -> {
			GameSession.Stage stage = GameSession.current() == session ? session.stage() : null;
			cleanUp(server, feet);
			removePlayer(server, player);
			helper.assertTrue(stage == GameSession.Stage.IN_HOUSE, "being in the basement started the escape (stage " + stage + ")");
			helper.succeed();
		});
	}

	/**
	 * Bug 7: a new game starting while a manual overworld game runs left daylight and mob spawning off.
	 * (The test server has no datapack dimensions, so the second game runs in the Nether instead of the nightmare.)
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_5_rules", timeoutTicks = 100)
	public void newGameRestoresTheRules(GameTestHelper helper) {
		ServerLevel overworld = helper.getLevel();
		MinecraftServer server = overworld.getServer();
		ServerLevel nether = server.getLevel(Level.NETHER);
		BlockPos feet = far(4);
		GameSession.start(overworld, feet, List.of());
		boolean offDuringManual = !overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
		GameSession.start(nether, feet, List.of());
		boolean daylight = overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
		boolean mobs = overworld.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
		cleanUp(server, feet);
		overworld.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
		overworld.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, server);
		helper.assertTrue(offDuringManual, "the manual game didn't turn the daylight cycle off");
		helper.assertTrue(daylight, "the daylight cycle stayed off after another game started");
		helper.assertTrue(mobs, "mob spawning stayed off after another game started");
		helper.succeed();
	}

	/**
	 * Bug 1: players in the Nether on the third night were left behind. The test server has no nightmare
	 * dimension, so this checks that the curse reaches them: the death sequence protection is applied.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_6_nether", timeoutTicks = 40)
	public void theCurseReachesTheNether(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(server.getLevel(Level.NETHER), 0.5, 100.0, 0.5, 0.0f, 0.0f);
		CurseManager.trigger(server);
		boolean reached = player.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		CurseManager.reset(server);
		CurseManager.clearRuntime();
		removePlayer(server, player);
		helper.assertTrue(reached, "the third night didn't reach a player in the Nether");
		helper.succeed();
	}

	/** Bug 2: going home while dead gave the stash to the dead body, and the respawn dropped it. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_7_dead", timeoutTicks = CurseManager.RETURN_DELAY + 200)
	public void deadPlayersGetTheirThingsBack(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID id = player.getUUID();
		player.getInventory().add(new ItemStack(Items.DIAMOND, 7));
		CurseManager.stash(server, player);
		player.kill();
		CurseManager.onGameOver(player);
		helper.runAfterDelay(CurseManager.RETURN_DELAY + 20, () -> {
			ServerPlayer respawned = server.getPlayerList().respawn(player, false);
			helper.runAfterDelay(10, () -> {
				ServerPlayer current = server.getPlayerList().getPlayer(id);
				int diamonds = current == null ? -1 : current.getInventory().countItem(Items.DIAMOND);
				removePlayer(server, current != null ? current : respawned);
				helper.assertTrue(diamonds == 7, "after dying and respawning the player has " + diamonds + " of 7 stashed diamonds");
				helper.succeed();
			});
		});
	}

	/** Bug 6: the battery ignored a flashlight held in the off hand. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lasttrain_8_battery", timeoutTicks = 20)
	public void batteryChargesTheOffhandFlashlight(GameTestHelper helper) {
		Player player = helper.makeMockPlayer();
		ItemStack light = new ItemStack(ModRegistry.FLASHLIGHT);
		light.setDamageValue(1000);
		player.setItemInHand(InteractionHand.OFF_HAND, light);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModRegistry.BATTERY));
		ModRegistry.BATTERY.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(player.getOffhandItem().getDamageValue() == 0,
				"the off-hand flashlight still has " + player.getOffhandItem().getDamageValue() + " damage");
		helper.succeed();
	}
}
