package net.starlight.stardance.utils;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
public class ParsedSchematic {
    public int width;
    public int height;
    public int length;

    public Map<Integer, BlockState> palette = new HashMap<>();
    public int[] blockData;

    public BlockState getBlockStateAt(int x, int y, int z) {
        int index = x + (z * width) + (y * width * length);
        if (index < 0 || index >= blockData.length) {
            throw new IndexOutOfBoundsException("Block data index out of bounds: " + index);
        }
        int paletteIndex = blockData[index];

        if (!palette.containsKey(paletteIndex)) {
            System.err.println("Warning: Palette does not contain paletteIndex " + paletteIndex);
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = palette.get(paletteIndex);
        if (state == null) {
            System.err.println("Warning: BlockState is null for paletteIndex: " + paletteIndex);
            return Blocks.AIR.defaultBlockState();
        }

        return state;
    }


}
