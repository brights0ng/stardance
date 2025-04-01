package net.stardance.physics.entity;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.PairCachingGhostObject;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.stardance.utils.SLogger;
import net.stardance.utils.ILoggingControl;

import javax.vecmath.Vector3f;

/**
 * Represents an entity in the physics world.
 * Creates and manages a ghost object that tracks the entity's position
 * but doesn't respond to physics forces.
 */
public class EntityProxy implements ILoggingControl {

    // Core references
    private final Entity entity;
    private final DynamicsWorld dynamicsWorld;

    // Physics objects
    private final PairCachingGhostObject ghostObject;
    private CollisionShape shape;

    // Tracking state
    private Box previousAABB;
    private boolean isActive = false;

    // Filter groups for collision
    public static final short ENTITY_GROUP = 11; // Default entity collision group
    public static final short ENTITY_MASK = 1;   // Default collision mask (grid objects)

    /**
     * Creates a new EntityProxy for the given entity.
     *
     * @param entity The Minecraft entity to proxy
     * @param dynamicsWorld The physics world to add the proxy to
     */
    public EntityProxy(Entity entity, DynamicsWorld dynamicsWorld) {
        this(entity, dynamicsWorld, ENTITY_GROUP, ENTITY_MASK);
    }

    /**
     * Creates a new EntityProxy with custom collision filtering.
     *
     * @param entity The Minecraft entity to proxy
     * @param dynamicsWorld The physics world to add the proxy to
     * @param collisionGroup The collision group this entity belongs to
     * @param collisionMask The collision mask defining what this entity collides with
     */
    public EntityProxy(Entity entity, DynamicsWorld dynamicsWorld,
                       short collisionGroup, short collisionMask) {
        this.entity = entity;
        this.dynamicsWorld = dynamicsWorld;

        // Create the ghost object
        this.ghostObject = new PairCachingGhostObject();

        // Set collision flags - NO_CONTACT_RESPONSE means it won't push things
        ghostObject.setCollisionFlags(
                CollisionFlags.NO_CONTACT_RESPONSE |
                        CollisionFlags.CUSTOM_MATERIAL_CALLBACK |
                        CollisionFlags.KINEMATIC_OBJECT  // Add this flag
        );

        // Store reference to entity for collision callbacks
        ghostObject.setUserPointer(entity);

        // Initial setup
        updateShape();
        updatePosition();

        // Add to physics world with custom filtering
        dynamicsWorld.addCollisionObject(ghostObject, collisionGroup, collisionMask);
        isActive = true;

        SLogger.log(this, "Created entity proxy for: " + entity.getUuid() +
                " (group=" + collisionGroup + ", mask=" + collisionMask + ")");
    }

    /**
     * Sets a custom collision shape for this proxy.
     */
    public void setCollisionShape(CollisionShape shape) {
        if (shape != null) {
            this.shape = shape;
            ghostObject.setCollisionShape(shape);
            SLogger.log(this, "Set custom shape for entity: " + entity.getUuid());
        }
    }

    /**
     * Updates the proxy's position and shape.
     * Called each tick to keep the proxy in sync with the entity.
     */
    public void update() {
        if (!isActive) return;

        // Check if shape needs updating (entity size changed)
        if (hasEntitySizeChanged()) {
            updateShape();
        }

        // Always update position
        updatePosition();
    }

    /**
     * Checks if the entity's size has changed since last update.
     */
    private boolean hasEntitySizeChanged() {
        Box currentBox = entity.getBoundingBox();

        if (previousAABB == null) {
            previousAABB = currentBox;
            return true;
        }

        // Check if dimensions have changed significantly
        double epsilon = 0.01;
        boolean changed =
                Math.abs((currentBox.maxX - currentBox.minX) - (previousAABB.maxX - previousAABB.minX)) > epsilon ||
                        Math.abs((currentBox.maxY - currentBox.minY) - (previousAABB.maxY - previousAABB.minY)) > epsilon ||
                        Math.abs((currentBox.maxZ - currentBox.minZ) - (previousAABB.maxZ - previousAABB.minZ)) > epsilon;

        if (changed) {
            previousAABB = currentBox;
        }

        return changed;
    }

    /**
     * Updates the collision shape based on entity dimensions.
     */
    private void updateShape() {
        Box entityBox = entity.getBoundingBox();

        // Calculate half extents (from center to edge)
        float width = (float) ((entityBox.maxX - entityBox.minX) * 0.5f);
        float height = (float) ((entityBox.maxY - entityBox.minY) * 0.5f);
        float depth = (float) ((entityBox.maxZ - entityBox.minZ) * 0.5f);

        // Create new box shape
        BoxShape newShape = new BoxShape(new Vector3f(width, height, depth));

        // Set small margin to prevent falling through cracks
        newShape.setMargin(0.04f);

        // Update the shape
        ghostObject.setCollisionShape(newShape);

        // Store the new shape
        if (shape != null) {
            // In a real implementation, you might need to clean up the old shape
        }
        shape = newShape;

        SLogger.log(this, "Updated shape for entity: " + entity.getUuid() +
                ", dimensions: " + width + "x" + height + "x" + depth);
    }

    /**
     * Updates the proxy's position to match the entity.
     */
    private void updatePosition() {
        Box entityBox = entity.getBoundingBox();

        // Calculate center of bounding box
        float centerX = (float) (entityBox.minX + entityBox.maxX) * 0.5f;
        float centerY = (float) (entityBox.minY + entityBox.maxY) * 0.5f;
        float centerZ = (float) (entityBox.minZ + entityBox.maxZ) * 0.5f;

        // Update transform
        Transform transform = new Transform();
        transform.setIdentity();
        transform.origin.set(centerX, centerY, centerZ);

        // Apply to ghost object
        ghostObject.setWorldTransform(transform);
    }

    /**
     * Removes this proxy from the physics world.
     * Should be called when the entity is no longer needed.
     */
    public void remove() {
        if (isActive) {
            dynamicsWorld.removeCollisionObject(ghostObject);
            isActive = false;
            SLogger.log(this, "Removed entity proxy for: " + entity.getUuid());
        }
    }

    /**
     * Gets the underlying collision object.
     */
    public CollisionObject getCollisionObject() {
        return ghostObject;
    }

    /**
     * Gets the entity this proxy represents.
     */
    public Entity getEntity() {
        return entity;
    }

    /**
     * Checks if this proxy is active in the physics world.
     */
    public boolean isActive() {
        return isActive;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }
}