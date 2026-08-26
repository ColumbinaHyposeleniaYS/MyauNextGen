package laoqi123.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class RotationUtil {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final float BORDER_SIZE = 0.3F;

    public static float wrapAngleDiff(float angle, float target) {
        return target + MathHelper.wrapDegrees(angle - target);
    }

    public static float clampAngle(float angle, float maxAngle) {
        maxAngle = Math.max(0.0f, Math.min(180.0f, maxAngle));
        if (angle > maxAngle) {
            angle = maxAngle;
        } else if (angle < -maxAngle) {
            angle = -maxAngle;
        }
        return angle;
    }

    public static boolean hasVisiblePoint(Box boundingBox) {
        Vec3d eyePos = RotationUtil.mc.player.getEyePos();
        double centerX = (boundingBox.minX + boundingBox.maxX) / 2.0;
        double centerZ = (boundingBox.minZ + boundingBox.maxZ) / 2.0;
        double height = boundingBox.maxY - boundingBox.minY;
        double[] yRatios = new double[]{0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};

        for (double ratio : yRatios) {
            double targetY = boundingBox.minY + ratio * height;
            Vec3d targetPoint = new Vec3d(centerX, targetY, centerZ);
            if (rayTraceBlocks(eyePos, targetPoint) == null) {
                return true;
            }
        }
        return false;
    }

    public static float smoothAngle(float angle, float smoothFactor) {
        return angle * (0.5f + 0.5f * (1.0f - Math.max(0.0f, Math.min(1.0f, smoothFactor + RandomUtil.nextFloat(-0.1f, 0.1f)))));
    }

    public static float quantizeAngle(float angle) {
        return (float) ((double) angle - (double) angle % (double) 0.0096f);
    }

    public static float[] getRotationsToBox(Box boundingBox, float yaw, float pitch, float maxAngle, float smoothFactor) {
        Vec3d eyePos = RotationUtil.mc.player.getEyePos();
        double minTargetY = boundingBox.minY + 0.05 * (boundingBox.maxY - boundingBox.minY);
        double maxTargetY = boundingBox.minY + 0.75 * (boundingBox.maxY - boundingBox.minY);
        double deltaX = (boundingBox.minX + boundingBox.maxX) / 2.0 - eyePos.x;
        double deltaY = eyePos.y >= maxTargetY ? maxTargetY - eyePos.y : (eyePos.y <= minTargetY ? minTargetY - eyePos.y : 0.0);
        double deltaZ = (boundingBox.minZ + boundingBox.maxZ) / 2.0 - eyePos.z;
        return RotationUtil.getRotations(deltaX, deltaY, deltaZ, yaw, pitch, maxAngle, smoothFactor);
    }

    public static float[] getRotationsTo(double targetX, double targetY, double targetZ, float currentYaw, float currentPitch) {
        return RotationUtil.getRotations(targetX, targetY, targetZ, currentYaw, currentPitch, 180.0f, 0.0f);
    }

    public static float[] getRotations(double targetX, double targetY, double targetZ, float currentYaw, float currentPitch, float maxAngle, float smoothFactor) {
        double horizontalDistance = Math.sqrt(targetX * targetX + targetZ * targetZ);
        float yawDelta = MathHelper.wrapDegrees((float) (Math.atan2(targetZ, targetX) * 180.0 / Math.PI) - 90.0f - currentYaw);
        float pitchDelta = MathHelper.wrapDegrees((float) (-Math.atan2(targetY, horizontalDistance) * 180.0 / Math.PI) - currentPitch);
        yawDelta = Math.abs(yawDelta) <= 1.0f ? 0.0f : RotationUtil.smoothAngle(RotationUtil.clampAngle(yawDelta, maxAngle), smoothFactor);
        pitchDelta = Math.abs(pitchDelta) <= 1.0f ? 0.0f : RotationUtil.smoothAngle(RotationUtil.clampAngle(pitchDelta, maxAngle), smoothFactor);
        return new float[]{RotationUtil.quantizeAngle(currentYaw + yawDelta), RotationUtil.quantizeAngle(currentPitch + pitchDelta)};
    }

    public static Vec3d clampVecToBox(Vec3d vector, Box boundingBox) {
        double[] coords = new double[]{vector.x, vector.y, vector.z};
        double[] minCoords = new double[]{boundingBox.minX, boundingBox.minY, boundingBox.minZ};
        double[] maxCoords = new double[]{boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ};
        for (int i = 0; i < 3; ++i) {
            if (coords[i] > maxCoords[i]) {
                coords[i] = maxCoords[i];
                continue;
            }
            if (!(coords[i] < minCoords[i])) continue;
            coords[i] = minCoords[i];
        }
        return new Vec3d(coords[0], coords[1], coords[2]);
    }

    public static double distanceToEntity(Entity entity) {
        Box boundingBox = entity.getBoundingBox().expand(BORDER_SIZE, BORDER_SIZE, BORDER_SIZE);
        return RotationUtil.distanceToBox(boundingBox);
    }

    public static double distanceToBox(Entity entity, Vec3d point) {
        return RotationUtil.clampVecToBox(entity.getBoundingBox().expand(BORDER_SIZE, BORDER_SIZE, BORDER_SIZE), point);
    }

    public static double distanceToBox(Box boundingBox) {
        return RotationUtil.clampVecToBox(boundingBox, RotationUtil.mc.player.getEyePos());
    }

    public static double clampVecToBox(Box boundingBox, Vec3d point) {
        if (boundingBox.contains(point)) {
            return 0.0;
        }
        Vec3d clampedPoint = RotationUtil.clampVecToBox(point, boundingBox);
        double deltaX = clampedPoint.x - point.x;
        double deltaY = clampedPoint.y - point.y;
        double deltaZ = clampedPoint.z - point.z;
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }

    public static float angleToEntity(Entity entity) {
        Vec3d eyePos = RotationUtil.mc.player.getEyePos();
        Box boundingBox = entity.getBoundingBox().expand(BORDER_SIZE, BORDER_SIZE, BORDER_SIZE);
        if (boundingBox.contains(eyePos)) {
            return 0.0f;
        }
        double deltaX = entity.getX() - eyePos.x;
        double deltaZ = entity.getZ() - eyePos.z;
        return Math.abs(MathHelper.wrapDegrees((float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0f - RotationUtil.mc.player.getYaw())) * 2.0f;
    }

    public static float getYawBetween(double x1, double z1, double x2, double z2) {
        return MathHelper.wrapDegrees((float) (Math.atan2(z2 - z1, x2 - x1) * 180.0 / Math.PI) - 90.0f - RotationUtil.mc.player.getYaw());
    }

    public static HitResult rayTrace(float yaw, float pitch, double distance, float partialTicks) {
        Vec3d eyePos = RotationUtil.mc.player.getEyePos();
        Vec3d lookVec = getVectorForRotation(pitch, yaw);
        Vec3d targetPos = eyePos.add(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance);
        return RotationUtil.mc.world.raycast(new RaycastContext(eyePos, targetPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, RotationUtil.mc.player));
    }

    public static HitResult rayTrace(Entity entity) {
        Vec3d eyePos = RotationUtil.mc.player.getEyePos();
        Vec3d targetPos = RotationUtil.clampVecToBox(eyePos, entity.getBoundingBox().expand(BORDER_SIZE, BORDER_SIZE, BORDER_SIZE));
        return RotationUtil.mc.world.raycast(new RaycastContext(eyePos, targetPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, RotationUtil.mc.player));
    }

    public static HitResult rayTrace(Box boundingBox, float yaw, float pitch, double distance) {
        Vec3d eyePos = RotationUtil.mc.player.getEyePos();
        Vec3d lookVec = getVectorForRotation(pitch, yaw);
        Vec3d targetPos = eyePos.add(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance);
        return boundingBox.raycast(eyePos, targetPos).map(Vec3d -> new BlockHitResult(Vec3d, net.minecraft.util.math.Direction.getFacing(lookVec.x, lookVec.y, lookVec.z).getOpposite(), net.minecraft.util.math.BlockPos.ofFloored(boundingBox.minX, boundingBox.minY, boundingBox.minZ), false)).orElse(null);
    }

    public static Vec3d getVectorForRotation(float pitch, float yaw) {
        float f = MathHelper.cos(-yaw * 0.017453292F - 3.1415927F);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - 3.1415927F);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3d(f1 * f2, f3, f * f2);
    }

    /**
     * 计算把投射物（珍珠：初速 1.5 / 重力 0.03 / 阻尼 0.99）扔到目标点所需的 [yaw, pitch]。
     * 迭代 pitch（90 → -90，步长 0.5）模拟弹道，找水平距离到达且高度差 < 1 的解。
     * （OpenNilore RotationUtil.getBallisticAngles 移植）
     */
    public static float[] getBallisticAngles(Vec3d target) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Vec3d eyePos = mc.player.getEyePos();
        double velocity = 1.5;
        double gravity = 0.03;
        double drag = 0.99;
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist == 0.0) {
            return new float[]{yaw, dy > 0.0 ? -90.0f : 90.0f};
        }
        block0:
        for (float pitch = 90.0f; pitch >= -90.0f; pitch -= 0.5f) {
            double pitchRad = Math.toRadians(pitch);
            double velX = -Math.sin(Math.toRadians(yaw)) * Math.cos(pitchRad);
            double velY = -Math.sin(pitchRad);
            double velZ = Math.cos(Math.toRadians(yaw)) * Math.cos(pitchRad);
            double mag = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
            velX = velX / mag * velocity;
            velY = velY / mag * velocity;
            velZ = velZ / mag * velocity;
            Vec3d projectilePos = new Vec3d(eyePos.x, eyePos.y, eyePos.z);
            Vec3d projectileVel = new Vec3d(velX, velY, velZ);
            for (int i = 0; i < 300; ++i) {
                projectilePos = projectilePos.add(projectileVel);
                projectileVel = new Vec3d(projectileVel.x * drag, projectileVel.y * drag - gravity, projectileVel.z * drag);
                if (projectilePos.y < (double) (mc.world.getBottomY() - 10)) continue block0;
                double traveledDist = Math.sqrt(Math.pow(projectilePos.x - eyePos.x, 2.0) + Math.pow(projectilePos.z - eyePos.z, 2.0));
                if (!(traveledDist >= horizontalDist)) continue;
                if (Math.abs(projectilePos.y - target.y) < 1.0) {
                    return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.wrapDegrees(pitch)};
                }
                if (projectileVel.y < 0.0 && projectilePos.y < target.y) continue block0;
            }
        }
        return null;
    }

    private static HitResult rayTraceBlocks(Vec3d start, Vec3d end) {
        return RotationUtil.mc.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, RotationUtil.mc.player));
    }

    public static final class RotationVec {
        public float x;
        public float y;

        public RotationVec(RotationVec vec) {
            this(vec.x, vec.y);
        }

        public RotationVec(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public RotationVec add(float x, float y) {
            return new RotationVec(this.x + x, this.y + y);
        }

        public float getX() {
            return this.x;
        }

        public float getY() {
            return this.y;
        }

        public void setX(float x) {
            this.x = x;
        }

        public void setY(float y) {
            this.y = y;
        }
    }
}
