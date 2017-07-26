/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.widget.PopupMenu;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.mozilla.focus.R;
import org.mozilla.focus.activity.InfoActivity;
import org.mozilla.focus.autocomplete.UrlAutoCompleteFilter;
import org.mozilla.focus.locale.LocaleAwareAppCompatActivity;
import org.mozilla.focus.telemetry.TelemetryWrapper;
import org.mozilla.focus.utils.ThreadUtils;
import org.mozilla.focus.utils.UrlUtils;
import org.mozilla.focus.utils.ViewUtils;
import org.mozilla.focus.widget.HintFrameLayout;
import org.mozilla.focus.widget.InlineAutocompleteEditText;

/**
 * Fragment for displaying he URL input controls.
 */
public class UrlInputFragment extends Fragment implements View.OnClickListener, InlineAutocompleteEditText.OnCommitListener, InlineAutocompleteEditText.OnFilterListener, PopupMenu.OnMenuItemClickListener {
    public static final String FRAGMENT_TAG = "url_input";

    private static final String ARGUMENT_URL = "url";
    private static final String ARGUMENT_ANIMATION = "animation";
    private static final String ARGUMENT_X = "x";
    private static final String ARGUMENT_Y = "y";
    private static final String ARGUMENT_WIDTH = "width";
    private static final String ARGUMENT_HEIGHT = "height";
    private static final String ARGUMENT_TRANSLUCENT = "translucent";

    private static final String ANIMATION_BROWSER_SCREEN = "browser_screen";

    private static final int ANIMATION_DURATION = 200;

    public static UrlInputFragment createWithBackground() {
        final Bundle arguments = new Bundle();
        arguments.putBoolean(ARGUMENT_TRANSLUCENT, false);

        final UrlInputFragment fragment = new UrlInputFragment();
        fragment.setArguments(arguments);

        return fragment;
    }

    /**
     * Create a new UrlInputFragment and animate the url input view from the position/size of the
     * browser toolbar's URL view.
     */
    public static UrlInputFragment createWithBrowserScreenAnimation(String url, View urlView) {
        final Bundle arguments = new Bundle();
        arguments.putString(ARGUMENT_ANIMATION, ANIMATION_BROWSER_SCREEN);
        arguments.putString(ARGUMENT_URL, url);
        arguments.putBoolean(ARGUMENT_TRANSLUCENT, true);

        int[] screenLocation = new int[2];
        urlView.getLocationOnScreen(screenLocation);

        arguments.putInt(ARGUMENT_X, screenLocation[0]);
        arguments.putInt(ARGUMENT_Y, screenLocation[1]);
        arguments.putInt(ARGUMENT_WIDTH, urlView.getWidth());
        arguments.putInt(ARGUMENT_HEIGHT, urlView.getHeight());

        final UrlInputFragment fragment = new UrlInputFragment();
        fragment.setArguments(arguments);

        return fragment;
    }

    private InlineAutocompleteEditText urlView;
    private View clearView;
    private View searchViewContainer;
    private TextView searchView;

    private UrlAutoCompleteFilter urlAutoCompleteFilter;
    private View dismissView;
    private HintFrameLayout urlInputContainerView;
    private View urlInputBackgroundView;
    private View toolbarBackgroundView;
    private View menuView;

    private @Nullable PopupMenu displayedPopupMenu;

