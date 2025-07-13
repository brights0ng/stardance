package net.starlight.stardance.mixin.feature.distance_replace;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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

import java.util.Optional;

/**
 * VS2-style distance check interception for block breaking.
 * This is the KEY mixin that makes grid block breaking work.
 * 
 * Equivalent to VS2's MixinServerPlayerGameMode.handleBlockBreakAction()
 */
@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManagerDistance {

    @Shadow @Final protected ServerPlayerEntity player;
    @Shadow protected ServerWorld world;

    /**
     * Intercept distance calculation in processBlockBreakingAction.
     * This is where VS2 does their magic - they override the distance check
     * to use world coordinates for ship blocks.
     */
    @WrapOperation(
        method = "processBlockBreakingAction",
        at = @At(value = "INVOKE", 
                target = "Lnet/minecraft/util/math/Vec3d;squaredDistanceTo(Lnet/minecraft/util/math/Vec3d;)D")
    )
    private double handleGridDistanceCheck(Vec3d playerPos, Vec3d blockPos, Operation<Double> original) {
        try {
            // Extract block position from the Vec3d (VS2 does this same calculation)
            BlockPos pos = BlockPos.ofFloored(blockPos.subtract(0.5, 0.5, 0.5));
            
            SLogger.log("MixinServerPlayerInteractionManagerDistance", 
                String.format("Distance check: player=%.2f,%.2f,%.2f block=%s", 
                    playerPos.x, playerPos.y, playerPos.z, pos));
            
            // Check if this is a GridSpace block using our enhanced detection
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult = 
                GridSpaceCoordinateUtils.findActualGridBlock(pos, world);
                
            if (gridResult.isPresent()) {
                // This is a grid block - use world coordinates for distance calculation (VS2 pattern)
                return calculateGridBlockDistance(playerPos, gridResult.get(), original);
            }
            
            // Regular world block - use original distance calculation
            return original.call(playerPos, blockPos);
            
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerDistance", 
                "Error in distance check: " + e.getMessage());
            e.printStackTrace();
            return original.call(playerPos, blockPos);
        }
    }

    /**
     * Calculate distance to grid block using world coordinates (VS2 approach).
     */
    private double calculateGridBlockDistance(Vec3d playerPos, 
                                            GridSpaceCoordinateUtils.GridSpaceBlockResult gridResult,
                                            Operation<Double> original) {
        try {
            // Get the block's world coordinates (where it visually appears)
            Vec3d blockWorldPos = TransformationAPI.getWorldCoordinates(
                world, gridResult.actualGridSpacePos, new Vec3d(0.5, 0.5, 0.5));
            
            double distance = TransformationAPI.squaredDistanceBetweenInclGrids(world, playerPos, blockWorldPos);
            
            SLogger.log("MixinServerPlayerInteractionManagerDistance", 
                String.format("Grid block distance: %.2f blocks (GridSpace=%s, world=%.2f,%.2f,%.2f)", 
                    Math.sqrt(distance), gridResult.actualGridSpacePos, blockWorldPos.x, blockWorldPos.y, blockWorldPos.z));
            
            return distance;
            
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerDistance", 
                "Error calculating grid distance: " + e.getMessage());
            
            // Fallback to original calculation
            Vec3d blockPos = new Vec3d(
                gridResult.actualGridSpacePos.getX() + 0.5,
                gridResult.actualGridSpacePos.getY() + 0.5,
                gridResult.actualGridSpacePos.getZ() + 0.5
            );
            return original.call(playerPos, blockPos);
        }
    }
}