package laoqi123.module.modules.player.helper.impl;

import laoqi123.event.impl.TickEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.module.modules.player.Helper;
import laoqi123.module.modules.player.helper.HelperBase;
import laoqi123.util.ChatUtil;
import laoqi123.util.PacketUtil;
import laoqi123.util.rotation.Rotation;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Self Extinguish（OpenNilore 移植）：自己着火时自动在脚下放水再收水。
 * 流程：着火且落地 → 切水桶、低头瞄准（5 tick 窗口）；
 * 预测要落地时放水（MLG 式）→ 水灭火后（运动包发送前）切空桶把水收走并还原热键栏。
 */
public class SelfExtinguish extends HelperBase {

    public Rotation targetRotation;
    private int savedSlot = -1;
    private int waterBucketSlot = -1;
    private boolean isAiming = false;
    private boolean shouldPickupWater = false;
    private BlockPos waterBlockPos;
    private int aimCooldown = 0;

    public SelfExtinguish() {
        super("Self Extinguish");
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
        this.waterBlockPos = null;
        this.targetRotation = null;
        if (this.savedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = this.savedSlot;
        }
        this.savedSlot = -1;
        this.waterBucketSlot = -1;
        this.isAiming = false;
        this.shouldPickupWater = false;
        this.aimCooldown = 0;
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (mc.player.isOnFire()) {
            if (this.isAiming && willCollideBelow(mc.player.getVelocity().y)) {
                this.shouldPickupWater = true;
            } else if (mc.player.isOnGround()) {
                this.waterBucketSlot = this.findHotbarSlot(Items.WATER_BUCKET);
                if (this.waterBucketSlot != -1) {
                    if (this.savedSlot == -1) {
                        this.savedSlot = mc.player.getInventory().selectedSlot;
                    }
                    mc.player.getInventory().selectedSlot = this.waterBucketSlot;
                    PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.waterBucketSlot));
                    this.isAiming = true;
                    this.targetRotation = new Rotation(mc.player.getYaw(), 90.0F);
                    this.aimCooldown = 5;
                }
            }
        }
        if (--this.aimCooldown == 0 && this.isAiming) {
            this.isAiming = false;
            this.targetRotation = null;
            if (this.savedSlot != -1) {
                mc.player.getInventory().selectedSlot = this.savedSlot;
                PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.savedSlot));
            }
        }
    }

    /** 运动包发送前：放水 / 收水（此时旋转覆盖已生效，use 包自带正确旋转） */
    @Override
    public void onPlayerUpdate(laoqi123.event.impl.PlayerUpdateEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (this.shouldPickupWater) {
            if (Helper.isRotationPending()) {
                return;
            }
            this.shouldPickupWater = false;
            // 低头瞄准（覆盖旋转已应用）：向下射线找落点，水会放在命中方块上方
            BlockHitResult hit = this.raycastDown(RaycastContext.FluidHandling.NONE);
            if (hit.getType() == HitResult.Type.BLOCK) {
                this.waterBlockPos = hit.getBlockPos().up();
                Helper.markWaterPlaced(this.waterBlockPos);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);
            } else {
                ChatUtil.sendMessage("Failed to place water!");
                this.isAiming = false;
                this.targetRotation = null;
            }
        } else if (this.waterBlockPos != null) {
            this.isAiming = false;
            this.targetRotation = null;
            // 收水：向下找水源（允许穿过流体打下面的方块来确认位置）
            BlockHitResult hit = this.raycastDown(RaycastContext.FluidHandling.NONE);
            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().up().equals(this.waterBlockPos)) {
                int bucketSlot = this.findHotbarSlot(Items.BUCKET);
                if (bucketSlot != -1) {
                    mc.player.getInventory().selectedSlot = bucketSlot;
                    PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(bucketSlot));
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                Helper.removeWaterPlacement(this.waterBlockPos);
            } else {
                ChatUtil.sendMessage("Failed to recycle water due to moving!");
            }
            if (this.savedSlot != -1) {
                mc.player.getInventory().selectedSlot = this.savedSlot;
                PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.savedSlot));
            }
            this.waterBlockPos = null;
        }
    }

    private BlockHitResult raycastDown(RaycastContext.FluidHandling fluidHandling) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d endPos = eyePos.add(0.0, -4.5, 0.0);
        return mc.world.raycast(new RaycastContext(eyePos, endPos,
                RaycastContext.ShapeType.OUTLINE, fluidHandling, mc.player));
    }

    private int findHotbarSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; ++i) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    /** 玩家按当前 Y 速度位移后是否会碰撞（预测是否要落地） */
    public static boolean willCollideBelow(double deltaY) {
        if (mc.world == null || mc.player == null) {
            return false;
        }
        return !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(0.0, deltaY, 0.0));
    }

    @Override
    public boolean isActive() {
        return this.isAiming && this.targetRotation != null;
    }

    @Override
    public Rotation getTargetRotation() {
        return this.targetRotation;
    }
}
