package net.stardance.debug;

import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.collision.shapes.ConvexShape;
import com.bulletphysics.dynamics.RigidBody;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalGrid;
import net.stardance.physics.PhysicsEngine;
import net.stardance.render.DebugRenderer;
import net.stardance.Stardance;

import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.Set;

/**
 * Debugging tool for visualizing collision detection.
 */
public class CollisionDebugger {

    // Constants
    private static final int LINE_DURATION_TICKS = 200; // How long lines remain visible (10 seconds at 20 TPS)
    private static final float RAY_LENGTH = 10.0f; // Length of the ray in blocks
    private static final float LINE_WIDTH = 0.02f; // Width of debug lines

    // Color constants
    private static final int COLOR_RAY = 0xFF00FF00; // Green
    private static final int COLOR_HITBOX = 0xFFFFFF00; // Yellow
    private static final int COLOR_DEFLECTION = 0xFF00FFFF; // Cyan
    private static final int COLOR_CONTACT = 0xFFFF0000; // Red
    private static final int COLOR_AFTER = 0xFF0000FF; // Blue
    private static final int COLOR_GRID = 0xFFFF00FF; // Purple

    /**
     * Performs a visual debug test of collision detection.
     *
     * @param source The command source
     * @return 1 if successful, 0 if failed
     */
    public static int debugCollision(FabricClientCommandSource source) {
        PlayerEntity player = source.getPlayer();

        // Get player's eye position and look vector
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVector();

        // Create the full-length ray
        Vec3d rayEnd = eyePos.add(lookVec.multiply(RAY_LENGTH));

        // Draw the initial ray
        DebugRenderer.addRay(eyePos, lookVec, RAY_LENGTH, COLOR_RAY, LINE_WIDTH, LINE_DURATION_TICKS);

        // Draw the player's current hitbox
        drawHitbox(player.getBoundingBox(), COLOR_HITBOX, LINE_DURATION_TICKS);

        // Show a marker at the player's position
        DebugRenderer.addCrosshair(new Vec3d(player.getX(), player.getY() + player.getHeight() / 2, player.getZ()),
                COLOR_HITBOX, 0.2f, LINE_WIDTH, LINE_DURATION_TICKS);

        // Get physics engine
        PhysicsEngine engine = Stardance.engineManager.getEngine(player.getWorld());
        if (engine == null) {
            source.sendFeedback(Text.literal("§cPhysics engine not available"));
            return 0;
        }

        // DIAGNOSTIC: Check active grids
        Set<LocalGrid> grids = engine.getGrids();
        source.sendFeedback(Text.literal("§eActive grids: " + grids.size()));

        // Visualize all grid AABBs
        for (LocalGrid grid : grids) {
            Vector3f minAabb = new Vector3f();
            Vector3f maxAabb = new Vector3f();
            grid.getAABB(minAabb, maxAabb);

            Box gridBox = new Box(
                    minAabb.x, minAabb.y, minAabb.z,
                    maxAabb.x, maxAabb.y, maxAabb.z
            );

            drawHitbox(gridBox, COLOR_GRID, LINE_DURATION_TICKS);

            // Show grid ID for debugging
            source.sendFeedback(Text.literal("§eGrid: " + grid.getGridId() +
                    " at (" + Math.round(minAabb.x) + "," + Math.round(minAabb.y) + "," + Math.round(minAabb.z) + ")"));

            // DIAGNOSTIC: Check grid rigid body
            RigidBody gridBody = grid.getRigidBody();
            if (gridBody == null) {
                source.sendFeedback(Text.literal("§c- Grid has no rigid body!"));
            } else {
                source.sendFeedback(Text.literal("§a- Grid has rigid body"));

                // DIAGNOSTIC: Check if grid is in dynamics world
                boolean isInWorld = engine.getDynamicsWorld().getCollisionObjectArray().contains(gridBody);
                if (!isInWorld) {
                    source.sendFeedback(Text.literal("§c- Grid rigid body is NOT in dynamics world!"));
                } else {
                    source.sendFeedback(Text.literal("§a- Grid rigid body is in dynamics world"));
                }
            }
        }

        // CRITICAL FIX: Force track player BEFORE doing anything else
        source.sendFeedback(Text.literal("§aForce tracking player..."));
        engine.getEntityPhysicsManager().forceTrackEntity(player);

        // Wait one tick to ensure the proxy is created
        try {
            Thread.sleep(50); // 50ms should be enough for a tick
        } catch (InterruptedException e) {
            // Ignore
        }

        // DIAGNOSTIC: Check if player is tracked and has a proxy
        Map<Entity, EntityProxy> entityProxies = engine.getEntityPhysicsManager().getEntityProxies();
        EntityProxy playerProxy = entityProxies.get(player);

        if (playerProxy == null) {
            source.sendFeedback(Text.literal("§cPlayer does not have a physics proxy!"));
            return 0;
        }

        source.sendFeedback(Text.literal("§aPlayer has a physics proxy"));

        // DIAGNOSTIC: Check player collision object
        if (playerProxy.getCollisionObject() == null) {
            source.sendFeedback(Text.literal("§cPlayer proxy has no collision object!"));
            return 0;
        }

        source.sendFeedback(Text.literal("§aPlayer proxy has a collision object"));

        // DIAGNOSTIC: Check player collision shape
        CollisionShape playerShape = playerProxy.getCollisionShape();
        if (playerShape == null) {
            source.sendFeedback(Text.literal("§cPlayer proxy has no collision shape!"));
            return 0;
        }

        source.sendFeedback(Text.literal("§aPlayer proxy has a collision shape: " + playerShape.getClass().getSimpleName()));

        // DIAGNOSTIC: Check if shape is convex
        if (!(playerShape instanceof ConvexShape)) {
            source.sendFeedback(Text.literal("§cPlayer shape is not a convex shape!"));
            return 0;
        }

        source.sendFeedback(Text.literal("§aPlayer shape is convex"));

        // Create a movement vector
        Vec3d movement = lookVec.multiply(RAY_LENGTH);

        // Perform the sweep test
        ContactDetector contactDetector = engine.getEntityPhysicsManager().getContactDetector();
        ContactDetector.SweepResult result = contactDetector.convexSweepTest(player, movement, entityProxies);

        if (result == null) {
            // No collision detected
            source.sendFeedback(Text.literal("§eNo collision detected"));

            // DIAGNOSTIC: Additional debugging for no collision
            if (grids.isEmpty()) {
                source.sendFeedback(Text.literal("§c⚠ No grids to collide with!"));
            } else {
                source.sendFeedback(Text.literal("§e⚠ Grids exist but no collision detected"));
            }

            // DIAGNOSTIC: Try alternative collision check method
            source.sendFeedback(Text.literal("§eTrying alternative collision detection..."));

            for (LocalGrid grid : grids) {
                RigidBody gridBody = grid.getRigidBody();
                if (gridBody == null) continue;

                // Get the ray endpoints
                Vector3f rayStart = new Vector3f((float)eyePos.x, (float)eyePos.y, (float)eyePos.z);
                Vector3f rayDir = new Vector3f((float)lookVec.x, (float)lookVec.y, (float)lookVec.z);
                rayDir.normalize();
                rayDir.scale(RAY_LENGTH);

                // Get grid AABB
                Vector3f minAabb = new Vector3f();
                Vector3f maxAabb = new Vector3f();
                grid.getAABB(minAabb, maxAabb);

                // Simple ray-AABB test
                float tNear = Float.NEGATIVE_INFINITY;
                float tFar = Float.POSITIVE_INFINITY;

                for (int i = 0; i < 3; i++) {
                    float dim = i == 0 ? rayDir.x : (i == 1 ? rayDir.y : rayDir.z);
                    float start = i == 0 ? rayStart.x : (i == 1 ? rayStart.y : rayStart.z);
                    float min = i == 0 ? minAabb.x : (i == 1 ? minAabb.y : minAabb.z);
                    float max = i == 0 ? maxAabb.x : (i == 1 ? maxAabb.y : maxAabb.z);

                    if (Math.abs(dim) < 1e-6) {
                        // Ray parallel to slab
                        if (start < min || start > max) {
                            // No hit
                            continue;
                        }
                    } else {
                        // Compute intersection parameters
                        float ood = 1.0f / dim;
                        float t1 = (min - start) * ood;
                        float t2 = (max - start) * ood;

                        // Make t1 the near intersection
                        if (t1 > t2) {
                            float temp = t1;
                            t1 = t2;
                            t2 = temp;
                        }

                        // Update tNear and tFar
                        tNear = Math.max(tNear, t1);
                        tFar = Math.min(tFar, t2);

                        // Check for no overlap
                        if (tNear > tFar) {
                            continue;
                        }
                    }
                }

                // If tNear <= tFar, we have an intersection
                if (tNear <= tFar && tNear >= 0 && tNear <= RAY_LENGTH) {
                    source.sendFeedback(Text.literal("§a✓ Alternative test found collision with grid: " + grid.getGridId()));

                    // Draw the intersection point
                    Vector3f hitPoint = new Vector3f(rayStart);
                    Vector3f scaledDir = new Vector3f(rayDir);
                    scaledDir.scale(tNear);
                    hitPoint.add(scaledDir);

                    Vec3d hitPointVec = new Vec3d(hitPoint.x, hitPoint.y, hitPoint.z);
                    DebugRenderer.addCrosshair(hitPointVec, COLOR_CONTACT, 0.2f, LINE_WIDTH * 2, LINE_DURATION_TICKS);

                    // DIAGNOSTIC: Check why convexSweepTest didn't detect this
                    source.sendFeedback(Text.literal("§c⚠ convexSweepTest failed to detect this collision!"));
                    source.sendFeedback(Text.literal("§e- Distance to hit: " + (tNear * RAY_LENGTH) + " blocks"));
                }
            }

            return 1;
        }

        // Collision detected!
        source.sendFeedback(Text.literal("§aCollision detected!"));

        // Get collision information
        float timeOfImpact = result.getTimeOfImpact();
        Vector3f hitNormal = result.getHitNormal();
        Vector3f hitPoint = result.getHitPoint();

        // Calculate safe movement (just before collision)
        double safetyMargin = 0.01;
        double safeTime = Math.max(0, timeOfImpact - safetyMargin);
        Vec3d safeMovement = movement.multiply(safeTime);

        // Calculate where the player would be after safe movement
        Box safeBox = player.getBoundingBox().offset(safeMovement);

        // Calculate remaining movement
        double remainingTime = 1.0 - safeTime;
        Vec3d remainingMovement = movement.multiply(remainingTime);

        // Visualize the safe movement
        Vec3d safeEnd = eyePos.add(safeMovement);
        DebugRenderer.addLine(eyePos, safeEnd, COLOR_AFTER, LINE_WIDTH, LINE_DURATION_TICKS);

        // Draw the player's hitbox after safe movement
        drawHitbox(safeBox, COLOR_AFTER, LINE_DURATION_TICKS);

        // Show the contact point
        Vec3d contactPoint = new Vec3d(hitPoint.x, hitPoint.y, hitPoint.z);
        DebugRenderer.addCrosshair(contactPoint, COLOR_CONTACT, 0.2f, LINE_WIDTH * 2, LINE_DURATION_TICKS);

        // Create the normal vector for visualization
        Vec3d normalVec = new Vec3d(hitNormal.x, hitNormal.y, hitNormal.z);
        DebugRenderer.addRay(contactPoint, normalVec, 1.0, COLOR_CONTACT, LINE_WIDTH, LINE_DURATION_TICKS);

        // Calculate deflection
        Vec3d deflection = calculateDeflection(movement, normalVec, timeOfImpact);

        // Visualize deflection
        DebugRenderer.addLine(safeEnd, safeEnd.add(deflection), COLOR_DEFLECTION, LINE_WIDTH, LINE_DURATION_TICKS);

        // Draw final hitbox after deflection
        Box deflectedBox = safeBox.offset(deflection);
        drawHitbox(deflectedBox, COLOR_DEFLECTION, LINE_DURATION_TICKS);

        // Send feedback about the collision
        source.sendFeedback(Text.literal(String.format(
                "§aTime of impact: §f%.4f\n§aNormal: §f(%.2f, %.2f, %.2f)\n§aContact: §f(%.2f, %.2f, %.2f)",
                timeOfImpact,
                hitNormal.x, hitNormal.y, hitNormal.z,
                hitPoint.x, hitPoint.y, hitPoint.z)));

        // DIAGNOSTIC: What did we hit?
        if (result.getGrid() != null) {
            source.sendFeedback(Text.literal("§aHit grid: " + result.getGrid().getGridId()));
        } else if (result.getCollidedEntity() != null) {
            source.sendFeedback(Text.literal("§aHit entity: " + result.getCollidedEntity().getEntityName()));
        }

        return 1;
    }

