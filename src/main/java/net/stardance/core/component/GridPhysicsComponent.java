package net.stardance.core.component;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CompoundShape;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.util.math.BlockPos;
import net.stardance.core.LocalBlock;
import net.stardance.core.LocalGrid;
import net.stardance.physics.SubchunkCoordinates;
import net.stardance.utils.SLogger;
import org.joml.Vector3i;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * Handles physics-related functionality for a LocalGrid.
 * Manages collision shapes, rigid bodies, transforms, and physics calculations.
 */
public class GridPhysicsComponent {
    // ----------------------------------------------
    // CONSTANTS
    // ----------------------------------------------
    private static final int SUBCHUNK_SIZE = 16; // Size of a subchunk (16x16x16)

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
    public GridPhysicsComponent(LocalGrid grid, Vector3d origin, Quat4f rotation) {
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

        // CRITICAL FIX: Skip if we've already updated this tick
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
     * Applies damping to reduce jitter in physics simulation.
     */
    public void applyVelocityDamping() {
        if (rigidBody == null) return;

        Vector3f linearVel = new Vector3f();
        rigidBody.getLinearVelocity(linearVel);

        // Stop completely if barely moving
        if (linearVel.length() < 0.1f) {
            rigidBody.setLinearVelocity(new Vector3f(0, 0, 0));
        } else {
            // Apply slight damping
            linearVel.scale(0.98f);
            rigidBody.setLinearVelocity(linearVel);
        }
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

        // Reset to dynamic if needed
        if (!wasKinematic) {
            rigidBody.setCollisionFlags(rigidBody.getCollisionFlags() & ~CollisionFlags.KINEMATIC_OBJECT);
        }

        // Clear velocities to prevent drift
        rigidBody.setLinearVelocity(new Vector3f(0, 0, 0));
        rigidBody.setAngularVelocity(new Vector3f(0, 0, 0));
        rigidBody.clearForces();
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
        compound.addChildShape(placeholderTransform, new BoxShape(new Vector3f(0.01f, 0.01f, 0.01f)));
        this.collisionShape = compound;

        // Calculate inertia
        Vector3f inertia = new Vector3f(0, 0, 0);
        collisionShape.calculateLocalInertia(0.1f, inertia);

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
                0.1f, gridMotionState, collisionShape, inertia);

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

            // Generate optimized collision shapes
            blockMerger.updateBlockMap(blocks, aabbMin, aabbMax);
            List<GridBlockMerger.BoxShapeData> boxes = blockMerger.generateMergedBoxes();

            // Create child shapes for each merged box
            for (GridBlockMerger.BoxShapeData box : boxes) {
                blockMerger.addBoxShapeToCompound(box, compound, aabbMin, this.centroid);
            }
        }

        // Store compound shape
        this.collisionShape = compound;

        // Calculate inertia
        Vector3f inertia = new Vector3f(0, 0, 0);
        collisionShape.calculateLocalInertia(this.totalMass, inertia);

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

        // Create rigid body
        RigidBodyConstructionInfo rbInfo = new RigidBodyConstructionInfo(
                this.totalMass, gridMotionState, collisionShape, inertia);

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