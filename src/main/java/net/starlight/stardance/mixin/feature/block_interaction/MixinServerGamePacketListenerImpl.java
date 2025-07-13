package net.starlight.stardance.mixin.feature.block_interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.utils.TransformationAPI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    /**
     * Transform block breaking coordinates from world space to GridSpace.
     * This ensures that when a player breaks a "visual" grid block, 
     * the action affects the actual GridSpace block.
     */
    @ModifyArg(
        method = "handlePlayerAction",
        at = @At(value = "INVOKE", 
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V"),
        index = 0
    )
    private BlockPos transformBreakingPosition(BlockPos worldPos) {
        return transformInteractionPosition(worldPos);
    }

    /**
     * Transform block placement coordinates from world space to GridSpace.
     */
    @ModifyArg(
        method = "handleUseItemOn", 
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"),
        index = 4
    )
    private BlockHitResult transformPlacementHitResult(BlockHitResult hitResult) {
        BlockPos transformedPos = transformInteractionPosition(hitResult.getBlockPos());
        
        // If position was transformed, create new HitResult with GridSpace coordinates
        if (!transformedPos.equals(hitResult.getBlockPos())) {
            return new BlockHitResult(
                hitResult.getLocation(),
                hitResult.getDirection(), 
                transformedPos,  // Use GridSpace coordinates
                hitResult.isInside()
            );
        }
        
        return hitResult;
    }

    /**
     * Core transformation logic: Convert world coordinates to GridSpace coordinates
     * if the position corresponds to a grid block.
     */
    private BlockPos transformInteractionPosition(BlockPos worldPos) {
        var transformResult = TransformationAPI.getInstance().worldToGridSpace(
            new Vec3(worldPos.getX(), worldPos.getY(), worldPos.getZ()),
            player.serverLevel());
            
        if (transformResult.isPresent()) {
            // Return GridSpace coordinates for actual block manipulation
            return transformResult.get().gridSpacePos;
        }
        
        // Not a grid block - return original coordinates
        return worldPos;
    }
}