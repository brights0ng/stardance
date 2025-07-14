package net.starlight.stardance.core;

import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.gridspace.GridSpaceRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.gridspace.GridSpaceBlockManager;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Represents a free-floating collection of blocks with physics properties.
 * This is the main API entry point for the Stardance physics grid system.
 * All access to physics grids should be done through this class.
 *
 * Now integrated with GridSpace system for proper block-world interaction handling.
 */
public class LocalGrid implements ILoggingControl {
    // ----------------------------------------------
    // GRIDSPACE CONSTANTS
    // ----------------------------------------------

    private long lastProcessedTick;

    /** Offset to center grid-local coordinates within the GridSpace region */
    public static final int GRIDSPACE_CENTER_OFFSET = 512; // Half of 1024x1024x1024 region size

    // ----------------------------------------------
    // CORE PROPERTIES
    // ----------------------------------------------
    private final ServerLevel world;
    private final UUID gridId;
    private final PhysicsEngine engine;

    // Grid position and orientation
    private Vector3d origin;                  // Initial origin point
    private Quat4f rotation;                  // Initial rotation

    // ----------------------------------------------
    // BLOCK STORAGE (HYBRID APPROACH)
    // ----------------------------------------------

    /**
     * Local block storage for fast physics access and iteration.
     * This is the "source of truth" for physics calculations.
     */
    private final ConcurrentMap<BlockPos, LocalBlock> blocks = new ConcurrentHashMap<>();

    // ----------------------------------------------
    // STATE FLAGS
    // ----------------------------------------------
    private boolean isDirty = true;           // Whether grid needs rebuild
    private boolean blocksDirty = false;      // Whether blocks have changed
    private volatile boolean renderDataInvalidated = false;
    private volatile boolean isDestroyed = false; // Whether this grid has been destroyed

    // ----------------------------------------------
    // COMPONENTS
    // ----------------------------------------------
    private final GridPhysicsComponent physicsComponent;
    private final GridNetworkingComponent networkingComponent;
    private final GridRenderComponent renderComponent;
    private final GridBlockMerger blockMerger;

