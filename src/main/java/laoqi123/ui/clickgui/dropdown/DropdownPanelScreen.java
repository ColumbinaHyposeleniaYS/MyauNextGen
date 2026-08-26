package laoqi123.ui.clickgui.dropdown;

import laoqi123.Myau;
import laoqi123.event.EventManager;
import laoqi123.event.impl.RenderSkiaEvent;
import laoqi123.module.Category;
import laoqi123.module.Module;
import laoqi123.module.modules.render.HUD;
import laoqi123.ui.font.FontManager;
import laoqi123.ui.font.base.SkiaFont;
import laoqi123.util.KeyBindUtil;
import laoqi123.util.config.Choice;
import laoqi123.util.config.NamedChoice;
import laoqi123.util.render.SkiaRenderUtil;
import laoqi123.value.Value;
import laoqi123.value.properties.BooleanValue;
import laoqi123.value.properties.ColorValue;
import laoqi123.value.properties.EnumChoiceValue;
import laoqi123.value.properties.FloatRangeValue;
import laoqi123.value.properties.FloatValue;
import laoqi123.value.properties.IntChoiceValue;
import laoqi123.value.properties.IntRangeValue;
import laoqi123.value.properties.IntValue;
import laoqi123.value.properties.ModeValue;
import laoqi123.value.properties.MultiEnumChoiceValue;
import laoqi123.value.properties.PercentValue;
import laoqi123.value.properties.TextValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.jetbrains.skija.Canvas;
import org.jetbrains.skija.ClipMode;
import org.jetbrains.skija.ImageFilter;
import org.jetbrains.skija.Paint;
import org.jetbrains.skija.RRect;
import org.jetbrains.skija.Rect;
import org.jetbrains.skija.Shader;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 下拉面板式 ClickGUI（从 Lyasim 的 DropdownPanelScreen 移植，Skia 渲染，含 OLD / OPAL 两种风格）。
 * OLD = Lyasim 旧版方角列表面板；OPAL = 圆角 + 阴影 + 背景 + 模糊 + Bloom 的现代面板。
 *
 * 关键点：与 ClickGui 一样，Skia 的 canvas 只在 SkiaManager.render() 的帧内有效，
 * 所有绘制都通过 RenderSkiaEvent 在子类的 onRenderSkia 里完成，Screen 自身只负责
 * 鼠标/键盘输入。render 与 click 的坐标/高度推进必须完全一致，行高统一由
 * {@link #valueHeight}/{@link #baseValueHeight} 给出。
 *
 * 与 Lyasim 原版的主要差异（适配 Myau）：
 * - 模块列表来自 Myau.moduleManager.getModulesInCategory(cat)（过滤 isHidden），
 *   设置项来自 Myau.valueManager.properties.get(module.getClass())（可能为 null → 空列表）。
 * - 值类型映射：BoolValue→BooleanValue、ModeValue(String 下拉选择)→ModeValue(Integer
 *   索引，显示 getModeString()，左键 nextMode 右键 previousMode，不再展开选项列表)、
 *   NumberValue→FloatValue/IntValue/PercentValue、ColorValue(HSB 对象)→ColorValue
 *   (ARGB int，用 hsbOf/applyHSB 维护 HSB 缓存)、StringValue→TextValue、
 *   MultiBoolValue→MultiEnumChoiceValue；
 *   Myau 独有的 EnumChoiceValue / IntChoiceValue / FloatRangeValue / IntRangeValue
 *   同样支持渲染与点击（循环切换 / 只读展示）。
 * - Lyasim 的 ClickGUI 模块（Opal 阴影/模糊/Bloom 设置）在 Myau 中不存在，
 *   按 Lyasim 默认值硬编码（见 OPAL_* 常量）。
 * - 按键绑定遵循 Myau 约定：0 = 未绑定，负数 = 鼠标键（button - 100）。
 */
public abstract class DropdownPanelScreen extends Screen {
    public enum Style { OLD, OPAL }

    // Lyasim ClickGUI 模块的 Opal 视觉设置在 Myau 中不存在，这里按 Lyasim 默认值硬编码
    private static final boolean OPAL_SHADOW = true;
    private static final float OPAL_SHADOW_STRENGTH = 12.0f;
    private static final boolean OPAL_BLUR = true;
    private static final float OPAL_BLUR_STRENGTH = 8.0f;
    private static final boolean OPAL_BLOOM = true;
    private static final float OPAL_BLOOM_STRENGTH = 8.0f;

    private static final float OPAL_CATEGORY_OPACITY = 0.70f;
    private static final float OPAL_MODULE_OPACITY = OPAL_CATEGORY_OPACITY * 0.70f;

    protected final Style style;
    private final List<CategoryState> panels = new ArrayList<>();
    private int mouseX;
    private int mouseY;
    private long lastFrame;
    private long openedAt;
    private boolean closing;
    private Value<?> draggingNumber;      // 正在拖动的数值滑条（FloatValue/IntValue/PercentValue）
    private float numberMin;              // 拖动滑条的范围（Myau 数值值没有 getInc，按整数/浮点区分步进）
    private float numberMax;
    private boolean numberIntegral;
    private float numberX;
    private float numberWidth;
    private ColorValue draggingColor;
    private boolean draggingHue;
    private float colorX;
    private float colorY;
    private TextValue focusedString;
    private ModuleState bindingModule;
    private final Map<ColorValue, float[]> colorHSB = new HashMap<>(); // 取色盘 HSB 状态（Myau 的 ColorValue 只存 ARGB int）

    protected DropdownPanelScreen(Style style, String title) {
        super(Text.literal(title));
        this.style = style;
        int index = 0;
        for (Category category : Category.values()) panels.add(new CategoryState(category, index++));
        layoutPanels();
    }

    @Override
    protected void init() {
        super.init();
        layoutPanels();
        openedAt = System.currentTimeMillis();
        lastFrame = System.nanoTime();
        closing = false;
        draggingNumber = null;
        draggingColor = null;
        focusedString = null;
        bindingModule = null;
        for (CategoryState panel : panels) {
            panel.scroll = 0.0f;
            panel.open = style == Style.OPAL ? 0.0f : 1.0f;
            panel.dragging = false;
            for (ModuleState module : panel.modules) module.reset();
        }
        // 先注销再注册，保证幂等：EventManager.register 不去重，
        // 若上次 removed() 未可靠触发，重复注册会让 onRenderSkia 每帧多跑一遍。
        EventManager.unregister(this);
        EventManager.register(this);
    }

    @Override
    public void removed() {
        EventManager.unregister(this);
        super.removed();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (draggingNumber != null) updateNumber(mouseX);
        if (draggingColor != null) updateColor(mouseX, mouseY);
    }

    protected final void renderSkia(RenderSkiaEvent event) {
        if (MinecraftClient.getInstance().currentScreen != this) return;
        long now = System.nanoTime();
        float delta = lastFrame == 0L ? 1.0f / 60.0f : Math.min(0.05f, (now - lastFrame) / 1_000_000_000.0f);
        lastFrame = now;
        Canvas canvas = event.getCanvas();
        boolean allClosed = true;
        for (CategoryState panel : panels) {
            float target = closing ? 0.0f : 1.0f;
            if (style == Style.OPAL && !closing) {
                long duration = 100L + panel.index * 80L;
                target = easeOutSine(clamp((System.currentTimeMillis() - openedAt) / (float) duration, 0.0f, 1.0f));
            }
            panel.open = approach(panel.open, target, delta, closing ? 18.0f : 13.0f);
            if (panel.open > 0.015f) allClosed = false;
            renderPanel(canvas, panel, delta);
        }
        if (closing && allClosed) super.close();
    }

    private void renderPanel(Canvas canvas, CategoryState panel, float delta) {
        float width = panelWidth();
        float row = rowHeight();
        float total = totalHeight(panel, delta);
        float maxVisible = Math.max(row, height - panel.y);
        float visible = Math.min(total, maxVisible);
        panel.scroll = clamp(panel.scroll, 0.0f, Math.max(0.0f, total - maxVisible));
        float alpha = panel.open;

        if (style == Style.OPAL && visible > 0.0f && alpha > 0.01f) {
            int effectHeight = Math.max(1, Math.round(visible * alpha));
            if (OPAL_SHADOW) {
                drawOpalShadow(canvas, panel.x, panel.y, width, effectHeight, OPAL_SHADOW_STRENGTH, alpha);
            }
            if (OPAL_BLUR) {
                SkiaRenderUtil.drawBlur(canvas,
                        Math.round(panel.x), Math.round(panel.y),
                        Math.round(width), effectHeight,
                        OPAL_BLUR_STRENGTH, 5);
            }
        }

        canvas.save();
        canvas.clipRect(Rect.makeXYWH(panel.x, panel.y, width, visible * alpha), ClipMode.INTERSECT, true);
        float drawY = panel.y - panel.scroll;
        try (Paint paint = new Paint().setAntiAlias(true)) {
            if (style == Style.OPAL) {
                drawOpalHeader(canvas, paint, panel.x, drawY, width, row, alpha);
                productText(canvas, panel.category.getName(), panel.x + 5.0f, drawY + 7.0f, 9, true, withAlpha(0xFFFFFFFF, alpha));
            } else {
                paint.setColor(0xF5101014);
                canvas.drawRect(Rect.makeXYWH(panel.x, drawY, width, row), paint);
                oldText(canvas, panel.category.getName(), panel.x + 4.0f, drawY + 6.0f, 9, 0xFFFFFFFF);
            }

            float moduleY = drawY + row;
            if (style == Style.OLD && !panel.expanded) {
                canvas.restore();
                return;
            }
            for (int i = 0; i < panel.modules.size(); i++) {
                ModuleState state = panel.modules.get(i);
                state.expand = approach(state.expand, state.expanded ? 1.0f : 0.0f, delta, 14.0f);
                float added = valuesHeight(state) * state.expand;
                renderModule(canvas, paint, panel, state, moduleY, row + added, alpha, i == panel.modules.size() - 1);
                moduleY += row + added;
            }
        }
        canvas.restore();
    }

    private void renderModule(Canvas canvas, Paint paint, CategoryState panel, ModuleState state,
                              float y, float height, float alpha, boolean last) {
        float width = panelWidth();
        float row = rowHeight();
        boolean hovered = inside(mouseX, mouseY, panel.x, y, width, row);
        if (style == Style.OPAL) {
            canvas.save();
            canvas.clipRect(Rect.makeXYWH(panel.x, y, width, height), ClipMode.INTERSECT, true);
            if (last) {
                // Extend the rounded box upward, then scissor it at y so only the lower corners remain.
                canvas.clipRRect(RRect.makeXYWH(panel.x, y - 5.0f, width, height + 5.0f, 5.0f), ClipMode.INTERSECT, true);
            }
            paint.setColor(withAlpha(0xFF1E1E2D, OPAL_MODULE_OPACITY * alpha));
            if (last) canvas.drawRRect(RRect.makeXYWH(panel.x, y - 5.0f, width, height + 5.0f, 5.0f), paint);
            else canvas.drawRect(Rect.makeXYWH(panel.x, y, width, height), paint);
            if (state.module.isEnabled()) {
                try (Shader shader = Shader.makeLinearGradient(panel.x, y, panel.x + width, y,
                        new int[]{withAlpha(hudColor(0L), 0.40f * alpha), withAlpha(hudColor(4L), 0.40f * alpha)})) {
                    if (OPAL_BLOOM) {
                        paint.setImageFilter(ImageFilter.makeDropShadow(0, 0, OPAL_BLOOM_STRENGTH, OPAL_BLOOM_STRENGTH,
                                withAlpha(hudColor(0L), 0.45f * alpha)));
                    }
                    paint.setShader(shader);
                    canvas.drawRect(Rect.makeXYWH(panel.x, y, width, row), paint);
                    paint.setShader(null);
                    if (OPAL_BLOOM) paint.setImageFilter(null);
                }
            } else if (hovered) {
                paint.setColor(withAlpha(0x15FFFFFF, alpha));
                canvas.drawRect(Rect.makeXYWH(panel.x, y, width, row), paint);
            }
            canvas.restore();
            productText(canvas, state.module.getName(), panel.x + 6.0f, y + 6.0f, 8, state.module.isEnabled(),
                    withAlpha(state.module.isEnabled() ? 0xFFFFFFFF : 0xFFCCCCCC, alpha));
            if (bindingModule == state) {
                productText(canvas, "[...]", panel.x + width - 24.0f, y + 7.0f, 7, false, withAlpha(0xFFFFFFFF, alpha));
            } else if (isTabDown() && state.module.getKey() != 0) {
                // Myau 约定：0 = 未绑定，负数 = 鼠标键（button - 100），所以这里用 != 0
                String key = KeyBindUtil.getKeyName(state.module.getKey());
                productText(canvas, "[" + key.toUpperCase(Locale.ROOT) + "]", panel.x + width - 30.0f, y + 7.0f, 7, false, withAlpha(0xFFFFFFFF, alpha));
            } else if (hasVisibleValues(state.module)) {
                // 原 Material Icons 的 \ue5cf（下拉箭头）在 Myau 字体里不存在，用 ▾ 代替
                materialText(canvas, "▾", panel.x + width - 16.0f, y + 5.0f, 10, withAlpha(0xFFFFFFFF, alpha));
            }
        } else {
            paint.setColor(new Color(21, 21, 21, hovered ? 200 : 160).getRGB());
            canvas.drawRect(Rect.makeXYWH(panel.x, y - 1.0f, width, row + 1.0f), paint);
            String name = state.module.getName();
            float nameWidth = oldFont(8).getStringWidth(name);
            oldText(canvas, name, panel.x + (width - nameWidth) / 2.0f, y + 6.0f, 8,
                    state.module.isEnabled() ? 0xFF8AB4F8 : 0xFFFFFFFF);
            if (hasVisibleValues(state.module)) oldText(canvas, state.expanded ? "^" : "v", panel.x + width - 14.0f, y + 6.0f, 8, 0xFFFFFFFF);
        }

        if (state.expand > 0.01f) {
            canvas.save();
            canvas.clipRect(Rect.makeXYWH(panel.x, y + row, width, Math.max(0.0f, height - row)), ClipMode.INTERSECT, true);
            if (style == Style.OPAL && last) {
                canvas.clipRRect(RRect.makeXYWH(panel.x, y - 5.0f, width, height + 5.0f, 5.0f), ClipMode.INTERSECT, true);
            }
            float valueY = y + row;
            for (Value<?> value : moduleValues(state.module)) {
                if (!value.isVisible()) continue;
                float valueHeight = valueHeight(state, value);
                renderValue(canvas, paint, panel, state, value, valueY, alpha * state.expand);
                valueY += valueHeight;
            }
            canvas.restore();
        }
    }

    private void drawOpalHeader(Canvas canvas, Paint paint, float x, float y, float width, float height, float alpha) {
        int brightStart = brighten(hudColor(0L), 0.30f);
        int brightEnd = brighten(hudColor(4L), 0.30f);
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(x, y, width, height), ClipMode.INTERSECT, true);

        // The box extends below the scissor, leaving only the upper corners visible.
        RRect upperRounded = RRect.makeXYWH(x, y, width, height + 5.0f, 5.0f);
        paint.setColor(withAlpha(0xFF0F0F0F, OPAL_CATEGORY_OPACITY * alpha));
        canvas.drawRRect(upperRounded, paint);
        try (Shader shader = Shader.makeLinearGradient(x, y, x + width, y,
                new int[]{withAlpha(brightStart, 0.58f * alpha), withAlpha(brightEnd, 0.58f * alpha)})) {
            if (OPAL_BLOOM) {
                paint.setImageFilter(ImageFilter.makeDropShadow(0, 0, OPAL_BLOOM_STRENGTH, OPAL_BLOOM_STRENGTH,
                        withAlpha(brightStart, 0.52f * alpha)));
            }
            paint.setShader(shader);
            canvas.drawRRect(upperRounded, paint);
            paint.setShader(null);
            if (OPAL_BLOOM) paint.setImageFilter(null);
        }
        canvas.restore();
    }

    private void drawOpalShadow(Canvas canvas, float x, float y, float width, float height, float strength, float alpha) {
        try (Paint shadow = new Paint().setAntiAlias(true)) {
            shadow.setColor(withAlpha(0x20000000, alpha));
            shadow.setImageFilter(ImageFilter.makeDropShadow(0, 3.0f, strength, strength,
                    withAlpha(0xB0000000, alpha)));
            canvas.drawRRect(RRect.makeXYWH(x, y, width, height, 5.0f), shadow);
        }
    }

    private void renderValue(Canvas canvas, Paint paint, CategoryState panel, ModuleState state, Value<?> value, float y, float alpha) {
        float x = panel.x;
        float width = panelWidth();
        float h = baseValueHeight(value);
        int propertyBg = style == Style.OPAL ? 0x40000000 : 0x960B0B0B;
        paint.setColor(withAlpha(propertyBg, alpha));
        canvas.drawRect(Rect.makeXYWH(x, y, width, valueHeight(state, value)), paint);
        drawLabel(canvas, value.getName(), x + (style == Style.OPAL ? 5.0f : 8.0f), y + (style == Style.OPAL ? 5.0f : 6.0f), alpha);

        if (value instanceof BooleanValue bool) {
            if (style == Style.OPAL) drawSmallToggle(canvas, paint, x + width - 22.0f, y + 4.0f, bool.getValue(), alpha);
            else drawOldToggle(canvas, paint, x + width - 26.0f, y + 6.0f, bool.getValue(), alpha);
        } else if (value instanceof ModeValue mode) {
            if (style == Style.OPAL) {
                float boxY = y + 13.5f;
                paint.setColor(withAlpha(0x40191919, alpha));
                canvas.drawRRect(RRect.makeXYWH(x + 3.0f, boxY, width - 5.0f, h - 15.0f, 4.0f), paint);
                productText(canvas, mode.getModeString(), x + 7.0f, boxY + 4.0f, 7, true, withAlpha(0xFFFFFFFF, alpha));
            } else {
                // 公共代码已在左侧画过标签（drawLabel），这里只画右对齐的当前模式，避免重叠
                String current = mode.getModeString();
                oldText(canvas, current, x + width - 12.0f - oldFont(8).getStringWidth(current), y + 6.0f, 8, withAlpha(0xFF8AB4F8, alpha));
            }
        } else if (isNumberValue(value)) {
            float min = numberMin(value);
            float max = numberMax(value);
            float cur = ((Number) value.getValue()).floatValue();
            float sliderX = x + 6.0f;
            float sliderWidth = width - 12.0f;
            float sliderY = y + (style == Style.OPAL ? 20.0f : 18.0f);
            float range = max - min;
            float progress = range == 0 ? 0 : clamp((cur - min) / range, 0, 1);
            String shown = numberText(value, cur);
            drawRightText(canvas, shown, x + width - 6.0f, y + 5.0f, alpha);
            paint.setColor(withAlpha(0xFF373737, alpha));
            canvas.drawRRect(RRect.makeXYWH(sliderX, sliderY, sliderWidth, 2.5f, 1.25f), paint);
            paint.setColor(withAlpha(accent(), alpha));
            canvas.drawRRect(RRect.makeXYWH(sliderX, sliderY, sliderWidth * progress, 2.5f, 1.25f), paint);
            paint.setColor(withAlpha(0xFFFFFFFF, alpha));
            canvas.drawRect(Rect.makeXYWH(sliderX + sliderWidth * progress - 1.0f, sliderY - 1.3f, 2.0f, 5.0f), paint);
        } else if (value instanceof MultiEnumChoiceValue<?> multi) {
            if (style == Style.OPAL) renderOpalMulti(canvas, paint, multi, x, y, width, alpha);
            else {
                drawRightText(canvas, state.openMulti.contains(multi) ? "^" : "v", x + width - 7.0f, y + 6.0f, alpha);
                if (state.openMulti.contains(multi)) {
                    float itemY = y + h;
                    for (Object option : multi.getValues()) {
                        drawOption(canvas, paint, choiceName(option), x, itemY, width, multiContains(multi, option), alpha);
                        itemY += optionHeight();
                    }
                }
            }
        } else if (value instanceof ColorValue color) {
            paint.setColor(color.getValue());
            canvas.drawRRect(RRect.makeXYWH(x + width - 22.0f, y + 3.5f, 18.0f, 10.0f, 3.0f), paint);
            if (state.openColors.contains(color)) renderColorPicker(canvas, paint, color, x + 5.0f, y + h, alpha);
        } else if (value instanceof TextValue string) {
            float boxY = y + 13.0f;
            if (style == Style.OPAL) {
                paint.setColor(withAlpha(0x40191919, alpha));
                canvas.drawRRect(RRect.makeXYWH(x + 3.0f, boxY, width - 5.0f, 10.0f, 4.0f), paint);
            } else {
                paint.setColor(withAlpha(0xFF191919, alpha));
                canvas.drawRRect(RRect.makeXYWH(x + 5.0f, boxY, width - 10.0f, 10.0f, 2.5f), paint);
            }
            String shown = string.getValue();
            if (shown == null) shown = "";
            while (!shown.isEmpty() && valueFont().getStringWidth(shown) > width - 18.0f) shown = shown.substring(1);
            drawValueText(canvas, shown, x + 7.0f, boxY + 2.0f, alpha, focusedString == string);
        } else if (value instanceof EnumChoiceValue<?> enumChoice) {
            // Myau 独有：枚举单选，左键循环下一个、右键循环上一个（与 ClickGui 一致）
            Object cur = enumChoice.getValue();
            drawRightText(canvas, cur instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(cur), x + width - 6.0f, y + 5.0f, alpha);
        } else if (value instanceof IntChoiceValue intChoice) {
            // Myau 独有：整数选项，左键循环下一个、右键循环上一个
            Choice active = intChoice.getConfigurable().getActiveChoice();
            drawRightText(canvas, active == null ? "?" : active.getChoiceName(), x + width - 6.0f, y + 5.0f, alpha);
        } else if (value instanceof FloatRangeValue range) {
            // Myau 独有：浮点区间，只读展示
            drawRightText(canvas, String.format(Locale.US, "%.1f..%.1f", range.getMin(), range.getMax()), x + width - 6.0f, y + 5.0f, alpha);
        } else if (value instanceof IntRangeValue range) {
            // Myau 独有：整数区间，只读展示
            drawRightText(canvas, range.getMin() + ".." + range.getMax(), x + width - 6.0f, y + 5.0f, alpha);
        }
    }

    private void renderOpalMulti(Canvas canvas, Paint paint, MultiEnumChoiceValue<?> multi, float x, float y, float width, float alpha) {
        float currentX = x + 7.0f;
        float currentY = y + 16.0f;
        for (Object option : multi.getValues()) {
            boolean on = multiContains(multi, option);
            float chipWidth = productFont(6, false).getStringWidth(choiceName(option)) + 8.75f;
            if (currentX + chipWidth > x + width - 5.0f) {
                currentX = x + 7.0f;
                currentY += 10.0f;
            }
            paint.setColor(withAlpha(on ? accent() : 0xFF303030, on ? alpha * 0.4f : alpha));
            canvas.drawRRect(RRect.makeXYWH(currentX, currentY, chipWidth - 4.0f, 8.5f, 2.5f), paint);
            productText(canvas, choiceName(option), currentX + 2.5f, currentY + 2.0f, 6, false, withAlpha(on ? 0xFFFFFFFF : 0xFFCCCCCC, alpha));
            currentX += chipWidth - 2.5f;
        }
    }

    private void renderColorPicker(Canvas canvas, Paint paint, ColorValue color, float x, float y, float alpha) {
        float w = 65.0f;
        float h = 50.0f;
        int hue = Color.getHSBColor(hsbOf(color)[0], 1.0f, 1.0f).getRGB();
        try (Shader horizontal = Shader.makeLinearGradient(x, y, x + w, y, new int[]{0xFFFFFFFF, hue});
             Shader vertical = Shader.makeLinearGradient(x, y, x, y + h, new int[]{0x00000000, 0xFF000000})) {
            paint.setShader(horizontal);
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
            paint.setShader(vertical);
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
            paint.setShader(null);
        }
        try (Shader rainbow = Shader.makeLinearGradient(x + w + 5.0f, y, x + w + 5.0f, y + h,
                new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000})) {
            paint.setShader(rainbow);
            canvas.drawRect(Rect.makeXYWH(x + w + 5.0f, y, 8.0f, h), paint);
            paint.setShader(null);
        }
    }

    private void drawOption(Canvas canvas, Paint paint, String option, float x, float y, float width, boolean selected, float alpha) {
        if (style == Style.OPAL) {
            productText(canvas, option, x + 7.0f, y + 3.0f, 7, false, withAlpha(selected ? accent() : 0xFFFFFFFF, alpha));
        } else {
            paint.setColor(withAlpha(0x96212121, alpha));
            canvas.drawRect(Rect.makeXYWH(x + 12.0f, y, width - 24.0f, optionHeight()), paint);
            oldText(canvas, option, x + (width - oldFont(8).getStringWidth(option)) / 2.0f, y + 2.0f, 8, withAlpha(selected ? accent() : 0xFFFFFFFF, alpha));
        }
    }

    private void drawSmallToggle(Canvas canvas, Paint paint, float x, float y, boolean enabled, float alpha) {
        paint.setColor(withAlpha(enabled ? accent() : 0xFF3C3C3C, alpha));
        canvas.drawRRect(RRect.makeXYWH(x, y, 18.0f, 9.0f, 4.5f), paint);
        paint.setColor(withAlpha(0xFFFFFFFF, alpha));
        canvas.drawCircle(x + (enabled ? 13.5f : 4.5f), y + 4.5f, 3.5f, paint);
    }

    private void drawOldToggle(Canvas canvas, Paint paint, float x, float y, boolean enabled, float alpha) {
        paint.setColor(withAlpha(enabled ? 0xC88AB4F8 : 0xB4646464, alpha));
        canvas.drawRect(Rect.makeXYWH(x, y, 18.0f, 8.0f), paint);
        paint.setColor(withAlpha(0xFFA0C3FF, alpha));
        canvas.drawRect(Rect.makeXYWH(x + (enabled ? 10.0f : 1.0f), y - 2.0f, 7.0f, 12.0f), paint);
    }

    private float totalHeight(CategoryState panel, float delta) {
        float total = rowHeight();
        if (style == Style.OLD && !panel.expanded) return total;
        for (ModuleState state : panel.modules) {
            state.expand = approach(state.expand, state.expanded ? 1.0f : 0.0f, delta, 14.0f);
            total += rowHeight() + valuesHeight(state) * state.expand;
        }
        return total;
    }

    private float valuesHeight(ModuleState state) {
        float height = 0.0f;
        for (Value<?> value : moduleValues(state.module)) if (value.isVisible()) height += valueHeight(state, value);
        return height;
    }

    private float valueHeight(ModuleState state, Value<?> value) {
        float h = baseValueHeight(value);
        if (value instanceof MultiEnumChoiceValue<?> multi && style == Style.OLD && state.openMulti.contains(multi)) h += multi.getValues().length * optionHeight();
        if (value instanceof ColorValue color && state.openColors.contains(color)) h += 50.0f;
        return h;
    }

    private float baseValueHeight(Value<?> value) {
        if (style == Style.OLD) return isNumberValue(value) ? 26.0f : 20.0f;
        if (value instanceof ModeValue) return 32.0f;
        if (value instanceof TextValue) return 26.0f;
        if (isNumberValue(value)) return 30.0f;
        if (value instanceof MultiEnumChoiceValue<?> multi) {
            float lineWidth = 2.0f;
            int lines = 1;
            for (Object option : multi.getValues()) {
                float chip = productFont(6, false).getStringWidth(choiceName(option)) + 8.75f;
                if (lineWidth + chip > panelWidth() - 10.0f) { lines++; lineWidth = 2.0f; }
                lineWidth += chip - 2.5f;
            }
            return 27.0f + (lines - 1) * 10.0f;
        }
        return 17.0f;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (bindingModule != null) {
            // Myau 约定：鼠标键存为负数（button - 100），KeyBindUtil.isKeyDown 才能识别
            bindingModule.module.setKey(button - 100);
            bindingModule = null;
            return true;
        }
        for (int p = panels.size() - 1; p >= 0; p--) {
            CategoryState panel = panels.get(p);
            float width = panelWidth();
            float row = rowHeight();
            float y = panel.y - panel.scroll;
            if (inside(mouseX, mouseY, panel.x, y, width, row)) {
                if (style == Style.OLD) {
                    if (button == 0) {
                        panel.dragging = true;
                        panel.dragX = (float) mouseX - panel.x;
                        panel.dragY = (float) mouseY - panel.y;
                    } else if (button == 1) panel.expanded = !panel.expanded;
                }
                return true;
            }
            if (style == Style.OLD && !panel.expanded) continue;
            float moduleY = y + row;
            for (ModuleState state : panel.modules) {
                float added = valuesHeight(state) * state.expand;
                if (inside(mouseX, mouseY, panel.x, moduleY, width, row)) {
                    if (button == 0) state.module.toggle();
                    else if (button == 1 && hasVisibleValues(state.module)) state.expanded = !state.expanded;
                    else if (button == 2 && style == Style.OPAL) bindingModule = state;
                    return true;
                }
                if (state.expand > 0.01f && inside(mouseX, mouseY, panel.x, moduleY + row, width, added)) {
                    clickValues(panel, state, mouseX, mouseY, button, moduleY + row);
                    return true;
                }
                moduleY += row + added;
            }
        }
        focusedString = null;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void clickValues(CategoryState panel, ModuleState state, double mouseX, double mouseY, int button, float startY) {
        float y = startY;
        for (Value<?> value : moduleValues(state.module)) {
            if (!value.isVisible()) continue;
            float h = valueHeight(state, value);
            if (!inside(mouseX, mouseY, panel.x, y, panelWidth(), h)) { y += h; continue; }
            float base = baseValueHeight(value);
            if (value instanceof BooleanValue bool) {
                if (button == 0) bool.setValue(!bool.getValue());
            } else if (value instanceof ModeValue mode) {
                // Myau 的 ModeValue 是 Integer 索引型（Lyasim 是 String 下拉选择）：
                // 左键下一个模式，右键上一个模式（与 ClickGui 的交互一致）
                if (button == 0) mode.nextMode();
                else if (button == 1) mode.previousMode();
            } else if (isNumberValue(value) && button == 0) {
                draggingNumber = value;
                numberMin = numberMin(value);
                numberMax = numberMax(value);
                numberIntegral = value instanceof IntValue || value instanceof PercentValue;
                numberX = panel.x + 6.0f;
                numberWidth = panelWidth() - 12.0f;
                updateNumber(mouseX);
            } else if (value instanceof MultiEnumChoiceValue<?> multi) {
                if (style == Style.OPAL) clickOpalMulti(panel, multi, mouseX, mouseY, y);
                else if (mouseY < y + base && button == 1) {
                    if (!state.openMulti.remove(multi)) state.openMulti.add(multi);
                } else if (state.openMulti.contains(multi)) {
                    Object[] options = multi.getValues();
                    int option = (int) ((mouseY - y - base) / optionHeight());
                    if (option >= 0 && option < options.length) toggleMultiChoice(multi, options[option]);
                }
            } else if (value instanceof ColorValue color) {
                if (mouseY < y + base && button == 1) {
                    if (!state.openColors.remove(color)) {
                        state.openColors.add(color);
                        colorHSB.remove(color); // 重新展开时从当前颜色反推 HSB
                    }
                } else if (state.openColors.contains(color) && button == 0) {
                    float pickerX = panel.x + 5.0f;
                    float pickerY = y + base;
                    draggingColor = color;
                    draggingHue = mouseX >= pickerX + 70.0f;
                    colorX = pickerX;
                    colorY = pickerY;
                    updateColor(mouseX, mouseY);
                }
            } else if (value instanceof TextValue string && button == 0) {
                focusedString = string;
            } else if (value instanceof EnumChoiceValue<?> enumChoice) {
                if (button == 0) cycleEnumChoice(enumChoice, true);
                else if (button == 1) cycleEnumChoice(enumChoice, false);
            } else if (value instanceof IntChoiceValue intChoice) {
                int n = intChoice.getConfigurable().getChoices().size();
                int idx = intChoice.getConfigurable().getActiveIndex();
                int next = button == 0 ? (idx + 1) % n : (idx - 1 + n) % n;
                intChoice.setValue(next);
            }
            return;
        }
    }

    private void clickOpalMulti(CategoryState panel, MultiEnumChoiceValue<?> multi, double mouseX, double mouseY, float y) {
        float currentX = panel.x + 7.0f;
        float currentY = y + 16.0f;
        for (Object option : multi.getValues()) {
            float chipWidth = productFont(6, false).getStringWidth(choiceName(option)) + 8.75f;
            if (currentX + chipWidth > panel.x + panelWidth() - 5.0f) { currentX = panel.x + 7.0f; currentY += 10.0f; }
            if (inside(mouseX, mouseY, currentX, currentY, chipWidth - 4.0f, 8.5f)) toggleMultiChoice(multi, option);
            currentX += chipWidth - 2.5f;
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        for (CategoryState panel : panels) {
            if (panel.dragging && button == 0) {
                panel.x = (float) mouseX - panel.dragX;
                panel.y = (float) mouseY - panel.dragY;
                return true;
            }
        }
        if (draggingNumber != null) { updateNumber(mouseX); return true; }
        if (draggingColor != null) { updateColor(mouseX, mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (CategoryState panel : panels) panel.dragging = false;
        if (button == 0) {
            draggingNumber = null;
            draggingColor = null;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        for (CategoryState panel : panels) {
            if (inside(mouseX, mouseY, panel.x, panel.y, panelWidth(), height - panel.y)) {
                panel.scroll -= (float) verticalAmount * (style == Style.OPAL ? 18.0f : 12.0f);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (bindingModule != null) {
            // Myau 约定：0 = 未绑定（Lyasim 原版用 -1）
            bindingModule.module.setKey(keyCode == GLFW.GLFW_KEY_ESCAPE ? 0 : keyCode);
            bindingModule = null;
            return true;
        }
        if (focusedString != null) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                String s = focusedString.getValue();
                if (s != null && !s.isEmpty()) focusedString.setValue(s.substring(0, s.length() - 1));
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) focusedString = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focusedString != null && chr >= 32 && chr != 127) {
            String s = focusedString.getValue();
            focusedString.setValue((s == null ? "" : s) + chr);
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public void close() {
        if (bindingModule != null) return;
        if (style == Style.OPAL) closing = true;
        else super.close();
    }

    @Override
    public boolean shouldPause() { return false; }

    private void updateNumber(double mouseX) {
        if (draggingNumber == null || numberWidth <= 0.0f) return;
        float ratio = clamp(((float) mouseX - numberX) / numberWidth, 0.0f, 1.0f);
        float raw = numberMin + (numberMax - numberMin) * ratio;
        // 分开装箱：IntValue/PercentValue 存 Integer，FloatValue 存 Float，混用会 ClassCastException
        if (numberIntegral) draggingNumber.setValue(Math.round(raw));
        else draggingNumber.setValue(raw);
    }

    private void updateColor(double mouseX, double mouseY) {
        if (draggingColor == null) return;
        float[] hsb = hsbOf(draggingColor);
        if (draggingHue) {
            float hue = clamp(((float) mouseY - colorY) / 50.0f, 0, 1);
            applyHSB(draggingColor, hue, hsb[1], hsb[2]);
        } else {
            float saturation = clamp(((float) mouseX - colorX) / 65.0f, 0, 1);
            float brightness = 1.0f - clamp(((float) mouseY - colorY) / 50.0f, 0, 1);
            applyHSB(draggingColor, hsb[0], saturation, brightness);
        }
    }

    private void layoutPanels() {
        if (style == Style.OPAL) {
            float total = Category.values().length * 110.0f + (Category.values().length - 1) * 10.0f;
            float start = (width - total) / 2.0f;
            for (CategoryState panel : panels) { panel.x = start + panel.index * 120.0f; panel.y = 25.0f; panel.expanded = true; }
        } else {
            for (CategoryState panel : panels) { if (panel.x == 0.0f) panel.x = 20.0f + panel.index * 160.0f; if (panel.y == 0.0f) panel.y = 20.0f; panel.expanded = true; }
        }
    }

    private float panelWidth() { return style == Style.OPAL ? 110.0f : 140.0f; }
    private float rowHeight() { return 20.0f; }
    private float optionHeight() { return style == Style.OPAL ? 13.0f : 12.0f; }
    private int accent() { return style == Style.OPAL ? hudColor(0L) : 0xFF8AB4F8; }
    private boolean isTabDown() { return GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS; }
    private static boolean hasVisibleValues(Module module) { return moduleValues(module).stream().anyMatch(Value::isVisible); }

    private void drawLabel(Canvas canvas, String text, float x, float y, float alpha) {
        if (style == Style.OPAL) productText(canvas, text, x, y, 7, false, withAlpha(0xFFFFFFFF, alpha));
        else oldText(canvas, text, x, y, 8, withAlpha(0xFFFFFFFF, alpha));
    }

    private void drawRightText(Canvas canvas, String text, float right, float y, float alpha) {
        SkiaFont font = valueFont();
        if (style == Style.OPAL) productText(canvas, text, right - font.getStringWidth(text), y, 7, false, withAlpha(0xFFFFFFFF, alpha));
        else oldText(canvas, text, right - font.getStringWidth(text), y, 8, withAlpha(0xFFFFFFFF, alpha));
    }

    private void drawValueText(Canvas canvas, String text, float x, float y, float alpha, boolean focused) {
        if (style == Style.OPAL) productText(canvas, text, x, y, 7, false, withAlpha(focused ? 0xFFFFFFFF : 0xFF999999, alpha));
        else oldText(canvas, text, x, y, 8, withAlpha(focused ? 0xFFFFFFFF : 0xFF999999, alpha));
    }

    private SkiaFont valueFont() { return style == Style.OPAL ? productFont(7, false) : oldFont(8); }
    private static SkiaFont oldFont(int size) { return FontManager.getInstance().getFont(size); }
    private static SkiaFont productFont(int size, boolean bold) { return bold ? FontManager.getInstance().getBoldFont(size) : FontManager.getInstance().getMediumFont(size); }
    private static void oldText(Canvas canvas, String text, float x, float y, int size, int color) { oldFont(size).drawShadowString(canvas, text, x, y, color); }
    private static void productText(Canvas canvas, String text, float x, float y, int size, boolean bold, int color) { productFont(size, bold).drawString(canvas, text, x, y, color); }
    private static void materialText(Canvas canvas, String text, float x, float y, int size, int color) { FontManager.getInstance().getIconFont(size).drawString(canvas, text, x, y, color); }

    // ---------- Myau 适配工具 ----------

    /** 模块的设置项列表（Myau 的 ValueManager 按 Class 索引，可能为 null）。 */
    private static List<Value<?>> moduleValues(Module module) {
        List<Value<?>> values = Myau.valueManager.properties.get(module.getClass());
        return values == null ? Collections.emptyList() : values;
    }

    /** HUD 强调色（带相位偏移，用于渐变两端；Myau 的 HUD.getColor 返回 java.awt.Color）。 */
    private static int hudColor(long offset) {
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        return hud != null ? hud.getColor(System.currentTimeMillis(), offset).getRGB() : 0xFFFFFFFF;
    }

    /** 枚举选项的显示名（NamedChoice.getChoiceName，兜底 toString）。 */
    private static String choiceName(Object choice) {
        return choice instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(choice);
    }

    /** MultiEnumChoiceValue 是否选中某个枚举项。 */
    private static boolean multiContains(MultiEnumChoiceValue<?> multi, Object option) {
        EnumSet<?> set = multi.getValue();
        return set != null && set.contains(option);
    }

    private static boolean isNumberValue(Value<?> value) {
        return value instanceof FloatValue || value instanceof IntValue || value instanceof PercentValue;
    }

    private static float numberMin(Value<?> value) {
        if (value instanceof FloatValue v) return v.getMinimum();
        if (value instanceof IntValue v) return v.getMinimum();
        if (value instanceof PercentValue v) return v.getMinimum();
        return 0.0f;
    }

    private static float numberMax(Value<?> value) {
        if (value instanceof FloatValue v) return v.getMaximum();
        if (value instanceof IntValue v) return v.getMaximum();
        if (value instanceof PercentValue v) return v.getMaximum();
        return 1.0f;
    }

    /** 滑条数值文本：整数型显示整数（百分比带 %），浮点型保留两位并去掉多余的 .00。 */
    private static String numberText(Value<?> value, float cur) {
        if (value instanceof PercentValue) return Math.round(cur) + "%";
        if (value instanceof IntValue) return Integer.toString(Math.round(cur));
        return String.format(Locale.US, "%.2f", cur).replace(".00", "");
    }

    /** 取色盘的 HSB 状态（首次访问时从当前 ARGB 反推）。 */
    private float[] hsbOf(ColorValue cv) {
        return colorHSB.computeIfAbsent(cv, v -> {
            int rgb = v.getValue();
            return Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        });
    }

    private void applyHSB(ColorValue cv, float h, float s, float b) {
        float[] hsb = hsbOf(cv);
        hsb[0] = h;
        hsb[1] = s;
        hsb[2] = b;
        cv.setValue(Color.HSBtoRGB(h, s, b) | 0xFF000000);
    }

    /** 枚举单选循环切换（与 ClickGui 的实现一致）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cycleEnumChoice(EnumChoiceValue raw, boolean forward) {
        Object[] vals = raw.getValues();
        if (vals == null || vals.length == 0) return;
        Object cur = raw.getValue();
        int idx = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == cur) { idx = i; break; }
        }
        int next = forward ? (idx + 1) % vals.length : (idx - 1 + vals.length) % vals.length;
        raw.setValue(vals[next]);
    }

    /** 多选枚举切换某一项（与 ClickGui 的实现一致）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void toggleMultiChoice(MultiEnumChoiceValue raw, Object ev) {
        if (!(ev instanceof Enum)) return;
        Enum enumVal = (Enum) ev;
        EnumSet set;
        EnumSet current = (EnumSet) raw.getValue();
        if (current == null || current.isEmpty()) {
            set = EnumSet.noneOf(enumVal.getDeclaringClass());
        } else {
            set = EnumSet.copyOf(current);
        }
        if (set.contains(enumVal)) {
            set.remove(enumVal);
        } else {
            set.add(enumVal);
        }
        raw.setValue(set);
    }

    // ---------- 数学工具 ----------

    private static float easeOutSine(float value) { return (float) Math.sin(value * Math.PI / 2.0); }
    private static float approach(float value, float target, float delta, float speed) { return target + (value - target) * (float) Math.exp(-speed * delta); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static int withAlpha(int color, float alpha) { return (color & 0xFFFFFF) | (Math.round(((color >>> 24) & 0xFF) * clamp(alpha, 0, 1)) << 24); }
    private static int brighten(int color, float factor) {
        int alpha = color >>> 24;
        int red = Math.round(((color >>> 16) & 0xFF) + (255 - ((color >>> 16) & 0xFF)) * factor);
        int green = Math.round(((color >>> 8) & 0xFF) + (255 - ((color >>> 8) & 0xFF)) * factor);
        int blue = Math.round((color & 0xFF) + (255 - (color & 0xFF)) * factor);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
    private static boolean inside(double px, double py, float x, float y, float w, float h) { return px >= x && px <= x + w && py >= y && py <= y + h; }

    // ---------- 面板 / 模块状态 ----------

    private final class CategoryState {
        private final Category category;
        private final int index;
        private final List<ModuleState> modules = new ArrayList<>();
        private float x;
        private float y;
        private float scroll;
        private float open = 1.0f;
        private boolean expanded = true;
        private boolean dragging;
        private float dragX;
        private float dragY;

        private CategoryState(Category category, int index) {
            this.category = category;
            this.index = index;
            // Myau：按分类取模块（LinkedHashMap 注册顺序），并过滤隐藏模块
            for (Module module : Myau.moduleManager.getModulesInCategory(category)) {
                if (!module.isHidden()) modules.add(new ModuleState(module));
            }
        }
    }

    private static final class ModuleState {
        private final Module module;
        private final Set<MultiEnumChoiceValue<?>> openMulti = new HashSet<>();
        private final Set<ColorValue> openColors = new HashSet<>();
        private boolean expanded;
        private float expand;

        private ModuleState(Module module) { this.module = module; }
        private void reset() { expanded = false; expand = 0.0f; openMulti.clear(); openColors.clear(); }
    }
}