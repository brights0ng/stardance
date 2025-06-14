package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Custom crafting screen handler for grid blocks using vanilla mechanics.
 */
public class GridCraftingScreenHandler extends CraftingScreenHandler {
    private final LocalGrid grid;
    private final BlockPos gridLocalPos;

    public GridCraftingScreenHandler(int syncId, PlayerInventory playerInventory, LocalGrid grid, BlockPos gridLocalPos) {
        // Create a custom context that provides the grid's world and a virtual position
        super(syncId, playerInventory, createGridContext(grid, gridLocalPos));

        this.grid = grid;
        this.gridLocalPos = gridLocalPos;

        SLogger.log("GridCraftingScreenHandler", "Created GridCraftingScreenHandler with syncId=" + syncId
                + ", grid=" + grid.getGridId()
                + ", gridLocalPos=" + gridLocalPos);
    }

    /**
     * Creates a ScreenHandlerContext that provides access to the grid's world
     * and a virtual world position for the crafting table.
     */
    private static ScreenHandlerContext createGridContext(LocalGrid grid, BlockPos gridLocalPos) {
        return new ScreenHandlerContext() {
            @Override
            public <T> Optional<T> get(BiFunction<World, BlockPos, T> getter) {
                // Calculate virtual world position
                Vector3f gridWorldPos = new Vector3f();
                Transform gridTransform = new Transform();
                grid.getRigidBody().getWorldTransform(gridTransform);
                gridTransform.origin.get(gridWorldPos);

                BlockPos virtualWorldPos = new BlockPos(
                        (int) Math.floor(gridWorldPos.x + gridLocalPos.getX()),
                        (int) Math.floor(gridWorldPos.y + gridLocalPos.getY()),
                        (int) Math.floor(gridWorldPos.z + gridLocalPos.getZ())
                );

                try {
                    T result = getter.apply(grid.getWorld(), virtualWorldPos);
                    return Optional.ofNullable(result);
                } catch (Exception e) {
                    SLogger.log("GridCraftingScreenHandler", "Error in context getter: " + e.getMessage());
                    return Optional.empty();
                }
            }

            @Override
            public void run(BiConsumer<World, BlockPos> function) {
                // Calculate virtual world position
                Vector3f gridWorldPos = new Vector3f();
                Transform gridTransform = new Transform();
                grid.getRigidBody().getWorldTransform(gridTransform);
                gridTransform.origin.get(gridWorldPos);

                BlockPos virtualWorldPos = new BlockPos(
                        (int) Math.floor(gridWorldPos.x + gridLocalPos.getX()),
                        (int) Math.floor(gridWorldPos.y + gridLocalPos.getY()),
                        (int) Math.floor(gridWorldPos.z + gridLocalPos.getZ())
                );

                try {
                    function.accept(grid.getWorld(), virtualWorldPos);
                } catch (Exception e) {
                    SLogger.log("GridCraftingScreenHandler", "Error in context runner: " + e.getMessage());
                }
            }
        };
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        SLogger.log("GridCraftingScreenHandler", "canUse() called for player " + player.getName().getString());

        if (grid.getBlock(gridLocalPos) == null) {
            SLogger.log("GridCraftingScreenHandler", "canUse() - Block no longer exists at " + gridLocalPos);
            return false;
        }

        try {
            Vector3f gridWorldPos = new Vector3f();
            Transform gridTransform = new Transform();
            grid.getRigidBody().getWorldTransform(gridTransform);
            gridTransform.origin.get(gridWorldPos);

            double distanceToGrid = player.squaredDistanceTo(gridWorldPos.x, gridWorldPos.y, gridWorldPos.z);
            boolean withinRange = distanceToGrid <= 64.0;

            SLogger.log("GridCraftingScreenHandler", "canUse() - Distance: " + Math.sqrt(distanceToGrid)
                    + ", within range: " + withinRange);

            return withinRange;
        } catch (Exception e) {
            SLogger.log("GridCraftingScreenHandler", "canUse() - Exception: " + e.getMessage());
            return false;
        }
    }
}