package net.starlight.stardance.item;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.starlight.stardance.core.LocalGrid;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;

/*
    Defines the GridCreator item and its mechanics
 */

public class GridCreatorItem extends Item {
    public GridCreatorItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level world = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = world.getBlockState(clickedPos);

        // Define the position above the clicked block
        BlockPos gridOriginPos = clickedPos.above();
        Vector3d origin = new Vector3d(gridOriginPos.getX(), gridOriginPos.getY(), gridOriginPos.getZ());

        // Define the rotation (no rotation by default)
        Quat4f rotation = new Quat4f(0, 0, 0, 1);

        if(world instanceof ServerLevel) {
            // Create a new LocalGrid
            new LocalGrid(origin, rotation, (net.minecraft.server.level.ServerLevel) world, clickedState);
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
            context.getItemInHand().shrink(1);

        }

        return InteractionResult.SUCCESS;
    }
}
