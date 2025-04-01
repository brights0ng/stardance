package net.stardance.core.component;

import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CompoundShape;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.util.math.BlockPos;
import net.stardance.core.LocalBlock;
import net.stardance.core.LocalGrid;
import net.stardance.utils.SLogger;
import org.joml.Vector3i;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Handles the optimization of block collision shapes by merging adjacent
 * blocks into larger box shapes, reducing the number of collision objects.
 */
public class GridBlockMerger {
    // ----------------------------------------------
    // PARENT REFERENCE
    // ----------------------------------------------
    private final LocalGrid grid;

    // ----------------------------------------------
    // BLOCK MAP
    // ----------------------------------------------
    private boolean[][][] blockMap; // Boolean map of occupied spaces for merging

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------
    /**
     * Creates a new GridBlockMerger for the given LocalGrid.
     *
     * @param grid The parent LocalGrid
     */
    public GridBlockMerger(LocalGrid grid) {
        this.grid = grid;
    }

    // ----------------------------------------------
    // PUBLIC METHODS
    // ----------------------------------------------
    /**
     * Adds a placeholder shape to the compound shape when no blocks exist.
     *
     * @param compound The compound shape to add to
     */
    public void addPlaceholderShape(CompoundShape compound) {
        compound.addChildShape(new Transform(), new BoxShape(new Vector3f(0.01f, 0.01f, 0.01f)));
    }

    /**
     * Updates the blockMap array for shape merging optimization.
     *
     * @param blocks The blocks to map
     * @param aabbMin Minimum AABB point
     * @param aabbMax Maximum AABB point
     */
    public void updateBlockMap(Map<BlockPos, LocalBlock> blocks, Vector3i aabbMin, Vector3i aabbMax) {
        // Calculate size of the block map
        int sizeX = aabbMax.x - aabbMin.x + 1;
        int sizeY = aabbMax.y - aabbMin.y + 1;
        int sizeZ = aabbMax.z - aabbMin.z + 1;

        // Create map
        this.blockMap = new boolean[sizeX][sizeY][sizeZ];

        // Fill map with all occupied locations
        for (LocalBlock localBlock : blocks.values()) {
            int x = localBlock.getPosition().getX() - aabbMin.x;
            int y = localBlock.getPosition().getY() - aabbMin.y;
            int z = localBlock.getPosition().getZ() - aabbMin.z;
            blockMap[x][y][z] = true;
        }
    }

    /**
     * Adds a box shape to the compound shape with the appropriate transform.
     *
     * @param box The box shape data
     * @param compound The compound shape to add to
     * @param aabbMin Minimum AABB point
     * @param centroid Center of mass
     */
    public void addBoxShapeToCompound(BoxShapeData box, CompoundShape compound, Vector3i aabbMin, Vector3f centroid) {
        float worldX = (box.x + aabbMin.x);
        float worldY = (box.y + aabbMin.y);
        float worldZ = (box.z + aabbMin.z);

        // Calculate half extents
        float halfX = (box.width * 0.5f);
        float halfY = (box.height * 0.5f);
        float halfZ = (box.depth * 0.5f);

        // Create box shape
        BoxShape boxShape = new BoxShape(new Vector3f(halfX, halfY, halfZ));
        boxShape.setMargin(0.01f);

        // Position relative to centroid
        Transform localTransform = new Transform();
        localTransform.setIdentity();
        Vector3f localTranslation = new Vector3f(
                worldX + halfX,
                worldY + halfY,
                worldZ + halfZ
        );
        localTranslation.sub(centroid);

        localTransform.origin.set(localTranslation);
        compound.addChildShape(localTransform, boxShape);
    }

    /**
     * Merges adjacent blocks into larger box shapes to reduce collision complexity.
     * Uses a greedy algorithm to create as few boxes as possible.
     *
     * @return List of merged box shapes
     */
    public List<BoxShapeData> generateMergedBoxes() {
        if (blockMap == null) {
            return Collections.emptyList();
        }

        List<BoxShapeData> boxes = new ArrayList<>();
        int sizeX = blockMap.length;
        if (sizeX == 0) return boxes;

        int sizeY = blockMap[0].length;
        if (sizeY == 0) return boxes;

        int sizeZ = blockMap[0][0].length;
        if (sizeZ == 0) return boxes;

        // Track which blocks have been processed
        boolean[][][] visited = new boolean[sizeX][sizeY][sizeZ];

        // Process in Y, Z, X order for better merging of common structures
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    // Skip if empty or already processed
                    if (!blockMap[x][y][z] || visited[x][y][z]) {
                        continue;
                    }

                    // Start with a 1x1x1 box
                    int width = 1;
                    int height = 1;
                    int depth = 1;

                    // Extend along X-axis first
                    while (x + width < sizeX && blockMap[x + width][y][z] && !visited[x + width][y][z]) {
                        width++;
                    }

                    // Then extend along Z-axis
                    boolean canExtendZ = true;
                    while ((z + depth) < sizeZ && canExtendZ) {
                        for (int i = x; i < x + width; i++) {
                            if (!blockMap[i][y][z + depth] || visited[i][y][z + depth]) {
                                canExtendZ = false;
                                break;
                            }
                        }
                        if (canExtendZ) {
                            depth++;
                        }
                    }

                    // Finally extend along Y-axis
                    boolean canExtendY = true;
                    while ((y + height) < sizeY && canExtendY) {
                        for (int i = x; i < x + width; i++) {
                            for (int j = z; j < z + depth; j++) {
                                if (!blockMap[i][y + height][j] || visited[i][y + height][j]) {
                                    canExtendY = false;
                                    break;
                                }
                            }
                            if (!canExtendY) break;
                        }
                        if (canExtendY) {
                            height++;
                        }
                    }

                    // Mark all blocks in this box as visited
                    for (int i = x; i < x + width; i++) {
                        for (int j = y; j < y + height; j++) {
                            for (int k = z; k < z + depth; k++) {
                                visited[i][j][k] = true;
                            }
                        }
                    }

                    // Add box to list
                    boxes.add(new BoxShapeData(x, y, z, width, height, depth));
                }
            }
        }

        SLogger.log(grid, "Generated " + boxes.size() + " merged box shapes");
        return boxes;
    }

    // ----------------------------------------------
    // NESTED CLASSES
    // ----------------------------------------------
    /**
     * Represents a merged box region for collision shapes.
     */
    public class BoxShapeData {
        public final int x, y, z;
        public final int width, height, depth;

        public BoxShapeData(int x, int y, int z, int width, int height, int depth) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
    }
}