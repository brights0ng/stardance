package net.starlight.stardance.mixin.server.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Server-side network packet interception for GridSpace block interactions.
 * Transforms world coordinates to grid coordinates for block breaking/placing actions.
 * Matches VS2's exact approach for packet coordinate transformation.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    /**
     * Intercepts block breaking position to transform world coordinates to grid coordinates.
     * This matches VS2's exact @ModifyArg approach for handlePlayerAction.
     */
    @ModifyArg(
        method = "handlePlayerAction",
        at = @At(value = "INVOKE", 
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V"),
        index = 0
    )
    private BlockPos transformBreakingPosition(BlockPos worldPos) {
        try {
            // Check if this world position corresponds to a GridSpace block
            LocalGrid grid = GridSpaceManager.getGridAtPosition(worldPos);
            
            if (grid != null) {
                // Convert world coordinates to GridSpace coordinates for actual block manipulation
                Vec3 worldVec = Vec3.atCenterOf(worldPos);
                Vec3 gridSpaceVec = grid.worldToGridSpace(worldVec);
                BlockPos gridSpacePos = BlockPos.containing(gridSpaceVec);
                
                SLogger.log("MixinServerGamePacketListenerImpl", 
                    String.format("Block break transform: world=%s → grid=%s (Grid ID: %s)", 
                        worldPos.toShortString(), 
                        gridSpacePos.toShortString(),
                        grid.getGridId()));
                
                return gridSpacePos;
            } else {
                // Regular world block - no transformation needed
                SLogger.log("MixinServerGamePacketListenerImpl", 
                    String.format("World block break: %s (no grid)", worldPos.toShortString()));
                return worldPos;
            }
            
        } catch (Exception e) {
            SLogger.log("MixinServerGamePacketListenerImpl", 
                "Error transforming break position for " + worldPos.toShortString() + ": " + e.getMessage());
            e.printStackTrace();
            
            // Fallback to original position on error
            return worldPos;
        }
    }

    /**
     * Intercepts block placing position to transform world coordinates to grid coordinates.
     * This handles right-click block placement on grids.
     */
    @ModifyArg(
        method = "handleUseItemOn",
        at = @At(value = "INVOKE", 
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"),
        index = 4
    )
    private net.minecraft.world.phys.BlockHitResult transformPlacementPosition(net.minecraft.world.phys.BlockHitResult hitResult) {
        try {
            BlockPos worldPos = hitResult.getBlockPos();
            LocalGrid grid = GridSpaceManager.getGridAtPosition(worldPos);
            
            if (grid != null) {
                // Convert world coordinates to GridSpace coordinates for block placement
                Vec3 worldVec = Vec3.atCenterOf(worldPos);
                Vec3 gridSpaceVec = grid.worldToGridSpace(worldVec);
                BlockPos gridSpacePos = BlockPos.containing(gridSpaceVec);
                
                // Create new BlockHitResult with grid coordinates
                net.minecraft.world.phys.BlockHitResult gridHitResult = new net.minecraft.world.phys.BlockHitResult(
                    hitResult.getLocation(), // Keep original hit location for visual consistency
                    hitResult.getDirection(),
                    gridSpacePos, // Use grid coordinates for block position
                    hitResult.isInside()
                );
                
                SLogger.log("MixinServerGamePacketListenerImpl", 
                    String.format("Block place transform: world=%s → grid=%s (Grid ID: %s)", 
                        worldPos.toShortString(), 
                        gridSpacePos.toShortString(),
                        grid.getGridId()));
                
                return gridHitResult;
            } else {
                // Regular world block placement
                return hitResult;
            }
            
        } catch (Exception e) {
            SLogger.log("MixinServerGamePacketListenerImpl", 
                "Error transforming placement position: " + e.getMessage());
            e.printStackTrace();
            
            // Fallback to original hit result
            return hitResult;
        }
    }

    /**
     * Intercepts block interaction position for right-click interactions (buttons, levers, etc.)
     */
    @ModifyArg(
        method = "handleUseItemOn", 
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/BlockHitResult;getBlockPos()Lnet/minecraft/core/BlockPos;"),
        index = 0
    )
    private net.minecraft.world.phys.BlockHitResult transformInteractionPosition(net.minecraft.world.phys.BlockHitResult hitResult) {
        // Use the same transformation logic as placement
        return transformPlacementPosition(hitResult);
    }
}