package net.stardance.core;

import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.stardance.core.component.GridBlockMerger;
import net.stardance.core.component.GridNetworkingComponent;
import net.stardance.core.component.GridPhysicsComponent;
import net.stardance.core.component.GridRenderComponent;
import net.stardance.physics.PhysicsEngine;
import net.stardance.physics.SubchunkCoordinates;
import net.stardance.physics.SubchunkManager;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static net.stardance.Stardance.engineManager;

/**
 * Represents a free-floating collection of blocks with physics properties.
 * This is the core class that coordinates the various components that manage
 * physics, networking, and rendering of a grid.
 */
public class LocalGrid implements ILoggingControl {
    // ----------------------------------------------
    // CORE PROPERTIES
    // ----------------------------------------------
    private final ServerWorld world;
    private final UUID gridId;
    private final PhysicsEngine engine;
    private final SubchunkManager subchunkManager;

    // Grid position and orientation
    private Vector3d origin;                  // Initial origin point
    private Quat4f rotation;                  // Initial rotation

    // ----------------------------------------------
    // BLOCK STORAGE
    // ----------------------------------------------
    private final ConcurrentMap<BlockPos, LocalBlock> blocks = new ConcurrentHashMap<>();

    // ----------------------------------------------
    // STATE FLAGS
    // ----------------------------------------------
    private boolean isDirty = true;           // Whether grid needs rebuild
    private boolean blocksDirty = false;      // Whether blocks have changed

    // ----------------------------------------------
    // COMPONENTS
    // ----------------------------------------------
    private final GridPhysicsComponent physicsComponent;
    private final GridNetworkingComponent networkingComponent;
    private final GridRenderComponent renderComponent;
    private final GridBlockMerger blockMerger;

    // ----------------------------------------------
    // SUBCHUNK MANAGEMENT
    // ----------------------------------------------
    private Set<SubchunkCoordinates> activeSubchunks = new HashSet<>();

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------
    /**
     * Creates a new LocalGrid at the specified position with the specified rotation.
     *
     * @param origin          Initial world position of grid origin
     * @param rotation        Initial rotation of the grid
     * @param world           Server world this grid belongs to
     * @param firstBlockState BlockState to use for the initial block
     */
    public LocalGrid(Vector3d origin, Quat4f rotation, ServerWorld world, BlockState firstBlockState) {
        this.origin = origin;
        this.rotation = rotation;
        this.world = world;
        this.gridId = UUID.randomUUID();

        // Get engine and subchunk manager
        this.engine = engineManager.getEngine(world);
        this.subchunkManager = engine.getSubchunkManager();

        // Initialize components
        this.blockMerger = new GridBlockMerger(this);
        this.physicsComponent = new GridPhysicsComponent(this, origin, rotation);
        this.networkingComponent = new GridNetworkingComponent(this);
        this.renderComponent = new GridRenderComponent(this);

        // Add to engine for management
        engine.addGrid(this);

        // Add the first block at origin (0,0,0)
        addBlock(new LocalBlock(new BlockPos(0, 0, 0), firstBlockState));
    }

    // ----------------------------------------------
    // PUBLIC API
    // ----------------------------------------------
    /**
     * Updates the grid's physics and network state.
     * Called each tick to sync transforms or rebuild if dirty.
     */
    public void tickUpdate() {
        if (physicsComponent.getRigidBody() == null) return;

        // CRITICAL FIX: Lock the physics operations to prevent concurrent updates
        synchronized(engineManager.getEngine(world).getPhysicsLock()) {
            // Update physics component only once per tick
            physicsComponent.updateTransforms();

            // Log the physics state - just once per tick
            Vector3f prevPos = new Vector3f();
            Vector3f currPos = new Vector3f();
            physicsComponent.getPreviousTransform(new Transform()).origin.get(prevPos);
            physicsComponent.getCurrentTransform(new Transform()).origin.get(currPos);
            SLogger.log(this, "Physics updated - prev: " + prevPos + ", current: " + currPos);

            // Store the current server tick to prevent double updates
            long currentServerTick = world.getTime();

            // Apply physics effects
            physicsComponent.applyVelocityDamping();

            // Rebuild if necessary
            if (isDirty) {
                rebuildPhysics();

                // After rebuilding, send full block data to clients
                net.stardance.network.GridNetwork.sendGridBlocks(this);
            }

            // Always send physics state update (position/rotation) every tick
            // This ensures smooth client-side interpolation
            net.stardance.network.GridNetwork.sendGridState(this);

            // Update which subchunks this grid overlaps
            updateActiveSubchunks();

            // Handle block updates
            if (blocksDirty) {
                // If blocks have changed, send a full block update
                net.stardance.network.GridNetwork.sendGridBlocks(this);
                blocksDirty = false;
            }
        }

        // Update render component with latest physics state - after completing physics update
        renderComponent.updateRenderState(physicsComponent.getRigidBody());
    }

