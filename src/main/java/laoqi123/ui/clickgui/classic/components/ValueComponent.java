package laoqi123.ui.clickgui.classic.components;

import laoqi123.value.Value;
import net.minecraft.client.gui.DrawContext;

/**
 * 设置项组件基类。从 Lyasim 的 ValueComponent 移植（Myau 无 Lombok，手写 getter）。
 */
public abstract class ValueComponent {
    private final Value<?> value;
    protected float lastHeight = 15;

    public ValueComponent(Value<?> value) {
        this.value = value;
    }

    public Value<?> getValue() {
        return value;
    }

    public float getLastHeight() {
        return lastHeight;
    }

    public abstract float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width);

    public abstract void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width);

    public void mouseReleased(int mouseX, int mouseY, int button) {
    }

    public void keyPressed(int keyCode, int scanCode, int modifiers) {
    }

    public void charTyped(char chr, int modifiers) {
    }

    protected boolean hovered(int mouseX, int mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
