package net.starlight.stardance.gridspace.utils;

import com.bulletphysics.dynamics.DynamicsWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.physics.EngineManager;
import net.starlight.stardance.utils.SLogger;

import java.util.Optional;

/**
 * Utility class for performing raycasts that include both world blocks and GridSpace blocks.
 */
public class GridSpaceRaycastUtils {



    /**
     * Performs raycasting that includes both world blocks and GridSpace blocks.
     */
    public static BlockHitResult clipIncludeGrids(Level level, ClipContext context) {
        try {
            // 1. Perform vanilla world raycast
            BlockHitResult worldHit = level.clip(context);

            // 2. Perform GridSpace raycast using JBullet physics
            BlockHitResult gridHit = performGridSpaceRaycast(level, context);

            // 3. Debug logging
            SLogger.log("GridSpaceRaycastUtils", "World hit: " + worldHit.getType() +
                    ", Grid hit: " + gridHit.getType());

            // 4. Return whichever hit is closer to ray origin
            BlockHitResult result = selectCloserHit(worldHit, gridHit, context.getFrom());

            SLogger.log("GridSpaceRaycastUtils", "Selected result: " + result.getType());
            return result;

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Error in clipIncludeGrids: " + e.getMessage());
            e.printStackTrace();
            // Graceful degradation - return vanilla result if grid raycast fails
            return level.clip(context);
        }
    }

    /**
     * Performs ONLY the grid raycast portion without calling level.clip().
     * This is used by MixinLevel to avoid recursion.
     *
     * @param level The world to raycast in
     * @param context Raycast parameters
     * @return Grid raycast result only (no vanilla raycast)
     */
    public static BlockHitResult performGridSpaceRaycastOnly(Level level, ClipContext context) {
        try {
//            SLogger.log("GridSpaceRaycastUtils", "Grid-only raycast starting...");

            // This is the same as performGridSpaceRaycast() but made public
            return performGridSpaceRaycast(level, context);

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Grid-only raycast failed: " + e.getMessage());
            return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
        }
    }

    private static BlockHitResult performGridSpaceRaycast(Level level, ClipContext context) {
        try {

            PhysicsEngine physicsEngine = null;
            // Only perform grid raycasting on server-side
            if (level.isClientSide) {
                // Client-side: try to access integrated server
                SLogger.log("GridSpaceRaycastUtils", "Client-side raycast - attempting integrated server access");
                physicsEngine = getIntegratedServerPhysicsEngine();

                if (physicsEngine == null) {
                    SLogger.log("GridSpaceRaycastUtils", "No integrated server physics available");
                    return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
                }

                SLogger.log("GridSpaceRaycastUtils", "Successfully accessed integrated server physics!");

            } else {
                // Server-side: use server level directly
                ServerLevel serverLevel = (ServerLevel) level;
                EngineManager engineManager = net.starlight.stardance.Stardance.engineManager;
                physicsEngine = engineManager.getEngine(serverLevel);

                SLogger.log("GridSpaceRaycastUtils", "Server-side raycast");
            }

            if (physicsEngine == null) {
                SLogger.log("GridSpaceRaycastUtils", "No physics engine for server level");
                return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
            }

            // Get dynamics world directly
            DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();
            if (dynamicsWorld == null) {
                SLogger.log("GridSpaceRaycastUtils", "No dynamics world available");
                return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
            }

            SLogger.log("GridSpaceRaycastUtils", "Performing server-side grid raycast...");

            // Use the physics engine's raycast method
            Optional<PhysicsEngine.GridRaycastResult> physicsHit =
                    physicsEngine.raycastGrids(context.getFrom(), context.getTo());

            if (physicsHit.isEmpty()) {
                SLogger.log("GridSpaceRaycastUtils", "No grid hits detected");
                return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
            }

            SLogger.log("GridSpaceRaycastUtils", "Grid hit detected! Converting to BlockHitResult...");
            return convertPhysicsHitToBlockHit(physicsHit.get(), context);

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Grid raycast error: " + e.getMessage());
            e.printStackTrace();
            return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
        }
    }

