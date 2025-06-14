package net.starlight.stardance.interaction;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.render.CollisionShapeRenderer;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

/**
 * Handles all raycast operations for grid interactions.
 */
public class GridRaycastEngine implements ILoggingControl {

    /**
     * Performs a raycast against the grid, using a hybrid of DDA and Minecraft's BlockHitResult.
     */
    public GridRaycastResult performGridRaycast(Vec3d startWorld, Vec3d endWorld, LocalGrid grid) {
        SLogger.log(this, "----- performGridRaycast START -----");
        
        // Convert to grid-local coordinate space
        Vector3d startVec = grid.worldToGridLocal(new Vector3d(startWorld.x, startWorld.y, startWorld.z));
        Vector3d endVec = grid.worldToGridLocal(new Vector3d(endWorld.x, endWorld.y, endWorld.z));

        Vec3d startLocal = new Vec3d(startVec.x, startVec.y, startVec.z);
        Vec3d endLocal = new Vec3d(endVec.x, endVec.y, endVec.z);

        // 1. Use DDA to find which block was hit
        BlockPos hitBlockPos = findHitBlockUsingDDA(startLocal, endLocal, grid);
        if (hitBlockPos == null) {
            return null;
        }

        // 2. Use Minecraft's BlockHitResult to determine exact hit position and face
        BlockHitResult mcHitResult = calculateBlockHitResult(startLocal, endLocal, hitBlockPos, grid);
        if (mcHitResult == null) {
            return null;
        }

        // 3. Extract information from the hit result
        BlockPos exactHitPos = mcHitResult.getBlockPos();
        Direction hitFace = mcHitResult.getSide();
        Vec3d hitPosLocal = mcHitResult.getPos();

        // 4. Convert hit position back to world space
        Vec3d hitPosWorld = convertLocalToWorld(hitPosLocal, grid);

        // For debug rendering
        CollisionShapeRenderer.eyePos = startLocal;
        CollisionShapeRenderer.lookVec = endLocal.subtract(startLocal);

        return new GridRaycastResult(exactHitPos, hitFace, hitPosWorld);
    }

    private BlockPos findHitBlockUsingDDA(Vec3d startLocal, Vec3d endLocal, LocalGrid grid) {
        Vec3d ray = endLocal.subtract(startLocal);
        double totalDist = ray.length();

        if (totalDist < 1e-8) {
            return null;
        }

        Vec3d dir = ray.normalize();
        double t = 0.0;

        int x = MathHelper.floor(startLocal.x);
        int y = MathHelper.floor(startLocal.y);
        int z = MathHelper.floor(startLocal.z);

        int stepX = dir.x > 0 ? 1 : (dir.x < 0 ? -1 : 0);
        int stepY = dir.y > 0 ? 1 : (dir.y < 0 ? -1 : 0);
        int stepZ = dir.z > 0 ? 1 : (dir.z < 0 ? -1 : 0);

        double dx = Math.abs(dir.x) < 1e-8 ? 1e-8 : dir.x;
        double dy = Math.abs(dir.y) < 1e-8 ? 1e-8 : dir.y;
        double dz = Math.abs(dir.z) < 1e-8 ? 1e-8 : dir.z;

        double tDeltaX = Math.abs(1.0 / dx);
        double tDeltaY = Math.abs(1.0 / dy);
        double tDeltaZ = Math.abs(1.0 / dz);

        double tx = nextTForAxis(startLocal.x, x, stepX, dx);
        double ty = nextTForAxis(startLocal.y, y, stepY, dy);
        double tz = nextTForAxis(startLocal.z, z, stepZ, dz);

        while (t <= totalDist) {
            BlockPos pos = new BlockPos(x, y, z);
            if (grid.getBlocks().containsKey(pos)) {
                return pos;
            }

            if (tx < ty && tx < tz) {
                x += stepX;
                t = tx;
                tx += tDeltaX;
            } else if (ty < tz) {
                y += stepY;
                t = ty;
                ty += tDeltaY;
            } else {
                z += stepZ;
                t = tz;
                tz += tDeltaZ;
            }

            if (t > totalDist) break;
        }

        return null;
    }

