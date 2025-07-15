package net.starlight.stardance.network;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ENHANCED: GridSpace-aware client grid manager with comprehensive debug logging.
 */
public class ClientGridManager implements ILoggingControl {
    // Singleton instance
    private static ClientGridManager INSTANCE;

    // Map of grid ID to client grid
    private final Map<UUID, ClientLocalGrid> grids = new ConcurrentHashMap<>();

    // Debug counters
    private int updateStateCount = 0;
    private int updateBlocksCount = 0;
    private int renderCallCount = 0;
    private long lastDebugLogTime = 0;
    private static final long DEBUG_LOG_INTERVAL = 5000; // Log every 5 seconds

    // Enable for comprehensive debugging
    private boolean verbose = true;

    /**
     * Gets the singleton instance of the registry.
     */
    public static ClientGridManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClientGridManager();
        }
        return INSTANCE;
    }

    /**
     * Private constructor for singleton.
     */
    private ClientGridManager() {
        SLogger.log(this, "ClientGridManager instance created");
    }

    /**
     * Gets or creates a client grid with the given ID.
     */
    public ClientLocalGrid getOrCreateGrid(UUID gridId) {
        return grids.computeIfAbsent(gridId, id -> {
            ClientLocalGrid grid = new ClientLocalGrid(id);
            SLogger.log(this, "Created new client grid: " + id + " (total grids: " + (grids.size() + 1) + ")");
            return grid;
        });
    }

    /**
     * ENHANCED: Updates a grid's state with debug logging.
     */
    public void updateGridState(UUID gridId, Vector3f position, Quat4f rotation, Vector3f centroid, long serverTick) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateState(position, rotation, centroid, serverTick);
        updateStateCount++;

        if (verbose && updateStateCount % 60 == 0) { // Log every 60 updates (3 seconds at 20 TPS)
            SLogger.log(this, "State update #" + updateStateCount + " for grid " + gridId +
                    " - pos=" + position + ", tick=" + serverTick);
        }
    }

    /**
     * ENHANCED: Updates GridSpace information with logging.
     */
    public void updateGridSpaceInfo(UUID gridId, int regionId, BlockPos regionOrigin) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateGridSpaceInfo(regionId, regionOrigin);

        SLogger.log(this, "Updated GridSpace info for grid " + gridId +
                ", regionId=" + regionId + ", origin=" + regionOrigin);
    }

    /**
     * ENHANCED: Updates a grid's blocks using GridSpace coordinates with logging.
     */
    public void updateGridSpaceBlocks(UUID gridId, Map<BlockPos, BlockState> gridSpaceBlocks) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateGridSpaceBlocks(gridSpaceBlocks);
        updateBlocksCount++;

        SLogger.log(this, "Updated GridSpace blocks for grid " + gridId +
                " - " + gridSpaceBlocks.size() + " blocks (update #" + updateBlocksCount + ")");

        // Log some sample blocks for debugging
        if (verbose && !gridSpaceBlocks.isEmpty()) {
            int logged = 0;
            for (Map.Entry<BlockPos, BlockState> entry : gridSpaceBlocks.entrySet()) {
                if (logged >= 3) break; // Log first 3 blocks
                SLogger.log(this, "  Block: " + entry.getKey() + " = " +
                        entry.getValue().getBlock().getName().getString());
                logged++;
            }
            if (gridSpaceBlocks.size() > 3) {
                SLogger.log(this, "  ... and " + (gridSpaceBlocks.size() - 3) + " more blocks");
            }
        }
    }

    /**
     * LEGACY: Updates a grid's blocks using grid-local coordinates with logging.
     */
    public void updateGridBlocks(UUID gridId, Map<BlockPos, BlockState> blocks) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateBlocks(blocks);
        updateBlocksCount++;

        SLogger.log(this, "Updated grid-local blocks (legacy) for grid " + gridId +
                " - " + blocks.size() + " blocks (update #" + updateBlocksCount + ")");
    }

    /**
     * Updates a single block in a grid.
     */
    public void updateGridBlock(UUID gridId, BlockPos pos, BlockState state) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateBlock(pos, state);

        if (verbose) {
            SLogger.log(this, "Updated single block for grid " + gridId + " at " + pos +
                    " = " + (state != null ? state.getBlock().getName().getString() : "AIR"));
        }
    }

    /**
     * Removes a grid from the registry.
     */
    public void removeGrid(UUID gridId) {
        ClientLocalGrid removed = grids.remove(gridId);

        if (removed != null) {
            SLogger.log(this, "Removed grid " + gridId + " (remaining grids: " + grids.size() + ")");
        } else {
            SLogger.log(this, "Attempted to remove non-existent grid " + gridId);
        }
    }

    /**
     * ENHANCED: Renders all registered grids with comprehensive logging.
     */
    public void renderGrids(PoseStack matrices, MultiBufferSource vertexConsumers,
                            float partialTick, long currentWorldTick) {
        renderCallCount++;

        // Periodic debug summary
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDebugLogTime > DEBUG_LOG_INTERVAL) {
            logDebugSummary();
            lastDebugLogTime = currentTime;
        }

        if (grids.isEmpty()) {
            if (verbose && renderCallCount % 300 == 0) { // Every 15 seconds at 20 FPS
                SLogger.log(this, "RENDER: No grids to render (call #" + renderCallCount + ")");
            }
            return;
        }

        int renderedGrids = 0;
        int skippedGrids = 0;

        for (Map.Entry<UUID, ClientLocalGrid> entry : grids.entrySet()) {
            UUID gridId = entry.getKey();
            ClientLocalGrid grid = entry.getValue();

            if (grid != null) {
                try {
                    grid.render(matrices, vertexConsumers, partialTick, currentWorldTick);
                    renderedGrids++;
                } catch (Exception e) {
                    SLogger.log(this, "ERROR rendering grid " + gridId + ": " + e.getMessage());
                    e.printStackTrace();
                    skippedGrids++;
                }
            } else {
                SLogger.log(this, "WARNING: Null grid found for ID " + gridId);
                skippedGrids++;
            }
        }

        // Log render statistics periodically
        if (verbose && renderCallCount % 60 == 0) { // Every 3 seconds at 20 FPS
            SLogger.log(this, "RENDER STATS: Rendered " + renderedGrids + " grids, skipped " +
                    skippedGrids + " (call #" + renderCallCount + ")");
        }
    }

    /**
     * NEW: Logs comprehensive debug summary.
     */
    private void logDebugSummary() {
        SLogger.log(this, "=== CLIENT GRID MANAGER DEBUG SUMMARY ===");
        SLogger.log(this, "Total grids: " + grids.size());
        SLogger.log(this, "State updates: " + updateStateCount);
        SLogger.log(this, "Block updates: " + updateBlocksCount);
        SLogger.log(this, "Render calls: " + renderCallCount);

        if (!grids.isEmpty()) {
            SLogger.log(this, "Grid details:");
            for (Map.Entry<UUID, ClientLocalGrid> entry : grids.entrySet()) {
                UUID gridId = entry.getKey();
                ClientLocalGrid grid = entry.getValue();

                if (grid != null) {
                    SLogger.log(this, "  " + gridId + ":");
                    SLogger.log(this, "    Valid state: " + grid.hasValidState());
                    SLogger.log(this, "    GridSpace info: " + grid.hasGridSpaceInfo());
                    SLogger.log(this, "    GridSpace blocks: " + grid.getGridSpaceBlocks().size());
                    SLogger.log(this, "    Grid-local blocks: " + grid.getGridLocalBlocks().size());
                    SLogger.log(this, "    Position: " + grid.getPosition());
                    SLogger.log(this, "    Region ID: " + grid.getRegionId());
                    SLogger.log(this, "    Region origin: " + grid.getRegionOrigin());
                }
            }
        }
        SLogger.log(this, "=== END DEBUG SUMMARY ===");
    }

    /**
     * Gets all managed grids for debugging.
     */
    public Map<UUID, ClientLocalGrid> getAllGrids() {
        return grids;
    }

    /**
     * NEW: Gets debug statistics.
     */
    public String getDebugStats() {
        return String.format("Grids: %d, State updates: %d, Block updates: %d, Render calls: %d",
                grids.size(), updateStateCount, updateBlocksCount, renderCallCount);
    }

    /**
     * Performs client-side raycast against all managed grids.
     * This is an approximate raycast using client-side grid data.
     *
     * @param rayStart Starting point of the ray in world coordinates
     * @param rayEnd End point of the ray in world coordinates
     * @return Optional containing the closest grid hit, or empty if no hit
     */
    public Optional<ClientGridRaycastResult> raycastGrids(Vec3 rayStart, Vec3 rayEnd) {
        if (grids.isEmpty()) {
            if (verbose) {
                SLogger.log(this, "Client raycast: No grids to test");
            }
            return Optional.empty();
        }

        SLogger.log(this, "Client raycast: Testing " + grids.size() + " grids");

        ClientGridRaycastResult closestHit = null;
        double closestDistance = Double.MAX_VALUE;

        for (Map.Entry<UUID, ClientLocalGrid> entry : grids.entrySet()) {
            UUID gridId = entry.getKey();
            ClientLocalGrid grid = entry.getValue();

            if (grid == null || !grid.hasValidState()) {
                continue;
            }

            // Test raycast against this grid
            Optional<ClientGridRaycastResult> hit = raycastSingleGrid(grid, rayStart, rayEnd);

            if (hit.isPresent()) {
                double distance = hit.get().hitPoint.distanceTo(rayStart);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestHit = hit.get();
                }

                SLogger.log(this, "Client raycast HIT grid " + gridId + " at distance " + distance);
            }
        }

        if (closestHit != null) {
            SLogger.log(this, "Client raycast: Closest hit at distance " + closestDistance);
        } else {
            SLogger.log(this, "Client raycast: No hits detected");
        }

        return Optional.ofNullable(closestHit);
    }

    /**
     * Performs raycast against a single client grid.
     */
    private Optional<ClientGridRaycastResult> raycastSingleGrid(ClientLocalGrid grid, Vec3 rayStart, Vec3 rayEnd) {
        try {
            // Get grid's current world position and rotation
            Vector3f gridPos = grid.getPosition();
            Quat4f gridRot = grid.getRotation();

            if (gridPos == null || gridRot == null) {
                return Optional.empty();
            }

            // Convert to Vec3 for easier math
            Vec3 gridWorldPos = new Vec3(gridPos.x, gridPos.y, gridPos.z);

            // For now, do a simple bounding box test
            // TODO: This can be enhanced with proper rotation and per-block testing

            // Get grid's blocks to determine bounding box
            Map<BlockPos, BlockState> blocks = grid.getGridSpaceBlocks();
            if (blocks.isEmpty()) {
                blocks = grid.getGridLocalBlocks(); // Fallback to legacy blocks
            }

            if (blocks.isEmpty()) {
                return Optional.empty();
            }

            // Calculate grid bounding box
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (BlockPos blockPos : blocks.keySet()) {
                minX = Math.min(minX, blockPos.getX());
                minY = Math.min(minY, blockPos.getY());
                minZ = Math.min(minZ, blockPos.getZ());
                maxX = Math.max(maxX, blockPos.getX());
                maxY = Math.max(maxY, blockPos.getY());
                maxZ = Math.max(maxZ, blockPos.getZ());
            }

            // Transform bounding box to world coordinates
            // TODO: Apply proper rotation transformation
            Vec3 worldMin = gridWorldPos.add(minX, minY, minZ);
            Vec3 worldMax = gridWorldPos.add(maxX + 1, maxY + 1, maxZ + 1);

            // Simple ray-AABB intersection test
            Optional<Vec3> intersection = rayAABBIntersection(rayStart, rayEnd, worldMin, worldMax);

            if (intersection.isPresent()) {
                Vec3 hitPoint = intersection.get();

                // Convert hit point back to grid-local coordinates
                Vec3 localHitPoint = hitPoint.subtract(gridWorldPos);
                BlockPos hitBlockPos = BlockPos.containing(
                        Math.floor(localHitPoint.x),
                        Math.floor(localHitPoint.y),
                        Math.floor(localHitPoint.z)
                );

                // Check if there's actually a block at this position
                BlockState hitBlock = blocks.get(hitBlockPos);
                if (hitBlock != null && !hitBlock.isAir()) {
                    return Optional.of(new ClientGridRaycastResult(
                            grid,
                            hitPoint,
                            Vec3.ZERO, // TODO: Calculate proper normal
                            hitBlockPos,
                            hitBlock
                    ));
                }
            }

            return Optional.empty();

        } catch (Exception e) {
            SLogger.log(this, "Error in client grid raycast: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Simple ray-AABB intersection test.
     */
    private Optional<Vec3> rayAABBIntersection(Vec3 rayStart, Vec3 rayEnd, Vec3 aabbMin, Vec3 aabbMax) {
        Vec3 rayDir = rayEnd.subtract(rayStart);
        double rayLength = rayDir.length();
        rayDir = rayDir.normalize();

        // Simple slab method for ray-AABB intersection
        double tMin = 0.0;
        double tMax = rayLength;

        // Test X slab
        if (Math.abs(rayDir.x) < 1e-8) {
            if (rayStart.x < aabbMin.x || rayStart.x > aabbMax.x) return Optional.empty();
        } else {
            double t1 = (aabbMin.x - rayStart.x) / rayDir.x;
            double t2 = (aabbMax.x - rayStart.x) / rayDir.x;
            if (t1 > t2) { double temp = t1; t1 = t2; t2 = temp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Optional.empty();
        }

        // Test Y slab
        if (Math.abs(rayDir.y) < 1e-8) {
            if (rayStart.y < aabbMin.y || rayStart.y > aabbMax.y) return Optional.empty();
        } else {
            double t1 = (aabbMin.y - rayStart.y) / rayDir.y;
            double t2 = (aabbMax.y - rayStart.y) / rayDir.y;
            if (t1 > t2) { double temp = t1; t1 = t2; t2 = temp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Optional.empty();
        }

        // Test Z slab
        if (Math.abs(rayDir.z) < 1e-8) {
            if (rayStart.z < aabbMin.z || rayStart.z > aabbMax.z) return Optional.empty();
        } else {
            double t1 = (aabbMin.z - rayStart.z) / rayDir.z;
            double t2 = (aabbMax.z - rayStart.z) / rayDir.z;
            if (t1 > t2) { double temp = t1; t1 = t2; t2 = temp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Optional.empty();
        }

        // Calculate hit point
        Vec3 hitPoint = rayStart.add(rayDir.scale(tMin));
        return Optional.of(hitPoint);
    }

    /**
     * Result of a client-side grid raycast.
     */
    public static class ClientGridRaycastResult {
        public final ClientLocalGrid hitGrid;
        public final Vec3 hitPoint;
        public final Vec3 hitNormal;
        public final BlockPos hitBlockPos;
        public final BlockState hitBlockState;

        public ClientGridRaycastResult(ClientLocalGrid hitGrid, Vec3 hitPoint, Vec3 hitNormal,
                                       BlockPos hitBlockPos, BlockState hitBlockState) {
            this.hitGrid = hitGrid;
            this.hitPoint = hitPoint;
            this.hitNormal = hitNormal;
            this.hitBlockPos = hitBlockPos;
            this.hitBlockState = hitBlockState;
        }
    }

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}