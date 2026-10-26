package com.tabpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Тачпад: 1 палець — курсор, тап — ліва кнопка, тап двома пальцями — права,
 * два пальці — прокрутка, тап і одразу утримання — перетягування.
 */
public class PadView extends View {
    private Net net;
    public float sensitivity = 1.5f;
    public boolean invertScroll = false;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float slop;

    private long downTime, lastTapUp;
    private float sx, sy, lx, ly;
    private float fracX, fracY, scrollAcc, twoDist;
    private boolean moved, multi, twoMoved, dragging, needBase;
    private int maxPointers;

    public PadView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        slop = 6f * d;
        bg.setColor(0xFF263238);
        border.setColor(0xFF546E7A);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f * d);
        hint.setColor(0xFF607D8B);
        hint.setTextSize(16f * d);
        hint.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
    }

    public void setNet(Net n) {
        net = n;
    }

    @Override
    protected void onDraw(Canvas c) {
        float m = 3f;
        rect.set(m, m, getWidth() - m, getHeight() - m);
        c.drawRoundRect(rect, 24f, 24f, bg);
        c.drawRoundRect(rect, 24f, 24f, border);
        c.drawText("Тачпад", getWidth() / 2f, getHeight() / 2f - 14f, hint);
        c.drawText("1 палець: курсор · тап: клік · 2 пальці: прокрутка / ПКМ", getWidth() / 2f,
                getHeight() / 2f + 24f, hint);
    }

    private void sendMove(float dx, float dy) {
        float speed = (float) Math.sqrt(dx * dx + dy * dy);
        float mult = sensitivity * (1f + Math.min(speed, 30f) / 30f);
        fracX += dx * mult;
        fracY += dy * mult;
        int ix = (int) fracX;
        int iy = (int) fracY;
        fracX -= ix;
        fracY -= iy;
        if (ix != 0 || iy != 0) net.move(ix, iy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (net == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = e.getEventTime();
                sx = lx = e.getX();
                sy = ly = e.getY();
                moved = false;
                multi = false;
                twoMoved = false;
                needBase = false;
                maxPointers = 1;
                fracX = 0;
                fracY = 0;
                scrollAcc = 0;
                twoDist = 0;
                if (lastTapUp > 0 && downTime - lastTapUp < 280) {
                    dragging = true;
                    net.button(1, true);
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                needBase = true;
                if (e.getPointerCount() > maxPointers) maxPointers = e.getPointerCount();
                scrollAcc = 0;
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                needBase = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float ax = (e.getX(0) + e.getX(1)) / 2f;
                    float ay = (e.getY(0) + e.getY(1)) / 2f;
                    if (needBase) {
                        lx = ax;
                        ly = ay;
                        needBase = false;
                        return true;
                    }
                    float dx = ax - lx;
                    float dy = ay - ly;
                    lx = ax;
                    ly = ay;
                    twoDist += Math.abs(dx) + Math.abs(dy);
                    if (twoDist > slop * 2) twoMoved = true;
                    scrollAcc += dy;
                    int units = (int) (scrollAcc / 10f);
                    if (units != 0) {
                        scrollAcc -= units * 10f;
                        net.scroll((invertScroll ? -1 : 1) * units * 40);
                    }
                } else if (!multi) {
                    float x = e.getX();
                    float y = e.getY();
                    if (!moved && !dragging) {
                        float ddx = x - sx;
                        float ddy = y - sy;
                        if (Math.sqrt(ddx * ddx + ddy * ddy) <= slop) return true;
                    }
                    moved = true;
                    sendMove(x - lx, y - ly);
                    lx = x;
                    ly = y;
                }
                return true;

            case MotionEvent.ACTION_UP: {
                long dur = e.getEventTime() - downTime;
                if (dragging) {
                    net.button(1, false);
                    dragging = false;
                    lastTapUp = 0;
                } else if (multi) {
                    if (!twoMoved && dur < 300) {
                        if (maxPointers == 2) net.click(2);
                        else if (maxPointers >= 3) net.click(3);
                    }
                    lastTapUp = 0;
                } else if (!moved && dur < 250) {
                    net.click(1);
                    lastTapUp = e.getEventTime();
                } else {
                    lastTapUp = 0;
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    net.button(1, false);
                    dragging = false;
                }
                lastTapUp = 0;
                return true;
        }
        return true;
    }
}