    /**
     * Converts a physics raycast result to a Minecraft BlockHitResult.
     * Uses hit normal for precise block detection.
     */
    private static BlockHitResult convertPhysicsHitToBlockHit(
            PhysicsEngine.GridRaycastResult physicsHit, ClipContext context) {

        try {
            LocalGrid hitGrid = physicsHit.hitGrid;
            Vec3 worldHitPoint = physicsHit.hitPoint;
            Vec3 worldHitNormal = physicsHit.hitNormal;

            SLogger.log("GridSpaceRaycastUtils", "Converting physics hit:");
            SLogger.log("GridSpaceRaycastUtils", "  World hit point: " + formatVec3(worldHitPoint));
            SLogger.log("GridSpaceRaycastUtils", "  World hit normal: " + formatVec3(worldHitNormal));

            // Convert world hit point to grid-local coordinates
            Vec3 gridHitPoint = hitGrid.worldToGridSpace(worldHitPoint);
            SLogger.log("GridSpaceRaycastUtils", "  Grid hit point: " + formatVec3(gridHitPoint));

            // NORMAL-BASED APPROACH: Transform the hit normal to grid space
            // We need to transform the normal vector properly to grid coordinate system
            Vec3 worldNormalEnd = worldHitPoint.add(worldHitNormal);
            Vec3 gridNormalEnd = hitGrid.worldToGridSpace(worldNormalEnd);
            Vec3 gridHitNormal = gridNormalEnd.subtract(gridHitPoint).normalize();

            SLogger.log("GridSpaceRaycastUtils", "  Grid hit normal: " + formatVec3(gridHitNormal));

            // Step INTO the block (opposite direction of the normal)
            // The normal points outward from the surface, so we go inward
            double stepDistance = 0.1;
            Vec3 insidePoint = gridHitPoint.subtract(gridHitNormal.scale(stepDistance));

            SLogger.log("GridSpaceRaycastUtils", "  Point inside block: " + formatVec3(insidePoint));

            // Get the grid-local block position by flooring the inside point
            BlockPos gridLocalBlockPos = new BlockPos(
                    (int) Math.floor(insidePoint.x),
                    (int) Math.floor(insidePoint.y),
                    (int) Math.floor(insidePoint.z)
            );

            SLogger.log("GridSpaceRaycastUtils", "  Calculated block pos: " + gridLocalBlockPos);

            // Verify the block exists
            BlockState hitBlockState = hitGrid.getBlockState(gridLocalBlockPos);

            if (hitBlockState == null || hitBlockState.isAir()) {
                SLogger.log("GridSpaceRaycastUtils", "  No solid block at " + gridLocalBlockPos + " using normal method");

                // FALLBACK 1: Try the surface point directly (sometimes the hit is exactly on a boundary)
                BlockPos surfacePos = new BlockPos(
                        (int) Math.floor(gridHitPoint.x),
                        (int) Math.floor(gridHitPoint.y),
                        (int) Math.floor(gridHitPoint.z)
                );

                SLogger.log("GridSpaceRaycastUtils", "  Trying surface pos: " + surfacePos);
                BlockState surfaceState = hitGrid.getBlockState(surfacePos);

                if (surfaceState != null && !surfaceState.isAir()) {
                    gridLocalBlockPos = surfacePos;
                    hitBlockState = surfaceState;
                    SLogger.log("GridSpaceRaycastUtils", "  Surface method worked!");
                } else {
                    // FALLBACK 2: Check the block in the opposite direction (in case normal is flipped)
                    Vec3 oppositeInsidePoint = gridHitPoint.add(gridHitNormal.scale(stepDistance));
                    BlockPos oppositePos = new BlockPos(
                            (int) Math.floor(oppositeInsidePoint.x),
                            (int) Math.floor(oppositeInsidePoint.y),
                            (int) Math.floor(oppositeInsidePoint.z)
                    );

                    SLogger.log("GridSpaceRaycastUtils", "  Trying opposite direction: " + oppositePos);
                    BlockState oppositeState = hitGrid.getBlockState(oppositePos);

                    if (oppositeState != null && !oppositeState.isAir()) {
                        gridLocalBlockPos = oppositePos;
                        hitBlockState = oppositeState;
                        SLogger.log("GridSpaceRaycastUtils", "  Opposite direction worked!");
                    } else {
                        SLogger.log("GridSpaceRaycastUtils", "  All methods failed - no solid block found");
                        return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
                    }
                }
            }

            // Convert to GridSpace coordinates
            BlockPos gridSpaceBlockPos = hitGrid.gridLocalToGridSpace(gridLocalBlockPos);

            // Determine hit face from the world normal (since this is what the player sees)
            Direction hitFace = getDirectionFromNormal(worldHitNormal);

            Vec3 gridLocalBlockCenter = Vec3.atCenterOf(gridLocalBlockPos);
            Vec3 worldBlockCenter = hitGrid.gridLocalToWorld(gridLocalBlockCenter);  // Key method!
            BlockPos worldBlockPos = BlockPos.containing(worldBlockCenter);

            SLogger.log("GridSpaceRaycastUtils", "  SUCCESS!");
            SLogger.log("GridSpaceRaycastUtils", "    Grid-local pos: " + gridLocalBlockPos);
            SLogger.log("GridSpaceRaycastUtils", "    World block pos: " + worldBlockPos);    // ✅ World coords
            SLogger.log("GridSpaceRaycastUtils", "    Hit face: " + hitFace);

            return new BlockHitResult(
                    worldHitPoint,
                    hitFace,
                    worldBlockPos,           // ✅ Now returning world coords!
                    false
            );

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Failed to convert physics hit: " + e.getMessage());
            e.printStackTrace();
            return BlockHitResult.miss(context.getTo(), null, BlockPos.containing(context.getTo()));
        }
    }

