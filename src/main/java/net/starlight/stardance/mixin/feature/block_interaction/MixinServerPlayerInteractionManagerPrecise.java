package net.starlight.stardance.mixin.feature.block_interaction;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.utils.GridSpaceCoordinateUtils;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.TransformationAPI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Enhanced debugging version to figure out why @Redirect isn't working.
 */
@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManagerPrecise {

    @Shadow @Final protected ServerPlayerEntity player;
    @Shadow protected ServerWorld world;

    /**
     * Debug injection to see if the method is being called at all.
     */
    @Inject(
            method = "processBlockBreakingAction",
            at = @At("HEAD")
    )
    private void debugMethodCall(BlockPos pos, net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action action,
                                 net.minecraft.util.math.Direction direction, int worldHeight, int sequence, CallbackInfo ci) {
        try {
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, world);

            if (gridResult.isPresent()) {
                SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                        "🔍 processBlockBreakingAction called for grid block: " + pos);
                SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                        "Action: " + action + ", Sequence: " + sequence);
            }
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerPrecise", "Debug error: " + e.getMessage());
        }
    }

    /**
     * Try multiple redirect approaches to see which one works.
     */

    // Attempt 1: Original approach
    @Redirect(
            method = "processBlockBreakingAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/math/Vec3d;squaredDistanceTo(Lnet/minecraft/util/math/Vec3d;)D")
    )
    public double redirectBlockBreakingDistance_v1(Vec3d eyePos, Vec3d blockCenter) {
        SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                "🎯 REDIRECT v1 TRIGGERED! eyePos=" + eyePos + " blockCenter=" + blockCenter);
        return handleGridDistanceCalculation(eyePos, blockCenter);
    }

    /**
     * Attempt 3: Target the specific method call pattern
     */
    @Redirect(
            method = "processBlockBreakingAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/math/Vec3d;squaredDistanceTo(Lnet/minecraft/util/math/Vec3d;)D",
                    ordinal = 0),  // Target the first occurrence
            require = 0
    )
    public double redirectBlockBreakingDistance_v3(Vec3d instance, Vec3d vec3d) {
        SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                "🎯 REDIRECT v3 TRIGGERED! instance=" + instance + " vec3d=" + vec3d);
        return handleGridDistanceCalculation(instance, vec3d);
    }

    /**
     * Common distance calculation handler.
     */
    private double handleGridDistanceCalculation(Vec3d eyePos, Vec3d blockCenter) {
        try {
            // Extract block position from Vec3d
            BlockPos pos = BlockPos.ofFloored(blockCenter.subtract(0.5, 0.5, 0.5));

            SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                    String.format("Distance intercept: eye=%.2f,%.2f,%.2f block=%s",
                            eyePos.x, eyePos.y, eyePos.z, pos));

            // Check if this is a grid block
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, world);

            if (gridResult.isPresent()) {
                // Grid block - use world coordinates
                Vec3d blockWorldPos = TransformationAPI.getWorldCoordinates(
                        world, gridResult.get().actualGridSpacePos, new Vec3d(0.5, 0.5, 0.5));

                double distance = eyePos.squaredDistanceTo(blockWorldPos);

                SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                        String.format("Grid distance: %.2f blocks (was %.2f GridSpace)",
                                Math.sqrt(distance), Math.sqrt(eyePos.squaredDistanceTo(blockCenter))));

                return distance;
            }

            // Regular world block
            SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                    "Regular world block - vanilla distance");
            return eyePos.squaredDistanceTo(blockCenter);

        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerPrecise",
                    "Error in distance calculation: " + e.getMessage());
            return eyePos.squaredDistanceTo(blockCenter);
        }
    }
}