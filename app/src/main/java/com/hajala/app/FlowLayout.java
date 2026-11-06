package com.hajala.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out in rows and wraps to the next row when full. Works in RTL and LTR. */
final class FlowLayout extends ViewGroup {
    private final int gap;
    private int[] xs = new int[0];
    private int[] ys = new int[0];

    FlowLayout(Context context, int gapPx) {
        super(context);
        this.gap = gapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int n = getChildCount();
        xs = new int[n];
        ys = new int[n];
        int x = 0;
        int y = 0;
        int rowH = 0;
        for (int i = 0; i < n; i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = ch.getMeasuredWidth();
            int chh = ch.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) {
                x = 0;
                y += rowH + gap;
                rowH = 0;
            }
            xs[i] = x;
            ys[i] = y;
            x += cw + gap;
            rowH = Math.max(rowH, chh);
        }
        int h = y + rowH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int width = r - l;
        int n = getChildCount();
        for (int i = 0; i < n && i < xs.length; i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int cw = ch.getMeasuredWidth();
            int chh = ch.getMeasuredHeight();
            int left = rtl ? width - getPaddingRight() - xs[i] - cw : getPaddingLeft() + xs[i];
            int top = getPaddingTop() + ys[i];
            ch.layout(left, top, left + cw, top + chh);
        }
    }
}
