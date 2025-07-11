package net.starlight.stardance.debug;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.TransformationAPI;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages debug state and utilities for the interaction system.
 * Provides runtime debugging controls and detailed logging for development.
 */
public class InteractionDebugManager implements ILoggingControl {

    // Debug state flags
    private static volatile boolean raycastDebuggingEnabled = false;
    private static volatile boolean interactionDebuggingEnabled = false;
    private static volatile boolean transformDebuggingEnabled = false;

    // Per-player debug tracking
    private static final ConcurrentMap<String, Long> lastRaycastDebugTime = new ConcurrentHashMap<>();
    private static final long DEBUG_THROTTLE_MS = 1000; // Limit debug spam to once per second

    // Singleton instance
    private static InteractionDebugManager INSTANCE;

    public static InteractionDebugManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new InteractionDebugManager();
        }
        return INSTANCE;
    }

    private InteractionDebugManager() {
        SLogger.log(this, "InteractionDebugManager initialized");
    }

    // ===============================================
    // DEBUG STATE MANAGEMENT
    // ===============================================

    public static boolean isRaycastDebuggingEnabled() {
        return raycastDebuggingEnabled;
    }

    public static boolean isInteractionDebuggingEnabled() {
        return interactionDebuggingEnabled;
    }

    public static boolean isTransformDebuggingEnabled() {
        return transformDebuggingEnabled;
    }

    public static void setRaycastDebugging(boolean enabled) {
        raycastDebuggingEnabled = enabled;
        SLogger.log("InteractionDebugManager", "Raycast debugging " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public static void setInteractionDebugging(boolean enabled) {
        interactionDebuggingEnabled = enabled;
        SLogger.log("InteractionDebugManager", "Interaction debugging " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public static void setTransformDebugging(boolean enabled) {
        transformDebuggingEnabled = enabled;
        SLogger.log("InteractionDebugManager", "Transform debugging " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public static void setAllDebugging(boolean enabled) {
        setRaycastDebugging(enabled);
        setInteractionDebugging(enabled);
        setTransformDebugging(enabled);
    }

    // ===============================================
    // DEBUG COMMAND IMPLEMENTATIONS
    // ===============================================

    /**
     * Tests coordinate transformation for the given world position.
     */
    public static int debugTransform(FabricClientCommandSource source, double x, double y, double z) {
        if (source.getWorld() == null) {
            source.sendFeedback(Text.literal("§cNo world available for transformation test"));
            return 0;
        }

        Vec3d worldPos = new Vec3d(x, y, z);

        source.sendFeedback(Text.literal("§6=== COORDINATE TRANSFORMATION TEST ==="));
        source.sendFeedback(Text.literal("§7Input: §f" + String.format("(%.2f, %.2f, %.2f)", x, y, z)));

        try {
            // Test the transformation
            Optional<TransformationAPI.GridSpaceTransformResult> result =
                    TransformationAPI.getInstance().worldToGridSpace(worldPos, source.getWorld());

            if (result.isPresent()) {
                TransformationAPI.GridSpaceTransformResult transform = result.get();

                source.sendFeedback(Text.literal("§a✓ Grid found: §f" + transform.grid.getGridId()));
                source.sendFeedback(Text.literal("§7Grid-local: §f" + transform.gridLocalPos));
                source.sendFeedback(Text.literal("§7GridSpace: §f" + transform.gridSpacePos));
                source.sendFeedback(Text.literal("§7GridSpace vec: §f" + String.format("(%.2f, %.2f, %.2f)",
                        transform.gridSpaceVec.x, transform.gridSpaceVec.y, transform.gridSpaceVec.z)));

                // Test round-trip transformation using CONTINUOUS coordinates for accuracy
                Vec3d backToWorld = TransformationAPI.getInstance().gridSpaceToWorld(transform.gridSpaceVec, transform.grid);
                double error = worldPos.distanceTo(backToWorld);

                source.sendFeedback(Text.literal("§7Round-trip: §f" + String.format("(%.2f, %.2f, %.2f)",
                        backToWorld.x, backToWorld.y, backToWorld.z)));
                source.sendFeedback(Text.literal("§7Error: §f" + String.format("%.6f blocks", error)));

                if (error > 0.001) {
                    source.sendFeedback(Text.literal("§c⚠ High transformation error!"));
                }

            } else {
                source.sendFeedback(Text.literal("§7No grid found at this position"));
            }

        } catch (Exception e) {
            source.sendFeedback(Text.literal("§cTransformation error: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }

        source.sendFeedback(Text.literal("§6=== END TRANSFORMATION TEST ==="));
        return 1;
    }

    /**
     * Debugs the player's current raycast target.
     */
    public static int debugRaycast(FabricClientCommandSource source) {
        PlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendFeedback(Text.literal("§cNo player available for raycast test"));
            return 0;
        }

        // Throttle debug messages per player
        String playerName = player.getName().getString();
        long currentTime = System.currentTimeMillis();
        Long lastDebugTime = lastRaycastDebugTime.get(playerName);

        if (lastDebugTime != null && (currentTime - lastDebugTime) < DEBUG_THROTTLE_MS) {
            return 1; // Silently throttle
        }

        lastRaycastDebugTime.put(playerName, currentTime);

        try {
            // Get detailed raycast information
            RaycastDebugInfo debugInfo =
                    getDebugInfo(player, 64.0);

            source.sendFeedback(Text.literal("§6=== RAYCAST DEBUG ==="));
            source.sendFeedback(Text.literal("§7" + debugInfo.description));

            if (debugInfo.worldPos != null) {
                source.sendFeedback(Text.literal("§7World: §f" + String.format("(%.2f, %.2f, %.2f)",
                        debugInfo.worldPos.x, debugInfo.worldPos.y, debugInfo.worldPos.z)));
            }

            if (debugInfo.gridSpacePos != null) {
                source.sendFeedback(Text.literal("§7GridSpace: §f" + String.format("(%.2f, %.2f, %.2f)",
                        debugInfo.gridSpacePos.x, debugInfo.gridSpacePos.y, debugInfo.gridSpacePos.z)));
            }

            if (debugInfo.grid != null) {
                source.sendFeedback(Text.literal("§7Grid: §f" + debugInfo.grid.getGridId()));
                source.sendFeedback(Text.literal("§7Blocks: §f" + debugInfo.grid.getBlocks().size()));
            }

            // Show player eye position and look direction for context
            Vec3d eyePos = player.getEyePos();
            Vec3d lookVec = player.getRotationVector();
            source.sendFeedback(Text.literal("§7Eye: §f" + String.format("(%.2f, %.2f, %.2f)",
                    eyePos.x, eyePos.y, eyePos.z)));
            source.sendFeedback(Text.literal("§7Look: §f" + String.format("(%.2f, %.2f, %.2f)",
                    lookVec.x, lookVec.y, lookVec.z)));

        } catch (Exception e) {
            source.sendFeedback(Text.literal("§cRaycast debug error: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }

        return 1;
    }

    /**
     * Throttled logging for high-frequency debug events.
     */
    public static void throttledLog(String source, String message, long throttleMs) {
        String key = source + ":" + message;
        long currentTime = System.currentTimeMillis();

        // Simple throttling - could be improved with a proper cache
        Long lastTime = lastRaycastDebugTime.get(key);
        if (lastTime == null || (currentTime - lastTime) > throttleMs) {
            SLogger.log("InteractionDebug", "[" + source + "] " + message);
            lastRaycastDebugTime.put(key, currentTime);
        }
    }

    // ===============================================
    // STATISTICS AND MONITORING
    // ===============================================

    /**
     * Gets debug statistics for monitoring.
     */
    public static String getDebugStats() {
        return String.format("Raycast: %s, Interaction: %s, Transform: %s",
                raycastDebuggingEnabled ? "ON" : "OFF",
                interactionDebuggingEnabled ? "ON" : "OFF",
                transformDebuggingEnabled ? "ON" : "OFF");
    }

    /**
     * Clears debug state and cached data.
     */
    public static void clearDebugState() {
        lastRaycastDebugTime.clear();
        SLogger.log("InteractionDebugManager", "Debug state cleared");
    }

    // ===============================================
    // LOGGING CONTROL
    // ===============================================

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }

    /**
     * Debug information container for raycast debugging.
     */
    public static class RaycastDebugInfo {
        public final String description;
        public final Vec3d worldPos;
        public final Vec3d gridSpacePos;
        public final LocalGrid grid;

        public RaycastDebugInfo(String description, Vec3d worldPos, Vec3d gridSpacePos, LocalGrid grid) {
            this.description = description;
            this.worldPos = worldPos;
            this.gridSpacePos = gridSpacePos;
            this.grid = grid;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(description);
            if (worldPos != null) {
                sb.append("\n  World: ").append(String.format("(%.2f, %.2f, %.2f)", worldPos.x, worldPos.y, worldPos.z));
            }
            if (gridSpacePos != null) {
                sb.append("\n  GridSpace: ").append(String.format("(%.2f, %.2f, %.2f)", gridSpacePos.x, gridSpacePos.y, gridSpacePos.z));
            }
            if (grid != null) {
                sb.append("\n  Grid: ").append(grid.getGridId());
            }
            return sb.toString();
        }
    }

    /**
     * Debug method: Gets detailed raycast information for debugging commands.
     */
    private static InteractionDebugManager.RaycastDebugInfo getDebugInfo(Entity entity, double maxDistance) {

        // Perform raycast
        HitResult result = entity.raycast(maxDistance, 0.0f, false);

        if (result.getType() == HitResult.Type.MISS) {
            return new InteractionDebugManager.RaycastDebugInfo("No hit within " + maxDistance + " blocks", null, null, null);
        }

        Vec3d worldPos = result.getPos();

        // Check for grid intersection
        Optional<TransformationAPI.GridSpaceTransformResult> gridTransform =
                TransformationAPI.getInstance().worldToGridSpace(worldPos, entity.getWorld());

        if (gridTransform.isPresent()) {
            TransformationAPI.GridSpaceTransformResult transform = gridTransform.get();
            return new InteractionDebugManager.RaycastDebugInfo(
                    "Hit grid block",
                    worldPos,
                    transform.gridSpaceVec,
                    transform.grid
            );
        } else {
            return new InteractionDebugManager.RaycastDebugInfo(
                    "Hit world block",
                    worldPos,
                    null,
                    null
            );
        }
    }
}