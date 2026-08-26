package laoqi123.event.impl;

import net.minecraft.client.gui.DrawContext;
import org.jetbrains.skija.Canvas;

public class RenderSkiaEvent implements Event {
    private final DrawContext context;
    private final Canvas canvas;

    public RenderSkiaEvent(DrawContext context, Canvas canvas) {
        this.context = context;
        this.canvas = canvas;
    }

    public DrawContext getContext() {
        return this.context;
    }

    public Canvas getCanvas() {
        return this.canvas;
    }
}