    /**
     * Rebuilds physics properties after changes.
     */
    public void rebuildPhysics() {
        physicsComponent.rebuildPhysics(blocks, blockMerger);
        markClean();
    }

    /**
     * Adds a new block to the grid at the specified position.
     *
     * @param localBlock The block to add
     */
    public void addBlock(LocalBlock localBlock) {
        if (localBlock.getPosition() == null || localBlock.getState() == null) {
            SLogger.log(this, "Cannot add block: null position or state");
            return;
        }

        BlockPos pos = localBlock.getPosition();
        if (!blocks.containsKey(pos)) {
            // Mark as rebuilding to defer network updates
            physicsComponent.setRebuildInProgress(true);

            // Add block to collection
            blocks.put(pos, localBlock);
            markDirty();
            blocksDirty = true;
            networkingComponent.setPendingNetworkUpdate(true);
        }
    }

    /**
     * Removes a block from the grid.
     *
     * @param pos Position of the block to remove
     */
    public void removeBlock(BlockPos pos) {
        LocalBlock removed = blocks.remove(pos);
        if (removed != null) {
            markDirty();
            blocksDirty = true;
        }
    }

    /**
     * Gets the block state at the specified position.
     *
     * @param pos Position to check
     * @return The BlockState at the position, or null if no block exists
     */
    public BlockState getBlock(BlockPos pos) {
        LocalBlock lb = blocks.get(pos);
        return (lb != null) ? lb.getState() : null;
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
     * Imports multiple blocks at once.
     *
     * @param blockMap Map of positions to blocks to import
     */
    public void importBlocks(ConcurrentMap<BlockPos, LocalBlock> blockMap) {
        for (Map.Entry<BlockPos, LocalBlock> entry : blockMap.entrySet()) {
            addBlock(entry.getValue());
        }
    }

    /**
     * Gets the grid's axis-aligned bounding box in world space.
     *
     * @param minAabb Vector to store minimum point
     * @param maxAabb Vector to store maximum point
     */
    public void getAABB(Vector3f minAabb, Vector3f maxAabb) {
        physicsComponent.getAABB(minAabb, maxAabb);
    }

    /**
     * Updates which subchunks this grid overlaps.
     */
    private void updateActiveSubchunks() {
        Set<SubchunkCoordinates> newSubchunks = physicsComponent.calculateOccupiedSubchunks();

        // Activate new subchunks
        for (SubchunkCoordinates coords : newSubchunks) {
            if (!activeSubchunks.contains(coords)) {
                subchunkManager.activateSubchunk(coords);
            }
        }

        // Deactivate old subchunks
        for (SubchunkCoordinates coords : activeSubchunks) {
            if (!newSubchunks.contains(coords)) {
                subchunkManager.deactivateSubchunk(coords);
            }
        }

        activeSubchunks = newSubchunks;
    }

    // ----------------------------------------------
    // GETTERS / SETTERS
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
     * Gets the origin point of this grid.
     */
    public Vector3d getOrigin() {
        return origin;
    }

    /**
     * Sets the origin point of this grid.
     */
    public void setOrigin(Vector3d origin) {
        this.origin = origin;
    }

    /**
     * Gets the rotation quaternion for this grid.
     */
    public Quat4f getRotation() {
        return rotation;
    }

    /**
     * Checks if the grid is dirty and needs rebuilding.
     */
    public boolean isDirty() {
        return isDirty;
    }

    /**
     * Marks the grid as needing a rebuild.
     */
    public void markDirty() {
        isDirty = true;
    }

    /**
     * Marks the grid as clean after a rebuild.
     */
    public void markClean() {
        isDirty = false;
    }

    /**
     * Gets all blocks in this grid.
     */
    public Map<BlockPos, LocalBlock> getBlocks() {
        return blocks;
    }

    /**
     * Gets the physics component.
     */
    public GridPhysicsComponent getPhysicsComponent() {
        return physicsComponent;
    }

    /**
     * Gets the rendering component.
     */
    public GridRenderComponent getRenderComponent() {
        return renderComponent;
    }

    /**
     * Gets the networking component.
     */
    public GridNetworkingComponent getNetworkingComponent() {
        return networkingComponent;
    }

    /**
     * Gets the server world this grid belongs to.
     */
    public ServerWorld getWorld() {
        return world;
    }

    /**
     * Gets the physics engine this grid is managed by.
     */
    public PhysicsEngine getEngine() {
        return engine;
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
     * Gets the origin as a Vector3f.
     */
    public Vector3f originFloat() {
        return new Vector3f((float) origin.x, (float) origin.y, (float) origin.z);
    }
}