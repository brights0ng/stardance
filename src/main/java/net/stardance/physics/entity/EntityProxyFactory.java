package net.stardance.physics.entity;

import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.collision.shapes.CompoundShape;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.util.math.Box;
import net.stardance.physics.PhysicsEngine;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.Map;

import static net.stardance.physics.EngineManager.COLLISION_GROUP_ENTITY;
import static net.stardance.physics.EngineManager.COLLISION_MASK_ENTITY;

/**
 * Factory for creating EntityProxy objects and their associated collision shapes.
 * Handles creation, caching, and configuration of physics proxies for entities.
 */
public class EntityProxyFactory implements ILoggingControl {
    // PhysicsEngine reference for access to dynamics world
    private final PhysicsEngine physicsEngine;

    // Shape cache for reusing common collision shapes
    private final Map<EntityShapeKey, CollisionShape> shapeCache = new HashMap<>();

    /**
     * Creates a new EntityProxyFactory.
     *
     * @param physicsEngine The physics engine to use
     */
    public EntityProxyFactory(PhysicsEngine physicsEngine) {
        this.physicsEngine = physicsEngine;
        SLogger.log(this, "EntityProxyFactory initialized");
    }

    /**
     * Creates an EntityProxy for the given entity.
     *
     * @param entity The entity to create a proxy for
     * @return The created proxy
     */
    public EntityProxy createProxy(Entity entity) {
        // Create or get a collision shape for this entity
        CollisionShape shape = createCollisionShapeForEntity(entity);

        // Create the proxy
        EntityProxy proxy = new EntityProxy(entity, shape);

        // Add to physics world if needed
        addProxyToPhysicsWorld(proxy);

        RigidBody rigidBody = proxy.getRigidBody();
        if (rigidBody != null && rigidBody.getBroadphaseHandle() != null) {
            rigidBody.getBroadphaseHandle().collisionFilterGroup = COLLISION_GROUP_ENTITY;
            rigidBody.getBroadphaseHandle().collisionFilterMask = COLLISION_MASK_ENTITY;
        }

        return proxy;
    }

    /**
     * Creates a collision shape for an entity.
     * Uses shape caching for common entity types.
     *
     * @param entity The entity to create a shape for
     * @return The collision shape
     */
    private CollisionShape createCollisionShapeForEntity(Entity entity) {
        // Create a key for caching
        EntityShapeKey key = new EntityShapeKey(entity);

        // Check cache first
        if (shapeCache.containsKey(key)) {
            return shapeCache.get(key);
        }

        // Create appropriate shape based on entity type
        CollisionShape shape;

        if (entity instanceof PlayerEntity) {
            // Players get a compound shape with special handling
            shape = createPlayerShape((PlayerEntity) entity);
        } else if (entity instanceof LivingEntity) {
            // Living entities get a compound shape
            shape = createLivingEntityShape((LivingEntity) entity);
        } else if (entity instanceof MinecartEntity) {
            // Minecarts get a special shape
            shape = createMinecartShape((MinecartEntity) entity);
        } else if (entity instanceof BoatEntity) {
            // Boats get a special shape
            shape = createBoatShape((BoatEntity) entity);
        } else if (entity instanceof ArmorStandEntity) {
            // Armor stands get a special shape
            shape = createArmorStandShape((ArmorStandEntity) entity);
        } else {
            // Default to a box shape based on bounding box
            shape = createBoxShapeFromBoundingBox(entity.getBoundingBox());
        }

        // Cache the shape if it's for a common entity type
        if (shouldCacheShapeForEntity(entity)) {
            shapeCache.put(key, shape);
        }

        return shape;
    }

    /**
     * Determines if a shape should be cached for an entity type.
     * Only cache shapes for common, unchanging entity types.
     */
    private boolean shouldCacheShapeForEntity(Entity entity) {
        // Don't cache shapes for entities whose size can change
        if (entity instanceof PlayerEntity) {
            return false;
        }

        // Cache shapes for common mobs and entities
        return true;
    }

