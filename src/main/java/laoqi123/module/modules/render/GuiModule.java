package laoqi123.module.modules.render;

import laoqi123.module.Module;
import laoqi123.ui.ClickGui;
import laoqi123.ui.clickgui.PanelClickGui;
import laoqi123.ui.clickgui.classic.ClickGUIScreen;
import laoqi123.ui.clickgui.dropdown.OldClickGui;
import laoqi123.ui.clickgui.dropdown.OpalDropdownClickGui;
import laoqi123.value.properties.ModeValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

public class GuiModule extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ClickGUI 风格：Modern=现代双栏 / Dropdown=经典组件式 / Panel=Nilore 双栏 / Zen=Old 下拉 / Opal=Opal 下拉
    public final ModeValue style = new ModeValue("Style", 0, new String[]{"Modern", "Dropdown", "Panel", "Zen", "Opal"});

    public GuiModule() {
        super("ClickGui", false);
        setKey(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    public void onEnabled() {
        setEnabled(false);
        if (mc.player == null || mc.world == null) return;

        Screen screen;
        switch (style.getValue()) {
            case 1 -> screen = new ClickGUIScreen();       // Dropdown
            case 2 -> screen = new PanelClickGui();        // Panel
            case 3 -> screen = new OldClickGui();          // Zen
            case 4 -> screen = new OpalDropdownClickGui(); // Opal
            default -> screen = new ClickGui();            // Modern
        }
        mc.setScreen(screen);
    }
}
