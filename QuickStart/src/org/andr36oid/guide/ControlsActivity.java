package org.andr36oid.guide;

import java.util.List;

/**
 * Settings > Buttons and shortcuts: every button combination on one page, from the system's
 * FN shortcuts to the emulator hotkeys and mouse mode, with a drawing of the console. Looks
 * and works like the quick start guide; the list comes from res/raw/controls.txt.
 */
public class ControlsActivity extends GuideActivity {

    @Override
    protected List<Chapters.Chapter> buildChapters() {
        return Controls.build(this);
    }

    @Override
    protected CharSequence getHeaderTitle() {
        return getText(R.string.controls_title);
    }

    @Override
    protected boolean offersTutorial() {
        return false;
    }
}
