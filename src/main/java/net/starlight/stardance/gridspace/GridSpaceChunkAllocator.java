package net.starlight.stardance.gridspace;

/**
 * Utility to check if chunks are in GridSpace areas.
 * Based on your GridSpaceManager constants.
 */
public class GridSpaceChunkAllocator {

    // Use the same constants as your GridSpaceManager
    private static final int GRIDSPACE_ORIGIN_X = 25_000_000;
    private static final int GRIDSPACE_ORIGIN_Z = 25_000_000;

    // Reasonable GridSpace area bounds (covers potential regions)
    private static final int GRIDSPACE_MAX_SIZE = 1_000_000; // 1M blocks in each direction

    /**
     * Check if a chunk coordinate is within the GridSpace area.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if chunk is in GridSpace area
     */
    public static boolean isChunkInGridSpace(int chunkX, int chunkZ) {
        // Convert chunk coordinates to block coordinates
        int blockX = chunkX * 16;
        int blockZ = chunkZ * 16;

        // Check if within the GridSpace area
        return blockX >= GRIDSPACE_ORIGIN_X &&
                blockZ >= GRIDSPACE_ORIGIN_Z &&
                blockX < (GRIDSPACE_ORIGIN_X + GRIDSPACE_MAX_SIZE) &&
                blockZ < (GRIDSPACE_ORIGIN_Z + GRIDSPACE_MAX_SIZE);
    }

    /**
     * Check if a block position is within GridSpace area.
     */
    public static boolean isBlockInGridSpace(int blockX, int blockZ) {
        return blockX >= GRIDSPACE_ORIGIN_X &&
                blockZ >= GRIDSPACE_ORIGIN_Z &&
                blockX < (GRIDSPACE_ORIGIN_X + GRIDSPACE_MAX_SIZE) &&
                blockZ < (GRIDSPACE_ORIGIN_Z + GRIDSPACE_MAX_SIZE);
    }
}