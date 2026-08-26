package laoqi123.module.modules.player;

import laoqi123.event.EventTarget;
import laoqi123.event.impl.PlayerUpdateEvent;
import laoqi123.event.impl.Render3DEvent;
import laoqi123.event.impl.TickEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.event.types.EventType;
import laoqi123.module.Module;
import laoqi123.module.modules.player.helper.HelperBase;
import laoqi123.module.modules.player.helper.impl.BlockLava;
import laoqi123.module.modules.player.helper.impl.BlockWater;
import laoqi123.module.modules.player.helper.impl.ExtinguishFire;
import laoqi123.module.modules.player.helper.impl.SelfExtinguish;
import laoqi123.util.config.NamedChoice;
import laoqi123.util.rotation.Rotation;
import laoqi123.value.properties.BooleanValue;
import laoqi123.value.properties.FloatValue;
import laoqi123.value.properties.MultiEnumChoiceValue;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper（OpenNilore 移植）：玩家辅助子模块集合。
 * 子模块：Self Extinguish（自灭火）/ Extinguish Fire（灭周围火）
 *        / Block Lava（堵岩浆）/ Block Water（堵水）。
 * 本模块负责：转发事件、汇总子模块目标旋转并通过 UpdateEvent 下发、
 * 流体放置标记（自己放的水/岩浆不会被 Block Water/Lava 去堵）。
 * Legit 开启后旋转按 Speed 平滑，且只处理 FOV 内的目标。
 */
