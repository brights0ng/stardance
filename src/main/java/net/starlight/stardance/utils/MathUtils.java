package net.starlight.stardance.utils;

import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;

public class MathUtils {
    public static Quaternionf toQuaternionf(Quat4f quat4f) {
        return new Quaternionf(quat4f.x, quat4f.y, quat4f.z, quat4f.w);
    }

    public static Vec3d toVec3d(Vector3d vector3d){
        return new Vec3d(vector3d.x, vector3d.y, vector3d.z);
    }
}
