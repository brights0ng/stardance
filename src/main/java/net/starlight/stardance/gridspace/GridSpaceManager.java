package net.starlight.stardance.gridspace;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.Stardance;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.physics.EngineManager;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Manages allocation and deallocation of GridSpace regions.
 * GridSpace is a far-off area of the world where grid blocks actually exist,
 * while being projected to appear elsewhere for physics simulation.
 *
 * Thread-safe singleton manager.
 */
public class GridSpaceManager implements ILoggingControl {

    // ----------------------------------------------
    // GRIDSPACE CONSTANTS
    // ----------------------------------------------

    /**
     * Starting X coordinate for GridSpace regions.
     * Using coordinates that avoid world generation interference.
     * VS2 uses around 28M, we'll use 25M to be safe.
     */
    private static final int GRIDSPACE_ORIGIN_X = 25_000_000;

    /**
     * Starting Z coordinate for GridSpace regions.
     * Using positive coordinates to avoid negative coordinate issues.
     */
    private static final int GRIDSPACE_ORIGIN_Z = 25_000_000;

    /**
     * Y coordinate for GridSpace (well above normal world generation).
     * Using Y=200 to be above most world generation but below build limit.
     */
    private static final int GRIDSPACE_Y = 128;

    /** Size of each GridSpace region in blocks */
    private static final int GRIDSPACE_REGION_SIZE = 1024;

    /** Buffer space between regions to prevent interference */
    private static final int GRIDSPACE_REGION_BUFFER = 128;

    /** Total space per region including buffer */
    private static final int GRIDSPACE_REGION_STRIDE = GRIDSPACE_REGION_SIZE + GRIDSPACE_REGION_BUFFER;

    /** Maximum regions per row before wrapping to next row */
    private static final int MAX_REGIONS_PER_ROW = 200;

    // ----------------------------------------------
    // DIMENSION MANAGEMENT
    // ----------------------------------------------

    /** The server world/dimension this manager is responsible for */
    private final ServerLevel world;

    /** Dimension identifier for logging and debugging */
    private final String dimensionId;

    /** Maps grid UUIDs to their allocated regions */
    private final Map<UUID, GridSpaceRegion> allocatedRegions = new ConcurrentHashMap<>();

    /** Maps region IDs to grid UUIDs for reverse lookup */
    private final Map<Integer, UUID> regionToGridMap = new ConcurrentHashMap<>();

    /** Tracks which region IDs are currently in use */
    private final Set<Integer> usedRegionIds = ConcurrentHashMap.newKeySet();

    /** Counter for generating unique region IDs */
    private final AtomicInteger nextRegionId = new AtomicInteger(0);

    // ----------------------------------------------
    // CACHING
    // ----------------------------------------------

