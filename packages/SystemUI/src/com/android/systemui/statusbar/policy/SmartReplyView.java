package com.android.systemui.statusbar.policy;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.TextPaint;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.Button;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** View which displays smart reply and smart actions buttons in notifications. */
public class SmartReplyView extends ViewGroup {

    private static final String TAG = "SmartReplyView";

    private static final int MEASURE_SPEC_ANY_LENGTH =
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

    private static final Comparator<View> DECREASING_MEASURED_WIDTH_WITHOUT_PADDING_COMPARATOR =
            (v1, v2) -> ((v2.getMeasuredWidth() - v2.getPaddingLeft() - v2.getPaddingRight())
                    - (v1.getMeasuredWidth() - v1.getPaddingLeft() - v1.getPaddingRight()));

    private static final int SQUEEZE_FAILED = -1;

    private final SmartReplyConstants mConstants;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    /**
     * The upper bound for the height of this view in pixels. Notifications are automatically
     * recreated on density or font size changes so caching this should be fine.
     */
    private final int mHeightUpperLimit;

    /** Spacing to be applied between views. */
    private final int mSpacing;

    /** Horizontal padding of smart reply buttons if all of them use only one line of text. */
    private final int mSingleLineButtonPaddingHorizontal;

    /** Horizontal padding of smart reply buttons if at least one of them uses two lines of text. */
    private final int mDoubleLineButtonPaddingHorizontal;

    /** Increase in width of a smart reply button as a result of using two lines instead of one. */
    private final int mSingleToDoubleLineButtonWidthIncrease;

    private final BreakIterator mBreakIterator;

    private PriorityQueue<Button> mCandidateButtonQueueForSqueezing;

    private View mSmartReplyContainer;

    @ColorInt
    private int mCurrentBackgroundColor;
    @ColorInt
    private final int mDefaultBackgroundColor;
    @ColorInt
    private final int mDefaultStrokeColor;
    @ColorInt
    private final int mDefaultTextColor;
    @ColorInt
    private final int mDefaultTextColorDarkBg;
    @ColorInt
    private final int mRippleColorDarkBg;
    @ColorInt
    private final int mRippleColor;
    private final int mStrokeWidth;
    private final double mMinStrokeContrast;

    private ActivityStarter mActivityStarter;

    public SmartReplyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mConstants = Dependency.get(SmartReplyConstants.class);
        mKeyguardDismissUtil = Dependency.get(KeyguardDismissUtil.class);
        mRemoteInputManager = Dependency.get(NotificationRemoteInputManager.class);

        mHeightUpperLimit = NotificationUtils.getFontScaledHeight(mContext,
            R.dimen.smart_reply_button_max_height);

        mCurrentBackgroundColor = context.getColor(R.color.smart_reply_button_background);
        mDefaultBackgroundColor = mCurrentBackgroundColor;
        mDefaultTextColor = mContext.getColor(R.color.smart_reply_button_text);
        mDefaultTextColorDarkBg = mContext.getColor(R.color.smart_reply_button_text_dark_bg);
        mDefaultStrokeColor = mContext.getColor(R.color.smart_reply_button_stroke);
        mRippleColor = mContext.getColor(R.color.notification_ripple_untinted_color);
        mRippleColorDarkBg = Color.argb(Color.alpha(mRippleColor),
                255 /* red */, 255 /* green */, 255 /* blue */);
        mMinStrokeContrast = ContrastColorUtil.calculateContrast(mDefaultStrokeColor,
                mDefaultBackgroundColor);

        int spacing = 0;
        int singleLineButtonPaddingHorizontal = 0;
        int doubleLineButtonPaddingHorizontal = 0;
        int strokeWidth = 0;