    // Helper method for formatting Vec3
    private static String formatVec3(Vec3 vec) {
        return String.format("(%.3f, %.3f, %.3f)", vec.x, vec.y, vec.z);
    }

    /**
     * Attempts to get the physics engine from the integrated server (single-player).
     */
    private static PhysicsEngine getIntegratedServerPhysicsEngine() {
        try {
            Minecraft minecraft = Minecraft.getInstance();

            // Check if we're in single-player
            if (minecraft.getSingleplayerServer() == null) {
//                SLogger.log("GridSpaceRaycastUtils", "Not in single-player - no integrated server");
                return null;
            }

            // Get the integrated server
            MinecraftServer integratedServer = minecraft.getSingleplayerServer();

            // Get the server level that corresponds to the client level
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel == null) {
                return null;
            }

            // Find matching server level
            ResourceKey<Level> dimension = clientLevel.dimension();
            ServerLevel serverLevel = integratedServer.getLevel(dimension);

            if (serverLevel == null) {
//                SLogger.log("GridSpaceRaycastUtils", "Could not find matching server level for dimension: " + dimension);
                return null;
            }

            // Get physics engine from server level
            EngineManager engineManager = net.starlight.stardance.Stardance.engineManager;
            PhysicsEngine physicsEngine = engineManager.getEngine(serverLevel);

//            SLogger.log("GridSpaceRaycastUtils", "Successfully retrieved integrated server physics engine");
            return physicsEngine;

        } catch (Exception e) {
            SLogger.log("GridSpaceRaycastUtils", "Failed to get integrated server physics: " + e.getMessage());
            return null;
        }
    }

    /**
     * Determines the closest hit between world and grid raycasts.
     */
    private static BlockHitResult selectCloserHit(BlockHitResult worldHit, BlockHitResult gridHit, Vec3 rayOrigin) {
        // If only one hit, return it
        if (worldHit.getType() == HitResult.Type.MISS) return gridHit;
        if (gridHit.getType() == HitResult.Type.MISS) return worldHit;

        // Both hit - return closer one
        double worldDistance = worldHit.getLocation().distanceToSqr(rayOrigin);
        double gridDistance = gridHit.getLocation().distanceToSqr(rayOrigin);

        if (gridDistance < worldDistance) {
//            SLogger.log("GridSpaceRaycastUtils", "Grid hit closer: " + Math.sqrt(gridDistance) + " vs " + Math.sqrt(worldDistance));
            return gridHit;
        } else {
//            SLogger.log("GridSpaceRaycastUtils", "World hit closer: " + Math.sqrt(worldDistance) + " vs " + Math.sqrt(gridDistance));
            return worldHit;
        }
    }

    /**
     * Converts a normal vector to a Minecraft Direction.
     */
    private static Direction getDirectionFromNormal(Vec3 normal) {
        // Find the axis with the largest absolute component
        double absX = Math.abs(normal.x);
        double absY = Math.abs(normal.y);
        double absZ = Math.abs(normal.z);

        if (absY > absX && absY > absZ) {
            return normal.y > 0 ? Direction.UP : Direction.DOWN;
        } else if (absX > absZ) {
            return normal.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return normal.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}