    /**
     * Creates a box shape from a Minecraft bounding box.
     */
    private CollisionShape createBoxShapeFromBoundingBox(Box box) {
        // Calculate half extents
        float halfWidth = (float) ((box.maxX - box.minX) * 0.5f);
        float halfHeight = (float) ((box.maxY - box.minY) * 0.5f);
        float halfDepth = (float) ((box.maxZ - box.minZ) * 0.5f);

        // Create and return the shape
        return new BoxShape(new Vector3f(halfWidth, halfHeight, halfDepth));
    }

    /**
     * Creates a box shape for a player entity.
     * Uses a simple BoxShape instead of CompoundShape to ensure it works with convexSweepTest.
     */
    private CollisionShape createPlayerShape(PlayerEntity player) {
        Box box = player.getBoundingBox();
        return createBoxShapeFromBoundingBox(box);
    }

    /**
     * Creates a compound shape for a living entity.
     */
    private CollisionShape createLivingEntityShape(LivingEntity entity) {
        // For most living entities, a simple box shape works well
        Box box = entity.getBoundingBox();
        return createBoxShapeFromBoundingBox(box);
    }

    /**
     * Creates a shape for a minecart entity.
     */
    private CollisionShape createMinecartShape(MinecartEntity entity) {
        // For minecarts, use a box shape
        Box box = entity.getBoundingBox();
        return createBoxShapeFromBoundingBox(box);
    }

    /**
     * Creates a shape for a boat entity.
     */
    private CollisionShape createBoatShape(BoatEntity entity) {
        // For boats, use a box shape
        Box box = entity.getBoundingBox();
        return createBoxShapeFromBoundingBox(box);
    }

    /**
     * Creates a shape for an armor stand entity.
     */
    private CollisionShape createArmorStandShape(ArmorStandEntity entity) {
        // For armor stands, use a slim box shape
        Box box = entity.getBoundingBox();
        float halfWidth = (float) ((box.maxX - box.minX) * 0.3f);
        float halfHeight = (float) ((box.maxY - box.minY) * 0.5f);
        float halfDepth = (float) ((box.maxZ - box.minZ) * 0.3f);

        return new BoxShape(new Vector3f(halfWidth, halfHeight, halfDepth));
    }

    /**
     * Adds an EntityProxy to the physics world.
     */
    private void addProxyToPhysicsWorld(EntityProxy proxy) {
        DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();

        if (dynamicsWorld != null) {
            synchronized (physicsEngine.getPhysicsLock()) {
                dynamicsWorld.addCollisionObject(proxy.getCollisionObject());
            }
        }
    }

    /**
     * Removes an EntityProxy from the physics world.
     */
    public void removeProxyFromPhysicsWorld(EntityProxy proxy) {
        DynamicsWorld dynamicsWorld = physicsEngine.getDynamicsWorld();

        if (dynamicsWorld != null) {
            synchronized (physicsEngine.getPhysicsLock()) {
                dynamicsWorld.removeCollisionObject(proxy.getCollisionObject());
            }
        }
    }

    /**
     * Clears the shape cache.
     */
    public void clearCache() {
        shapeCache.clear();
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    /**
     * Key class for caching entity collision shapes.
     */
    private static class EntityShapeKey {
        private final EntityType<?> entityType;
        private final float width;
        private final float height;

        public EntityShapeKey(Entity entity) {
            this.entityType = entity.getType();
            Box box = entity.getBoundingBox();
            this.width = (float) (box.maxX - box.minX);
            this.height = (float) (box.maxY - box.minY);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EntityShapeKey that = (EntityShapeKey) o;
            return Float.compare(that.width, width) == 0 &&
                    Float.compare(that.height, height) == 0 &&
                    entityType.equals(that.entityType);
        }

        @Override
        public int hashCode() {
            int result = entityType.hashCode();
            result = 31 * result + Float.floatToIntBits(width);
            result = 31 * result + Float.floatToIntBits(height);
            return result;
        }
    }
}