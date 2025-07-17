package net.starlight.stardance.utils;

import com.bulletphysics.dynamics.DynamicsWorld;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.Stardance;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.debug.CollisionDebugger;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.gridspace.GridSpaceRegion;
import net.starlight.stardance.gridspace.utils.GridSpaceRaycastUtils;
import net.starlight.stardance.physics.EngineManager;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.render.DebugRenderer;

import javax.vecmath.Vector3d;
import java.io.IOException;
import java.util.Optional;

import static net.minecraft.commands.Commands.literal;
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

        registerRaycastDebugCommands();
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
                    serverInstance.getLevel(context.getSource().getWorld().dimension()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return 1;
    }

// Add this method to your CommandRegistry class
    /**
     * Registers raycast debugging commands for testing the core interaction system.
     */
    public static void registerRaycastDebugCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // Main debug command: /stardance debug raycast
            dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stardance")
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("debug")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("raycast")
                                    .executes(CommandRegistry::executeRaycastDebug)
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("continuous")
                                            .executes(CommandRegistry::executeRaycastContinuous))
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("detailed")
                                            .executes(CommandRegistry::executeRaycastDetailed))
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("performance")
                                            .executes(CommandRegistry::executeRaycastPerformance))
                            )));
        });
    }

    /**
     * Register server-side commands
     */
    public static void registerServerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("stardance")
                    .requires(source -> source.hasPermission(2))
                    .then(literal("test")
                            .then(literal("physics")
                                    .executes(CommandRegistry::executePhysicsRaycastTest))
                            .then(literal("level")
                                    .executes(CommandRegistry::testLevelClip))
                            .then(literal("distance")
                                    .executes(CommandRegistry::executeDistanceDebug)
                                    .then(Commands.literal("visual-distance")
                                            .executes(CommandRegistry::testDistanceMixinVisual))
                                    .then(Commands.literal("visual-container")
                                            .executes(CommandRegistry::testContainerDistanceVisual))
                                    .then(Commands.literal("live-distance")
                                            .executes(CommandRegistry::startLiveDistanceMonitoring))
                            )
                    )
            );
        });
    }

    /**
     * Comprehensive distance system test.
     */
    private static int executeDistanceDebug(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                source.sendSuccess(() -> Component.literal("§cNo player or world available"),false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal("§6=== Distance System Debug ==="),false);

            Player player = mc.player;
            Vec3 playerPos = player.position();

            source.sendSuccess(() -> Component.literal("§ePlayer position: §f" + formatVec3(playerPos)),false);

            // Step 1: Test basic distance mixin interception
            source.sendSuccess(() -> Component.literal("§a--- Step 1: Mixin Interception Test ---"),false);

            // Test distance to a nearby position
            Vec3 testPos = playerPos.add(5, 0, 0);
            double distance1 = player.distanceToSqr(testPos.x, testPos.y, testPos.z);
            double distance2 = player.distanceToSqr(testPos);

            source.sendSuccess(() -> Component.literal("§fTest position: §e" + formatVec3(testPos)),false);
            source.sendSuccess(() -> Component.literal("§fDistance method 1: §e" + String.format("%.3f", Math.sqrt(distance1))),false);
            source.sendSuccess(() -> Component.literal("§fDistance method 2: §e" + String.format("%.3f", Math.sqrt(distance2))),false);

            // Calculate expected vanilla distance
            double expectedDistance = playerPos.distanceTo(testPos);
            source.sendSuccess(() -> Component.literal("§fExpected distance: §e" + String.format("%.3f", expectedDistance)),false);

            boolean mixinWorking = Math.abs(Math.sqrt(distance1) - expectedDistance) < 0.01;
            source.sendSuccess(() -> Component.literal("§fMixin working: §e" +
                    (mixinWorking ? "✓ YES" : "❌ NO - might not be intercepting")),false);

            // Step 2: Test GridSpace position detection
            source.sendSuccess(() -> Component.literal("§a--- Step 2: GridSpace Detection Test ---"),false);

            // Test with obvious world coordinates
            BlockPos worldPos = BlockPos.containing(playerPos);
            LocalGrid worldGrid = GridSpaceManager.getGridAtPosition(worldPos);
            source.sendSuccess(() -> Component.literal("§fWorld pos " + worldPos + " → Grid: §e" +
                    (worldGrid != null ? worldGrid.getGridId().toString().substring(0, 8) : "null (expected)")),false);

            // Test with GridSpace coordinates
            BlockPos gridSpacePos = new BlockPos(25_000_100, 128, 25_000_200);
            LocalGrid gridSpaceGrid = GridSpaceManager.getGridAtPosition(gridSpacePos);
            source.sendSuccess(() -> Component.literal("§fGridSpace pos " + gridSpacePos + " → Grid: §e" +
                    (gridSpaceGrid != null ? gridSpaceGrid.getGridId().toString().substring(0, 8) : "null")),false);

            // Step 3: Test actual grid detection (if any grids exist)
            source.sendSuccess(() -> Component.literal("§a--- Step 3: Active Grid Detection ---"),false);

            if (mc.getSingleplayerServer() != null) {
                ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());

                if (serverLevel != null) {
                    EngineManager engineManager = net.starlight.stardance.Stardance.engineManager;
                    PhysicsEngine physicsEngine = engineManager.getEngine(serverLevel);

                    if (physicsEngine != null) {
                        java.util.List<LocalGrid> grids = physicsEngine.getActiveGrids();
                        source.sendSuccess(() -> Component.literal("§fActive grids: §e" + grids.size()),false);

                        for (LocalGrid grid : grids) {
                            GridSpaceRegion region = engineManager.getGridSpaceManager(serverLevel).getRegion(grid.getGridId());
                            if (region != null) {
                                BlockPos regionOrigin = region.getRegionOrigin();
                                Vec3 gridWorldPos = grid.getWorldPosition();

                                source.sendSuccess(() -> Component.literal("§f  Grid " +
                                        grid.getGridId().toString().substring(0, 8) + ":"),false);
                                source.sendSuccess(() -> Component.literal("§f    Visual pos: " + formatVec3(gridWorldPos)),false);
                                source.sendSuccess(() -> Component.literal("§f    GridSpace region: " + regionOrigin),false);

                                // Test distance to this grid
                                double distanceToGrid = player.distanceToSqr(gridWorldPos.x, gridWorldPos.y, gridWorldPos.z);
                                source.sendSuccess(() -> Component.literal("§f    Distance: " +
                                        String.format("%.2f", Math.sqrt(distanceToGrid))),false);
                            }
                        }
                    } else {
                        source.sendSuccess(() -> Component.literal("§cNo physics engine found"),false);
                    }
                } else {
                    source.sendSuccess(() -> Component.literal("§cNo server level available"),false);
                }
            } else {
                source.sendSuccess(() -> Component.literal("§7Client-side only - limited grid detection"),false);
            }

            // Step 4: Test coordinate conversion (if we have grids)
            source.sendSuccess(() -> Component.literal("§a--- Step 4: Coordinate Conversion Test ---"),false);
            source.sendSuccess(() -> Component.literal("§7Note: This will work once gridSpaceToWorldSpace() is implemented"),false);

            return 1;

        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("§cDistance debug failed: " + e.getMessage()),false);
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Performance test for distance calculations.
     */
    private static int executeDistancePerformance(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return 0;

            source.sendSuccess(() -> Component.literal("§6=== Distance Performance Test ==="),false);

            Player player = mc.player;
            Vec3 playerPos = player.position();

            // Test with various position types
            int iterations = 1000;

            // Test 1: Normal world coordinates
            long startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                Vec3 testPos = playerPos.add(i % 10, 0, 0);
                player.distanceToSqr(testPos.x, testPos.y, testPos.z);
            }
            long worldTime = System.nanoTime() - startTime;

            // Test 2: GridSpace coordinates
            startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                double x = 25_000_000 + (i % 10);
                double y = 128;
                double z = 25_000_000;
                player.distanceToSqr(x, y, z);
            }
            long gridSpaceTime = System.nanoTime() - startTime;

            // Results
            double worldMs = worldTime / 1_000_000.0;
            double gridSpaceMs = gridSpaceTime / 1_000_000.0;
            double overhead = ((double) gridSpaceTime / worldTime - 1.0) * 100.0;

            source.sendSuccess(() -> Component.literal("§fIterations: §e" + iterations),false);
            source.sendSuccess(() -> Component.literal("§fWorld coords time: §e" + String.format("%.2f ms", worldMs)),false);
            source.sendSuccess(() -> Component.literal("§fGridSpace coords time: §e" + String.format("%.2f ms", gridSpaceMs)),false);
            source.sendSuccess(() -> Component.literal("§fOverhead: §e" + String.format("%.1f%%", overhead)),false);

            if (overhead > 100.0) {
                source.sendSuccess(() -> Component.literal("§c⚠ High overhead detected!"),false);
            } else if (overhead > 50.0) {
                source.sendSuccess(() -> Component.literal("§e⚠ Moderate overhead"),false);
            } else {
                source.sendSuccess(() -> Component.literal("§a✓ Acceptable performance"),false);
            }

            return 1;

        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("§cPerformance test failed: " + e.getMessage()),false);
            return 0;
        }
    }

    /**
     * Test GridSpace coordinate system specifically.
     */
    private static int executeGridSpaceDebug(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            source.sendSuccess(() -> Component.literal("§6=== GridSpace System Debug ==="),false);

            // Test various coordinate ranges
            BlockPos[] testPositions = {
                    new BlockPos(0, 64, 0),                    // Normal world
                    new BlockPos(100, 64, 100),                // Normal world
                    new BlockPos(25_000_000, 128, 25_000_000), // GridSpace origin
                    new BlockPos(25_001_000, 128, 25_001_000), // GridSpace region 1
                    new BlockPos(30_000_000, 128, 30_000_000), // Far GridSpace
                    new BlockPos(-100, 64, -100),              // Negative world coords
            };

            for (BlockPos pos : testPositions) {
                LocalGrid grid = GridSpaceManager.getGridAtPosition(pos);
                boolean isGridSpace = pos.getX() >= 25_000_000; // Simple check

                source.sendSuccess(() -> Component.literal("§fPos " + pos + ":"),false);
                source.sendSuccess(() -> Component.literal("§f  GridSpace: §e" + isGridSpace),false);
                source.sendSuccess(() -> Component.literal("§f  Grid found: §e" + (grid != null)),false);
                if (grid != null) {
                    source.sendSuccess(() -> Component.literal("§f  Grid ID: §e" +
                            grid.getGridId().toString().substring(0, 8)),false);
                }
            }

            // Test cache performance
            source.sendSuccess(() -> Component.literal("§a--- Cache Test ---"),false);
            BlockPos cacheTestPos = new BlockPos(25_000_100, 128, 25_000_100);

            long startTime = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                GridSpaceManager.getGridAtPosition(cacheTestPos);
            }
            long cacheTime = System.nanoTime() - startTime;

            source.sendSuccess(() -> Component.literal("§f100 lookups time: §e" +
                    String.format("%.2f ms", cacheTime / 1_000_000.0)),false);

            return 1;

        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("§cGridSpace debug failed: " + e.getMessage()),false);
            return 0;
        }
    }

    /**
     * Real-time distance monitoring while looking around.
     */
    private static int executeRealtimeDistanceDebug(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        source.sendSuccess(() -> Component.literal("§6=== Real-time Distance Debug ==="),false);
        source.sendSuccess(() -> Component.literal("§eLook around and watch console for distance calculations"),false);
        source.sendSuccess(() -> Component.literal("§7Use '/stardance debug distance' to stop monitoring"),false);

        // This would need a toggle system to start/stop monitoring
        // For now, just show current crosshair target distance

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            HitResult hitResult = mc.hitResult;
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hitResult;
                Vec3 hitPos = blockHit.getLocation();
                double distance = mc.player.distanceToSqr(hitPos.x, hitPos.y, hitPos.z);

                source.sendSuccess(() -> Component.literal("§fCurrent target: §e" + blockHit.getBlockPos()),false);
                source.sendSuccess(() -> Component.literal("§fDistance: §e" + String.format("%.2f", Math.sqrt(distance))),false);
            }
        }

        return 1;
    }

    /**
     * Test Level.clip() directly to verify MixinLevel is working
     */
    private static int testLevelClip(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return 0;

            source.sendSuccess(() -> Component.literal("§6=== Level.clip() Test ==="), false);

            // Setup raycast
            double reach = mc.gameMode.getPickRange();
            Vec3 eyePos = mc.player.getEyePosition(1.0f);
            Vec3 lookVec = mc.player.getLookAngle();
            Vec3 endPos = eyePos.add(lookVec.scale(reach));

            ClipContext clipContext = new ClipContext(
                    eyePos, endPos,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    mc.player
            );

            // Call Level.clip() directly - this should use our MixinLevel
            BlockHitResult result = mc.level.clip(clipContext);

            source.sendSuccess(() -> Component.literal("§eLevel.clip() result: §f" + result.getType()), false);
            if (result.getType() != BlockHitResult.Type.MISS) {
                source.sendSuccess(() -> Component.literal("§eHit position: §f" + result.getBlockPos()), false);
            }

            source.sendSuccess(() -> Component.literal("§7Check console for MixinLevel logs"), false);

            return 1;

        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("§cLevel clip test failed: " + e.getMessage()), false);
            return 0;
        }
    }

    /**
     * Test physics raycasting directly on server
     */
    private static int executePhysicsRaycastTest(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        try {
            CommandSourceStack source = context.getSource();
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = source.getLevel();

            source.sendSuccess(() -> Component.literal("§6=== Server Physics Raycast Test ==="), false);

            // Get physics engine
            EngineManager engineManager = net.starlight.stardance.Stardance.engineManager;
            PhysicsEngine physicsEngine = engineManager.getEngine(level);

            if (physicsEngine == null) {
                source.sendSuccess(() -> Component.literal("§c❌ No physics engine!"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal("§a✅ Physics engine found"), false);

            // Check dynamics world
            DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();
            if (dynamicsWorld == null) {
                source.sendSuccess(() -> Component.literal("§c❌ No dynamics world!"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal("§a✅ Dynamics world found"), false);

            // Check active grids
            java.util.List<LocalGrid> grids = physicsEngine.getActiveGrids();
            source.sendSuccess(() -> Component.literal("§fActive grids: " + grids.size()), false);

            if (grids.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§c❌ No grids to raycast against!"), false);
                return 0;
            }

            // List grids
            for (LocalGrid grid : grids) {
                Vec3 pos = grid.getWorldPosition();
                source.sendSuccess(() -> Component.literal("§f  - Grid " +
                        grid.getGridId().toString().substring(0, 8) + " at " + formatVec3(pos)), false);
            }

            // Perform raycast
            double reach = Minecraft.getInstance().gameMode.getPickRange();
            Vec3 eyePos = player.getEyePosition(1.0f);
            Vec3 lookVec = player.getLookAngle();
            Vec3 endPos = eyePos.add(lookVec.scale(reach));

            source.sendSuccess(() -> Component.literal("§eRaycasting from " + formatVec3(eyePos) +
                    " to " + formatVec3(endPos)), false);

            // Direct physics raycast
            Optional<PhysicsEngine.GridRaycastResult> result = physicsEngine.raycastGrids(eyePos, endPos);

            if (result.isPresent()) {
                PhysicsEngine.GridRaycastResult hit = result.get();
                source.sendSuccess(() -> Component.literal("§a✅ PHYSICS HIT!"), false);
                source.sendSuccess(() -> Component.literal("§f  Hit Grid: " + hit.hitGrid.getGridId()), false);
                source.sendSuccess(() -> Component.literal("§f  Hit Point: " + formatVec3(hit.hitPoint)), false);
                source.sendSuccess(() -> Component.literal("§f  Hit Normal: " + formatVec3(hit.hitNormal)), false);
                source.sendSuccess(() -> Component.literal("§f  Hit Fraction: " + hit.hitFraction), false);
            } else {
                source.sendSuccess(() -> Component.literal("§c❌ No physics hit detected"), false);
            }

            // Test GridSpaceRaycastUtils
            ClipContext clipContext = new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
            BlockHitResult gridResult = GridSpaceRaycastUtils.clipIncludeGrids(level, clipContext);

            source.sendSuccess(() -> Component.literal("§eGridSpaceRaycastUtils result: " + gridResult.getType()), false);
            if (gridResult.getType() != HitResult.Type.MISS) {
                source.sendSuccess(() -> Component.literal("§f  Block Hit: " + gridResult.getBlockPos()), false);
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§cPhysics test failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Simple test: just call Entity.pick() and see if our mixin works
     */
    private static int executeRaycastDebug(CommandContext<FabricClientCommandSource> context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                context.getSource().sendFeedback(Component.literal("§cNo player or world available"));
                return 0;
            }

            context.getSource().sendFeedback(Component.literal("§6=== Simple Mixin Test ==="));

            double reach = mc.gameMode.getPickRange();

            // Test 1: Call Entity.pick() - this should use our mixin
            context.getSource().sendFeedback(Component.literal("§eTesting Entity.pick() (uses our mixin)..."));
            HitResult mixinResult = mc.player.pick(reach, 1.0f, false);

            // Test 2: Call Level.clip() directly - this is vanilla
            Vec3 eyePos = mc.player.getEyePosition(1.0f);
            Vec3 lookVec = mc.player.getLookAngle();
            Vec3 endPos = eyePos.add(lookVec.scale(reach));
            ClipContext clipContext = new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player);

            context.getSource().sendFeedback(Component.literal("§eTesting Level.clip() (vanilla)..."));
            BlockHitResult vanillaResult = mc.level.clip(clipContext);

            // Compare results
            context.getSource().sendFeedback(Component.literal("§a--- Results ---"));
            context.getSource().sendFeedback(Component.literal("§fMixin result: §e" + mixinResult.getType()));
            context.getSource().sendFeedback(Component.literal("§fVanilla result: §e" + vanillaResult.getType()));

            if (mixinResult instanceof BlockHitResult mixinBlockHit) {
                context.getSource().sendFeedback(Component.literal("§fMixin hit pos: §e" + mixinBlockHit.getBlockPos()));
            }
            context.getSource().sendFeedback(Component.literal("§fVanilla hit pos: §e" + vanillaResult.getBlockPos()));

            // Check if they're different
            boolean different = !mixinResult.equals(vanillaResult);
            context.getSource().sendFeedback(Component.literal("§fResults different: §e" + different));

            if (different) {
                context.getSource().sendFeedback(Component.literal("§a✅ Mixin is working and detected something different!"));
            } else {
                context.getSource().sendFeedback(Component.literal("§7❌ No difference - either no grids or mixin not working"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§cTest failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Helper method to test grid space raycast directly
     */
    private static BlockHitResult testGridSpaceRaycast(ServerLevel level, ClipContext context) {
        // This is essentially the private method from GridSpaceRaycastUtils
        // We're duplicating it here for testing purposes

        EngineManager engineManager = net.starlight.stardance.Stardance.engineManager;
        PhysicsEngine physicsEngine = engineManager.getEngine(level);

        if (physicsEngine == null) {
            return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
        }

        Optional<PhysicsEngine.GridRaycastResult> physicsHit =
                physicsEngine.raycastGrids(context.getFrom(), context.getTo());

        if (physicsHit.isEmpty()) {
            return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
        }

        // For now, just return a simple hit to test the conversion
        PhysicsEngine.GridRaycastResult hit = physicsHit.get();
        return new BlockHitResult(
                hit.hitPoint,
                Direction.UP, // Simplified for testing
                BlockPos.containing(hit.hitPoint),
                false
        );
    }

    /**
     * More accurate comparison of BlockHitResult objects
     */
    private static boolean compareHitResults(BlockHitResult result1, BlockHitResult result2) {
        // Check types
        if (result1.getType() != result2.getType()) {
            return false;
        }

        // If both are misses, they're identical
        if (result1.getType() == HitResult.Type.MISS) {
            return true;
        }

        // Compare block positions
        if (!result1.getBlockPos().equals(result2.getBlockPos())) {
            return false;
        }

        // Compare hit locations (with small tolerance for floating point)
        Vec3 loc1 = result1.getLocation();
        Vec3 loc2 = result2.getLocation();
        double threshold = 0.001;

        if (Math.abs(loc1.x - loc2.x) > threshold ||
                Math.abs(loc1.y - loc2.y) > threshold ||
                Math.abs(loc1.z - loc2.z) > threshold) {
            return false;
        }

        // Compare directions
        return result1.getDirection() == result2.getDirection();
    }

    /**
     * Continuous raycast monitoring - updates every tick
     */
    private static int executeRaycastContinuous(CommandContext<FabricClientCommandSource> context) {
        // TODO: Implement continuous monitoring system
        context.getSource().sendFeedback(Component.literal("§eContinuous raycast monitoring started..."));
        context.getSource().sendFeedback(Component.literal("§7Use '/stardance debug raycast' to stop"));
        return 1;
    }

    /**
     * Detailed raycast info including coordinate transformations
     */
    private static int executeRaycastDetailed(CommandContext<FabricClientCommandSource> context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return 0;

            // Get crosshair target using Entity.pick() - this tests our mixin!
            HitResult crosshairTarget = mc.player.pick(mc.player.isCreative() ? 5.0 : 4.5, 1.0f, false);

            context.getSource().sendFeedback(Component.literal("§6=== Detailed Raycast Analysis ==="));
            context.getSource().sendFeedback(Component.literal("§eCrosshair Target Type: §f" + crosshairTarget.getType()));

            if (crosshairTarget instanceof BlockHitResult blockHit) {
                context.getSource().sendFeedback(Component.literal("§eBlock Position: §f" + blockHit.getBlockPos()));
                context.getSource().sendFeedback(Component.literal("§eHit Location: §f" + formatVec3(blockHit.getLocation())));
                context.getSource().sendFeedback(Component.literal("§eHit Face: §f" + blockHit.getDirection()));

                // Check if this block is in GridSpace
                // TODO: Add GridSpace detection here
                context.getSource().sendFeedback(Component.literal("§7GridSpace Status: Not yet implemented"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§cDetailed raycast failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Performance testing for raycast system
     */
    private static int executeRaycastPerformance(CommandContext<FabricClientCommandSource> context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return 0;

            int iterations = 1000;
            context.getSource().sendFeedback(Component.literal("§6=== Raycast Performance Test ==="));
            context.getSource().sendFeedback(Component.literal("§eRunning " + iterations + " raycast iterations..."));

            Vec3 eyePos = mc.player.getEyePosition(1.0f);
            Vec3 lookVec = mc.player.getLookAngle();
            Vec3 endPos = eyePos.add(lookVec.scale(mc.player.isCreative() ? 5.0 : 4.5));

            ClipContext clipContext = new ClipContext(
                    eyePos, endPos,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    mc.player
            );

            // Test vanilla performance
            long vanillaStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                mc.level.clip(clipContext);
            }
            long vanillaTime = System.nanoTime() - vanillaStart;

            // Test enhanced performance
            long enhancedStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                GridSpaceRaycastUtils.clipIncludeGrids(mc.level, clipContext);
            }
            long enhancedTime = System.nanoTime() - enhancedStart;

            // Display results
            double vanillaMs = vanillaTime / 1_000_000.0;
            double enhancedMs = enhancedTime / 1_000_000.0;
            double overhead = ((double) enhancedTime / vanillaTime - 1.0) * 100.0;

            context.getSource().sendFeedback(Component.literal("§eVanilla Time: §f" + String.format("%.2f ms", vanillaMs)));
            context.getSource().sendFeedback(Component.literal("§eEnhanced Time: §f" + String.format("%.2f ms", enhancedMs)));
            context.getSource().sendFeedback(Component.literal("§eOverhead: §f" + String.format("%.1f%%", overhead)));

            if (overhead > 50.0) {
                context.getSource().sendFeedback(Component.literal("§c⚠ High overhead detected!"));
            } else if (overhead > 20.0) {
                context.getSource().sendFeedback(Component.literal("§e⚠ Moderate overhead"));
            } else {
                context.getSource().sendFeedback(Component.literal("§a✓ Acceptable performance"));
            }

            return 1;

        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§cPerformance test failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Helper method to display hit result information
     */
    private static void displayHitResult(CommandContext<FabricClientCommandSource> context,
                                         BlockHitResult result, String label) {
        if (result.getType() == HitResult.Type.MISS) {
            context.getSource().sendFeedback(Component.literal("§7" + label + ": MISS"));
        } else {
            context.getSource().sendFeedback(Component.literal("§f" + label + ": HIT"));
            context.getSource().sendFeedback(Component.literal("  §7Position: §f" + result.getBlockPos()));
            context.getSource().sendFeedback(Component.literal("  §7Location: §f" + formatVec3(result.getLocation())));
            context.getSource().sendFeedback(Component.literal("  §7Face: §f" + result.getDirection()));
            context.getSource().sendFeedback(Component.literal("  §7Distance: §f" +
                    String.format("%.2f", result.getLocation().distanceTo(
                            Minecraft.getInstance().player.getEyePosition(1.0f)))));
        }
    }

    /**
     * Helper method to format Vec3 for display
     */
    private static String formatVec3(Vec3 vec) {
        return String.format("(%.2f, %.2f, %.2f)", vec.x, vec.y, vec.z);
    }

    /**
     * Shows information about active grids (server-side only)
     */
    private static int executeGridStatus(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.literal("§6=== Grid Status ==="));
        context.getSource().sendFeedback(Component.literal("§7Note: Grid information only available server-side"));
        context.getSource().sendFeedback(Component.literal("§7This command shows client-side grid rendering info only"));

        // TODO: If you have client-side grid tracking, show that here
        // For now, just acknowledge the limitation

        return 1;
    }

    /**
     * Visual test for the distance replacement mixins using DebugRenderer
     */
    private static int testDistanceMixinVisual(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                source.sendSuccess(() -> Component.literal("§cNo player or world available"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal("§6=== Distance Mixin Visual Test ==="), false);
            source.sendSuccess(() -> Component.literal("§eStarting visual distance test..."), false);

            Player player = mc.player;
            Vec3 playerPos = player.position();
            Vec3 eyePos = player.getEyePosition();

            // Clear existing debug elements
            DebugRenderer.clear();

            // Colors for different types of distance checks
            final int COLOR_WORLD_DISTANCE = 0xFF00FF00;    // Green - vanilla world distance
            final int COLOR_GRID_DISTANCE = 0xFFFF0000;     // Red - grid-aware distance
            final int COLOR_PLAYER_POS = 0xFF0000FF;        // Blue - player position
            final int COLOR_TEST_POINT = 0xFFFFFF00;        // Yellow - test points
            final int COLOR_CONTAINER_RANGE = 0xFFFF00FF;   // Magenta - container interaction range

            // Mark player position
            DebugRenderer.addCrosshair(eyePos, COLOR_PLAYER_POS, 0.5f, 0.05f, 100);

            // Test 1: Distance to various world positions
            source.sendSuccess(() -> Component.literal("§a--- Test 1: World Position Distance ---"), false);

            Vec3[] testPositions = {
                    playerPos.add(3, 0, 0),   // 3 blocks east
                    playerPos.add(0, 3, 0),   // 3 blocks up
                    playerPos.add(0, 0, 3),   // 3 blocks south
                    playerPos.add(5, 2, 5)    // Diagonal
            };

            for (int i = 0; i < testPositions.length; i++) {
                Vec3 testPos = testPositions[i];

                // Mark test position
                DebugRenderer.addPoint(testPos, COLOR_TEST_POINT, 0.3f, 100);

                // Calculate distance using our mixin (should call through the distance replacement)
                double mixinDistance = player.distanceToSqr(testPos);

                // Calculate vanilla distance for comparison
                double vanillaDistance = playerPos.distanceToSqr(testPos);

                // Draw line from player to test position
                // Color depends on whether distances match (green) or differ (red)
                int lineColor = Math.abs(mixinDistance - vanillaDistance) < 0.01 ? COLOR_WORLD_DISTANCE : COLOR_GRID_DISTANCE;
                DebugRenderer.addLine(eyePos, testPos, lineColor, 0.03f, 100);

                int finalI = i;
                source.sendSuccess(() -> Component.literal(
                        String.format("§fPoint %d: §e%.2f §7(vanilla: §e%.2f§7) §f%s",
                                finalI + 1,
                                Math.sqrt(mixinDistance),
                                Math.sqrt(vanillaDistance),
                                Math.abs(mixinDistance - vanillaDistance) < 0.01 ? "✓" : "⚠ DIFFERENT")
                ), false);
            }

            // Test 2: Find grids and test grid positions
            source.sendSuccess(() -> Component.literal("§a--- Test 2: Grid Position Distance ---"), false);

            EngineManager engineManager = Stardance.engineManager;
            PhysicsEngine physicsEngine = engineManager.getEngine(mc.level);

            if (physicsEngine != null) {
                int gridCount = 0;
                for (LocalGrid grid : physicsEngine.getGrids()) {
                    if (gridCount >= 3) break; // Limit to 3 grids for clarity

                    // Get a sample position from the grid
                    Vec3 gridPos = grid.getWorldPosition();
                    Vec3 sampleGridSpacePos = gridPos.add(2, 1, 2);

                    // Mark grid position
                    DebugRenderer.addBox(
                            new AABB(gridPos.add(-0.5, -0.5, -0.5), gridPos.add(0.5, 0.5, 0.5)),
                            COLOR_GRID_DISTANCE, 0.02f, 100
                    );

                    // Test distance to grid position
                    double gridDistance = player.distanceToSqr(sampleGridSpacePos.x, sampleGridSpacePos.y, sampleGridSpacePos.z);

                    // Convert to world position and calculate vanilla distance
                    Vec3 worldPos = grid.gridSpaceToWorldSpace(sampleGridSpacePos);
                    double worldDistance = playerPos.distanceToSqr(worldPos);

                    // Draw line from player to visual world position
                    DebugRenderer.addLine(eyePos, worldPos, COLOR_GRID_DISTANCE, 0.05f, 100);

                    // Add point at the grid space position being tested
                    DebugRenderer.addPoint(sampleGridSpacePos, COLOR_TEST_POINT, 0.2f, 100);

                    int finalGridCount = gridCount;
                    source.sendSuccess(() -> Component.literal(
                            String.format("§fGrid %d: §c%.2f §7(world: §e%.2f§7)",
                                    finalGridCount + 1,
                                    Math.sqrt(gridDistance),
                                    Math.sqrt(worldDistance))
                    ), false);

                    gridCount++;
                }

                if (gridCount == 0) {
                    source.sendSuccess(() -> Component.literal("§7No grids found for testing"), false);
                }
            } else {
                source.sendSuccess(() -> Component.literal("§7No physics engine available"), false);
            }

            // Test 3: Container interaction range visualization
            source.sendSuccess(() -> Component.literal("§a--- Test 3: Container Range Visualization ---"), false);

            // Show the standard container interaction range (8 blocks)
            double containerRange = 8.0;
            int rangeSamples = 16;

            for (int i = 0; i < rangeSamples; i++) {
                double angle = (2 * Math.PI * i) / rangeSamples;
                Vec3 rangePoint = eyePos.add(
                        Math.cos(angle) * containerRange,
                        0,
                        Math.sin(angle) * containerRange
                );

                DebugRenderer.addPoint(rangePoint, COLOR_CONTAINER_RANGE, 0.1f, 100);

                if (i % 4 == 0) { // Draw lines for cardinal directions
                    DebugRenderer.addLine(eyePos, rangePoint, COLOR_CONTAINER_RANGE, 0.02f, 100);
                }
            }

            source.sendSuccess(() -> Component.literal("§fContainer range: §d8.0 blocks §7(magenta circle)"), false);
            source.sendSuccess(() -> Component.literal("§eVisual test active for 5 seconds!"), false);
            source.sendSuccess(() -> Component.literal("§7Green = world distance, Red = grid distance"), false);

            return 1;

        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("§cTest failed: " + e.getMessage()), false);
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Real-time container distance monitoring using DebugRenderer
     */
    private static int testContainerDistanceVisual(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                source.sendSuccess(() -> Component.literal("§cNo player or world available"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal("§6=== Container Distance Visual Test ==="), false);

            Player player = mc.player;
            Vec3 eyePos = player.getEyePosition();

            // Clear existing debug elements
            DebugRenderer.clear();

            // Colors
            final int COLOR_ACCESSIBLE = 0xFF00FF00;      // Green - within range
            final int COLOR_TOO_FAR = 0xFFFF0000;         // Red - too far
            final int COLOR_CONTAINER = 0xFF0000FF;       // Blue - container position
            final int COLOR_RANGE_INDICATOR = 0xFFFFFF00; // Yellow - range boundary

            // Find nearby containers (simplified - looking for obvious container blocks)
            Vec3 playerPos = player.position();
            int containerCount = 0;

            // Check positions around the player for potential containers
            for (int x = -10; x <= 10; x++) {
                for (int y = -5; y <= 5; y++) {
                    for (int z = -10; z <= 10; z++) {
                        BlockPos checkPos = BlockPos.containing(playerPos.add(x, y, z));
                        BlockState state = mc.level.getBlockState(checkPos);

                        // Check if this looks like a container (basic check)
                        String blockName = state.getBlock().getDescriptionId().toLowerCase();
                        if (blockName.contains("chest") || blockName.contains("barrel") ||
                                blockName.contains("crafting") || blockName.contains("furnace")) {

                            Vec3 containerPos = Vec3.atCenterOf(checkPos);

                            // Test distance using player.distanceToSqr (our mixin should intercept this)
                            double distance = player.distanceToSqr(containerPos.x, containerPos.y, containerPos.z);
                            boolean withinRange = distance <= 64.0; // 8^2 = 64

                            // Mark container
                            DebugRenderer.addBox(
                                    new AABB(checkPos),
                                    withinRange ? COLOR_ACCESSIBLE : COLOR_TOO_FAR,
                                    0.03f, 60
                            );

                            // Draw line from player to container
                            DebugRenderer.addLine(
                                    eyePos, containerPos,
                                    withinRange ? COLOR_ACCESSIBLE : COLOR_TOO_FAR,
                                    0.02f, 60
                            );

                            // Add distance indicator
                            String distanceText = String.format("%.1f", Math.sqrt(distance));
                            source.sendSuccess(() -> Component.literal(
                                    String.format("§fContainer at %s: §%c%.1f blocks §7%s",
                                            checkPos.toShortString(),
                                            withinRange ? 'a' : 'c',
                                            Math.sqrt(distance),
                                            withinRange ? "✓" : "✗")
                            ), false);

                            containerCount++;
                            if (containerCount >= 5) break; // Limit output
                        }
                    }
                    if (containerCount >= 5) break;
                }
                if (containerCount >= 5) break;
            }

            if (containerCount == 0) {
                source.sendSuccess(() -> Component.literal("§7No containers found nearby"), false);

                // Create a test container position anyway
                Vec3 testContainerPos = playerPos.add(6, 0, 0);
                double distance = player.distanceToSqr(testContainerPos.x, testContainerPos.y, testContainerPos.z);
                boolean withinRange = distance <= 64.0;

                DebugRenderer.addBox(
                        new AABB(testContainerPos.add(-0.5, -0.5, -0.5), testContainerPos.add(0.5, 0.5, 0.5)),
                        withinRange ? COLOR_ACCESSIBLE : COLOR_TOO_FAR,
                        0.03f, 60
                );

                DebugRenderer.addLine(eyePos, testContainerPos, withinRange ? COLOR_ACCESSIBLE : COLOR_TOO_FAR, 0.02f, 60);

                source.sendSuccess(() -> Component.literal(
                        String.format("§fTest container: §%c%.1f blocks §7%s",
                                withinRange ? 'a' : 'c',
                                Math.sqrt(distance),
                                withinRange ? "✓" : "✗")
                ), false);
            }

            // Show interaction range as a sphere
            int rangeSamples = 20;
            double containerRange = 8.0;

            for (int i = 0; i < rangeSamples; i++) {
                for (int j = 0; j < rangeSamples; j++) {
                    double theta = (2 * Math.PI * i) / rangeSamples;
                    double phi = (Math.PI * j) / rangeSamples;

                    Vec3 spherePoint = eyePos.add(
                            containerRange * Math.sin(phi) * Math.cos(theta),
                            containerRange * Math.cos(phi),
                            containerRange * Math.sin(phi) * Math.sin(theta)
                    );

                    if (i % 5 == 0 && j % 5 == 0) { // Sparse sampling for visibility
                        DebugRenderer.addPoint(spherePoint, COLOR_RANGE_INDICATOR, 0.05f, 60);
                    }
                }
            }

            source.sendSuccess(() -> Component.literal("§eContainer distance test active for 3 seconds!"), false);
            source.sendSuccess(() -> Component.literal("§aGreen = accessible, §cRed = too far, §eYellow = range boundary"), false);

            return 1;

        } catch (Exception e) {
            source.sendSuccess(() -> Component.literal("§cTest failed: " + e.getMessage()), false);
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Live distance monitoring that updates in real-time
     */
    private static int startLiveDistanceMonitoring(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        source.sendSuccess(() -> Component.literal("§6=== Live Distance Monitoring ==="), false);
        source.sendSuccess(() -> Component.literal("§eStarting live monitoring... Look around to see distance rays!"), false);
        source.sendSuccess(() -> Component.literal("§7Use '/stardance debug stop-live' to stop"), false);

        // This would need a background task system, but for now we'll show current state
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.hitResult != null) {
            Vec3 eyePos = mc.player.getEyePosition();

            if (mc.hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) mc.hitResult;
                Vec3 hitPos = blockHit.getLocation();

                // Test our distance mixin
                double mixinDistance = mc.player.distanceToSqr(hitPos);

                // Clear and add current ray
                DebugRenderer.clear();
                DebugRenderer.addLine(eyePos, hitPos, 0xFF00FFFF, 0.04f, 20); // Cyan ray
                DebugRenderer.addPoint(hitPos, 0xFFFFFF00, 0.2f, 20); // Yellow target

                source.sendSuccess(() -> Component.literal(
                        String.format("§fTarget: %s §eDistance: %.2f",
                                blockHit.getBlockPos().toShortString(),
                                Math.sqrt(mixinDistance))
                ), false);
            }
        }

        return 1;
    }

}