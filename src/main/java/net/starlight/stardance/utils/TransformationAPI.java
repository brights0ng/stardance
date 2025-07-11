package net.starlight.stardance.utils;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.PhysicsEngine;
import org.joml.Matrix4d;

import javax.vecmath.Vector3f;
import javax.vecmath.Vector3d;
import java.util.Optional;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Core coordinate transformation API for GridSpace interactions.
 * Handles the complete transformation pipeline between world coordinates,
 * grid-local coordinates, and GridSpace coordinates.
 * 
 * This is the foundation for VS2-style interaction interception.
 */
public class TransformationAPI implements ILoggingControl {
    
    // Cache transform matrices for performance
    private static final ThreadLocal<Transform> TEMP_TRANSFORM = ThreadLocal.withInitial(Transform::new);
    private static final ThreadLocal<Matrix4d> TEMP_MATRIX = ThreadLocal.withInitial(Matrix4d::new);
    private static final ThreadLocal<Vector3d> TEMP_VECTOR3D = ThreadLocal.withInitial(Vector3d::new);
    
    // Singleton instance
    private static TransformationAPI INSTANCE;
    
    public static TransformationAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TransformationAPI();
        }
        return INSTANCE;
    }
    
    private TransformationAPI() {
        SLogger.log(this, "GridSpace Transformation API initialized");
    }
    
    // ===============================================
    // MAIN INTERACTION TRANSFORMATION METHODS
    // ===============================================
    
    /**
     * CORE METHOD: Transforms world coordinates to GridSpace coordinates.
     * This is the main method for interaction interception.
     * 
     * @param worldPos World position where player interacted
     * @param world The world
     * @return GridSpaceTransformResult containing grid and GridSpace coordinates, or empty if not on a grid
     */
    public Optional<GridSpaceTransformResult> worldToGridSpace(Vec3d worldPos, World world) {
        // Find which grid (if any) contains this world position
        Optional<LocalGrid> gridOpt = findGridAtWorldPosition(worldPos, world);
        if (gridOpt.isEmpty()) {
            return Optional.empty();
        }
        
        LocalGrid grid = gridOpt.get();
        
        try {
            // Transform world → grid-local
            Vector3d worldPoint = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
            Vector3d gridLocalPoint = grid.worldToGridLocal(worldPoint);
            
            // Transform grid-local → GridSpace
            BlockPos gridLocalPos = new BlockPos(
                (int) Math.floor(gridLocalPoint.x),
                (int) Math.floor(gridLocalPoint.y), 
                (int) Math.floor(gridLocalPoint.z)
            );
            
            BlockPos gridSpacePos = grid.gridLocalToGridSpace(gridLocalPos);
            
            // Also provide the continuous GridSpace coordinates
            Vec3d gridSpaceVec = gridLocalToGridSpaceVec3d(grid, gridLocalPoint);
            
            SLogger.log(this, String.format("Transformed world %s → grid-local %s → GridSpace %s", 
                worldPos, gridLocalPoint, gridSpacePos));
            
            return Optional.of(new GridSpaceTransformResult(grid, gridSpacePos, gridSpaceVec, gridLocalPos));
            
        } catch (Exception e) {
            SLogger.log(this, "Error transforming world to GridSpace: " + e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * CORE METHOD: Transforms GridSpace coordinates back to world coordinates.
     * Used for results that need to be positioned in the world.
     * 
     * @param gridSpacePos GridSpace position
     * @param grid The grid this position belongs to
     * @return World coordinates
     */
    public Vec3d gridSpaceToWorld(BlockPos gridSpacePos, LocalGrid grid) {
        try {
            // Transform GridSpace → grid-local
            BlockPos gridLocalPos = grid.gridSpaceToGridLocal(gridSpacePos);
            
            // Transform grid-local → world using physics matrix
            Vector3d gridLocalVec = new Vector3d(
                gridLocalPos.getX() + 0.5, // Center of block
                gridLocalPos.getY() + 0.5,
                gridLocalPos.getZ() + 0.5
            );
            
            return gridLocalToWorld(grid, gridLocalVec);
            
        } catch (Exception e) {
            SLogger.log(this, "Error transforming GridSpace to world: " + e.getMessage());
            return Vec3d.ZERO;
        }
    }
    
    /**
     * Transforms continuous GridSpace coordinates to world coordinates.
     */
    public Vec3d gridSpaceToWorld(Vec3d gridSpaceVec, LocalGrid grid) {
        try {
            // Transform GridSpace → grid-local (continuous)
            Vec3d gridLocalVec = gridSpaceVecToGridLocal(grid, gridSpaceVec);
            
            // Transform grid-local → world
            Vector3d gridLocalPoint = new Vector3d(gridLocalVec.x, gridLocalVec.y, gridLocalVec.z);
            return gridLocalToWorld(grid, gridLocalPoint);
            
        } catch (Exception e) {
            SLogger.log(this, "Error transforming GridSpace vec to world: " + e.getMessage());
            return Vec3d.ZERO;
        }
    }
    
    // ===============================================
    // GRID DETECTION METHODS
    // ===============================================
    
    /**
     * Finds which grid (if any) contains the given world position.
     * Uses physics transforms and bounding boxes.
     */
    public Optional<LocalGrid> findGridAtWorldPosition(Vec3d worldPos, World world) {
        PhysicsEngine engine = engineManager.getEngine(world);
        if (engine == null) {
            return Optional.empty();
        }
        
        // Check all grids to see which one contains this world position
        for (LocalGrid grid : engine.getGrids()) {
            if (isWorldPositionInGrid(worldPos, grid)) {
                return Optional.of(grid);
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Checks if a world position is within the bounds of a grid.
     */
    public boolean isWorldPositionInGrid(Vec3d worldPos, LocalGrid grid) {
        try {
            // Transform world position to grid-local space
            Vector3d worldPoint = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
            Vector3d gridLocalPoint = grid.worldToGridLocal(worldPoint);
            
            // Check if this grid-local position has a block
            BlockPos gridLocalPos = new BlockPos(
                (int) Math.floor(gridLocalPoint.x),
                (int) Math.floor(gridLocalPoint.y),
                (int) Math.floor(gridLocalPoint.z)
            );
            
            // Check if there's actually a block at this position
            return grid.getBlock(gridLocalPos) != null;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Alternative method: check if world position is within grid's AABB.
     * More performant but less precise than block-by-block checking.
     */
    public boolean isWorldPositionInGridAABB(Vec3d worldPos, LocalGrid grid) {
        try {
            Vector3f minAabb = new Vector3f();
            Vector3f maxAabb = new Vector3f();
            grid.getAABB(minAabb, maxAabb);
            
            return worldPos.x >= minAabb.x && worldPos.x <= maxAabb.x &&
                   worldPos.y >= minAabb.y && worldPos.y <= maxAabb.y &&
                   worldPos.z >= minAabb.z && worldPos.z <= maxAabb.z;
                   
        } catch (Exception e) {
            return false;
        }
    }
    
    // ===============================================
    // LOWER-LEVEL TRANSFORMATION UTILITIES
    // ===============================================
    
    /**
     * Transforms grid-local coordinates to world coordinates using physics matrix.
     */
    public Vec3d gridLocalToWorld(LocalGrid grid, Vector3d gridLocalPoint) {
        try {
            // Get the current physics transform
            Transform physicsTransform = TEMP_TRANSFORM.get();
            grid.getCurrentTransform(physicsTransform);
            
            // Apply transform to grid-local point
            Vector3f worldPoint = new Vector3f((float) gridLocalPoint.x, (float) gridLocalPoint.y, (float) gridLocalPoint.z);
            physicsTransform.transform(worldPoint);
            
            return new Vec3d(worldPoint.x, worldPoint.y, worldPoint.z);
            
        } catch (Exception e) {
            SLogger.log(this, "Error transforming grid-local to world: " + e.getMessage());
            return Vec3d.ZERO;
        }
    }
    
    /**
     * Transforms continuous grid-local coordinates to GridSpace coordinates.
     */
    private Vec3d gridLocalToGridSpaceVec3d(LocalGrid grid, Vector3d gridLocalPoint) {
        // Apply GridSpace center offset
        double gridSpaceX = gridLocalPoint.x + LocalGrid.GRIDSPACE_CENTER_OFFSET;
        double gridSpaceY = gridLocalPoint.y + LocalGrid.GRIDSPACE_CENTER_OFFSET;
        double gridSpaceZ = gridLocalPoint.z + LocalGrid.GRIDSPACE_CENTER_OFFSET;
        
        // Add region origin
        BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();
        
        return new Vec3d(
            gridSpaceX + regionOrigin.getX(),
            gridSpaceY + regionOrigin.getY(),
            gridSpaceZ + regionOrigin.getZ()
        );
    }
    
    /**
     * Transforms continuous GridSpace coordinates to grid-local coordinates.
     */
    private Vec3d gridSpaceVecToGridLocal(LocalGrid grid, Vec3d gridSpaceVec) {
        // Subtract region origin
        BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();
        
        double regionLocalX = gridSpaceVec.x - regionOrigin.getX();
        double regionLocalY = gridSpaceVec.y - regionOrigin.getY();
        double regionLocalZ = gridSpaceVec.z - regionOrigin.getZ();
        
        // Remove GridSpace center offset
        return new Vec3d(
            regionLocalX - LocalGrid.GRIDSPACE_CENTER_OFFSET,
            regionLocalY - LocalGrid.GRIDSPACE_CENTER_OFFSET,
            regionLocalZ - LocalGrid.GRIDSPACE_CENTER_OFFSET
        );
    }
    
    // ===============================================
    // VALIDATION AND DEBUGGING
    // ===============================================
    
    /**
     * Validates that a GridSpace position is within valid bounds for the grid.
     */
    public boolean isValidGridSpacePosition(BlockPos gridSpacePos, LocalGrid grid) {
        if (grid.isDestroyed()) {
            return false;
        }
        
        return grid.getGridSpaceRegion().containsGridSpacePosition(gridSpacePos);
    }
    
    /**
     * Debug method: logs the complete transformation chain for a world position.
     */
    public void debugTransformationChain(Vec3d worldPos, World world) {
        SLogger.log(this, "=== TRANSFORMATION DEBUG ===");
        SLogger.log(this, "World position: " + worldPos);
        
        Optional<GridSpaceTransformResult> result = worldToGridSpace(worldPos, world);
        if (result.isPresent()) {
            GridSpaceTransformResult r = result.get();
            SLogger.log(this, "Grid: " + r.grid.getGridId());
            SLogger.log(this, "Grid-local: " + r.gridLocalPos);
            SLogger.log(this, "GridSpace: " + r.gridSpacePos);
            SLogger.log(this, "GridSpace vec: " + r.gridSpaceVec);
            
            // Test round-trip transformation
            Vec3d backToWorld = gridSpaceToWorld(r.gridSpacePos, r.grid);
            SLogger.log(this, "Round-trip world: " + backToWorld);
            SLogger.log(this, "Round-trip error: " + worldPos.distanceTo(backToWorld));
        } else {
            SLogger.log(this, "No grid found at world position");
        }
        SLogger.log(this, "=== END DEBUG ===");
    }
    
    // ===============================================
    // LOGGING CONTROL
    // ===============================================
    
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }
    
    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }
    
    // ===============================================
    // RESULT CLASS
    // ===============================================
    
    /**
     * Result of a world → GridSpace transformation.
     * Contains all the information needed for interaction interception.
     */
    public static class GridSpaceTransformResult {
        public final LocalGrid grid;
        public final BlockPos gridSpacePos;      // Discrete GridSpace coordinates
        public final Vec3d gridSpaceVec;         // Continuous GridSpace coordinates
        public final BlockPos gridLocalPos;     // Grid-local coordinates (for reference)
        
        public GridSpaceTransformResult(LocalGrid grid, BlockPos gridSpacePos, Vec3d gridSpaceVec, BlockPos gridLocalPos) {
            this.grid = grid;
            this.gridSpacePos = gridSpacePos;
            this.gridSpaceVec = gridSpaceVec;
            this.gridLocalPos = gridLocalPos;
        }
        
        @Override
        public String toString() {
            return String.format("GridSpaceTransformResult{grid=%s, gridSpace=%s, gridLocal=%s}", 
                grid.getGridId(), gridSpacePos, gridLocalPos);
        }
    }
}