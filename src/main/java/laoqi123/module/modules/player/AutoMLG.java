package laoqi123.module.modules.player;

import laoqi123.event.EventTarget;
import laoqi123.event.types.EventType;
import laoqi123.event.impl.TickEvent;
import laoqi123.module.Module;
import laoqi123.util.RotationUtil;
import laoqi123.util.rotation.Rotation;
import laoqi123.value.properties.BooleanValue;
import laoqi123.value.properties.FloatValue;
import laoqi123.value.properties.IntValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * AutoMLG（OpenNilore 直接移植）：跌落时自动放水 + 收水。
 * - 累计跌落距离达到阈值且预测 N tick 内落地时放水
 * - 落水后 Recovery：自动找空桶收水
 * - 空桶 + 附近有水源时会先取水备着
 * 使用瞬发旋转（临时 snap → interactItem → 还原），
 * use-item 包自带旋转（1.21.4），无需多 tick 预转。
 */
public class AutoMLG extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private final FloatValue triggerDistanceSetting = new FloatValue("Fall distance", 3.0F, 1.0F, 10.0F);
    private final IntValue predictTicksSetting = new IntValue("Predict Ticks", 2, 1, 5);
    private final BooleanValue solidCheckSetting = new BooleanValue("Solid check", true);
    private final BooleanValue recoverySetting = new BooleanValue("Recorvey", true);
    public Rotation targetRotation = null;
    private float accumulatedFall;
    private double lastY;
    private Integer slotToRestore;
    private boolean waterPlaced;
    private boolean recoveryActive;
    private int recoveryDelay;
    private int recoveryCountdown;
    private Integer waterBucketSlot;
    private BlockPos placedWaterPos;
    private boolean readyToPlace;
    private int postPlaceCooldown;
    private int postActionCooldown;
    private int extraCooldown;

    public AutoMLG() {
        super("AutoMLG", false);
    }

    @Override
    public void onEnabled() {
        this.resetState();
        this.accumulatedFall = 0.0F;
        this.lastY = mc.player != null ? mc.player.getY() : 0.0;
    }

    @Override
    public void onDisabled() {
        this.resetState();
        this.accumulatedFall = 0.0F;
    }

    private void resetState() {
        this.slotToRestore = null;
        this.waterPlaced = false;
        this.recoveryActive = false;
        this.recoveryDelay = 0;
        this.recoveryCountdown = 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = null;
        this.readyToPlace = false;
        this.postPlaceCooldown = 0;
        this.postActionCooldown = 0;
        this.extraCooldown = 0;
    }

    public boolean isInCooldown() {
        return this.postPlaceCooldown > 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        double deltaY;
        if (mc.player == null || mc.world == null) {
            return;
        }
        if (mc.player.isGliding()) {
            return;
        }
        if (mc.player.isOnGround() || mc.player.getAbilities().flying || mc.player.isTouchingWaterOrRain() || mc.player.isInLava()) {
            this.accumulatedFall = 0.0F;
        } else {
            deltaY = mc.player.getY() - this.lastY;
            if (deltaY < 0.0) {
                this.accumulatedFall -= (float) deltaY;
            }
        }
        this.lastY = mc.player.getY();
        if (this.postPlaceCooldown > 0) {
            --this.postPlaceCooldown;
        }
        if (this.postActionCooldown > 0) {
            --this.postActionCooldown;
        }
        if (this.extraCooldown > 0) {
            --this.extraCooldown;
        }
        if (this.slotToRestore != null) {
            mc.player.getInventory().selectedSlot = this.slotToRestore;
            this.slotToRestore = null;
        }
        if (mc.player.isOnGround() || this.accumulatedFall <= 0.0F) {
            this.waterPlaced = false;
            this.readyToPlace = false;
        }
        if (this.recoveryActive) {
            if (this.recoveryDelay > 0) {
                --this.recoveryDelay;
                return;
            }
            if (this.recoveryCountdown-- <= 0) {
                this.recoveryActive = false;
                return;
            }
            if (this.waterBucketSlot == null) {
                this.waterBucketSlot = this.findItemInHotbar(Items.BUCKET);
                if (this.waterBucketSlot == null) {
                    this.recoveryActive = false;
                    return;
                }
            }
            if (mc.player.getInventory().getStack(this.waterBucketSlot).getItem() == Items.WATER_BUCKET) {
                this.recoveryActive = false;
                this.waterBucketSlot = null;
                this.placedWaterPos = null;
                this.postPlaceCooldown = Math.max(this.postPlaceCooldown, 1);
                return;
            }
            if (this.placedWaterPos == null || !this.isWaterSource(this.placedWaterPos)) {
                this.recoveryActive = false;
                this.waterBucketSlot = null;
                this.placedWaterPos = null;
                return;
            }
            Rotation recoveryRotation = this.rotationToBlock(this.placedWaterPos);
            BlockHitResult recoveryHit = this.raycastFluid(recoveryRotation, 4.5);
            if (recoveryHit.getType() == HitResult.Type.MISS || !recoveryHit.getBlockPos().equals(this.placedWaterPos)) {
                this.recoveryActive = false;
                this.waterBucketSlot = null;
                this.placedWaterPos = null;
                return;
            }
            this.setTargetRotation(recoveryRotation);
            this.selectSlot(this.waterBucketSlot);
            this.useItem(recoveryRotation);
            return;
        }
        // 空桶取水：没水桶但有空桶、不在下落、附近有可见水源 → 先取水备着
        if (!this.waterPlaced
                && !this.recoveryActive
                && this.placedWaterPos == null
                && this.postPlaceCooldown == 0
                && this.postActionCooldown == 0
                && this.accumulatedFall <= 0.5F
                && this.findItemInHotbar(Items.WATER_BUCKET) < 0) {
            int slot = this.findItemInHotbar(Items.BUCKET);
            if (slot >= 0) {
                BlockPos bucketPos = this.findBucketPos();
                if (bucketPos != null) {
                    Rotation rotation = this.rotationToBlock(bucketPos);
                    BlockHitResult hit = this.raycastFluid(rotation, 4.5);
                    if (hit.getType() != HitResult.Type.MISS && hit.getBlockPos().equals(bucketPos)) {
                        this.setTargetRotation(rotation);
                        this.selectSlot(slot);
                        this.useItem(rotation);
                        this.postActionCooldown = 8;
                        this.postPlaceCooldown = Math.max(this.postPlaceCooldown, 1);
                        return;
                    }
                }
            }
        }
        if (this.waterPlaced && !this.readyToPlace && mc.player.getVelocity().y < 0.0) {
            deltaY = this.distanceToGround(2.5);
            if (deltaY > 0.0 && deltaY <= 1.05) {
                this.readyToPlace = true;
            }
        }
        if (this.waterPlaced) {
            return;
        }
        if (this.accumulatedFall < (float) this.triggerDistanceSetting.getValue()) {
            return;
        }
        int slot = this.findItemInHotbar(Items.WATER_BUCKET);
        if (slot < 0) {
            return;
        }
        int ticksLeft = this.ticksUntilGround();
        if (ticksLeft <= (int) this.predictTicksSetting.getValue()) {
            if ((boolean) this.solidCheckSetting.getValue() && !this.hasSolidBelow(BlockPos.ofFloored(mc.player.getX(), mc.player.getY(), mc.player.getZ()))) {
                return;
            }
            Rotation rotation = new Rotation(mc.player.getYaw(), 90.0F);
            BlockHitResult hit = this.raycastSolid(rotation, 5.0);
            if (hit.getType() == HitResult.Type.MISS) {
                return;
            }
            this.placeWaterBucket(slot, true);
        }
    }

    private int ticksUntilGround() {
        if (mc.player.getVelocity().y >= 0.0) {
            return 999;
        }
        double distance = this.distanceToGround(30.0);
        if (distance == Double.POSITIVE_INFINITY) {
            return 999;
        }
        double simulatedDrop = 0.0;
        double simulatedVelocity = mc.player.getVelocity().y;
        for (int i = 1; i <= 20; ++i) {
            simulatedDrop += simulatedVelocity;
            simulatedVelocity = (simulatedVelocity - 0.08) * 0.98;
            if (Math.abs(simulatedDrop) >= distance) {
                return i;
            }
        }
        return 999;
    }

    /** 瞬发用物品：临时 snap 旋转 → interactItem（包内带旋转）→ 还原。 */
    private void useItem(Rotation rotation) {
        if (mc.interactionManager == null || mc.player == null) {
            return;
        }
        float originalPitch = mc.player.getPitch();
        float originalYaw = mc.player.getYaw();
        if (rotation != null) {
            mc.player.setPitch(rotation.getPitch());
            mc.player.setYaw(rotation.getYaw());
        }
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        if (rotation != null) {
            mc.player.setPitch(originalPitch);
            mc.player.setYaw(originalYaw);
        }
    }

    private BlockPos findBucketPos() {
        BlockPos playerPos = BlockPos.ofFloored(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        BlockPos closestPos = null;
        double closestDistSq = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -4; dx <= 4; ++dx) {
                for (int dz = -4; dz <= 4; ++dz) {
                    BlockPos candidatePos = playerPos.add(dx, dy, dz);
                    if (!this.isWaterSource(candidatePos)) {
                        continue;
                    }
                    double distSq = mc.player.getPos().squaredDistanceTo(
                            (double) candidatePos.getX() + 0.5, (double) candidatePos.getY() + 0.5, (double) candidatePos.getZ() + 0.5
                    );
                    if (distSq >= closestDistSq) {
                        continue;
                    }
                    Rotation rotation = this.rotationToBlock(candidatePos);
                    BlockHitResult hit = this.raycastFluid(rotation, 4.5);
                    if (hit.getType() == HitResult.Type.MISS || !hit.getBlockPos().equals(candidatePos)) {
                        continue;
                    }
                    closestPos = candidatePos;
                    closestDistSq = distSq;
                }
            }
        }
        return closestPos;
    }

    private void setTargetRotation(Rotation rotation) {
        this.targetRotation = rotation;
    }

    private void selectSlot(int slot) {
        this.slotToRestore = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;
    }

    private void placeWaterBucket(int slot, boolean markPlaced) {
        Rotation rotation = new Rotation(mc.player.getYaw(), 90.0F);
        this.setTargetRotation(rotation);
        this.selectSlot(slot);
        this.useItem(rotation);
        if (markPlaced) {
            this.waterPlaced = true;
        }
        this.recoveryActive = (boolean) this.recoverySetting.getValue();
        this.recoveryDelay = 3;
        this.recoveryCountdown = this.recoveryActive ? 2 : 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = this.getPlacementBlockPos(rotation);
    }

    private BlockPos getPlacementBlockPos(Rotation rotation) {
        BlockHitResult hit = this.raycastSolid(rotation, 4.5);
        if (hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        return hit.getBlockPos().offset(hit.getSide());
    }

    private BlockHitResult raycastSolid(Rotation rotation, double range) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d direction = RotationUtil.getVectorForRotation(rotation.getPitch(), rotation.getYaw());
        Vec3d endPos = eyePos.add(direction.multiply(range));
        return mc.world.raycast(new RaycastContext(eyePos, endPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    private BlockHitResult raycastFluid(Rotation rotation, double range) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d direction = RotationUtil.getVectorForRotation(rotation.getPitch(), rotation.getYaw());
        Vec3d endPos = eyePos.add(direction.multiply(range));
        return mc.world.raycast(new RaycastContext(eyePos, endPos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
    }

    private boolean isWaterSource(BlockPos blockPos) {
        FluidState fluidState = mc.world.getFluidState(blockPos);
        return fluidState.getFluid() == Fluids.WATER && fluidState.isStill();
    }

    private boolean hasSolidBelow(BlockPos blockPos) {
        return this.isSolidNonMenu(blockPos.down()) || this.isSolidNonMenu(blockPos.down(2));
    }

    private boolean isSolidNonMenu(BlockPos blockPos) {
        net.minecraft.block.BlockState blockState = mc.world.getBlockState(blockPos);
        boolean hasCollision = !blockState.getCollisionShape(mc.world, blockPos).isEmpty();
        boolean noMenu = blockState.createScreenHandlerFactory(mc.world, blockPos) == null;
        return hasCollision && noMenu;
    }

    private double distanceToGround(double maxDist) {
        Vec3d startPos = new Vec3d(mc.player.getX(), mc.player.getBoundingBox().minY, mc.player.getZ());
        Vec3d endPos = startPos.add(0.0, -maxDist, 0.0);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(startPos, endPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return Double.POSITIVE_INFINITY;
        }
        return startPos.y - hit.getPos().y;
    }

    private int findItemInHotbar(Item item) {
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private Rotation rotationToBlock(BlockPos blockPos) {
        float[] rotations = RotationUtil.getRotationsTo(
                (double) blockPos.getX() + 0.5 - mc.player.getX(),
                (double) blockPos.getY() + 0.5 - mc.player.getY() - (double) mc.player.getStandingEyeHeight(),
                (double) blockPos.getZ() + 0.5 - mc.player.getZ(),
                mc.player.getYaw(), mc.player.getPitch()
        );
        return new Rotation(rotations[0], rotations[1]);
    }
}
