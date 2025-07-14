package net.starlight.stardance.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.StardanceGameUtils;
import net.starlight.stardance.utils.TransformationAPI;

import java.util.Optional;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Enhanced debugging system for interaction troubleshooting.
 */
public class InteractionDebugManager {

    private static boolean interactionDebuggingEnabled = false;
    private static boolean raycastDebuggingEnabled = false;
    private static boolean coordinateDebuggingEnabled = false;
    private static boolean mixinDebuggingEnabled = false;

    // ===============================================
    // DEBUG COMMAND IMPLEMENTATIONS
    // ===============================================

    /**
     * Test the complete interaction pipeline for the player's current target.
     */
    public static int debugInteractionPipeline(Player player) {
        if (player == null) {
            return 0;
        }

        player.sendSystemMessage(Component.literal("§6=== INTERACTION PIPELINE DEBUG ==="));
        
        // 1. Test player's current raycast
        HitResult hitResult = player.pick(5.0, 1.0f, false);
        
        player.sendSystemMessage(Component.literal("§71. Player Raycast Result:"));
        player.sendSystemMessage(Component.literal("  Type: " + hitResult.getType().name()));
        
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos targetPos = blockHit.getBlockPos();
            Vec3 hitLocation = blockHit.getLocation();
            
            player.sendSystemMessage(Component.literal("  §7Target Position: §f" + targetPos));
            player.sendSystemMessage(Component.literal("  §7Hit Location: §f" + String.format("(%.2f, %.2f, %.2f)", 
                hitLocation.x, hitLocation.y, hitLocation.z)));
            
            // 2. Test coordinate transformation
            debugCoordinateTransformation(player, targetPos);
            
            // 3. Test block state access
            debugBlockStateAccess(player, targetPos);
            
            // 4. Test distance calculations
            debugDistanceCalculations(player, targetPos);
            
            // 5. Test grid detection
            debugGridDetection(player, targetPos);
            
        } else {
            player.sendSystemMessage(Component.literal("  §cNo block hit detected"));
        }
        
