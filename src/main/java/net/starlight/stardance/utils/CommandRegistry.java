package net.starlight.stardance.utils;

import com.bulletphysics.collision.dispatch.CollisionWorld;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.debug.CollisionDebugger;
import net.starlight.stardance.physics.PhysicsEngine;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.util.Optional;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
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

        registerStage12DebugCommands();
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

    /**
     * Enhanced debug commands for testing Stage 1 & 2 raycast integration.
     * Add these to your existing CommandRegistry class.
     */
    public static void registerStage12DebugCommands() {

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Register all interaction debug commands under one structure
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("stardance")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("debug")
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("interaction")

                                            // Test grid containment
                                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("gridcontains")
                                                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("x", DoubleArgumentType.doubleArg())
                                                            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("y", DoubleArgumentType.doubleArg())
                                                                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("z", DoubleArgumentType.doubleArg())
                                                                            .executes(CommandRegistry::testGridContains)
                                                                    )
                                                            )
                                                    )
                                            )

                                            // Test physics raycast
                                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("physicsraycast")
                                                    .executes(CommandRegistry::testPhysicsRaycast)
                                            )

                                            // Test clipIncludeGrids
                                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cliptest")
                                                    .executes(CommandRegistry::testClipIncludeGrids)
                                            )

                                            // Compare raycast results
                                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("raycastcompare")
                                                    .executes(CommandRegistry::testRaycastComparison)
                                            )

                                            // Test Level.clip integration
                                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("levelclip")
                                                    .executes(CommandRegistry::testLevelClipIntegration)
                                            )

                                            // Full test suite
                                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fulltest")
                                                    .executes(CommandRegistry::testFullStage12Integration)
                                            )
                                    )
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("precision")
                                            .executes(context -> {
                                                Player player = Minecraft.getInstance().player;
                                                if (player != null) {
                                                    // Create a raycast context from player's look direction
                                                    Vec3 start = player.getEyePosition();
                                                    Vec3 direction = player.getViewVector(1.0f);
                                                    Vec3 end = start.add(direction.scale(10.0));

                                                    ClipContext rayContext = new ClipContext(
                                                            start, end,
                                                            ClipContext.Block.OUTLINE,
                                                            ClipContext.Fluid.NONE,
                                                            player
                                                    );

                                                    GridSpaceRaycastUtils.testPrecisionFix(player.level(), rayContext);
                                                }
                                                return 1;
                                            })
                                    )
                            )

            );
        });
    }

    /**
     * Fixed testGridContains method
     */
    private static int testGridContains(CommandContext<FabricClientCommandSource> context) {
        try {
            double x = DoubleArgumentType.getDouble(context, "x");
            double y = DoubleArgumentType.getDouble(context, "y");
            double z = DoubleArgumentType.getDouble(context, "z");

            Player player = context.getSource().getPlayer();
            Level level = player.level();
            Vec3 testPos = new Vec3(x, y, z);

            context.getSource().sendFeedback(Component.literal("§6[Stage1&2] Testing containsWorldPosition at " + testPos));

            PhysicsEngine physics = engineManager.getEngine(level);
            if (physics == null) {
                context.getSource().sendFeedback(Component.literal("§c[ERROR] No physics engine found"));
                return 0;
            }

            // Test each grid - FIXED: Use %s instead of %d for UUID
            boolean foundGrid = false;
            for (LocalGrid grid : physics.getGrids()) {
                boolean contains = grid.containsWorldPosition(testPos);
                context.getSource().sendFeedback(Component.literal(
                        String.format("§7Grid %s: %s", grid.getGridId(), contains ? "§aCONTAINS" : "§7does not contain")
                ));
                if (contains) foundGrid = true;
            }

            context.getSource().sendFeedback(Component.literal(
                    foundGrid ? "§a[SUCCESS] Position found in grid(s)" : "§7[INFO] Position not in any grid"
            ));

            return 1;
        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§c[ERROR] " + e.getMessage()));
            e.printStackTrace(); // Add this to see the full stack trace
            return 0;
        }
    }

    /**
     * Fixed testClipIncludeGrids method
     */
    private static int testClipIncludeGrids(CommandContext<FabricClientCommandSource> context) {
        try {
            Player player = context.getSource().getPlayer();
            Level level = player.level();

            // Create raycast context
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getViewVector(1.0f);
            Vec3 endPos = eyePos.add(lookVec.scale(10.0));

            ClipContext clipContext = new ClipContext(
                    eyePos, endPos,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
            );

            context.getSource().sendFeedback(Component.literal("§6[Stage1&2] Testing clipIncludeGrids"));

            // Test our new method
            BlockHitResult result = GridSpaceRaycastUtils.clipIncludeGrids(level, clipContext);

            if (result.getType() == HitResult.Type.BLOCK) {
                context.getSource().sendFeedback(Component.literal("§a[SUCCESS] clipIncludeGrids hit!"));
                context.getSource().sendFeedback(Component.literal("§7Hit Position: " + result.getBlockPos()));
                context.getSource().sendFeedback(Component.literal("§7Hit Location: " + result.getLocation()));
                context.getSource().sendFeedback(Component.literal("§7Hit Direction: " + result.getDirection()));

                // FIXED: Handle UUID grid IDs properly
                PhysicsEngine physics = engineManager.getEngine(level);
                boolean isGridSpaceCoordinate = false;
                String gridInfo = "";

                if (physics != null) {
                    // Find which grid this coordinate belongs to
                    Vec3 blockPosVec = new Vec3(result.getBlockPos().getX(), result.getBlockPos().getY(), result.getBlockPos().getZ());

                    for (LocalGrid grid : physics.getGrids()) {
                        try {
                            var transformResult = TransformationAPI.getInstance().gridSpaceToWorld(blockPosVec, grid);
                            if (transformResult != null) {
                                isGridSpaceCoordinate = true;
                                // FIXED: Use %s for UUID and handle potential issues
                                gridInfo = String.format("Grid %s - GridSpace: %s",
                                        grid.getGridId().toString(), result.getBlockPos());
                                break;
                            }
                        } catch (Exception e) {
                            context.getSource().sendFeedback(Component.literal("§c[ERROR] Transform failed for grid " + grid.getGridId() + ": " + e.getMessage()));
                        }
                    }
                }

                if (isGridSpaceCoordinate) {
                    context.getSource().sendFeedback(Component.literal("§a[SUCCESS] Hit is GridSpace coordinate!"));
                    context.getSource().sendFeedback(Component.literal("§7" + gridInfo));
                } else {
                    context.getSource().sendFeedback(Component.literal("§7[INFO] Hit is vanilla world coordinate"));
                }
            } else {
                context.getSource().sendFeedback(Component.literal("§7[INFO] clipIncludeGrids missed"));
            }

            return 1;
        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§c[ERROR] " + e.getMessage()));
            e.printStackTrace(); // Add this for debugging
            return 0;
        }
    }

    /**
     * Test JBullet physics raycast directly
     */
    private static int testPhysicsRaycast(CommandContext<FabricClientCommandSource> context) {
        try {
            Player player = context.getSource().getPlayer();
            Level level = player.level();

            // Get player's look direction
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getViewVector(1.0f);
            Vec3 endPos = eyePos.add(lookVec.scale(10.0)); // 10 block reach

            context.getSource().sendFeedback(Component.literal("§6[Stage1&2] Testing JBullet physics raycast"));
            context.getSource().sendFeedback(Component.literal("§7From: " + eyePos));
            context.getSource().sendFeedback(Component.literal("§7To: " + endPos));

            PhysicsEngine physics = engineManager.getEngine(level);
            if (physics == null) {
                context.getSource().sendFeedback(Component.literal("§c[ERROR] No physics engine found"));
                return 0;
            }

            // Perform JBullet raycast
            Vector3f startBullet = new Vector3f((float)eyePos.x, (float)eyePos.y, (float)eyePos.z);
            Vector3f endBullet = new Vector3f((float)endPos.x, (float)endPos.y, (float)endPos.z);

            CollisionWorld.ClosestRayResultCallback rayCallback = new CollisionWorld.ClosestRayResultCallback(startBullet, endBullet);
            physics.getDynamicsWorld().rayTest(startBullet, endBullet, rayCallback);

            if (rayCallback.hasHit()) {
                Vector3f hitPoint = rayCallback.hitPointWorld;
                Vector3f hitNormal = rayCallback.hitNormalWorld;

                context.getSource().sendFeedback(Component.literal("§a[SUCCESS] JBullet raycast hit!"));
                context.getSource().sendFeedback(Component.literal(String.format("§7Hit Point: %.2f, %.2f, %.2f",
                        hitPoint.x, hitPoint.y, hitPoint.z)));
                context.getSource().sendFeedback(Component.literal(String.format("§7Hit Normal: %.2f, %.2f, %.2f",
                        hitNormal.x, hitNormal.y, hitNormal.z)));
            } else {
                context.getSource().sendFeedback(Component.literal("§7[INFO] JBullet raycast missed"));
            }

            return 1;
        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§c[ERROR] " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Compare vanilla raycast vs grid-aware raycast
     */
    private static int testRaycastComparison(CommandContext<FabricClientCommandSource> context) {
        try {
            Player player = context.getSource().getPlayer();
            Level level = player.level();

            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getViewVector(1.0f);
            Vec3 endPos = eyePos.add(lookVec.scale(10.0));

            ClipContext clipContext = new ClipContext(
                    eyePos, endPos,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
            );

            context.getSource().sendFeedback(Component.literal("§6[Stage1&2] Comparing vanilla vs grid raycast"));

            // Vanilla raycast (bypass our system)
            BlockHitResult vanillaResult = performVanillaRaycastDirect(level, clipContext);

            // Grid-aware raycast
            BlockHitResult gridResult = GridSpaceRaycastUtils.clipIncludeGrids(level, clipContext);

            // Display results
            context.getSource().sendFeedback(Component.literal("§e--- Vanilla Raycast ---"));
            displayHitResult(context, vanillaResult);

            context.getSource().sendFeedback(Component.literal("§e--- Grid-Aware Raycast ---"));
            displayHitResult(context, gridResult);

            // Compare distances
            if (vanillaResult.getType() == HitResult.Type.BLOCK && gridResult.getType() == HitResult.Type.BLOCK) {
                double vanillaDist = vanillaResult.getLocation().distanceToSqr(eyePos);
                double gridDist = gridResult.getLocation().distanceToSqr(eyePos);

                context.getSource().sendFeedback(Component.literal(String.format(
                        "§7Distances - Vanilla: %.2f, Grid: %.2f",
                        Math.sqrt(vanillaDist), Math.sqrt(gridDist)
                )));

                if (gridDist < vanillaDist) {
                    context.getSource().sendFeedback(Component.literal("§a[SUCCESS] Grid hit is closer!"));
                } else if (vanillaDist < gridDist) {
                    context.getSource().sendFeedback(Component.literal("§7[INFO] Vanilla hit is closer"));
                } else {
                    context.getSource().sendFeedback(Component.literal("§7[INFO] Same distance"));
                }
            }

            return 1;
        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§c[ERROR] " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Test Level.clip override integration
     */
    private static int testLevelClipIntegration(CommandContext<FabricClientCommandSource> context) {
        try {
            Player player = context.getSource().getPlayer();
            Level level = player.level();

            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getViewVector(1.0f);
            Vec3 endPos = eyePos.add(lookVec.scale(10.0));

            ClipContext clipContext = new ClipContext(
                    eyePos, endPos,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
            );

            context.getSource().sendFeedback(Component.literal("§6[Stage1&2] Testing Level.clip override"));

            // This should use our MixinLevel override
            BlockHitResult result = level.clip(clipContext);

            context.getSource().sendFeedback(Component.literal("§7Level.clip result:"));
            displayHitResult(context, result);

            // Test if it matches our direct call
            BlockHitResult directResult = GridSpaceRaycastUtils.clipIncludeGrids(level, clipContext);

            boolean matches = (result.getType() == directResult.getType()) &&
                    (result.getType() != HitResult.Type.BLOCK ||
                            result.getBlockPos().equals(directResult.getBlockPos()));

            context.getSource().sendFeedback(Component.literal(
                    matches ? "§a[SUCCESS] Level.clip override working!" : "§c[ERROR] Override mismatch!"
            ));

            return 1;
        } catch (Exception e) {
            context.getSource().sendFeedback(Component.literal("§c[ERROR] " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Full integration test of all Stage 1 & 2 components
     */
    private static int testFullStage12Integration(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.literal("§6[Stage1&2] Running full integration test..."));

        // Run all tests in sequence
        testPhysicsRaycast(context);
        testClipIncludeGrids(context);
        testRaycastComparison(context);
        testLevelClipIntegration(context);

        context.getSource().sendFeedback(Component.literal("§a[Stage1&2] Full integration test complete!"));
        return 1;
    }

    /**
     * Helper: Display hit result info
     */
    private static void displayHitResult(CommandContext<FabricClientCommandSource> context, BlockHitResult result) {
        if (result.getType() == HitResult.Type.BLOCK) {
            context.getSource().sendFeedback(Component.literal("§a  HIT: " + result.getBlockPos()));
            context.getSource().sendFeedback(Component.literal("§7  Location: " + result.getLocation()));
            context.getSource().sendFeedback(Component.literal("§7  Direction: " + result.getDirection()));
        } else {
            context.getSource().sendFeedback(Component.literal("§7  MISS"));
        }
    }

    /**
     * Helper: Perform vanilla raycast bypassing our system
     */
    private static BlockHitResult performVanillaRaycastDirect(Level level, ClipContext context) {
        // Use BlockGetter.traverseBlocks directly to bypass Level.clip override
        return BlockGetter.traverseBlocks(
                context.getFrom(),
                context.getTo(),
                context,
                (clipContext, pos) -> {
                    BlockState blockState = level.getBlockState(pos);
                    FluidState fluidState = level.getFluidState(pos);

                    VoxelShape blockShape = clipContext.getBlockShape(blockState, level, pos);
                    BlockHitResult blockHit = blockShape.clip(clipContext.getFrom(), clipContext.getTo(), pos);

                    VoxelShape fluidShape = clipContext.getFluidShape(fluidState, level, pos);
                    BlockHitResult fluidHit = fluidShape.clip(clipContext.getFrom(), clipContext.getTo(), pos);

                    double blockDist = blockHit == null ? Double.MAX_VALUE :
                            clipContext.getFrom().distanceToSqr(blockHit.getLocation());
                    double fluidDist = fluidHit == null ? Double.MAX_VALUE :
                            clipContext.getFrom().distanceToSqr(fluidHit.getLocation());

                    return blockDist <= fluidDist ? blockHit : fluidHit;
                },
                (clipContext) -> {
                    Vec3 vec3 = clipContext.getFrom().subtract(clipContext.getTo());
                    return BlockHitResult.miss(
                            clipContext.getTo(),
                            Direction.getNearest(vec3.x, vec3.y, vec3.z),
                            BlockPos.containing(clipContext.getTo())
                    );
                }
        );
    }
}