package laoqi123.module.modules.player.helper.impl;

import laoqi123.event.impl.Render3DEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.module.modules.player.Helper;
import laoqi123.module.modules.player.helper.HelperBase;
import laoqi123.util.RenderUtil;
import laoqi123.util.rotation.Rotation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;

/**
 * Block Water（OpenNilore 移植）：用方块填掉身边的水源（防水推/水掩埋）。
 * 状态机：NONE → WATER（找放置面）→ WATER_SUPPORT（水柱下方垫方块）。
 */
public class BlockWater extends HelperBase {

    public enum State { NONE, WATER, WATER_SUPPORT }

    public record PlacementData(BlockPos blockPos, Direction direction, Vec3d hitVec) {
    }

    public Rotation targetRotation;
    private BlockPos targetPos;
    public BlockWater.State currentState = BlockWater.State.NONE;
    private BlockWater.PlacementData currentPlacement = null;
    private int savedSlot = -1;
    private static final String NAME = "Block Water";

    public BlockWater() {
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
        this.targetPos = null;
        this.currentState = BlockWater.State.NONE;
        this.targetRotation = null;
        if (this.savedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = this.savedSlot;
        }
        this.savedSlot = -1;
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (mc.player.isTouchingWater()) {
            this.reset();
            return;
        }
        if (event.getType() != laoqi123.event.types.EventType.PRE) {
            return;
        }
        if (this.currentPlacement != null) {
            // 平滑模式下等待服务器旋转到位再放置
            if (Helper.isRotationPending(new Rotation(event.getYaw(), event.getPitch()))) {
                return;
            }
            this.placeBlock();
            this.currentPlacement = null;
            if (this.currentState != BlockWater.State.WATER_SUPPORT) {
                this.reset();
            }
            return;
        }
        switch (this.currentState) {
            case WATER: {
                if (this.targetPos == null || !mc.world.getBlockState(this.targetPos).isOf(Blocks.WATER)) {
                    this.reset();
                    return;
                }
                if (mc.world.getBlockState(this.targetPos.down()).isAir()) {
                    this.currentState = BlockWater.State.WATER_SUPPORT;
                    break;
                }
                if (this.tryFindPlacement()) break;
                this.currentState = BlockWater.State.WATER_SUPPORT;
                break;
            }
            case WATER_SUPPORT: {
                if (this.targetPos == null || !mc.world.getBlockState(this.targetPos).isOf(Blocks.WATER)) {
                    this.reset();
                    return;
                }
                BlockPos blockPos = this.targetPos.down();
                if (mc.world.getBlockState(blockPos).isSolidBlock(mc.world, blockPos)) {
                    this.currentState = BlockWater.State.WATER;
                    this.tryFindPlacement();
                    return;
                }
                Optional<BlockWater.PlacementData> placement = this.findSuitableFace(blockPos);
                if (placement.isPresent()) {
                    this.targetRotation = Rotation.lookingAt(placement.get().hitVec(), mc.player.getEyePos());
                    this.currentPlacement = placement.get();
                } else {
                    this.reset();
                }
                break;
            }
            case NONE: {
                this.findTargetPos();
                if (this.targetPos == null) break;
                this.currentState = BlockWater.State.WATER;
            }
        }
    }

    private boolean tryFindPlacement() {
        Optional<BlockWater.PlacementData> placementOpt = this.findSuitableFace(this.targetPos);
        if (placementOpt.isPresent()) {
            BlockWater.PlacementData placementData = placementOpt.get();
            this.targetRotation = Rotation.lookingAt(placementData.hitVec(), mc.player.getEyePos());
            this.currentPlacement = placementData;
            return true;
        }
        return false;
    }

