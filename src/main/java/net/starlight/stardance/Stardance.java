package net.starlight.stardance;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.interaction.GridDetectionService;
import net.starlight.stardance.interaction.GridInteractionHandler;
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
		UseItemCallback.EVENT.register(this::handleUseItem);
		UseBlockCallback.EVENT.register(this::handleUseBlock);


		// Hook into block breaking events
		AttackBlockCallback.EVENT.register(this::handleAttackBlock);
		PlayerBlockBreakEvents.BEFORE.register(this::handleBlockBreakBefore);

		// Register world unload/stopping event for proper GridSpace cleanup
		ServerWorldEvents.UNLOAD.register((server, world) -> {engineManager.unload(world);});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {engineManager.shutdown();});
	}

	// --------------------------------------------------
	// PRIVATE / CALLBACK METHODS
	// --------------------------------------------------

	/**
	 * Handles player attacking blocks (left-click).
	 */
	private ActionResult handleAttackBlock(PlayerEntity player, World world, Hand hand, BlockPos pos, Direction direction) {
		if (world.isClient) {
			// On client, just check if we're targeting a grid to prevent client-side effects
			if (gridInteractionHandler.isTargetingGridBlock(player)) {
				return ActionResult.SUCCESS; // Consume the event
			}
			return ActionResult.PASS;
		}

		// Server-side: check if player is targeting a grid block
		if (gridInteractionHandler.isTargetingGridBlock(player)) {
			SLogger.log("Stardance","Player attacking grid block - intercepting");

			// For creative mode or instant breaking, break immediately
			if (player.isCreative()) {
				boolean handled = gridInteractionHandler.handleGridBlockBreaking(player, pos, direction);
				return handled ? ActionResult.SUCCESS : ActionResult.SUCCESS;
			}

			// For survival mode, we'll handle this in the BEFORE event
			// Just consume the attack to prevent vanilla effects
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	/**
	 * Handles block breaking progression (survival mode).
	 */
	private boolean handleBlockBreakBefore(World world, PlayerEntity player, BlockPos pos, BlockState state, net.minecraft.block.entity.BlockEntity blockEntity) {
		if (world.isClient) {
			return true; // Let client handle it normally
		}

		// Check if player is targeting a grid block
		if (gridInteractionHandler.isTargetingGridBlock(player)) {
			SLogger.log("Stardance","Player breaking grid block - intercepting");

			// Handle the grid block breaking
			boolean handled = gridInteractionHandler.handleGridBlockBreaking(player, pos, null);

			// Return false to cancel vanilla block breaking
			return false;
		}

		// Not targeting a grid, let vanilla handle it
		return true;
	}

	/**
	 * Called on block use actions (e.g., player right-click). Delegates to
	 * {@link GridInteractionHandler}.
	 */
	private TypedActionResult<ItemStack> handleUseItem(PlayerEntity player, World world, Hand hand) {
		// Only handle block items
		ItemStack heldItem = player.getStackInHand(hand);
		if (!(heldItem.getItem() instanceof BlockItem)) {
			return TypedActionResult.pass(heldItem);
		}

		// Only handle main hand
		if (hand != Hand.MAIN_HAND) {
			return TypedActionResult.pass(heldItem);
		}

		SLogger.log(gridInteractionHandler, "=== handleUseItem === Player: " + player.getName().getString() +
				", Side: " + (world.isClient ? "CLIENT" : "SERVER"));

		// Check if player is looking at a grid
		LocalGrid gridInView = engineManager.getGridPlayerIsLookingAt(player);

		if (gridInView != null) {
			SLogger.log(gridInteractionHandler, "UseItem: Player looking at grid - calling full onUseBlock logic");

			// Call the FULL grid interaction logic (not just handleGridInteraction)
			ActionResult result = gridInteractionHandler.onUseBlock(player, hand);

			if (result == ActionResult.SUCCESS) {
				return TypedActionResult.success(heldItem);
			} else if (result == ActionResult.FAIL) {
				return TypedActionResult.fail(heldItem);
			} else {
				// Even if PASS, still consume the event to prevent vanilla when looking at grids
				return TypedActionResult.success(heldItem);
			}
		}

		return TypedActionResult.pass(heldItem);
	}

	private ActionResult handleUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
		SLogger.log(gridInteractionHandler, "=== handleUseBlock === Player: " + player.getName().getString() +
				", Side: " + (world.isClient ? "CLIENT" : "SERVER"));

		// Always call the grid interaction handler - it will determine if it should handle this
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
