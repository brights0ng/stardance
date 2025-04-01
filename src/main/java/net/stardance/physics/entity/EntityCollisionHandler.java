package net.stardance.physics.entity;

import com.bulletphysics.dynamics.DynamicsWorld;
import net.minecraft.entity.Entity;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages entity collision objects and handles collision callbacks.
 * Acts as a bridge between Minecraft entities and JBullet physics.
 */
public class EntityCollisionHandler implements ILoggingControl {
    // Core references
    private final DynamicsWorld dynamicsWorld;
    private final EntityTracker entityTracker;

    // Entity proxies
    private final Map<Entity, EntityProxy> entityProxies = new ConcurrentHashMap<>();

    /**
     * Creates a new EntityCollisionHandler.
     */
    public EntityCollisionHandler(DynamicsWorld dynamicsWorld, EntityTracker entityTracker) {
        this.dynamicsWorld = dynamicsWorld;
        this.entityTracker = entityTracker;

        SLogger.log(this, "EntityCollisionHandler initialized");
    }

    /**
     * Updates proxies for all tracked entities.
     * Should be called each tick before physics simulation.
     */
    public void updateEntityProxies() {
        // For all entities in active subchunks
        for (Entity entity : entityTracker.getTrackedEntities()) {
            // Skip non-physical entities
            if (entity.isSpectator() || entity.noClip) {
                continue;
            }

            // Get or create proxy
            EntityProxy proxy = entityProxies.get(entity);
            if (proxy == null) {
                proxy = EntityProxyFactory.createProxy(entity, dynamicsWorld);
                entityProxies.put(entity, proxy);
                SLogger.log(this, "Created new proxy for entity: " + entity.getUuid());
            } else {
                // Update existing proxy
                proxy.update();
            }
        }

        // Remove proxies for entities no longer tracked
        entityProxies.entrySet().removeIf(entry -> {
            Entity entity = entry.getKey();
            if (entity.isRemoved() || !entityTracker.isTracked(entity)) {
                entry.getValue().remove();
                SLogger.log(this, "Removed proxy for entity: " + entity.getUuid());
                return true;
            }
            return false;
        });
    }

    /**
     * Gets all entity proxies.
     */
    public Map<Entity, EntityProxy> getEntityProxies() {
        return entityProxies;
    }

    /**
     * Gets a proxy for a specific entity.
     */
    public EntityProxy getProxyForEntity(Entity entity) {
        return entityProxies.get(entity);
    }

    /**
     * Gets the dynamics world.
     */
    public DynamicsWorld getDynamicsWorld() {
        return dynamicsWorld;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }
}