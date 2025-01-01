# Edge-to-edge support in PCAPdroid

This document gives a brief introduction to edge-to-edge and contains the guidelines on how to implement proper edge-to-edge support for new views and activities.

See https://medium.com/androiddevelopers/insets-handling-tips-for-android-15s-edge-to-edge-enforcement-872774e8839b for a detailed description.

- Android 35 forces apps to be edge-to-edge
- It's temporary possible to opt out by applying a custom style:

```
<!-- In values-v35.xml -->
<resources>
    <!-- TODO: Remove once activities handle insets. -->
    <style name="OptOutEdgeToEdgeEnforcement">
        <item name="android:windowOptOutEdgeToEdgeEnforcement">true</item>
    </style>
</resources>
```

- Implementing edge-to-edge support requires a lot of efforts, in particular to handle all the possible cases for insets
- Moreover, there can be issues which only appear on older Android versions (e.g. Android 9, see adbc33afe55f2c1795d250707fc3f29aadacdbb0)
- When edge-to-edge support is enabled, it breaks the ability to select a custom theme, see 5f50ae30c1e8b4f2ce73cb684066d12fb0d65b0a

# How it works

- inset types:
  - WindowInsetsCompat.Type.statusBars: the status bar, which is the one with icons and wall clock
  - WindowInsetsCompat.Type.systemBars: any system bar, e.g. 3-buttons navigation bar
  - WindowInsetsCompat.Type.displayCutout: cutouts in the displays, e.g. camera holes
  - WindowInsetsCompat.Type.ime: virtual keyboard. Handling of this inset is not usually required, but it allows to get a better behavior on IME open
    - e.g. to resize the ReciclerView to make all the items visible on screen

- insets handling works hierarchically:
  - the top views first get a chance to handle the insets
  - if the top views don't consume the insets, they get propagated to the children
  - it's unclear how it affects siblings, but it does, see issues on Android 9 adbc33afe55f2c1795d250707fc3f29aadacdbb0

- insets can either be handled via:
  - ViewCompat.setOnApplyWindowInsetsListener
    - you can choose to consume or not the insets
    - makes it possible to implement edge-to-edge
  - fitsSystemWindows="true"
    - it always *consumes* all the insets
    - it just adds margins, without proper edge-to-edge support. Normally it should not be used

- ViewPager2 does not handle insets dispatching properly
  - https://issuetracker.google.com/issues/145617093#comment10
  - to fix it, it's necessary to dispatch the insets manually on tab change: ViewCompat.dispatchApplyWindowInsets
    - see Utils.fixViewPager2Insets

# Guidelines

- To enable proper edge-to-edge support, in particular on older platforms, when creating an Activity be sure to either:
  - just inherit from BaseActivity, which internally calls Utils.enableEdgeToEdge and handles the insets for the toolbar (preferred approach)
  - call Utils.enableEdgeToEdge manually in onCreate, before super.onCreate

- The activity layout must use AppBarLayout and Toolbar (with id `toolbar`) in a CoordinatorLayout. See how it is done for other activities (e.g. about_activity.xml)
  - this makes proper space for the toolbar
  - when possible, reuse `fragment_activity.xml`, for simple activities made of just 1 fragment
  - for tab-based activities, either use `tab_activity.xml` or `tab_activity_fixed.xml`. In the latter case, be sure to call `Utils.fixScrollableTabLayoutInsets`
  - the toolbar needs some top insets handling; if you inherit from BaseActivity, this is already implemented

- When using ViewPager2, be sure to call Utils.fixViewPager2Insets to properly dispatch insets to sub-views
- For ReciclerViews, use EmptyRecyclerView when possible, which already handles insets, IME (virtual keyboard), uses setClipToPadding(false) for edge-to-edge support
- For ListViews, use Utils.fixListviewInsetsBottom for edge-to-edge support

- New activity testing:
  - test with 3-buttons bottom navbar. This can be enabled from the Android settings
  - to properly test scrolling behavior, ensure to populate the views
  - test horizontal insets with rotated device
  - test with IME open/closed in views that use it
  - test on the newest Android version
  - test on Android 9 or lower, as some bugs are only visible there (e.g. adbc33afe55f2c1795d250707fc3f29aadacdbb0)
