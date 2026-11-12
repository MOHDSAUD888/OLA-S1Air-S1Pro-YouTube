package dev.olatool;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/**
 * One top-level tab that hosts several related screens behind a segmented
 * control, so the main tab bar stays short instead of listing sixteen screens.
 *
 * <p>Each child is an ordinary {@link Tab}; this class only forwards
 * {@link #onShow()} / {@link #onHide()} to the child that is currently visible,
 * so a child's timers run only while its segment is selected. The children are
 * built once and reused — switching a segment never rebuilds a screen.
 */
public final class GroupTab implements Tab {

    private final LinearLayout root;
    private final FrameLayout content;
    private final Button[] segButtons;
    private final Tab[] children;
    private final View[] views;

    private boolean visible;
    private int current = -1;
    private int startAt;

    public GroupTab(Context c, String[] labels, Tab[] children) {
        this.children = children;
        this.views = new View[children.length];
        this.segButtons = new Button[children.length];

        root = Ui.column(c);
        root.setBackgroundColor(Ui.BG);

        // Segmented selector: a single rounded track holding one chip per child.
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 4));
        LinearLayout seg = Ui.row(c);
        seg.setBackground(Ui.round(c, Ui.CARD, Ui.RADIUS_CONTROL, Ui.LINE));
        seg.setPadding(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        for (int i = 0; i < children.length; i++) {
            final int index = i;
            Button b = new Button(c);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(Ui.dp(c, 36));
            b.setMinimumHeight(Ui.dp(c, 36));
            b.setStateListAnimator(null);
            b.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
            Ui.wire(b, v -> select(index));
            segButtons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = Ui.dp(c, 2);
            }
            seg.addView(b, lp);
        }
        scroll.addView(seg);
        root.addView(scroll);

        content = new FrameLayout(c);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    public View view() {
        return root;
    }

    /** Sub-screen to open the first time this group is shown. Default: the first. */
    public void startAt(int index) {
        startAt = index;
    }

    @Override
    public void onShow() {
        visible = true;
        if (current < 0) {
            select(startAt);
        } else {
            children[current].onShow();
        }
    }

    @Override
    public void onHide() {
        visible = false;
        if (current >= 0) {
            children[current].onHide();
        }
    }

    private void select(int index) {
        if (index == current) {
            return;
        }
        if (current >= 0) {
            children[current].onHide();
        }
        current = index;
        content.removeAllViews();
        if (views[index] == null) {
            views[index] = children[index].view();
        }
        content.addView(views[index], new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paint();
        if (visible) {
            children[index].onShow();
        }
    }

    private void paint() {
        for (int i = 0; i < segButtons.length; i++) {
            Button b = segButtons[i];
            boolean on = i == current;
            b.setTextColor(on ? Ui.ACCENT : Ui.DIM);
            b.setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            // Tinted, not solid: the solid accent is reserved for the one
            // primary action on a screen.
            b.setBackground(on ? Ui.round(b.getContext(), Ui.alpha(Ui.ACCENT, 0x26), 9, 0)
                    : Ui.round(b.getContext(), 0x00000000, 9, 0));
        }
    }
}
