package net.starlight.stardance.debug;

import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.dynamics.RigidBody;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.render.DebugRenderer;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.TransformationAPI;

import javax.vecmath.Vector3f;
import java.util.Optional;
import java.util.Set;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * COMPREHENSIVE RAYCAST DEBUGGER: Visual debugging for all aspects of raycast operations.
 * Shows rays, grids, blocks, physics collision shapes, and coordinate transformations.
 */
public class ComprehensiveRaycastDebugger implements ILoggingControl {

    // Debug colors (ARGB format)
    private static final int COLOR_RAY_PRIMARY = 0xFF00FF00;        // Green - Primary ray
    private static final int COLOR_RAY_PHYSICS = 0xFF0088FF;        // Blue - Physics raycast
    private static final int COLOR_WORLD_HIT = 0xFFFFFF00;          // Yellow - World hit
    private static final int COLOR_GRID_HIT = 0xFFFF8800;           // Orange - Grid hit
    private static final int COLOR_GRID_BOUNDS = 0xFF8800FF;        // Purple - Grid AABB
    private static final int COLOR_GRID_BLOCKS = 0xFF88FFFF;        // Cyan - Individual blocks
    private static final int COLOR_PHYSICS_SHAPES = 0xFFFF0088;     // Pink - Physics collision shapes
    private static final int COLOR_COORDINATE_LINES = 0xFF888888;   // Gray - Coordinate transformation lines
    private static final int COLOR_ERROR = 0xFFFF0000;              // Red - Errors or problems