    private void placeBlock() {
        if (this.currentPlacement == null) {
            return;
        }
        int blockSlot = this.findBlockSlot();
        if (blockSlot == -1) {
            this.reset();
            return;
        }
        if (this.savedSlot == -1) {
            this.savedSlot = mc.player.getInventory().selectedSlot;
        }
        mc.player.getInventory().selectedSlot = blockSlot;
        BlockHitResult blockHitResult = new BlockHitResult(
                this.currentPlacement.hitVec(), this.currentPlacement.direction(),
                this.currentPlacement.blockPos(), false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHitResult);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    @Override
    public void onRender(Render3DEvent renderEvent) {
        if (this.currentState == BlockWater.State.NONE || this.targetPos == null) {
            return;
        }
        RenderUtil.drawBlockBox(this.targetPos, 1.0, 0, 128, 255);
        RenderUtil.drawBlockBoundingBox(this.targetPos, 1.0, 0, 128, 255, 190, 1.5f);
    }

    private void findTargetPos() {
        if (mc.player == null || mc.world == null) {
            this.targetPos = null;
            return;
        }
        BlockPos playerPos = mc.player.getBlockPos();
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; ++dx) {
            for (int dy = -2; dy <= 2; ++dy) {
                for (int dz = -3; dz <= 3; ++dz) {
                    BlockPos candidatePos = playerPos.add(dx, dy, dz);
                    if (Helper.hasWaterPlacement(candidatePos)) continue;
                    if (!mc.world.getBlockState(candidatePos).isOf(Blocks.WATER)) continue;
                    if (!mc.world.getFluidState(candidatePos).isStill()) continue;
                    if (!Helper.isPositionInFov(Vec3d.ofCenter(candidatePos))) continue;
                    candidates.add(candidatePos);
                }
            }
        }
        Vec3d eyePos = mc.player.getEyePos();
        this.targetPos = candidates.stream()
                .min(Comparator.comparingDouble(blockPos -> Vec3d.ofCenter(blockPos).squaredDistanceTo(eyePos)))
                .orElse(null);
    }

    private int findBlockSlot() {
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack itemStack = mc.player.getInventory().getStack(slot);
            if (itemStack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock().getDefaultState().isSolidBlock(mc.world, BlockPos.ORIGIN)) {
                return slot;
            }
        }
        return -1;
    }

    private Optional<BlockWater.PlacementData> findSuitableFace(BlockPos blockPos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = blockPos.offset(direction);
            if (mc.world.getBlockState(neighborPos).isSolidBlock(mc.world, neighborPos)
                    && !mc.world.getBlockState(neighborPos).isOf(Blocks.WATER)) {
                Direction opposite = direction.getOpposite();
                Vec3d hitVec = Vec3d.ofCenter(neighborPos).add(
                        opposite.getOffsetX() * 0.5, opposite.getOffsetY() * 0.5, opposite.getOffsetZ() * 0.5);
                if (mc.player.getEyePos().squaredDistanceTo(hitVec) <= 25.0
                        && this.canSeeBlockFace(neighborPos, opposite)) {
                    return Optional.of(new BlockWater.PlacementData(neighborPos, opposite, hitVec));
                }
            }
            if (direction != Direction.DOWN) continue;
            // 水柱下方 8 格内找实心底，从其顶面往上垫
            BlockPos belowPos = neighborPos;
            for (int i = 0; i < 8 && mc.world.getBlockState(belowPos).isOf(Blocks.WATER); ++i) {
                belowPos = belowPos.down();
            }
            if (!mc.world.getBlockState(belowPos).isSolidBlock(mc.world, belowPos)
                    || mc.world.getBlockState(belowPos).isOf(Blocks.WATER)) continue;
            Vec3d topHitVec = Vec3d.ofCenter(belowPos).add(
                    Direction.UP.getOffsetX() * 0.5, Direction.UP.getOffsetY() * 0.5, Direction.UP.getOffsetZ() * 0.5);
            if (!(mc.player.getEyePos().squaredDistanceTo(topHitVec) <= 25.0)
                    || !this.canSeeBlockFace(belowPos, Direction.UP)) continue;
            return Optional.of(new BlockWater.PlacementData(belowPos, Direction.UP, topHitVec));
        }
        return Optional.empty();
    }

    private boolean canSeeBlockFace(BlockPos blockPos, Direction direction) {
        if (mc.player == null || mc.world == null) {
            return false;
        }
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d targetVec = Vec3d.ofCenter(blockPos).add(
                direction.getOffsetX() * 0.49, direction.getOffsetY() * 0.49, direction.getOffsetZ() * 0.49);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eyePos, targetVec,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (!hit.getBlockPos().equals(blockPos)) {
            return false;
        }
        return hit.getPos().squaredDistanceTo(targetVec) < 0.25;
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