        final TypedArray arr = context.obtainStyledAttributes(attrs, R.styleable.SmartReplyView,
                0, 0);
        final int length = arr.getIndexCount();
        for (int i = 0; i < length; i++) {
            int attr = arr.getIndex(i);
            if (attr == R.styleable.SmartReplyView_spacing) {
                spacing = arr.getDimensionPixelSize(i, 0);
            } else if (attr == R.styleable.SmartReplyView_singleLineButtonPaddingHorizontal) {
                singleLineButtonPaddingHorizontal = arr.getDimensionPixelSize(i, 0);
            } else if (attr == R.styleable.SmartReplyView_doubleLineButtonPaddingHorizontal) {
                doubleLineButtonPaddingHorizontal = arr.getDimensionPixelSize(i, 0);
            } else if (attr == R.styleable.SmartReplyView_buttonStrokeWidth) {
                strokeWidth = arr.getDimensionPixelSize(i, 0);
            }
        }
        arr.recycle();

        mStrokeWidth = strokeWidth;
        mSpacing = spacing;
        mSingleLineButtonPaddingHorizontal = singleLineButtonPaddingHorizontal;
        mDoubleLineButtonPaddingHorizontal = doubleLineButtonPaddingHorizontal;
        mSingleToDoubleLineButtonWidthIncrease =
                2 * (doubleLineButtonPaddingHorizontal - singleLineButtonPaddingHorizontal);


