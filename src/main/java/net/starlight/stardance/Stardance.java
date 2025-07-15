package net.starlight.stardance;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.item.ModItems;
import net.starlight.stardance.physics.EngineManager;
import net.starlight.stardance.utils.CommandRegistry;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.SchemManager;
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
	public static MinecraftServer serverInstance;

	// Packet identifiers - using standard Java constants
	public static final ResourceLocation TRANSFORM_UPDATE_PACKET_ID = new ResourceLocation(MOD_ID, "transform_update");
	public static final ResourceLocation PHYSICS_STATE_UPDATE_PACKET_ID = new ResourceLocation(MOD_ID, "physics_state_update");

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
//		CommandRegistry.init();
		CommandRegistry.registerServerCommands();

		// Capture the server instance on startup
		ServerLifecycleEvents.SERVER_STARTED.register(server -> serverInstance = server);

		// Run logic at the end of each server tick
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);

		// Handle loading data for each loaded server world
		ServerWorldEvents.LOAD.register((server, world) -> {
			if (!world.isClientSide()) {
				loadServerWorld(server, world);
			}
		});

		// Register world unload/stopping event for proper GridSpace cleanup
		ServerWorldEvents.UNLOAD.register((server, world) -> {engineManager.unload(world);});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {engineManager.shutdown();});
	}

	// --------------------------------------------------
	// PRIVATE / CALLBACK METHODS
	// --------------------------------------------------


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
	private void loadServerWorld(MinecraftServer server, ServerLevel world) {
		engineManager.load(world);
	}
}
