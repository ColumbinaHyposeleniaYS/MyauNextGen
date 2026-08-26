package laoqi123.module.modules.movement;

import laoqi123.Myau;
import laoqi123.enums.BlinkModules;
import laoqi123.event.EventTarget;
import laoqi123.event.types.EventType;
import laoqi123.event.types.Priority;
import laoqi123.event.impl.LoadWorldEvent;
import laoqi123.event.impl.PacketEvent;
import laoqi123.event.impl.StrafeEvent;
import laoqi123.event.impl.TickEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.module.Module;
import laoqi123.module.modules.player.Scaffold;
import laoqi123.util.PacketUtil;
import laoqi123.value.properties.ModeValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stuck（OpenNilore 移植）：取消全部移动包让服务器视角"卡住"，同时拦截 pong 制造高 ping 假象。
 * - Delay：交互包（use/action）捕获后下一 tick 先补发旋转同步包再转发，服务器侧旋转与 use 包隐含旋转一致；
 *   解除时发 +1337 位移包强制服务器传送纠正（收到 PlayerPositionLook 后真正关闭）。
 * - Packet：每 tick 发 START_FALL_FLYING 让服务器按滑翔状态判定，解除时直接放行。
 * 两种模式均会强制关闭 Scaffold（其持续发包会破坏卡住状态）。
 */
