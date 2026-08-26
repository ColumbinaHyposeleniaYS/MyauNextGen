package laoqi123.module.modules.player.helper;

import laoqi123.event.impl.PlayerUpdateEvent;
import laoqi123.event.impl.Render3DEvent;
import laoqi123.event.impl.TickEvent;
import laoqi123.event.impl.UpdateEvent;
import laoqi123.util.rotation.Rotation;
import net.minecraft.client.MinecraftClient;

/**
 * Helper 子模块基类（OpenNilore HelperBase 移植）。
 * 由 Helper 模块持有并转发事件，不是独立 Module。
 */
public abstract class HelperBase {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    private final String name;

    public HelperBase(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    /** tick 开始（OpenNilore onTick） */
    public void onTick(TickEvent tickEvent) {
    }

    /** 旋转/主逻辑（OpenNilore onMotion；PRE=旋转转发前，POST=运动包后） */
    public void onUpdate(UpdateEvent updateEvent) {
    }

    /** 运动包发送前（OpenNilore onPreMotion） */
    public void onPlayerUpdate(PlayerUpdateEvent playerUpdateEvent) {
    }

    /** 世界渲染（OpenNilore onRender） */
    public void onRender(Render3DEvent renderEvent) {
    }

    /** 是否持有目标旋转（供 Helper 汇总转发） */
    public boolean isActive() {
        return false;
    }

    public Rotation getTargetRotation() {
        return null;
    }
}
