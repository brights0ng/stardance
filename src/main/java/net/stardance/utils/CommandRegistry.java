package net.stardance.utils;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.stardance.physics.PhysicsEngine;
import net.stardance.physics.SubchunkCoordinates;
import net.stardance.physics.entity.*;
import net.stardance.render.CollisionShapeRenderer;
import net.stardance.render.DebugRenderer;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.stardance.Stardance.*;

public class CommandRegistry implements ILoggingControl {
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }
    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    public void init(){
        // Register the toggle command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("togglephysicsdebug")
                            .executes(context -> {
                                // Toggle the renderDebug boolean
                                CollisionShapeRenderer.ENABLED = !CollisionShapeRenderer.ENABLED;
                                // Send feedback to the player
                                context.getSource().sendFeedback(
                                        Text.literal("Debug toggled"));

                                return 1; // Command success status
                            })
            );

            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("importSchem")
                            .executes(context -> {
                                SLogger.log(this,"Step 1");
                                org.joml.Vector3f pos = context.getSource().getPosition().toVector3f();
                                try {
                                    schemManager.importSchematic("rat.schem", new Vector3d(pos.x, pos.y, pos.z), serverInstance.getWorld(context.getSource().getWorld().getRegistryKey()));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                return 1; // Command success status
                            })
            );

            // Command to display tracked entities
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("showTrackedEntities")
                            .executes(context -> {
                                if (serverInstance == null) {
                                    context.getSource().sendFeedback(Text.literal("Server not available"));
                                    return 0;
                                }

                                ServerWorld world = serverInstance.getWorld(
                                        context.getSource().getWorld().getRegistryKey());

                                // Get physics engine for this world
                                PhysicsEngine engine = engineManager.getEngine(world);
                                if (engine == null) {
                                    context.getSource().sendFeedback(Text.literal("No physics engine for current world"));
                                    return 0;
                                }

                                // Access entity tracker
                                EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();
                                Set<Entity> allEntities = entityPhysics.getTrackedEntities();

                                // Display summary
                                context.getSource().sendFeedback(
                                        Text.literal("§6Tracking " + allEntities.size() + " entities:"));

                                // Show entities by subchunk
                                for (SubchunkCoordinates coords : engine.getSubchunkManager().getActiveSubchunks()) {
                                    Set<Entity> entitiesInSubchunk = entityPhysics.getEntitiesInSubchunk(coords);
                                    if (!entitiesInSubchunk.isEmpty()) {
                                        context.getSource().sendFeedback(
                                                Text.literal("§7Subchunk [" + coords.x + ", " + coords.y + ", " + coords.z +
                                                        "]: §f" + entitiesInSubchunk.size() + " entities"));

                                        // List each entity with details (limit to first 5 per subchunk to avoid spam)
                                        int count = 0;
                                        for (Entity entity : entitiesInSubchunk) {
                                            if (count++ >= 5) {
                                                context.getSource().sendFeedback(
                                                        Text.literal("§7  ... and " + (entitiesInSubchunk.size() - 5) + " more"));
                                                break;
                                            }

                                            String entityInfo = String.format("§7  %s §8at [%.1f, %.1f, %.1f]",
                                                    entity.getType().getName().getString(),
                                                    entity.getX(), entity.getY(), entity.getZ());
                                            context.getSource().sendFeedback(Text.literal(entityInfo));
                                        }
                                    }
                                }

                                return 1; // Command success status
                            })
            );

            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("showContacts")
                            .executes(context -> {
                                if (serverInstance == null) {
                                    context.getSource().sendFeedback(Text.literal("Server not available"));
                                    return 0;
                                }

                                ServerWorld world = serverInstance.getWorld(
                                        context.getSource().getWorld().getRegistryKey());

                                // Get physics engine for this world
                                PhysicsEngine engine = engineManager.getEngine(world);
                                if (engine == null) {
                                    context.getSource().sendFeedback(Text.literal("No physics engine for current world"));
                                    return 0;
                                }

                                // Get entity physics manager
                                EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();
                                Set<Entity> entitiesWithContacts = entityPhysics.getContactDetector().getEntitiesWithContacts();

                                // Display summary
                                context.getSource().sendFeedback(
                                        Text.literal("§6Found " + entitiesWithContacts.size() + " entities with contacts:"));

                                // Show entities and their contacts
                                for (Entity entity : entitiesWithContacts) {
                                    List<Contact> contacts = entityPhysics.getContactDetector().getContactsForEntity(entity);

                                    context.getSource().sendFeedback(
                                            Text.literal("§7Entity: " + entity.getType().getName().getString() +
                                                    " §f(" + contacts.size() + " contacts)"));

                                    // Show first 3 contacts with details
                                    int count = 0;
                                    for (Contact contact : contacts) {
                                        if (count++ >= 3) {
                                            context.getSource().sendFeedback(
                                                    Text.literal("§7  ... and " + (contacts.size() - 3) + " more"));
                                            break;
                                        }

                                        String contactInfo = String.format("§7  Depth: §f%.2f §7Normal: §f[%.2f, %.2f, %.2f]",
                                                contact.getPenetrationDepth(),
                                                contact.getContactNormal().x,
                                                contact.getContactNormal().y,
                                                contact.getContactNormal().z);
                                        context.getSource().sendFeedback(Text.literal(contactInfo));
                                    }
                                }

                                return 1; // Command success status
                            })
            );

            // NEW COMMAND: Debug Entity Proxies
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("sdebug")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("getEntityProxies")
                                    .executes(context -> {
                                        if (serverInstance == null) {
                                            context.getSource().sendFeedback(Text.literal("§cServer not available"));
                                            return 0;
                                        }

                                        ServerWorld world = serverInstance.getWorld(
                                                context.getSource().getWorld().getRegistryKey());

                                        PhysicsEngine engine = engineManager.getEngine(world);
                                        if (engine == null) {
                                            context.getSource().sendFeedback(Text.literal("§cNo physics engine for current world"));
                                            return 0;
                                        }

                                        EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();
                                        Map<Entity, EntityProxy> proxies = entityPhysics.getEntityProxies();

                                        // Display detailed information
                                        context.getSource().sendFeedback(
                                                Text.literal("§6=== Entity Proxies (" + proxies.size() + " total) ==="));

                                        // Show each proxy with details
                                        proxies.forEach((entity, proxy) -> {
                                            // Header for this entity
                                            context.getSource().sendFeedback(
                                                    Text.literal("§e" + entity.getType().getName().getString() +
                                                            " §7(ID: " + entity.getId() + ")"));

                                            // Position and bounds
                                            context.getSource().sendFeedback(
                                                    Text.literal("  §7Position: §f[" +
                                                            String.format("%.2f, %.2f, %.2f",
                                                                    entity.getX(), entity.getY(), entity.getZ()) + "]"));

                                            // Motion and status
                                            context.getSource().sendFeedback(
                                                    Text.literal("  §7Motion: §f[" +
                                                            String.format("%.2f, %.2f, %.2f",
                                                                    entity.getVelocity().x,
                                                                    entity.getVelocity().y,
                                                                    entity.getVelocity().z) + "]"));

                                            // Entity status
                                            context.getSource().sendFeedback(
                                                    Text.literal("  §7Status: " +
                                                            "§fActive: " + proxy.isActive() +
                                                            " | OnGround: " + entity.isOnGround() +
                                                            " | NoClip: " + entity.noClip));

                                            // Bounding box
                                            context.getSource().sendFeedback(
                                                    Text.literal("  §7BoundingBox: §f" +
                                                            String.format("[%.2f,%.2f,%.2f] to [%.2f,%.2f,%.2f]",
                                                                    entity.getBoundingBox().minX,
                                                                    entity.getBoundingBox().minY,
                                                                    entity.getBoundingBox().minZ,
                                                                    entity.getBoundingBox().maxX,
                                                                    entity.getBoundingBox().maxY,
                                                                    entity.getBoundingBox().maxZ)));
                                        });

                                        return 1; // Command success status
                                    }))
            );

            // NEW COMMAND: Debug Contacts
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("sdebug")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("getContacts")
                                    .executes(context -> {
                                        if (serverInstance == null) {
                                            context.getSource().sendFeedback(Text.literal("§cServer not available"));
                                            return 0;
                                        }

                                        ServerWorld world = serverInstance.getWorld(
                                                context.getSource().getWorld().getRegistryKey());

                                        PhysicsEngine engine = engineManager.getEngine(world);
                                        if (engine == null) {
                                            context.getSource().sendFeedback(Text.literal("§cNo physics engine for current world"));
                                            return 0;
                                        }

                                        EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();
                                        Map<Entity, List<Contact>> allContacts = entityPhysics.getContactDetector().collectContacts();

                                        // Display detailed information
                                        int totalContacts = 0;
                                        for (List<Contact> contacts : allContacts.values()) {
                                            totalContacts += contacts.size();
                                        }

                                        context.getSource().sendFeedback(
                                                Text.literal("§6=== Entity Contacts (" + totalContacts +
                                                        " contacts across " + allContacts.size() + " entities) ==="));

                                        // Show each entity's contacts
                                        allContacts.forEach((entity, contacts) -> {
                                            if (contacts.isEmpty()) return;

                                            // Header for this entity
                                            context.getSource().sendFeedback(
                                                    Text.literal("§e" + entity.getType().getName().getString() +
                                                            " §7(ID: " + entity.getId() + ") - " +
                                                            contacts.size() + " contacts"));

                                            // List each contact with detailed info
                                            for (int i = 0; i < contacts.size(); i++) {
                                                Contact contact = contacts.get(i);

                                                String collidedWithStr = contact.isGridContact() ?
                                                        "Grid[" + contact.getGrid().getGridId() + "]" :
                                                        contact.getCollidedWith().getClass().getSimpleName();

                                                // Contact header
                                                context.getSource().sendFeedback(
                                                        Text.literal("  §7Contact #" + (i+1) + " with §f" + collidedWithStr));

                                                // Contact details
                                                Vector3f normal = contact.getContactNormal();
                                                context.getSource().sendFeedback(
                                                        Text.literal("    §7Normal: §f[" +
                                                                String.format("%.2f, %.2f, %.2f",
                                                                        normal.x, normal.y, normal.z) + "]"));

                                                context.getSource().sendFeedback(
                                                        Text.literal("    §7Depth: §f" +
                                                                String.format("%.4f", contact.getPenetrationDepth())));

                                                Vector3f point = contact.getContactPoint();
                                                context.getSource().sendFeedback(
                                                        Text.literal("    §7Point: §f[" +
                                                                String.format("%.2f, %.2f, %.2f",
                                                                        point.x, point.y, point.z) + "]"));

                                                // If grid contact, show grid velocity at point
                                                if (contact.isGridContact()) {
                                                    Vector3f gridVel = contact.getGridVelocityAtContactPoint();
                                                    context.getSource().sendFeedback(
                                                            Text.literal("    §7Grid Velocity: §f[" +
                                                                    String.format("%.2f, %.2f, %.2f",
                                                                            gridVel.x, gridVel.y, gridVel.z) + "]"));
                                                }

                                                // Show if this contact would count as ground
                                                boolean isGround = normal.y > 0.7071f && contact.getPenetrationDepth() > 0.001f;
                                                context.getSource().sendFeedback(
                                                        Text.literal("    §7Ground Contact: " +
                                                                (isGround ? "§aYes" : "§cNo")));
                                            }
                                        });

                                        return 1; // Command success status
                                    }))
            );
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("sdebug")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("forceTrackNearbyEntities")
                                    .executes(context -> {
                                        if (serverInstance == null) {
                                            context.getSource().sendFeedback(Text.literal("§cServer not available"));
                                            return 0;
                                        }

                                        ServerWorld world = serverInstance.getWorld(
                                                context.getSource().getWorld().getRegistryKey());

                                        PhysicsEngine engine = engineManager.getEngine(world);
                                        if (engine == null) {
                                            context.getSource().sendFeedback(Text.literal("§cNo physics engine for current world"));
                                            return 0;
                                        }

                                        // Get player position
                                        org.joml.Vector3f playerPos = context.getSource().getPosition().toVector3f();

                                        // Track all entities within 16 blocks of player
                                        int entitiesTracked = engine.getEntityPhysicsManager().forceTrackNearbyEntities(
                                                new net.minecraft.util.math.Box(
                                                        playerPos.x - 16, playerPos.y - 16, playerPos.z - 16,
                                                        playerPos.x + 16, playerPos.y + 16, playerPos.z + 16
                                                )
                                        );

                                        context.getSource().sendFeedback(
                                                Text.literal("§aForced tracking of " + entitiesTracked + " entities near player."));

                                        return 1; // Command success status
                                    }))
            );
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("sdebug")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("sweepTest")
                                    .executes(context -> {
                                        try {
                                            if (serverInstance == null) {
                                                context.getSource().sendFeedback(Text.literal("§cServer not available"));
                                                return 0;
                                            }

                                            ServerWorld world = serverInstance.getWorld(
                                                    context.getSource().getWorld().getRegistryKey());

                                            PhysicsEngine engine = engineManager.getEngine(world);
                                            if (engine == null) {
                                                context.getSource().sendFeedback(Text.literal("§cNo physics engine for current world"));
                                                return 0;
                                            }

                                            // Get the player entity
                                            Entity player = context.getSource().getPlayer();
                                            if (player == null) {
                                                context.getSource().sendFeedback(Text.literal("§cPlayer entity not found"));
                                                return 0;
                                            }

                                            // Clear any existing debug drawings
                                            DebugRenderer.clear();

                                            // Get look direction and calculate test movement
                                            Vec3d lookDir = player.getRotationVector();
                                            Vec3d testMovement = lookDir.multiply(5.0); // 5 blocks in look direction

                                            // Run a sweep test visualization
                                            visualizeSweepTest(context.getSource(), engine, player, testMovement);

                                            return 1; // Command success status
                                        } catch (Exception e) {
                                            // Log any errors
                                            SLogger.log(this, "Error during sweep test visualization: " + e.getMessage());
                                            e.printStackTrace();

                                            context.getSource().sendFeedback(Text.literal("§cError during sweep test: " + e.getMessage()));
                                            return 0;
                                        }
                                    }))
            );
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("sdebug")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("convexSweep")
                                    .executes(context -> {
                                        try {
                                            if (serverInstance == null) {
                                                context.getSource().sendFeedback(Text.literal("§cServer not available"));
                                                return 0;
                                            }

                                            ServerWorld world = serverInstance.getWorld(
                                                    context.getSource().getWorld().getRegistryKey());

                                            PhysicsEngine engine = engineManager.getEngine(world);
                                            if (engine == null) {
                                                context.getSource().sendFeedback(Text.literal("§cNo physics engine for current world"));
                                                return 0;
                                            }

                                            // Get the player entity
                                            Entity player = context.getSource().getPlayer();
                                            if (player == null) {
                                                context.getSource().sendFeedback(Text.literal("§cPlayer entity not found"));
                                                return 0;
                                            }

                                            // Clear any existing debug drawings
                                            DebugRenderer.clear();

                                            // Get look direction and calculate test movement
                                            Vec3d lookDir = player.getRotationVector();
                                            Vec3d testMovement = lookDir.multiply(5.0); // 5 blocks in look direction

                                            // Run a convex sweep test visualization
                                            visualizeConvexSweepTest(context.getSource(), engine, player, testMovement);

                                            return 1; // Command success status
                                        } catch (Exception e) {
                                            // Log any errors
                                            SLogger.log(this, "Error during convex sweep test visualization: " + e.getMessage());
                                            e.printStackTrace();

                                            context.getSource().sendFeedback(Text.literal("§cError during convex sweep test: " + e.getMessage()));
                                            return 0;
                                        }
                                    }))
            );
            dispatcher.register(
                    LiteralArgumentBuilder.<FabricClientCommandSource>literal("sdebug")
                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("testEntityTracking")
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("nearby")
                                            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("range")
                                                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, 32))
                                                            .executes(context -> {
                                                                return testNearbyEntities(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "radius")
                                                                );
                                                            })
                                                    )
                                            )
                                            .executes(context -> {
                                                // Default radius of 8 blocks
                                                return testNearbyEntities(context.getSource(), 8);
                                            })
                                    )
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("visualize")
                                            .executes(context -> visualizeTrackedEntities(context.getSource()))
                                    )
                                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("analyze")
                                            .executes(context -> analyzeEntityMovements(context.getSource()))
                                    )
                                    .executes(context -> {
                                                // Show usage info
                                                context.getSource().sendFeedback(Text.literal("§6=== Entity Tracking Test Commands ==="));
                                                context.getSource().sendFeedback(Text.literal("§7/testEntityTracking nearby [range <radius>] - Test tracking nearby entities"));
                                                context.getSource().sendFeedback(Text.literal("§7/testEntityTracking visualize - Visualize currently tracked entities"));
                                                context.getSource().sendFeedback(Text.literal("§7/testEntityTracking analyze - Test movement analysis for tracked entities"));
                                                return 1;
                                            })
                            )
            );
        });
    }

    /**
     * Visualizes a sweep test with the debug renderer.
     */
    private void visualizeSweepTest(FabricClientCommandSource context, PhysicsEngine engine,
                                    Entity entity, Vec3d movement) {
        // Define colors
        final int COLOR_START = 0x8000FF00; // Green with alpha
        final int COLOR_END_ORIGINAL = 0x80FF0000; // Red with alpha
        final int COLOR_END_SAFE = 0x800000FF; // Blue with alpha
        final int COLOR_PATH = 0xFFFFFFFF; // White
        final int COLOR_COLLISION = 0xFFFFFF00; // Yellow
        final int COLOR_NORMAL = 0xFFFFA500; // Orange
        final int COLOR_DEFLECTED = 0xFF00FFFF; // Cyan

        // How long to display the visualization (10 seconds)
        final int DISPLAY_TICKS = 200;

        // Record starting position
        Vec3d startPos = entity.getPos().add(0,1.6,0);
        SLogger.log(this, "Starting position: " + startPos);
        SLogger.log(this, "Movement vector: " + movement);

        // Calculate where the entity would end up with unmodified movement
        Vec3d endPosOriginal = startPos.add(movement);

        // Get the entity physics manager
        EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();

        // Force track the entity if needed
        entityPhysics.forceTrackNearbyEntities(entity.getBoundingBox().expand(1.0));

        // Get the safe movement from our system
        Vec3d safeMovement = entityPhysics.analyzePotentialMovement(entity, movement);
        Vec3d endPosSafe = startPos.add(safeMovement);

        SLogger.log(this, "Safe movement: " + safeMovement);
        SLogger.log(this, "Safe end position: " + endPosSafe);

        // Add markers for positions
        DebugRenderer.addCrosshair(startPos, COLOR_START, 0.2f, 0.01f, DISPLAY_TICKS);
        DebugRenderer.addCrosshair(endPosOriginal, COLOR_END_ORIGINAL, 0.2f, 0.01f, DISPLAY_TICKS);
        DebugRenderer.addCrosshair(endPosSafe, COLOR_END_SAFE, 0.2f, 0.01f, DISPLAY_TICKS);

        // Add lines for paths
        DebugRenderer.addLine(startPos, endPosOriginal, COLOR_PATH, 0.01f, DISPLAY_TICKS);
        DebugRenderer.addLine(startPos, endPosSafe, COLOR_PATH, 0.01f, DISPLAY_TICKS);

        // Perform the sweep test
        ContactDetector.SweepResult result = entityPhysics.getContactDetector().sweepTest(entity, movement);

        // If we found a collision, mark it
        if (result != null) {
            // Get collision point
            Vec3d hitPos = new Vec3d(
                    result.getHitPoint().x,
                    result.getHitPoint().y,
                    result.getHitPoint().z
            );

            // Get collision normal
            Vec3d normalVec = new Vec3d(
                    result.getHitNormal().x,
                    result.getHitNormal().y,
                    result.getHitNormal().z
            );

            SLogger.log(this, "Collision detected:");
            SLogger.log(this, "  Time of impact: " + result.getTimeOfImpact());
            SLogger.log(this, "  Hit point: " + hitPos);
            SLogger.log(this, "  Hit normal: " + normalVec);

            // Draw collision marker
            DebugRenderer.addCrosshair(hitPos, COLOR_COLLISION, 0.25f, 0.01f, DISPLAY_TICKS);

            // Draw normal vector
            Vec3d normalEnd = hitPos.add(normalVec.multiply(0.5));
            DebugRenderer.addLine(hitPos, normalEnd, COLOR_NORMAL, 0.01f, DISPLAY_TICKS);

            // Calculate deflected movement if applicable
            float remainingTime = 1.0f - result.getTimeOfImpact();
            if (remainingTime > 0.01f) {
                Vec3d deflectedMovement = result.getDeflectedMovement(movement, remainingTime);

                // Only show if there's meaningful deflection
                if (deflectedMovement.lengthSquared() > 0.01) {
                    Vec3d deflectedEnd = hitPos.add(deflectedMovement);

                    SLogger.log(this, "  Deflected movement: " + deflectedMovement);

                    // Draw deflected movement
                    DebugRenderer.addLine(hitPos, deflectedEnd, COLOR_DEFLECTED, 0.01f, DISPLAY_TICKS);

                    context.sendFeedback(
                            Text.literal(String.format(
                                    "§aDeflected movement: §f[%.2f, %.2f, %.2f]",
                                    deflectedMovement.x, deflectedMovement.y, deflectedMovement.z
                            ))
                    );
                }
            }

            // Display collision information in chat
            context.sendFeedback(
                    Text.literal(String.format(
                            "§eCollision at §c%.2f §eof movement",
                            result.getTimeOfImpact()
                    ))
            );
        } else {
            SLogger.log(this, "No collision detected");
            context.sendFeedback(
                    Text.literal("§aNo collision detected in movement path")
            );
        }

        // Draw entity bounding box
        Box entityBox = entity.getBoundingBox();
        DebugRenderer.addBox(entityBox, COLOR_START, 0.01f, DISPLAY_TICKS);

        // Display safe movement in chat
        context.sendFeedback(
                Text.literal(String.format(
                        "§aSafe movement: §f[%.2f, %.2f, %.2f]",
                        safeMovement.x, safeMovement.y, safeMovement.z
                ))
        );

        // Display legend
        context.sendFeedback(Text.literal("§aVisualization active for 10 seconds:"));
        context.sendFeedback(Text.literal("§2■ Green: Start position"));
        context.sendFeedback(Text.literal("§4■ Red: Original end position"));
        context.sendFeedback(Text.literal("§1■ Blue: Safe end position"));
        if (result != null) {
            context.sendFeedback(Text.literal("§e■ Yellow: Collision point"));
            context.sendFeedback(Text.literal("§6■ Orange: Surface normal"));
            context.sendFeedback(Text.literal("§b■ Cyan: Deflected movement"));
        }
        context.sendFeedback(Text.literal("§f■ White: Movement paths"));

        SLogger.log(this, "Visualization created");
    }

    /**
     * Visualizes a convex sweep test with the debug renderer.
     */
    private void visualizeConvexSweepTest(FabricClientCommandSource context, PhysicsEngine engine,
                                          Entity entity, Vec3d movement) {
        // Define colors
        final int COLOR_START = 0x8000FF00; // Green with alpha
        final int COLOR_END_ORIGINAL = 0x80FF0000; // Red with alpha
        final int COLOR_END_SAFE = 0x800000FF; // Blue with alpha
        final int COLOR_PATH = 0xFFFFFFFF; // White
        final int COLOR_COLLISION = 0xFFFFFF00; // Yellow
        final int COLOR_NORMAL = 0xFFFFA500; // Orange
        final int COLOR_DEFLECTED = 0xFF00FFFF; // Cyan
        final int COLOR_ENTITY_BOX = 0x80FFFFFF; // White with alpha

        // How long to display the visualization (10 seconds)
        final int DISPLAY_TICKS = 200;

        // Record starting position
        Vec3d startPos = entity.getPos();
        SLogger.log(this, "Starting position: " + startPos);
        SLogger.log(this, "Movement vector: " + movement);

        // Calculate where the entity would end up with unmodified movement
        Vec3d endPosOriginal = startPos.add(movement);

        // Get the entity physics manager
        EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();

        // Force track the entity if needed
        entityPhysics.forceTrackNearbyEntities(entity.getBoundingBox().expand(1.0));

        // Get the entity proxies map
        Map<Entity, EntityProxy> entityProxies = entityPhysics.getEntityProxies();

        // Add markers for positions
        DebugRenderer.addCrosshair(startPos, COLOR_START, 0.2f, 0.01f, DISPLAY_TICKS);
        DebugRenderer.addCrosshair(endPosOriginal, COLOR_END_ORIGINAL, 0.2f, 0.01f, DISPLAY_TICKS);

        // Add lines for paths
        DebugRenderer.addLine(startPos, endPosOriginal, COLOR_PATH, 0.01f, DISPLAY_TICKS);

        // Draw entity bounding box
        Box entityBox = entity.getBoundingBox();
        DebugRenderer.addBox(entityBox, COLOR_ENTITY_BOX, 0.01f, DISPLAY_TICKS);

        // Also visualize where the box would end up
        Box destinationBox = entityBox.offset(movement);
        DebugRenderer.addBox(destinationBox, COLOR_END_ORIGINAL, 0.01f, DISPLAY_TICKS);

        // Perform the convex sweep test
        ContactDetector.SweepResult result = entityPhysics.getContactDetector().convexSweepTest(
                entity, movement, entityProxies);

        // If convex sweep failed, try the basic sweep test
        if (result == null) {
            SLogger.log(this, "Convex sweep test failed, trying basic sweep test");
            context.sendFeedback(Text.literal("§eConvex sweep test failed, trying basic sweep test"));

            result = entityPhysics.getContactDetector().sweepTest(entity, movement);
        }

        // If no collision detected by either method
        if (result == null) {
            SLogger.log(this, "No collision detected by any method");
            context.sendFeedback(Text.literal("§aNo collision detected in movement path"));

            // Get the safe movement (which should be the original in this case)
            Vec3d safeMovement = movement;
            Vec3d endPosSafe = startPos.add(safeMovement);

            // Add safe end position marker
            DebugRenderer.addCrosshair(endPosSafe, COLOR_END_SAFE, 0.2f, 0.01f, DISPLAY_TICKS);

            // Add safe path line
            DebugRenderer.addLine(startPos, endPosSafe, COLOR_PATH, 0.01f, DISPLAY_TICKS);

            // Display safe movement in chat
            context.sendFeedback(
                    Text.literal(String.format(
                            "§aSafe movement: §f[%.2f, %.2f, %.2f]",
                            safeMovement.x, safeMovement.y, safeMovement.z
                    ))
            );

            // Also draw the safe destination box
            Box safeDestinationBox = entityBox.offset(safeMovement);
            DebugRenderer.addBox(safeDestinationBox, COLOR_END_SAFE, 0.01f, DISPLAY_TICKS);

            return;
        }

        // We found a collision, now visualize it

        // Get collision point
        Vec3d hitPos = new Vec3d(
                result.getHitPoint().x,
                result.getHitPoint().y,
                result.getHitPoint().z
        );

        // Get collision normal
        Vec3d normalVec = new Vec3d(
                result.getHitNormal().x,
                result.getHitNormal().y,
                result.getHitNormal().z
        );

        SLogger.log(this, "Collision detected:");
        SLogger.log(this, "  Time of impact: " + result.getTimeOfImpact());
        SLogger.log(this, "  Hit point: " + hitPos);
        SLogger.log(this, "  Hit normal: " + normalVec);

        // Calculate safe position
        double safetyMargin = 0.01; // 1cm safety margin
        Vec3d safePos = result.getSafePosition(startPos, movement, safetyMargin);

        // Add safe position marker
        DebugRenderer.addCrosshair(safePos, COLOR_END_SAFE, 0.2f, 0.01f, DISPLAY_TICKS);

        // Add safe path line
        DebugRenderer.addLine(startPos, safePos, COLOR_PATH, 0.01f, DISPLAY_TICKS);

        // Calculate and draw safe destination box (where entity would stop)
        Vec3d safeMovement = safePos.subtract(startPos);
        Box safeDestinationBox = entityBox.offset(safeMovement);
        DebugRenderer.addBox(safeDestinationBox, COLOR_END_SAFE, 0.01f, DISPLAY_TICKS);

        // Draw collision marker
        DebugRenderer.addCrosshair(hitPos, COLOR_COLLISION, 0.25f, 0.01f, DISPLAY_TICKS);

        // Draw normal vector
        Vec3d normalEnd = hitPos.add(normalVec.multiply(0.5));
        DebugRenderer.addLine(hitPos, normalEnd, COLOR_NORMAL, 0.01f, DISPLAY_TICKS);

        // Calculate deflected movement if applicable
        float remainingTime = 1.0f - result.getTimeOfImpact();
        if (remainingTime > 0.01f) {
            Vec3d deflectedMovement = result.getDeflectedMovement(movement, remainingTime);

            // Only show if there's meaningful deflection
            if (deflectedMovement.lengthSquared() > 0.01) {
                Vec3d deflectedEnd = hitPos.add(deflectedMovement);

                SLogger.log(this, "  Deflected movement: " + deflectedMovement);

                // Draw deflected movement
                DebugRenderer.addLine(hitPos, deflectedEnd, COLOR_DEFLECTED, 0.01f, DISPLAY_TICKS);

                // Draw deflected destination box
                Box deflectedDestinationBox = safeDestinationBox.offset(deflectedMovement);
                DebugRenderer.addBox(deflectedDestinationBox, COLOR_DEFLECTED, 0.01f, DISPLAY_TICKS);

                context.sendFeedback(
                        Text.literal(String.format(
                                "§aDeflected movement: §f[%.2f, %.2f, %.2f]",
                                deflectedMovement.x, deflectedMovement.y, deflectedMovement.z
                        ))
                );
            }
        }

        // Display collision information in chat
        context.sendFeedback(
                Text.literal(String.format(
                        "§eCollision at §c%.2f §eof movement",
                        result.getTimeOfImpact()
                ))
        );

        // Display safe movement in chat
        context.sendFeedback(
                Text.literal(String.format(
                        "§aSafe movement: §f[%.2f, %.2f, %.2f]",
                        safeMovement.x, safeMovement.y, safeMovement.z
                ))
        );

        // Display legend
        context.sendFeedback(Text.literal("§aVisualization active for 10 seconds:"));
        context.sendFeedback(Text.literal("§2■ Green: Start position"));
        context.sendFeedback(Text.literal("§4■ Red: Original end position"));
        context.sendFeedback(Text.literal("§1■ Blue: Safe end position"));
        context.sendFeedback(Text.literal("§f■ White: Entity bounding boxes"));
        context.sendFeedback(Text.literal("§e■ Yellow: Collision point"));
        context.sendFeedback(Text.literal("§6■ Orange: Surface normal"));
        context.sendFeedback(Text.literal("§b■ Cyan: Deflected movement"));

        SLogger.log(this, "Visualization created");
    }

    /**
     * Tests entity tracking for nearby entities.
     *
     * @param source Command source
     * @param radius Radius to search for entities
     * @return Command result
     */
    private int testNearbyEntities(FabricClientCommandSource source, int radius) {
        try {
            if (serverInstance == null) {
                source.sendFeedback(Text.literal("§cServer not available"));
                return 0;
            }

            ServerWorld world = serverInstance.getWorld(
                    source.getWorld().getRegistryKey());

            // Get physics engine for this world
            PhysicsEngine engine = engineManager.getEngine(world);
            if (engine == null) {
                source.sendFeedback(Text.literal("§cNo physics engine for current world"));
                return 0;
            }

            // Get the EntityPhysicsManager
            EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();
            if (entityPhysics == null) {
                source.sendFeedback(Text.literal("§cEntityPhysicsManager not found in physics engine"));
                return 0;
            }

            // Get player position
            Vec3d playerPos = source.getPosition();

            // Create a box around the player
            Box searchBox = new Box(
                    playerPos.x - radius, playerPos.y - radius, playerPos.z - radius,
                    playerPos.x + radius, playerPos.y + radius, playerPos.z + radius
            );

            // Force track all entities in this box
            int entitiesTracked = entityPhysics.forceTrackNearbyEntities(searchBox);

            // Get the tracked entities
            Set<Entity> trackedEntities = entityPhysics.getTrackedEntities();
            Map<Entity, EntityProxy> proxies = entityPhysics.getEntityProxies();

            // Display info about tracked entities
            source.sendFeedback(Text.literal("§aForced tracking of §e" + entitiesTracked +
                    " §aentities within §e" + radius + " §ablocks"));
            source.sendFeedback(Text.literal("§7Total tracked entities: §f" + trackedEntities.size()));
            source.sendFeedback(Text.literal("§7Total entity proxies: §f" + proxies.size()));

            // Visualize the tracked entities
            visualizeTrackedEntities(source);

            return 1;
        } catch (Exception e) {
            source.sendFeedback(Text.literal("§cError testing entity tracking: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Visualizes all currently tracked entities.
     *
     * @param source Command source
     * @return Command result
     */
    private int visualizeTrackedEntities(FabricClientCommandSource source) {
        try {
            if (serverInstance == null) {
                source.sendFeedback(Text.literal("§cServer not available"));
                return 0;
            }

            ServerWorld world = serverInstance.getWorld(
                    source.getWorld().getRegistryKey());

            // Get physics engine for this world
            PhysicsEngine engine = engineManager.getEngine(world);
            if (engine == null) {
                source.sendFeedback(Text.literal("§cNo physics engine for current world"));
                return 0;
            }

            // Get the EntityPhysicsManager
            EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();
            if (entityPhysics == null) {
                source.sendFeedback(Text.literal("§cEntityPhysicsManager not found in physics engine"));
                return 0;
            }

            // Get the tracked entities
            Set<Entity> trackedEntities = entityPhysics.getTrackedEntities();
            Map<Entity, EntityProxy> proxies = entityPhysics.getEntityProxies();

            // Clear any existing debug visualizations
            DebugRenderer.clear();

            // Define colors
            final int COLOR_ENTITY_BOX = 0x8000FF00; // Green with alpha
            final int COLOR_ENTITY_POSITION = 0xFFFFFF00; // Yellow
            final int COLOR_TRACKED_PATH = 0x80FFFFFF; // White with alpha

            // How long to display the visualization (10 seconds)
            final int DISPLAY_TICKS = 200;

            // Visualize each tracked entity
            int visualizedCount = 0;
            for (Entity entity : trackedEntities) {
                // Draw the entity's bounding box
                Box box = entity.getBoundingBox();
                DebugRenderer.addBox(box, COLOR_ENTITY_BOX, 0.01f, DISPLAY_TICKS);

                // Draw a marker at the entity's position
                Vec3d pos = entity.getPos();
                DebugRenderer.addPoint(pos, COLOR_ENTITY_POSITION, 0.1f, DISPLAY_TICKS);

                // Draw a connecting line to the entity if it's in a proxy
                if (proxies.containsKey(entity)) {
                    // Add a small line above the entity to indicate it has a proxy
                    Vec3d lineStart = new Vec3d(pos.x, box.maxY + 0.1, pos.z);
                    Vec3d lineEnd = new Vec3d(pos.x, box.maxY + 0.5, pos.z);
                    DebugRenderer.addLine(lineStart, lineEnd, COLOR_TRACKED_PATH, 0.01f, DISPLAY_TICKS);
                }

                visualizedCount++;
            }

            // Display info about visualization
            source.sendFeedback(Text.literal("§aVisualized §e" + visualizedCount +
                    " §atracked entities for 10 seconds"));
            source.sendFeedback(Text.literal("§7Legend:"));
            source.sendFeedback(Text.literal("§2■ Green box: Entity bounding box"));
            source.sendFeedback(Text.literal("§e■ Yellow dot: Entity position"));
            source.sendFeedback(Text.literal("§f■ White line: Entity has a physics proxy"));

            return 1;
        } catch (Exception e) {
            source.sendFeedback(Text.literal("§cError visualizing tracked entities: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Tests movement analysis for tracked entities.
     *
     * @param source Command source
     * @return Command result
     */
    private int analyzeEntityMovements(FabricClientCommandSource source) {
        try {
            if (serverInstance == null) {
                source.sendFeedback(Text.literal("§cServer not available"));
                return 0;
            }

            ServerWorld world = serverInstance.getWorld(
                    source.getWorld().getRegistryKey());

            // Get physics engine for this world
            PhysicsEngine engine = engineManager.getEngine(world);
            if (engine == null) {
                source.sendFeedback(Text.literal("§cNo physics engine for current world"));
                return 0;
            }

            // Get the EntityPhysicsManager
            EntityPhysicsManager entityPhysics = engine.getEntityPhysicsManager();
            if (entityPhysics == null) {
                source.sendFeedback(Text.literal("§cEntityPhysicsManager not found in physics engine"));
                return 0;
            }

            // Get the player entity
            Entity player = source.getPlayer();
            if (player == null) {
                source.sendFeedback(Text.literal("§cPlayer entity not found"));
                return 0;
            }

            // Clear any existing debug drawings
            DebugRenderer.clear();

            // Define colors
            final int COLOR_START = 0x8000FF00; // Green with alpha
            final int COLOR_END_ORIGINAL = 0x80FF0000; // Red with alpha
            final int COLOR_END_SAFE = 0x800000FF; // Blue with alpha
            final int COLOR_PATH = 0xFFFFFFFF; // White
            final int COLOR_COLLISION = 0xFFFFFF00; // Yellow
            final int COLOR_NORMAL = 0xFFFFA500; // Orange
            final int COLOR_DEFLECTED = 0xFF00FFFF; // Cyan

            // How long to display the visualization (10 seconds)
            final int DISPLAY_TICKS = 200;

            // Get look direction and calculate test movement
            Vec3d lookDir = player.getRotationVector();

            // Use a reasonable test distance (5 blocks)
            double testDistance = 5.0;
            Vec3d testMovement = lookDir.multiply(testDistance);

            // Record starting position (eye level for better visibility)
            Vec3d startPos = player.getPos().add(0, player.getStandingEyeHeight(), 0);

            // Calculate where the entity would end up with unmodified movement
            Vec3d endPosOriginal = startPos.add(testMovement);

            // Get the safe movement from our system
            Vec3d safeMovement = entityPhysics.analyzePotentialMovement(player, testMovement);
            Vec3d endPosSafe = startPos.add(safeMovement);

            // Add markers for positions
            DebugRenderer.addCrosshair(startPos, COLOR_START, 0.2f, 0.03f, DISPLAY_TICKS);
            DebugRenderer.addCrosshair(endPosOriginal, COLOR_END_ORIGINAL, 0.2f, 0.03f, DISPLAY_TICKS);

            // Only add safe endpoint if it's different from original
            if (!safeMovement.equals(testMovement)) {
                DebugRenderer.addCrosshair(endPosSafe, COLOR_END_SAFE, 0.2f, 0.03f, DISPLAY_TICKS);
            }

            // Add lines for paths
            DebugRenderer.addLine(startPos, endPosOriginal, COLOR_PATH, 0.02f, DISPLAY_TICKS);

            // Only add safe path if it's different from original
            if (!safeMovement.equals(testMovement)) {
                DebugRenderer.addLine(startPos, endPosSafe, COLOR_DEFLECTED, 0.02f, DISPLAY_TICKS);
            }

            // Display information about the movement analysis
            source.sendFeedback(Text.literal("§aMovement Analysis Test"));
            source.sendFeedback(Text.literal("§7Original movement: §f[" +
                    String.format("%.2f, %.2f, %.2f", testMovement.x, testMovement.y, testMovement.z) + "]"));

            if (!safeMovement.equals(testMovement)) {
                source.sendFeedback(Text.literal("§7Safe movement: §f[" +
                        String.format("%.2f, %.2f, %.2f", safeMovement.x, safeMovement.y, safeMovement.z) + "]"));
                source.sendFeedback(Text.literal("§cMovement was modified due to potential collision"));
            } else {
                source.sendFeedback(Text.literal("§aNo collisions detected - movement unmodified"));
            }

            // Display legend
            source.sendFeedback(Text.literal("§aVisualization active for 10 seconds:"));
            source.sendFeedback(Text.literal("§2■ Green: Start position"));
            source.sendFeedback(Text.literal("§4■ Red: Original end position"));
            if (!safeMovement.equals(testMovement)) {
                source.sendFeedback(Text.literal("§1■ Blue: Safe end position"));
                source.sendFeedback(Text.literal("§b■ Cyan: Safe movement path"));
            }
            source.sendFeedback(Text.literal("§f■ White: Original movement path"));

            return 1;
        } catch (Exception e) {
            source.sendFeedback(Text.literal("§cError analyzing movements: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }
}