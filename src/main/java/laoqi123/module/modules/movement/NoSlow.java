package laoqi123.module.modules.movement;

import com.mojang.datafixers.util.Pair;
import laoqi123.Myau;
import laoqi123.enums.BlinkModules;
import laoqi123.enums.FloatModules;
import laoqi123.event.EventTarget;
import laoqi123.event.types.EventType;
import laoqi123.event.types.Priority;
import laoqi123.event.impl.*;
import laoqi123.module.Module;
import laoqi123.module.modules.combat.KillAura;
import laoqi123.value.properties.*;
import laoqi123.value.properties.BooleanValue;
import laoqi123.value.properties.IntValue;
import laoqi123.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NoSlow extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public final ModeValue swordMode = new ModeValue("Sword Mode", 1, new String[]{"None", "Vanilla", "Hypixel", "Grim1.9+"});
    public final IntValue swapDelay = new IntValue("Swap Delay", 0, 0, 3, () -> swordMode.getValue() == 2);
    public final BooleanValue noAttack = new BooleanValue("No Attack", false, () -> swordMode.getValue() == 2);
    public final PercentValue swordMotion = new PercentValue("Sword Motion", 100, () -> this.swordMode.getValue() != 0);
    public final BooleanValue swordSprint = new BooleanValue("Sword Sprint", true, () -> this.swordMode.getValue() != 0);
    public final BooleanValue onlyKillAuraAutoBlock = new BooleanValue("Only Kill Aura Auto Block", false, () -> this.swordMode.getValue() != 0);
    public final ModeValue foodMode = new ModeValue("Food Mode", 0, new String[]{"None", "Vanilla", "Float", "Watchdog Prediction"});
    public final PercentValue foodMotion = new PercentValue("Food Motion", 100, () -> this.foodMode.getValue() != 0);
    public final BooleanValue foodSprint = new BooleanValue("Food Sprint", true, () -> this.foodMode.getValue() != 0);
    public final ModeValue bowMode = new ModeValue("Bow Mode", 0, new String[]{"None", "Vanilla", "Float", "Watchdog Prediction"});
    public final PercentValue bowMotion = new PercentValue("Bow Motion", 100, () -> this.bowMode.getValue() != 0);
    public final BooleanValue bowSprint = new BooleanValue("Bow Sprint", true, () -> this.bowMode.getValue() != 0);
    public final IntValue maxPingSpoof = new IntValue("Max Ping Spoof", 8, 0, 30, () -> this.foodMode.getValue() == 3 || this.bowMode.getValue() == 3);
    public final IntValue whenToFinishEating = new IntValue("When To Finish Eating", 30, 20, 36, () -> this.foodMode.getValue() == 3 || this.bowMode.getValue() == 3);

    private int delay = 0;
    private boolean post = false;
    private boolean watchdogBlink;
    private boolean watchdogUsing;
    private boolean watchdogForceStopped;
    private int usingTicks;

    // Grim1.9+（OpenNilore NoC0F 移植）：换手中断 + 入站同步包缓冲
    private boolean grimUseInterrupted = false;
    private int grimQueueTicks = 0;
    private boolean grimFlushing = false;
    private final Queue<Packet<ClientPlayPacketListener>> grimHeldPackets = new ConcurrentLinkedQueue<>();

    public NoSlow() {
        super("NoSlow", false);
    }

    @Override
    public void onDisabled() {
        if (this.watchdogForceStopped) {
            KeyBindUtil.updateKeyState(mc.options.useKey);
            this.watchdogForceStopped = false;
        }
        this.releaseWatchdog();
        this.grimUseInterrupted = false;
        if (this.grimQueueTicks > 0 || !this.grimHeldPackets.isEmpty()) {
            this.flushGrimPackets();
        }
    }

    private boolean isWatchdogActive() {
        return this.isEnabled() && mc.player != null
                && (this.foodMode.getValue() == 3 && ItemUtil.isEating()
                || this.bowMode.getValue() == 3 && ItemUtil.isUsingBow());
    }

    private void releaseWatchdog() {
        if (this.watchdogBlink) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.NO_SLOW);
        }
        this.watchdogBlink = false;
        this.watchdogUsing = false;
        this.usingTicks = 0;
    }

    public boolean isSwordActive() {
        return this.swordMode.getValue() != 0 && ItemUtil.isHoldingSword() && (!this.onlyKillAuraAutoBlock.getValue() || this.isKillAuraAutoBlocking());
    }

    public boolean isFoodActive() {
        return this.foodMode.getValue() != 0 && ItemUtil.isEating();
    }

    public boolean isBowActive() {
        return this.bowMode.getValue() != 0 && ItemUtil.isUsingBow();
    }

    public boolean isFloatMode() {
        return this.foodMode.getValue() == 2 && ItemUtil.isEating()
                || this.bowMode.getValue() == 2 && ItemUtil.isUsingBow();
    }

    private boolean isKillAuraAutoBlocking() {
        KillAura aura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (!aura.isPlayerBlocking() || !aura.isEnabled()) {
            return false;
        }
        return aura.isBlocking();
    }

    public boolean isAnyActive() {
        if (this.swordMode.getValue() != 2) {
            return mc.player.isUsingItem() && (this.isSwordActive() || this.isFoodActive() || this.isBowActive());
        } else if (this.swordMode.getValue() == 2 && isSwordActive()) {
            KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
            if (!noAttack.getValue() || !((killAura.getAutoBlock().getBlockTick() == 0 && killAura.getAutoBlock().mode.getValue() == 2) || (killAura.getAutoBlock().mode.getValue() == 6 && killAura.getAutoBlock().getBlockTick() == killAura.getAutoBlock().attackTick.getValue()) || (killAura.getAutoBlock().mode.getValue() != 6 && killAura.getAutoBlock().mode.getValue() != 2) || (killAura.getAutoBlock().mode.getValue() == 5 && killAura.getAutoBlock().getBlockTick() == 0) && killAura.isEnabled() && killAura.isPlayerBlocking())) {
                return delay == 0;
            }
        }
        return false;
    }

    public boolean canSprint() {
        return this.isSwordActive() && this.swordSprint.getValue()
                || this.isFoodActive() && this.foodSprint.getValue()
                || this.isBowActive() && this.bowSprint.getValue();
    }

    public int getMotionMultiplier() {
        if (ItemUtil.isHoldingSword()) {
            return this.swordMotion.getValue();
        } else if (ItemUtil.isEating()) {
            return this.foodMotion.getValue();
        } else {
            return ItemUtil.isUsingBow() ? this.bowMotion.getValue() : 100;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        // Grim1.9+：包缓冲窗口倒计时（含禁用/换世界时的兜底回收）
        if (event.getType() == EventType.PRE) {
            if (mc.player == null || mc.world == null) {
                if (!this.grimHeldPackets.isEmpty() || this.grimQueueTicks > 0) {
                    this.grimHeldPackets.clear();
                    this.grimQueueTicks = 0;
                }
            } else if (this.grimQueueTicks > 0) {
                if (--this.grimQueueTicks == 0) {
                    this.flushGrimPackets();
                }
            } else if (!this.grimHeldPackets.isEmpty()) {
                this.flushGrimPackets();
            }
        }
        if (!this.isEnabled()) return;
        // Grim1.9+：use 开始后在运动包发出前执行换手中断（OpenNilore NoC0F 技术），
        // 服务器侧使用状态被打断，Grim 不再预期减速；客户端保持格挡不受影响
        if (this.swordMode.getValue() == 3 && event.getType() == EventType.PRE && mc.player != null) {
            if (ItemUtil.isHoldingSword() && mc.player.isUsingItem()
                    && mc.player.getActiveHand() == Hand.MAIN_HAND && this.isSwordActive()) {
                if (!this.grimUseInterrupted) {
                    this.grimUseInterrupted = true;
                    this.sendGrimSwapInterrupt();
                }
            } else {
                this.grimUseInterrupted = false;
            }
        }
        if (ItemUtil.isHoldingSword() && mc.player.isUsingItem()) {
            if (isSwordActive()) {
                if (this.swordMode.getValue() == 2) {
                    if (event.getType() == EventType.PRE) {
                        delay--;
                        if (delay < 0) {
                            KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
                            if (!noAttack.getValue() || !((killAura.getAutoBlock().getBlockTick() == 0 && killAura.getAutoBlock().mode.getValue() == 2) || (killAura.getAutoBlock().mode.getValue() == 6 && killAura.getAutoBlock().getBlockTick() == killAura.getAutoBlock().attackTick.getValue()) || (killAura.getAutoBlock().mode.getValue() != 6 && killAura.getAutoBlock().mode.getValue() != 2) || (killAura.getAutoBlock().mode.getValue() == 5 && killAura.getAutoBlock().getBlockTick() == 0) && killAura.isEnabled() && killAura.isPlayerBlocking())) {
                                int randomSlot = new Random().nextInt(9);
                                while (randomSlot == mc.player.getInventory().selectedSlot) {
                                    randomSlot = new Random().nextInt(9);
                                }
                                PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(randomSlot));
                                PacketUtil.sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().selectedSlot));
                            }
                            post = true;
                            delay = swapDelay.getValue();
                        }
                    }
                }
            }
        } else {
            if (post) {
                post = false;
            }
        }
    }

    /**
     * Grim1.9+ 包处理：
     * - SEND：检测到新的主手剑 use 包 → 重置中断标记，下个 PRE 重新打断
     *   （覆盖 KillAura AutoBlock 在使用中重发 use 包的场景；不在事件内直接发包以保证包序）
     * - RECEIVE：缓冲窗口内暂存背包同步包，避免客户端看到换手中间状态
     */
    @EventTarget
    public void onGrimPacket(PacketEvent event) {
        if (event.getType() == EventType.SEND) {
            if (!this.isEnabled() || this.swordMode.getValue() != 3) return;
            if (event.getPacket() instanceof PlayerInteractItemC2SPacket usePacket
                    && usePacket.getHand() == Hand.MAIN_HAND
                    && mc.player != null && ItemUtil.isHoldingSword()) {
                this.grimUseInterrupted = false;
            }
        } else if (event.getType() == EventType.RECEIVE) {
            if (this.grimQueueTicks <= 0 || this.grimFlushing || event.isCancelled()) return;
            if (mc.player == null) return;
            if (this.isGrimQueueablePacket(event.getPacket())) {
                event.setCancelled(true);
                this.grimHeldPackets.add((Packet<ClientPlayPacketListener>) event.getPacket());
            }
        }
    }

    /** 换出 + 立即换回：副手净效果为零，但服务器侧 use 状态被打断 */
    private void sendGrimSwapInterrupt() {
        PacketUtil.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        PacketUtil.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        this.grimQueueTicks = 3;
    }

    /** 需要缓冲的入站包：玩家背包槽位同步、自身含副手的装备同步（换手中间状态） */
    private boolean isGrimQueueablePacket(Packet<?> packet) {
        if (packet instanceof ScreenHandlerSlotUpdateS2CPacket slotPacket) {
            return slotPacket.getSyncId() == 0;
        }
        if (packet instanceof EntityEquipmentUpdateS2CPacket equipmentPacket) {
            if (equipmentPacket.getEntityId() != mc.player.getId()) return false;
            for (Pair<EquipmentSlot, ItemStack> slot : equipmentPacket.getEquipmentList()) {
                if (slot.getFirst() == EquipmentSlot.OFFHAND) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 重放缓冲的入站包，恢复客户端背包同步 */
    private void flushGrimPackets() {
        this.grimFlushing = true;
        try {
            this.grimQueueTicks = 0;
            Packet<ClientPlayPacketListener> packet;
            while ((packet = this.grimHeldPackets.poll()) != null) {
                if (mc.getNetworkHandler() != null) {
                    try {
                        packet.apply(mc.getNetworkHandler());
                    } catch (Exception ignored) {
                    }
                }
            }
        } finally {
            this.grimFlushing = false;
        }
    }

    @EventTarget
    public void onWatchdog(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            this.releaseWatchdog();
            return;
        }
        boolean active = this.isWatchdogActive();
        if (active) {
            if (!this.watchdogUsing) {
                this.watchdogUsing = true;
                this.usingTicks = 0;
            }
            this.usingTicks++;
            if (!this.watchdogBlink && this.usingTicks > this.maxPingSpoof.getValue()) {
                this.watchdogBlink = Myau.blinkManager.setBlinkState(true, BlinkModules.NO_SLOW);
            }
            if (this.usingTicks > this.whenToFinishEating.getValue()) {
                this.watchdogForceStopped = true;
            }
        } else if ((this.watchdogUsing || this.watchdogBlink) && !this.watchdogForceStopped) {
            this.releaseWatchdog();
        }

        if (this.watchdogForceStopped) {
            if (KeyBindUtil.isKeyDown(mc.options.useKey)) {
                KeyBindUtil.setKeyBindState(mc.options.useKey, false);
                if (mc.player.isUsingItem()) {
                    mc.player.stopUsingItem();
                }
            } else {
                KeyBindUtil.updateKeyState(mc.options.useKey);
                this.watchdogForceStopped = false;
                if (!active) {
                    this.releaseWatchdog();
                }
            }
        }
    }

    @EventTarget
    public void onMotion(PostMotionEvent event) {
        if (!this.isEnabled()) return;
        if (!ItemUtil.isHoldingSword() || !mc.player.isUsingItem()) return;
        if (isSwordActive()) {
            if (this.swordMode.getValue() == 2) {
                if (post) {
                    post = false;
                }
            }
        }
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled() && this.isAnyActive()) {
            float multiplier = (float) this.getMotionMultiplier() / 100.0F;
            mc.player.input.movementForward *= multiplier;
            mc.player.input.movementSideways *= multiplier;
            if (!this.canSprint()) {
                mc.player.setSprinting(false);
            }
        }
    }

    @EventTarget(Priority.LOW)
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled() && this.isFloatMode()) {
            int item = mc.player.getInventory().selectedSlot;
            Myau.floatManager.setFloatState(true, FloatModules.NO_SLOW);
        } else {
            Myau.floatManager.setFloatState(false, FloatModules.NO_SLOW);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            if (mc.crosshairTarget != null) {
                switch (mc.crosshairTarget.getType()) {
                    case BLOCK:
                        if (mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult) {
                            BlockPos blockPos = ((net.minecraft.util.hit.BlockHitResult) mc.crosshairTarget).getBlockPos();
                            if (BlockUtil.isInteractable(blockPos) && !PlayerUtil.isSneaking()) {
                                return;
                            }
                        }
                        break;
                    case ENTITY:
                        if (mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult) {
                            Entity entityHit = ((net.minecraft.util.hit.EntityHitResult) mc.crosshairTarget).getEntity();
                            if (entityHit instanceof VillagerEntity) {
                                return;
                            }
                            if (entityHit instanceof LivingEntity && TeamUtil.isShop((LivingEntity) entityHit)) {
                                return;
                            }
                        }
                }
            }
            if (this.isFloatMode() && !Myau.floatManager.isPredicted() && mc.player.isOnGround()) {
                event.setCancelled(true);
                mc.player.setVelocity(mc.player.getVelocity().x, 0.42F, mc.player.getVelocity().z);
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{swordMotion.getValue() + "%"};
    }
}
