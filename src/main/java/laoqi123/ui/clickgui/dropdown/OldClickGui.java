package laoqi123.ui.clickgui.dropdown;

import laoqi123.event.EventTarget;
import laoqi123.event.impl.RenderSkiaEvent;

public final class OldClickGui extends DropdownPanelScreen {
    public OldClickGui() {
        super(Style.OLD, "Old ClickGUI");
    }

    @EventTarget
    public void onRenderSkia(RenderSkiaEvent event) {
        renderSkia(event);
    }
}
