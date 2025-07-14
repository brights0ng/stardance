package net.starlight.stardance.utils;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
    // Add this method to your TransformationAPI class

    /**
     * ENHANCED DEBUG: Logs detailed information about worldToGridSpace transformation.
     */
    public Optional<GridSpaceTransformResult> worldToGridSpace(Vec3 worldPos, Level world) {
        SLogger.log(this, "=== TRANSFORMATION API DEBUG ===");
        SLogger.log(this, "Input world position: " + worldPos);
        SLogger.log(this, "World: " + (world.isClientSide ? "CLIENT" : "SERVER"));

        // Find which grid (if any) contains this world position
        SLogger.log(this, "Looking for grids at world position...");

        PhysicsEngine engine = engineManager.getEngine(world);
        if (engine == null) {
            SLogger.log(this, "ERROR: No physics engine for world");
            return Optional.empty();
        }

        SLogger.log(this, "Physics engine found, checking " + engine.getGrids().size() + " grids");

        for (LocalGrid grid : engine.getGrids()) {
            SLogger.log(this, "Checking grid " + grid.getGridId());

            // Check AABB first (faster)
            if (isWorldPositionInGridAABB(worldPos, grid)) {
                SLogger.log(this, "  Position is within grid AABB");

                // Check actual block existence
                if (isWorldPositionInGrid(worldPos, grid)) {
                    SLogger.log(this, "  Position has actual block - GRID FOUND!");

                    try {
                        // Transform world → grid-local (KEEP CONTINUOUS COORDINATES)
                        Vector3d worldPoint = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
                        Vector3d gridLocalPoint = grid.worldToGridLocal(worldPoint);

                        SLogger.log(this, "  Grid-local point: " + gridLocalPoint);

                        // FIXED: Keep continuous coordinates for round-trip accuracy
                        Vec3 gridLocalVec = new Vec3(gridLocalPoint.x, gridLocalPoint.y, gridLocalPoint.z);

                        // Convert to discrete BlockPos for block-based operations
                        BlockPos gridLocalPos = new BlockPos(
                                (int) Math.floor(gridLocalPoint.x),
                                (int) Math.floor(gridLocalPoint.y),
                                (int) Math.floor(gridLocalPoint.z)
                        );

                        SLogger.log(this, "  Grid-local BlockPos: " + gridLocalPos);

                        // Transform CONTINUOUS grid-local → GridSpace for accuracy
                        Vec3 gridSpaceVec = gridLocalToGridSpaceVec3d(grid, gridLocalVec);

                        // Also provide discrete GridSpace coordinates for block operations
                        BlockPos gridSpacePos = grid.gridLocalToGridSpace(gridLocalPos);

                        SLogger.log(this, "  GridSpace vec: " + gridSpaceVec);
                        SLogger.log(this, "  GridSpace BlockPos: " + gridSpacePos);

                        GridSpaceTransformResult result = new GridSpaceTransformResult(
                                grid, gridSpacePos, gridSpaceVec, gridLocalVec, gridLocalPos);

                        SLogger.log(this, "=== TRANSFORMATION SUCCESS ===");
                        return Optional.of(result);

                    } catch (Exception e) {
                        SLogger.log(this, "  ERROR during transformation: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    SLogger.log(this, "  Position is in AABB but no block found");
                }
            } else {
                SLogger.log(this, "  Position is outside grid AABB");
            }
        }

        SLogger.log(this, "No grid found at position");
        SLogger.log(this, "=== TRANSFORMATION FAILED ===");
        return Optional.empty();
    }

    /**
     * FIXED: Transforms GridSpace coordinates back to world coordinates with full precision.
     *
     * @param gridSpaceVec Continuous GridSpace position (Vec3d)
     * @param grid The grid this position belongs to
     * @return World coordinates
     */
    public Vec3 gridSpaceToWorld(Vec3 gridSpaceVec, LocalGrid grid) {
        try {
            // Transform GridSpace → grid-local (CONTINUOUS)
            Vec3 gridLocalVec = gridSpaceVecToGridLocal(grid, gridSpaceVec);

            // Transform grid-local → world using physics matrix
            Vector3d gridLocalPoint = new Vector3d(gridLocalVec.x, gridLocalVec.y, gridLocalVec.z);
            return gridLocalToWorld(grid, gridLocalPoint);

        } catch (Exception e) {
            SLogger.log(this, "Error transforming GridSpace to world: " + e.getMessage());
            return Vec3.ZERO;
        }
    }

    /**
     * LEGACY: Transforms discrete GridSpace coordinates to world coordinates.
     *
     * @param gridSpacePos GridSpace position (BlockPos)
     * @param grid The grid this position belongs to
     * @return World coordinates
     */
    public Vec3 gridSpaceToWorld(BlockPos gridSpacePos, LocalGrid grid) {
        try {
            // Convert BlockPos to Vec3d (center of block)
            Vec3 gridSpaceVec = new Vec3(
                    gridSpacePos.getX() + 0.5,
                    gridSpacePos.getY() + 0.5,
                    gridSpacePos.getZ() + 0.5
            );

            return gridSpaceToWorld(gridSpaceVec, grid);

        } catch (Exception e) {
            SLogger.log(this, "Error transforming GridSpace BlockPos to world: " + e.getMessage());
            return Vec3.ZERO;
        }
    }

    // ===============================================
    // GRID DETECTION METHODS (UNCHANGED)
    // ===============================================

    /**
     * Finds which grid (if any) contains the given world position.
     * Uses physics transforms and bounding boxes.
     */
    public Optional<LocalGrid> findGridAtWorldPosition(Vec3 worldPos, Level world) {
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
     * FIXED: Uses physics collision shapes instead of block-by-block checking.
     */
    public boolean isWorldPositionInGrid(Vec3 worldPos, LocalGrid grid) {
        try {
            // First check AABB for performance
            if (!isWorldPositionInGridAABB(worldPos, grid)) {
                return false;
            }

            // Use physics raycast to check collision shape
            // Cast a very short ray from the position to see if it hits the grid
            Vec3 shortOffset = new Vec3(0.01, 0, 0); // 1cm offset

            PhysicsEngine engine = engineManager.getEngine(grid.getWorld());
            Optional<PhysicsEngine.PhysicsRaycastResult> result =
                    engine.raycastGrids(worldPos, worldPos.add(shortOffset));

            return result.isPresent() && result.get().grid.equals(grid);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Alternative method: check if world position is within grid's AABB.
     * More performant but less precise than block-by-block checking.
     */
    public boolean isWorldPositionInGridAABB(Vec3 worldPos, LocalGrid grid) {
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
    public Vec3 gridLocalToWorld(LocalGrid grid, Vector3d gridLocalPoint) {
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

            return new Vec3(adjustedPoint.x, adjustedPoint.y, adjustedPoint.z);

        } catch (Exception e) {
            SLogger.log(this, "Error transforming grid-local to world: " + e.getMessage());
            return Vec3.ZERO;
        }
    }

    /**
     * FIXED: Transforms continuous grid-local coordinates to GridSpace coordinates.
     */
    private Vec3 gridLocalToGridSpaceVec3d(LocalGrid grid, Vec3 gridLocalVec) {
        // Apply GridSpace center offset
        double gridSpaceX = gridLocalVec.x + LocalGrid.GRIDSPACE_CENTER_OFFSET;
        double gridSpaceY = gridLocalVec.y + LocalGrid.GRIDSPACE_CENTER_OFFSET;
        double gridSpaceZ = gridLocalVec.z + LocalGrid.GRIDSPACE_CENTER_OFFSET;

        // Add region origin
        BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();

        return new Vec3(
                gridSpaceX + regionOrigin.getX(),
                gridSpaceY + regionOrigin.getY(),
                gridSpaceZ + regionOrigin.getZ()
        );
    }

    /**
     * FIXED: Transforms continuous GridSpace coordinates to grid-local coordinates.
     */
    private Vec3 gridSpaceVecToGridLocal(LocalGrid grid, Vec3 gridSpaceVec) {
        // Subtract region origin
        BlockPos regionOrigin = grid.getGridSpaceRegion().getRegionOrigin();

        double regionLocalX = gridSpaceVec.x - regionOrigin.getX();
        double regionLocalY = gridSpaceVec.y - regionOrigin.getY();
        double regionLocalZ = gridSpaceVec.z - regionOrigin.getZ();

        // Remove GridSpace center offset
        return new Vec3(
                regionLocalX - LocalGrid.GRIDSPACE_CENTER_OFFSET,
                regionLocalY - LocalGrid.GRIDSPACE_CENTER_OFFSET,
                regionLocalZ - LocalGrid.GRIDSPACE_CENTER_OFFSET
        );
    }

    // ===============================================
    // DISTANCE AND INTERACTION UTILITIES
    // ===============================================

    /**
     * VS2-style distance calculation that uses world coordinates for accuracy.
     * Equivalent to VS2's VSGameUtilsKt.squaredDistanceBetweenInclShips()
     */
    public static double squaredDistanceBetweenInclGrids(Level world, Vec3 pos1, Vec3 pos2) {
        try {
            // Convert positions to world coordinates if they're in GridSpace
            Vec3 worldPos1 = ensureWorldCoordinates(pos1, world);
            Vec3 worldPos2 = ensureWorldCoordinates(pos2, world);

            return worldPos1.distanceToSqr(worldPos2);
        } catch (Exception e) {
            SLogger.log("TransformationAPI", "Error in distance calculation, falling back to direct: " + e.getMessage());
            return pos1.distanceToSqr(pos2); // Fallback to direct calculation
        }
    }

    /**
     * VS2-style coordinate getter that ensures we always return world coordinates.
     * Equivalent to VS2's VSGameUtilsKt.getWorldCoordinates()
     */
    public static Vec3 getWorldCoordinates(Level world, BlockPos pos, Vec3 localOffset) {
        // Check if this position is in GridSpace
        Optional<GridSpaceTransformResult> gridResult = getInstance().detectGridSpacePosition(pos, world);

        if (gridResult.isPresent()) {
            GridSpaceTransformResult result = gridResult.get();
            // Transform GridSpace + offset back to world coordinates
            Vec3 gridSpacePos = new Vec3(pos.getX() + localOffset.x, pos.getY() + localOffset.y, pos.getZ() + localOffset.z);
            return getInstance().gridSpaceToWorld(gridSpacePos, result.grid);
        }

        // Regular world position
        return new Vec3(pos.getX() + localOffset.x, pos.getY() + localOffset.y, pos.getZ() + localOffset.z);
    }

    /**
     * Helper to ensure coordinates are in world space, not GridSpace.
     */
    private static Vec3 ensureWorldCoordinates(Vec3 pos, Level world) {
        BlockPos blockPos = new BlockPos((int)Math.floor(pos.x), (int)Math.floor(pos.y), (int)Math.floor(pos.z));
        Vec3 offset = new Vec3(pos.x - blockPos.getX(), pos.y - blockPos.getY(), pos.z - blockPos.getZ());
        return getWorldCoordinates(world, blockPos, offset);
    }

    /**
     * Detects if a BlockPos is in GridSpace coordinates.
     * Returns the grid and transformation info if found.
     */
    public Optional<GridSpaceTransformResult> detectGridSpacePosition(BlockPos pos, Level world) {
        PhysicsEngine engine = engineManager.getEngine(world);
        if (engine == null) return Optional.empty();

        // Check all grids to see if this position falls in their GridSpace region
        for (LocalGrid grid : engine.getGrids()) {
            if (!grid.isDestroyed() && grid.getGridSpaceRegion().containsGridSpacePosition(pos)) {
                // Transform back to get all coordinate representations
                Vec3 gridSpaceVec = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                Vec3 gridLocalVec = gridSpaceVecToGridLocal(grid, gridSpaceVec);
                BlockPos gridLocalPos = new BlockPos((int)Math.floor(gridLocalVec.x), (int)Math.floor(gridLocalVec.y), (int)Math.floor(gridLocalVec.z));

                return Optional.of(new GridSpaceTransformResult(grid, pos, gridSpaceVec, gridLocalVec, gridLocalPos));
            }
        }

        return Optional.empty();
    }

    /**
     * ENHANCED: Find the closest actual block in GridSpace with tolerance.
     * Fixes physics engine precision issues by snapping to nearest real block.
     */
    public Optional<GridSpaceTransformResult> findNearestGridSpaceBlock(BlockPos approximateGridSpacePos, Level world, double tolerance) {
        try {
            // First try exact match
            Optional<GridSpaceTransformResult> exactMatch = detectGridSpacePosition(approximateGridSpacePos, world);
            if (exactMatch.isPresent()) {
                LocalGrid grid = exactMatch.get().grid;
                if (grid.hasBlock(exactMatch.get().gridLocalPos)) {
                    return exactMatch; // Perfect match
                }
            }

            // If exact match fails, search nearby positions within tolerance
            int searchRadius = (int) Math.ceil(tolerance);

            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dy = -searchRadius; dy <= searchRadius; dy++) {
                    for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue; // Already tried exact

                        BlockPos testPos = approximateGridSpacePos.offset(dx, dy, dz);
                        Optional<GridSpaceTransformResult> testResult = detectGridSpacePosition(testPos, world);

                        if (testResult.isPresent()) {
                            LocalGrid grid = testResult.get().grid;
                            if (grid.hasBlock(testResult.get().gridLocalPos)) {
//                                SLogger.log("TransformationAPI", String.format(
//                                        "Found block at %s (offset by %d,%d,%d from physics pos %s)",
//                                        testPos, dx, dy, dz, approximateGridSpacePos
//                                ));
                                return testResult;
                            }
                        }
                    }
                }
            }

            SLogger.log("TransformationAPI", "No blocks found within tolerance of " + approximateGridSpacePos);
            return Optional.empty();

        } catch (Exception e) {
            SLogger.log("TransformationAPI", "Error in nearest block search: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * ENHANCED: Get GridSpace transform result with physics precision fix.
     * Uses tolerance to handle physics engine coordinate precision issues.
     */
    public Optional<GridSpaceTransformResult> worldToGridSpaceWithTolerance(Vec3 worldPos, Level world) {
        // First try the regular transformation
        Optional<GridSpaceTransformResult> regularResult = worldToGridSpace(worldPos, world);
        if (regularResult.isPresent()) {
            LocalGrid grid = regularResult.get().grid;
            if (grid.hasBlock(regularResult.get().gridLocalPos)) {
                return regularResult; // Perfect match
            }
        }

        // If regular transformation doesn't find a real block, try with tolerance
        BlockPos approximatePos = new BlockPos((int)Math.floor(worldPos.x), (int)Math.floor(worldPos.y), (int)Math.floor(worldPos.z));
        return findNearestGridSpaceBlock(approximatePos, world, 2.0); // 2 block tolerance
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
    public void debugTransformationChain(Vec3 worldPos, Level world) {
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
            Vec3 backToWorld = gridSpaceToWorld(r.gridSpaceVec, r.grid);
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
        return false;
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
        public final Vec3 gridSpaceVec;         // Continuous GridSpace coordinates (for accurate transforms)
        public final Vec3 gridLocalVec;         // Continuous grid-local coordinates (for round-trips)
        public final BlockPos gridLocalPos;     // Discrete grid-local coordinates (for block operations)

        public GridSpaceTransformResult(LocalGrid grid, BlockPos gridSpacePos, Vec3 gridSpaceVec,
                                        Vec3 gridLocalVec, BlockPos gridLocalPos) {
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