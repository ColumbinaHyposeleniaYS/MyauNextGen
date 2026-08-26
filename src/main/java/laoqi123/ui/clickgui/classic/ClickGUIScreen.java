package laoqi123.ui.clickgui.classic;

import laoqi123.module.Category;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 经典组件式 ClickGUI（Dropdown 风格，原版 DrawContext 渲染，不走 RenderSkiaEvent）。
 * 从 Lyasim 的 ClickGUIScreen 移植，适配 Myau 的模块 / 值系统。
 */
public class ClickGUIScreen extends Screen {
    private final List<Panel> panels = new ArrayList<>();

    public ClickGUIScreen() {
        super(Text.literal("ClickGUI"));
        float startX = 20;
        for (Category category : Category.values()) {
            panels.add(new Panel(category, startX, 20));
            startX += 120;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        for (Panel panel : panels) {
            panel.render(context, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Panel panel : panels) {
            panel.mouseClicked((int) mouseX, (int) mouseY, button);
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (Panel panel : panels) {
            panel.mouseReleased((int) mouseX, (int) mouseY, button);
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (Panel panel : panels) {
            if (panel.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        for (Panel panel : panels) {
            if (panel.charTyped(chr, modifiers)) {
                return true;
            }
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
