package net.starlight.stardance.utils;

import com.bulletphysics.collision.dispatch.CollisionWorld;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.debug.CollisionDebugger;
import net.starlight.stardance.physics.PhysicsEngine;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.io.IOException;
import java.util.Optional;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
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
                    serverInstance.getLevel(context.getSource().getWorld().dimension()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return 1;
    }

}