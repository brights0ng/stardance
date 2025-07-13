package net.starlight.stardance.mixin.client;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.starlight.stardance.utils.GridSpaceCoordinateUtils;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Debug client-side interaction manager to see if the client is trying to break grid blocks.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManagerDebug {

    /**
     * Track when the client tries to attack/break a block.
     */
    @Inject(
        method = "attackBlock",
        at = @At("HEAD")
    )
    private void debugClientAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        try {
            // Check if this is a grid block
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.world != null) {
                Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult = 
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, client.world);
                    
                if (gridResult.isPresent()) {
                    SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                        "=== CLIENT: Attempting to attack grid block ===");
                    SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                        String.format("Position: %s", pos));
                    SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                        String.format("Direction: %s", direction));
                    SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                        String.format("Grid-local: %s", gridResult.get().gridLocalPos));
                        
                    // Check client-side distance
                    if (client.player != null) {
                        double distance = Math.sqrt(client.player.squaredDistanceTo(
                            net.minecraft.util.math.Vec3d.ofCenter(pos)));
                        SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                            String.format("Client distance: %.2f blocks", distance));
                    }
                } else {
                    SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                        String.format("CLIENT: Attacking world block at %s", pos));
                }
            }
            
        } catch (Exception e) {
            SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                "Error in client attack debug: " + e.getMessage());
        }
    }

    /**
     * Track when the client continues breaking a block.
     */
    @Inject(
        method = "updateBlockBreakingProgress",
        at = @At("HEAD")
    )
    private void debugClientBreakingProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        try {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.world != null) {
                Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult = 
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, client.world);
                    
                if (gridResult.isPresent()) {
                    SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                        "CLIENT: Updating breaking progress for grid block");
                }
            }
            
        } catch (Exception e) {
            SLogger.log("MixinClientPlayerInteractionManagerDebug", 
                "Error in client progress debug: " + e.getMessage());
        }
    }
}