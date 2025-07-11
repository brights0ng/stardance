package net.starlight.stardance.utils;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.debug.CollisionDebugger;
import net.starlight.stardance.debug.InteractionDebugManager;

import javax.vecmath.Vector3d;
import java.io.IOException;

import static net.starlight.stardance.Stardance.*;

/**
 * UPDATED: Enhanced command registry with Phase 3 interaction debugging commands.
 */
public class CommandRegistry implements ILoggingControl {
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    /**
     * Registers all Stardance commands including new interaction debugging commands.
     */
    public static void init() {
        LiteralArgumentBuilder<FabricClientCommandSource> stardanceCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("stardance");

        // ===============================================
        // EXISTING COMMANDS (PRESERVED)
        // ===============================================

        LiteralArgumentBuilder<FabricClientCommandSource> importSchem =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("importschem");
        importSchem.executes(CommandRegistry::importSchem);

        LiteralArgumentBuilder<FabricClientCommandSource> debugCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("debug");

        LiteralArgumentBuilder<FabricClientCommandSource> sweepTestCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("sweeptest");

        RequiredArgumentBuilder<FabricClientCommandSource, Float> distanceArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Float>argument("distance", FloatArgumentType.floatArg(0.1f, 10.0f));

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> raysArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("rays", IntegerArgumentType.integer(1, 24));

        // Build existing debug structure
        raysArg.executes(CommandRegistry::executeSweepTest);
        distanceArg.then(raysArg);
        distanceArg.executes(CommandRegistry::executeSweepTest);
        sweepTestCommand.then(distanceArg);
        sweepTestCommand.executes(CommandRegistry::executeSweepTest);

        // ===============================================
        // NEW: INTERACTION DEBUGGING COMMANDS
        // ===============================================

        // /stardance debug interaction <on|off>
        LiteralArgumentBuilder<FabricClientCommandSource> interactionCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("interaction");

        RequiredArgumentBuilder<FabricClientCommandSource, Boolean> interactionToggleArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("enabled", BoolArgumentType.bool());
        interactionToggleArg.executes(CommandRegistry::toggleInteractionDebug);

        interactionCommand.then(interactionToggleArg);
        interactionCommand.executes(context -> {
            // Show current state if no argument provided
            String state = InteractionDebugManager.getDebugStats();
            context.getSource().sendFeedback(Text.literal("§6Interaction Debug State: §f" + state));
            return 1;
        });

        // /stardance debug raycast [visual]
        LiteralArgumentBuilder<FabricClientCommandSource> raycastCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("raycast");

        LiteralArgumentBuilder<FabricClientCommandSource> raycastVisualCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("visual");
        raycastVisualCommand.executes(CommandRegistry::debugRaycastVisual);

        LiteralArgumentBuilder<FabricClientCommandSource> raycastPipelineCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("pipeline");
        raycastPipelineCommand.executes(CommandRegistry::debugRaycastPipeline);

        raycastCommand.then(raycastVisualCommand);
        raycastCommand.then(raycastPipelineCommand);
        raycastCommand.executes(context -> InteractionDebugManager.debugRaycast(context.getSource()));

        // /stardance debug transform <x> <y> <z>
        LiteralArgumentBuilder<FabricClientCommandSource> transformCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("transform");

        RequiredArgumentBuilder<FabricClientCommandSource, Double> xArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("x", DoubleArgumentType.doubleArg());
        RequiredArgumentBuilder<FabricClientCommandSource, Double> yArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("y", DoubleArgumentType.doubleArg());
        RequiredArgumentBuilder<FabricClientCommandSource, Double> zArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("z", DoubleArgumentType.doubleArg());

        zArg.executes(CommandRegistry::debugTransform);
        yArg.then(zArg);
        xArg.then(yArg);
        transformCommand.then(xArg);

        // /stardance debug all <on|off>
        LiteralArgumentBuilder<FabricClientCommandSource> allDebugCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("all");

        RequiredArgumentBuilder<FabricClientCommandSource, Boolean> allToggleArg =
                RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("enabled", BoolArgumentType.bool());
        allToggleArg.executes(CommandRegistry::toggleAllDebug);

        allDebugCommand.then(allToggleArg);
        allDebugCommand.executes(context -> {
            // Show current state
            String state = InteractionDebugManager.getDebugStats();
            context.getSource().sendFeedback(Text.literal("§6All Debug State: §f" + state));
            return 1;
        });

        // /stardance debug clear
        LiteralArgumentBuilder<FabricClientCommandSource> clearDebugCommand =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear");
        clearDebugCommand.executes(context -> {
            InteractionDebugManager.clearDebugState();
            context.getSource().sendFeedback(Text.literal("§6Debug state cleared"));
            return 1;
        });

        // ===============================================
        // COMMAND TREE ASSEMBLY
        // ===============================================

        // Add new commands to debug tree
        debugCommand.then(interactionCommand);
        debugCommand.then(raycastCommand);
        debugCommand.then(transformCommand);
        debugCommand.then(allDebugCommand);
        debugCommand.then(clearDebugCommand);
        debugCommand.then(sweepTestCommand); // Keep existing sweep test

        // Build final command tree
        stardanceCommand.then(debugCommand);
        stardanceCommand.then(importSchem);

        // Register the complete command tree
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(stardanceCommand));
    }

    // ===============================================
    // NEW COMMAND IMPLEMENTATIONS
    // ===============================================

    /**
     * Toggles interaction debugging on/off.
     */
    private static int toggleInteractionDebug(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        InteractionDebugManager.setInteractionDebugging(enabled);
        InteractionDebugManager.setRaycastDebugging(enabled); // Also toggle raycast debugging

        context.getSource().sendFeedback(Text.literal("§6Interaction debugging " +
                (enabled ? "§aENABLED" : "§cDISABLED")));
        return 1;
    }

    /**
     * Toggles all debugging systems on/off.
     */
    private static int toggleAllDebug(CommandContext<FabricClientCommandSource> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        InteractionDebugManager.setAllDebugging(enabled);

        context.getSource().sendFeedback(Text.literal("§6All debugging " +
                (enabled ? "§aENABLED" : "§cDISABLED")));
        return 1;
    }

    /**
     * Tests coordinate transformation for specific coordinates.
     */
    private static int debugTransform(CommandContext<FabricClientCommandSource> context) {
        double x = DoubleArgumentType.getDouble(context, "x");
        double y = DoubleArgumentType.getDouble(context, "y");
        double z = DoubleArgumentType.getDouble(context, "z");

        // Also trigger visual debug
        Vec3d pos = new Vec3d(x, y, z);
        net.starlight.stardance.debug.VisualRaycastDebugger.visualizeCoordinateTransformation(pos, context.getSource().getPlayer());

        return InteractionDebugManager.debugTransform(context.getSource(), x, y, z);
    }

    /**
     * Visual raycast debugging with DebugRenderer.
     */
    private static int debugRaycastVisual(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for visual raycast"));
            return 0;
        }

        net.starlight.stardance.debug.VisualRaycastDebugger.visualizePlayerRaycast(player);
        context.getSource().sendFeedback(Text.literal("§6Visual raycast debug activated - check the world!"));
        return 1;
    }

    /**
     * Visual pipeline debugging showing transformation at multiple points.
     */
    private static int debugRaycastPipeline(CommandContext<FabricClientCommandSource> context) {
        PlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFeedback(Text.literal("§cNo player available for pipeline debug"));
            return 0;
        }

        net.starlight.stardance.debug.VisualRaycastDebugger.visualizeTransformationPipeline(player);
        context.getSource().sendFeedback(Text.literal("§6Transformation pipeline visualized - check the world!"));
        return 1;
    }

    // ===============================================
    // EXISTING COMMAND IMPLEMENTATIONS (PRESERVED)
    // ===============================================

    /**
     * Executes the sweep test command with specified arguments.
     */
    private static int executeSweepTest(CommandContext<FabricClientCommandSource> context) {
        CollisionDebugger.debugCollision(context.getSource());
        return 1;
    }

    /**
     * Imports a schematic at the player's location.
     */
    private static int importSchem(CommandContext<FabricClientCommandSource> context) {
        SLogger.log(CommandRegistry.class.getSimpleName(), "Step 1");
        org.joml.Vector3f pos = context.getSource().getPosition().toVector3f();
        try {
            schemManager.importSchematic("rat.schem", new Vector3d(pos.x, pos.y, pos.z),
                    serverInstance.getWorld(context.getSource().getWorld().getRegistryKey()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return 1;
    }
}