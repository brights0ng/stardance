package net.starlight.stardance.mixin.feature.block_interaction;

import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.starlight.stardance.utils.GridSpaceCoordinateUtils;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Debug mixin to track what happens in processBlockBreakingAction.
 * This will help us see exactly where the pipeline is failing.
 */
@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManagerProcess {

    @Shadow @Final protected ServerPlayerEntity player;
    @Shadow protected ServerWorld world;

    /**
     * Inject at the very start of processBlockBreakingAction to track all calls.
     */
    @Inject(
        method = "processBlockBreakingAction",
        at = @At("HEAD")
    )
    private void debugProcessBlockBreakingAction(BlockPos pos, PlayerActionC2SPacket.Action action, 
                                                Direction direction, int worldHeight, int sequence, 
                                                CallbackInfo ci) {
        try {
            // Check if this is a grid block
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult = 
                GridSpaceCoordinateUtils.findActualGridBlock(pos, world);
                
            if (gridResult.isPresent()) {
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("=== GRID BLOCK BREAKING PROCESS START ==="));
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Player: %s", player.getName().getString()));
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Action: %s", action));
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Position: %s", pos));
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Grid-local: %s", gridResult.get().gridLocalPos));
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Sequence: %d", sequence));
                
                // Log player position for distance debugging
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Player eye pos: %.2f, %.2f, %.2f", 
                        player.getEyePos().x, player.getEyePos().y, player.getEyePos().z));
                        
                // Log the exact distance calculation that the method will do
                double exactDistance = player.getEyePos().squaredDistanceTo(
                    net.minecraft.util.math.Vec3d.ofCenter(pos));
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Exact distance calculation: %.2f blocks", Math.sqrt(exactDistance)));
                    
                // Log MAX_BREAK_SQUARED_DISTANCE for comparison
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Max break distance allowed: %.2f blocks", 
                        Math.sqrt(net.minecraft.server.network.ServerPlayNetworkHandler.MAX_BREAK_SQUARED_DISTANCE)));
            }
            
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                "Error in process debug: " + e.getMessage());
        }
    }

    /**
     * Inject after the distance check to see if we pass it.
     */
    @Inject(
        method = "processBlockBreakingAction",
        at = @At(value = "INVOKE", 
                target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;method_41250(Lnet/minecraft/util/math/BlockPos;ZILjava/lang/String;)V",
                ordinal = 0), // First call to method_41250 (too far)
        cancellable = true
    )
    private void debugTooFarRejection(BlockPos pos, PlayerActionC2SPacket.Action action, 
                                     Direction direction, int worldHeight, int sequence, 
                                     CallbackInfo ci) {
        try {
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult = 
                GridSpaceCoordinateUtils.findActualGridBlock(pos, world);
                
            if (gridResult.isPresent()) {
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    "❌ GRID BLOCK REJECTED: TOO FAR");
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    "This should NOT happen with global distance override!");
                
                // Double-check our distance calculation
                double ourDistance = player.getEyePos().squaredDistanceTo(
                    net.minecraft.util.math.Vec3d.ofCenter(pos));
                SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                    String.format("Calculated distance: %.2f", Math.sqrt(ourDistance)));
            }
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                "Error in too far debug: " + e.getMessage());
        }
    }

    /**
     * Inject after passing distance check to see progression.
     */
    @Inject(
        method = "processBlockBreakingAction",
        at = @At(value = "FIELD", 
                target = "Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;START_DESTROY_BLOCK:Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;")
    )
    private void debugStartDestroyBlock(BlockPos pos, PlayerActionC2SPacket.Action action, 
                                       Direction direction, int worldHeight, int sequence, 
                                       CallbackInfo ci) {
        try {
            if (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
                Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult = 
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, world);
                    
                if (gridResult.isPresent()) {
                    SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                        "✅ GRID BLOCK PASSED DISTANCE CHECK - Processing START_DESTROY_BLOCK");
                        
                    // Check what block state we're getting
                    net.minecraft.block.BlockState blockState = world.getBlockState(pos);
                    SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                        String.format("Block state at GridSpace pos: %s", 
                            blockState.getBlock().getName().getString()));
                }
            }
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerProcess", 
                "Error in start destroy debug: " + e.getMessage());
        }
    }
}