package tfsapps.aiallergychecker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * CustomOverlayView — direct bounding-box overlay with confidence-based colouring
 *
 * Layout (top → bottom):
 *
 *  ┌─────────────────────────────────────────────┐  ← green border (scan zone)
 *  │ この緑枠にパッケージやラベルの                 │  ← guide text (top-left, inside zone)
 *  │ 原材料が全体に映る様にしてください             │
 *  │                                             │
 *  │   [RED box]  ← confirmed allergen           │
 *  │   [YEL box]  ← uncertain allergen           │
 *  └─────────────────────────────────────────────┘
 *  ┌─────────────────────────────────────────────┐  ← legend band (below zone)
 *  │  アレルゲン検出                              │
 *  │  ■ 赤：信頼度（高）   ■ 黄：信頼度（低）     │
 *  └─────────────────────────────────────────────┘
 */
public class CustomOverlayView extends View {

    // ── Zone geometry (fraction of view dimensions) ───────────────────────────
    private static final float ZONE_W_RATIO     = 0.92f;
    private static final float ZONE_H_RATIO     = 0.72f;
    private static final float TOP_OFFSET_RATIO = 0.07f;

    // ── Confidence threshold ──────────────────────────────────────────────────
    private static final float HIGH_CONF = AllergyAnalyzer.HIGH_CONF_THRESHOLD;

    // ── Guide text (single line drawn inside the zone at top-left) ──────────
    private static final String GUIDE_LINE1 = "緑枠内に原材料ラベルを映してください";

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint mBorderPaint;       // thick green outer border
    private final Paint mHighConfPaint;     // semi-transparent red  fill
    private final Paint mLowConfPaint;      // semi-transparent yellow fill
    private final Paint mHighConfStroke;    // red stroke border
    private final Paint mLowConfStroke;     // yellow stroke border
    private final Paint mOverlayBgPaint;    // dark bg for guide text and legend
    private final Paint mGuideTextPaint;    // white text for in-zone instruction
    private final Paint mLegendTextPaint;   // white text for legend labels
    private final Paint mLegendHeaderPaint; // slightly larger text for "アレルゲン検出"

    // ── State ─────────────────────────────────────────────────────────────────
    private List<AllergyAnalyzer.AllergenBox> mBoxes       = new ArrayList<>();
    private int                               mImageWidth  = 0;
    private int                               mImageHeight = 0;
    private int                               mRotation    = 0;

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public CustomOverlayView(Context context) {
        super(context);
        mBorderPaint      = makeBorderPaint();
        mHighConfPaint    = makeHighConfPaint();
        mLowConfPaint     = makeLowConfPaint();
        mHighConfStroke   = makeHighConfStroke();
        mLowConfStroke    = makeLowConfStroke();
        mOverlayBgPaint   = makeOverlayBgPaint();
        mGuideTextPaint   = makeGuideTextPaint(context);
        mLegendTextPaint  = makeLegendTextPaint(context);
        mLegendHeaderPaint = makeLegendHeaderPaint(context);
    }