    // Debug settings
    private static final double RAY_LENGTH = 64.0;
    private static final int VISUAL_DURATION = 300; // 15 seconds at 20 TPS
    private static final float LINE_WIDTH = 0.04f;
    private static final float MARKER_SIZE = 0.25f;

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }

    /**
     * COMPREHENSIVE DEBUG: Visualizes everything about raycast operations.
     */
    public static void debugComprehensiveRaycast(Player player) {
//        if (player == null || player.getWorld().isClient) {
//            return;
//        }

        try {
            SLogger.log("ComprehensiveRaycastDebugger", "=== COMPREHENSIVE RAYCAST DEBUG START ===");
            
            // Clear previous debug visuals
            DebugRenderer.clear();
            
            // Get player raycast info
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookDir = player.getLookAngle().normalize();
            Vec3 rayEnd = eyePos.add(lookDir.scale(RAY_LENGTH));
            
            // 1. VISUALIZE THE PRIMARY RAY
            visualizePrimaryRay(eyePos, lookDir, player);
            
            // 2. VISUALIZE ALL GRIDS IN THE WORLD
            visualizeAllGrids(player);
            
            // 3. PERFORM AND VISUALIZE VANILLA RAYCAST
            visualizeVanillaRaycast(player, eyePos, rayEnd);
            
            // 4. PERFORM AND VISUALIZE PHYSICS RAYCAST
            visualizePhysicsRaycast(player, eyePos, rayEnd);
            
            // 5. VISUALIZE COORDINATE TRANSFORMATIONS
            visualizeCoordinateTransformations(player, eyePos, rayEnd);
            
            // 6. CHECK INDIVIDUAL GRID BLOCKS ALONG RAY PATH
            visualizeGridBlocksAlongRay(player, eyePos, lookDir);
            
            SLogger.log("ComprehensiveRaycastDebugger", "=== COMPREHENSIVE RAYCAST DEBUG COMPLETE ===");
            
        } catch (Exception e) {
            SLogger.log("ComprehensiveRaycastDebugger", "Error in comprehensive debug: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 1. Visualize the primary ray being cast.
     */
    private static void visualizePrimaryRay(Vec3 eyePos, Vec3 lookDir, Player player) {
        SLogger.log("ComprehensiveRaycastDebugger", "1. VISUALIZING PRIMARY RAY");
        
        // Draw the main ray
        DebugRenderer.addRay(eyePos, lookDir, RAY_LENGTH, COLOR_RAY_PRIMARY, LINE_WIDTH, VISUAL_DURATION);
        
        // Mark the eye position
        DebugRenderer.addPoint(eyePos, COLOR_RAY_PRIMARY, MARKER_SIZE, VISUAL_DURATION);
        
        // Mark points along the ray every 5 blocks
        for (int i = 5; i <= RAY_LENGTH; i += 5) {
            Vec3 rayPoint = eyePos.add(lookDir.scale(i));
            DebugRenderer.addPoint(rayPoint, COLOR_RAY_PRIMARY, MARKER_SIZE * 0.6f, VISUAL_DURATION);
        }
        
        SLogger.log("ComprehensiveRaycastDebugger", "Ray: " + eyePos + " → " + eyePos.add(lookDir.scale(RAY_LENGTH)));
    }

    /**
     * 2. Visualize all grids in the world.
     */
    private static void visualizeAllGrids(Player player) {
        SLogger.log("ComprehensiveRaycastDebugger", "2. VISUALIZING ALL GRIDS");
        
        PhysicsEngine engine = engineManager.getEngine(player.level());
        if (engine == null) {
            SLogger.log("ComprehensiveRaycastDebugger", "❌ No physics engine found!");
            return;
        }
        
        Set<LocalGrid> grids = engine.getGrids();
        SLogger.log("ComprehensiveRaycastDebugger", "Found " + grids.size() + " grids");
        
        for (LocalGrid grid : grids) {
            try {
                // Visualize grid AABB
                Vector3f minAabb = new Vector3f();
                Vector3f maxAabb = new Vector3f();
                grid.getAABB(minAabb, maxAabb);
                
                net.minecraft.world.phys.AABB gridBox = new net.minecraft.world.phys.AABB(
                    minAabb.x, minAabb.y, minAabb.z,
                    maxAabb.x, maxAabb.y, maxAabb.z
                );
                
                DebugRenderer.addBox(gridBox, COLOR_GRID_BOUNDS, LINE_WIDTH, VISUAL_DURATION);
                
                // Visualize individual blocks in the grid
                int blockCount = 0;
                for (var entry : grid.getBlocks().entrySet()) {
                    BlockPos gridLocalPos = entry.getKey();
                    
                    // Convert grid-local to world position for visualization
                    try {
                        Vec3 worldBlockPos = grid.gridLocalToWorld(new Vec3(
                            gridLocalPos.getX() + 0.5,
                            gridLocalPos.getY() + 0.5, 
                            gridLocalPos.getZ() + 0.5
                        ));
                        
                        DebugRenderer.addPoint(worldBlockPos, COLOR_GRID_BLOCKS, MARKER_SIZE * 0.8f, VISUAL_DURATION);
                        blockCount++;
                        
                        // Limit visual clutter - only show first 20 blocks
                        if (blockCount >= 20) break;
                        
                    } catch (Exception e) {
                        SLogger.log("ComprehensiveRaycastDebugger", "Error visualizing block " + gridLocalPos + ": " + e.getMessage());
                    }
                }
                
                SLogger.log("ComprehensiveRaycastDebugger", "Grid " + grid.getGridId().toString().substring(0, 8) + 
                    ": " + grid.getBlocks().size() + " blocks, AABB=" + minAabb + " to " + maxAabb);
                
                // Visualize physics collision shape if available
                RigidBody rigidBody = grid.getRigidBody();
                if (rigidBody != null) {
                    Vector3f center = new Vector3f();
                    rigidBody.getCenterOfMassPosition(center);
                    Vec3 centerVec = new Vec3(center.x, center.y, center.z);
                    DebugRenderer.addCrosshair(centerVec, COLOR_PHYSICS_SHAPES, MARKER_SIZE * 1.5f, LINE_WIDTH, VISUAL_DURATION);
                    
                    SLogger.log("ComprehensiveRaycastDebugger", "Grid physics center: " + centerVec);
                } else {
                    SLogger.log("ComprehensiveRaycastDebugger", "❌ Grid has no rigid body!");
                }
                
            } catch (Exception e) {
                SLogger.log("ComprehensiveRaycastDebugger", "Error visualizing grid " + grid.getGridId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 3. Visualize vanilla Minecraft raycast.
     */
    private static void visualizeVanillaRaycast(Player player, Vec3 rayStart, Vec3 rayEnd) {
        SLogger.log("ComprehensiveRaycastDebugger", "3. VISUALIZING VANILLA RAYCAST");
        
        try {
            // Create raycast context
            ClipContext context = new ClipContext(
                rayStart, rayEnd, 
                ClipContext.Block.OUTLINE, 
                ClipContext.Fluid.NONE, 
                player
            );
            
            // Perform vanilla raycast
            BlockHitResult worldResult = player.level().clip(context);
            
            if (worldResult.getType() == HitResult.Type.MISS) {
                SLogger.log("ComprehensiveRaycastDebugger", "Vanilla raycast: MISS");
            } else {
                Vec3 hitPos = worldResult.getLocation();
                BlockPos blockPos = worldResult.getBlockPos();
                
                DebugRenderer.addPoint(hitPos, COLOR_WORLD_HIT, MARKER_SIZE * 1.5f, VISUAL_DURATION);
                DebugRenderer.addCrosshair(hitPos, COLOR_WORLD_HIT, MARKER_SIZE, LINE_WIDTH, VISUAL_DURATION);
                
                SLogger.log("ComprehensiveRaycastDebugger", "Vanilla raycast HIT: " + hitPos + " block=" + blockPos);
            }
            
        } catch (Exception e) {
            SLogger.log("ComprehensiveRaycastDebugger", "Error in vanilla raycast: " + e.getMessage());
        }
    }

    /**
     * 4. Visualize physics engine raycast.
     */
    private static void visualizePhysicsRaycast(Player player, Vec3 rayStart, Vec3 rayEnd) {
        SLogger.log("ComprehensiveRaycastDebugger", "4. VISUALIZING PHYSICS RAYCAST");
        
        PhysicsEngine engine = engineManager.getEngine(player.level());
        if (engine == null) {
            SLogger.log("ComprehensiveRaycastDebugger", "❌ No physics engine for physics raycast");
            return;
        }
        
        try {
            // Check if the physics raycast method exists
            Optional<PhysicsEngine.PhysicsRaycastResult> result = engine.raycastGrids(rayStart, rayEnd);
            
            if (result.isPresent()) {
                PhysicsEngine.PhysicsRaycastResult hit = result.get();
                
                DebugRenderer.addPoint(hit.worldHitPos, COLOR_GRID_HIT, MARKER_SIZE * 2.0f, VISUAL_DURATION);
                DebugRenderer.addCrosshair(hit.worldHitPos, COLOR_GRID_HIT, MARKER_SIZE * 1.2f, LINE_WIDTH, VISUAL_DURATION);
                
                // Draw line from ray start to physics hit
                DebugRenderer.addRay(rayStart, hit.worldHitPos.subtract(rayStart).normalize(), 
                    rayStart.distanceTo(hit.worldHitPos), COLOR_RAY_PHYSICS, LINE_WIDTH, VISUAL_DURATION);
                
                SLogger.log("ComprehensiveRaycastDebugger", "Physics raycast HIT: " + hit.worldHitPos + 
                    " GridSpace=" + hit.gridSpacePos + " fraction=" + hit.hitFraction);
                    
            } else {
                SLogger.log("ComprehensiveRaycastDebugger", "Physics raycast: MISS");
            }
            
        } catch (Exception e) {
            SLogger.log("ComprehensiveRaycastDebugger", "❌ Error in physics raycast (method might not exist): " + e.getMessage());
            
            // Fallback: Try manual JBullet raycast
            tryManualPhysicsRaycast(engine, rayStart, rayEnd);
        }
    }

    /**
     * Fallback manual physics raycast if the method doesn't exist.
     */
    private static void tryManualPhysicsRaycast(PhysicsEngine engine, Vec3 rayStart, Vec3 rayEnd) {
        try {
            SLogger.log("ComprehensiveRaycastDebugger", "Attempting manual JBullet raycast");
            
            // Convert to JBullet vectors
            Vector3f bulletStart = new Vector3f((float) rayStart.x, (float) rayStart.y, (float) rayStart.z);
            Vector3f bulletEnd = new Vector3f((float) rayEnd.x, (float) rayEnd.y, (float) rayEnd.z);
            
            // Create raycast callback
            com.bulletphysics.collision.dispatch.CollisionWorld.ClosestRayResultCallback callback = 
                new com.bulletphysics.collision.dispatch.CollisionWorld.ClosestRayResultCallback(bulletStart, bulletEnd);
            
            // Perform raycast
            synchronized (engine.getPhysicsLock()) {
                engine.getDynamicsWorld().rayTest(bulletStart, bulletEnd, callback);
            }
            
            if (callback.hasHit()) {
                Vector3f hitPoint = new Vector3f();
                callback.hitPointWorld.get(hitPoint);
                Vec3 hitPos = new Vec3(hitPoint.x, hitPoint.y, hitPoint.z);
                
                DebugRenderer.addPoint(hitPos, COLOR_GRID_HIT, MARKER_SIZE * 2.0f, VISUAL_DURATION);
                DebugRenderer.addCrosshair(hitPos, COLOR_GRID_HIT, MARKER_SIZE * 1.2f, LINE_WIDTH, VISUAL_DURATION);
                
                // Check what we hit
                CollisionObject hitObject = callback.collisionObject;
                Object userPointer = hitObject.getUserPointer();
                
                if (userPointer instanceof LocalGrid) {
                    LocalGrid grid = (LocalGrid) userPointer;
                    SLogger.log("ComprehensiveRaycastDebugger", "Manual physics raycast HIT: " + hitPos + 
                        " grid=" + grid.getGridId().toString().substring(0, 8));
                } else {
                    SLogger.log("ComprehensiveRaycastDebugger", "Manual physics raycast hit non-grid object: " + 
                        (userPointer != null ? userPointer.getClass().getSimpleName() : "null"));
                }
            } else {
                SLogger.log("ComprehensiveRaycastDebugger", "Manual physics raycast: MISS");
            }
            
        } catch (Exception e) {
            SLogger.log("ComprehensiveRaycastDebugger", "❌ Manual physics raycast failed: " + e.getMessage());
        }
    }

    /**
     * 5. Visualize coordinate transformations.
     */
    private static void visualizeCoordinateTransformations(Player player, Vec3 rayStart, Vec3 rayEnd) {
        SLogger.log("ComprehensiveRaycastDebugger", "5. VISUALIZING COORDINATE TRANSFORMATIONS");
        
        // Test coordinate transformations at several points along the ray
        for (int i = 10; i <= RAY_LENGTH; i += 10) {
            Vec3 testPoint = rayStart.add(rayEnd.subtract(rayStart).normalize().scale(i));
            
            try {
                Optional<TransformationAPI.GridSpaceTransformResult> result = 
                    TransformationAPI.getInstance().worldToGridSpace(testPoint, player.level());
                    
                if (result.isPresent()) {
                    TransformationAPI.GridSpaceTransformResult transform = result.get();
                    
                    // Show the transformation with a line
                    Vec3 gridSpaceWorldPos = TransformationAPI.getInstance().gridSpaceToWorld(transform.gridSpaceVec, transform.grid);
                    
                    DebugRenderer.addLine(testPoint, gridSpaceWorldPos, COLOR_COORDINATE_LINES, LINE_WIDTH * 0.5f, VISUAL_DURATION);
                    DebugRenderer.addPoint(testPoint, COLOR_COORDINATE_LINES, MARKER_SIZE * 0.5f, VISUAL_DURATION);
                    
                    double error = testPoint.distanceTo(gridSpaceWorldPos);
                    
                    SLogger.log("ComprehensiveRaycastDebugger", String.format(
                        "Transform test at distance %d: world=%s, gridSpace=%s, error=%.6f",
                        i, testPoint, transform.gridSpacePos, error
                    ));
                    
                    if (error > 0.01) {
                        DebugRenderer.addLine(testPoint, gridSpaceWorldPos, COLOR_ERROR, LINE_WIDTH * 2, VISUAL_DURATION);
                        SLogger.log("ComprehensiveRaycastDebugger", "⚠ HIGH COORDINATE TRANSFORMATION ERROR!");
                    }
                }
                
            } catch (Exception e) {
                SLogger.log("ComprehensiveRaycastDebugger", "Error in coordinate transformation test: " + e.getMessage());
            }
        }
    }

    /**
     * 6. Check individual grid blocks along the ray path.
     */
    private static void visualizeGridBlocksAlongRay(Player player, Vec3 rayStart, Vec3 lookDir) {
        SLogger.log("ComprehensiveRaycastDebugger", "6. CHECKING GRID BLOCKS ALONG RAY PATH");
        
        PhysicsEngine engine = engineManager.getEngine(player.level());
        if (engine == null) return;
        
        // Check every 0.5 blocks along the ray
        for (double distance = 0.5; distance <= RAY_LENGTH; distance += 0.5) {
            Vec3 checkPoint = rayStart.add(lookDir.scale(distance));
            
            // Check if this point intersects any grid blocks
            for (LocalGrid grid : engine.getGrids()) {
                try {
                    // Convert world point to grid-local
                    javax.vecmath.Vector3d gridLocalPoint = grid.worldToGridLocal(
                        new javax.vecmath.Vector3d(checkPoint.x, checkPoint.y, checkPoint.z)
                    );
                    
                    BlockPos gridLocalPos = new BlockPos(
                        (int) Math.floor(gridLocalPoint.x),
                        (int) Math.floor(gridLocalPoint.y),
                        (int) Math.floor(gridLocalPoint.z)
                    );
                    
                    // Check if a block exists at this position
                    if (grid.getBlock(gridLocalPos) != null) {
                        // We found a block along the ray path!
                        DebugRenderer.addPoint(checkPoint, COLOR_GRID_HIT, MARKER_SIZE * 1.2f, VISUAL_DURATION);
                        
                        BlockPos gridSpacePos = grid.gridLocalToGridSpace(gridLocalPos);
                        
                        SLogger.log("ComprehensiveRaycastDebugger", String.format(
                            "✓ BLOCK FOUND along ray at distance %.1f: world=%s, gridLocal=%s, gridSpace=%s",
                            distance, checkPoint, gridLocalPos, gridSpacePos
                        ));
                        
                        // We found the first block, that's enough for debugging
                        return;
                    }
                    
                } catch (Exception e) {
                    // Ignore errors for individual points
                }
            }
        }
        
        SLogger.log("ComprehensiveRaycastDebugger", "No grid blocks found along ray path");
    }
}