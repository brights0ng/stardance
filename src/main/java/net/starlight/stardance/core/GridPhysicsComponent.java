package net.starlight.stardance.core;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CompoundShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.starlight.stardance.physics.SubchunkCoordinates;
import net.starlight.stardance.utils.SLogger;
import org.joml.Vector3i;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static net.starlight.stardance.physics.EngineManager.COLLISION_GROUP_GRID;
import static net.starlight.stardance.physics.EngineManager.COLLISION_MASK_GRID;

/**
 * Handles physics-related functionality for a LocalGrid.
 * This class is package-private - external code should use LocalGrid instead.
 */
class GridPhysicsComponent {
    // ----------------------------------------------
    // CONSTANTS
    // ----------------------------------------------
    private static final int SUBCHUNK_SIZE = 16; // Size of a subchunk (16x16x16)
    private static final float COLLISION_MARGIN = 0.002f; // Small margin to prevent stuck entities
    private static final float SLEEP_VELOCITY_THRESHOLD = 0.05f; // Threshold for putting rigidbody to sleep

    // ----------------------------------------------
    // SHAPE CACHING
    // ----------------------------------------------
    private static final Map<BlockState, CollisionShape> SHAPE_CACHE = new ConcurrentHashMap<>();

    // ----------------------------------------------
    // PARENT REFERENCE
    // ----------------------------------------------
    private final LocalGrid grid;

    // ----------------------------------------------
    // PHYSICS COMPONENTS
    // ----------------------------------------------
    private RigidBody rigidBody;                   // Bullet physics rigid body
    private CompoundShape collisionShape;          // Compound collision shape
    private GridMotionState gridMotionState;       // Custom motion state

    // Transforms for interpolation
    private final Transform previousTransform = new Transform();
    private final Transform currentTransform = new Transform();

    // ----------------------------------------------
    // PHYSICS PROPERTIES
    // ----------------------------------------------
    private Vector3f centroid = new Vector3f();    // Center of mass
    private float totalMass = 0.0f;                // Total mass of all blocks
    private Vector3f absoluteWorldPosition = new Vector3f(0, 0, 0);   // Position without centroid offset

    // ----------------------------------------------
    // BOUNDING BOX
    // ----------------------------------------------
    private Vector3i aabbMin = new Vector3i();     // Minimum AABB point in grid space
    private Vector3i aabbMax = new Vector3i();     // Maximum AABB point in grid space

    // ----------------------------------------------
    // STATE FLAGS
    // ----------------------------------------------
    private boolean isFirstBuild = true;           // First time build flag
    private boolean rebuildInProgress = false;     // Rebuild operation in progress
    private boolean isAsleep = false;              // Whether the rigid body is currently sleeping
    private Set<SubchunkCoordinates> activeSubchunks = new HashSet<>(); // Currently active subchunks

    // ----------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------
    /**
     * Creates a new GridPhysicsComponent for the given LocalGrid.
     *
     * @param grid The parent LocalGrid
     * @param origin Initial origin position
     * @param rotation Initial rotation
     */
    GridPhysicsComponent(LocalGrid grid, Vector3d origin, Quat4f rotation) {
        this.grid = grid;

        // Create the rigid body with empty collision shape
        buildCollisionShapeAndRigidBody(origin, rotation);
    }

    // ----------------------------------------------
    // PUBLIC METHODS
    // ----------------------------------------------
    /**
     * Updates transform history for interpolation.
     * Modified to prevent multiple updates within the same tick.
     */
    private long lastUpdateTick = 0;

    public void updateTransforms() {
        if (rigidBody == null) return;

        // Get the current server tick
        long currentTick = grid.getWorld().getTime();

        // Skip if we've already updated this tick
        if (currentTick == lastUpdateTick) {
            return;
        }

        // Update the last update tick
        lastUpdateTick = currentTick;

        // Update transforms for interpolation
        synchronized (grid.getEngine().getPhysicsLock()) {
            Transform newTransform = new Transform();
            rigidBody.getMotionState().getWorldTransform(newTransform);

            previousTransform.set(currentTransform);
            currentTransform.set(newTransform);
        }
    }

