package laoqi123.module.modules.player.helper.impl;

import laoqi123.event.impl.Render3DEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.module.modules.player.Helper;
import laoqi123.module.modules.player.helper.HelperBase;
import laoqi123.util.RenderUtil;
import laoqi123.util.rotation.Rotation;
import net.minecraft.block.Blocks;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Extinguish Fire（OpenNilore 移植）：打掉身边 5 格内的火方块。
 * 瞄准最近的火 → 2 tick 延迟 → attackBlock 一下（火被打一下即灭）。
 */
public class ExtinguishFire extends HelperBase {

    public enum State { NONE, FIRE }

    public Rotation targetRotation;
    private BlockPos firePos;
    public ExtinguishFire.State currentState = ExtinguishFire.State.NONE;
    private boolean isAiming = false;
    private int aimDelay = 0;
    private static final String NAME = "Extinguish Fire";

    public ExtinguishFire() {
        super(NAME);
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    private void reset() {
        this.firePos = null;
        this.currentState = ExtinguishFire.State.NONE;
        this.targetRotation = null;
        this.isAiming = false;
        this.aimDelay = 0;
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.player.isUsingItem()) {
            return;
        }
        if (event.getType() != laoqi123.event.types.EventType.PRE) {
            return;
        }
        Vec3d eyePos = mc.player.getEyePos();
        if (this.currentState == ExtinguishFire.State.FIRE) {
            if (this.firePos == null
                    || !mc.world.getBlockState(this.firePos).isOf(Blocks.FIRE)
                    || eyePos.squaredDistanceTo(Vec3d.ofCenter(this.firePos)) > 25.0) {
                this.reset();
            } else {
                this.targetRotation = Rotation.lookingAt(Vec3d.ofCenter(this.firePos), eyePos);
                if (!this.isAiming) {
                    this.isAiming = true;
                    this.aimDelay = 2;
                }
            }
        } else {
            this.findFirePos();
            if (this.firePos != null) {
                this.currentState = ExtinguishFire.State.FIRE;
                this.targetRotation = Rotation.lookingAt(Vec3d.ofCenter(this.firePos), eyePos);
                this.isAiming = true;
                this.aimDelay = 2;
            }
        }
        if (this.isAiming && this.currentState == ExtinguishFire.State.FIRE && this.firePos != null) {
            if (this.aimDelay > 0) {
                --this.aimDelay;
                return;
            }
            // 平滑模式下等待服务器旋转到位再打
            if (Helper.isRotationPending(new Rotation(event.getYaw(), event.getPitch()))) {
                return;
            }
            mc.interactionManager.attackBlock(this.firePos, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
            this.isAiming = false;
        }
    }

    @Override
    public void onRender(Render3DEvent renderEvent) {
        if (this.currentState != ExtinguishFire.State.FIRE || this.firePos == null) {
            return;
        }
        RenderUtil.drawBlockBox(this.firePos, 1.0, 255, 0, 0);
        RenderUtil.drawBlockBoundingBox(this.firePos, 1.0, 255, 0, 0, 190, 1.5f);
    }

    private void findFirePos() {
        if (mc.player == null || mc.world == null) {
            this.firePos = null;
            return;
        }
        BlockPos playerPos = mc.player.getBlockPos();
        ArrayList<BlockPos> candidates = new ArrayList<>();
        Vec3d eyePos = mc.player.getEyePos();
        for (int dx = -8; dx <= 8; ++dx) {
            for (int dy = -2; dy <= 2; ++dy) {
                for (int dz = -8; dz <= 8; ++dz) {
                    BlockPos candidatePos = playerPos.add(dx, dy, dz);
                    if (!mc.world.getBlockState(candidatePos).isOf(Blocks.FIRE)) continue;
                    if (!(Vec3d.ofCenter(candidatePos).squaredDistanceTo(eyePos) <= 25.0)) continue;
                    if (!Helper.isPositionInFov(Vec3d.ofCenter(candidatePos))) continue;
                    candidates.add(candidatePos);
                }
            }
        }
        this.firePos = candidates.stream()
                .min(Comparator.comparingDouble(blockPos -> Vec3d.ofCenter(blockPos).squaredDistanceTo(eyePos)))
                .orElse(null);
    }

    @Override
    public boolean isActive() {
        return this.targetRotation != null;
    }

    @Override
    public Rotation getTargetRotation() {
        return this.targetRotation;
    }
}
