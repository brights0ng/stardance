package net.starlight.stardance.physics;

import com.bulletphysics.dynamics.DynamicsWorld;
import net.minecraft.server.level.ServerLevel;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;  // Assuming you have this logging utility

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SubchunkManager implements ILoggingControl {
    private Map<SubchunkCoordinates, SubchunkMesh> subchunkMeshes = new ConcurrentHashMap<>();
    private Map<SubchunkCoordinates, Integer> referenceCounts = new ConcurrentHashMap<>();
    private DynamicsWorld dynamicsWorld;
    private ServerLevel world;

    public SubchunkManager(DynamicsWorld dynamicsWorld, ServerLevel world) {
        this.dynamicsWorld = dynamicsWorld;
        this.world = world;
        SLogger.log(this, "SubchunkManager created for world: " + world);
    }

    public void activateSubchunk(SubchunkCoordinates coords) {
        SLogger.log(this, "Activating subchunk at coords: " + coords);
        SubchunkMesh mesh = subchunkMeshes.computeIfAbsent(coords, SubchunkMesh::new);
        mesh.generateMesh(world);
        referenceCounts.merge(coords, 1, Integer::sum);

        if (referenceCounts.get(coords) == 1) {
            // First reference, add to physics world
            mesh.addToPhysicsWorld(dynamicsWorld);
        }
    }

    public void deactivateSubchunk(SubchunkCoordinates coords) {
        SLogger.log(this, "Deactivating subchunk at coords: " + coords);
        if (referenceCounts.containsKey(coords)) {
            int count = referenceCounts.merge(coords, -1, Integer::sum);
            if (count <= 0) {
                // No more references, remove from physics world
                SubchunkMesh mesh = subchunkMeshes.get(coords);
                mesh.removeFromPhysicsWorld(dynamicsWorld);
                referenceCounts.remove(coords);
            }
        }
    }

    public void updateDirtySubchunks() {
        SLogger.log(this, "Updating dirty subchunks.");
        for (SubchunkMesh mesh : subchunkMeshes.values()) {
            if (mesh.isDirty() && mesh.isActive()) {
                SLogger.log(this, "Regenerating mesh for subchunk at coords: " + mesh.getRigidBody());
                mesh.generateMesh(world);
            }
        }
    }

    public void markSubchunkDirty(SubchunkCoordinates coords) {
        SLogger.log(this, "Marking subchunk at coords " + coords + " as dirty.");
        SubchunkMesh mesh = subchunkMeshes.get(coords);
        if (mesh != null) {
            mesh.markDirty();
        }
    }

    /**
     * Checks if a subchunk is currently active.
     */
    public boolean isSubchunkActive(SubchunkCoordinates coords) {
        return referenceCounts.containsKey(coords) && referenceCounts.get(coords) > 0;
    }

    /**
     * Gets all currently active subchunks.
     */
    public Set<SubchunkCoordinates> getActiveSubchunks() {
        return referenceCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
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