    /**
     * Applies damping and sleeping logic to reduce jitter in physics simulation.
     */
    public void applyVelocityDamping() {
        if (rigidBody == null) return;

        Vector3f linearVel = new Vector3f();
        Vector3f angularVel = new Vector3f();
        rigidBody.getLinearVelocity(linearVel);
        rigidBody.getAngularVelocity(angularVel);

        // Check if the rigid body should be put to sleep
        float linearSpeed = linearVel.length();
        float angularSpeed = angularVel.length();

        if (linearSpeed < SLEEP_VELOCITY_THRESHOLD && angularSpeed < SLEEP_VELOCITY_THRESHOLD) {
            // Object is nearly still - should sleep
            if (!isAsleep) {
                // Put object to sleep if it wasn't already
                rigidBody.setActivationState(com.bulletphysics.collision.dispatch.CollisionObject.ISLAND_SLEEPING);
                isAsleep = true;
                SLogger.log(grid, "Grid rigid body put to sleep due to low velocity");
            }

            // Force velocities to be exactly zero
            rigidBody.setLinearVelocity(new Vector3f(0, 0, 0));
            rigidBody.setAngularVelocity(new Vector3f(0, 0, 0));
        } else {
            // Object is moving - wake it up if it was sleeping
            if (isAsleep) {
                rigidBody.activate(true);
                isAsleep = false;
            }

            // Apply more aggressive damping for stability
            linearVel.scale(0.95f);
            angularVel.scale(0.90f);
            rigidBody.setLinearVelocity(linearVel);
            rigidBody.setAngularVelocity(angularVel);
        }
    }

    /**
     * Updates which subchunks this grid overlaps.
     */
    public void updateActiveSubchunks() {
        Set<SubchunkCoordinates> newSubchunks = calculateOccupiedSubchunks();

        // Activate new subchunks
        for (SubchunkCoordinates coords : newSubchunks) {
            if (!activeSubchunks.contains(coords)) {
                grid.getEngine().getSubchunkManager().activateSubchunk(coords);
            }
        }

        // Deactivate old subchunks
        for (SubchunkCoordinates coords : activeSubchunks) {
            if (!newSubchunks.contains(coords)) {
                grid.getEngine().getSubchunkManager().deactivateSubchunk(coords);
            }
        }

        activeSubchunks = newSubchunks;
    }

    /**
     * Rebuilds physics properties after changes.
     */
    public void rebuildPhysics(ConcurrentMap<BlockPos, LocalBlock> blocks, GridBlockMerger blockMerger) {
        // Set kinematic during rebuild to prevent physics influence
        boolean wasKinematic = false;
        if (rigidBody.isKinematicObject()) {
            wasKinematic = true;
        } else {
            rigidBody.setCollisionFlags(rigidBody.getCollisionFlags() | CollisionFlags.KINEMATIC_OBJECT);
        }

        // Remove from world before rebuilding
        grid.getEngine().getDynamicsWorld().removeRigidBody(rigidBody);

        // Rebuild collision shape and physics
        rebuildCollisionShapeAndRigidBody(blocks, blockMerger);

        // Add back to world
        grid.getEngine().getDynamicsWorld().addRigidBody(rigidBody);

        if (rigidBody.getBroadphaseHandle() != null) {
            rigidBody.getBroadphaseHandle().collisionFilterGroup = COLLISION_GROUP_GRID;
            rigidBody.getBroadphaseHandle().collisionFilterMask = COLLISION_MASK_GRID;
        }

        // Reset to dynamic if needed
        if (!wasKinematic) {
            rigidBody.setCollisionFlags(rigidBody.getCollisionFlags() & ~CollisionFlags.KINEMATIC_OBJECT);
        }

        // Clear velocities to prevent drift
        rigidBody.setLinearVelocity(new Vector3f(0, 0, 0));
        rigidBody.setAngularVelocity(new Vector3f(0, 0, 0));
        rigidBody.clearForces();

        // Set higher mass to improve stability
        Vector3f inertia = new Vector3f();
        rigidBody.getCollisionShape().calculateLocalInertia(totalMass, inertia);
        rigidBody.setMassProps(totalMass, inertia);
    }

    /**
     * Converts a point from world space to grid-local space.
     *
     * @param worldPoint Point in world coordinates
     * @return Point in grid-local coordinates
     */
    public Vector3d worldToGridLocal(Vector3d worldPoint) {
        if (rigidBody == null) {
            return new Vector3d(worldPoint);
        }

        // Get current world transform
        Transform worldTransform = new Transform();
        rigidBody.getWorldTransform(worldTransform);

        // Invert transform
        Transform inverseTransform = new Transform(worldTransform);
        inverseTransform.inverse();

        // Transform point
        Vector3f temp = new Vector3f((float) worldPoint.x, (float) worldPoint.y, (float) worldPoint.z);
        inverseTransform.transform(temp);

        // Adjust for center of mass offset
        temp.add(this.centroid);

        return new Vector3d(temp.x, temp.y, temp.z);
    }

