package laoqi123.ui.clickgui.classic;

import laoqi123.Myau;
import laoqi123.module.Category;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类面板：顶部渐变标题栏（左键拖动、右键折叠）+ 模块按钮列表。
 * 从 Lyasim 的 Panel 移植。
 */
public class Panel {
    private final List<ModuleButton> buttons = new ArrayList<>();
    private final Category category;
    private float x, y, dragX, dragY;
    private boolean dragging, open = true;
    private final float width = 105;

    public Panel(Category category, float x, float y) {
        this.category = category;
        this.x = x;
        this.y = y;

        Myau.moduleManager.getModulesInCategory(category).stream()
                .filter(m -> !m.isHidden())
                .forEach(m -> buttons.add(new ModuleButton(m)));
    }

    public void render(DrawContext context, int mouseX, int mouseY) {
        if (dragging) {
            x = mouseX - dragX;
            y = mouseY - dragY;
        }

        ClassicRenderUtil.drawGradientRect(context, x, y, width, 18,
                ClassicRenderUtil.accent(0), ClassicRenderUtil.accent(4), true);
        ClassicFontUtil.drawString(context, category.getName(),
                x + width / 2f - ClassicFontUtil.getStringWidth(category.getName()) / 2f, y + 5, -1, true);

        if (open) {
            float offsetY = 18;
            for (ModuleButton button : buttons) {
                offsetY += button.render(context, mouseX, mouseY, x, y + offsetY, width);
            }
        }
    }

    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 18) {
            if (button == 0) {
                dragging = true;
                dragX = mouseX - x;
                dragY = mouseY - y;
            } else if (button == 1) open = !open;
        }

        if (open) {
            float offsetY = 18;
            for (ModuleButton btn : buttons) {
                btn.mouseClicked(mouseX, mouseY, button, x, y + offsetY, width);
                offsetY += btn.getHeight();
            }
        }
    }

    public void mouseReleased(int mouseX, int mouseY, int button) {
        dragging = false;

        if (open) {
            buttons.forEach(btn -> btn.mouseReleased(mouseX, mouseY, button));
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) return false;
        for (ModuleButton btn : buttons) {
            if (btn.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (!open) return false;
        for (ModuleButton btn : buttons) {
            if (btn.charTyped(chr, modifiers)) return true;
        }
        return false;
    }
}