        return 1;
    }

    /**
     * Test coordinate transformation for a specific position.
     */
    public static int debugCoordinateTransformation(Player player, BlockPos pos) {
        player.sendSystemMessage(Component.literal("§72. Coordinate Transformation:"));
        
        try {
            Vec3 worldVec = new Vec3(pos.getX(), pos.getY(), pos.getZ());
            
            // Test world -> GridSpace transformation
            Optional<TransformationAPI.GridSpaceTransformResult> transform = 
                TransformationAPI.getInstance().worldToGridSpace(
                    new Vec3(worldVec.x, worldVec.y, worldVec.z), player.level());
            
            if (transform.isPresent()) {
                TransformationAPI.GridSpaceTransformResult result = transform.get();
                
                player.sendSystemMessage(Component.literal("  §a✓ Position is on grid: §f" + result.grid.getGridId()));
                player.sendSystemMessage(Component.literal("  §7GridSpace Pos: §f" + result.gridSpacePos));
                player.sendSystemMessage(Component.literal("  §7GridSpace Vec: §f" + String.format("(%.3f, %.3f, %.3f)",
                    result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z)));
                player.sendSystemMessage(Component.literal("  §7Grid-Local Pos: §f" + result.gridLocalPos));
                
                // Test round-trip transformation
                Vec3 backToWorld = TransformationAPI.getInstance().gridSpaceToWorld(result.gridSpaceVec, result.grid);
                double error = worldVec.distanceTo(backToWorld);
                
                player.sendSystemMessage(Component.literal("  §7Round-trip error: §f" + String.format("%.6f blocks", error)));
                
                if (error > 0.001) {
                    player.sendSystemMessage(Component.literal("  §c⚠ High transformation error!"));
                }
                
            } else {
                player.sendSystemMessage(Component.literal("  §7Position is not on any grid"));
            }
            
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("  §cError in transformation: " + e.getMessage()));
            SLogger.log("InteractionDebugManager", "Transformation error: " + e.getMessage());
        }
        
        return 1;
    }

    /**
     * Test block state access in both world and GridSpace.
     */
    public static int debugBlockStateAccess(Player player, BlockPos pos) {
        player.sendSystemMessage(Component.literal("§73. Block State Access:"));
        
        Level level = player.level();
        
        // Test world block state
        BlockState worldState = level.getBlockState(pos);
        player.sendSystemMessage(Component.literal("  §7World block: §f" + worldState.getBlock().getName().getString()));
        
        // Test GridSpace block state
        Optional<TransformationAPI.GridSpaceTransformResult> transform = 
            TransformationAPI.getInstance().worldToGridSpace(
                new Vec3(pos.getX(), pos.getY(), pos.getZ()), level);
        
        if (transform.isPresent()) {
            TransformationAPI.GridSpaceTransformResult result = transform.get();
            
            try {
                // Check if grid has block at GridSpace coordinates
                BlockState gridState = result.grid.getGridSpaceBlockManager().getBlockState(result.gridSpacePos);
                player.sendSystemMessage(Component.literal("  §7GridSpace block: §f" + gridState.getBlock().getName().getString()));
                
                if (!worldState.equals(gridState)) {
                    player.sendSystemMessage(Component.literal("  §c⚠ World and GridSpace blocks don't match!"));
                }
                
            } catch (Exception e) {
                player.sendSystemMessage(Component.literal("  §cError accessing GridSpace block: " + e.getMessage()));
            }
        }
        
        return 1;
    }

    /**
     * Test distance calculations using both vanilla and grid-aware methods.
     */
    public static int debugDistanceCalculations(Player player, BlockPos pos) {
        player.sendSystemMessage(Component.literal("§74. Distance Calculations:"));
        
        Vec3 playerPos = player.position();
        Vec3 targetPos = Vec3.atCenterOf(pos);
        
        // Vanilla distance
        double vanillaDistance = playerPos.distanceToSqr(targetPos);
        
        // Grid-aware distance
        double gridAwareDistance = StardanceGameUtils.squaredDistanceBetweenInclGrids(
            player.level(), playerPos, targetPos);
        
        player.sendSystemMessage(Component.literal("  §7Vanilla distance²: §f" + String.format("%.3f", vanillaDistance)));
        player.sendSystemMessage(Component.literal("  §7Grid-aware distance²: §f" + String.format("%.3f", gridAwareDistance)));
        
        if (Math.abs(vanillaDistance - gridAwareDistance) > 0.001) {
            player.sendSystemMessage(Component.literal("  §a✓ Grid-aware distance differs (grid detected)"));
        } else {
            player.sendSystemMessage(Component.literal("  §7Distances match (no grid or stationary grid)"));
        }
        
        return 1;
    }

    /**
     * Test grid detection and physics engine integration.
     */
    public static int debugGridDetection(Player player, BlockPos pos) {
        player.sendSystemMessage(Component.literal("§75. Grid Detection:"));
        
        Level level = player.level();
        PhysicsEngine engine = engineManager.getEngine(level);
        
        if (engine == null) {
            player.sendSystemMessage(Component.literal("  §cNo physics engine found"));
            return 0;
        }
        
        // Test physics raycast to position
        Vec3 playerEye = player.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(pos);
        
        Optional<PhysicsEngine.PhysicsRaycastResult> physicsHit = 
            engine.raycastGrids(playerEye, targetCenter);
        
        if (physicsHit.isPresent()) {
            PhysicsEngine.PhysicsRaycastResult result = physicsHit.get();
            player.sendSystemMessage(Component.literal("  §a✓ Physics raycast hit grid"));
            player.sendSystemMessage(Component.literal("  §7Hit world pos: §f" + String.format("(%.3f, %.3f, %.3f)",
                result.worldHitPos.x, result.worldHitPos.y, result.worldHitPos.z)));
            player.sendSystemMessage(Component.literal("  §7Hit GridSpace pos: §f" + result.gridSpacePos));
            player.sendSystemMessage(Component.literal("  §7Hit fraction: §f" + String.format("%.6f", result.hitFraction)));
        } else {
            player.sendSystemMessage(Component.literal("  §7No physics raycast hit (position not on grid)"));
        }
        
        return 1;
    }

    /**
     * Test mixin interception by simulating block breaking.
     */
    public static int debugMixinInterception(Player player) {
        player.sendSystemMessage(Component.literal("§6=== MIXIN INTERCEPTION DEBUG ==="));
        
        // Enable mixin debugging temporarily
        boolean originalMixinDebugging = mixinDebuggingEnabled;
        mixinDebuggingEnabled = true;
        
        HitResult hitResult = player.pick(5.0, 1.0f, false);
        
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos targetPos = blockHit.getBlockPos();
            
            player.sendSystemMessage(Component.literal("§7Testing mixin interception for: §f" + targetPos));
            player.sendSystemMessage(Component.literal("§7Check console/logs for mixin debug output"));
            
            // Test distance calculation (should trigger MixinEntity)
            double distance = player.distanceToSqr(Vec3.atCenterOf(targetPos));
            player.sendSystemMessage(Component.literal("§7Distance calculation result: §f" + String.format("%.3f", distance)));
            
        } else {
            player.sendSystemMessage(Component.literal("§cNo block target found"));
        }
        
        // Restore original debugging state
        mixinDebuggingEnabled = originalMixinDebugging;
        
        return 1;
    }

    /**
     * Simulate block breaking to test the complete pipeline.
     */
    public static int debugBlockBreaking(Player player) {
        player.sendSystemMessage(Component.literal("§6=== BLOCK BREAKING SIMULATION ==="));
        
        HitResult hitResult = player.pick(5.0, 1.0f, false);
        
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos targetPos = blockHit.getBlockPos();
            Level level = player.level();
            
            player.sendSystemMessage(Component.literal("§7Simulating block break at: §f" + targetPos));
            
            // Check current block state
            BlockState currentState = level.getBlockState(targetPos);
            player.sendSystemMessage(Component.literal("§7Current block: §f" + currentState.getBlock().getName().getString()));
            
            // Test coordinate transformation
            Optional<TransformationAPI.GridSpaceTransformResult> transform = 
                TransformationAPI.getInstance().worldToGridSpace(
                    new Vec3(targetPos.getX(), targetPos.getY(), targetPos.getZ()), level);
            
            if (transform.isPresent()) {
                TransformationAPI.GridSpaceTransformResult result = transform.get();
                
                player.sendSystemMessage(Component.literal("  §a✓ Block is on grid: §f" + result.grid.getGridId()));
                player.sendSystemMessage(Component.literal("  §7Would break GridSpace block at: §f" + result.gridSpacePos));
                
                // Check if GridSpace has the block
                try {
                    BlockState gridState = result.grid.getGridSpaceBlockManager().getBlockState(result.gridSpacePos);
                    player.sendSystemMessage(Component.literal("  §7GridSpace has block: §f" + gridState.getBlock().getName().getString()));
                    
                    if (currentState.equals(gridState)) {
                        player.sendSystemMessage(Component.literal("  §a✓ World and GridSpace blocks match"));
                    } else {
                        player.sendSystemMessage(Component.literal("  §c⚠ World and GridSpace blocks don't match!"));
                    }
                    
                } catch (Exception e) {
                    player.sendSystemMessage(Component.literal("  §cError: " + e.getMessage()));
                }
                
            } else {
                player.sendSystemMessage(Component.literal("  §7Block is in normal world (not on grid)"));
            }
            
        } else {
            player.sendSystemMessage(Component.literal("§cNo block target found"));
        }
        
        return 1;
    }

    // ===============================================
    // DEBUG STATE MANAGEMENT
    // ===============================================

    public static void enableAllDebugging() {
        interactionDebuggingEnabled = true;
        raycastDebuggingEnabled = true;
        coordinateDebuggingEnabled = true;
        mixinDebuggingEnabled = true;
        SLogger.log("InteractionDebugManager", "All interaction debugging ENABLED");
    }

    public static void disableAllDebugging() {
        interactionDebuggingEnabled = false;
        raycastDebuggingEnabled = false;
        coordinateDebuggingEnabled = false;
        mixinDebuggingEnabled = false;
        SLogger.log("InteractionDebugManager", "All interaction debugging DISABLED");
    }

    // Getters for mixin debugging
    public static boolean isInteractionDebuggingEnabled() { return interactionDebuggingEnabled; }
    public static boolean isRaycastDebuggingEnabled() { return raycastDebuggingEnabled; }
    public static boolean isCoordinateDebuggingEnabled() { return coordinateDebuggingEnabled; }
    public static boolean isMixinDebuggingEnabled() { return mixinDebuggingEnabled; }
}