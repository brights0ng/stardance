package net.starlight.stardance.core;

import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.interaction.GridFurnaceBlockEntity;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.network.GridNetwork;

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
 */
public class LocalGrid implements ILoggingControl {
    // ----------------------------------------------
    // CORE PROPERTIES
    // ----------------------------------------------
    private final ServerWorld world;
    private final UUID gridId;
    private final PhysicsEngine engine;

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
    private volatile boolean renderDataInvalidated = false;

    // ----------------------------------------------
    // COMPONENTS
    // ----------------------------------------------
    private final GridPhysicsComponent physicsComponent;
    private final GridNetworkingComponent networkingComponent;
    private final GridRenderComponent renderComponent;
    private final GridBlockMerger blockMerger;

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

        // Get engine
        this.engine = engineManager.getEngine(world);

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
    // CORE API METHODS
    // ----------------------------------------------
    /**
     * Updates the grid's physics and network state.
     * Called each tick to sync transforms or rebuild if dirty.
     */
    public void tickUpdate() {
        if (physicsComponent.getRigidBody() == null) return;

        // CRITICAL FIX: Lock the physics operations to prevent concurrent updates
        synchronized(engine.getPhysicsLock()) {
            // Update physics component only once per tick
            physicsComponent.updateTransforms();

            // Apply physics effects
            physicsComponent.applyVelocityDamping();

            // Tick block entities
            tickBlockEntities();

            // Rebuild if necessary
            if (isDirty) {
                rebuildPhysics();

                // After rebuilding, send full block data to clients
                GridNetwork.sendGridBlocks(this);
            }

            // Always send physics state update (position/rotation) every tick
            // This ensures smooth client-side interpolation
            GridNetwork.sendGridState(this);

            // Update which subchunks this grid overlaps
            physicsComponent.updateActiveSubchunks();

            // Handle block updates
            if (blocksDirty) {
                // If blocks have changed, send a full block update
                GridNetwork.sendGridBlocks(this);
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

        // PERFORMANCE: Flag that cached render data is now invalid
        renderDataInvalidated = true;

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
     * Converts a point from world space to grid-local space.
     *
     * @param worldPoint Point in world coordinates
     * @return Point in grid-local coordinates
     */
    public Vector3d worldToGridLocal(Vector3d worldPoint) {
        return physicsComponent.worldToGridLocal(worldPoint);
    }

    /**
     * Applies an impulse to the grid's center of mass.
     *
     * @param impulse Impulse vector to apply
     */
    public void applyImpulse(Vector3f impulse) {
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
        RigidBody body = getRigidBody();
        if (body != null) {
            body.applyTorque(torque);
            body.activate(true);
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
     * Gets the velocity at a specific point in the grid (in world space).
     *
     * @param worldPoint Point to check velocity at (in world coordinates)
     * @return Velocity vector at that point
     */
    public Vector3f getVelocityAtPoint(Vector3f worldPoint) {
        RigidBody body = getRigidBody();
        if (body == null) {
            return new Vector3f(0, 0, 0);
        }

        // Get grid's current velocity
        Vector3f linearVel = new Vector3f();
        Vector3f angularVel = new Vector3f();
        body.getLinearVelocity(linearVel);
        body.getAngularVelocity(angularVel);

        // Get grid center
        Vector3f gridCenter = new Vector3f();
        Transform gridTransform = new Transform();
        body.getWorldTransform(gridTransform);
        gridTransform.origin.get(gridCenter);

        // Calculate relative position
        Vector3f relativePos = new Vector3f();
        relativePos.sub(worldPoint, gridCenter);

        // Calculate velocity at point (v = linear + angular × r)
        Vector3f velocityAtPoint = new Vector3f(linearVel);
        Vector3f angularComponent = new Vector3f();
        angularComponent.cross(angularVel, relativePos);
        velocityAtPoint.add(angularComponent);

        return velocityAtPoint;
    }


    /**
     * Ticks all block entities in this grid.
     */
    private void tickBlockEntities() {
        for (Map.Entry<BlockPos, LocalBlock> entry : blocks.entrySet()) {
            LocalBlock localBlock = entry.getValue();
            if (localBlock.hasBlockEntity()) {
                Object blockEntity = localBlock.getBlockEntity();

                // Tick grid block entities
                if (blockEntity instanceof GridBlockEntity gridBlockEntity) {
                    // Create a virtual world position for the block entity
                    Vector3f worldPos = new Vector3f();
                    Transform gridTransform = new Transform();
                    getRigidBody().getWorldTransform(gridTransform);
                    gridTransform.origin.get(worldPos);

                    BlockPos virtualWorldPos = new BlockPos(
                            (int) Math.floor(worldPos.x + entry.getKey().getX()),
                            (int) Math.floor(worldPos.y + entry.getKey().getY()),
                            (int) Math.floor(worldPos.z + entry.getKey().getZ())
                    );

                    try {
                        gridBlockEntity.tick(world, virtualWorldPos, localBlock.getState());
                    } catch (Exception e) {
                        SLogger.log(this, "Error ticking grid block entity at " + entry.getKey() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
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
     * Gets the origin as a Vector3f.
     */
    public Vector3f originFloat() {
        return new Vector3f((float) origin.x, (float) origin.y, (float) origin.z);
    }

    // ----------------------------------------------
    // PACKAGE-PRIVATE METHODS FOR COMPONENTS
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
}