    /**
     * Gets the grid's axis-aligned bounding box in world space.
     *
     * @param minAabb Vector to store minimum point
     * @param maxAabb Vector to store maximum point
     */
    public void getAABB(Vector3f minAabb, Vector3f maxAabb) {
        if (rigidBody == null || collisionShape == null) {
            minAabb.set(0, 0, 0);
            maxAabb.set(0, 0, 0);
            return;
        }

        Transform worldTransform = new Transform();
        rigidBody.getWorldTransform(worldTransform);
        collisionShape.getAabb(worldTransform, minAabb, maxAabb);
    }

    /**
     * Calculates which subchunks are occupied by this grid.
     *
     * @return Set of occupied subchunk coordinates
     */
    public Set<SubchunkCoordinates> calculateOccupiedSubchunks() {
        Set<SubchunkCoordinates> occupied = new HashSet<>();

        if (rigidBody == null || collisionShape == null) {
            return occupied;
        }

        // Get AABB in world space
        Vector3f minAabb = new Vector3f();
        Vector3f maxAabb = new Vector3f();
        Transform tr = new Transform();
        rigidBody.getWorldTransform(tr);
        collisionShape.getAabb(tr, minAabb, maxAabb);

        // Convert to subchunk coordinates
        int minX = (int) Math.floor(minAabb.x / SUBCHUNK_SIZE);
        int minY = (int) Math.floor(minAabb.y / SUBCHUNK_SIZE);
        int minZ = (int) Math.floor(minAabb.z / SUBCHUNK_SIZE);

        int maxX = (int) Math.floor(maxAabb.x / SUBCHUNK_SIZE);
        int maxY = (int) Math.floor(maxAabb.y / SUBCHUNK_SIZE);
        int maxZ = (int) Math.floor(maxAabb.z / SUBCHUNK_SIZE);

        // Add all overlapping subchunks
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    occupied.add(new SubchunkCoordinates(x, y, z));
                }
            }
        }
        return occupied;
    }

    // ----------------------------------------------
    // PRIVATE METHODS
    // ----------------------------------------------
    /**
     * Initial creation of collision shape and rigid body.
     */
    private void buildCollisionShapeAndRigidBody(Vector3d origin, Quat4f rotation) {
        // Create a minimal compound shape
        CompoundShape compound = new CompoundShape();
        // Add a placeholder shape to ensure the compound shape isn't empty
        Transform placeholderTransform = new Transform();
        placeholderTransform.setIdentity();
        BoxShape placeholderBox = new BoxShape(new Vector3f(0.01f, 0.01f, 0.01f));
        placeholderBox.setMargin(COLLISION_MARGIN);
        compound.addChildShape(placeholderTransform, placeholderBox);
        this.collisionShape = compound;

        // Calculate inertia - use higher mass for stability
        float initialMass = 100f; // Higher mass for stability
        Vector3f inertia = new Vector3f(0, 0, 0);
        collisionShape.calculateLocalInertia(initialMass, inertia);

        // Scale inertia to increase rotational stability
        inertia.scale(5.0f);

        // Set up rigid body transform
        Transform initialTransform = new Transform();
        initialTransform.setIdentity();
        Vector3f worldOrigin = new Vector3f((float) origin.x, (float) origin.y, (float) origin.z);
        initialTransform.origin.set(worldOrigin);
        initialTransform.setRotation(rotation);

        // Create motion state
        gridMotionState = new GridMotionState(initialTransform);

        // Create rigid body
        RigidBodyConstructionInfo rbInfo = new RigidBodyConstructionInfo(
                initialMass, gridMotionState, collisionShape, inertia);

        // Set damping factors
        rbInfo.linearDamping = 0.6f;
        rbInfo.angularDamping = 0.8f;

        // Create the rigid body
        this.rigidBody = new RigidBody(rbInfo);

        // Set up collision flags
        rigidBody.setCollisionFlags(rigidBody.getCollisionFlags() | CollisionFlags.CUSTOM_MATERIAL_CALLBACK);
        rigidBody.setUserPointer(grid);

        // Initialize transform history
        currentTransform.set(initialTransform);
        previousTransform.set(initialTransform);

        // Activate the rigid body
        rigidBody.activate(true);


    }

    /**
     * Rebuilds the collision shape and rigid body with current block configuration.
     */
    private void rebuildCollisionShapeAndRigidBody(ConcurrentMap<BlockPos, LocalBlock> blocks, GridBlockMerger blockMerger) {
        // Store current transform for preservation
        Vector3f currentPos = null;
        Vector3f currentLinVel = new Vector3f(0, 0, 0);
        Vector3f currentAngVel = new Vector3f(0, 0, 0);
        Quat4f currentRot = new Quat4f();

        if (rigidBody != null) {
            // Save position, velocity and rotation
            currentPos = new Vector3f();
            rigidBody.getCenterOfMassPosition(currentPos);
            rigidBody.getLinearVelocity(currentLinVel);
            rigidBody.getAngularVelocity(currentAngVel);
            rigidBody.getOrientation(currentRot);

            // Calculate and save absolute position (without centroid)
            absoluteWorldPosition.set(currentPos);
            absoluteWorldPosition.sub(this.centroid);
        }

        // Create a new compound shape
        CompoundShape compound = new CompoundShape();

        // Reset mass and centroid trackers
        float massAcc = 0.0f;
        Vector3f centroidAcc = new Vector3f(0, 0, 0);

        // Reset AABB
        aabbMin = new Vector3i(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        aabbMax = new Vector3i(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

        // If no blocks, create a tiny placeholder shape
        if (blocks.isEmpty()) {
            blockMerger.addPlaceholderShape(compound);
            this.totalMass = 0;
            this.centroid.set(0, 0, 0);
        } else {
            // Calculate mass, centroid, and AABB for all blocks
            for (LocalBlock localBlock : blocks.values()) {
                BlockPos pos = localBlock.getPosition();

                // Increase mass for better stability
                float blockMass = localBlock.getMass();
                massAcc += blockMass;

                // Add weighted position to centroid
                Vector3f blockPos = new Vector3f(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
                blockPos.scale(blockMass);
                centroidAcc.add(blockPos);

                // Update AABB
                aabbMin.x = Math.min(aabbMin.x, pos.getX());
                aabbMin.y = Math.min(aabbMin.y, pos.getY());
                aabbMin.z = Math.min(aabbMin.z, pos.getZ());
                aabbMax.x = Math.max(aabbMax.x, pos.getX());
                aabbMax.y = Math.max(aabbMax.y, pos.getY());
                aabbMax.z = Math.max(aabbMax.z, pos.getZ());
            }

            // Finalize centroid
            if (massAcc > 0) {
                centroidAcc.scale(1.0f / massAcc);
            }
            this.totalMass = massAcc;
            this.centroid = centroidAcc;

            grid.getRenderComponent().updateRenderState(this.rigidBody);

            // Separate blocks into simple and complex for different processing
            Map<BlockPos, LocalBlock> simpleBlocks = new HashMap<>();
            Map<BlockPos, LocalBlock> complexBlocks = new HashMap<>();

            for (Map.Entry<BlockPos, LocalBlock> entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                LocalBlock block = entry.getValue();

                if (hasFullCubeHitbox(block.getState())) {
                    simpleBlocks.put(pos, block);
                } else {
                    complexBlocks.put(pos, block);
                }
            }

            // Process simple blocks with blockMerger for optimization
            if (!simpleBlocks.isEmpty()) {
                SLogger.log(grid, "Processing " + simpleBlocks.size() + " simple blocks with merger");

                // Create a temporary map with just the simple blocks
                ConcurrentMap<BlockPos, LocalBlock> simpleBlocksMap = new ConcurrentHashMap<>(simpleBlocks);

                // Update block map for merging (simple blocks only)
                blockMerger.updateBlockMap(simpleBlocksMap, aabbMin, aabbMax);
                List<GridBlockMerger.BoxShapeData> boxes = blockMerger.generateMergedBoxes();

                // Add merged boxes to compound shape
                for (GridBlockMerger.BoxShapeData box : boxes) {
                    blockMerger.addBoxShapeToCompound(box, compound, aabbMin, this.centroid);
                }
            }

            // Process complex blocks individually with accurate VoxelShape collision
            if (!complexBlocks.isEmpty()) {
                SLogger.log(grid, "Processing " + complexBlocks.size() + " complex blocks with VoxelShape conversion");

                for (Map.Entry<BlockPos, LocalBlock> entry : complexBlocks.entrySet()) {
                    BlockPos pos = entry.getKey();
                    LocalBlock block = entry.getValue();

                    // Get or create collision shape from block's VoxelShape
                    CollisionShape blockShape = getOrCreateShapeFromState(block.getState());

                    if (blockShape != null) {
                        // Position relative to grid centroid
                        Transform localTransform = new Transform();
                        localTransform.setIdentity();
                        Vector3f blockPosition = new Vector3f(
                                pos.getX() + 0.5f - centroid.x,
                                pos.getY() + 0.5f - centroid.y,
                                pos.getZ() + 0.5f - centroid.z
                        );
                        localTransform.origin.set(blockPosition);

                        // Add to compound shape
                        compound.addChildShape(localTransform, blockShape);
                    }
                }
            }
        }

        // Store compound shape
        this.collisionShape = compound;

        // Calculate inertia with higher mass for stability
        Vector3f inertia = new Vector3f(0, 0, 0);
        collisionShape.calculateLocalInertia(this.totalMass, inertia);

        // Scale up inertia for better rotational stability
        inertia.scale(2.0f);

        // Set up rigid body transform
        Transform desiredTransform = new Transform();
        desiredTransform.setIdentity();

        if (rigidBody == null || isFirstBuild) {
            // First-time creation
            Vector3f worldOrigin = new Vector3f(grid.originFloat());
            worldOrigin.add(this.centroid);
            desiredTransform.origin.set(worldOrigin);
            desiredTransform.setRotation(grid.getRotation());

            // Store initial absolute position
            absoluteWorldPosition.set(grid.originFloat());
            isFirstBuild = false;
        } else {
            // Reusing rigid body - preserve position
            Vector3f worldOrigin = new Vector3f(absoluteWorldPosition);
            worldOrigin.add(this.centroid);
            desiredTransform.origin.set(worldOrigin);
            desiredTransform.setRotation(currentRot);
        }

        // Create motion state
        gridMotionState = new GridMotionState(desiredTransform);

        // Create rigid body with higher damping for stability
        RigidBodyConstructionInfo rbInfo = new RigidBodyConstructionInfo(
                this.totalMass, gridMotionState, collisionShape, inertia);
        rbInfo.linearDamping = 0.6f;
        rbInfo.angularDamping = 0.8f;

        // Create or recreate rigid body
        this.rigidBody = new RigidBody(rbInfo);

        // Restore velocity if needed
        if (currentLinVel != null && (currentLinVel.length() > 0.01f || currentAngVel.length() > 0.01f)) {
            rigidBody.setLinearVelocity(currentLinVel);
            rigidBody.setAngularVelocity(currentAngVel);
        }

        // Set up collision flags
        rigidBody.setCollisionFlags(rigidBody.getCollisionFlags() | CollisionFlags.CUSTOM_MATERIAL_CALLBACK);
        rigidBody.setUserPointer(grid);

        // Initialize transform history
        currentTransform.set(desiredTransform);
        previousTransform.set(desiredTransform);

        // Activate the rigid body
        rigidBody.activate(true);
    }

    /**
     * Checks if a BlockState has a full cube hitbox.
     */
    private boolean hasFullCubeHitbox(BlockState state) {
        try {
            // Check if the block is a full cube using Minecraft's own method
            // This returns true for blocks like stone, dirt, etc.
            return Block.isShapeFullCube(state.getOutlineShape(grid.getWorld(), BlockPos.ORIGIN));
        } catch (Exception e) {
            // Fallback if the method above fails
            VoxelShape shape = state.getCollisionShape(grid.getWorld(), BlockPos.ORIGIN);
            Box bounds = shape.getBoundingBox();

            // Check if bounds are close enough to a full 1x1x1 cube
            // Using a small epsilon to allow for minor precision differences
            double epsilon = 0.001;
            return Math.abs(bounds.minX) < epsilon &&
                    Math.abs(bounds.minY) < epsilon &&
                    Math.abs(bounds.minZ) < epsilon &&
                    Math.abs(bounds.maxX - 1.0) < epsilon &&
                    Math.abs(bounds.maxY - 1.0) < epsilon &&
                    Math.abs(bounds.maxZ - 1.0) < epsilon;
        }
    }

    /**
     * Gets or creates a collision shape from a BlockState, using caching.
     */
    private CollisionShape getOrCreateShapeFromState(BlockState state) {
        return SHAPE_CACHE.computeIfAbsent(state, this::createShapeFromState);
    }

    /**
     * Creates a collision shape from a BlockState's VoxelShape.
     */
    private CollisionShape createShapeFromState(BlockState state) {
        VoxelShape voxelShape = state.getCollisionShape(grid.getWorld(), BlockPos.ORIGIN);

        if (voxelShape.isEmpty()) {
            return null; // No collision
        }

        // For complex shapes, create a compound
        CompoundShape compoundShape = new CompoundShape();

        // Extract each box from the VoxelShape
        final boolean[] anyBoxes = {false};
        voxelShape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            // Convert from Minecraft's 0-1 scale to our half-extents (0-0.5)
            float hx = (float)(maxX - minX) / 2.0f;
            float hy = (float)(maxY - minY) / 2.0f;
            float hz = (float)(maxZ - minZ) / 2.0f;

            // Skip degenerate boxes
            if (hx < 0.001f || hy < 0.001f || hz < 0.001f) return;

            // Create box shape with small margin
            BoxShape boxShape = new BoxShape(new Vector3f(hx, hy, hz));
            boxShape.setMargin(COLLISION_MARGIN);

            // Position within local block space
            Transform localTransform = new Transform();
            localTransform.setIdentity();
            localTransform.origin.set(
                    new Vector3f(
                            (float)(minX + hx) - 0.5f,
                            (float)(minY + hy) - 0.5f,
                            (float)(minZ + hz) - 0.5f
                    )
            );

            // Add to compound shape
            compoundShape.addChildShape(localTransform, boxShape);
            anyBoxes[0] = true;
        });

        if (!anyBoxes[0]) {
            // No boxes in shape - use a minimal shape
            BoxShape minimal = new BoxShape(new Vector3f(0.01f, 0.01f, 0.01f));
            minimal.setMargin(COLLISION_MARGIN);
            Transform localTransform = new Transform();
            localTransform.setIdentity();
            compoundShape.addChildShape(localTransform, minimal);
        }

        return compoundShape;
    }

    // ----------------------------------------------
    // GETTERS / SETTERS
    // ----------------------------------------------
    /**
     * Gets the rigid body.
     */
    public RigidBody getRigidBody() {
        return rigidBody;
    }

    /**
     * Gets the center of mass.
     */
    public Vector3f getCentroid() {
        return centroid;
    }

    /**
     * Gets the current transform.
     */
    public Transform getCurrentTransform(Transform outTransform) {
        outTransform.set(currentTransform);
        return outTransform;
    }

    /**
     * Gets the previous transform.
     */
    public Transform getPreviousTransform(Transform outTransform) {
        outTransform.set(previousTransform);
        return outTransform;
    }

    /**
     * Sets the rebuild in progress flag.
     */
    public void setRebuildInProgress(boolean inProgress) {
        this.rebuildInProgress = inProgress;
    }

    /**
     * Gets the rebuild in progress flag.
     */
    public boolean isRebuildInProgress() {
        return rebuildInProgress;
    }

    /**
     * Gets the active subchunks.
     */
    public Set<SubchunkCoordinates> getActiveSubchunks() {
        return activeSubchunks;
    }

    // ----------------------------------------------
    // NESTED CLASSES
    // ----------------------------------------------
    /**
     * Custom motion state that maintains the grid's physics transform.
     */
    public class GridMotionState extends DefaultMotionState {
        private final Transform desiredTransform = new Transform();

        /**
         * Creates a new motion state with the given initial transform.
         */
        public GridMotionState(Transform startTransform) {
            super(startTransform);
            desiredTransform.set(startTransform);
        }

        /**
         * Sets a new desired transform.
         */
        public void resetWorldTransform(Transform transform) {
            super.setWorldTransform(transform);
            desiredTransform.set(transform);
        }

        @Override
        public Transform getWorldTransform(Transform worldTrans) {
            super.getWorldTransform(worldTrans);
            return worldTrans;
        }

        @Override
        public void setWorldTransform(Transform worldTrans) {
            super.setWorldTransform(worldTrans);
            // Position correction handled in tick method
        }
    }
}