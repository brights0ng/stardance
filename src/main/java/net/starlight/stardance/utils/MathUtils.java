package net.starlight.stardance.utils;

import org.joml.Quaternionf;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;
import net.minecraft.world.phys.Vec3;

public class MathUtils {
    public static Quaternionf toQuaternionf(Quat4f quat4f) {
        return new Quaternionf(quat4f.x, quat4f.y, quat4f.z, quat4f.w);
    }

    public static Vec3 toVec3d(Vector3d vector3d){
        return new Vec3(vector3d.x, vector3d.y, vector3d.z);
    }
}