    private volatile boolean isAnimating;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_urlinput, container, false);

        dismissView = view.findViewById(R.id.dismiss);
        dismissView.setOnClickListener(this);

        clearView = view.findViewById(R.id.clear);
        clearView.setOnClickListener(this);

        searchViewContainer = view.findViewById(R.id.search_hint_container);

        searchView =  (TextView) view.findViewById(R.id.search_hint);
        searchView.setOnClickListener(this);

        urlAutoCompleteFilter = new UrlAutoCompleteFilter();
        urlAutoCompleteFilter.loadDomainsInBackground(getContext().getApplicationContext());

        urlView = (InlineAutocompleteEditText) view.findViewById(R.id.url_edit);
        urlView.setOnFilterListener(this);
        urlView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                Log.w("SKDBG", "onFocusChange(): " + hasFocus + " isAnimating: " + isAnimating);

                // Avoid showing keyboard again when returning to the previous page by back key.
                if (hasFocus && !isAnimating) {
                    ViewUtils.showKeyboard(urlView);
                    Log.e("SKDBG", "SHOW KEYBOARD!!");
                }
            }
        });

        toolbarBackgroundView = view.findViewById(R.id.toolbar_background);
        urlInputBackgroundView = view.findViewById(R.id.url_input_background);

        menuView = view.findViewById(R.id.menu);

        urlInputContainerView = (HintFrameLayout) view.findViewById(R.id.url_input_container);
        urlInputContainerView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                urlInputContainerView.getViewTreeObserver().removeOnPreDrawListener(this);

                animateFirstDraw();

                return true;
            }
        });

        if (isOverlay()) {
            view.findViewById(R.id.brand_background).setVisibility(View.GONE);
        } else {
            dismissView.setVisibility(View.GONE);
            toolbarBackgroundView.setVisibility(View.GONE);

            menuView.setVisibility(View.VISIBLE);
            menuView.setOnClickListener(this);
        }

        urlView.setOnCommitListener(this);

        if (getArguments().containsKey(ARGUMENT_URL)) {
            urlView.setText(getArguments().getString(ARGUMENT_URL));
            clearView.setVisibility(View.VISIBLE);
        }

        return view;
    }

    private boolean isOverlay() {
        return getArguments().getBoolean(ARGUMENT_TRANSLUCENT, false);
    }

    public boolean onBackPressed() {
        if (isOverlay()) {
            animateAndDismiss();
            return true;
        }

        return false;
    }

    @Override
    public void onStart() {
        super.onStart();

        urlView.requestFocus();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.clear:
                urlView.setText("");
                urlView.requestFocus();
                break;

            case R.id.search_hint:
                onSearch();
                break;

            case R.id.dismiss:
                animateAndDismiss();
                break;

            case R.id.menu:
                final PopupMenu popupMenu = new PopupMenu(view.getContext(), view);
                popupMenu.getMenuInflater().inflate(R.menu.menu_home, popupMenu.getMenu());
                popupMenu.setOnMenuItemClickListener(this);
                popupMenu.setGravity(Gravity.TOP);
                popupMenu.show();
                displayedPopupMenu = popupMenu;
                break;

            default:
                throw new IllegalStateException("Unhandled view in onClick()");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();

        // On detach, the PopupMenu is no longer relevant to other content (e.g. BrowserFragment) so dismiss it.
        // Note: if we don't dismiss the PopupMenu, its onMenuItemClick method references the old Fragment, which now
        // has a null Context and will cause crashes.
        if (displayedPopupMenu != null) {
            displayedPopupMenu.dismiss();
        }
    }

    private void animateFirstDraw() {
        final String animation = getArguments().getString(ARGUMENT_ANIMATION);

        if (ANIMATION_BROWSER_SCREEN.equals(animation)) {
            playBrowserScreenAnimation(false);
        }
    }

    private void animateAndDismiss() {
        ThreadUtils.assertOnUiThread();

        if (isAnimating) {
            // We are already animating some state change. Ignore all other requests.
            return;
        }

        // Don't allow any more clicks: dismissView is still visible until the animation ends,
        // but we don't want to restart animations and/or trigger hiding again (which could potentially
        // cause crashes since we don't know what state we're in). Ignoring further clicks is the simplest
        // solution, since dismissView is about to disappear anyway.
        dismissView.setClickable(false);

        final String animation = getArguments().getString(ARGUMENT_ANIMATION);

        if (ANIMATION_BROWSER_SCREEN.equals(animation)) {
            playBrowserScreenAnimation(true);
        } else {
            dismiss();
        }
    }

    private void playBrowserScreenAnimation(final boolean reverse) {
        if (isAnimating) {
            // We are already animating, let's ignore another request.
            return;
        }

        isAnimating = true;

        {
            float containerMargin = ((FrameLayout.LayoutParams) urlInputContainerView.getLayoutParams()).bottomMargin;

            float width = urlInputBackgroundView.getWidth();
            float height = urlInputBackgroundView.getHeight();

            float widthScale = (width + (2 * containerMargin)) / width;
            float heightScale = (height + (2 * containerMargin)) / height;

            if (!reverse) {
                urlInputBackgroundView.setPivotX(0);
                urlInputBackgroundView.setPivotY(0);
                urlInputBackgroundView.setScaleX(widthScale);
                urlInputBackgroundView.setScaleY(heightScale);
                urlInputBackgroundView.setTranslationX(-containerMargin);
                urlInputBackgroundView.setTranslationY(-containerMargin);
                urlInputContainerView.setAnimationOffset(0f);

                clearView.setAlpha(0);
            }

            // Let the URL input use the full width/height and then shrink to the actual size
            urlInputBackgroundView.animate()
                    .setDuration(ANIMATION_DURATION)
                    .scaleX(reverse ? widthScale : 1)
                    .scaleY(reverse ? heightScale : 1)
                    .alpha(reverse ? 0 : 1)
                    .translationX(reverse ? -containerMargin : 0)
                    .translationY(reverse ? -containerMargin : 0)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            if (reverse) {
                                clearView.setAlpha(0);
                            }
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (reverse) {
                                dismiss();
                            } else {
                                clearView.setAlpha(1);
                            }

                            isAnimating = false;
                        }
                    });
        }
        {
            int[] screenLocation = new int[2];
            urlView.getLocationOnScreen(screenLocation);

            int leftDelta = getArguments().getInt(ARGUMENT_X) - screenLocation[0] - urlView.getPaddingLeft();

            if (!reverse) {
                urlView.setPivotX(0);
                urlView.setPivotY(0);
                urlView.setTranslationX(leftDelta);
            }

            // The URL moves from the right (at least if the lock is visible) to it's actual position
            urlView.animate()
                    .setDuration(ANIMATION_DURATION)
                    .translationX(reverse ? leftDelta : 0);
        }

        if (!reverse) {
            toolbarBackgroundView.setAlpha(0);
            clearView.setAlpha(0);
        }

        // The darker background appears with an alpha animation
        toolbarBackgroundView.animate()
                .setDuration(ANIMATION_DURATION)
                .alpha(reverse ? 0 : 1);
    }

    private void dismiss() {
        // This method is called from animation callbacks. In the short time frame between the animation
        // starting and ending the activity can be paused. In this case this code can throw an
        // IllegalStateException because we already saved the state (of the activity / fragment) before
        // this transaction is committed. To avoid this we commit while allowing a state loss here.
        // We do not save any state in this fragment (It's getting destroyed) so this should not be a problem.
        getActivity().getSupportFragmentManager()
                .beginTransaction()
                .remove(this)
                .commitAllowingStateLoss();
    }

    @Override
    public void onCommit() {
        final String input = urlView.getText().toString();
        if (!input.trim().isEmpty()) {
            ViewUtils.hideKeyboard(urlView);

            final boolean isUrl = UrlUtils.isUrl(input);

            final String url = isUrl
                    ? UrlUtils.normalize(input)
                    : UrlUtils.createSearchUrl(getContext(), input);

            openUrl(url);

            TelemetryWrapper.urlBarEvent(isUrl);
        }
    }

    private void onSearch() {
        final String searchUrl = UrlUtils.createSearchUrl(getContext(), urlView.getOriginalText());

        openUrl(searchUrl);

        TelemetryWrapper.searchSelectEvent();
    }

    private void openUrl(String url) {
        final FragmentManager fragmentManager = getActivity().getSupportFragmentManager();

        // Replace all fragments with a fresh browser fragment. This means we either remove the
        // HomeFragment with an UrlInputFragment on top or an old BrowserFragment with an
        // UrlInputFragment.
        final BrowserFragment browserFragment = (BrowserFragment) fragmentManager
                .findFragmentByTag(BrowserFragment.FRAGMENT_TAG);

        if (browserFragment != null && browserFragment.isVisible()) {
            // Reuse existing visible fragment - in this case we know the user is already browsing.
            // The fragment might exist if we "erased" a browsing session, hence we need to check
            // for visibility in addition to existence.
            browserFragment.loadUrl(url);

            // And this fragment can be removed again.
            fragmentManager.beginTransaction()
                    .remove(this)
                    .commit();
        } else {
            fragmentManager
                    .beginTransaction()
                    .replace(R.id.container, BrowserFragment.create(url), BrowserFragment.FRAGMENT_TAG)
                    .commit();
        }
    }

    @Override
    public void onFilter(String searchText, InlineAutocompleteEditText view) {
        // If the UrlInputFragment has already been hidden, don't bother with filtering. Because of the text
        // input architecture on Android it's possible for onFilter() to be called after we've already
        // hidden the Fragment, see the relevant bug for more background:
        // https://github.com/mozilla-mobile/focus-android/issues/441#issuecomment-293691141
        if (!isVisible()) {
            return;
        }

        urlAutoCompleteFilter.onFilter(searchText, view);

        if (searchText.trim().isEmpty()) {
            clearView.setVisibility(View.GONE);
            searchViewContainer.setVisibility(View.GONE);

            if (!isOverlay()) {
                menuView.setVisibility(View.VISIBLE);
            }
        } else {
            clearView.setVisibility(View.VISIBLE);
            menuView.setVisibility(View.GONE);

            final String hint = getString(R.string.search_hint, searchText);

            final SpannableString content = new SpannableString(hint);
            content.setSpan(new StyleSpan(Typeface.BOLD), hint.length() - searchText.length(), hint.length(), 0);

            searchView.setText(content);
            searchViewContainer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        final int id = item.getItemId();

        switch (id) {
            case R.id.settings:
                ((LocaleAwareAppCompatActivity) getActivity()).openPreferences();
                return true;

            case R.id.about:
                Intent aboutIntent = InfoActivity.getAboutIntent(getActivity());
                startActivity(aboutIntent);
                return true;

            case R.id.rights:
                Intent rightsIntent = InfoActivity.getRightsIntent(getActivity());
                startActivity(rightsIntent);
                return true;

            case R.id.help:
                Intent helpIntent = InfoActivity.getHelpIntent(getActivity());
                startActivity(helpIntent);
                return true;

            default:
                throw new IllegalStateException("Unhandled view ID in onMenuItemClick()");
        }
    }
}