        mBreakIterator = BreakIterator.getLineInstance();
        reallocateCandidateButtonQueueForSqueezing();
    }

    /**
     * Returns an upper bound for the height of this view in pixels. This method is intended to be
     * invoked before onMeasure, so it doesn't do any analysis on the contents of the buttons.
     */
    public int getHeightUpperLimit() {
       return mHeightUpperLimit;
    }

    private void reallocateCandidateButtonQueueForSqueezing() {
        // Instead of clearing the priority queue, we re-allocate so that it would fit all buttons
        // exactly. This avoids (1) wasting memory because PriorityQueue never shrinks and
        // (2) growing in onMeasure.
        // The constructor throws an IllegalArgument exception if initial capacity is less than 1.
        mCandidateButtonQueueForSqueezing = new PriorityQueue<>(
                Math.max(getChildCount(), 1), DECREASING_MEASURED_WIDTH_WITHOUT_PADDING_COMPARATOR);
    }

    /**
     * Reset the smart suggestions view to allow adding new replies and actions.
     */
    public void resetSmartSuggestions(View newSmartReplyContainer) {
        mSmartReplyContainer = newSmartReplyContainer;
        removeAllViews();
        mCurrentBackgroundColor = mDefaultBackgroundColor;
    }

    /**
     * Add smart replies to this view, using the provided {@link RemoteInput} and
     * {@link PendingIntent} to respond when the user taps a smart reply. Only the replies that fit
     * into the notification are shown.
     */
    public void addRepliesFromRemoteInput(
            SmartReplies smartReplies,
            SmartReplyController smartReplyController, NotificationData.Entry entry) {
        if (smartReplies.remoteInput != null && smartReplies.pendingIntent != null) {
            if (smartReplies.choices != null) {
                for (int i = 0; i < smartReplies.choices.length; ++i) {
                    Button replyButton = inflateReplyButton(
                            getContext(), this, i, smartReplies, smartReplyController, entry);
                    addView(replyButton);
                }
            }
        }
        reallocateCandidateButtonQueueForSqueezing();
    }

    /**
     * Add smart actions to be shown next to smart replies. Only the actions that fit into the
     * notification are shown.
     */
    public void addSmartActions(SmartActions smartActions,
            SmartReplyController smartReplyController, NotificationData.Entry entry,
            HeadsUpManager headsUpManager) {
        int numSmartActions = smartActions.actions.size();
        for (int n = 0; n < numSmartActions; n++) {
            Notification.Action action = smartActions.actions.get(n);
            if (action.actionIntent != null) {
                Button actionButton = inflateActionButton(
                        getContext(), this, n, smartActions, smartReplyController, entry,
                        headsUpManager);
                addView(actionButton);
            }
        }
        reallocateCandidateButtonQueueForSqueezing();
    }

    public static SmartReplyView inflate(Context context, ViewGroup root) {
        return (SmartReplyView)
                LayoutInflater.from(context).inflate(R.layout.smart_reply_view, root, false);
    }

    @VisibleForTesting
    Button inflateReplyButton(Context context, ViewGroup root, int replyIndex,
            SmartReplies smartReplies, SmartReplyController smartReplyController,
            NotificationData.Entry entry) {
        Button b = (Button) LayoutInflater.from(context).inflate(
                R.layout.smart_reply_button, root, false);
        CharSequence choice = smartReplies.choices[replyIndex];
        b.setText(choice);

        OnDismissAction action = () -> {
            // TODO(b/111437455): Also for EDIT_CHOICES_BEFORE_SENDING_AUTO, depending on flags.
            if (smartReplies.remoteInput.getEditChoicesBeforeSending()
                    == RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED) {
                entry.remoteInputText = choice;
                mRemoteInputManager.activateRemoteInput(b,
                        new RemoteInput[] { smartReplies.remoteInput }, smartReplies.remoteInput,
                        smartReplies.pendingIntent);
                return false;
            }

            smartReplyController.smartReplySent(
                    entry, replyIndex, b.getText(), smartReplies.fromAssistant);
            Bundle results = new Bundle();
            results.putString(smartReplies.remoteInput.getResultKey(), choice.toString());
            Intent intent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            RemoteInput.addResultsToIntent(new RemoteInput[] { smartReplies.remoteInput }, intent,
                    results);
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_CHOICE);
            entry.setHasSentReply();
            try {
                smartReplies.pendingIntent.send(context, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Unable to send smart reply", e);
            }
            mSmartReplyContainer.setVisibility(View.GONE);
            return false; // do not defer
        };

        b.setOnClickListener(view -> {
            mKeyguardDismissUtil.executeWhenUnlocked(action);
        });

        b.setAccessibilityDelegate(new AccessibilityDelegate() {
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                String label = getResources().getString(R.string.accessibility_send_smart_reply);
                info.addAction(new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label));
            }
        });

        setColors(b, mCurrentBackgroundColor, mDefaultStrokeColor, mDefaultTextColor, mRippleColor);
        return b;
    }

    @VisibleForTesting
    Button inflateActionButton(Context context, ViewGroup root, int actionIndex,
            SmartActions smartActions, SmartReplyController smartReplyController,
            NotificationData.Entry entry, HeadsUpManager headsUpManager) {
        Notification.Action action = smartActions.actions.get(actionIndex);
        Button button = (Button) LayoutInflater.from(context).inflate(
                R.layout.smart_action_button, root, false);
        button.setText(action.title);

        Drawable iconDrawable = action.getIcon().loadDrawable(context);
        // Add the action icon to the Smart Action button.
        int newIconSize = context.getResources().getDimensionPixelSize(
                R.dimen.smart_action_button_icon_size);
        iconDrawable.setBounds(0, 0, newIconSize, newIconSize);
        button.setCompoundDrawables(iconDrawable, null, null, null);

        button.setOnClickListener(view ->
                getActivityStarter().startPendingIntentDismissingKeyguard(
                        action.actionIntent,
                        () -> {
                            smartReplyController.smartActionClicked(
                                    entry, actionIndex, action, smartActions.fromAssistant);
                            postOnUiThread(() ->
                                    headsUpManager.removeNotification(entry.key, true));
                        }));

        // TODO(b/119010281): handle accessibility

        // Mark this as an Action button
        final LayoutParams lp = (LayoutParams) button.getLayoutParams();
        lp.buttonType = SmartButtonType.ACTION;
        return button;
    }

    private void postOnUiThread(Runnable runnable) {
        mMainThreadHandler.post(runnable);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(mContext, attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new LayoutParams(params.width, params.height);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int targetWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(widthMeasureSpec);

        // Mark all buttons as hidden and un-squeezed.
        resetButtonsLayoutParams();

        if (!mCandidateButtonQueueForSqueezing.isEmpty()) {
            Log.wtf(TAG, "Single line button queue leaked between onMeasure calls");
            mCandidateButtonQueueForSqueezing.clear();
        }

        int measuredWidth = mPaddingLeft + mPaddingRight;
        int maxChildHeight = 0;
        int displayedChildCount = 0;
        int buttonPaddingHorizontal = mSingleLineButtonPaddingHorizontal;

        // Set up a list of suggestions where actions come before replies. Note that the Buttons
        // themselves have already been added to the view hierarchy in an order such that Smart
        // Replies are shown before Smart Actions. The order of the list below determines which
        // suggestions will be shown at all - only the first X elements are shown (where X depends
        // on how much space each suggestion button needs).
        List<View> smartActions = filterActionsOrReplies(SmartButtonType.ACTION);
        List<View> smartReplies = filterActionsOrReplies(SmartButtonType.REPLY);
        List<View> smartSuggestions = new ArrayList<>(smartActions);
        smartSuggestions.addAll(smartReplies);
        List<View> coveredSuggestions = new ArrayList<>();

        for (View child : smartSuggestions) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            child.setPadding(buttonPaddingHorizontal, child.getPaddingTop(),
                    buttonPaddingHorizontal, child.getPaddingBottom());
            child.measure(MEASURE_SPEC_ANY_LENGTH, heightMeasureSpec);

            coveredSuggestions.add(child);

            final int lineCount = ((Button) child).getLineCount();
            if (lineCount < 1 || lineCount > 2) {
                // If smart reply has no text, or more than two lines, then don't show it.
                continue;
            }

            if (lineCount == 1) {
                mCandidateButtonQueueForSqueezing.add((Button) child);
            }

            // Remember the current measurements in case the current button doesn't fit in.
            final int originalMaxChildHeight = maxChildHeight;
            final int originalMeasuredWidth = measuredWidth;
            final int originalButtonPaddingHorizontal = buttonPaddingHorizontal;

            final int spacing = displayedChildCount == 0 ? 0 : mSpacing;
            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            measuredWidth += spacing + childWidth;
            maxChildHeight = Math.max(maxChildHeight, childHeight);

            // Do we need to increase the number of lines in smart reply buttons to two?
            final boolean increaseToTwoLines =
                    buttonPaddingHorizontal == mSingleLineButtonPaddingHorizontal
                            && (lineCount == 2 || measuredWidth > targetWidth);
            if (increaseToTwoLines) {
                measuredWidth += (displayedChildCount + 1) * mSingleToDoubleLineButtonWidthIncrease;
                buttonPaddingHorizontal = mDoubleLineButtonPaddingHorizontal;
            }

            // If the last button doesn't fit into the remaining width, try squeezing preceding
            // smart reply buttons.
            if (measuredWidth > targetWidth) {
                // Keep squeezing preceding and current smart reply buttons until they all fit.
                while (measuredWidth > targetWidth
                        && !mCandidateButtonQueueForSqueezing.isEmpty()) {
                    final Button candidate = mCandidateButtonQueueForSqueezing.poll();
                    final int squeezeReduction = squeezeButton(candidate, heightMeasureSpec);
                    if (squeezeReduction != SQUEEZE_FAILED) {
                        maxChildHeight = Math.max(maxChildHeight, candidate.getMeasuredHeight());
                        measuredWidth -= squeezeReduction;
                    }
                }

                // If the current button still doesn't fit after squeezing all buttons, undo the
                // last squeezing round.
                if (measuredWidth > targetWidth) {
                    measuredWidth = originalMeasuredWidth;
                    maxChildHeight = originalMaxChildHeight;
                    buttonPaddingHorizontal = originalButtonPaddingHorizontal;

                    // Mark all buttons from the last squeezing round as "failed to squeeze", so
                    // that they're re-measured without squeezing later.
                    markButtonsWithPendingSqueezeStatusAs(
                            LayoutParams.SQUEEZE_STATUS_FAILED, coveredSuggestions);

                    // The current button doesn't fit, so there's no point in measuring further
                    // buttons.
                    break;
                }

                // The current button fits, so mark all squeezed buttons as "successfully squeezed"
                // to prevent them from being un-squeezed in a subsequent squeezing round.
                markButtonsWithPendingSqueezeStatusAs(
                        LayoutParams.SQUEEZE_STATUS_SUCCESSFUL, coveredSuggestions);
            }

            lp.show = true;
            displayedChildCount++;
        }

        // We're done squeezing buttons, so we can clear the priority queue.
        mCandidateButtonQueueForSqueezing.clear();

        // Finally, we need to re-measure some buttons.
        remeasureButtonsIfNecessary(buttonPaddingHorizontal, maxChildHeight);

        setMeasuredDimension(
                resolveSize(Math.max(getSuggestedMinimumWidth(), measuredWidth), widthMeasureSpec),
                resolveSize(Math.max(getSuggestedMinimumHeight(),
                        mPaddingTop + maxChildHeight + mPaddingBottom), heightMeasureSpec));
    }

    private List<View> filterActionsOrReplies(SmartButtonType buttonType) {
        List<View> actions = new ArrayList<>();
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (child.getVisibility() != View.VISIBLE || !(child instanceof Button)) {
                continue;
            }
            if (lp.buttonType == buttonType) {
                actions.add(child);
            }
        }
        return actions;
    }

    private void resetButtonsLayoutParams() {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.show = false;
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_NONE;
        }
    }

    private int squeezeButton(Button button, int heightMeasureSpec) {
        final int estimatedOptimalTextWidth = estimateOptimalSqueezedButtonTextWidth(button);
        if (estimatedOptimalTextWidth == SQUEEZE_FAILED) {
            return SQUEEZE_FAILED;
        }
        return squeezeButtonToTextWidth(button, heightMeasureSpec, estimatedOptimalTextWidth);
    }

    private int estimateOptimalSqueezedButtonTextWidth(Button button) {
        // Find a line-break point in the middle of the smart reply button text.
        final String rawText = button.getText().toString();

        // The button sometimes has a transformation affecting text layout (e.g. all caps).
        final TransformationMethod transformation = button.getTransformationMethod();
        final String text = transformation == null ?
                rawText : transformation.getTransformation(rawText, button).toString();
        final int length = text.length();
        mBreakIterator.setText(text);

        if (mBreakIterator.preceding(length / 2) == BreakIterator.DONE) {
            if (mBreakIterator.next() == BreakIterator.DONE) {
                // Can't find a single possible line break in either direction.
                return SQUEEZE_FAILED;
            }
        }

        final TextPaint paint = button.getPaint();
        final int initialPosition = mBreakIterator.current();
        final float initialLeftTextWidth = Layout.getDesiredWidth(text, 0, initialPosition, paint);
        final float initialRightTextWidth =
                Layout.getDesiredWidth(text, initialPosition, length, paint);
        float optimalTextWidth = Math.max(initialLeftTextWidth, initialRightTextWidth);

        if (initialLeftTextWidth != initialRightTextWidth) {
            // See if there's a better line-break point (leading to a more narrow button) in
            // either left or right direction.
            final boolean moveLeft = initialLeftTextWidth > initialRightTextWidth;
            final int maxSqueezeRemeasureAttempts = mConstants.getMaxSqueezeRemeasureAttempts();
            for (int i = 0; i < maxSqueezeRemeasureAttempts; i++) {
                final int newPosition =
                        moveLeft ? mBreakIterator.previous() : mBreakIterator.next();
                if (newPosition == BreakIterator.DONE) {
                    break;
                }

                final float newLeftTextWidth = Layout.getDesiredWidth(text, 0, newPosition, paint);
                final float newRightTextWidth =
                        Layout.getDesiredWidth(text, newPosition, length, paint);
                final float newOptimalTextWidth = Math.max(newLeftTextWidth, newRightTextWidth);
                if (newOptimalTextWidth < optimalTextWidth) {
                    optimalTextWidth = newOptimalTextWidth;
                } else {
                    break;
                }

                boolean tooFar = moveLeft
                        ? newLeftTextWidth <= newRightTextWidth
                        : newLeftTextWidth >= newRightTextWidth;
                if (tooFar) {
                    break;
                }
            }
        }

        return (int) Math.ceil(optimalTextWidth);
    }

    /**
     * Returns the combined width of the left drawable (the action icon) and the padding between the
     * drawable and the button text.
     */
    private int getLeftCompoundDrawableWidthWithPadding(Button button) {
        Drawable[] drawables = button.getCompoundDrawables();
        Drawable leftDrawable = drawables[0];
        if (leftDrawable == null) return 0;

        return leftDrawable.getBounds().width() + button.getCompoundDrawablePadding();
    }

    private int squeezeButtonToTextWidth(Button button, int heightMeasureSpec, int textWidth) {
        int oldWidth = button.getMeasuredWidth();
        if (button.getPaddingLeft() != mDoubleLineButtonPaddingHorizontal) {
            // Correct for the fact that the button was laid out with single-line horizontal
            // padding.
            oldWidth += mSingleToDoubleLineButtonWidthIncrease;
        }

        // Re-measure the squeezed smart reply button.
        button.setPadding(mDoubleLineButtonPaddingHorizontal, button.getPaddingTop(),
                mDoubleLineButtonPaddingHorizontal, button.getPaddingBottom());
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                2 * mDoubleLineButtonPaddingHorizontal + textWidth
                      + getLeftCompoundDrawableWidthWithPadding(button), MeasureSpec.AT_MOST);
        button.measure(widthMeasureSpec, heightMeasureSpec);

        final int newWidth = button.getMeasuredWidth();

        final LayoutParams lp = (LayoutParams) button.getLayoutParams();
        if (button.getLineCount() > 2 || newWidth >= oldWidth) {
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_FAILED;
            return SQUEEZE_FAILED;
        } else {
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_PENDING;
            return oldWidth - newWidth;
        }
    }

    private void remeasureButtonsIfNecessary(
            int buttonPaddingHorizontal, int maxChildHeight) {
        final int maxChildHeightMeasure =
                MeasureSpec.makeMeasureSpec(maxChildHeight, MeasureSpec.EXACTLY);

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.show) {
                continue;
            }

            boolean requiresNewMeasure = false;
            int newWidth = child.getMeasuredWidth();

            // Re-measure reason 1: The button needs to be un-squeezed (either because it resulted
            // in more than two lines or because it was unnecessary).
            if (lp.squeezeStatus == LayoutParams.SQUEEZE_STATUS_FAILED) {
                requiresNewMeasure = true;
                newWidth = Integer.MAX_VALUE;
            }

            // Re-measure reason 2: The button's horizontal padding is incorrect (because it was
            // measured with the wrong number of lines).
            if (child.getPaddingLeft() != buttonPaddingHorizontal) {
                requiresNewMeasure = true;
                if (newWidth != Integer.MAX_VALUE) {
                    if (buttonPaddingHorizontal == mSingleLineButtonPaddingHorizontal) {
                        // Change padding (2->1 line).
                        newWidth -= mSingleToDoubleLineButtonWidthIncrease;
                    } else {
                        // Change padding (1->2 lines).
                        newWidth += mSingleToDoubleLineButtonWidthIncrease;
                    }
                }
                child.setPadding(buttonPaddingHorizontal, child.getPaddingTop(),
                        buttonPaddingHorizontal, child.getPaddingBottom());
            }

            // Re-measure reason 3: The button's height is less than the max height of all buttons
            // (all should have the same height).
            if (child.getMeasuredHeight() != maxChildHeight) {
                requiresNewMeasure = true;
            }

            if (requiresNewMeasure) {
                child.measure(MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.AT_MOST),
                        maxChildHeightMeasure);
            }
        }
    }

    private void markButtonsWithPendingSqueezeStatusAs(
            int squeezeStatus, List<View> coveredChildren) {
        for (View child : coveredChildren) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.squeezeStatus == LayoutParams.SQUEEZE_STATUS_PENDING) {
                lp.squeezeStatus = squeezeStatus;
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        final int width = right - left;
        int position = isRtl ? width - mPaddingRight : mPaddingLeft;

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.show) {
                continue;
            }

            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            final int childLeft = isRtl ? position - childWidth : position;
            child.layout(childLeft, 0, childLeft + childWidth, childHeight);

            final int childWidthWithSpacing = childWidth + mSpacing;
            if (isRtl) {
                position -= childWidthWithSpacing;
            } else {
                position += childWidthWithSpacing;
            }
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();
        return lp.show && super.drawChild(canvas, child, drawingTime);
    }

    public void setBackgroundTintColor(int backgroundColor) {
        if (backgroundColor == mCurrentBackgroundColor) {
            // Same color ignoring.
           return;
        }
        mCurrentBackgroundColor = backgroundColor;

        final boolean dark = !ContrastColorUtil.isColorLight(backgroundColor);

        int textColor = ContrastColorUtil.ensureTextContrast(
                dark ? mDefaultTextColorDarkBg : mDefaultTextColor,
                backgroundColor | 0xff000000, dark);
        int strokeColor = ContrastColorUtil.ensureContrast(
                mDefaultStrokeColor, backgroundColor | 0xff000000, dark, mMinStrokeContrast);
        int rippleColor = dark ? mRippleColorDarkBg : mRippleColor;

        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final Button child = (Button) getChildAt(i);
            setColors(child, backgroundColor, strokeColor, textColor, rippleColor);
        }
    }

    private void setColors(Button button, int backgroundColor, int strokeColor, int textColor,
            int rippleColor) {
        Drawable drawable = button.getBackground();
        if (drawable instanceof RippleDrawable) {
            // Mutate in case other notifications are using this drawable.
            drawable = drawable.mutate();
            RippleDrawable ripple = (RippleDrawable) drawable;
            ripple.setColor(ColorStateList.valueOf(rippleColor));
            Drawable inset = ripple.getDrawable(0);
            if (inset instanceof InsetDrawable) {
                Drawable background = ((InsetDrawable) inset).getDrawable();
                if (background instanceof GradientDrawable) {
                    GradientDrawable gradientDrawable = (GradientDrawable) background;
                    gradientDrawable.setColor(backgroundColor);
                    gradientDrawable.setStroke(mStrokeWidth, strokeColor);
                }
            }
            button.setBackground(drawable);
        }
        button.setTextColor(textColor);
    }

    private ActivityStarter getActivityStarter() {
        if (mActivityStarter == null) {
            mActivityStarter = Dependency.get(ActivityStarter.class);
        }
        return mActivityStarter;
    }

    private enum SmartButtonType {
        REPLY,
        ACTION
    }

    @VisibleForTesting
    static class LayoutParams extends ViewGroup.LayoutParams {

        /** Button is not squeezed. */
        private static final int SQUEEZE_STATUS_NONE = 0;

        /**
         * Button was successfully squeezed, but it might be un-squeezed later if the squeezing
         * turns out to have been unnecessary (because there's still not enough space to add another
         * button).
         */
        private static final int SQUEEZE_STATUS_PENDING = 1;

        /** Button was successfully squeezed and it won't be un-squeezed. */
        private static final int SQUEEZE_STATUS_SUCCESSFUL = 2;

        /**
         * Button wasn't successfully squeezed. The squeezing resulted in more than two lines of
         * text or it didn't reduce the button's width at all. The button will have to be
         * re-measured to use only one line of text.
         */
        private static final int SQUEEZE_STATUS_FAILED = 3;

        private boolean show = false;
        private int squeezeStatus = SQUEEZE_STATUS_NONE;
        private SmartButtonType buttonType = SmartButtonType.REPLY;

        private LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        private LayoutParams(int width, int height) {
            super(width, height);
        }

        @VisibleForTesting
        boolean isShown() {
            return show;
        }
    }

    /**
     * Data class for smart replies.
     */
    public static class SmartReplies {
        @NonNull
        public final RemoteInput remoteInput;
        @NonNull
        public final PendingIntent pendingIntent;
        @NonNull
        public final CharSequence[] choices;
        public final boolean fromAssistant;

        public SmartReplies(CharSequence[] choices, RemoteInput remoteInput,
                PendingIntent pendingIntent, boolean fromAssistant) {
            this.choices = choices;
            this.remoteInput = remoteInput;
            this.pendingIntent = pendingIntent;
            this.fromAssistant = fromAssistant;
        }
    }


    /**
     * Data class for smart actions.
     */
    public static class SmartActions {
        @NonNull
        public final List<Notification.Action> actions;
        public final boolean fromAssistant;

        public SmartActions(List<Notification.Action> actions, boolean fromAssistant) {
            this.actions = actions;
            this.fromAssistant = fromAssistant;
        }
    }
}
