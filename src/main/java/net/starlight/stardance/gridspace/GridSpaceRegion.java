package net.starlight.stardance.gridspace;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import java.util.UUID;

/**
 * Represents a specific region in GridSpace allocated to a LocalGrid.
 * Handles coordinate transformations between world space and GridSpace.
 */
public class GridSpaceRegion implements ILoggingControl {

    // ----------------------------------------------
    // CORE PROPERTIES
    // ----------------------------------------------

    /** UUID of the grid that owns this region */
    private final UUID gridId;

    /** Unique ID for this region within GridSpace */
    private final int regionId;

    /** Origin point of this region in GridSpace coordinates */
    private final BlockPos regionOrigin;

    /** Server world this region exists in */
    private final ServerWorld world;

    /** Size of this region in blocks */
    private final int regionSize;

    /** Whether this region has been cleaned up */
    private volatile boolean isCleanedUp = false;

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
     * Creates a new GridSpace region.
     * Package-private constructor - only GridSpaceManager should create these.
     */
    GridSpaceRegion(UUID gridId, int regionId, BlockPos regionOrigin, ServerWorld world) {
        this.gridId = gridId;
        this.regionId = regionId;
        this.regionOrigin = regionOrigin;
        this.world = world;
        this.regionSize = 1024; // Match GridSpaceManager.GRIDSPACE_REGION_SIZE

        SLogger.log(this, "Created GridSpace region " + regionId + " for grid " + gridId +
                " at " + regionOrigin);
    }

    // ----------------------------------------------
    // COORDINATE TRANSFORMATIONS
    // ----------------------------------------------

    /**
     * Transforms a position from grid-local coordinates to GridSpace coordinates.
     * Grid-local coordinates are relative to the grid's origin (0,0,0).
     *
     * @param gridLocalPos Position relative to grid origin
     * @return Position in GridSpace coordinates
     */
    public BlockPos gridLocalToGridSpace(BlockPos gridLocalPos) {
        if (isCleanedUp) {
            throw new IllegalStateException("Cannot use cleaned up GridSpace region");
        }

        return regionOrigin.add(gridLocalPos);
    }

    /**
     * Transforms a position from GridSpace coordinates to grid-local coordinates.
     *
     * @param gridSpacePos Position in GridSpace coordinates
     * @return Position relative to grid origin
     */
    public BlockPos gridSpaceToGridLocal(BlockPos gridSpacePos) {
        if (isCleanedUp) {
            throw new IllegalStateException("Cannot use cleaned up GridSpace region");
        }

        return gridSpacePos.subtract(regionOrigin);
    }

    /**
     * Checks if a GridSpace position is within this region's bounds.
     *
     * @param gridSpacePos Position to check
     * @return true if position is within this region
     */
    public boolean containsGridSpacePosition(BlockPos gridSpacePos) {
        if (isCleanedUp) {
            return false;
        }

        int relativeX = gridSpacePos.getX() - regionOrigin.getX();
        int relativeY = gridSpacePos.getY() - regionOrigin.getY();
        int relativeZ = gridSpacePos.getZ() - regionOrigin.getZ();

        return relativeX >= 0 && relativeX < regionSize &&
                relativeY >= 0 && relativeY < regionSize &&
                relativeZ >= 0 && relativeZ < regionSize;
    }

    /**
     * Checks if a grid-local position is within valid bounds for this region.
     *
     * @param gridLocalPos Position to check
     * @return true if position is within region bounds
     */
    public boolean containsGridLocalPosition(BlockPos gridLocalPos) {
        return gridLocalPos.getX() >= 0 && gridLocalPos.getX() < regionSize &&
                gridLocalPos.getY() >= 0 && gridLocalPos.getY() < regionSize &&
                gridLocalPos.getZ() >= 0 && gridLocalPos.getZ() < regionSize;
    }

    // ----------------------------------------------
    // REGION PROPERTIES
    // ----------------------------------------------

    /**
     * Gets the UUID of the grid that owns this region.
     */
    public UUID getGridId() {
        return gridId;
    }

    /**
     * Gets the unique region ID.
     */
    public int getRegionId() {
        return regionId;
    }

    /**
     * Gets the origin point of this region in GridSpace.
     */
    public BlockPos getRegionOrigin() {
        return regionOrigin;
    }

    /**
     * Gets the server world this region exists in.
     */
    public ServerWorld getWorld() {
        return world;
    }

    /**
     * Gets the size of this region in blocks.
     */
    public int getRegionSize() {
        return regionSize;
    }

    /**
     * Gets the bounding box of this region in GridSpace coordinates.
     */
    public Box getRegionBounds() {
        return new Box(
                regionOrigin.getX(), regionOrigin.getY(), regionOrigin.getZ(),
                regionOrigin.getX() + regionSize, regionOrigin.getY() + regionSize, regionOrigin.getZ() + regionSize
        );
    }

    /**
     * Checks if this region has been cleaned up.
     */
    public boolean isCleanedUp() {
        return isCleanedUp;
    }

    // ----------------------------------------------
    // REGION MANAGEMENT
    // ----------------------------------------------

    /**
     * Cleans up this region and marks it as unusable.
     * Called by GridSpaceManager during deallocation.
     */
    void cleanup() {
        if (isCleanedUp) {
            return;
        }

        SLogger.log(this, "Cleaning up GridSpace region " + regionId + " for grid " + gridId);

        // TODO: Add any necessary cleanup logic here
        // - Clear blocks from GridSpace?
        // - Unload chunks?
        // - Clean up any cached data?

        isCleanedUp = true;
    }

    // ----------------------------------------------
    // UTILITY METHODS
    // ----------------------------------------------

    @Override
    public String toString() {
        return "GridSpaceRegion{" +
                "gridId=" + gridId +
                ", regionId=" + regionId +
                ", origin=" + regionOrigin +
                ", size=" + regionSize +
                ", cleanedUp=" + isCleanedUp +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        GridSpaceRegion that = (GridSpaceRegion) o;
        return regionId == that.regionId && gridId.equals(that.gridId);
    }

    @Override
    public int hashCode() {
        return gridId.hashCode() * 31 + regionId;
    }
}