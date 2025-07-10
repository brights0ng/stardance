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
 * UPDATED: GridSpace-aware client grid manager.
 * Now handles both grid-local blocks (legacy) and GridSpace blocks (new system).
 */
public class ClientGridManager implements ILoggingControl {
    // Singleton instance
    private static ClientGridManager INSTANCE;

    // Map of grid ID to client grid
    private final Map<UUID, ClientLocalGrid> grids = new ConcurrentHashMap<>();

    // Debug
    private boolean verbose = true; // Enable for GridSpace debugging

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
        // Private constructor for singleton
    }

    /**
     * Gets or creates a client grid with the given ID.
     */
    public ClientLocalGrid getOrCreateGrid(UUID gridId) {
        return grids.computeIfAbsent(gridId, id -> {
            ClientLocalGrid grid = new ClientLocalGrid(id);
            SLogger.log(this, "Created new client grid: " + id);
            return grid;
        });
    }

    /**
     * Updates a grid's state (unchanged).
     */
    public void updateGridState(UUID gridId, Vector3f position, Quat4f rotation, Vector3f centroid, long serverTick) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateState(position, rotation, centroid, serverTick);

        if (verbose) {
            SLogger.log(this, "Updated grid state: " + gridId + ", serverTick=" + serverTick + ", pos=" + position);
        }
    }

    /**
     * NEW: Updates GridSpace information for a grid.
     */
    public void updateGridSpaceInfo(UUID gridId, int regionId, BlockPos regionOrigin) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateGridSpaceInfo(regionId, regionOrigin);

        if (verbose) {
            SLogger.log(this, "Updated GridSpace info for grid: " + gridId +
                    ", regionId=" + regionId + ", origin=" + regionOrigin);
        }
    }

    /**
     * NEW: Updates a grid's blocks using GridSpace coordinates.
     * This is the new method that should be used for GridSpace-based networking.
     */
    public void updateGridSpaceBlocks(UUID gridId, Map<BlockPos, BlockState> gridSpaceBlocks) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateGridSpaceBlocks(gridSpaceBlocks);

        if (verbose) {
            SLogger.log(this, "Updated GridSpace blocks: " + gridId + ", count=" + gridSpaceBlocks.size());
        }
    }

    /**
     * LEGACY: Updates a grid's blocks using grid-local coordinates.
     * This method is kept for backwards compatibility.
     */
    public void updateGridBlocks(UUID gridId, Map<BlockPos, BlockState> blocks) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateBlocks(blocks);

        if (verbose) {
            SLogger.log(this, "Updated grid-local blocks (legacy): " + gridId + ", count=" + blocks.size());
        }
    }

    /**
     * Updates a single block in a grid (unchanged).
     */
    public void updateGridBlock(UUID gridId, BlockPos pos, BlockState state) {
        ClientLocalGrid grid = getOrCreateGrid(gridId);
        grid.updateBlock(pos, state);
    }

    /**
     * Removes a grid from the registry (unchanged).
     */
    public void removeGrid(UUID gridId) {
        grids.remove(gridId);

        if (verbose) {
            SLogger.log(this, "Removed grid: " + gridId);
        }
    }

    /**
     * Renders all registered grids (unchanged - but now they'll use GridSpace data).
     */
    public void renderGrids(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                            float partialTick, long currentWorldTick) {
        for (ClientLocalGrid grid : grids.values()) {
            if (grid != null) {
                grid.render(matrices, vertexConsumers, partialTick, currentWorldTick);
            }
        }
    }

    /**
     * Gets all managed grids for debugging.
     */
    public Map<UUID, ClientLocalGrid> getAllGrids() {
        return grids;
    }

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return true; }
}