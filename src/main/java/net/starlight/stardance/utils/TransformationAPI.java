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
 * FIXED: Core coordinate transformation API for GridSpace interactions.
 * Now maintains floating-point precision throughout the transformation pipeline.
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
    // MAIN INTERACTION TRANSFORMATION METHODS (FIXED)
    // ===============================================

    /**
     * FIXED: Transforms world coordinates to GridSpace coordinates with full precision.
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
            // Transform world → grid-local (KEEP CONTINUOUS COORDINATES)
            Vector3d worldPoint = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
            Vector3d gridLocalPoint = grid.worldToGridLocal(worldPoint);

            // FIXED: Keep continuous coordinates for round-trip accuracy
            Vec3d gridLocalVec = new Vec3d(gridLocalPoint.x, gridLocalPoint.y, gridLocalPoint.z);

            // Convert to discrete BlockPos for block-based operations (but keep continuous for round-trip)
            BlockPos gridLocalPos = new BlockPos(
                    (int) Math.floor(gridLocalPoint.x),
                    (int) Math.floor(gridLocalPoint.y),
                    (int) Math.floor(gridLocalPoint.z)
            );

            // Transform CONTINUOUS grid-local → GridSpace for accuracy
            Vec3d gridSpaceVec = gridLocalToGridSpaceVec3d(grid, gridLocalVec);

            // Also provide discrete GridSpace coordinates for block operations
            BlockPos gridSpacePos = grid.gridLocalToGridSpace(gridLocalPos);

            SLogger.log(this, String.format("Transformed world %s → grid-local %s → GridSpace %s",
                    worldPos, gridLocalPoint, gridSpacePos));

            return Optional.of(new GridSpaceTransformResult(grid, gridSpacePos, gridSpaceVec, gridLocalVec, gridLocalPos));

        } catch (Exception e) {
            SLogger.log(this, "Error transforming world to GridSpace: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * FIXED: Transforms GridSpace coordinates back to world coordinates with full precision.
     *
     * @param gridSpaceVec Continuous GridSpace position (Vec3d)
     * @param grid The grid this position belongs to
     * @return World coordinates
     */
    public Vec3d gridSpaceToWorld(Vec3d gridSpaceVec, LocalGrid grid) {
        try {
            // Transform GridSpace → grid-local (CONTINUOUS)
            Vec3d gridLocalVec = gridSpaceVecToGridLocal(grid, gridSpaceVec);

            // Transform grid-local → world using physics matrix
            Vector3d gridLocalPoint = new Vector3d(gridLocalVec.x, gridLocalVec.y, gridLocalVec.z);
            return gridLocalToWorld(grid, gridLocalPoint);

        } catch (Exception e) {
            SLogger.log(this, "Error transforming GridSpace to world: " + e.getMessage());
            return Vec3d.ZERO;
        }
    }

    /**
     * LEGACY: Transforms discrete GridSpace coordinates to world coordinates.
     *
     * @param gridSpacePos GridSpace position (BlockPos)
     * @param grid The grid this position belongs to
     * @return World coordinates
     */
    public Vec3d gridSpaceToWorld(BlockPos gridSpacePos, LocalGrid grid) {
        try {
            // Convert BlockPos to Vec3d (center of block)
            Vec3d gridSpaceVec = new Vec3d(
                    gridSpacePos.getX() + 0.5,
                    gridSpacePos.getY() + 0.5,
                    gridSpacePos.getZ() + 0.5
            );

            return gridSpaceToWorld(gridSpaceVec, grid);

        } catch (Exception e) {
            SLogger.log(this, "Error transforming GridSpace BlockPos to world: " + e.getMessage());
            return Vec3d.ZERO;
        }
    }

    // ===============================================
    // GRID DETECTION METHODS (UNCHANGED)
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
    // LOWER-LEVEL TRANSFORMATION UTILITIES (ENHANCED)
    // ===============================================

    /**
     * Transforms grid-local coordinates to world coordinates using physics matrix.
     */
    public Vec3d gridLocalToWorld(LocalGrid grid, Vector3d gridLocalPoint) {
        try {
            // CRITICAL FIX: Subtract centroid offset first
            Vector3f adjustedPoint = new Vector3f(
                    (float) gridLocalPoint.x - grid.getCentroid().x,  // <-- Subtract centroid
                    (float) gridLocalPoint.y - grid.getCentroid().y,
                    (float) gridLocalPoint.z - grid.getCentroid().z
            );

            // Get the current physics transform (origin at centroid)
            Transform physicsTransform = TEMP_TRANSFORM.get();
            grid.getCurrentTransform(physicsTransform);

            // Apply transform
            physicsTransform.transform(adjustedPoint);

            return new Vec3d(adjustedPoint.x, adjustedPoint.y, adjustedPoint.z);

        } catch (Exception e) {
            SLogger.log(this, "Error transforming grid-local to world: " + e.getMessage());
            return Vec3d.ZERO;
        }
    }

    /**
     * FIXED: Transforms continuous grid-local coordinates to GridSpace coordinates.
     */
    private Vec3d gridLocalToGridSpaceVec3d(LocalGrid grid, Vec3d gridLocalVec) {
        // Apply GridSpace center offset
        double gridSpaceX = gridLocalVec.x + LocalGrid.GRIDSPACE_CENTER_OFFSET;
        double gridSpaceY = gridLocalVec.y + LocalGrid.GRIDSPACE_CENTER_OFFSET;
        double gridSpaceZ = gridLocalVec.z + LocalGrid.GRIDSPACE_CENTER_OFFSET;

        // Add region origin
        BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();

        return new Vec3d(
                gridSpaceX + regionOrigin.getX(),
                gridSpaceY + regionOrigin.getY(),
                gridSpaceZ + regionOrigin.getZ()
        );
    }

    /**
     * FIXED: Transforms continuous GridSpace coordinates to grid-local coordinates.
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
    // VALIDATION AND DEBUGGING (ENHANCED)
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
     * ENHANCED: Debug method with round-trip accuracy testing.
     */
    public void debugTransformationChain(Vec3d worldPos, World world) {
        SLogger.log(this, "=== TRANSFORMATION DEBUG ===");
        SLogger.log(this, "World position: " + worldPos);

        Optional<GridSpaceTransformResult> result = worldToGridSpace(worldPos, world);
        if (result.isPresent()) {
            GridSpaceTransformResult r = result.get();
            SLogger.log(this, "Grid: " + r.grid.getGridId());
            SLogger.log(this, "Grid-local continuous: " + r.gridLocalVec);
            SLogger.log(this, "Grid-local discrete: " + r.gridLocalPos);
            SLogger.log(this, "GridSpace discrete: " + r.gridSpacePos);
            SLogger.log(this, "GridSpace continuous: " + r.gridSpaceVec);

            // Test round-trip transformation with CONTINUOUS coordinates
            Vec3d backToWorld = gridSpaceToWorld(r.gridSpaceVec, r.grid);
            double error = worldPos.distanceTo(backToWorld);
            SLogger.log(this, "Round-trip world: " + backToWorld);
            SLogger.log(this, "Round-trip error: " + String.format("%.6f blocks", error));

            if (error > 0.001) {
                SLogger.log(this, "⚠ WARNING: High transformation error!");
            } else {
                SLogger.log(this, "✓ Transformation accuracy acceptable");
            }
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
    // RESULT CLASS (ENHANCED)
    // ===============================================

    /**
     * ENHANCED: Result of a world → GridSpace transformation with both discrete and continuous coordinates.
     */
    public static class GridSpaceTransformResult {
        public final LocalGrid grid;
        public final BlockPos gridSpacePos;      // Discrete GridSpace coordinates (for block operations)
        public final Vec3d gridSpaceVec;         // Continuous GridSpace coordinates (for accurate transforms)
        public final Vec3d gridLocalVec;         // Continuous grid-local coordinates (for round-trips)
        public final BlockPos gridLocalPos;     // Discrete grid-local coordinates (for block operations)

        public GridSpaceTransformResult(LocalGrid grid, BlockPos gridSpacePos, Vec3d gridSpaceVec,
                                        Vec3d gridLocalVec, BlockPos gridLocalPos) {
            this.grid = grid;
            this.gridSpacePos = gridSpacePos;
            this.gridSpaceVec = gridSpaceVec;
            this.gridLocalVec = gridLocalVec;
            this.gridLocalPos = gridLocalPos;
        }

        @Override
        public String toString() {
            return String.format("GridSpaceTransformResult{grid=%s, gridSpace=%s, gridLocal=%s}",
                    grid.getGridId(), gridSpacePos, gridLocalPos);
        }
    }
}