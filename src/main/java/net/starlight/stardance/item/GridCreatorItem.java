package net.starlight.stardance.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.starlight.stardance.core.LocalGrid;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;

/*
    Defines the GridCreator item and its mechanics
 */

public class GridCreatorItem extends Item {
    public GridCreatorItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos clickedPos = context.getBlockPos();
        BlockState clickedState = world.getBlockState(clickedPos);

        // Define the position above the clicked block
        BlockPos gridOriginPos = clickedPos.up();
        Vector3d origin = new Vector3d(gridOriginPos.getX(), gridOriginPos.getY(), gridOriginPos.getZ());

        // Define the rotation (no rotation by default)
        Quat4f rotation = new Quat4f(0, 0, 0, 1);

        if(world instanceof ServerWorld) {
            // Create a new LocalGrid
            new LocalGrid(origin, rotation, (net.minecraft.server.world.ServerWorld) world, clickedState);
//            int size = 5;
//            for(int x = -size; x <= size; x++){
//                for (int y = -size; y <= size; y++) {
//                    for (int z = -size; z <= size; z++) {
//                        localGrid.addBlock(new LocalBlock(new BlockPos(x, y, z), clickedState));
//                    }
//                }
//            }
//            localGrid.addBlock(new LocalBlock(new BlockPos(0, 0, 1), clickedState));
//            localGrid.addBlock(new LocalBlock(new BlockPos(0, 0, 2), clickedState));
//            localGrid.addBlock(new LocalBlock(new BlockPos(0, 0, 3), clickedState));
//            localGrid.addBlock(new LocalBlock(new BlockPos(0, 0, 4), clickedState));
//            localGrid.addBlock(new LocalBlock(new BlockPos(0, 0, 5), clickedState));
//            localGrid.addBlock(new LocalBlock(new BlockPos(0, 3, 2), clickedState));
//            localGrid.addBlock(new LocalBlock(new BlockPos(0, 4, 2), clickedState));
//            localGrid.addBlock(new LocalBlock(new BlockPos(1, 3, 4), clickedState));

            // Optionally, reduce item stack size or consume the item
            context.getStack().decrement(1);

        }

        return ActionResult.SUCCESS;
    }
}
