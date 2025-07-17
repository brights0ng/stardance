package net.starlight.stardance.mixin.feature.server_interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Server-side block breaking distance validation for GridSpace blocks.
 * Matches VS2's exact approach and entry point.
 */
@Mixin(ServerPlayerGameMode.class)
public class MixinServerPlayerGameMode {

    @Final
    @Shadow
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    /**
     * Includes grids in server-side distance check when player breaks a block.
     * This matches VS2's exact @Redirect approach and method signature.
     */
    @Redirect(
        method = "handleBlockBreakAction",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D")
    )
    public double handleBlockBreakAction(final Vec3 instance, final Vec3 vec3) {
        try {
            // Extract block position (matches VS2's approach exactly)
            final BlockPos pos = BlockPos.containing(vec3.subtract(0.5, 0.5, 0.5));
            
            // Check if this block position is on a grid
            LocalGrid grid = GridSpaceManager.getGridAtPosition(pos);
            
            if (grid != null) {
                // Block is on a grid - use visual world coordinates for distance check
                Vec3 blockCenter = Vec3.atCenterOf(pos);
                Vec3 visualWorldPos = grid.gridSpaceToWorldSpace(blockCenter);
                
                // Calculate distance using visual world position
                // Note: VS2 uses player.getY() + 1.5 for eye height - we match this exactly
                double distanceSquared = visualWorldPos.distanceToSqr(
                    player.getX(), 
                    player.getY() + 1.5,  // Eye height offset (matches VS2)
                    player.getZ()
                );
                
                SLogger.log("MixinServerPlayerGameMode", 
                    String.format("Grid block distance check: pos=%s, visual=%s, distance=%.2f", 
                        pos.toShortString(), 
                        String.format("(%.1f,%.1f,%.1f)", visualWorldPos.x, visualWorldPos.y, visualWorldPos.z),
                        Math.sqrt(distanceSquared)));
                
                return distanceSquared;
                
            } else {
                // Regular world block - use vanilla distance calculation
                return instance.distanceToSqr(vec3);
            }
            
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerGameMode", 
                "Error in grid distance check, falling back to vanilla: " + e.getMessage());
            e.printStackTrace();
            
            // Fallback to vanilla behavior
            return instance.distanceToSqr(vec3);
        }
    }
}