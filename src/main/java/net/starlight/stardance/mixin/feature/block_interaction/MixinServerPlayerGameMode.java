package net.starlight.stardance.mixin.feature.block_interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.utils.TransformationAPI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayerGameMode.class)
public class MixinServerPlayerGameMode {

    @Final
    @Shadow
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    /**
     * VS2-Style: Include grids in server-side distance check when player breaks a block.
     * Uses TransformationAPI to get proper world coordinates for grid blocks.
     */
    @Redirect(
        method = "handleBlockBreakAction",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D")
    )
    public double handleBlockBreakAction(Vec3 instance, Vec3 vec3) {
        BlockPos pos = BlockPos.containing(vec3.subtract(0.5, 0.5, 0.5));
        
        // Check if this block is in GridSpace
        var transformResult = TransformationAPI.getInstance().worldToGridSpace(
            new Vec3(vec3.x, vec3.y, vec3.z), level);
            
        if (transformResult.isPresent()) {
            // This is a grid block - use visual world coordinates for distance check
            var result = transformResult.get();
            Vec3 gridWorldPos = new Vec3(result.gridSpaceVec.x, result.gridSpaceVec.y, result.gridSpaceVec.z);
            Vec3 blockCenter = gridWorldPos.add(0.5, 0.5, 0.5);
            
            return blockCenter.distanceToSqr(player.getX(), player.getY() + 1.5, player.getZ());
        } else {
            // Regular world block - use vanilla logic
            return instance.distanceToSqr(vec3);
        }
    }
}