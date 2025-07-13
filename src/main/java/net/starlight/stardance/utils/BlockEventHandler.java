package net.starlight.stardance.utils;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.physics.SubchunkCoordinates;
import net.starlight.stardance.physics.SubchunkManager;

import static net.starlight.stardance.Stardance.engineManager;


public class BlockEventHandler {
    private final SubchunkManager subchunkManager;

    public BlockEventHandler(SubchunkManager subchunkManager) {
        this.subchunkManager = subchunkManager;

        // Register block break event
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide) {
                onBlockUpdate(pos, (ServerLevel) world);
            }
        });

        // Register block place event
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide && hand == InteractionHand.MAIN_HAND) {
                if (player.getItemInHand(hand).getItem() instanceof BlockItem) {
                    // Wait until the next tick to check if a block was placed
                    world.getServer().execute(() -> {
                        BlockPos pos = hitResult.getBlockPos().relative(hitResult.getDirection());
                        onBlockUpdate(pos, (ServerLevel) world);
                    });
                }
            }
            return InteractionResult.PASS;
        });
    }

    private SubchunkCoordinates getSubchunkCoordinates(BlockPos pos) {
        int x = pos.getX() >> 4;
        int y = pos.getY() >> 4;
        int z = pos.getZ() >> 4;
        return new SubchunkCoordinates(x, y, z);
    }

    // In your BlockEventHandler
    public void onBlockUpdate(BlockPos pos, ServerLevel world) {
        SubchunkCoordinates coords = getSubchunkCoordinates(pos);
        subchunkManager.markSubchunkDirty(coords);

        PhysicsEngine physicsEngine = engineManager.getEngine(world);
        physicsEngine.onBlockUpdate(pos);
    }

}
