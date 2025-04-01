package net.stardance.physics.entity;

import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.collision.shapes.CompoundShape;
import com.bulletphysics.collision.shapes.CylinderShape;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating specialized physics proxies for different entity types.
 * Handles shape optimization and proxy configuration.
 */
public class EntityProxyFactory implements ILoggingControl {

    // Cache for entity shape creators by entity type
    private static final ConcurrentHashMap<EntityType<?>, ShapeCreator> SHAPE_CREATORS = new ConcurrentHashMap<>();

    // Shape margin to prevent falling through cracks
    private static final float DEFAULT_MARGIN = 0.04f;

    // Collision filter groups for entity types
    private static final short PLAYER_GROUP = 12;
    private static final short MOB_GROUP = 13;
    private static final short ITEM_GROUP = 14;
    private static final short OTHER_ENTITY_GROUP = 15;

    // Common mask for grid collisions (will be refined)
    private static final short GRID_COLLISION_MASK = 1;  // Grid objects use group 1

    /**
     * Creates a proxy for the given entity with appropriate settings.
     *
     * @param entity The entity to create a proxy for
     * @param dynamicsWorld The dynamics world to add the proxy to
     * @return The created proxy
     */
    public static EntityProxy createProxy(Entity entity, DynamicsWorld dynamicsWorld) {
        // Determine collision group based on entity type
        short collisionGroup = determineCollisionGroup(entity);

        // Determine collision mask based on what this entity should collide with
        short collisionMask = determineCollisionMask(entity);

        // Create the proxy with custom settings
        EntityProxy proxy = new EntityProxy(entity, dynamicsWorld, collisionGroup, collisionMask);

        // Set a custom shape if available
        CollisionShape shape = createOptimizedShape(entity);
        if (shape != null) {
            proxy.setCollisionShape(shape);
        }

        return proxy;
    }

    /**
     * Creates an optimized collision shape for the given entity.
     * Uses specialized shapes for different entity types.
     */
// In EntityProxyFactory.java, modify the createOptimizedShape method
    private static CollisionShape createOptimizedShape(Entity entity) {
        // Create a compound shape instead of a direct box shape
        CompoundShape compound = new CompoundShape();

        // Get entity dimensions
        Box entityBox = entity.getBoundingBox();
        float width = (float) ((entityBox.maxX - entityBox.minX) * 0.5f);
        float height = (float) ((entityBox.maxY - entityBox.minY) * 0.5f);
        float depth = (float) ((entityBox.maxZ - entityBox.minZ) * 0.5f);

        // Create box shape
        BoxShape boxShape = new BoxShape(new Vector3f(width, height, depth));
        boxShape.setMargin(0.04f);

        // Add box to compound shape
        Transform transform = new Transform();
        transform.setIdentity();
        compound.addChildShape(transform, boxShape);

        return compound;
    }

    /**
     * Determines which shape creator to use for an entity type.
     */
    private static ShapeCreator determineShapeCreator(Entity entity) {

        // Default to box shape
        return EntityProxyFactory::createBoxShape;
    }

    /**
     * Creates a box shape for an entity.
     */
    private static CollisionShape createBoxShape(Entity entity) {
        Box box = entity.getBoundingBox();

        // Calculate half extents (from center to edge)
        float width = (float) ((box.maxX - box.minX) * 0.5f);
        float height = (float) ((box.maxY - box.minY) * 0.5f);
        float depth = (float) ((box.maxZ - box.minZ) * 0.5f);

        // Create shape with margin
        BoxShape shape = new BoxShape(new Vector3f(width, height, depth));
        shape.setMargin(DEFAULT_MARGIN);

        return shape;
    }

    /**
     * Creates a cylinder shape for an entity (better for players/mobs).
     */
    private static CollisionShape createCylinderShape(Entity entity) {
        Box box = entity.getBoundingBox();

        // Calculate dimensions
        float radius = (float) (Math.max(box.maxX - box.minX, box.maxZ - box.minZ) * 0.5f);
        float height = (float) (box.maxY - box.minY);

        // Create cylinder shape (Y-up)
        CylinderShape shape = new CylinderShape(new Vector3f(radius, height * 0.5f, radius));
        shape.setMargin(DEFAULT_MARGIN);

        return shape;
    }

    /**
     * Determines the collision group for an entity.
     */
    private static short determineCollisionGroup(Entity entity) {
        if (entity instanceof PlayerEntity) {
            return PLAYER_GROUP;
        } else if (entity instanceof LivingEntity) {
            return MOB_GROUP;
        } else if (entity.getType() == EntityType.ITEM) {
            return ITEM_GROUP;
        } else {
            return OTHER_ENTITY_GROUP;
        }
    }

    /**
     * Determines what an entity should collide with.
     */
    private static short determineCollisionMask(Entity entity) {
        // All entities should collide with grids
        return GRID_COLLISION_MASK;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }

    /**
     * Functional interface for creating shapes.
     */
    @FunctionalInterface
    private interface ShapeCreator {
        CollisionShape createShape(Entity entity);
    }
}