    public CustomOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBorderPaint      = makeBorderPaint();
        mHighConfPaint    = makeHighConfPaint();
        mLowConfPaint     = makeLowConfPaint();
        mHighConfStroke   = makeHighConfStroke();
        mLowConfStroke    = makeLowConfStroke();
        mOverlayBgPaint   = makeOverlayBgPaint();
        mGuideTextPaint   = makeGuideTextPaint(context);
        mLegendTextPaint  = makeLegendTextPaint(context);
        mLegendHeaderPaint = makeLegendHeaderPaint(context);
    }

    public CustomOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mBorderPaint      = makeBorderPaint();
        mHighConfPaint    = makeHighConfPaint();
        mLowConfPaint     = makeLowConfPaint();
        mHighConfStroke   = makeHighConfStroke();
        mLowConfStroke    = makeLowConfStroke();
        mOverlayBgPaint   = makeOverlayBgPaint();
        mGuideTextPaint   = makeGuideTextPaint(context);
        mLegendTextPaint  = makeLegendTextPaint(context);
        mLegendHeaderPaint = makeLegendHeaderPaint(context);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    public void setResults(List<AllergyAnalyzer.AllergenBox> boxes,
                           int imageWidth, int imageHeight, int rotationDegrees) {
        mBoxes       = new ArrayList<>(boxes);
        mImageWidth  = imageWidth;
        mImageHeight = imageHeight;
        mRotation    = rotationDegrees;
        invalidate();
    }

    public void clear() {
        mBoxes.clear();
        invalidate();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Drawing
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float vW = getWidth();
        final float vH = getHeight();
        if (vW == 0 || vH == 0) return;

        // ── Step 1: Zone geometry ─────────────────────────────────────────────
        final float zoneW     = vW * ZONE_W_RATIO;
        final float zoneH     = vH * ZONE_H_RATIO;
        final float usableTop = vH * TOP_OFFSET_RATIO;
        final float usableH   = vH - usableTop;
        final float zoneL     = (vW - zoneW) / 2f;
        final float zoneT     = usableTop + (usableH - zoneH) / 2f;
        final float zoneR     = zoneL + zoneW;
        final float zoneB     = zoneT + zoneH;

        // ── Step 2: Draw allergen bounding boxes ──────────────────────────────
        if (mImageWidth > 0 && mImageHeight > 0) {
            for (AllergyAnalyzer.AllergenBox box : mBoxes) {
                RectF v = transformRect(
                        box.rect, mImageWidth, mImageHeight, mRotation, vW, vH);
                if (v == null) continue;

                boolean high = box.confidence >= HIGH_CONF;
                canvas.drawRect(v, high ? mHighConfPaint  : mLowConfPaint);
                canvas.drawRect(v, high ? mHighConfStroke : mLowConfStroke);
            }
        }

        // ── Step 3: Green scan-zone border ────────────────────────────────────
        canvas.drawRect(zoneL, zoneT, zoneR, zoneB, mBorderPaint);

        // ── Step 4: Guide text — top-left inside the zone ─────────────────────
        //
        //  ┌──────────────────────────────────────────────┐ ← green border
        //  │ この緑枠にパッケージやラベルの                  │
        //  │ 原材料が全体に映る様にしてください              │
        //  │                                              │
        //
        final float guidePad = 10f;   // inner padding from zone edge

        Paint.FontMetrics gfm   = mGuideTextPaint.getFontMetrics();
        final float       gLineH = gfm.descent - gfm.ascent; // height of one guide line

        // Size the background rectangle around the single guide line
        float guideBgW = mGuideTextPaint.measureText(GUIDE_LINE1) + guidePad * 2f;
        float guideBgH = gLineH + guidePad * 2f;

        float guideBgL = zoneL + guidePad;
        float guideBgT = zoneT + guidePad;
        float guideBgR = guideBgL + guideBgW;
        float guideBgB = guideBgT + guideBgH;

        canvas.drawRect(guideBgL, guideBgT, guideBgR, guideBgB, mOverlayBgPaint);

        // Text baseline (Canvas draws from baseline)
        float guideTextX = guideBgL + guidePad;
        float guideLine1Y = guideBgT + guidePad - gfm.ascent;

        canvas.drawText(GUIDE_LINE1, guideTextX, guideLine1Y, mGuideTextPaint);

        // ── Step 5: Legend band below the zone ───────────────────────────────
        //
        //  ┌──────────────────────────────────────────────┐
        //  │  アレルゲン検出                               │  ← header
        //  │  ■ 赤：信頼度（高）    ■ 黄：信頼度（低）      │  ← colour key (one row)
        //  └──────────────────────────────────────────────┘
        //
        final float lPad       = 10f;   // horizontal inner padding
        final float vPad       = 6f;    // vertical inner padding
        final float swatchSz   = 16f;   // coloured square side
        final float swatchGap  = 6f;    // gap between swatch and its label text
        final float itemGap    = 20f;   // gap between red item and yellow item
        final float rowGap     = 4f;    // gap between header and colour-key row

        Paint.FontMetrics hfm  = mLegendHeaderPaint.getFontMetrics();
        Paint.FontMetrics lfm  = mLegendTextPaint.getFontMetrics();
        float headerH = hfm.descent - hfm.ascent;
        float itemH   = Math.max(swatchSz, lfm.descent - lfm.ascent);

        float legT = zoneB + 12f;
        float legB = legT + vPad + headerH + rowGap + itemH + vPad;

        canvas.drawRect(zoneL, legT, zoneR, legB, mOverlayBgPaint);

        // Header: "アレルゲン検出"
        float headerY = legT + vPad - hfm.ascent;
        canvas.drawText("アレルゲン検出",
                        zoneL + lPad, headerY, mLegendHeaderPaint);

        // Colour-key row (header bottom + rowGap + centred on itemH)
        float itemRowMidY = legT + vPad + headerH + rowGap + itemH / 2f;

        // RED swatch + label
        float redSwL = zoneL + lPad;
        canvas.drawRect(redSwL, itemRowMidY - swatchSz / 2f,
                        redSwL + swatchSz, itemRowMidY + swatchSz / 2f,
                        mHighConfPaint);
        canvas.drawRect(redSwL, itemRowMidY - swatchSz / 2f,
                        redSwL + swatchSz, itemRowMidY + swatchSz / 2f,
                        mHighConfStroke);
        float redTextX = redSwL + swatchSz + swatchGap;
        canvas.drawText("赤：信頼度（高）",
                        redTextX,
                        itemRowMidY - (lfm.ascent + lfm.descent) / 2f,
                        mLegendTextPaint);

        // YELLOW swatch + label (positioned right of the red item)
        float redItemW   = mLegendTextPaint.measureText("赤：信頼度（高）");
        float yelSwL     = redTextX + redItemW + itemGap;
        canvas.drawRect(yelSwL, itemRowMidY - swatchSz / 2f,
                        yelSwL + swatchSz, itemRowMidY + swatchSz / 2f,
                        mLowConfPaint);
        canvas.drawRect(yelSwL, itemRowMidY - swatchSz / 2f,
                        yelSwL + swatchSz, itemRowMidY + swatchSz / 2f,
                        mLowConfStroke);
        canvas.drawText("黄：信頼度（低）",
                        yelSwL + swatchSz + swatchGap,
                        itemRowMidY - (lfm.ascent + lfm.descent) / 2f,
                        mLegendTextPaint);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Coordinate transformation
    // ─────────────────────────────────────────────────────────────────────────

    private static RectF transformRect(Rect r,
                                       int imgW, int imgH, int rot,
                                       float vW, float vH) {
        float effectiveW = (rot == 90 || rot == 270) ? imgH : imgW;
        float effectiveH = (rot == 90 || rot == 270) ? imgW : imgH;

        float scale   = Math.max(vW / effectiveW, vH / effectiveH);
        float offsetX = (vW - effectiveW * scale) / 2f;
        float offsetY = (vH - effectiveH * scale) / 2f;

        float l  = r.left   * scale + offsetX;
        float t  = r.top    * scale + offsetY;
        float ri = r.right  * scale + offsetX;
        float b  = r.bottom * scale + offsetY;

        l  = Math.max(0f, l);
        t  = Math.max(0f, t);
        ri = Math.min(vW, ri);
        b  = Math.min(vH, b);

        if (l >= ri || t >= b) return null;
        return new RectF(l, t, ri, b);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Paint factories
    // ─────────────────────────────────────────────────────────────────────────

    private static Paint makeBorderPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.rgb(0, 210, 90));
        p.setStrokeWidth(4f);
        return p;
    }

    private static Paint makeHighConfPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(70, 220, 30, 30));
        return p;
    }

    private static Paint makeHighConfStroke() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.argb(230, 220, 30, 30));
        p.setStrokeWidth(3.5f);
        return p;
    }

    private static Paint makeLowConfPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(70, 230, 180, 0));
        return p;
    }

    private static Paint makeLowConfStroke() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.argb(230, 230, 180, 0));
        p.setStrokeWidth(3.5f);
        return p;
    }

    /** Shared dark translucent background for both guide text and legend band. */
    private static Paint makeOverlayBgPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(175, 0, 0, 0));
        return p;
    }

    /** Green bold 11 sp text for the in-zone instruction lines (matches border colour). */
    private static Paint makeGuideTextPaint(Context context) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(0, 210, 90));   // same green as the zone border
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float sp15 = 15f * context.getResources().getDisplayMetrics().scaledDensity;
        p.setTextSize(sp15);
        p.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        return p;
    }

    /** White 12 sp text for the legend colour-key items. */
    private static Paint makeLegendTextPaint(Context context) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        float sp12 = 12f * context.getResources().getDisplayMetrics().scaledDensity;
        p.setTextSize(sp12);
        p.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        return p;
    }

    /** White 13 sp bold text for the "アレルゲン検出" legend header. */
    private static Paint makeLegendHeaderPaint(Context context) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        float sp13 = 13f * context.getResources().getDisplayMetrics().scaledDensity;
        p.setTextSize(sp13);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        return p;
    }
}
