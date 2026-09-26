package org.andr36oid.buttonmapper;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ButtonMappingActivity extends Activity implements ControllerView.Listener {

    private static final String STATE_SELECTION = "selection";
    private static final int CONFIRM_TIMEOUT_SECONDS = 10;
    private static final int SCAN_CODE_A = 304;
    private static final String NEW_PROFILE = "new";
    private static final String DELETE_PROFILE = "delete";

    // What the drawing activates the selected button with.
    private static final List<String> OK_ACTIONS = Arrays.asList(
            "BUTTON_A", "DPAD_CENTER", "ENTER");
    // What presses the focused button of a dialog, best first.
    private static final List<String> DIALOG_OK_ACTIONS = Arrays.asList(
            "BUTTON_A", "DPAD_CENTER", "ENTER", "BUTTON_START", "BUTTON_THUMBL", "BUTTON_Y");

    // Names of the LayoutValidator functions.
    private static final int[] FUNCTION_NAMES = {
            R.string.function_up, R.string.function_down, R.string.function_left,
            R.string.function_right, R.string.function_select, R.string.function_back,
            R.string.function_home, R.string.function_power,
    };

    private ButtonMapping mMapping;
    private ButtonRemap mRemap;

    private ControllerView mController;
    private TextView mNameView;
    private TextView mActionView;
    private Button mProfileButton;
    private AlertDialog mDialog;

    // A change waiting to be confirmed with the new buttons, undone when it isn't.
    private AlertDialog mConfirmDialog;
    private CountDownTimer mConfirmTimer;
    private ButtonProfiles mUndoProfiles;

    private final ContentObserver mSettingsObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    mMapping.reload();
                    updateViews();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_button_mapping);

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mMapping = new ButtonMapping(this);
        mRemap = mMapping.getActiveRemap();

        mController = findViewById(R.id.controller);
        mNameView = findViewById(R.id.button_name);
        mActionView = findViewById(R.id.button_action);
        mProfileButton = findViewById(R.id.profile);
        mProfileButton.setOnClickListener(v -> showProfilesDialog());

        mController.setPresent(mMapping.present);
        mController.setListener(this);
        final int selection = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_SELECTION) : HardwareButton.indexOf(SCAN_CODE_A);
        mController.setSelection(selection);

        // The framework resets all buttons while both volume buttons are held down.
        final boolean volumeButtons =
                mMapping.present[HardwareButton.indexOf(HardwareButton.KEY_VOLUMEUP)]
                && mMapping.present[HardwareButton.indexOf(HardwareButton.KEY_VOLUMEDOWN)];
        findViewById(R.id.rescue_tip).setVisibility(volumeButtons ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (String setting : new String[] { ButtonRemap.SETTING, ButtonMapping.SETTING_PROFILES,
                ButtonMapping.SETTING_ACTIVE_PROFILE }) {
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(setting),
                    false, mSettingsObserver);
        }
        mMapping.reload();
        updateViews();
        checkCurrentLayout();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving undoes a change that wasn't confirmed yet.
        finishConfirmation(false);
        getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissDialog();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTION, mController.getSelection());
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    // Pressing a button picks it on the drawing, even while the drawing isn't focused.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mController.findPickedButton(keyCode, event) >= 0) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        final int index = mController.findPickedButton(keyCode, event);
        if (index >= 0) {
            mController.requestFocus();
            mController.setSelection(index);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onButtonSelected(int index) {
        updatePanel();
    }

    @Override
    public void onButtonActivated(int index) {
        if (mMapping.buttons[index].locked) {
            showDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.power_locked_title)
                    .setMessage(R.string.power_locked_message)
                    .setPositiveButton(android.R.string.ok, null));
        } else {
            showChooser(index);
        }
    }

    private void showChooser(int index) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final ActionListAdapter adapter = new ActionListAdapter(builder.getContext());
        final String defaultAction = mMapping.defaults[index];
        adapter.addAction(getString(R.string.action_original, getActionTitle(defaultAction)),
                defaultAction);
        for (ButtonActions.Item item : ButtonActions.ITEMS) {
            if (item.isHeader()) {
                adapter.addHeader(getString(item.titleRes));
            } else {
                adapter.addAction(getString(item.titleRes), item.value);
            }
        }

        final String action = mMapping.getAction(mRemap, index);
        final int checked = action.equals(defaultAction) ? 0 : adapter.findPosition(action, 1);
        builder.setTitle(getString(R.string.choose_title,
                        getString(mMapping.buttons[index].nameRes)))
                .setSingleChoiceItems(adapter, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onActionChosen(index, adapter.getValue(which));
                })
                .setNegativeButton(android.R.string.cancel, null);
        showDialog(builder);
    }

    private void onActionChosen(int index, String action) {
        final String oldAction = mMapping.getAction(mRemap, index);
        if (action.equals(oldAction)) {
            return;
        }

        final ButtonRemap changed = mRemap.copy();
        mMapping.setAction(changed, index, action);
        final List<Integer> missing = mMapping.findMissing(changed);

        // The button that already does this could take over the old job of this one.
        final int other = ButtonRemap.DISABLED.equals(action)
                ? -1 : findOnlyOtherButton(index, action);
        ButtonRemap swapped = null;
        if (other >= 0) {
            swapped = changed.copy();
            mMapping.setAction(swapped, other, oldAction);
            if (!mMapping.findMissing(swapped).isEmpty()) {
                swapped = null;
            }
        }

        final ButtonActions.Item item = ButtonActions.find(action);
        if (!missing.isEmpty()) {
            if (swapped != null) {
                showSwapDialog(index, other, changed, swapped, missing);
            } else {
                showBlockedDialog(getString(R.string.blocked_message, describe(missing)));
            }
        } else if (swapped != null && item != null && item.swappable) {
            showSwapDialog(index, other, changed, swapped, missing);
        } else {
            applyLayout(changed);
        }
    }

    /** Returns the only other button that can be changed and does an action, or -1. */
    private int findOnlyOtherButton(int index, String action) {
        int found = -1;
        for (int i = 0; i < mMapping.buttons.length; i++) {
            if (i == index || !mMapping.present[i]
                    || !action.equals(mMapping.getAction(mRemap, i))) {
                continue;
            }
            if (found >= 0 || mMapping.buttons[i].locked) {
                return -1;
            }
            found = i;
        }
        return found;
    }

    private void showSwapDialog(int index, int other, ButtonRemap changed, ButtonRemap swapped,
            List<Integer> missing) {
        final String name = getString(mMapping.buttons[index].nameRes);
        final String otherName = getString(mMapping.buttons[other].nameRes);
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.swap_title, otherName))
                .setPositiveButton(R.string.swap_confirm, (dialog, which) ->
                        applyLayout(swapped));
        if (missing.isEmpty()) {
            builder.setMessage(getString(R.string.swap_message, otherName, name))
                    .setNegativeButton(R.string.swap_only_this, (dialog, which) ->
                            applyLayout(changed));
        } else {
            // Changing only this button would lock the user out, swapping is the only way.
            builder.setMessage(getString(R.string.swap_needed_message, otherName, name,
                    describe(missing)))
                    .setNegativeButton(android.R.string.cancel, null);
        }
        showDialog(builder);
    }

    private void showBlockedDialog(String message) {
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.blocked_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null));
    }

    /** Saves a changed layout in the active profile and puts it to use. */
    private void applyLayout(ButtonRemap remap) {
        final List<Integer> missing = mMapping.findMissing(remap);
        if (!missing.isEmpty()) {
            // Never save a layout that locks the user out.
            showBlockedDialog(getString(R.string.blocked_message, describe(missing)));
            return;
        }
        final ButtonProfiles undoProfiles = mMapping.snapshot();
        final ButtonProfiles.Profile created = mMapping.saveLayout(remap);
        updateViews();
        if (created != null) {
            Toast.makeText(this, getString(R.string.profile_forked, mMapping.getName(created)),
                    Toast.LENGTH_LONG).show();
        }
        confirmIfNavigationChanged(undoProfiles);
    }

    private void showProfilesDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final ActionListAdapter adapter = new ActionListAdapter(builder.getContext());
        for (ButtonProfiles.Profile profile : mMapping.getProfiles()) {
            adapter.addAction(mMapping.getName(profile), profile.id);
        }
        if (mMapping.canCreateProfile()) {
            adapter.addCommand(getString(R.string.profile_new), NEW_PROFILE);
        }
        // In the list, so any profile can be deleted with the d-pad, not just the active one
        if (mMapping.getProfiles().size() > 1) {
            adapter.addCommand(getString(R.string.profile_delete_choose), DELETE_PROFILE);
        }

        final ButtonProfiles.Profile active = mMapping.getActiveProfile();
        builder.setTitle(R.string.profiles_title)
                .setSingleChoiceItems(adapter, adapter.findPosition(active.id, 0),
                        (dialog, which) -> {
                            dialog.dismiss();
                            final String id = adapter.getValue(which);
                            if (NEW_PROFILE.equals(id)) {
                                createProfile();
                            } else if (DELETE_PROFILE.equals(id)) {
                                showDeleteChooser();
                            } else if (!id.equals(active.id)) {
                                switchProfile(id);
                            }
                        })
                .setPositiveButton(R.string.profiles_close, null);
        if (!active.isStandard()) {
            builder.setNeutralButton(R.string.profile_rename, (dialog, which) ->
                            showRenameDialog(active))
                    .setNegativeButton(R.string.profile_delete, (dialog, which) ->
                            showDeleteDialog(active));
        }
        showDialog(builder);
    }

    private void switchProfile(String id) {
        ButtonProfiles.Profile target = null;
        for (ButtonProfiles.Profile profile : mMapping.getProfiles()) {
            if (profile.id.equals(id)) {
                target = profile;
            }
        }
        if (target == null) {
            return;
        }
        final List<Integer> missing = mMapping.findMissing(mMapping.getRemap(target));
        if (!missing.isEmpty()) {
            showBlockedDialog(getString(R.string.profile_blocked_message,
                    mMapping.getName(target), describe(missing)));
            return;
        }
        final ButtonProfiles undoProfiles = mMapping.snapshot();
        mMapping.activate(id);
        updateViews();
        // The standard buttons are always fine to go back to.
        if (!target.isStandard()) {
            confirmIfNavigationChanged(undoProfiles);
        }
    }

    private void createProfile() {
        final ButtonProfiles.Profile profile = mMapping.createProfile();
        updateViews();
        Toast.makeText(this, getString(R.string.profile_created, mMapping.getName(profile)),
                Toast.LENGTH_LONG).show();
    }

    private void showRenameDialog(ButtonProfiles.Profile profile) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final EditText input = new EditText(builder.getContext());
        input.setSingleLine(true);
        input.setText(profile.name);
        input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(ButtonProfiles.MAX_NAME_LENGTH) });
        final FrameLayout container = new FrameLayout(builder.getContext());
        final int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input);

        builder.setTitle(R.string.profile_rename_title)
                .setView(container)
                .setPositiveButton(R.string.profile_rename_save, (dialog, which) -> {
                    if (mMapping.renameProfile(profile.id, input.getText().toString())) {
                        updateViews();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        showDialog(builder);
        input.requestFocus();
    }

    private void showDeleteChooser() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final ActionListAdapter adapter = new ActionListAdapter(builder.getContext());
        for (ButtonProfiles.Profile profile : mMapping.getProfiles()) {
            if (!profile.isStandard()) {
                adapter.addAction(mMapping.getName(profile), profile.id);
            }
        }
        builder.setTitle(R.string.profile_delete_choose_title)
                .setAdapter(adapter, (dialog, which) -> {
                    final String id = adapter.getValue(which);
                    for (ButtonProfiles.Profile profile : mMapping.getProfiles()) {
                        if (profile.id.equals(id)) {
                            showDeleteDialog(profile);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        showDialog(builder);
    }

    private void showDeleteDialog(ButtonProfiles.Profile profile) {
        final boolean active = profile.id.equals(mMapping.getActiveProfile().id);
        showDialog(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.profile_delete_title, mMapping.getName(profile)))
                .setMessage(active ? R.string.profile_delete_message
                        : R.string.profile_delete_message_inactive)
                .setPositiveButton(R.string.profile_delete, (dialog, which) -> {
                    // Deleting switches to the standard buttons, which are always fine.
                    mMapping.deleteProfile(profile.id);
                    updateViews();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** Offers the standard buttons when the saved layout, e.g. set up with adb, is unusable. */
    private void checkCurrentLayout() {
        final List<Integer> missing = mMapping.findMissing(mRemap);
        if (missing.isEmpty() || mConfirmDialog != null) {
            return;
        }
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.problem_title)
                .setMessage(getString(R.string.problem_message, describe(missing)))
                .setPositiveButton(R.string.problem_fix, (dialog, which) -> {
                    mMapping.activate(ButtonProfiles.STANDARD_ID);
                    updateViews();
                })
                .setNegativeButton(R.string.problem_later, null));
    }

    /**
     * Asks to confirm the layout now in use with its own buttons when it changed how the menus
     * are used, and goes back to {@code undoProfiles} if that doesn't happen.
     */
    private void confirmIfNavigationChanged(ButtonProfiles undoProfiles) {
        final ButtonRemap before = mMapping.getRemap(undoProfiles.getActive());
        if (!mMapping.changesNavigation(before, mRemap)) {
            return;
        }
        mUndoProfiles = undoProfiles;
        final int okButton = mMapping.findButtonFor(mRemap, DIALOG_OK_ACTIONS);
        final String okName = okButton >= 0
                ? getString(mMapping.buttons[okButton].nameRes) : null;
        mConfirmDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_title)
                .setMessage(getConfirmMessage(okName, CONFIRM_TIMEOUT_SECONDS))
                .setPositiveButton(R.string.confirm_keep, (dialog, which) ->
                        finishConfirmation(true))
                .setNegativeButton(R.string.confirm_undo, (dialog, which) ->
                        finishConfirmation(false))
                .setOnCancelListener(dialog -> finishConfirmation(false))
                .create();
        mConfirmDialog.setOnShowListener(dialog -> ((AlertDialog) dialog)
                .getButton(DialogInterface.BUTTON_POSITIVE).requestFocus());
        mConfirmDialog.show();

        mConfirmTimer = new CountDownTimer(CONFIRM_TIMEOUT_SECONDS * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (mConfirmDialog != null) {
                    mConfirmDialog.setMessage(getConfirmMessage(okName,
                            (int) ((millisUntilFinished + 999) / 1000)));
                }
            }

            @Override
            public void onFinish() {
                finishConfirmation(false);
            }
        }.start();
    }

    private void finishConfirmation(boolean keep) {
        if (mConfirmDialog == null) {
            return;
        }
        final AlertDialog dialog = mConfirmDialog;
        mConfirmDialog = null;
        mConfirmTimer.cancel();
        mConfirmTimer = null;
        dialog.dismiss();

        if (!keep) {
            mMapping.restore(mUndoProfiles);
            updateViews();
        }
        mUndoProfiles = null;
    }

    private String getConfirmMessage(String okName, int seconds) {
        return okName != null
                ? getResources().getQuantityString(R.plurals.confirm_message, seconds, okName,
                        seconds)
                : getResources().getQuantityString(R.plurals.confirm_message_generic, seconds,
                        seconds);
    }

    private void updateViews() {
        mRemap = mMapping.getActiveRemap();
        final boolean[] changed = new boolean[mMapping.buttons.length];
        for (int i = 0; i < changed.length; i++) {
            changed[i] = !mMapping.getAction(mRemap, i).equals(mMapping.defaults[i]);
        }
        mController.setChanged(changed);

        final int okButton = mMapping.findButtonFor(mRemap, OK_ACTIONS);
        mController.setHint(okButton >= 0
                ? getString(R.string.screen_hint, getString(mMapping.buttons[okButton].nameRes))
                : getString(R.string.screen_hint_no_ok));
        mProfileButton.setText(getString(R.string.profile_button,
                mMapping.getName(mMapping.getActiveProfile())));
        updatePanel();
    }

    private void updatePanel() {
        final int index = mController.getSelection();
        final String name = getString(mMapping.buttons[index].nameRes);
        final String action = mMapping.getAction(mRemap, index);
        final String status;
        if (mMapping.buttons[index].locked) {
            status = getString(R.string.status_locked, getActionTitle(action));
        } else if (action.equals(mMapping.defaults[index])) {
            status = getString(R.string.status_default, getActionTitle(action));
        } else {
            status = getString(R.string.status_changed, getActionTitle(action),
                    getActionTitle(mMapping.defaults[index]));
        }
        mNameView.setText(name);
        mActionView.setText(status);
        mController.setContentDescription(name + ". " + status);
    }

    private String getActionTitle(String action) {
        final ButtonActions.Item item = ButtonActions.find(action);
        // Something set up by hand that the list doesn't offer, show its key code.
        return item != null ? getString(item.titleRes) : action;
    }

    private String describe(List<Integer> functions) {
        final List<String> names = new ArrayList<>();
        for (int function : functions) {
            names.add(getString(FUNCTION_NAMES[function]));
        }
        return String.join(", ", names);
    }

    private void showDialog(AlertDialog.Builder builder) {
        dismissDialog();
        mDialog = builder.show();
    }

    private void dismissDialog() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }
}
