package net.stardance;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import net.stardance.interaction.GridInteractionHandler;
import net.stardance.item.ModItems;
import net.stardance.physics.EngineManager;
import net.stardance.utils.BlockEventHandler;
import net.stardance.utils.CommandRegistry;
import net.stardance.utils.SchemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod entry point for Stardance. Sets up items, commands, and the
 * physics engine manager, as well as event handlers for block interactions
 * and world ticks.
 */
public class Stardance implements ModInitializer {

	public static final String MOD_ID = "stardance";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Shared managers accessible throughout the mod
	public static final EngineManager engineManager = new EngineManager();
	public static final SchemManager schemManager = new SchemManager();

	// Interaction / server references
	private static final GridInteractionHandler gridInteractionHandler = new GridInteractionHandler();
	public static MinecraftServer serverInstance;

	// Packet identifiers - using standard Java constants
	public static final Identifier TRANSFORM_UPDATE_PACKET_ID = new Identifier(MOD_ID, "transform_update");
	public static final Identifier PHYSICS_STATE_UPDATE_PACKET_ID = new Identifier(MOD_ID, "physics_state_update");

	// --------------------------------------------------
	// MOD INITIALIZATION
	// --------------------------------------------------

	/**
	 * Runs once on mod initialization (client + server). Registers items,
	 * commands, event callbacks, etc.
	 */
	@Override
	public void onInitialize() {
		LOGGER.info("Initiating Stardance...");

		// Register any custom items, commands, etc.
		ModItems.registerItems();
		CommandRegistry.init();

		// Capture the server instance on startup
		ServerLifecycleEvents.SERVER_STARTED.register(server -> serverInstance = server);

		// Run logic at the end of each server tick
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);

		// Handle loading data for each loaded server world
		ServerWorldEvents.LOAD.register((server, world) -> {
			if (!world.isClient()) {
				loadServerWorld(server, world);
			}
		});

		// Hook into block use events
		UseBlockCallback.EVENT.register(this::handleUseBlock);
	}

	// --------------------------------------------------
	// PRIVATE / CALLBACK METHODS
	// --------------------------------------------------

	/**
	 * Called on block use actions (e.g., player right-click). Delegates to
	 * {@link GridInteractionHandler}.
	 */
	private ActionResult handleUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
		return gridInteractionHandler.onUseBlock(player, hand);
	}

	/**
	 * Called at the end of each server tick. Steps the engine manager
	 * to advance physics, etc.
	 */
	private void onEndServerTick(MinecraftServer server) {
		engineManager.tick(server);
	}

	/**
	 * Handles loading / reloading a given server world, e.g. setting up
	 * or refreshing physics engine state for that world.
	 */
	private void loadServerWorld(MinecraftServer server, ServerWorld world) {
		engineManager.load(world);
	}
}
