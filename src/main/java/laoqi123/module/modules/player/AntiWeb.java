package laoqi123.module.modules.player;

import laoqi123.event.EventTarget;
import laoqi123.event.impl.PlayerUpdateEvent;
import laoqi123.event.impl.Render3DEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.event.types.EventType;
import laoqi123.module.Module;
import laoqi123.util.ChatUtil;
import laoqi123.util.PacketUtil;
import laoqi123.util.RenderUtil;
import laoqi123.util.rotation.Rotation;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * AntiWeb（OpenNilore 移植）：进蜘蛛网时自动用水桶脱困。
 * IDLE → PLACING（朝网顶放水，水冲掉网）→ RECYCLING（用空桶把水收回来，还原热键栏）。
 * 旋转通过 UpdateEvent 覆盖（运动包携带），use 包自带相同旋转，服务器视角一致。
 */
public class AntiWeb extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int ROTATION_PRIORITY = 8;
    private static final int PLACING_TIMEOUT = 40;

    public enum Phase { IDLE, PLACING, RECYCLING }

    public Phase currentPhase = Phase.IDLE;

    private int webCheckTicks = 0;
    private int placingTicks = 0;
    private int pickupTicks = 0;
    private int recycleUseCooldown = 0;
    private BlockPos webPos;
    private int savedHotbarSlot = -1;
    private int waterBucketSlot = -1;
    private boolean sentUsePacket = false;
    private boolean pendingUse = false;
    private BlockPos waterSourcePos = null;

    public AntiWeb() {
        super("AntiWeb", false);
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        if (mc.options != null && mc.options.useKey.isPressed()) {
            mc.options.useKey.setPressed(false);
        }
        this.reset();
    }

    private void reset() {
        if (mc.options != null && mc.options.useKey.isPressed()) {
            mc.options.useKey.setPressed(false);
        }
        if (this.savedHotbarSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = this.savedHotbarSlot;
            PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.savedHotbarSlot));
            this.savedHotbarSlot = -1;
        }
        this.currentPhase = Phase.IDLE;
        this.webPos = null;
        this.waterSourcePos = null;
        this.sentUsePacket = false;
        this.pendingUse = false;
        this.webCheckTicks = 0;
        this.placingTicks = 0;
        this.pickupTicks = 0;
        this.recycleUseCooldown = 0;
    }

    private boolean isInCobweb() {
        if (mc.player == null || mc.world == null) {
            return false;
        }
        net.minecraft.util.math.Box box = mc.player.getBoundingBox().expand(0.1);
        for (int x = MathHelper.floor(box.minX); x <= MathHelper.floor(box.maxX); ++x) {
            for (int y = MathHelper.floor(box.minY); y <= MathHelper.floor(box.maxY); ++y) {
                for (int z = MathHelper.floor(box.minZ); z <= MathHelper.floor(box.maxZ); ++z) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) {
                        this.webPos = pos;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findNearestWaterSource(BlockPos origin) {
        if (mc.world == null) {
            return null;
        }
        if (mc.world.getBlockState(origin).isOf(Blocks.WATER)
                && mc.world.getFluidState(origin).isStill()) {
            return origin;
        }
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dz = -1; dz <= 1; ++dz) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (mc.world.getBlockState(pos).isOf(Blocks.WATER)
                            && mc.world.getFluidState(pos).isStill()) {
                        return pos;
                    }
                }
            }
        }
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dz = -1; dz <= 1; ++dz) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (mc.world.getBlockState(pos).isOf(Blocks.WATER)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }

        // 放水成功：网变成水 → 进入收水阶段
        if (this.currentPhase == Phase.PLACING && this.webPos != null
                && mc.world.getBlockState(this.webPos).isOf(Blocks.WATER)) {
            this.waterSourcePos = this.findNearestWaterSource(this.webPos);
            if (this.waterSourcePos != null) {
                Helper.markWaterPlaced(this.waterSourcePos);
                this.currentPhase = Phase.RECYCLING;
                this.sentUsePacket = false;
                this.recycleUseCooldown = 0;
                this.pickupTicks = 0;
            } else {
                ChatUtil.sendMessage("Could not find water source!");
                this.reset();
            }
        }

        switch (this.currentPhase) {
            case IDLE -> {
                ++this.webCheckTicks;
                if (mc.player.isTouchingWater() || this.webCheckTicks < 5 || !this.isInCobweb()) {
                    return;
                }
                this.waterBucketSlot = this.findHotbarSlot(Items.WATER_BUCKET);
                if (this.waterBucketSlot == -1) {
                    return;
                }
                this.currentPhase = Phase.PLACING;
                this.placingTicks = 0;
            }
            case PLACING -> {
                if (!this.isInCobweb()) {
                    this.reset();
                    return;
                }
                if (++this.placingTicks > PLACING_TIMEOUT) {
                    ChatUtil.sendMessage("AntiWeb placing timeout, giving up!");
                    this.reset();
                    return;
                }
                if (this.sentUsePacket) {
                    // 已放水：保持瞄准直到网被冲掉
                    this.setWebRotation(event);
                    return;
                }
                if (this.savedHotbarSlot == -1) {
                    this.savedHotbarSlot = mc.player.getInventory().selectedSlot;
                }
                mc.player.getInventory().selectedSlot = this.waterBucketSlot;
                PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.waterBucketSlot));
                this.setWebRotation(event);
                // 本 tick 运动包发出前使用水桶（旋转已生效）
                this.pendingUse = true;
                this.sentUsePacket = true;
            }
            case RECYCLING -> {
                if (++this.pickupTicks > 20) {
                    ChatUtil.sendMessage("Pickup water timeout after 20 ticks, giving up!");
                    this.reset();
                    return;
                }
                if (mc.player.getMainHandStack().getItem() == Items.WATER_BUCKET) {
                    // 空桶重新装满水：收水成功
                    this.reset();
                    return;
                }
                if (this.waterSourcePos == null
                        || !mc.world.getBlockState(this.waterSourcePos).isOf(Blocks.WATER)) {
                    ChatUtil.sendMessage("Failed to recycle water!");
                    this.reset();
                    return;
                }
                if (mc.player.getMainHandStack().getItem() != Items.BUCKET) {
                    // 物品还没同步成空桶：重新选中桶所在槽位等待
                    mc.player.getInventory().selectedSlot = this.waterBucketSlot;
                    PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.waterBucketSlot));
                    return;
                }
                // 瞄准水源
                Rotation rotation = Rotation.lookingAt(Vec3d.ofCenter(this.waterSourcePos), mc.player.getEyePos());
                event.setRotation(rotation.getYaw(), rotation.getPitch(), ROTATION_PRIORITY);
                if (this.recycleUseCooldown > 0) {
                    --this.recycleUseCooldown;
                    return;
                }
                ChatUtil.sendMessage("Trying to recycle water...");
                this.pendingUse = true;
                this.recycleUseCooldown = 5;
            }
        }
    }

    private void setWebRotation(UpdateEvent event) {
        Rotation rotation = Rotation.lookingAt(
                Vec3d.ofCenter(this.webPos).add(0.0, 0.5, 0.0), mc.player.getEyePos());
        event.setRotation(rotation.getYaw(), rotation.getPitch(), ROTATION_PRIORITY);
    }

    /** 运动包发送前：旋转覆盖已应用，use 包携带相同旋转 */
    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || !this.pendingUse) {
            return;
        }
        this.pendingUse = false;
        if (mc.player != null && mc.interactionManager != null) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!this.isEnabled() || this.currentPhase == Phase.IDLE) {
            return;
        }
        if (this.webPos != null) {
            RenderUtil.drawBlockBox(this.webPos, 1.0, 0, 150, 255);
            RenderUtil.drawBlockBoundingBox(this.webPos, 1.0, 0, 150, 255, 190, 1.5f);
        }
        if (this.waterSourcePos != null && this.currentPhase == Phase.RECYCLING) {
            RenderUtil.drawBlockBox(this.waterSourcePos, 1.0, 0, 255, 0);
            RenderUtil.drawBlockBoundingBox(this.waterSourcePos, 1.0, 0, 255, 0, 190, 1.5f);
        }
    }

    private int findHotbarSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; ++i) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }
}
