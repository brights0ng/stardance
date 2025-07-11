package net.starlight.stardance.network;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.Map;
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
    public void renderGrids(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
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

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}