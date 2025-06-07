package net.starlight.stardance.physics.entity;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.MotionState;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.Stardance;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an entity in the physics system.
 * Acts as a proxy between Minecraft entities and jBullet physics objects.
 */
public class EntityProxy implements ILoggingControl {

    // Enable debug logging
    private static final boolean DEBUG_PROXY = true;

    // DynamicsWorld - obtained from the physics engine for the entity's world
    private final DynamicsWorld dynamicsWorld;

    // Constants
    private static final float DEFAULT_MASS = 5.0f; // Zero mass = kinematic object
    private static final float COLLISION_MARGIN = 0.02f; // Small margin to prevent "sticky" collisions

    // Entity reference
    private final Entity entity;

    // Physics objects
    private CollisionShape collisionShape;
    private final MotionState motionState;
    private final RigidBody rigidBody;

    // State tracking
    private boolean isActive = true;
    private Box lastBoundingBox;

    // Contact tracking
    private final List<Contact> currentContacts = new ArrayList<>();

    // Update tracking
    private long lastUpdateTime = 0;

    /**
     * Creates a new EntityProxy for the specified entity.
     *
     * @param entity The Minecraft entity to proxy
     * @param collisionShape The bullet physics collision shape for this entity
     */
    public EntityProxy(Entity entity, CollisionShape collisionShape) {
        this.entity = entity;
        this.collisionShape = collisionShape;
        this.lastBoundingBox = entity.getBoundingBox();

        // Configure shape properties
        this.collisionShape.setMargin(COLLISION_MARGIN);

        // Create initial transform
        Transform startTransform = new Transform();
        startTransform.setIdentity();
        updateTransformFromEntity(startTransform, entity);

        // Create motion state
        this.motionState = new DefaultMotionState(startTransform);

        // Calculate inertia (not used for kinematic objects, but required)
        Vector3f inertia = new Vector3f(0, 0, 0);

        // Create rigid body
        RigidBodyConstructionInfo rbInfo = new RigidBodyConstructionInfo(
                DEFAULT_MASS, motionState, collisionShape, inertia);
        this.rigidBody = new RigidBody(rbInfo);

        // Configure as kinematic body (moved by code, not physics)
        rigidBody.setCollisionFlags(
                rigidBody.getCollisionFlags() |
                        CollisionFlags.KINEMATIC_OBJECT |
                        CollisionFlags.CUSTOM_MATERIAL_CALLBACK |
                        CollisionFlags.NO_CONTACT_RESPONSE
        );

        // Store reference to this proxy in the user pointer
        rigidBody.setUserPointer(this);

        // Activate the rigid body initially
        rigidBody.activate(true);

        this.dynamicsWorld = Stardance.engineManager.getEngine(entity.getWorld()).getDynamicsWorld();

        if (DEBUG_PROXY && entity instanceof PlayerEntity) {
            SLogger.log(this, "Created proxy for player: " + entity.getEntityName());
        }
    }

    /**
     * Updates the proxy's physics state from the entity's current state.
     * Called each tick to synchronize the proxy with the entity.
     *
     * @param entity The entity to update from
     */
    public void updateFromEntity(Entity entity) {
        if (!isActive) {
            isActive = true;
            dynamicsWorld.addCollisionObject(rigidBody);

            if (DEBUG_PROXY && entity instanceof PlayerEntity) {
                SLogger.log(this, "Reactivated proxy for player: " + entity.getEntityName());
            }
        }

        // Check if bounding box has changed
        Box currentBoundingBox = entity.getBoundingBox();
        boolean boundingBoxChanged = !currentBoundingBox.equals(lastBoundingBox);

        // Update transform from entity position
        Transform worldTransform = new Transform();
        rigidBody.getWorldTransform(worldTransform);

        updateTransformFromEntity(worldTransform, entity);

        // Update motion state with new transform
        motionState.setWorldTransform(worldTransform);
        rigidBody.setWorldTransform(worldTransform);

        // If bounding box changed, update collision shape
        if (boundingBoxChanged) {
            updateCollisionShape(entity);

            // Log bounding box change for players
            if (DEBUG_PROXY && entity instanceof PlayerEntity) {
                Vec3d oldSize = new Vec3d(
                        lastBoundingBox.getXLength(),
                        lastBoundingBox.getYLength(),
                        lastBoundingBox.getZLength()
                );

                Vec3d newSize = new Vec3d(
                        currentBoundingBox.getXLength(),
                        currentBoundingBox.getYLength(),
                        currentBoundingBox.getZLength()
                );

                SLogger.log(this, String.format(
                        "Player bounding box changed - old size=(%.2f, %.2f, %.2f), new size=(%.2f, %.2f, %.2f)",
                        oldSize.x, oldSize.y, oldSize.z,
                        newSize.x, newSize.y, newSize.z));
            }

            lastBoundingBox = currentBoundingBox;
        }

        // Always keep the rigid body active since we're constantly updating it
        rigidBody.activate(true);

        // Update tracking
        lastUpdateTime = entity.getWorld().getTime();
    }

