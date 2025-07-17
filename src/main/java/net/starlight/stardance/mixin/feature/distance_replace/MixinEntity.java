package net.starlight.stardance.mixin.feature.distance_replace;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces all distance checks to include grids - matches VS2's exact approach.
 * This is the foundation that makes ALL interactions work on moving grids.
 */
@Mixin(Entity.class)
public class MixinEntity {

    @Inject(method = "distanceTo", at = @At("HEAD"), cancellable = true)
    private void preDistanceTo(final Entity entity, final CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(Mth.sqrt((float) (Entity.class.cast(this)).distanceToSqr(entity)));
    }

    @Inject(method = "distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D", at = @At("HEAD"), cancellable = true)
    private void preDistanceToSqr(final Vec3 vec, final CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(squaredDistanceToInclGrids(Entity.class.cast(this), vec.x, vec.y, vec.z));
    }

    @Inject(method = "distanceToSqr(DDD)D", at = @At("HEAD"), cancellable = true)
    private void preDistanceToSqr(final double x, final double y, final double z,
                                  final CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(squaredDistanceToInclGrids(Entity.class.cast(this), x, y, z));
    }

    /**
     * Stardance equivalent of VS2's VSGameUtilsKt.squaredDistanceToInclShips()
     */
    private static double squaredDistanceToInclGrids(Entity entity, double x, double y, double z) {
        try {
            // Check if target position is in a GridSpace
            BlockPos targetPos = BlockPos.containing(x, y, z);
            LocalGrid grid = GridSpaceManager.getGridAtPosition(targetPos);
            
            if (grid != null) {
                // Convert GridSpace coordinates to visual world coordinates
                Vec3 gridSpacePos = new Vec3(x, y, z);
                Vec3 visualWorldPos = grid.gridSpaceToWorldSpace(gridSpacePos);
                
                // Calculate distance using visual world position
                Vec3 entityPos = entity.position();
                return entityPos.distanceToSqr(visualWorldPos);
            } else {
                // Regular world position - use vanilla calculation
                Vec3 entityPos = entity.position();
                return entityPos.distanceToSqr(x, y, z);
            }
            
        } catch (Exception e) {
            SLogger.log("MixinEntityDistanceReplace", "Error in grid distance calculation: " + e.getMessage());
            // Fallback to vanilla distance
            Vec3 entityPos = entity.position();
            return entityPos.distanceToSqr(x, y, z);
        }
    }
}