public class Stuck extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public final ModeValue mode = new ModeValue("Mode", 0, new String[]{"Delay", "Packet"});

    private int stuckState = 0;
    private Packet<?> capturedPacket;
    private float savedYaw;
    private float savedPitch;
    private boolean pendingDisable = false;
    private boolean selfSending = false;
    private final Queue<CommonPongC2SPacket> pongQueue = new ConcurrentLinkedQueue<>();

    public Stuck() {
        super("Stuck", false);
    }

    @Override
    public void onEnabled() {
        this.stuckState = 0;
        this.capturedPacket = null;
        this.pendingDisable = false;
        this.pongQueue.clear();
        this.savedYaw = mc.player != null ? mc.player.getYaw() : 0.0F;
        this.savedPitch = mc.player != null ? mc.player.getPitch() : 0.0F;
    }

    @Override
    public void onDisabled() {
        this.pendingDisable = false;
        this.capturedPacket = null;
        this.flushPongs();
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (mc.player == null) {
            super.setEnabled(enabled);
            return;
        }
        if (enabled) {
            super.setEnabled(true);
        } else if (this.mode.getModeString().equals("Delay")) {
            // Delay 模式不能直接关：需先发 +1337 触发服务器传送纠正，收到纠正包后才真正关闭
            if (this.stuckState == 3) {
                super.setEnabled(false);
            } else {
                this.pendingDisable = true;
            }
        } else {
            super.setEnabled(false);
        }
    }

    /** Packet 模式：每 tick 发 START_FALL_FLYING，服务器按滑翔判定（解除后高速移动不触发过速纠正） */
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (!this.mode.getModeString().equals("Packet")) {
            return;
        }
        Module scaffold = Myau.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            scaffold.setEnabled(false);
            return;
        }
        if (mc.player == null) {
            return;
        }
        if (!this.isAntiVoidActive()) {
            this.sendOwn(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        // Scaffold 会持续发包破坏卡住状态，强制关闭
        Module scaffold = Myau.moduleManager.getModule(Scaffold.class);
        if (!this.isAntiVoidActive() && scaffold != null && scaffold.isEnabled()) {
            scaffold.setEnabled(false);
            return;
        }
        if (mc.player == null || event.getType() != EventType.POST) {
            return;
        }
        mc.player.setVelocity(0.0, 0.0, 0.0);
        if (this.stuckState == 1) {
            this.stuckState = 2;
            float currentYaw = mc.player.getYaw();
            float currentPitch = mc.player.getPitch();
            // 转发捕获的交互包前先补发旋转包，让服务器已知旋转与 use 包携带的旋转一致（Grim 一致性）
            if (this.shouldSendCapturedPacket() && (this.savedYaw != currentYaw || this.savedPitch != currentPitch)) {
                this.sendOwn(new PlayerMoveC2SPacket.LookAndOnGround(
                        currentYaw, currentPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                this.flushPongs();
                this.savedYaw = currentYaw;
                this.savedPitch = currentPitch;
            }
            this.sendOwn(this.capturedPacket);
        } else if (!this.isAntiVoidActive() && this.mode.getModeString().equals("Packet") && mc.player.age % 10 == 0) {
            this.flushPongs();
        }
        if (this.pendingDisable) {
            if (this.mode.getModeString().equals("Delay")) {
                // +1337 位移包：服务器判定"moved wrongly"回发传送纠正
                this.sendOwn(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX() + 1337.0, mc.player.getY(), mc.player.getZ() + 1337.0,
                        mc.player.isOnGround(), mc.player.horizontalCollision));
            } else {
                this.sendOwn(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
            this.flushPongs();
            this.stuckState = 3;
            this.pendingDisable = false;
        }
    }

    private boolean shouldSendCapturedPacket() {
        if (this.capturedPacket instanceof PlayerInteractItemC2SPacket useItemPacket) {
            ItemStack heldStack = mc.player.getStackInHand(useItemPacket.getHand());
            return !this.isStewItem(heldStack) && !(heldStack.getItem() instanceof BowItem);
        }
        if (this.capturedPacket instanceof PlayerActionC2SPacket actionPacket) {
            return actionPacket.getAction() == PlayerActionC2SPacket.Action.RELEASE_USE_ITEM
                    && mc.player.getActiveItem().getItem() instanceof BowItem;
        }
        return false;
    }

    private boolean isStewItem(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.MUSHROOM_STEW || item == Items.RABBIT_STEW
                || item == Items.BEETROOT_SOUP || item == Items.SUSPICIOUS_STEW;
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        event.setForward(0.0F);
        event.setStrafe(0.0F);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        this.stuckState = 3;
        this.capturedPacket = null;
        this.pendingDisable = false;
        this.pongQueue.clear();
        this.setEnabled(false);
    }

    /**
     * 拦截逻辑：
     * - 移动包：全部取消（服务器视角卡住）
     * - pong：入队取消（延迟回执制造高 ping 假象，Grim 时序容忍窗口变大）
     * - use/action 包：捕获取消，下一 tick POST 经旋转同步后转发
     * - 收到 PlayerPositionLook（Delay 模式）：服务器已纠正，刷新 pong 后真正关闭
     */
    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || this.selfSending) {
            return;
        }
        if (mc.player == null) {
            return;
        }
        if (event.getType() == EventType.SEND) {
            Packet<?> packet = event.getPacket();
            if (packet instanceof PlayerMoveC2SPacket) {
                event.setCancelled(true);
            } else if (packet instanceof CommonPongC2SPacket) {
                this.pongQueue.offer((CommonPongC2SPacket) packet);
                event.setCancelled(true);
            } else if (packet instanceof PlayerInteractItemC2SPacket || packet instanceof PlayerActionC2SPacket) {
                this.capturedPacket = packet;
                this.stuckState = 1;
                event.setCancelled(true);
            }
        } else if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof PlayerPositionLookS2CPacket && this.mode.getModeString().equals("Delay")) {
                this.flushPongs();
                this.stuckState = 3;
                this.setEnabled(false);
            }
        }
    }

    private boolean isAntiVoidActive() {
        return Myau.blinkManager != null
                && Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID
                && mc.player != null && !mc.player.isOnGround();
    }

    /** 模块自身重发包时置位，避免被自己的拦截逻辑再次取消/捕获 */
    private void sendOwn(Packet<?> packet) {
        if (packet == null) {
            return;
        }
        this.selfSending = true;
        try {
            PacketUtil.sendPacket(packet);
        } finally {
            this.selfSending = false;
        }
    }

    private void flushPongs() {
        this.selfSending = true;
        try {
            CommonPongC2SPacket pong;
            while ((pong = this.pongQueue.poll()) != null) {
                PacketUtil.sendPacket(pong);
            }
        } finally {
            this.selfSending = false;
        }
    }
}