    /**
     * Calculates the deflected movement after a collision.
     * This replicates the logic in MixinEntity.adjustMovementForGridCollisions.
     */
    private static Vec3d calculateDeflection(Vec3d fullMovement, Vec3d normal, float timeOfImpact) {
        // Calculate remaining time
        double safetyMargin = 0.01;
        double safeTime = Math.max(0, timeOfImpact - safetyMargin);
        double remainingTime = 1.0 - safeTime;

        if (remainingTime < 0.01) {
            return Vec3d.ZERO;
        }

        // Calculate remaining movement
        Vec3d remainingMovement = fullMovement.multiply(remainingTime);

        // Convert to Vector3d for more precise math
        org.joml.Vector3d movementVec = new org.joml.Vector3d(
                remainingMovement.x,
                remainingMovement.y,
                remainingMovement.z
        );

        org.joml.Vector3d normalVec = new org.joml.Vector3d(normal.x, normal.y, normal.z);
        normalVec.normalize();

        // Calculate dot product
        double dot = movementVec.dot(normalVec);

        // Only deflect if moving into the surface
        if (dot < 0) {
            // Remove normal component to get tangential component
            org.joml.Vector3d normalComponent = new org.joml.Vector3d(normalVec);
            normalComponent.mul(dot);

            org.joml.Vector3d tangentialComponent = new org.joml.Vector3d(movementVec);
            tangentialComponent.sub(normalComponent);

            // Calculate tangential length
            double tangentialLength = tangentialComponent.length();

            // If tangential component is negligible, no deflection
            if (tangentialLength < 0.0001) {
                return Vec3d.ZERO;
            } else {
                // Normalize tangential component
                tangentialComponent.mul(1.0 / tangentialLength);

                // Scale to appropriate magnitude (80% of remaining length)
                double deflectionMagnitude = Math.min(
                        remainingMovement.length() * 0.8,  // 80% of remaining length
                        tangentialLength                   // Original tangential length
                );

                tangentialComponent.mul(deflectionMagnitude);

                // Convert back to Vec3d
                return new Vec3d(
                        tangentialComponent.x,
                        tangentialComponent.y,
                        tangentialComponent.z
                );
            }
        } else {
            // If not moving into surface, use full remaining movement
            return remainingMovement;
        }
    }

    /**
     * Draws a hitbox with debug lines.
     */
    private static void drawHitbox(Box box, int color, int durationTicks) {
        DebugRenderer.addBox(box, color, LINE_WIDTH, durationTicks);
    }
}