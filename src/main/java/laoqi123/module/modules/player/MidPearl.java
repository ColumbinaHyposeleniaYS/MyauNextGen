package laoqi123.module.modules.player;

import laoqi123.Myau;
import laoqi123.event.EventTarget;
import laoqi123.event.impl.PlayerUpdateEvent;
import laoqi123.event.impl.TickEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.event.types.EventType;
import laoqi123.module.Module;
import laoqi123.module.modules.combat.KillAura;
import laoqi123.util.PacketUtil;
import laoqi123.util.RotationUtil;
import laoqi123.util.rotation.Rotation;
import laoqi123.value.properties.BooleanValue;
import laoqi123.value.properties.ModeValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/**
 * MidPearl（OpenNilore 移植）：
 * - 普通模式：按住设定鼠标键自动选中珍珠，松开即扔、还原槽位（快捷珍珠）。
 * - Intercept：瞄准敌方珍珠，用弹道计算扔出自己的珍珠在空中对撞（拦截）。
 * 投掷旋转通过 UpdateEvent 覆盖（运动包携带），use 包自带相同旋转。
 */
public class MidPearl extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int ROTATION_PRIORITY = 7;

    private final ModeValue buttonSetting = new ModeValue("Button", 0, new String[]{"Middle", "Mouse 4", "Mouse 5"});
    private final BooleanValue interceptSetting = new BooleanValue("Intercept", false);
    private final ModeValue interceptButtonSetting = new ModeValue("Intercept Button", 0, new String[]{"Mouse 4", "Mouse 5"});
    private final BooleanValue holdModeSetting = new BooleanValue("On Release", false);
    private final BooleanValue losCheckSetting = new BooleanValue("Block Check", true);

    public Rotation targetRotation = null;
    private boolean isHoldingIntercept = false;
    private boolean isHoldingButton = false;
    private boolean pearlSlotSelected = false;
    private int throwCountdown = 0;
    private int disableDelay = 0;
    private int pearlSavedSlot = -1;
    private boolean throwPhaseActive = false;
    private int preThrowTicks = 0;
    private int rodSavedSlot = -1;
    private int postThrowTicks = 0;
    private boolean pendingThrow = false;

    public MidPearl() {
        super("MidPearl", false);
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        if (this.pearlSavedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = this.pearlSavedSlot;
        }
        if (this.rodSavedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = this.rodSavedSlot;
        }
        this.reset();
    }

    private void reset() {
        this.pearlSlotSelected = false;
        this.throwCountdown = 0;
        this.disableDelay = 0;
        this.pearlSavedSlot = -1;
        this.throwPhaseActive = false;
        this.targetRotation = null;
        this.preThrowTicks = 0;
        this.isHoldingIntercept = false;
        this.isHoldingButton = false;
        this.rodSavedSlot = -1;
        this.postThrowTicks = 0;
        this.pendingThrow = false;
    }

    private void cancelThrow() {
        this.throwPhaseActive = false;
        this.targetRotation = null;
        this.preThrowTicks = 0;
        this.isHoldingIntercept = false;
    }

    private int getMouseButton(ModeValue setting) {
        return switch (setting.getModeString()) {
            case "Mouse 4" -> 3;
            case "Mouse 5" -> 4;
            default -> 2;
        };
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            if (this.throwPhaseActive) {
                this.reset();
            }
            return;
        }
        // 拦截投掷完成：延迟还原槽位
        if (this.postThrowTicks > 0) {
            --this.postThrowTicks;
            if (this.postThrowTicks == 0 && this.rodSavedSlot != -1) {
                mc.player.getInventory().selectedSlot = this.rodSavedSlot;
                PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.rodSavedSlot));
                this.rodSavedSlot = -1;
            }
        }
        // 普通投掷完成：延迟还原槽位并重置
        if (this.disableDelay > 0) {
            --this.disableDelay;
            if (this.disableDelay == 0) {
                if (this.pearlSavedSlot != -1) {
                    mc.player.getInventory().selectedSlot = this.pearlSavedSlot;
                    PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(this.pearlSavedSlot));
                }
                this.reset();
            }
            return;
        }
        // 拦截投掷阶段：倒计时归零标记投掷（在运动包发送前执行）
        if (this.throwPhaseActive) {
            if (this.preThrowTicks > 0) {
                --this.preThrowTicks;
            }
            if (this.preThrowTicks == 0) {
                this.pendingThrow = true;
            }
            return;
        }
        if (this.interceptSetting.getValue()) {
            boolean pressed = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(),
                    this.getMouseButton(this.interceptButtonSetting)) == 1;
            if (pressed) {
                if (this.isHoldingButton) {
                    return;
                }
            } else {
                this.isHoldingButton = false;
            }
            if (this.holdModeSetting.getValue()) {
                if (pressed) {
                    this.updateTargetRotation();
                    this.isHoldingIntercept = true;
                } else {
                    if (this.isHoldingIntercept) {
                        if (this.targetRotation != null) {
                            this.selectPearlSlot(this.findHotbarSlot(Items.ENDER_PEARL));
                        } else {
                            this.cancelThrow();
                        }
                    }
                    this.isHoldingIntercept = false;
                }
            } else if (pressed) {
                this.updateTargetRotation();
                if (this.targetRotation != null) {
                    this.selectPearlSlot(this.findHotbarSlot(Items.ENDER_PEARL));
                }
            } else {
                this.cancelThrow();
            }
            boolean sameButton = this.getMouseButton(this.interceptButtonSetting) == this.getMouseButton(this.buttonSetting);
            if (sameButton && (pressed || this.isHoldingIntercept)) {
                return;
            }
        }
        this.tickNormalMode();
    }

    /** 投掷阶段持续下发拦截旋转 */
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.throwPhaseActive && this.targetRotation != null && mc.player != null) {
            event.setRotation(this.targetRotation.getYaw(), this.targetRotation.getPitch(), ROTATION_PRIORITY);
        }
    }

    /** 运动包发送前：旋转覆盖已应用，use 包携带相同旋转 */
    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || !this.pendingThrow) {
            return;
        }
        this.pendingThrow = false;
        if (mc.player == null || mc.interactionManager == null) {
            return;
        }
        if (mc.player.getMainHandStack().getItem() == Items.ENDER_PEARL) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        this.postThrowTicks = 2;
        this.throwPhaseActive = false;
        this.preThrowTicks = -1;
        this.isHoldingButton = true;
    }

    private void updateTargetRotation() {
        this.targetRotation = this.calculateTargetRotation();
    }

    private Rotation calculateTargetRotation() {
        EnderPearlEntity pearl = this.findNearestEnemyPearl();
        if (pearl == null) {
            return null;
        }
        int slot = this.findHotbarSlot(Items.ENDER_PEARL);
        if (slot == -1) {
            return null;
        }
        Vec3d target = pearl.getPos();
        if (this.losCheckSetting.getValue() && mc.world != null && mc.player != null) {
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                    mc.player.getEyePos(), target,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                return null;
            }
        }
        float[] angles = RotationUtil.getBallisticAngles(target);
        if (angles != null) {
            return new Rotation(angles[0], angles[1]);
        }
        return null;
    }

    private void selectPearlSlot(int slot) {
        if (slot == -1 || this.throwPhaseActive || mc.player == null) {
            return;
        }
        this.throwPhaseActive = true;
        this.preThrowTicks = 2;
        this.rodSavedSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;
        PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void tickNormalMode() {
        if (this.throwCountdown > 0) {
            --this.throwCountdown;
            if (this.throwCountdown == 0) {
                this.pendingThrow = true;
                this.disableDelay = 2;
            }
            return;
        }
        boolean pressed = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(),
                this.getMouseButton(this.buttonSetting)) == 1;
        if (pressed) {
            if (!this.pearlSlotSelected) {
                int slot = this.findHotbarSlot(Items.ENDER_PEARL);
                if (slot != -1) {
                    this.pearlSlotSelected = true;
                    this.pearlSavedSlot = mc.player.getInventory().selectedSlot;
                    Module killAura = Myau.moduleManager.modules.get(KillAura.class);
                    if (killAura != null && killAura.isEnabled()) {
                        killAura.setEnabled(false);
                    }
                    mc.player.getInventory().selectedSlot = slot;
                    PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
                }
            }
        } else if (this.pearlSlotSelected) {
            this.pearlSlotSelected = false;
            this.throwCountdown = 1;
        }
    }

    private EnderPearlEntity findNearestEnemyPearl() {
        if (mc.world == null || mc.player == null) {
            return null;
        }
        EnderPearlEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl)) {
                continue;
            }
            Entity owner = pearl.getOwner();
            if (owner == null || owner == mc.player) {
                continue;
            }
            double dist = pearl.getPos().squaredDistanceTo(mc.player.getPos());
            if (dist < bestDist) {
                bestDist = dist;
                best = pearl;
            }
        }
        return best;
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