    /**
     * Updates the transform from entity position and rotation.
     *
     * @param transform The transform to update
     * @param entity The entity to get position and rotation from
     */
    private void updateTransformFromEntity(Transform transform, Entity entity) {
        // Set position to center of entity's bounding box
        Box box = entity.getBoundingBox();
        float x = (float)((box.minX + box.maxX) * 0.5);
        float y = (float)((box.minY + box.maxY) * 0.5);
        float z = (float)((box.minZ + box.maxZ) * 0.5);

        transform.origin.set(x, y, z);

        // Set rotation based on entity's yaw
        // Convert Minecraft's yaw (degrees) to radians
        float yawRadians = (float)Math.toRadians(-entity.getYaw());

        // Create rotation matrix around Y axis
        Quat4f rotation = new Quat4f();
        rotation.set(new javax.vecmath.AxisAngle4f(0, 1, 0, yawRadians));
        transform.setRotation(rotation);
    }

    /**
     * Updates the collision shape based on the entity's current bounding box.
     *
     * @param entity The entity to update from
     */
    private void updateCollisionShape(Entity entity) {
        // This would be replaced by proper collision shape handling
        // For now, we assume the shape factory handles this
        // and the shape itself can be updated directly

        // In a real implementation, we might need to:
        // 1. Get a new shape from a shape factory
        // 2. Update the rigid body with the new shape
        // 3. Update inertia and other properties

        // For now, we'll just log
        if (DEBUG_PROXY && entity instanceof PlayerEntity) {
            SLogger.log(this, "Entity collision shape updated for player: " + entity.getEntityName());
        }
    }

    /**
     * Performs a cleanup when the proxy is no longer needed.
     * Should be called before discarding the proxy.
     */
    public void dispose() {
        dynamicsWorld.removeCollisionObject(rigidBody);
        // Clean up resources if needed
        isActive = false;
        currentContacts.clear();

        if (DEBUG_PROXY && entity instanceof PlayerEntity) {
            SLogger.log(this, "Disposed proxy for player: " + entity.getEntityName());
        }
    }

    /**
     * Checks if the proxy is still active.
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Gets the rigid body for this proxy.
     */
    public RigidBody getRigidBody() {
        return rigidBody;
    }

    /**
     * Gets the collision object for this proxy.
     */
    public CollisionObject getCollisionObject() {
        return rigidBody;
    }

    /**
     * Gets the collision shape for this proxy.
     */
    public CollisionShape getCollisionShape() {
        return collisionShape;
    }

    /**
     * Gets the entity this proxy represents.
     */
    public Entity getEntity() {
        return entity;
    }

    /**
     * Gets the last known bounding box.
     * Used to detect when bounding box changes.
     */
    public Box getLastBoundingBox() {
        return lastBoundingBox;
    }

    /**
     * Gets the last update time.
     * Used to determine when proxy was last updated.
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Adds a contact to this proxy's list of current contacts.
     *
     * @param contact The contact to add
     */
    public void addContact(Contact contact) {
        currentContacts.add(contact);
    }

    /**
     * Clears all contacts from this proxy.
     */
    public void clearContacts() {
        currentContacts.clear();
    }

    /**
     * Gets all current contacts for this entity.
     */
    public List<Contact> getContacts() {
        return new ArrayList<>(currentContacts);
    }

    /**
     * Gets the center of the entity in world space.
     */
    public Vec3d getCenter() {
        Box box = entity.getBoundingBox();
        return new Vec3d(
                (box.minX + box.maxX) * 0.5,
                (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5
        );
    }

    /**
     * Gets the half-extents of the entity bounding box.
     */
    public Vector3f getHalfExtents() {
        Box box = entity.getBoundingBox();
        return new Vector3f(
                (float)((box.maxX - box.minX) * 0.5),
                (float)((box.maxY - box.minY) * 0.5),
                (float)((box.maxZ - box.minZ) * 0.5)
        );
    }

    /**
     * Gets the minimum AABB point.
     */
    public Vector3f getMinimum() {
        Box box = entity.getBoundingBox();
        return new Vector3f(
                (float)box.minX,
                (float)box.minY,
                (float)box.minZ
        );
    }

    /**
     * Gets the maximum AABB point.
     */
    public Vector3f getMaximum() {
        Box box = entity.getBoundingBox();
        return new Vector3f(
                (float)box.maxX,
                (float)box.maxY,
                (float)box.maxZ
        );
    }

    @Override
    public String toString() {
        return "EntityProxy{" +
                "entity=" + entity.getType().getName().getString() +
                ", active=" + isActive +
                '}';
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false; // Enable console logging for debugging
    }
}