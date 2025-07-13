package net.starlight.stardance.utils;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.debug.CollisionDebugger;
import net.starlight.stardance.debug.InteractionDebugManager;
import net.starlight.stardance.gridspace.GridSpaceBlockManager;
import net.starlight.stardance.mixinducks.OriginalCrosshairProvider;
import net.starlight.stardance.physics.PhysicsEngine;

import javax.vecmath.Vector3d;
import java.io.IOException;
import java.util.Optional;

import static net.starlight.stardance.Stardance.*;

/**
 * UPDATED: Enhanced command registry with Phase 3 interaction debugging commands.
 */
public class CommandRegistry implements ILoggingControl {
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    /**
     * Registers all Stardance commands including new interaction debugging commands.
     */
    public static void init() {
        LiteralArgumentBuilder<FabricClientCommandSource> stardanceCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("stardance");

        // ===============================================
        // EXISTING COMMANDS (PRESERVED)
        // ===============================================

        LiteralArgumentBuilder<FabricClientCommandSource> importSchem =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("importschem");
        importSchem.executes(CommandRegistry::importSchem);

        LiteralArgumentBuilder<FabricClientCommandSource> debugCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("debug");

        LiteralArgumentBuilder<FabricClientCommandSource> sweepTestCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("sweeptest");

        RequiredArgumentBuilder<FabricClientCommandSource, Float> distanceArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Float>argument("distance", FloatArgumentType.floatArg(0.1f, 10.0f));

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> raysArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("rays", IntegerArgumentType.integer(1, 24));

        // Build existing debug structure
        raysArg.executes(CommandRegistry::executeSweepTest);
        distanceArg.then(raysArg);
        distanceArg.executes(CommandRegistry::executeSweepTest);
        sweepTestCommand.then(distanceArg);
        sweepTestCommand.executes(CommandRegistry::executeSweepTest);

        // ===============================================
        // NEW: INTERACTION DEBUGGING COMMANDS
        // ===============================================

        // /stardance debug interaction <on|off>
        LiteralArgumentBuilder<FabricClientCommandSource> interactionCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("interaction");

        RequiredArgumentBuilder<FabricClientCommandSource, Boolean> interactionToggleArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("enabled", BoolArgumentType.bool());
        interactionToggleArg.executes(CommandRegistry::toggleInteractionDebug);

        interactionCommand.then(interactionToggleArg);
        interactionCommand.executes(context -> {
            // Show current state if no argument provided
            String state = InteractionDebugManager.getDebugStats();
            context.getSource().sendFeedback(Text.literal("§6Interaction Debug State: §f" + state));
            return 1;
        });

        // /stardance debug raycast [visual]
        LiteralArgumentBuilder<FabricClientCommandSource> raycastCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("raycast");

        LiteralArgumentBuilder<FabricClientCommandSource> raycastVisualCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("visual");
        raycastVisualCommand.executes(CommandRegistry::debugRaycastVisual);

        LiteralArgumentBuilder<FabricClientCommandSource> raycastPipelineCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("pipeline");
        raycastPipelineCommand.executes(CommandRegistry::debugRaycastPipeline);

        raycastCommand.then(raycastVisualCommand);
        raycastCommand.then(raycastPipelineCommand);
        raycastCommand.executes(context -> InteractionDebugManager.debugRaycast(context.getSource()));

        // /stardance debug transform <x> <y> <z>
        LiteralArgumentBuilder<FabricClientCommandSource> transformCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("transform");

        RequiredArgumentBuilder<FabricClientCommandSource, Double> xArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("x", DoubleArgumentType.doubleArg());
        RequiredArgumentBuilder<FabricClientCommandSource, Double> yArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("y", DoubleArgumentType.doubleArg());
        RequiredArgumentBuilder<FabricClientCommandSource, Double> zArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("z", DoubleArgumentType.doubleArg());

        zArg.executes(CommandRegistry::debugTransform);
        yArg.then(zArg);
        xArg.then(yArg);
        transformCommand.then(xArg);

        // /stardance debug all <on|off>
        LiteralArgumentBuilder<FabricClientCommandSource> allDebugCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("all");

        RequiredArgumentBuilder<FabricClientCommandSource, Boolean> allToggleArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("enabled", BoolArgumentType.bool());
        allToggleArg.executes(CommandRegistry::toggleAllDebug);

        allDebugCommand.then(allToggleArg);
        allDebugCommand.executes(context -> {
            // Show current state
            String state = InteractionDebugManager.getDebugStats();
            context.getSource().sendFeedback(Text.literal("§6All Debug State: §f" + state));
            return 1;
        });

        // /stardance debug clear
        LiteralArgumentBuilder<FabricClientCommandSource> clearDebugCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear");
        clearDebugCommand.executes(context -> {
            InteractionDebugManager.clearDebugState();
            context.getSource().sendFeedback(Text.literal("§6Debug state cleared"));
            return 1;
        });

        // ===============================================
        // COMMAND TREE ASSEMBLY
        // ===============================================

        // Add new commands to debug tree
        debugCommand.then(interactionCommand);
        debugCommand.then(raycastCommand);
        debugCommand.then(transformCommand);
        debugCommand.then(allDebugCommand);
        debugCommand.then(clearDebugCommand);
        debugCommand.then(sweepTestCommand); // Keep existing sweep test

        // Build final command tree
        stardanceCommand.then(debugCommand);
        stardanceCommand.then(importSchem);

        registerFoundationTestCommands(stardanceCommand);

        // Register the complete command tree
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(stardanceCommand));
    }

    // ===============================================
    // NEW COMMAND IMPLEMENTATIONS
    // ===============================================

    /**
     * Toggles interaction debugging on/off.
     */
    private static int toggleInteractionDebug(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        InteractionDebugManager.setInteractionDebugging(enabled);
        InteractionDebugManager.setRaycastDebugging(enabled); // Also toggle raycast debugging

        context.getSource().sendFeedback(Text.literal("§6Interaction debugging " +
                (enabled ? "§aENABLED" : "§cDISABLED")));
        return 1;
    }

    /**
     * Toggles all debugging systems on/off.
     */
    private static int toggleAllDebug(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        InteractionDebugManager.setAllDebugging(enabled);

        context.getSource().sendFeedback(Text.literal("§6All debugging " +
                (enabled ? "§aENABLED" : "§cDISABLED")));
        return 1;
    }

    /**
     * Tests coordinate transformation for specific coordinates.
     */
    private static int debugTransform(CommandContext<FabricClientCommandSource> context) {
        double x = DoubleArgumentType.getDouble(context, "x");
        double y = DoubleArgumentType.getDouble(context, "y");
        double z = DoubleArgumentType.getDouble(context, "z");

        // Also trigger visual debug
        Vec3d pos = new Vec3d(x, y, z);
        net.starlight.stardance.debug.VisualRaycastDebugger.visualizeCoordinateTransformation(pos, context.getSource().getPlayer());

        return InteractionDebugManager.debugTransform(context.getSource(), x, y, z);
    }

    /**
     * Visual raycast debugging with DebugRenderer.
     */
    private static int debugRaycastVisual(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for visual raycast"));
            return 0;
        }

        net.starlight.stardance.debug.ComprehensiveRaycastDebugger.debugComprehensiveRaycast(player);
        context.getSource().sendFeedback(Text.literal("§6Visual raycast debug activated - check the world!"));
        return 1;
    }

    /**
     * Visual pipeline debugging showing transformation at multiple points.
     */
    private static int debugRaycastPipeline(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for pipeline debug"));
            return 0;
        }

        net.starlight.stardance.debug.VisualRaycastDebugger.visualizeTransformationPipeline(player);
        context.getSource().sendFeedback(Text.literal("§6Transformation pipeline visualized - check the world!"));
        return 1;
    }

    // ===============================================
    // EXISTING COMMAND IMPLEMENTATIONS (PRESERVED)
    // ===============================================

    /**
     * Executes the sweep test command with specified arguments.
     */
    private static int executeSweepTest(CommandContext<FabricClientCommandSource> context) {
        CollisionDebugger.debugCollision(context.getSource());
        return 1;
    }

    /**
     * Imports a schematic at the player's location.
     */
    private static int importSchem(CommandContext<FabricClientCommandSource> context) {
        SLogger.log(CommandRegistry.class.getSimpleName(), "Step 1");
        org.joml.Vector3f pos = context.getSource().getPosition().toVector3f();
        try {
            schemManager.importSchematic("rat.schem", new Vector3d(pos.x, pos.y, pos.z),
                    serverInstance.getWorld(context.getSource().getWorld().getRegistryKey()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return 1;
    }
// ===============================================
// FOUNDATION TESTING COMMANDS (ADD TO YOUR EXISTING COMMANDREGISTRY)
// ===============================================

    /**
     * Registers foundation testing commands.
     * Call this from your existing registerCommands() method.
     */
    public static void registerFoundationTestCommands(LiteralArgumentBuilder<FabricClientCommandSource> rootCommand) {

        // /stardance test foundation
        LiteralArgumentBuilder<FabricClientCommandSource> foundationCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("foundation");

        // /stardance test foundation distance
        LiteralArgumentBuilder<FabricClientCommandSource> distanceCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("distance");
        distanceCommand.executes(CommandRegistry::testDistanceCalculation);

        // /stardance test foundation coordinates
        LiteralArgumentBuilder<FabricClientCommandSource> coordinatesCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("coordinates");
        coordinatesCommand.executes(CommandRegistry::testCoordinateUtilities);

        // /stardance test foundation detection
        LiteralArgumentBuilder<FabricClientCommandSource> detectionCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("detection");
        detectionCommand.executes(CommandRegistry::testGridSpaceDetection);

        // /stardance test foundation crosshair
        LiteralArgumentBuilder<FabricClientCommandSource> crosshairCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("crosshair");
        crosshairCommand.executes(CommandRegistry::testCrosshairStorage);

        // /stardance test foundation raycast
        LiteralArgumentBuilder<FabricClientCommandSource> raycastCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("raycast");
        raycastCommand.executes(CommandRegistry::testEnhancedRaycast);

        // /stardance test foundation all
        LiteralArgumentBuilder<FabricClientCommandSource> allTestsCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("all");
        allTestsCommand.executes(CommandRegistry::testAllFoundation);

        LiteralArgumentBuilder<FabricClientCommandSource> breakingCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("breaking");
        breakingCommand.executes(CommandRegistry::testBlockBreaking);

        // /stardance debug storage
        LiteralArgumentBuilder<FabricClientCommandSource> storageCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("storage");
        storageCommand.executes(CommandRegistry::debugGridStorage);

        // /stardance debug sync-grid
        LiteralArgumentBuilder<FabricClientCommandSource> syncCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("sync-grid");
        syncCommand.executes(CommandRegistry::syncGridStorage);

        LiteralArgumentBuilder<FabricClientCommandSource> storageEnhancedCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("storage-enhanced");
        storageEnhancedCommand.executes(CommandRegistry::debugGridStorageEnhanced);

        // /stardance debug add-to-gridspace
        LiteralArgumentBuilder<FabricClientCommandSource> addToGridSpaceCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("add-to-gridspace");
        addToGridSpaceCommand.executes(CommandRegistry::manuallyAddToGridSpace);

        LiteralArgumentBuilder<FabricClientCommandSource> coordinatesDebugCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("coordinates");
        coordinatesDebugCommand.executes(CommandRegistry::debugCoordinateMismatch);

        LiteralArgumentBuilder<FabricClientCommandSource> enhancedCoordsCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("enhanced-coords");
        enhancedCoordsCommand.executes(CommandRegistry::testEnhancedCoordinates);

        // Register: /stardance test distance
        LiteralArgumentBuilder<FabricClientCommandSource> distanceTestCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("distance");
        distanceTestCommand.executes(CommandRegistry::testDistanceCheck);

        LiteralArgumentBuilder<FabricClientCommandSource> globalDistanceCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("global-distance");
        globalDistanceCommand.executes(CommandRegistry::testGlobalDistance);

        // Register: /stardance test process
        LiteralArgumentBuilder<FabricClientCommandSource> processCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("process");
        processCommand.executes(CommandRegistry::testBreakingProcess);

        // Register: /stardance test precise
        LiteralArgumentBuilder<FabricClientCommandSource> preciseCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("precise");
        preciseCommand.executes(CommandRegistry::testPreciseDistance);

        // Register: /stardance test network
        LiteralArgumentBuilder<FabricClientCommandSource> networkCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("network");
        networkCommand.executes(CommandRegistry::testNetworkLayer);

        // Build the command tree
        foundationCommand.then(distanceCommand);
        foundationCommand.then(coordinatesCommand);
        foundationCommand.then(detectionCommand);
        foundationCommand.then(crosshairCommand);
        foundationCommand.then(raycastCommand);
        foundationCommand.then(allTestsCommand);
        foundationCommand.then(breakingCommand);
        foundationCommand.then(storageCommand);
        foundationCommand.then(syncCommand);
        foundationCommand.then(storageEnhancedCommand);
        foundationCommand.then(addToGridSpaceCommand);
        foundationCommand.then(coordinatesDebugCommand);
        foundationCommand.then(enhancedCoordsCommand);
        foundationCommand.then(distanceTestCommand);
        foundationCommand.then(globalDistanceCommand);
        foundationCommand.then(processCommand);
        foundationCommand.then(preciseCommand);
        foundationCommand.then(networkCommand);

        rootCommand.then(foundationCommand);
    }

// ===============================================
// FOUNDATION TEST IMPLEMENTATIONS
// ===============================================

    /**
     * Tests VS2-style distance calculation utilities.
     */
    private static int testDistanceCalculation(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for distance test"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING VS2-STYLE DISTANCE CALCULATION ==="));

        try {
            Vec3d playerPos = player.getPos();
            Vec3d testPos1 = playerPos.add(5, 0, 0);  // 5 blocks east
            Vec3d testPos2 = playerPos.add(0, 3, 4);  // 3 up, 4 north

            // Test 1: Regular distance calculation
            double regularDistance = playerPos.squaredDistanceTo(testPos1);
            double vs2Distance = TransformationAPI.squaredDistanceBetweenInclGrids(player.getWorld(), playerPos, testPos1);

            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Regular distance: §f%.3f §7| VS2-style distance: §f%.3f",
                    Math.sqrt(regularDistance), Math.sqrt(vs2Distance)
            )));

            // Test 2: Cross-dimensional test
            double crossDimensionalDistance = TransformationAPI.squaredDistanceBetweenInclGrids(
                    player.getWorld(), testPos1, testPos2);

            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Cross-dimensional distance: §f%.3f", Math.sqrt(crossDimensionalDistance)
            )));

            // Test 3: GridSpace coordinate test (if any grids exist)
            PhysicsEngine engine = engineManager.getEngine(player.getWorld());
            if (engine != null && !engine.getGrids().isEmpty()) {
                LocalGrid testGrid = engine.getGrids().iterator().next();

                // Create a test GridSpace position
                BlockPos gridSpacePos = testGrid.getGridSpaceRegion().getRegionOrigin().add(5, 5, 5);
                Vec3d gridSpaceVec = new Vec3d(gridSpacePos.getX(), gridSpacePos.getY(), gridSpacePos.getZ());

                double gridDistance = TransformationAPI.squaredDistanceBetweenInclGrids(
                        player.getWorld(), playerPos, gridSpaceVec);

                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Distance to GridSpace: §f%.3f", Math.sqrt(gridDistance)
                )));
            } else {
                context.getSource().sendFeedback(Text.literal("§7No grids available for GridSpace distance test"));
            }

            context.getSource().sendFeedback(Text.literal("§a✓ Distance calculation tests completed"));
            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cDistance test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Tests coordinate transformation utilities.
     */
    private static int testCoordinateUtilities(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for coordinate test"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING COORDINATE UTILITIES ==="));

        try {
            BlockPos playerPos = player.getBlockPos();
            Vec3d offset = new Vec3d(0.5, 0.0, 0.5); // Center of block

            // Test 1: Basic coordinate utility
            Vec3d worldCoords = TransformationAPI.getWorldCoordinates(player.getWorld(), playerPos, offset);

            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Player BlockPos: §f%s", playerPos
            )));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7World coordinates: §f(%.3f, %.3f, %.3f)",
                    worldCoords.x, worldCoords.y, worldCoords.z
            )));

            // Test 2: GridSpace detection
            Optional<TransformationAPI.GridSpaceTransformResult> detection =
                    TransformationAPI.getInstance().detectGridSpacePosition(playerPos, player.getWorld());

            if (detection.isPresent()) {
                TransformationAPI.GridSpaceTransformResult result = detection.get();
                context.getSource().sendFeedback(Text.literal("§a✓ Player is in GridSpace!"));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Grid: §f%s", result.grid.getGridId().toString().substring(0, 8)
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7GridSpace pos: §f%s", result.gridSpacePos
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Grid-local pos: §f%s", result.gridLocalPos
                )));
            } else {
                context.getSource().sendFeedback(Text.literal("§7Player is in regular world space"));
            }

            // Test 3: Round-trip transformation accuracy
            PhysicsEngine engine = engineManager.getEngine(player.getWorld());
            if (engine != null && !engine.getGrids().isEmpty()) {
                LocalGrid testGrid = engine.getGrids().iterator().next();

                // Test round-trip: world → GridSpace → world
                Vec3d originalWorld = player.getPos();
                Optional<TransformationAPI.GridSpaceTransformResult> transform =
                        TransformationAPI.getInstance().worldToGridSpace(originalWorld, player.getWorld());

                if (transform.isPresent()) {
                    Vec3d backToWorld = TransformationAPI.getInstance().gridSpaceToWorld(
                            transform.get().gridSpaceVec, transform.get().grid);

                    double roundTripError = originalWorld.distanceTo(backToWorld);

                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Round-trip error: §f%.6f blocks", roundTripError
                    )));

                    if (roundTripError < 0.001) {
                        context.getSource().sendFeedback(Text.literal("§a✓ Excellent round-trip accuracy"));
                    } else if (roundTripError < 0.01) {
                        context.getSource().sendFeedback(Text.literal("§e⚠ Acceptable round-trip accuracy"));
                    } else {
                        context.getSource().sendFeedback(Text.literal("§c✗ Poor round-trip accuracy"));
                    }
                }
            }

            context.getSource().sendFeedback(Text.literal("§a✓ Coordinate utility tests completed"));
            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cCoordinate test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Tests GridSpace detection functionality.
     */
    private static int testGridSpaceDetection(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for detection test"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING GRIDSPACE DETECTION ==="));

        try {
            PhysicsEngine engine = engineManager.getEngine(player.getWorld());
            if (engine == null) {
                context.getSource().sendFeedback(Text.literal("§cNo physics engine found"));
                return 0;
            }

            var grids = engine.getGrids();
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Found §f%d §7grids in world", grids.size()
            )));

            if (grids.isEmpty()) {
                context.getSource().sendFeedback(Text.literal("§eCreate some grids to test detection!"));
                return 1;
            }

            // Test detection for each grid
            int detectedCount = 0;
            for (LocalGrid grid : grids) {
                if (grid.isDestroyed()) continue;

                BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();
                BlockPos testPos = regionOrigin.add(10, 10, 10); // Test position in GridSpace

                Optional<TransformationAPI.GridSpaceTransformResult> detection =
                        TransformationAPI.getInstance().detectGridSpacePosition(testPos, player.getWorld());

                if (detection.isPresent() && detection.get().grid.equals(grid)) {
                    detectedCount++;
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§a✓ Grid %s detected correctly",
                            grid.getGridId().toString().substring(0, 8)
                    )));
                } else {
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§c✗ Grid %s detection failed",
                            grid.getGridId().toString().substring(0, 8)
                    )));
                }
            }

            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Detection success rate: §f%d/%d", detectedCount, grids.size()
            )));

            // Test false positive detection
            BlockPos randomWorldPos = player.getBlockPos().add(1000, 100, 1000);
            Optional<TransformationAPI.GridSpaceTransformResult> falsePositive =
                    TransformationAPI.getInstance().detectGridSpacePosition(randomWorldPos, player.getWorld());

            if (falsePositive.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§c✗ False positive detection at random world position"));
            } else {
                context.getSource().sendFeedback(Text.literal("§a✓ No false positive detection"));
            }

            context.getSource().sendFeedback(Text.literal("§a✓ GridSpace detection tests completed"));
            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cDetection test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Tests original crosshair storage (client-side).
     */
    private static int testCrosshairStorage(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("§6=== TESTING CROSSHAIR STORAGE ==="));

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!(client instanceof OriginalCrosshairProvider)) {
                context.getSource().sendFeedback(Text.literal("§c✗ MinecraftClient doesn't implement OriginalCrosshairProvider"));
                return 0;
            }

            OriginalCrosshairProvider provider = (OriginalCrosshairProvider) client;

            // Test storage and retrieval
            HitResult storedTarget = provider.stardance$getOriginalCrosshairTarget();

            if (storedTarget != null) {
                context.getSource().sendFeedback(Text.literal("§a✓ Crosshair storage is working"));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Stored target type: §f%s", storedTarget.getType()
                )));

                if (storedTarget instanceof BlockHitResult) {
                    BlockHitResult blockHit = (BlockHitResult) storedTarget;
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Block position: §f%s", blockHit.getBlockPos()
                    )));
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Hit position: §f(%.3f, %.3f, %.3f)",
                            blockHit.getPos().x, blockHit.getPos().y, blockHit.getPos().z
                    )));
                }
            } else {
                context.getSource().sendFeedback(Text.literal("§7No crosshair target currently stored"));
                context.getSource().sendFeedback(Text.literal("§eLook at a block to test storage"));
            }

            context.getSource().sendFeedback(Text.literal("§a✓ Crosshair storage interface tests completed"));
            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cCrosshair test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Tests enhanced raycast system.
     */
    private static int testEnhancedRaycast(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for raycast test"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING ENHANCED RAYCAST ==="));

        try {
            Vec3d eyePos = player.getEyePos();
            Vec3d lookVec = player.getRotationVector();
            Vec3d rayEnd = eyePos.add(lookVec.multiply(64.0));

            // Create raycast context
            RaycastContext context_raycast = new RaycastContext(
                    eyePos, rayEnd,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,
                    player
            );

            // Test 1: Vanilla raycast
            long startTime = System.nanoTime();
            BlockHitResult vanillaResult = player.getWorld().raycast(context_raycast);
            long vanillaTime = System.nanoTime() - startTime;

            // Test 2: Enhanced raycast
            startTime = System.nanoTime();
            BlockHitResult enhancedResult = GridSpaceRaycastUtils.raycastIncludeGrids(player.getWorld(), context_raycast);
            long enhancedTime = System.nanoTime() - startTime;

            // Compare results
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Vanilla raycast: §f%s", vanillaResult.getType()
            )));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Enhanced raycast: §f%s", enhancedResult.getType()
            )));

            if (vanillaResult.getType() != HitResult.Type.MISS) {
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Vanilla hit: §f%s", vanillaResult.getBlockPos()
                )));
            }

            if (enhancedResult.getType() != HitResult.Type.MISS) {
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Enhanced hit: §f%s", enhancedResult.getBlockPos()
                )));
            }

            // Performance comparison
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Performance - Vanilla: §f%.3fms §7| Enhanced: §f%.3fms",
                    vanillaTime / 1_000_000.0, enhancedTime / 1_000_000.0
            )));

            // Test physics raycast specifically
            PhysicsEngine engine = engineManager.getEngine(player.getWorld());
            if (engine != null) {
                Optional<PhysicsEngine.PhysicsRaycastResult> physicsResult =
                        engine.raycastGrids(eyePos, rayEnd);

                if (physicsResult.isPresent()) {
                    context.getSource().sendFeedback(Text.literal("§a✓ Physics raycast detected grid hit"));
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Grid hit position: §f(%.3f, %.3f, %.3f)",
                            physicsResult.get().worldHitPos.x,
                            physicsResult.get().worldHitPos.y,
                            physicsResult.get().worldHitPos.z
                    )));
                } else {
                    context.getSource().sendFeedback(Text.literal("§7No physics raycast hits"));
                }
            }

            context.getSource().sendFeedback(Text.literal("§a✓ Enhanced raycast tests completed"));
            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cRaycast test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Runs all foundation tests in sequence.
     */
    private static int testAllFoundation(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("§6=== RUNNING ALL FOUNDATION TESTS ==="));

        int totalTests = 0;
        int passedTests = 0;

        // Run each test and count results
        try {
            totalTests++;
            if (testDistanceCalculation(context) > 0) passedTests++;

            totalTests++;
            if (testCoordinateUtilities(context) > 0) passedTests++;

            totalTests++;
            if (testGridSpaceDetection(context) > 0) passedTests++;

            totalTests++;
            if (testCrosshairStorage(context) > 0) passedTests++;

            totalTests++;
            if (testEnhancedRaycast(context) > 0) passedTests++;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cFoundation test suite failed: " + e.getMessage()));
            return 0;
        }

        // Summary
        context.getSource().sendFeedback(Text.literal("§6=== FOUNDATION TEST SUMMARY ==="));
        context.getSource().sendFeedback(Text.literal(String.format(
                "§7Tests passed: §f%d/%d", passedTests, totalTests
        )));

        if (passedTests == totalTests) {
            context.getSource().sendFeedback(Text.literal("§a✓ ALL FOUNDATION TESTS PASSED! Ready for next phase."));
        } else if (passedTests > totalTests / 2) {
            context.getSource().sendFeedback(Text.literal("§e⚠ Most tests passed, check failures above"));
        } else {
            context.getSource().sendFeedback(Text.literal("§c✗ Multiple foundation issues detected"));
        }

        return passedTests;
    }

    /**
     * Tests block breaking interactions on grids.
     */
    private static int testBlockBreaking(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING BLOCK BREAKING ==="));

        try {
            // Get what the player is looking at
            HitResult hitResult = player.raycast(8.0, 0.0f, false);

            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block to test breaking"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Target position: §f%s", targetPos
            )));

            // Check if this is a GridSpace block
            Optional<TransformationAPI.GridSpaceTransformResult> gridResult =
                    TransformationAPI.getInstance().detectGridSpacePosition(targetPos, player.getWorld());

            if (gridResult.isPresent()) {
                TransformationAPI.GridSpaceTransformResult result = gridResult.get();

                context.getSource().sendFeedback(Text.literal("§a✓ Target is a GridSpace block"));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Grid: §f%s", result.grid.getGridId().toString().substring(0, 8)
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7GridSpace pos: §f%s", result.gridSpacePos
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Grid-local pos: §f%s", result.gridLocalPos
                )));

                // Check if block exists in grid
                if (result.grid.hasBlock(result.gridLocalPos)) {
                    context.getSource().sendFeedback(Text.literal("§a✓ Block exists in grid storage"));

                    // Check GridSpace block manager
                    if (result.grid.getGridSpaceBlockManager() != null) {
                        BlockState gridSpaceState = result.grid.getGridSpaceBlockManager().getBlockState(result.gridSpacePos);
                        context.getSource().sendFeedback(Text.literal(String.format(
                                "§7GridSpace block state: §f%s", gridSpaceState.getBlock().getName().getString()
                        )));
                    } else {
                        context.getSource().sendFeedback(Text.literal("§c✗ No GridSpace block manager"));
                    }
                } else {
                    context.getSource().sendFeedback(Text.literal("§c✗ Block missing from grid storage"));
                }

                // Test distance calculation
                Vec3d blockWorldPos = TransformationAPI.getWorldCoordinates(
                        player.getWorld(), targetPos, new Vec3d(0.5, 0.5, 0.5));
                double distance = Math.sqrt(TransformationAPI.squaredDistanceBetweenInclGrids(
                        player.getWorld(), player.getPos(), blockWorldPos));

                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Distance: §f%.2f blocks", distance
                )));

                if (distance <= 8.0) {
                    context.getSource().sendFeedback(Text.literal("§a✓ Within breaking distance"));
                    context.getSource().sendFeedback(Text.literal("§e§lTry breaking the block now!"));
                    context.getSource().sendFeedback(Text.literal("§7Watch the console for debug messages."));
                } else {
                    context.getSource().sendFeedback(Text.literal("§c✗ Too far to break"));
                }

            } else {
                context.getSource().sendFeedback(Text.literal("§7Target is a regular world block"));
                context.getSource().sendFeedback(Text.literal("§eCreate a grid first to test GridSpace breaking"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cBreaking test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Debug grid storage synchronization issues.
     */
    private static int debugGridStorage(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== DEBUGGING GRID STORAGE ==="));

        try {
            // Get target block
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block to debug"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            // Check if GridSpace
            Optional<TransformationAPI.GridSpaceTransformResult> gridResult =
                    TransformationAPI.getInstance().detectGridSpacePosition(targetPos, player.getWorld());

            if (!gridResult.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§cNot a GridSpace block"));
                return 0;
            }

            TransformationAPI.GridSpaceTransformResult result = gridResult.get();
            LocalGrid grid = result.grid;

            context.getSource().sendFeedback(Text.literal("§a=== GRID STORAGE DEBUG ==="));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Grid ID: §f%s", grid.getGridId().toString().substring(0, 8)
            )));

            // 1. Check grid's local storage
            context.getSource().sendFeedback(Text.literal("§61. GRID LOCAL STORAGE:"));
            boolean inGridStorage = grid.hasBlock(result.gridLocalPos);
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Grid-local pos %s exists: §f%s", result.gridLocalPos, inGridStorage
            )));

            if (inGridStorage) {
                BlockState gridState = grid.getBlock(result.gridLocalPos);
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Grid block state: §f%s", gridState.getBlock().getName().getString()
                )));
            }

            // 2. Check GridSpace storage
            context.getSource().sendFeedback(Text.literal("§62. GRIDSPACE STORAGE:"));
            GridSpaceBlockManager blockManager = grid.getGridSpaceBlockManager();
            if (blockManager != null) {
                BlockState gridSpaceState = blockManager.getBlockState(result.gridSpacePos);
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7GridSpace pos %s state: §f%s", result.gridSpacePos,
                        gridSpaceState.getBlock().getName().getString()
                )));

                boolean isAir = gridSpaceState.isAir();
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Is air: §f%s", isAir
                )));
            } else {
                context.getSource().sendFeedback(Text.literal("§c✗ No GridSpace block manager"));
            }

            // 3. Check world storage at GridSpace coordinates
            context.getSource().sendFeedback(Text.literal("§63. WORLD STORAGE AT GRIDSPACE:"));
            BlockState worldState = player.getWorld().getBlockState(result.gridSpacePos);
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7World state at GridSpace pos: §f%s", worldState.getBlock().getName().getString()
            )));

            // 4. Check all blocks in grid
            context.getSource().sendFeedback(Text.literal("§64. GRID BLOCK COUNT:"));
            int gridBlockCount = grid.getBlocks().size();
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Total blocks in grid: §f%d", gridBlockCount
            )));

            if (gridBlockCount > 0) {
                context.getSource().sendFeedback(Text.literal("§7First 5 blocks in grid:"));
                int count = 0;
                for (BlockPos gridLocalPos : grid.getBlocks().keySet()) {
                    if (count >= 5) break;
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7  - %s", gridLocalPos
                    )));
                    count++;
                }
            }

            // 5. Suggest sync action
            context.getSource().sendFeedback(Text.literal("§65. SUGGESTED ACTIONS:"));
            if (!inGridStorage) {
                context.getSource().sendFeedback(Text.literal("§c✗ Block missing from grid storage"));
                context.getSource().sendFeedback(Text.literal("§eRun: /stardance debug sync-grid to fix"));
            } else {
                context.getSource().sendFeedback(Text.literal("§a✓ Storage looks synchronized"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cDebug failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Attempt to synchronize grid storage (fix missing blocks).
     */
    private static int syncGridStorage(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== SYNCHRONIZING GRID STORAGE ==="));

        try {
            // Get target block
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a GridSpace block to sync"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            Optional<TransformationAPI.GridSpaceTransformResult> gridResult =
                    TransformationAPI.getInstance().detectGridSpacePosition(targetPos, player.getWorld());

            if (!gridResult.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§cNot a GridSpace block"));
                return 0;
            }

            TransformationAPI.GridSpaceTransformResult result = gridResult.get();
            LocalGrid grid = result.grid;

            // Get the block state from GridSpace
            GridSpaceBlockManager blockManager = grid.getGridSpaceBlockManager();
            if (blockManager == null) {
                context.getSource().sendFeedback(Text.literal("§cNo GridSpace block manager"));
                return 0;
            }

            BlockState gridSpaceState = blockManager.getBlockState(result.gridSpacePos);

            if (gridSpaceState.isAir()) {
                context.getSource().sendFeedback(Text.literal("§cGridSpace block is air - nothing to sync"));
                return 0;
            }

            // Add to grid's local storage
            boolean added = grid.addBlock(new LocalBlock(result.gridLocalPos, gridSpaceState));

            if (added) {
                context.getSource().sendFeedback(Text.literal("§a✓ Successfully synced block to grid storage"));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Added %s at %s", gridSpaceState.getBlock().getName().getString(), result.gridLocalPos
                )));

                // Mark grid dirty for physics update
                grid.markDirty();

                context.getSource().sendFeedback(Text.literal("§aTry breaking the block now!"));
            } else {
                context.getSource().sendFeedback(Text.literal("§cFailed to add block to grid storage"));
            }

            return added ? 1 : 0;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cSync failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }
    /**
     * Enhanced debug for grid storage with null safety.
     */
    private static int debugGridStorageEnhanced(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== ENHANCED GRID STORAGE DEBUG ==="));

        try {
            // Get target block
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block to debug"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            // Check if GridSpace
            Optional<TransformationAPI.GridSpaceTransformResult> gridResult =
                    TransformationAPI.getInstance().detectGridSpacePosition(targetPos, player.getWorld());

            if (!gridResult.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§cNot a GridSpace block"));
                return 0;
            }

            TransformationAPI.GridSpaceTransformResult result = gridResult.get();
            LocalGrid grid = result.grid;

            context.getSource().sendFeedback(Text.literal("§a=== DETAILED STORAGE ANALYSIS ==="));

            // 1. Grid basic info
            context.getSource().sendFeedback(Text.literal("§61. GRID INFO:"));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Grid ID: §f%s", grid.getGridId().toString()
            )));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Grid destroyed: §f%s", grid.isDestroyed()
            )));

            // 2. Physics engine detection
            context.getSource().sendFeedback(Text.literal("§62. PHYSICS ENGINE:"));
            PhysicsEngine engine = engineManager.getEngine(player.getWorld());
            if (engine != null) {
                Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit =
                        engine.raycastGrids(player.getEyePos(), player.getEyePos().add(player.getRotationVector().multiply(8.0)));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Physics raycast hit: §f%s", physicsHit.isPresent()
                )));
                if (physicsHit.isPresent()) {
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Physics hit pos: §f%s", physicsHit.get().worldHitPos
                    )));
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Physics GridSpace pos: §f%s", physicsHit.get().gridSpacePos
                    )));
                }
            } else {
                context.getSource().sendFeedback(Text.literal("§c✗ No physics engine"));
            }

            // 3. Grid local storage
            context.getSource().sendFeedback(Text.literal("§63. GRID LOCAL STORAGE:"));
            boolean inGridStorage = grid.hasBlock(result.gridLocalPos);
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Has block at %s: §f%s", result.gridLocalPos, inGridStorage
            )));

            if (inGridStorage) {
                BlockState gridState = grid.getBlock(result.gridLocalPos);
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Grid local state: §f%s", gridState != null ? gridState.getBlock().getName().getString() : "NULL"
                )));
            }

            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Total blocks in grid: §f%d", grid.getBlocks().size()
            )));

            // 4. GridSpace storage
            context.getSource().sendFeedback(Text.literal("§64. GRIDSPACE STORAGE:"));
            GridSpaceBlockManager blockManager = grid.getGridSpaceBlockManager();
            if (blockManager != null) {
                context.getSource().sendFeedback(Text.literal("§a✓ GridSpace block manager exists"));

                BlockState gridSpaceState = blockManager.getBlockState(result.gridSpacePos);
                if (gridSpaceState != null) {
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7GridSpace state: §f%s", gridSpaceState.getBlock().getName().getString()
                    )));
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Is air: §f%s", gridSpaceState.isAir()
                    )));
                } else {
                    context.getSource().sendFeedback(Text.literal("§c✗ GridSpace state is NULL"));
                }
            } else {
                context.getSource().sendFeedback(Text.literal("§c✗ No GridSpace block manager"));
            }

            // 5. World storage at GridSpace position
            context.getSource().sendFeedback(Text.literal("§65. WORLD AT GRIDSPACE:"));
            BlockState worldState = player.getWorld().getBlockState(result.gridSpacePos);
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7World state at GridSpace: §f%s", worldState.getBlock().getName().getString()
            )));

            // 6. GridSpace region info
            context.getSource().sendFeedback(Text.literal("§66. GRIDSPACE REGION:"));
            if (grid.getGridSpaceRegion() != null) {
                BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Region origin: §f%s", regionOrigin
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Contains position: §f%s", grid.getGridSpaceRegion().containsGridSpacePosition(result.gridSpacePos)
                )));
            } else {
                context.getSource().sendFeedback(Text.literal("§c✗ No GridSpace region"));
            }

            // 7. Diagnosis
            context.getSource().sendFeedback(Text.literal("§67. DIAGNOSIS:"));
            if (blockManager == null) {
                context.getSource().sendFeedback(Text.literal("§c✗ CRITICAL: No GridSpace block manager"));
                context.getSource().sendFeedback(Text.literal("§eThis grid was likely created before GridSpace system"));
            } else if (blockManager.getBlockState(result.gridSpacePos) == null) {
                context.getSource().sendFeedback(Text.literal("§c✗ CRITICAL: Block not in GridSpace storage"));
                context.getSource().sendFeedback(Text.literal("§eBlock exists in physics but not in GridSpace storage"));
            } else if (!inGridStorage) {
                context.getSource().sendFeedback(Text.literal("§c✗ Block not in grid local storage"));
            } else {
                context.getSource().sendFeedback(Text.literal("§a✓ Storage looks synchronized"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cEnhanced debug failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Attempt to manually add a block to GridSpace storage.
     */
    private static int manuallyAddToGridSpace(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== MANUALLY ADDING TO GRIDSPACE ==="));

        try {
            // Get target block
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            Optional<TransformationAPI.GridSpaceTransformResult> gridResult =
                    TransformationAPI.getInstance().detectGridSpacePosition(targetPos, player.getWorld());

            if (!gridResult.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§cNot a GridSpace position"));
                return 0;
            }

            TransformationAPI.GridSpaceTransformResult result = gridResult.get();
            LocalGrid grid = result.grid;

            // Check if grid has the block in local storage
            if (!grid.hasBlock(result.gridLocalPos)) {
                context.getSource().sendFeedback(Text.literal("§cBlock not in grid local storage - can't determine block type"));
                return 0;
            }

            BlockState localState = grid.getBlock(result.gridLocalPos);
            if (localState == null) {
                context.getSource().sendFeedback(Text.literal("§cCannot get block state from grid"));
                return 0;
            }

            // Ensure GridSpace block manager exists
            GridSpaceBlockManager blockManager = grid.getGridSpaceBlockManager();
            if (blockManager == null) {
                context.getSource().sendFeedback(Text.literal("§c✗ No GridSpace block manager - this grid needs initialization"));
                context.getSource().sendFeedback(Text.literal("§eThis is likely an old grid created before GridSpace system"));
                return 0;
            }

            // Add to GridSpace storage
            boolean success = blockManager.placeBlock(result.gridSpacePos, localState);

            if (success) {
                context.getSource().sendFeedback(Text.literal("§a✓ Successfully added block to GridSpace storage"));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Added %s at GridSpace %s", localState.getBlock().getName().getString(), result.gridSpacePos
                )));
                context.getSource().sendFeedback(Text.literal("§eTry breaking the block now!"));
            } else {
                context.getSource().sendFeedback(Text.literal("§cFailed to add block to GridSpace storage"));
            }

            return success ? 1 : 0;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cManual add failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Find where the grid's block is actually stored and compare coordinates.
     */
    private static int debugCoordinateMismatch(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== COORDINATE MISMATCH ANALYSIS ==="));

        try {
            // Get target
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            Optional<TransformationAPI.GridSpaceTransformResult> gridResult =
                    TransformationAPI.getInstance().detectGridSpacePosition(targetPos, player.getWorld());

            if (!gridResult.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§cNot a GridSpace position"));
                return 0;
            }

            TransformationAPI.GridSpaceTransformResult result = gridResult.get();
            LocalGrid grid = result.grid;

            context.getSource().sendFeedback(Text.literal("§a=== COORDINATE ANALYSIS ==="));

            // 1. Physics engine coordinates
            context.getSource().sendFeedback(Text.literal("§61. PHYSICS ENGINE COORDINATES:"));
            PhysicsEngine engine = engineManager.getEngine(player.getWorld());
            Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit =
                    engine.raycastGrids(player.getEyePos(), player.getEyePos().add(player.getRotationVector().multiply(8.0)));

            if (physicsHit.isPresent()) {
                PhysicsEngine.PhysicsRaycastResult hit = physicsHit.get();
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Physics world hit: §f%s", hit.worldHitPos
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Physics GridSpace: §f%s", hit.gridSpacePos
                )));
            }

            // 2. TransformationAPI coordinates
            context.getSource().sendFeedback(Text.literal("§62. TRANSFORMATION API COORDINATES:"));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Target GridSpace: §f%s", targetPos
            )));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Calculated grid-local: §f%s", result.gridLocalPos
            )));

            // 3. Find the actual block in the grid
            context.getSource().sendFeedback(Text.literal("§63. ACTUAL BLOCKS IN GRID:"));
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Total blocks: §f%d", grid.getBlocks().size()
            )));

            for (BlockPos actualPos : grid.getBlocks().keySet()) {
                BlockState actualState = grid.getBlock(actualPos);
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Block at %s: §f%s", actualPos, actualState.getBlock().getName().getString()
                )));

                // Calculate what GridSpace coordinate this SHOULD be
                BlockPos calculatedGridSpace = grid.gridLocalToGridSpace(actualPos);
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7  → Should be GridSpace: §f%s", calculatedGridSpace
                )));

                // Calculate world position
                try {
                    Vec3d worldPos = grid.gridLocalToWorld(new Vec3d(actualPos.getX() + 0.5, actualPos.getY() + 0.5, actualPos.getZ() + 0.5));
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7  → Should be world: §f(%.2f, %.2f, %.2f)", worldPos.x, worldPos.y, worldPos.z
                    )));
                } catch (Exception e) {
                    context.getSource().sendFeedback(Text.literal("§c  → Error calculating world pos: " + e.getMessage()));
                }
            }

            // 4. Test reverse transformation from physics coordinates
            if (physicsHit.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§64. REVERSE COORDINATE TRANSFORMATION:"));
                PhysicsEngine.PhysicsRaycastResult hit = physicsHit.get();

                // Try to transform physics GridSpace pos to grid-local
                Optional<TransformationAPI.GridSpaceTransformResult> reverseResult =
                        TransformationAPI.getInstance().detectGridSpacePosition(hit.gridSpacePos, player.getWorld());

                if (reverseResult.isPresent()) {
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Physics GridSpace %s → grid-local %s",
                            hit.gridSpacePos, reverseResult.get().gridLocalPos
                    )));

                    // Check if THIS coordinate has the block
                    boolean hasBlockAtReverse = grid.hasBlock(reverseResult.get().gridLocalPos);
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Block exists at reverse coordinate: §f%s", hasBlockAtReverse
                    )));
                } else {
                    context.getSource().sendFeedback(Text.literal("§cCannot reverse-transform physics coordinates"));
                }
            }

            // 5. GridSpace region calculation check
            context.getSource().sendFeedback(Text.literal("§65. GRIDSPACE REGION CALCULATION:"));
            BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Region origin: §f%s", regionOrigin
            )));

            // Test the calculation manually
            for (BlockPos actualPos : grid.getBlocks().keySet()) {
                double centerOffset = LocalGrid.GRIDSPACE_CENTER_OFFSET; // You might need to access this

                BlockPos manualCalc = new BlockPos(
                        (int)(actualPos.getX() + centerOffset + regionOrigin.getX()),
                        (int)(actualPos.getY() + centerOffset + regionOrigin.getY()),
                        (int)(actualPos.getZ() + centerOffset + regionOrigin.getZ())
                );

                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Manual GridSpace calc for %s: §f%s", actualPos, manualCalc
                )));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cCoordinate analysis failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }


    /**
     * Test the enhanced coordinate detection that handles raycast edges.
     */
    private static int testEnhancedCoordinates(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING ENHANCED COORDINATES ==="));

        try {
            // Get target
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7Raycast target: §f%s", targetPos
            )));

            // Test enhanced coordinate detection
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> result =
                    GridSpaceCoordinateUtils.findActualGridBlock(targetPos, player.getWorld());

            if (result.isPresent()) {
                GridSpaceCoordinateUtils.GridSpaceBlockResult blockResult = result.get();

                context.getSource().sendFeedback(Text.literal("§a✓ Enhanced detection successful!"));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Original GridSpace: §f%s", targetPos
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Actual GridSpace: §f%s", blockResult.actualGridSpacePos
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Grid-local: §f%s", blockResult.gridLocalPos
                )));

                BlockState actualState = blockResult.grid.getBlock(blockResult.gridLocalPos);
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Block type: §f%s", actualState.getBlock().getName().getString()
                )));

                // Show offset
                BlockPos offset = blockResult.actualGridSpacePos.subtract(targetPos);
                if (!offset.equals(BlockPos.ORIGIN)) {
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§eOffset from raycast: §f%s", offset
                    )));
                } else {
                    context.getSource().sendFeedback(Text.literal("§a✓ No offset needed"));
                }

                context.getSource().sendFeedback(Text.literal("§e§lTry breaking the block now!"));

            } else {
                context.getSource().sendFeedback(Text.literal("§cNo GridSpace block found"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cEnhanced coordinate test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Test distance check interception.
     */
    private static int testDistanceCheck(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING DISTANCE CHECK ==="));

        try {
            // Get target
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            // Check if it's a grid block
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(targetPos, player.getWorld());

            if (gridResult.isPresent()) {
                GridSpaceCoordinateUtils.GridSpaceBlockResult result = gridResult.get();

                // Calculate distances like the mixin does
                Vec3d playerPos = player.getPos();
                Vec3d blockWorldPos = TransformationAPI.getWorldCoordinates(
                        player.getWorld(), result.actualGridSpacePos, new Vec3d(0.5, 0.5, 0.5));

                double gridDistance = Math.sqrt(TransformationAPI.squaredDistanceBetweenInclGrids(
                        player.getWorld(), playerPos, blockWorldPos));

                // Also calculate raw GridSpace distance for comparison
                Vec3d gridSpacePos = new Vec3d(
                        result.actualGridSpacePos.getX() + 0.5,
                        result.actualGridSpacePos.getY() + 0.5,
                        result.actualGridSpacePos.getZ() + 0.5
                );
                double rawDistance = Math.sqrt(playerPos.squaredDistanceTo(gridSpacePos));

                context.getSource().sendFeedback(Text.literal("§a✓ Grid block detected"));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7World distance: §f%.2f blocks", gridDistance
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Raw GridSpace distance: §f%.2f blocks", rawDistance
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Player pos: §f(%.2f, %.2f, %.2f)", playerPos.x, playerPos.y, playerPos.z
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Block world pos: §f(%.2f, %.2f, %.2f)", blockWorldPos.x, blockWorldPos.y, blockWorldPos.z
                )));

                if (gridDistance <= 8.0) {
                    context.getSource().sendFeedback(Text.literal("§a✓ Within breaking distance"));
                    context.getSource().sendFeedback(Text.literal("§e§lTry breaking now - should work!"));
                } else {
                    context.getSource().sendFeedback(Text.literal("§c✗ Too far to break"));
                }

            } else {
                context.getSource().sendFeedback(Text.literal("§7Regular world block"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cDistance test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Test global distance override system.
     */
    private static int testGlobalDistance(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING GLOBAL DISTANCE OVERRIDE ==="));

        try {
            // Get a test position 5 blocks away
            Vec3d playerPos = player.getPos();
            Vec3d testPos = playerPos.add(5, 0, 0);

            context.getSource().sendFeedback(Text.literal("§61. VANILLA CALCULATIONS:"));

            // Test Vec3d distance (this should be overridden by our mixin)
            double vec3Distance = player.squaredDistanceTo(testPos);
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7squaredDistanceTo(Vec3d): §f%.2f", Math.sqrt(vec3Distance)
            )));

            // Test coordinate distance (this should be overridden by our mixin)
            double coordDistance = player.squaredDistanceTo(testPos.x, testPos.y, testPos.z);
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7squaredDistanceTo(DDD): §f%.2f", Math.sqrt(coordDistance)
            )));

            context.getSource().sendFeedback(Text.literal("§62. DIRECT API COMPARISON:"));

            // Test our API directly
            double apiDistance = TransformationAPI.squaredDistanceBetweenInclGrids(
                    player.getWorld(), playerPos, testPos);
            context.getSource().sendFeedback(Text.literal(String.format(
                    "§7TransformationAPI: §f%.2f", Math.sqrt(apiDistance)
            )));

            // Test on a grid block if available
            context.getSource().sendFeedback(Text.literal("§63. GRID BLOCK TEST:"));

            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hitResult;
                BlockPos targetPos = blockHit.getBlockPos();

                // Check if it's a grid block
                Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                        GridSpaceCoordinateUtils.findActualGridBlock(targetPos, player.getWorld());

                if (gridResult.isPresent()) {
                    Vec3d blockCenter = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

                    // This should now use our overridden distance calculation
                    double playerToBlock = player.squaredDistanceTo(blockCenter);

                    context.getSource().sendFeedback(Text.literal("§a✓ Grid block detected"));
                    context.getSource().sendFeedback(Text.literal(String.format(
                            "§7Distance to grid block: §f%.2f blocks", Math.sqrt(playerToBlock)
                    )));

                    if (Math.sqrt(playerToBlock) <= 8.0) {
                        context.getSource().sendFeedback(Text.literal("§a✓ Grid block within interaction range"));
                        context.getSource().sendFeedback(Text.literal("§e§lTry breaking it now!"));
                    } else {
                        context.getSource().sendFeedback(Text.literal("§c✗ Grid block too far"));
                    }
                } else {
                    context.getSource().sendFeedback(Text.literal("§7Looking at regular world block"));
                }
            } else {
                context.getSource().sendFeedback(Text.literal("§7Not looking at any block"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cGlobal distance test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Test the block breaking process pipeline.
     */
    private static int testBreakingProcess(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING BREAKING PROCESS PIPELINE ==="));

        try {
            // Get target
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            // Check if it's a grid block
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(targetPos, player.getWorld());

            if (gridResult.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§a✓ Grid block detected"));
                context.getSource().sendFeedback(Text.literal("§e§lNow try breaking the block!"));
                context.getSource().sendFeedback(Text.literal("§7Watch console for detailed process logs..."));

                // Show what we expect to see
                double distance = Math.sqrt(player.getPos().squaredDistanceTo(
                        net.minecraft.util.math.Vec3d.ofCenter(targetPos)));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Expected distance: §f%.2f blocks", distance
                )));

                double maxDistance = Math.sqrt(net.minecraft.server.network.ServerPlayNetworkHandler.MAX_BREAK_SQUARED_DISTANCE);
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Max allowed: §f%.2f blocks", maxDistance
                )));

                if (distance <= maxDistance) {
                    context.getSource().sendFeedback(Text.literal("§a✓ Should pass distance check"));
                } else {
                    context.getSource().sendFeedback(Text.literal("§c✗ Will fail distance check"));
                }

            } else {
                context.getSource().sendFeedback(Text.literal("§cNot a grid block"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cProcess test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Test the precise distance targeting.
     */
    private static int testPreciseDistance(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING PRECISE DISTANCE TARGETING ==="));

        try {
            // Get target
            HitResult hitResult = player.raycast(8.0, 0.0f, false);
            if (hitResult.getType() != HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Text.literal("§cLook at a block"));
                return 0;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();

            // Check if it's a grid block
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(targetPos, player.getWorld());

            if (gridResult.isPresent()) {
                context.getSource().sendFeedback(Text.literal("§a✓ Grid block detected"));

                // Show the distance calculations
                Vec3d playerEye = player.getEyePos();
                Vec3d gridSpaceCenter = Vec3d.ofCenter(targetPos);
                Vec3d blockWorldPos = TransformationAPI.getWorldCoordinates(
                        player.getWorld(), targetPos, new Vec3d(0.5, 0.5, 0.5));

                double gridSpaceDistance = Math.sqrt(playerEye.squaredDistanceTo(gridSpaceCenter));
                double worldDistance = Math.sqrt(playerEye.squaredDistanceTo(blockWorldPos));

                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7GridSpace distance: §f%.2f blocks", gridSpaceDistance
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7World distance: §f%.2f blocks", worldDistance
                )));
                context.getSource().sendFeedback(Text.literal(String.format(
                        "§7Max allowed: §f%.2f blocks",
                        Math.sqrt(net.minecraft.server.network.ServerPlayNetworkHandler.MAX_BREAK_SQUARED_DISTANCE)
                )));

                if (worldDistance <= 6.0) {
                    context.getSource().sendFeedback(Text.literal("§a✓ World distance should pass"));
                    context.getSource().sendFeedback(Text.literal("§e§lTry breaking now - should work!"));
                } else {
                    context.getSource().sendFeedback(Text.literal("§c✗ Still too far even with world coordinates"));
                }

            } else {
                context.getSource().sendFeedback(Text.literal("§cNot a grid block"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Text.literal("§cPrecise distance test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }

    }

    /**
     * Test network layer debugging.
     */
    private static int testNetworkLayer(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available"));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("§6=== TESTING NETWORK LAYER ==="));
        context.getSource().sendFeedback(Text.literal("§e§lTry breaking a grid block now!"));
        context.getSource().sendFeedback(Text.literal("§7Watch for network layer logs in console..."));
        context.getSource().sendFeedback(Text.literal("§7Expected logs:"));
        context.getSource().sendFeedback(Text.literal("§7  1. 'NETWORK: Grid Block Action Received'"));
        context.getSource().sendFeedback(Text.literal("§7  2. 'Network distance check' logs"));
        context.getSource().sendFeedback(Text.literal("§7  3. 'About to call processBlockBreakingAction'"));

        return 1;
    }

}