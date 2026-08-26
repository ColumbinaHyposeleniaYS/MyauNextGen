package laoqi123.ui.clickgui.dropdown;

import laoqi123.event.EventTarget;
import laoqi123.event.impl.RenderSkiaEvent;

public final class OpalDropdownClickGui extends DropdownPanelScreen {
    public OpalDropdownClickGui() {
        super(Style.OPAL, "Opal Dropdown ClickGUI");
    }

    @EventTarget
    public void onRenderSkia(RenderSkiaEvent event) {
        renderSkia(event);
    }
}