    // GridSpace integration components
    private final GridSpaceManager gridSpaceManager;
    private final GridSpaceRegion gridSpaceRegion;
    private final GridSpaceBlockManager gridSpaceBlockManager;

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false; // Enable for GridSpace integration logging
    }

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------
    /**
     * Creates a new LocalGrid at the specified position with the specified rotation.
     * Now with full GridSpace integration for proper block-world interaction.
     *
     * @param origin          Initial world position of grid origin
     * @param rotation        Initial rotation of the grid
     * @param world           Server world this grid belongs to
     * @param firstBlockState BlockState to use for the initial block
     * @throws IllegalStateException if GridSpace allocation fails
     */
    public LocalGrid(Vector3d origin, Quat4f rotation, ServerLevel world, BlockState firstBlockState) {
        this.origin = origin;
        this.rotation = rotation;
        this.world = world;
        this.gridId = UUID.randomUUID();

        // Get engine and GridSpace manager
        this.engine = engineManager.getEngine(world);
        this.gridSpaceManager = engineManager.getGridSpaceManager(world);

        if (engine == null) {
            throw new IllegalStateException("No PhysicsEngine found for world: " + world.dimension().location());
        }

        if (gridSpaceManager == null) {
            throw new IllegalStateException("No GridSpaceManager found for world: " + world.dimension().location());
        }

        // Allocate GridSpace region
        try {
            this.gridSpaceRegion = gridSpaceManager.allocateRegion(gridId);
            this.gridSpaceBlockManager = new GridSpaceBlockManager(gridSpaceRegion);

            SLogger.log(this, "Successfully allocated GridSpace region " + gridSpaceRegion.getRegionId() +
                    " for LocalGrid " + gridId);

        } catch (Exception e) {
            SLogger.log(this, "Failed to allocate GridSpace region for LocalGrid " + gridId + ": " + e.getMessage());
            throw new IllegalStateException("GridSpace allocation failed", e);
        }

        // Initialize components (existing ones unchanged)
        this.blockMerger = new GridBlockMerger(this);
        this.physicsComponent = new GridPhysicsComponent(this, origin, rotation);
        this.networkingComponent = new GridNetworkingComponent(this);
        this.renderComponent = new GridRenderComponent(this);

        // Add to engine for management
        engine.addGrid(this);

        // Add the first block at origin (0,0,0) - this will now go to both local storage and GridSpace
        addBlock(new LocalBlock(new BlockPos(0, 0, 0), firstBlockState));

        SLogger.log(this, "LocalGrid " + gridId + " created successfully with GridSpace integration");

        // After grid creation, force it to sleep
        getRigidBody().setLinearVelocity(new Vector3f(0, 0, 0));
        getRigidBody().setAngularVelocity(new Vector3f(0, 0, 0));
        getRigidBody().setActivationState(com.bulletphysics.collision.dispatch.CollisionObject.ISLAND_SLEEPING);
    }

    // ----------------------------------------------
    // COORDINATE TRANSFORMATION METHODS
    // ----------------------------------------------

    /**
     * Converts grid-local coordinates to GridSpace coordinates.
     * Grid-local (0,0,0) maps to the center of the GridSpace region.
     *
     * @param gridLocalPos Position relative to grid origin
     * @return Position in GridSpace coordinates
     */
    public BlockPos gridLocalToGridSpace(BlockPos gridLocalPos) {
        if (isDestroyed) {
            throw new IllegalStateException("Cannot use destroyed LocalGrid");
        }

        // Apply center offset: grid-local (0,0,0) = center of GridSpace region
        BlockPos offsetPos = gridLocalPos.offset(GRIDSPACE_CENTER_OFFSET, GRIDSPACE_CENTER_OFFSET, GRIDSPACE_CENTER_OFFSET);
        return gridSpaceRegion.gridLocalToGridSpace(offsetPos);
    }

    /**
     * Converts GridSpace coordinates to grid-local coordinates.
     *
     * @param gridSpacePos Position in GridSpace coordinates
     * @return Position relative to grid origin
     */
    public BlockPos gridSpaceToGridLocal(BlockPos gridSpacePos) {
        if (isDestroyed) {
            throw new IllegalStateException("Cannot use destroyed LocalGrid");
        }

        BlockPos regionLocalPos = gridSpaceRegion.gridSpaceToGridLocal(gridSpacePos);
        // Remove center offset to get back to grid-local coordinates
        return regionLocalPos.offset(-GRIDSPACE_CENTER_OFFSET, -GRIDSPACE_CENTER_OFFSET, -GRIDSPACE_CENTER_OFFSET);
    }

    /**
     * Checks if a grid-local position is within the valid bounds of this grid's region.
     *
     * @param gridLocalPos Position to check
     * @return true if position is within valid bounds
     */
    public boolean isValidGridLocalPosition(BlockPos gridLocalPos) {
        if (isDestroyed) {
            return false;
        }

        // Apply center offset and check if it fits within the region
        BlockPos offsetPos = gridLocalPos.offset(GRIDSPACE_CENTER_OFFSET, GRIDSPACE_CENTER_OFFSET, GRIDSPACE_CENTER_OFFSET);
        return gridSpaceRegion.containsGridLocalPosition(offsetPos);
    }

    // ----------------------------------------------
    // CORE API METHODS (UPDATED FOR GRIDSPACE)
    // ----------------------------------------------

    // Add this debugging code to your LocalGrid.java class

    // Add these debug fields to LocalGrid
    private static final Map<UUID, TickDebugInfo> debugTracker = new ConcurrentHashMap<>();

    public boolean hasBlock(BlockPos gridLocalPos) {
        return blocks.containsKey(gridLocalPos);
    }

    private static class TickDebugInfo {
        long lastTickUpdateCall = -1;
        long lastNetworkUpdateCall = -1;
        int tickUpdateCallsThisTick = 0;
        int networkUpdateCallsThisTick = 0;
        long currentTick = -1;
    }

    /**
     * DEBUGGING VERSION: Enhanced tickUpdate with comprehensive call tracking.
     */
    public void tickUpdate() {
        if (isDestroyed || physicsComponent.getRigidBody() == null) return;

        long currentServerTick = world.getGameTime();

        // DEBUG: Track tickUpdate calls
        TickDebugInfo debug = debugTracker.computeIfAbsent(gridId, k -> new TickDebugInfo());

        if (debug.currentTick != currentServerTick) {
            // New tick started
            if (debug.tickUpdateCallsThisTick > 1 || debug.networkUpdateCallsThisTick > 1) {
                SLogger.log(this, "PREV TICK SUMMARY: Grid " + gridId + " tick " + debug.currentTick +
                        " - tickUpdate calls: " + debug.tickUpdateCallsThisTick +
                        ", network calls: " + debug.networkUpdateCallsThisTick);
            }
            debug.currentTick = currentServerTick;
            debug.tickUpdateCallsThisTick = 0;
            debug.networkUpdateCallsThisTick = 0;
        }

        debug.tickUpdateCallsThisTick++;
        debug.lastTickUpdateCall = currentServerTick;

        // Log excessive calls
        if (debug.tickUpdateCallsThisTick > 1) {
            SLogger.log(this, "WARNING: Multiple tickUpdate calls for grid " + gridId +
                    " on tick " + currentServerTick + " (call #" + debug.tickUpdateCallsThisTick + ")");
        }

        // CRITICAL FIX: Only process full updates once per server tick
        boolean isNewTick = (currentServerTick != lastProcessedTick);

        // CRITICAL FIX: Lock the physics operations to prevent concurrent updates
        synchronized(engine.getPhysicsLock()) {
            // Update physics component only once per tick
            physicsComponent.updateTransforms();

            // Apply physics effects
            physicsComponent.applyVelocityDamping();

            // Only do expensive operations once per server tick
            if (isNewTick) {
                // Tick block entities
                tickBlockEntities();

                // Rebuild if necessary
                if (isDirty) {
                    rebuildPhysics();

                    // FIXED: Use networking component instead of direct calls
                    networkingComponent.setPendingNetworkUpdate(true);
                    networkingComponent.setRebuildInProgress(false); // Rebuild is complete

                    SLogger.log(this, "Physics rebuilt for grid " + gridId + ", marked for network update");
                }

                // DEBUG: Track network update calls
                debug.networkUpdateCallsThisTick++;

                // FIXED: Handle network updates only once per server tick
                networkingComponent.handleNetworkUpdates();

                // Update which subchunks this grid overlaps
                physicsComponent.updateActiveSubchunks();

                // Handle block updates
                if (blocksDirty) {
                    // FIXED: Use networking component instead of direct call
                    networkingComponent.setPendingNetworkUpdate(true);
                    blocksDirty = false;

                    SLogger.log(this, "Blocks dirty for grid " + gridId + ", marked for network update");
                }

                // Update the last processed tick
                lastProcessedTick = currentServerTick;

                // Debug: Log tick processing occasionally
                if (currentServerTick % 60 == 0) { // Every 3 seconds
                    SLogger.log(this, "Processed tick " + currentServerTick + " for grid " + gridId);
                }
            } else {
                SLogger.log(this, "SKIPPED expensive operations for grid " + gridId +
                        " - tick " + currentServerTick + " already processed");
            }
        }

        // Update render component with latest physics state - after completing physics update
        renderComponent.updateRenderState(physicsComponent.getRigidBody());

        if (Math.random() < 0.01) { // 1% chance per tick
            Vector3f pos = new Vector3f();
            Vector3f vel = new Vector3f();
            getRigidBody().getCenterOfMassPosition(pos);
            getRigidBody().getLinearVelocity(vel);

            SLogger.log(this, String.format("Physics: pos=(%.6f,%.6f,%.6f) vel=(%.6f,%.6f,%.6f)",
                    pos.x, pos.y, pos.z, vel.x, vel.y, vel.z));
        }
    }

    /**
     * Rebuilds physics properties after changes.
     */
    public void rebuildPhysics() {
        if (isDestroyed) return;

        physicsComponent.rebuildPhysics(blocks, blockMerger);

        // PERFORMANCE: Flag that cached render data is now invalid
        renderDataInvalidated = true;

        markClean();
    }

    /**
     * Adds a new block to the grid at the specified position.
     * Now stores blocks in both local storage AND GridSpace.
     *
     * @param localBlock The block to add
     * @return
     */
    public boolean addBlock(LocalBlock localBlock) {
        if (isDestroyed) {
            SLogger.log(this, "Cannot add block to destroyed grid");
            return false;
        }

        if (localBlock.getPosition() == null || localBlock.getState() == null) {
            SLogger.log(this, "Cannot add block: null position or state");
            return false;
        }

        BlockPos pos = localBlock.getPosition();

        // Validate position is within bounds
        if (!isValidGridLocalPosition(pos)) {
            SLogger.log(this, "Cannot add block at " + pos + " - outside valid grid bounds");
            return false;
        }

        if (!blocks.containsKey(pos)) {
            // Mark as rebuilding to defer network updates
            physicsComponent.setRebuildInProgress(true);

            // Add to local storage (for physics)
            blocks.put(pos, localBlock);

            // Add to GridSpace (for world interaction)
            boolean gridSpaceSuccess = gridSpaceBlockManager.placeBlock(pos, localBlock.getState());
            if (!gridSpaceSuccess) {
                // Rollback local storage if GridSpace failed
                blocks.remove(pos);
                SLogger.log(this, "Failed to place block in GridSpace, rolling back local placement");
                return gridSpaceSuccess;
            }

            // Mark grid as needing updates
            markDirty();
            blocksDirty = true;
            networkingComponent.setPendingNetworkUpdate(true);

            SLogger.log(this, "Added block " + localBlock.getState().getBlock().getName().getString() +
                    " at grid-local " + pos + " (GridSpace: " + gridLocalToGridSpace(pos) + ")");
            return true;
        }
        return false;
    }

    /**
     * Removes a block from the grid.
     * Now removes from both local storage AND GridSpace.
     *
     * @param pos Position of the block to remove
     * @return
     */
    public boolean removeBlock(BlockPos pos) {
        if (isDestroyed) {
            return false;
        }

        LocalBlock removed = blocks.remove(pos);
        if (removed != null) {
            // Remove from GridSpace as well
            boolean gridSpaceSuccess = gridSpaceBlockManager.removeBlock(pos);
            if (!gridSpaceSuccess) {
                // Rollback local removal if GridSpace failed
                blocks.put(pos, removed);
                SLogger.log(this, "Failed to remove block from GridSpace, rolling back local removal");
                return gridSpaceSuccess;
            }

            markDirty();
            blocksDirty = true;

            SLogger.log(this, "Removed block at grid-local " + pos + " (GridSpace: " + gridLocalToGridSpace(pos) + ")");
            return true;
        }
        return false;
    }

    /**
     * Gets the block state at the specified position.
     * Checks local storage first for performance.
     *
     * @param pos Position to check
     * @return The BlockState at the position, or null if no block exists
     */
    public BlockState getBlock(BlockPos pos) {
        if (isDestroyed) {
            return null;
        }

        LocalBlock lb = blocks.get(pos);
        return (lb != null) ? lb.getState() : null;
    }

    /**
     * Imports multiple blocks at once.
     * Optimized for bulk operations.
     *
     * @param blockMap Map of positions to blocks to import
     */
    public void importBlocks(ConcurrentMap<BlockPos, LocalBlock> blockMap) {
        if (isDestroyed) {
            SLogger.log(this, "Cannot import blocks to destroyed grid");
            return;
        }

        // Prepare batch operation for GridSpace
        Map<BlockPos, BlockState> gridSpaceBlocks = new HashMap<>();

        // Validate all positions first
        for (Map.Entry<BlockPos, LocalBlock> entry : blockMap.entrySet()) {
            BlockPos pos = entry.getKey();
            LocalBlock block = entry.getValue();

            if (block.getPosition() == null || block.getState() == null) {
                SLogger.log(this, "Skipping invalid block during import: null position or state");
                continue;
            }

            if (!isValidGridLocalPosition(pos)) {
                SLogger.log(this, "Skipping block at " + pos + " during import: outside valid bounds");
                continue;
            }

            if (!blocks.containsKey(pos)) {
                gridSpaceBlocks.put(pos, block.getState());
            }
        }

        // Batch place in GridSpace
        int placedCount = gridSpaceBlockManager.placeBlocks(gridSpaceBlocks);

        // Add successfully placed blocks to local storage
        int localCount = 0;
        for (Map.Entry<BlockPos, LocalBlock> entry : blockMap.entrySet()) {
            BlockPos pos = entry.getKey();
            LocalBlock block = entry.getValue();

            if (gridSpaceBlocks.containsKey(pos) && !blocks.containsKey(pos)) {
                blocks.put(pos, block);
                localCount++;
            }
        }

        if (localCount > 0) {
            markDirty();
            blocksDirty = true;
            networkingComponent.setPendingNetworkUpdate(true);
        }

        SLogger.log(this, "Imported " + localCount + "/" + blockMap.size() + " blocks (" +
                placedCount + " placed in GridSpace)");
    }

    /**
     * Converts a point from world space to grid-local space.
     *
     * @param worldPoint Point in world coordinates
     * @return Point in grid-local coordinates
     */
    public Vector3d worldToGridLocal(Vector3d worldPoint) {
        return physicsComponent.worldToGridLocal(worldPoint);
    }

    /**
     * Converts a point from grid-local space to world space.
     * This is the reverse of worldToGridLocal().
     *
     * @param gridLocalPoint Point in grid-local coordinates
     * @return Point in world coordinates
     */
    public Vec3 gridLocalToWorld(javax.vecmath.Vector3d gridLocalPoint) {
        return physicsComponent.gridLocalToWorld(gridLocalPoint);
    }

    /**
     * Convenience method for Vec3d input.
     */
    public Vec3 gridLocalToWorld(Vec3 gridLocalPoint) {
        javax.vecmath.Vector3d point = new javax.vecmath.Vector3d(
                gridLocalPoint.x, gridLocalPoint.y, gridLocalPoint.z
        );
        return gridLocalToWorld(point);
    }

    /**
     * Applies an impulse to the grid's center of mass.
     *
     * @param impulse Impulse vector to apply
     */
    public void applyImpulse(Vector3f impulse) {
        if (isDestroyed) return;

        RigidBody body = getRigidBody();
        if (body != null) {
            body.applyCentralImpulse(impulse);
            body.activate(true);
        }
    }

    /**
     * Applies torque to the grid.
     *
     * @param torque Torque vector to apply
     */
    public void applyTorque(Vector3f torque) {
        if (isDestroyed) return;

        RigidBody body = getRigidBody();
        if (body != null) {
            body.applyTorque(torque);
            body.activate(true);
        }
    }

    /**
     * Check if a world position is within this grid's physics bounds.
     * Used for raycast hit detection.
     */
    public boolean containsWorldPosition(Vec3 worldPos) {
        try {
            RigidBody rigidBody = getPhysicsComponent().getRigidBody();

            // Get the rigid body transform
            if (rigidBody == null) {
                return false;
            }

            // Get the current transform of the rigid body
            Transform bodyTransform = new Transform();
            rigidBody.getWorldTransform(bodyTransform);

            // Transform world position to grid-local coordinates
            Vector3f worldPosBullet = new Vector3f((float)worldPos.x, (float)worldPos.y, (float)worldPos.z);

            // Inverse transform: world -> local
            Transform inverseTransform = new Transform();
            bodyTransform.inverse(inverseTransform);

            // Apply the inverse transform (JBullet modifies the vector in-place)
            Vector3f localPos = new Vector3f(worldPosBullet);
            inverseTransform.transform(localPos);

            // Check if local position is within our block bounds
            return isWithinGridBounds(localPos);

        } catch (Exception e) {
            SLogger.log("LocalGrid", "Error checking world position containment: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if local coordinates are within the grid's block bounds.
     */
    private boolean isWithinGridBounds(Vector3f localPos) {
        // Get the bounding box of all blocks in this grid
        if (blocks.isEmpty()) {
            return false;
        }

        // Calculate the min/max bounds of all blocks
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (LocalBlock block : blocks.values()) {
            BlockPos pos = block.getPosition();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        // Add some padding for collision detection
        float padding = 0.1f;

        return localPos.x >= (minX - padding) && localPos.x <= (maxX + 1 + padding) &&
                localPos.y >= (minY - padding) && localPos.y <= (maxY + 1 + padding) &&
                localPos.z >= (minZ - padding) && localPos.z <= (maxZ + 1 + padding);
    }

    // ----------------------------------------------
    // CLEANUP AND DESTRUCTION
    // ----------------------------------------------

    /**
     * Destroys this LocalGrid and cleans up all associated resources.
     * This includes deallocating the GridSpace region and clearing all blocks.
     */
    public void destroy() {
        if (isDestroyed) {
            return;
        }

        SLogger.log(this, "Destroying LocalGrid " + gridId + " and cleaning up GridSpace region " +
                gridSpaceRegion.getRegionId());

        isDestroyed = true;

        // Remove from physics engine
        if (engine != null) {
            engine.removeGrid(this);
        }

        // Clean up GridSpace blocks and region
        if (gridSpaceBlockManager != null) {
            gridSpaceBlockManager.shutdown();
        }

        if (gridSpaceManager != null) {
            gridSpaceManager.deallocateRegion(gridId);
        }

        // Clear local storage
        blocks.clear();

        SLogger.log(this, "LocalGrid " + gridId + " destroyed successfully");
    }

    // ----------------------------------------------
    // EXISTING GETTER METHODS (UNCHANGED)
    // ----------------------------------------------

    /**
     * Gets the unique identifier for this grid.
     */
    public UUID getGridId() {
        return gridId;
    }

    /**
     * Gets the rigid body for physics simulation.
     */
    public RigidBody getRigidBody() {
        return physicsComponent.getRigidBody();
    }

    /**
     * Marks the grid as needing a physics rebuild.
     */
    public void markDirty() {
        if (!isDestroyed) {
            isDirty = true;
        }
    }

    /**
     * Marks the grid as clean after a rebuild.
     */
    public void markClean() {
        isDirty = false;
    }

    /**
     * Gets all blocks in this grid (local storage).
     */
    public Map<BlockPos, LocalBlock> getBlocks() {
        return blocks;
    }

    /**
     * Gets the center of mass.
     */
    public Vector3f getCentroid() {
        return physicsComponent.getCentroid();
    }

    /**
     * Gets the current transform.
     */
    public void getCurrentTransform(Transform outTransform) {
        physicsComponent.getCurrentTransform(outTransform);
    }

    /**
     * Gets the previous transform.
     */
    public void getPreviousTransform(Transform outTransform) {
        physicsComponent.getPreviousTransform(outTransform);
    }

    /**
     * Gets the server world this grid belongs to.
     */
    public ServerLevel getWorld() {
        return world;
    }

    /**
     * Gets the physics engine this grid is managed by.
     */
    public PhysicsEngine getEngine() {
        return engine;
    }

    /**
     * Gets the origin as a Vector3f.
     */
    public Vector3f originFloat() {
        return new Vector3f((float) origin.x, (float) origin.y, (float) origin.z);
    }

    /**
     * Gets the GridSpace region allocated to this grid.
     */
    public GridSpaceRegion getGridSpaceRegion() {
        return gridSpaceRegion;
    }

    /**
     * Gets the GridSpace block manager for this grid.
     */
    public GridSpaceBlockManager getGridSpaceBlockManager() {
        return gridSpaceBlockManager;
    }

    /**
     * Checks if this grid has been destroyed.
     */
    public boolean isDestroyed() {
        return isDestroyed;
    }

    // ----------------------------------------------
    // PACKAGE-PRIVATE METHODS FOR COMPONENTS (UNCHANGED)
    // ----------------------------------------------

    /**
     * For internal use by components. Gets the physics component.
     */
    GridPhysicsComponent getPhysicsComponent() {
        return physicsComponent;
    }

    /**
     * For internal use by components. Gets the rendering component.
     */
    GridRenderComponent getRenderComponent() {
        return renderComponent;
    }

    /**
     * For internal use by components. Gets the networking component.
     */
    GridNetworkingComponent getNetworkingComponent() {
        return networkingComponent;
    }

    /**
     * For internal use by components. Gets the block merger component.
     */
    GridBlockMerger getBlockMerger() {
        return blockMerger;
    }

    public boolean isRenderDataInvalidated() {
        return renderDataInvalidated;
    }

    public void clearRenderDataInvalidated() {
        renderDataInvalidated = false;
    }

    public Quat4f getRotation(){
        return rotation;
    }

    // ----------------------------------------------
    // BLOCK ENTITY HANDLING (PLACEHOLDER FOR FUTURE)
    // ----------------------------------------------

    /**
     * Ticks all block entities within this grid.
     * TODO: Update this method to work with GridSpace block entities.
     */
    private void tickBlockEntities() {
        // Existing implementation - will need updates for GridSpace integration
        // For now, keeping it as-is to maintain compatibility

        // Future: iterate through GridSpace positions and tick block entities there
    }

    /**
     * Gets the axis-aligned bounding box for this grid.
     */
    public void getAABB(Vector3f minAabb, Vector3f maxAabb) {
        physicsComponent.getAABB(minAabb, maxAabb);
    }

    public Vector3f getVelocityAtPoint(Vector3f entityPosVector) {
        return getRigidBody().getVelocityInLocalPoint(entityPosVector, new Vector3f());
    }
}