public class Helper extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int ROTATION_PRIORITY = 4;

    public static Helper INSTANCE;

    public enum SubModule implements NamedChoice {
        SELF_EXTINGUISH("Self Extinguish"),
        EXTINGUISH_FIRE("Extinguish Fire"),
        BLOCK_LAVA("Block Lava"),
        BLOCK_WATER("Block Water");

        private final String choiceName;

        SubModule(String choiceName) {
            this.choiceName = choiceName;
        }

        @Override
        public String getChoiceName() {
            return this.choiceName;
        }
    }

    /** 位置 → 剩余 tick（我们自己放的水，防止子模块去堵/供回收判断） */
    private static final Map<BlockPos, Integer> waterPlacements = new HashMap<>();
    private static final Map<BlockPos, Integer> lavaPlacements = new HashMap<>();
    private static FluidTracker fluidTracker;

    private final MultiEnumChoiceValue<SubModule> modes = new MultiEnumChoiceValue<>("Mode", SubModule.values(), null);
    private final BooleanValue legit = new BooleanValue("Legit", false);
    private final FloatValue speed = new FloatValue("Speed", 45.0F, 2.0F, 180.0F, this.legit::getValue);
    private final FloatValue fov = new FloatValue("FOV", 90.0F, 30.0F, 180.0F, this.legit::getValue);

    private final List<HelperBase> subModules = new ArrayList<>();
    private Rotation lastTargetRotation;

    public Helper() {
        super("Helper", false);
        INSTANCE = this;
        this.subModules.add(new SelfExtinguish());
        this.subModules.add(new ExtinguishFire());
        this.subModules.add(new BlockLava());
        this.subModules.add(new BlockWater());
    }

    @Override
    public void onEnabled() {
        this.subModules.forEach(HelperBase::onEnable);
    }

    @Override
    public void onDisabled() {
        this.lastTargetRotation = null;
        waterPlacements.clear();
        lavaPlacements.clear();
        fluidTracker = null;
        this.subModules.forEach(HelperBase::onDisable);
    }

    private boolean isSubSelected(HelperBase sub) {
        EnumSet<SubModule> set = this.modes.getValue();
        if (set == null) {
            return false;
        }
        for (SubModule mode : set) {
            if (mode.getChoiceName().equals(sub.getName())) {
                return true;
            }
        }
        return false;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            waterPlacements.clear();
            lavaPlacements.clear();
            fluidTracker = null;
            return;
        }
        this.processBucketTracker();
        this.cleanupPlacementMaps();
        for (HelperBase sub : this.subModules) {
            if (this.isSubSelected(sub)) {
                sub.onTick(event);
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            return;
        }
        // 1. 子模块状态机（旋转到位判断用 event 携带的上一 tick 服务器旋转）
        for (HelperBase sub : this.subModules) {
            if (this.isSubSelected(sub)) {
                sub.onUpdate(event);
            }
        }
        // 2. 汇总目标旋转（后面的子模块覆盖前面的）
        Rotation target = null;
        for (HelperBase sub : this.subModules) {
            if (this.isSubSelected(sub) && sub.isActive() && sub.getTargetRotation() != null) {
                target = sub.getTargetRotation();
            }
        }
        if (target == null) {
            this.lastTargetRotation = null;
            return;
        }
        Rotation toApply = target;
        if (this.legit.getValue()) {
            this.lastTargetRotation = target;
            // 平滑：从上一 tick 发送的旋转向目标步进 Speed 度
            float yawDelta = MathHelper.wrapDegrees(target.getYaw() - event.getYaw());
            float pitchDelta = MathHelper.wrapDegrees(target.getPitch() - event.getPitch());
            float maxStep = this.speed.getValue();
            yawDelta = MathHelper.clamp(yawDelta, -maxStep, maxStep);
            pitchDelta = MathHelper.clamp(pitchDelta, -maxStep, maxStep);
            toApply = new Rotation(
                    event.getYaw() + yawDelta,
                    MathHelper.clamp(event.getPitch() + pitchDelta, -90.0F, 90.0F));
        }
        event.setRotation(toApply.getYaw(), toApply.getPitch(), ROTATION_PRIORITY);
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        this.updateBucketTracker();
        for (HelperBase sub : this.subModules) {
            if (this.isSubSelected(sub)) {
                sub.onPlayerUpdate(event);
            }
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        for (HelperBase sub : this.subModules) {
            if (this.isSubSelected(sub)) {
                sub.onRender(event);
            }
        }
    }

    /** 平滑模式下当前服务器旋转是否还没转到目标（>2°） */
    public static boolean isRotationPending(Rotation current) {
        if (INSTANCE == null || !INSTANCE.legit.getValue()) {
            return false;
        }
        if (INSTANCE.lastTargetRotation == null || current == null) {
            return false;
        }
        return INSTANCE.lastTargetRotation.angleTo(current) > 2.0F;
    }

    public static boolean isRotationPending() {
        if (mc.player == null) {
            return false;
        }
        return isRotationPending(new Rotation(mc.player.getYaw(), mc.player.getPitch()));
    }

    /** 平滑模式下目标位置是否在 FOV 内 */
    public static boolean isPositionInFov(Vec3d pos) {
        if (INSTANCE == null || !INSTANCE.legit.getValue() || mc.player == null) {
            return true;
        }
        Rotation current = new Rotation(mc.player.getYaw(), mc.player.getPitch());
        Rotation target = Rotation.lookingAt(pos, mc.player.getEyePos());
        return current.angleTo(target) <= INSTANCE.fov.getValue() / 2.0F;
    }

    public static void markWaterPlaced(BlockPos pos) {
        addToPlacementMap(waterPlacements, pos);
    }

    public static void markLavaPlaced(BlockPos pos) {
        addToPlacementMap(lavaPlacements, pos);
    }

    public static void removeWaterPlacement(BlockPos pos) {
        if (pos != null) {
            waterPlacements.remove(pos);
        }
    }

    public static void removeLavaPlacement(BlockPos pos) {
        if (pos != null) {
            lavaPlacements.remove(pos);
        }
    }

    public static boolean hasWaterPlacement(BlockPos pos) {
        return pos != null && waterPlacements.containsKey(pos);
    }

    public static boolean hasLavaPlacement(BlockPos pos) {
        return pos != null && lavaPlacements.containsKey(pos);
    }

    /** 手里拿着水/岩浆桶时快照周围流体源；之后新出现的源视为自己放的 */
    private static void updateBucketTracker() {
        if (mc.player == null || mc.world == null) {
            return;
        }
        Item item = mc.player.getMainHandStack().getItem();
        if (item != Items.WATER_BUCKET && item != Items.LAVA_BUCKET) {
            item = mc.player.getOffHandStack().getItem();
        }
        if (item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET) {
            Block block = item == Items.WATER_BUCKET ? Blocks.WATER : Blocks.LAVA;
            fluidTracker = new FluidTracker(block, mc.player.getBlockPos(),
                    findFluidBlocks(mc.player.getBlockPos(), block), 20);
        }
    }

    private static void processBucketTracker() {
        if (fluidTracker == null || mc.world == null) {
            return;
        }
        Set<BlockPos> newSources = findFluidBlocks(fluidTracker.sourcePos, fluidTracker.fluidBlock);
        newSources.removeAll(fluidTracker.connectedPositions);
        for (BlockPos pos : newSources) {
            if (fluidTracker.fluidBlock == Blocks.WATER) {
                markWaterPlaced(pos);
            } else {
                markLavaPlaced(pos);
            }
        }
        if (!newSources.isEmpty() || --fluidTracker.tickCount <= 0) {
            fluidTracker = null;
        }
    }

    private static Set<BlockPos> findFluidBlocks(BlockPos center, Block block) {
        HashSet<BlockPos> set = new HashSet<>();
        if (mc.world == null || center == null) {
            return set;
        }
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -5; dy <= 5; ++dy) {
                for (int dz = -6; dz <= 6; ++dz) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (isFluidSourceAt(pos, block)) {
                        set.add(pos.toImmutable());
                    }
                }
            }
        }
        return set;
    }

    private static void addToPlacementMap(Map<BlockPos, Integer> map, BlockPos pos) {
        if (pos != null) {
            map.put(pos.toImmutable(), 20);
        }
    }

    private static void cleanupPlacementMaps() {
        if (mc.world == null) {
            waterPlacements.clear();
            lavaPlacements.clear();
            return;
        }
        updatePlacementMap(waterPlacements, Blocks.WATER);
        updatePlacementMap(lavaPlacements, Blocks.LAVA);
    }

    private static void updatePlacementMap(Map<BlockPos, Integer> map, Block block) {
        map.entrySet().removeIf(entry -> {
            if (isFluidSourceAt(entry.getKey(), block)) {
                entry.setValue(0);
                return false;
            }
            int remaining = entry.getValue();
            if (remaining <= 0) {
                return true;
            }
            entry.setValue(remaining - 1);
            return false;
        });
    }

    private static boolean isFluidSourceAt(BlockPos pos, Block block) {
        return mc.world.getBlockState(pos).isOf(block) && mc.world.getFluidState(pos).isStill();
    }

    private static final class FluidTracker {
        final Block fluidBlock;
        final BlockPos sourcePos;
        final Set<BlockPos> connectedPositions;
        int tickCount;

        FluidTracker(Block fluidBlock, BlockPos sourcePos, Set<BlockPos> connectedPositions, int tickCount) {
            this.fluidBlock = fluidBlock;
            this.sourcePos = sourcePos;
            this.connectedPositions = connectedPositions;
            this.tickCount = tickCount;
        }
    }
}