    private double nextTForAxis(double startCoord, int blockCoord, int step, double direction) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = (step > 0) ? (blockCoord + 1) : (blockCoord);
        double distToBoundary = boundary - startCoord;
        return distToBoundary / direction;
    }

    private BlockHitResult calculateBlockHitResult(Vec3d startLocal, Vec3d endLocal, BlockPos blockPos, LocalGrid grid) {
        VoxelShape shape = VoxelShapes.fullCube();
        BlockHitResult hitResult = raycastShape(shape, startLocal, endLocal, blockPos);

        if (hitResult == null || hitResult.getType() == BlockHitResult.Type.MISS) {
            Direction face = getClosestFace(startLocal, blockPos);
            return BlockHitResult.createMissed(endLocal, face, blockPos);
        }

        return hitResult;
    }

    private BlockHitResult raycastShape(VoxelShape shape, Vec3d start, Vec3d end, BlockPos pos) {
        Vec3d relStart = start.subtract(Vec3d.of(pos));
        Vec3d relEnd = end.subtract(Vec3d.of(pos));
        return raycastBox(relStart, relEnd, pos);
    }

    private BlockHitResult raycastBox(Vec3d start, Vec3d end, BlockPos pos) {
        Vec3d dir = end.subtract(start).normalize();
        
        double tMinX = intersectBox(start.x, dir.x, 0, 1);
        double tMinY = intersectBox(start.y, dir.y, 0, 1);
        double tMinZ = intersectBox(start.z, dir.z, 0, 1);
        
        double tMin = Math.max(Math.max(tMinX, tMinY), tMinZ);
        
        if (tMin < 0) {
            return null;
        }

        Direction face;
        if (tMin == tMinX) {
            face = dir.x > 0 ? Direction.WEST : Direction.EAST;
        } else if (tMin == tMinY) {
            face = dir.y > 0 ? Direction.DOWN : Direction.UP;
        } else {
            face = dir.z > 0 ? Direction.NORTH : Direction.SOUTH;
        }

        Vec3d hitPos = start.add(dir.multiply(tMin));
        Vec3d worldHitPos = hitPos.add(Vec3d.of(pos));

        return new BlockHitResult(worldHitPos, face, pos, false);
    }

    private double intersectBox(double start, double dir, double min, double max) {
        if (Math.abs(dir) < 1e-8) {
            return (start >= min && start <= max) ? 0 : Double.NEGATIVE_INFINITY;
        }

        double t1 = (min - start) / dir;
        double t2 = (max - start) / dir;

        if (t1 > t2) {
            double temp = t1;
            t1 = t2;
            t2 = temp;
        }

        return t1;
    }

    private Direction getClosestFace(Vec3d point, BlockPos pos) {
        double x = point.x - pos.getX();
        double y = point.y - pos.getY();
        double z = point.z - pos.getZ();

        double distWest = x;
        double distEast = 1 - x;
        double distDown = y;
        double distUp = 1 - y;
        double distNorth = z;
        double distSouth = 1 - z;

        double minDist = Double.MAX_VALUE;
        Direction closestFace = Direction.UP;

        if (distWest > 0 && distWest < minDist) { minDist = distWest; closestFace = Direction.WEST; }
        if (distEast > 0 && distEast < minDist) { minDist = distEast; closestFace = Direction.EAST; }
        if (distDown > 0 && distDown < minDist) { minDist = distDown; closestFace = Direction.DOWN; }
        if (distUp > 0 && distUp < minDist) { minDist = distUp; closestFace = Direction.UP; }
        if (distNorth > 0 && distNorth < minDist) { minDist = distNorth; closestFace = Direction.NORTH; }
        if (distSouth > 0 && distSouth < minDist) { minDist = distSouth; closestFace = Direction.SOUTH; }

        if (minDist == Double.MAX_VALUE) {
            minDist = Math.min(Math.min(Math.min(-distWest, -distEast),
                            Math.min(-distDown, -distUp)),
                    Math.min(-distNorth, -distSouth));

            if (minDist == -distWest) return Direction.WEST;
            if (minDist == -distEast) return Direction.EAST;
            if (minDist == -distDown) return Direction.DOWN;
            if (minDist == -distUp) return Direction.UP;
            if (minDist == -distNorth) return Direction.NORTH;
            if (minDist == -distSouth) return Direction.SOUTH;
        }

        return closestFace;
    }

    private Vec3d convertLocalToWorld(Vec3d localPos, LocalGrid grid) {
        Vector3f localVec = new Vector3f((float)localPos.x, (float)localPos.y, (float)localPos.z);
        com.bulletphysics.linearmath.Transform tf = new com.bulletphysics.linearmath.Transform();
        grid.getRigidBody().getWorldTransform(tf);
        tf.transform(localVec);
        return new Vec3d(localVec.x, localVec.y, localVec.z);
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() { return false; }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() { return false; }
}