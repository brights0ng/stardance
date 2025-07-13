package net.starlight.stardance.mixin.feature.network;

import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.utils.GridSpaceCoordinateUtils;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Debug network layer to find where grid blocks are being rejected.
 * No method call interception - just logging to trace the flow.
 */
@Mixin(ServerPlayNetworkHandler.class)
public class MixinServerPlayNetworkHandlerDebug {

    @Shadow public ServerPlayerEntity player;

    /**
     * Inject at the start of onPlayerAction to track all incoming packets.
     */
    @Inject(
            method = "onPlayerAction",
            at = @At("HEAD")
    )
    private void debugIncomingPlayerAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        try {
            BlockPos pos = packet.getPos();
            PlayerActionC2SPacket.Action action = packet.getAction();

            // Only log block breaking actions
            if (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK ||
                    action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK ||
                    action == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK) {

                // Check if this is a grid block
                Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                        GridSpaceCoordinateUtils.findActualGridBlock(pos, player.getWorld());

                if (gridResult.isPresent()) {
//                    SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                            "=== NETWORK: Grid Block Action Received ===");
//                    SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                            String.format("Action: %s", action));
//                    SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                            String.format("Position: %s", pos));
//                    SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                            String.format("Grid-local: %s", gridResult.get().gridLocalPos));
//                    SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                            String.format("Player: %s", player.getName().getString()));
//                    SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                            "About to enter switch statement...");
                } else {
//                    SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                            String.format("NETWORK: World Block Action - %s at %s", action, pos));
                }
            }

        } catch (Exception e) {
//            SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                    "Error in network debug: " + e.getMessage());
        }
    }

    /**
     * Inject right before the call to processBlockBreakingAction to see if we reach it.
     */
    @Inject(
            method = "onPlayerAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;processBlockBreakingAction(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;Lnet/minecraft/util/math/Direction;II)V")
    )
    private void debugBeforeProcessBlockBreaking(PlayerActionC2SPacket packet, CallbackInfo ci) {
        try {
            BlockPos pos = packet.getPos();

            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, player.getWorld());

            if (gridResult.isPresent()) {
//                SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                        "✅ NETWORK: About to call processBlockBreakingAction for grid block");
//                SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                        "This should trigger our ServerPlayerInteractionManager mixins...");
            } else {
//                SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                        "NETWORK: About to call processBlockBreakingAction for world block");
            }

        } catch (Exception e) {
//            SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                    "Error in before process debug: " + e.getMessage());
        }
    }

    /**
     * Inject right after the call to processBlockBreakingAction to see if it completed.
     */
    @Inject(
            method = "onPlayerAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;processBlockBreakingAction(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;Lnet/minecraft/util/math/Direction;II)V",
                    shift = At.Shift.AFTER)
    )
    private void debugAfterProcessBlockBreaking(PlayerActionC2SPacket packet, CallbackInfo ci) {
        try {
            BlockPos pos = packet.getPos();

            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, player.getWorld());

            if (gridResult.isPresent()) {
//                SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                        "✅ NETWORK: processBlockBreakingAction completed for grid block");
            } else {
//                SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                        "NETWORK: processBlockBreakingAction completed for world block");
            }

        } catch (Exception e) {
//            SLogger.log("MixinServerPlayNetworkHandlerDebug",
//                    "Error in after process debug: " + e.getMessage());
        }
    }
}