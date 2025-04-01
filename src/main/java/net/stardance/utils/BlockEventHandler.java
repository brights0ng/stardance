package net.stardance.utils;

import com.bulletphysics.collision.broadphase.BroadphasePair;
import com.bulletphysics.collision.broadphase.BroadphaseProxy;
import com.bulletphysics.collision.broadphase.Dispatcher;
import com.bulletphysics.collision.broadphase.OverlappingPairCallback;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.BlockItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.stardance.physics.PhysicsEngine;
import net.stardance.physics.SubchunkCoordinates;
import net.stardance.physics.SubchunkManager;

import javax.vecmath.Vector3f;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

import static net.stardance.Stardance.engineManager;


public class BlockEventHandler {
    private final SubchunkManager subchunkManager;

    public BlockEventHandler(SubchunkManager subchunkManager) {
        this.subchunkManager = subchunkManager;

        // Register block break event
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClient) {
                onBlockUpdate(pos, (ServerWorld) world);
            }
        });

        // Register block place event
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && hand == Hand.MAIN_HAND) {
                if (player.getStackInHand(hand).getItem() instanceof BlockItem) {
                    // Wait until the next tick to check if a block was placed
                    world.getServer().execute(() -> {
                        BlockPos pos = hitResult.getBlockPos().offset(hitResult.getSide());
                        onBlockUpdate(pos, (ServerWorld) world);
                    });
                }
            }
            return ActionResult.PASS;
        });
    }

    private SubchunkCoordinates getSubchunkCoordinates(BlockPos pos) {
        int x = pos.getX() >> 4;
        int y = pos.getY() >> 4;
        int z = pos.getZ() >> 4;
        return new SubchunkCoordinates(x, y, z);
    }

    // In your BlockEventHandler
    public void onBlockUpdate(BlockPos pos, ServerWorld world) {
        SubchunkCoordinates coords = getSubchunkCoordinates(pos);
        subchunkManager.markSubchunkDirty(coords);

        PhysicsEngine physicsEngine = engineManager.getEngine(world);
        physicsEngine.onBlockUpdate(pos);
    }

}
