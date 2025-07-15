package net.starlight.stardance.gridspace;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the actual placement and removal of blocks within GridSpace regions.
 * Handles chunk loading, block state management, and cleanup operations.
 */
public class GridSpaceBlockManager implements ILoggingControl {

    // ----------------------------------------------
    // CORE PROPERTIES
    // ----------------------------------------------

    /** The region this manager is responsible for */
    private final GridSpaceRegion region;

    /** Server world where blocks are placed */
    private final ServerLevel world;

    /** Tracks blocks placed in this region for cleanup purposes */
    private final Map<BlockPos, BlockState> placedBlocks = new ConcurrentHashMap<>();

    /** Whether this manager has been shut down */
    private volatile boolean isShutdown = false;

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------

    /**
     * Creates a new block manager for the specified GridSpace region.
     *
     * @param region The GridSpace region to manage
     */
    public GridSpaceBlockManager(GridSpaceRegion region) {
        this.region = region;
        this.world = region.getWorld();

        SLogger.log(this, "Created GridSpaceBlockManager for region " + region.getRegionId());
    }

    // ----------------------------------------------
    // BLOCK PLACEMENT OPERATIONS
    // ----------------------------------------------

    /**
     * Places a block in GridSpace at the specified grid-local position.
     *
     * @param gridLocalPos Position relative to the grid's origin
     * @param blockState Block state to place
     * @return true if block was placed successfully
     */
    public boolean placeBlock(BlockPos gridLocalPos, BlockState blockState) {
        if (isShutdown || region.isCleanedUp()) {
            SLogger.log(this, "Cannot place block - manager is shut down or region is cleaned up");
            return false;
        }

        if (!region.containsGridLocalPosition(gridLocalPos)) {
            SLogger.log(this, "Cannot place block at " + gridLocalPos + " - outside region bounds");
            return false;
        }

        // Convert to GridSpace coordinates
        BlockPos gridSpacePos = region.gridLocalToGridSpace(gridLocalPos);

        try {
            // Ensure chunk is loaded
            ensureChunkLoaded(gridSpacePos);

            // Place the block
            world.setBlockAndUpdate(gridSpacePos, blockState);

            // Track the placement for cleanup
            placedBlocks.put(gridSpacePos, blockState);

            SLogger.log(this, "Placed block " + blockState.getBlock().getName().getString() +
                    " at GridSpace " + gridSpacePos + " (grid-local " + gridLocalPos + ")");

            return true;

        } catch (Exception e) {
            SLogger.log(this, "Failed to place block at " + gridSpacePos + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes a block from GridSpace at the specified grid-local position.
     *
     * @param gridLocalPos Position relative to the grid's origin
     * @return true if block was removed successfully
     */
    public boolean removeBlock(BlockPos gridLocalPos) {
        if (isShutdown || region.isCleanedUp()) {
            return false;
        }

        if (!region.containsGridLocalPosition(gridLocalPos)) {
            return false;
        }

        // Convert to GridSpace coordinates
        BlockPos gridSpacePos = region.gridLocalToGridSpace(gridLocalPos);

        try {
            // Replace with air
            world.setBlockAndUpdate(gridSpacePos, Blocks.AIR.defaultBlockState());

            // Remove from tracking
            placedBlocks.remove(gridSpacePos);

            SLogger.log(this, "Removed block at GridSpace " + gridSpacePos +
                    " (grid-local " + gridLocalPos + ")");

            return true;

        } catch (Exception e) {
            SLogger.log(this, "Failed to remove block at " + gridSpacePos + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the block state at the specified grid-local position.
     *
     * @param gridLocalPos Position relative to the grid's origin
     * @return BlockState at the position, or null if position is invalid
     */
    public BlockState getBlockState(BlockPos gridLocalPos) {
        if (isShutdown || region.isCleanedUp()) {
            return null;
        }

        if (!region.containsGridLocalPosition(gridLocalPos)) {
            return null;
        }

        // Convert to GridSpace coordinates
        BlockPos gridSpacePos = region.gridLocalToGridSpace(gridLocalPos);

        try {
            return world.getBlockState(gridSpacePos);
        } catch (Exception e) {
            SLogger.log(this, "Failed to get block state at " + gridSpacePos + ": " + e.getMessage());
            return null;
        }
    }

    // ----------------------------------------------
    // BATCH OPERATIONS
    // ----------------------------------------------

    /**
     * Places multiple blocks in a single operation.
     * More efficient than individual placements for large numbers of blocks.
     *
     * @param blocks Map of grid-local positions to block states
     * @return Number of blocks successfully placed
     */
    public int placeBlocks(Map<BlockPos, BlockState> blocks) {
        if (isShutdown || region.isCleanedUp()) {
            return 0;
        }

        int successCount = 0;

        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            if (placeBlock(entry.getKey(), entry.getValue())) {
                successCount++;
            }
        }

        SLogger.log(this, "Batch placed " + successCount + "/" + blocks.size() + " blocks");
        return successCount;
    }

    /**
     * Removes all blocks managed by this GridSpace region.
     * Used during cleanup operations.
     *
     * @return Number of blocks successfully removed
     */
    public int clearAllBlocks() {
        if (isShutdown) {
            return 0;
        }

        int removedCount = 0;

        // Create a copy to avoid concurrent modification
        Map<BlockPos, BlockState> blocksToRemove = new ConcurrentHashMap<>(placedBlocks);

        for (BlockPos gridSpacePos : blocksToRemove.keySet()) {
            try {
                world.setBlockAndUpdate(gridSpacePos, Blocks.AIR.defaultBlockState());
                removedCount++;
            } catch (Exception e) {
                SLogger.log(this, "Failed to remove block at " + gridSpacePos + " during cleanup: " + e.getMessage());
            }
        }

        placedBlocks.clear();

        SLogger.log(this, "Cleared " + removedCount + " blocks from GridSpace region " +
                region.getRegionId());

        return removedCount;
    }

    // ----------------------------------------------
    // CHUNK MANAGEMENT
    // ----------------------------------------------

    /**
     * Ensures the chunk containing the specified GridSpace position is loaded.
     * Uses force loading to avoid triggering world generation.
     *
     * @param gridSpacePos Position in GridSpace coordinates
     */
    private void ensureChunkLoaded(BlockPos gridSpacePos) {
        try {
            int chunkX = gridSpacePos.getX() >> 4;
            int chunkZ = gridSpacePos.getZ() >> 4;

            // Check if we're within reasonable world bounds
            if (Math.abs(chunkX) > 1_875_000 || Math.abs(chunkZ) > 1_875_000) {
                SLogger.log(this, "Warning: GridSpace chunk coordinates may be too extreme: " +
                        chunkX + ", " + chunkZ);
            }

            // Force load the chunk without triggering world generation
            // This prevents the world generator from trying to generate structures
            ChunkAccess chunk = world.getChunk(chunkX, chunkZ);
            if (chunk == null) {
                SLogger.log(this, "Warning: Could not load GridSpace chunk at " + chunkX + ", " + chunkZ +
                        " - this may cause issues");
            }
        } catch (Exception e) {
            SLogger.log(this, "Error loading GridSpace chunk at " + gridSpacePos + ": " + e.getMessage());
            // Don't throw - this is a safety net, not a critical failure
        }
    }

    // ----------------------------------------------
    // REGION INFORMATION
    // ----------------------------------------------

    /**
     * Gets the number of blocks currently managed by this region.
     */
    public int getBlockCount() {
        return placedBlocks.size();
    }

    /**
     * Gets the GridSpace region this manager is responsible for.
     */
    public GridSpaceRegion getRegion() {
        return region;
    }

    /**
     * Checks if this block manager has been shut down.
     */
    public boolean isShutdown() {
        return isShutdown;
    }

    // ----------------------------------------------
    // CLEANUP
    // ----------------------------------------------

    /**
     * Shuts down this block manager and cleans up all managed blocks.
     * Called when the associated LocalGrid is being destroyed.
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }

        SLogger.log(this, "Shutting down GridSpaceBlockManager for region " + region.getRegionId());

        // Clear all blocks
        clearAllBlocks();

        isShutdown = true;
    }

    // ----------------------------------------------
    // UTILITY METHODS
    // ----------------------------------------------

    @Override
    public String toString() {
        return "GridSpaceBlockManager{" +
                "regionId=" + region.getRegionId() +
                ", gridId=" + region.getGridId() +
                ", blockCount=" + placedBlocks.size() +
                ", shutdown=" + isShutdown +
                '}';
    }

    public Map<BlockPos, BlockState> getAllBlocks() {
        return placedBlocks;
    }
}