    private static final Map<BlockPos, LocalGrid> GRID_POSITION_CACHE = new ConcurrentHashMap<>();
    private static long lastGridCacheClean = 0;
    private static final long GRID_CACHE_CLEAN_INTERVAL = 10000; // 10 seconds
    private static final int MAX_GRID_CACHE_SIZE = 500;

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return true; }

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------

    /**
     * Creates a new GridSpaceManager for the specified dimension.
     *
     * @param world The server world/dimension this manager will handle
     */
    public GridSpaceManager(ServerLevel world) {
        this.world = world;
        this.dimensionId = world.dimension().location().toString();

        SLogger.log(this, "GridSpaceManager initialized for dimension: " + dimensionId);
    }

    // ----------------------------------------------
    // REGION ALLOCATION
    // ----------------------------------------------

    /**
     * Allocates a new GridSpace region for the specified grid.
     *
     * @param gridId UUID of the grid requesting a region
     * @return Allocated GridSpaceRegion
     * @throws IllegalStateException if grid already has a region allocated
     */
    public GridSpaceRegion allocateRegion(UUID gridId) {
        if (allocatedRegions.containsKey(gridId)) {
            throw new IllegalStateException("Grid " + gridId + " already has a region allocated in dimension " + dimensionId);
        }

        // Find next available region ID
        int regionId = findNextAvailableRegionId();

        // Calculate region position
        BlockPos regionOrigin = calculateRegionOrigin(regionId);

        // Create the region
        GridSpaceRegion region = new GridSpaceRegion(gridId, regionId, regionOrigin, world);

        // Register the allocation
        allocatedRegions.put(gridId, region);
        regionToGridMap.put(regionId, gridId);
        usedRegionIds.add(regionId);

        SLogger.log(this, "Allocated GridSpace region " + regionId + " for grid " + gridId +
                " at origin " + regionOrigin + " in dimension " + dimensionId);

        return region;
    }

    /**
     * Deallocates the GridSpace region for the specified grid.
     *
     * @param gridId UUID of the grid to deallocate
     * @return true if a region was deallocated, false if no region was found
     */
    public boolean deallocateRegion(UUID gridId) {
        GridSpaceRegion region = allocatedRegions.remove(gridId);
        if (region == null) {
            SLogger.log(this, "No region found for grid " + gridId + " during deallocation");
            return false;
        }

        // Clean up tracking maps
        regionToGridMap.remove(region.getRegionId());
        usedRegionIds.remove(region.getRegionId());

        // Clean up the region itself
        region.cleanup();

        SLogger.log(this, "Deallocated GridSpace region " + region.getRegionId() +
                " for grid " + gridId + " in dimension " + dimensionId);

        return true;
    }

    // ----------------------------------------------
    // REGION LOOKUP
    // ----------------------------------------------

    /**
     * Gets the GridSpace region for a specific grid.
     *
     * @param gridId UUID of the grid
     * @return GridSpaceRegion or null if not found
     */
    public GridSpaceRegion getRegion(UUID gridId) {
        return allocatedRegions.get(gridId);
    }

    /**
     * Gets the grid ID that owns a specific region.
     *
     * @param regionId Region ID to look up
     * @return Grid UUID or null if region not found
     */
    public UUID getGridForRegion(int regionId) {
        return regionToGridMap.get(regionId);
    }

    /**
     * Checks if a block position is within any GridSpace region.
     *
     * @param pos Block position to check
     * @return true if position is in GridSpace
     */
    public boolean isInGridSpace(BlockPos pos) {
        return pos.getX() >= GRIDSPACE_ORIGIN_X &&
                pos.getZ() >= GRIDSPACE_ORIGIN_Z &&
                pos.getX() < GRIDSPACE_ORIGIN_X + (MAX_REGIONS_PER_ROW * GRIDSPACE_REGION_STRIDE) &&
                pos.getZ() < GRIDSPACE_ORIGIN_Z + (MAX_REGIONS_PER_ROW * GRIDSPACE_REGION_STRIDE);
    }

    /**
     * Gets the region that contains a specific GridSpace position.
     *
     * @param pos Position in GridSpace
     * @return GridSpaceRegion or null if position is not in any region
     */
    public GridSpaceRegion getRegionContaining(BlockPos pos) {
        if (!isInGridSpace(pos)) {
            return null;
        }

        // Calculate which region this position belongs to
        int relativeX = pos.getX() - GRIDSPACE_ORIGIN_X;
        int relativeZ = pos.getZ() - GRIDSPACE_ORIGIN_Z;

        int regionX = relativeX / GRIDSPACE_REGION_STRIDE;
        int regionZ = relativeZ / GRIDSPACE_REGION_STRIDE;

        int regionId = regionZ * MAX_REGIONS_PER_ROW + regionX;

        UUID gridId = regionToGridMap.get(regionId);
        return gridId != null ? allocatedRegions.get(gridId) : null;
    }

    // ----------------------------------------------
    // UTILITY METHODS
    // ----------------------------------------------

    /**
     * Validates that GridSpace coordinates are safe for world generation.
     * Checks against known Minecraft world boundary limits.
     *
     * @param pos Position to validate
     * @return true if position is safe, false otherwise
     */
    public static boolean isValidGridSpacePosition(BlockPos pos) {
        // Check against Minecraft's world boundary (30M blocks from center)
        final int WORLD_BOUNDARY = 29_999_984; // Minecraft's actual world border limit

        return Math.abs(pos.getX()) < WORLD_BOUNDARY &&
                Math.abs(pos.getZ()) < WORLD_BOUNDARY &&
                pos.getY() >= -64 && pos.getY() <= 319; // Minecraft 1.18+ world height limits
    }

    /**
     * Gets statistics about current GridSpace usage.
     */
    public GridSpaceStats getStats() {
        return new GridSpaceStats(
                allocatedRegions.size(),
                usedRegionIds.size(),
                MAX_REGIONS_PER_ROW * MAX_REGIONS_PER_ROW, // theoretical max
                dimensionId
        );
    }

    /**
     * Gets the server world this manager is responsible for.
     */
    public ServerLevel getWorld() {
        return world;
    }

    /**
     * Gets the dimension identifier for this manager.
     */
    public String getDimensionId() {
        return dimensionId;
    }

    /**
     * Cleanup method for server shutdown.
     */
    public void shutdown() {
        SLogger.log(this, "Shutting down GridSpaceManager for dimension " + dimensionId +
                ", cleaning up " + allocatedRegions.size() + " regions");

        // Clean up all regions
        for (GridSpaceRegion region : allocatedRegions.values()) {
            region.cleanup();
        }

        // Clear all tracking
        allocatedRegions.clear();
        regionToGridMap.clear();
        usedRegionIds.clear();
    }

    // ----------------------------------------------
    // PRIVATE HELPER METHODS
    // ----------------------------------------------

    /**
     * Finds the next available region ID using a simple linear search.
     * Could be optimized with a more sophisticated allocation strategy if needed.
     */
    private int findNextAvailableRegionId() {
        int regionId = nextRegionId.get();

        // Linear search for available ID (simple but effective for most use cases)
        while (usedRegionIds.contains(regionId)) {
            regionId++;
            if (regionId >= MAX_REGIONS_PER_ROW * MAX_REGIONS_PER_ROW) {
                throw new IllegalStateException("GridSpace is full in dimension " + dimensionId +
                        "! Cannot allocate more regions. Maximum: " +
                        (MAX_REGIONS_PER_ROW * MAX_REGIONS_PER_ROW));
            }
        }

        nextRegionId.set(regionId + 1);
        return regionId;
    }

    /**
     * Calculates the world origin position for a region based on its ID.
     * Includes validation to ensure coordinates are within safe bounds.
     */
    private BlockPos calculateRegionOrigin(int regionId) {
        int regionX = regionId % MAX_REGIONS_PER_ROW;
        int regionZ = regionId / MAX_REGIONS_PER_ROW;

        int worldX = GRIDSPACE_ORIGIN_X + (regionX * GRIDSPACE_REGION_STRIDE);
        int worldZ = GRIDSPACE_ORIGIN_Z + (regionZ * GRIDSPACE_REGION_STRIDE);

        BlockPos origin = new BlockPos(worldX, GRIDSPACE_Y, worldZ);

        // Validate the calculated position is safe
        if (!isValidGridSpacePosition(origin)) {
            SLogger.log(this, "WARNING: Calculated GridSpace origin " + origin +
                    " may be outside safe world boundaries for region " + regionId);
        }

        return origin;
    }

    /**
     * Gets the LocalGrid that contains the given GridSpace position.
     * OPTIMIZED version with caching for better performance.
     *
     * This is the core method used by the distance replacement system to determine
     * if a position is in GridSpace and which grid owns it.
     *
     * @param gridSpacePos Position in GridSpace coordinates
     * @return LocalGrid containing this position, or null if not in GridSpace
     */
    public static LocalGrid getGridAtPosition(BlockPos gridSpacePos) {
        try {
            // Clean cache periodically
            cleanGridCacheIfNeeded();

            // Check cache first
            LocalGrid cached = GRID_POSITION_CACHE.get(gridSpacePos);
            if (cached != null) {
                return cached;
            }

            // First check if this is even in GridSpace coordinate range
            if (!isInGridSpaceStatic(gridSpacePos)) {
                return null; // Definitely not in GridSpace
            }

            // Lookup the grid (expensive operation)
            LocalGrid result = performGridLookup(gridSpacePos);

            // Cache the result (even if null, to avoid repeated expensive lookups)
            if (GRID_POSITION_CACHE.size() < MAX_GRID_CACHE_SIZE) {
                GRID_POSITION_CACHE.put(new BlockPos(gridSpacePos), result);
            }

            return result;

        } catch (Exception e) {
            SLogger.log("GridSpaceManager", "Error in getGridAtPosition: " + e.getMessage());
            return null;
        }
    }

    /**
     * Static version of isInGridSpace check.
     * Used by the static getGridAtPosition method.
     */
    private static boolean isInGridSpaceStatic(BlockPos pos) {
        return pos.getX() >= GRIDSPACE_ORIGIN_X &&
                pos.getZ() >= GRIDSPACE_ORIGIN_Z &&
                pos.getX() < GRIDSPACE_ORIGIN_X + (MAX_REGIONS_PER_ROW * GRIDSPACE_REGION_STRIDE) &&
                pos.getZ() < GRIDSPACE_ORIGIN_Z + (MAX_REGIONS_PER_ROW * GRIDSPACE_REGION_STRIDE);
    }

    /**
     * Performs the actual grid lookup across all dimensions.
     */
    private static LocalGrid performGridLookup(BlockPos gridSpacePos) {
        try {
            EngineManager engineManager = Stardance.engineManager;

            for (ServerLevel serverLevel : engineManager.getAllActiveWorlds()) {
                GridSpaceManager manager = engineManager.getGridSpaceManager(serverLevel);
                if (manager != null) {
                    LocalGrid grid = manager.getGridAtPositionInstance(gridSpacePos);
                    if (grid != null) {
                        return grid;
                    }
                }
            }

            return null;

        } catch (Exception e) {
            SLogger.log("GridSpaceManager", "Error in performGridLookup: " + e.getMessage());
            return null;
        }
    }

    /**
     * Finds the LocalGrid that is visually rendered at the given world position.
     * This is different from getRegionContaining() which works with GridSpace coordinates.
     *
     * @param worldPos World position where a grid might be visually rendered
     * @return LocalGrid that contains this world position, or null if none found
     */
    public static LocalGrid getGridAtWorldPosition(BlockPos worldPos) {
        try {
            // Get all engines and check their grids
            for (PhysicsEngine engine : net.starlight.stardance.Stardance.engineManager.getAllEngines().values()) {
                for (LocalGrid grid : engine.getGrids()) {
                    if (grid.isDestroyed()) {
                        continue;
                    }

                    // Check if this world position is within the grid's visual bounds
                    if (isWorldPositionInGrid(worldPos, grid)) {
                        return grid;
                    }
                }
            }

            return null;

        } catch (Exception e) {
            SLogger.log("GridSpaceManager", "Error finding grid at world position " + worldPos + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if a world position is within a grid's visual rendering area.
     */
    private static boolean isWorldPositionInGrid(BlockPos worldPos, LocalGrid grid) {
        try {
            // Convert world position to grid-local coordinates
            Vec3 worldVec = Vec3.atCenterOf(worldPos);
            Vec3 gridLocalVec = grid.worldToGridSpace(worldVec);
            BlockPos gridLocalPos = BlockPos.containing(gridLocalVec);

            // Check if this grid-local position has a block
            return grid.hasBlock(gridLocalPos);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Instance method version of getGridAtPosition for this specific dimension.
     */
    public LocalGrid getGridAtPositionInstance(BlockPos gridSpacePos) {
        try {
            // Check if position is in GridSpace
            if (!isInGridSpace(gridSpacePos)) {
                return null;
            }

            // Find the region containing this position
            GridSpaceRegion region = getRegionContaining(gridSpacePos);
            if (region == null) {
                return null; // Position in GridSpace but no allocated region
            }

            // Get the grid UUID for this region
            UUID gridId = region.getGridId();
            if (gridId == null) {
                return null;
            }

            // Get the LocalGrid from the physics engine
            PhysicsEngine physicsEngine = engineManager.getEngine(world);
            if (physicsEngine == null) {
                return null;
            }

            // Search through active grids to find the one with matching UUID
            for (LocalGrid grid : physicsEngine.getActiveGrids()) {
                if (gridId.equals(grid.getGridId())) {
                    return grid;
                }
            }

            // Grid region exists but LocalGrid not found in physics engine
            // This might happen during grid creation/destruction - not necessarily an error
            return null;

        } catch (Exception e) {
            SLogger.log(this, "Error in getGridAtPositionInstance: " + e.getMessage());
            return null;
        }
    }

    /**
     * Periodically cleans the grid position cache to prevent memory leaks.
     */
    private static void cleanGridCacheIfNeeded() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastGridCacheClean > GRID_CACHE_CLEAN_INTERVAL) {
            if (GRID_POSITION_CACHE.size() > MAX_GRID_CACHE_SIZE) {
                GRID_POSITION_CACHE.clear(); // Simple clear strategy
            }
            lastGridCacheClean = currentTime;
        }
    }

    /**
     * Clears the grid position cache manually.
     * Useful for debugging or when grids are created/destroyed.
     */
    public static void clearGridPositionCache() {
        GRID_POSITION_CACHE.clear();
        SLogger.log("GridSpaceManager", "Grid position cache cleared manually");
    }

    // ----------------------------------------------
    // INNER CLASSES
    // ----------------------------------------------

    /**
     * Statistics about GridSpace usage.
     */
    public static class GridSpaceStats {
        private final int allocatedRegions;
        private final int usedRegionIds;
        private final int maxRegions;
        private final String dimensionId;

        public GridSpaceStats(int allocated, int used, int max, String dimensionId) {
            this.allocatedRegions = allocated;
            this.usedRegionIds = used;
            this.maxRegions = max;
            this.dimensionId = dimensionId;
        }

        public int getAllocatedRegions() { return allocatedRegions; }
        public int getUsedRegionIds() { return usedRegionIds; }
        public int getMaxRegions() { return maxRegions; }
        public String getDimensionId() { return dimensionId; }
        public double getUsagePercentage() { return (double) allocatedRegions / maxRegions * 100.0; }

        @Override
        public String toString() {
            return String.format("GridSpace Stats [%s]: %d/%d regions allocated (%.1f%% usage)",
                    dimensionId, allocatedRegions, maxRegions, getUsagePercentage());
        }
    }
}