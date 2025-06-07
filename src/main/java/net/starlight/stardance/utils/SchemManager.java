package net.starlight.stardance.utils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SchemManager implements ILoggingControl{
    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return true;
    }
    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }



    public void importSchematic(String fileName, Vector3d position, ServerWorld world) throws IOException {
        LocalGrid grid = new LocalGrid(position, new Quat4f(0, 0, 0, 1), world, Blocks.AIR.getDefaultState());
        SLogger.log(this, "Importing schematic " + fileName);
        grid.importBlocks(loadSchematic(fileName));
        grid.removeBlock(new BlockPos(0,0,0));
    }

    public static ConcurrentMap<BlockPos, LocalBlock> loadSchematic(String fileName) throws IOException {
        Path schemPath = getSchematicPath(fileName);
        byte[] schemData = Files.readAllBytes(schemPath); // Load file into a byte array

        // Parse schematic
        ParsedSchematic parsed = parseSchematic(schemData);

        ConcurrentMap<BlockPos, LocalBlock> blocks = new ConcurrentHashMap<>();

        // Iterate through the block data and populate the Map
        for (int y = 0; y < parsed.height; y++) {
            for (int z = 0; z < parsed.length; z++) {
                for (int x = 0; x < parsed.width; x++) {
                    BlockState state = parsed.getBlockStateAt(x, y, z);

                    // Skip air blocks
                    if (state.isAir()) {
                        continue;
                    }

                    BlockPos pos = new BlockPos(x, y, z);
                    blocks.put(pos, new LocalBlock(pos, state));
                }
            }
        }


        return blocks;
    }


    private static ParsedSchematic parseSchematic(byte[] schemData) throws IOException {
        NbtCompound nbtData = NbtIo.readCompressed(new ByteArrayInputStream(schemData));

        ParsedSchematic parsed = new ParsedSchematic();

        // Read dimensions
        parsed.width = nbtData.getShort("Width");
        parsed.height = nbtData.getShort("Height");
        parsed.length = nbtData.getShort("Length");

        // Read the palette
        NbtCompound paletteTag = nbtData.getCompound("Palette");
        Map<Integer, BlockState> palette = new HashMap<>();

        for (String key : paletteTag.getKeys()) {
            int index = paletteTag.getInt(key);
            BlockState state = getBlockStateFromString(key);
            palette.put(index, state);
        }

        parsed.palette = palette;

        // Read the block data as byte[]
        byte[] blockDataBytes = nbtData.getByteArray("BlockData");
        parsed.blockData = new int[blockDataBytes.length];
        for (int i = 0; i < blockDataBytes.length; i++) {
            parsed.blockData[i] = blockDataBytes[i] & 0xFF; // Convert signed byte to unsigned int
        }

        return parsed;
    }




    // Helper method to decompress block data
    private static int[] decompressBlockData(byte[] blockData, int bitsPerBlock, int totalBlocks) {
        int[] blocks = new int[totalBlocks];
        int blockIndex = 0;
        int maxEntryValue = (1 << bitsPerBlock) - 1;

        int dataBitLength = blockData.length * 8;
        int bitIndex = 0;

        while (blockIndex < totalBlocks && bitIndex + bitsPerBlock <= dataBitLength) {
            int value = 0;

            int bitsCollected = 0;
            while (bitsCollected < bitsPerBlock) {
                int byteIndex = bitIndex / 8;
                int bitsLeftInByte = 8 - (bitIndex % 8);
                int bitsToRead = Math.min(bitsPerBlock - bitsCollected, bitsLeftInByte);

                int shift = bitsLeftInByte - bitsToRead;
                int mask = (1 << bitsToRead) - 1;
                int bits = (blockData[byteIndex] >> shift) & mask;

                value = (value << bitsToRead) | bits;

                bitIndex += bitsToRead;
                bitsCollected += bitsToRead;
            }

            blocks[blockIndex++] = value;
        }

        if (blockIndex < totalBlocks) {
            System.err.println("Warning: Expected " + totalBlocks + " blocks, but only read " + blockIndex);
        }

        return blocks;
    }






    public static Path getSchematicPath(String fileName) {
        return Paths.get( "stardance", "schematic", fileName);
    }

    private static BlockState getBlockStateFromString(String stateString) {
        // Split into block ID and properties
        String[] parts = stateString.split("\\[");
        String blockId = parts[0];
        String propertiesString = parts.length > 1 ? parts[1].replace("]", "") : "";

        // Get the block from the registry
        Block block = Registries.BLOCK.get(new Identifier(blockId));
        if (block == null) {
            throw new IllegalArgumentException("Invalid block ID: " + blockId);
        }

        // Start with the default block state
        BlockState blockState = block.getDefaultState();

        // If there are properties, parse and apply them
        if (!propertiesString.isEmpty()) {
            String[] propertyPairs = propertiesString.split(",");
            for (String pair : propertyPairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length != 2) {
                    throw new IllegalArgumentException("Invalid property format: " + pair);
                }
                String key = keyValue[0];
                String value = keyValue[1];

                // Find the property by name
                Property<?> property = blockState.getProperties()
                        .stream()
                        .filter(p -> p.getName().equals(key))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Invalid property for block " + blockId + ": " + key));

                // Parse and apply the property value
                blockState = applyProperty(blockState, property, value);
            }
        }

        return blockState;
    }

    // Helper method to apply a property value
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value) {
        Optional<T> optionalValue = property.parse(value);
        if (optionalValue.isPresent()) {
            return state.with(property, optionalValue.get());
        } else {
            throw new IllegalArgumentException("Invalid value for property " + property.getName() + ": " + value);
        }
    }


}
