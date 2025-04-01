package net.stardance.physics.entity;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.stardance.physics.SubchunkCoordinates;
import net.stardance.physics.SubchunkManager;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which entities are in which subchunks.
 * Manages the association between entities and subchunks for physics optimization.
 */
public class EntityTracker implements ILoggingControl {
    // Core reference
    private final SubchunkManager subchunkManager;

    // Entity tracking
    private final Map<SubchunkCoordinates, Set<Entity>> entitiesBySubchunk = new HashMap<>();
    private final Map<Entity, Set<SubchunkCoordinates>> subchunksByEntity = new HashMap<>();
    private final Set<Entity> trackedEntities = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new EntityTracker with the given subchunk manager.
     */
    public EntityTracker(SubchunkManager subchunkManager) {
        this.subchunkManager = subchunkManager;

        SLogger.log(this, "EntityTracker initialized");
    }

    /**
     * Updates entity tracking for all active subchunks.
     * Called once per tick to maintain accurate entity tracking.
     */
    public void updateEntitiesInSubchunks(ServerWorld world) {
        // Clear entities that no longer exist
        cleanupRemovedEntities();

        // For each active subchunk, find entities within its bounds
        for (SubchunkCoordinates coords : subchunkManager.getActiveSubchunks()) {
            // Convert subchunk coordinates to world coordinates
            int minX = coords.x * 16;
            int minY = coords.y * 16;
            int minZ = coords.z * 16;

            // Create a box for this subchunk
            Box subchunkBox = new Box(
                    minX, minY, minZ,
                    minX + 16, minY + 16, minZ + 16
            );

            // Find all entities that intersect this box
            List<Entity> entitiesInBox = world.getEntitiesByClass(
                    Entity.class,
                    subchunkBox,
                    entity -> !entity.isSpectator() && !entity.noClip
            );

            // Update our tracking maps
            Set<Entity> entitiesInSubchunk = entitiesBySubchunk.computeIfAbsent(
                    coords, k -> new HashSet<>()
            );

            // Add all entities in this subchunk
            for (Entity entity : entitiesInBox) {
                // Add to tracked entities set
                trackedEntities.add(entity);

                // Add to subchunk's entity set
                entitiesInSubchunk.add(entity);

                // Update reverse mapping
                Set<SubchunkCoordinates> entitySubchunks = subchunksByEntity.computeIfAbsent(
                        entity, k -> new HashSet<>()
                );
                entitySubchunks.add(coords);
            }
        }

        SLogger.log(this, "Updated entity tracking: " +
                trackedEntities.size() + " entities across " +
                entitiesBySubchunk.size() + " subchunks");
    }

    /**
     * Cleans up tracking data for removed entities.
     */
    private void cleanupRemovedEntities() {
        // Remove from trackedEntities
        trackedEntities.removeIf(Entity::isRemoved);

        // Remove from subchunksByEntity
        subchunksByEntity.entrySet().removeIf(entry -> entry.getKey().isRemoved());

        // Remove from entitiesBySubchunk
        for (Set<Entity> entities : entitiesBySubchunk.values()) {
            entities.removeIf(Entity::isRemoved);
        }
    }

    /**
     * Gets all entities currently being tracked.
     */
    public Set<Entity> getTrackedEntities() {
        return Collections.unmodifiableSet(trackedEntities);
    }

    /**
     * Gets all entities in a specific subchunk.
     */
    public Set<Entity> getEntitiesInSubchunk(SubchunkCoordinates coords) {
        return entitiesBySubchunk.getOrDefault(coords, Collections.emptySet());
    }

    /**
     * Gets all subchunks an entity is in.
     */
    public Set<SubchunkCoordinates> getSubchunksForEntity(Entity entity) {
        return subchunksByEntity.getOrDefault(entity, Collections.emptySet());
    }

    /**
     * Checks if an entity is currently being tracked.
     */
    public boolean isTracked(Entity entity) {
        return trackedEntities.contains(entity);
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