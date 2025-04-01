package net.stardance.interaction;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class GridRaycastResult {
    private final BlockPos blockPosition;
    private final Direction hitFace;
    private final Vec3d hitPosition;

    public GridRaycastResult(BlockPos blockPosition, Direction hitFace, Vec3d hitPosition) {
        this.blockPosition = blockPosition;
        this.hitFace = hitFace;
        this.hitPosition = hitPosition;
    }

    public BlockPos getBlockPosition() {
        return blockPosition;
    }

    public Direction getHitFace() {
        return hitFace;
    }

    public Vec3d getHitPosition() {
        return hitPosition;
    }
}