package laoqi123.module.modules.combat.velocity.impl;

import laoqi123.Myau;
import laoqi123.event.types.EventType;
import laoqi123.event.impl.MoveInputEvent;
import laoqi123.event.impl.PacketEvent;
import laoqi123.event.impl.PlayerUpdateEvent;
import laoqi123.event.impl.TickEvent;
import laoqi123.mixin.EntityAccessor;
import laoqi123.module.modules.combat.KillAura;
import laoqi123.module.modules.combat.velocity.VelocityMode;
import laoqi123.util.RayCastUtil;
import laoqi123.util.RotationUtil;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import laoqi123.value.properties.IntValue;

public class GrimReduceVelocity extends VelocityMode {
    private static final float ATTACK_REACH = 3.0F;

    public final IntValue maxAirTicks = new IntValue("Max Air Ticks", 12, 4, 20);
    public final IntValue reach = new IntValue("Reach", 3, 2, 4);

    private boolean suspending;
    private int suspendTicks;
    private boolean knockback;
    private boolean jumpFlag;
    private boolean jumpPending;

    @Override
    public String getName() {
        return "Grim Reduce";
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.release();
        this.reset();
    }

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        if (event.getType() != EventType.RECEIVE || event.isCancelled()) {
            return;
        }
        if (!(event.getPacket() instanceof EntityVelocityUpdateS2CPacket packet)) {
            return;
        }
        if (packet.getEntityId() != mc.player.getId()) {
            return;
        }
        if (this.suspending) {
            return;
        }
        if (packet.getVelocityX() == 0 && packet.getVelocityY() == 0 && packet.getVelocityZ() == 0) {
            return;
        }
        if (!this.isPlayerKnockback()) {
            return;
        }
        if (this.isBlockedState()) {
            return;
        }
        this.jumpFlag = packet.getVelocityY() > 0;
        if (!mc.player.isOnGround()) {
            event.setCancelled(true);
            this.suspending = true;
            this.suspendTicks = 0;
        } else {
            this.knockback = true;
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.world == null || mc.player == null) {
            this.reset();
            return;
        }

        if (this.jumpFlag) {
            this.jumpFlag = false;
            if (mc.player.isOnGround() && mc.player.isSprinting()
                    && !mc.player.hasStatusEffect(StatusEffects.JUMP_BOOST) && !this.isInLiquidOrWeb()) {
                this.jumpPending = true;
            }
        }

        if (this.suspending) {
            this.suspendTicks++;
            boolean timeout = this.suspendTicks >= this.maxAirTicks.getValue();
            if (mc.player.isOnGround() || timeout || this.isBlockedState()) {
                boolean grounded = mc.player.isOnGround();
                Entity target = this.findTarget();
                boolean canReduce = grounded
                        && mc.player.isSprinting()
                        && this.isValidTarget(target)
                        && !this.isBlockedState()
                        && this.getTicksSinceAttack() > 0;

                this.release();

                if (canReduce) {
                    this.doReduce(target);
                } else if (grounded && mc.player.isSprinting()) {
                    mc.player.setSprinting(false);
                }
            }
            return;
        }

        if (this.knockback) {
            this.knockback = false;
            if (this.isBlockedState()) {
                return;
            }
            if (!mc.player.isSprinting()) {
                return;
            }
            Entity target = this.findTarget();
            if (this.isValidTarget(target)) {
                this.doReduce(target);
            }
        }
    }

    @Override
    public void onMoveInput(MoveInputEvent event) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        if (this.jumpPending) {
            this.jumpPending = false;
            event.setJump(true);
        }
        if (!this.suspending) {
            return;
        }
        if (this.isBlockedState()) {
            return;
        }
        event.setForward(1.0F);
        event.setStrafe(0.0F);
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
    }

    private void doReduce(Entity target) {
        if (!(target instanceof PlayerEntity) || this.isBlockedState()) {
            return;
        }
        if (mc.player.distanceTo(target) > ATTACK_REACH) {
            return;
        }
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.setVelocity(mc.player.getVelocity().x * 0.6, mc.player.getVelocity().y, mc.player.getVelocity().z * 0.6);
        mc.player.setSprinting(false);
    }

    private void release() {
        this.suspending = false;
        this.suspendTicks = 0;
    }

    private void reset() {
        this.suspending = false;
        this.suspendTicks = 0;
        this.knockback = false;
        this.jumpFlag = false;
        this.jumpPending = false;
    }

    private boolean isPlayerKnockback() {
        double radius = this.reach.getValue() + 2.0;
        double radiusSq = radius * radius;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive()) {
                continue;
            }
            if (mc.player.squaredDistanceTo(player) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlockedState() {
        return mc.player.isClimbing() || this.isInLiquidOrWeb() || this.isOnFireBlock();
    }

    private boolean isInLiquidOrWeb() {
        return mc.player.isTouchingWater() || mc.player.isInLava()
                || ((EntityAccessor) mc.player).getIsInWeb();
    }

    private boolean isOnFireBlock() {
        int x = (int) Math.floor(mc.player.getX());
        int z = (int) Math.floor(mc.player.getZ());
        int y = (int) Math.floor(mc.player.getY());
        return mc.world.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.FIRE
                || mc.world.getBlockState(new BlockPos(x, y - 1, z)).getBlock() == Blocks.FIRE;
    }

    private Entity findTarget() {
        RayCastUtil.RayCastResult result = RayCastUtil.rayCast(
                new RotationUtil.RotationVec(mc.player.getYaw(), mc.player.getPitch()),
                Math.min((float) this.reach.getValue(), ATTACK_REACH));
        Entity raycastTarget = result != null && result.typeOfHit == RayCastUtil.RayCastResult.Type.ENTITY
                && result.entityHit instanceof PlayerEntity ? result.entityHit : null;

        KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
        if (raycastTarget != null && killAura != null && killAura.isEnabled()
                && killAura.getTarget() != null && killAura.getTarget() == raycastTarget) {
            return killAura.getTarget();
        }
        return raycastTarget;
    }

    private boolean isValidTarget(Entity entity) {
        return entity instanceof PlayerEntity
                && entity.isAlive()
                && entity != mc.player
                && mc.player.distanceTo(entity) <= ATTACK_REACH;
    }
}
