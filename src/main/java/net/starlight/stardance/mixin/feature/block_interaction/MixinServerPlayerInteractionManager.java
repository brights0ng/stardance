package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.TransformationAPI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * VS2-style block breaking distance validation using Yarn mappings.
 * Follows VS2's MixinServerPlayerGameMode.handleBlockBreakAction() pattern.
 */
@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManager {

    @Shadow @Final protected ServerPlayerEntity player;
    @Shadow protected ServerWorld world;

    /**
     * Intercept distance calculation during block breaking to use world coordinates.
     * This follows VS2's exact pattern: redirect distance checks to use proper coordinates.
     */
    @WrapOperation(
        method = "processBlockBreakingAction", // Yarn equivalent of handleBlockBreakAction
        at = @At(value = "INVOKE", 
                target = "Lnet/minecraft/util/math/Vec3d;squaredDistanceTo(Lnet/minecraft/util/math/Vec3d;)D")
    )
    private double handleBlockBreakingDistance(Vec3d playerPos, Vec3d blockPos, Operation<Double> original) {
        try {
            // Extract BlockPos from the block position Vec3d (VS2's approach)
            BlockPos pos = new BlockPos(
                (int) Math.floor(blockPos.x - 0.5), 
                (int) Math.floor(blockPos.y - 0.5), 
                (int) Math.floor(blockPos.z - 0.5)
            );
            
            // Use our VS2-style coordinate transformation
            Vec3d blockCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            Vec3d worldCoordinates = TransformationAPI.getWorldCoordinates(world, pos, 
                new Vec3d(0.5, 0.5, 0.5));
            
            // Calculate distance using world coordinates (VS2's pattern)
            double distance = TransformationAPI.squaredDistanceBetweenInclGrids(world, playerPos, worldCoordinates);
            
            SLogger.log("MixinServerPlayerInteractionManager", 
                String.format("Block breaking distance: %.2f (world coords)", Math.sqrt(distance)));
            
            return distance;
            
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManager", 
                "Error in distance calculation: " + e.getMessage());
            return original.call(playerPos, blockPos);
        }
    }
}