package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.InputType;
import android.util.TypedValue;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLMethod;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.Business.QuickRepliesController;
import org.telegram.ui.Cells.ContextLinkCell;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.LoadingCell;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Cells.SharedDocumentCell;
import org.telegram.ui.Cells.SharedLinkCell;
import org.telegram.ui.Cells.SharedMediaSectionCell;
import org.telegram.ui.Cells.SharedPhotoVideoCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EmbedBottomSheet;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SearchViewPager;
import org.telegram.ui.Components.StickerEmptyView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

@SuppressLint("ViewConstructor")
public class FilteredSearchView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {
    private static final int ANIMATOR_ID_FLOATING_DATE_VISIBLE = 0;
    private final BoolAnimator animatorFloatingDataVisible = new BoolAnimator(ANIMATOR_ID_FLOATING_DATE_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);

    public final @NonNull RecyclerListView recyclerListView;
    public IBlur3Capture iBlur3Capture;

    StickerEmptyView emptyView;
    RecyclerListView.Adapter adapter;

    Runnable searchRunnable;

    public ArrayList<MessageObject> messages = new ArrayList<>();
    private final ArrayList<MessageObject> rawMessages = new ArrayList<>();
    public SparseArray<MessageObject> messagesById = new SparseArray<>();
    public ArrayList<String> sections = new ArrayList<>();
    public HashMap<String, ArrayList<MessageObject>> sectionArrays = new HashMap<>();

    private int columnsCount = 3;
    private int nextSearchRate;
    String lastMessagesSearchString;
    String lastSearchFilterQueryString;

    FiltersView.MediaFilterData currentSearchFilter;
    long currentSearchDialogId;
    long currentSearchCommunityId;
    long currentSearchMaxDate;
    long currentSearchMinDate;
    String currentSearchString;
    boolean currentIncludeFolder;

    Activity parentActivity;
    BaseFragment parentFragment;
    private boolean isLoading;
    private boolean endReached;
    private int totalCount;
    private int requestIndex;
    private static final int GLOBAL_MEDIA_PAGE_SIZE = 100;
    private static final long GLOBAL_MEDIA_REQUEST_INTERVAL_MS = 1000;
    private static final long GLOBAL_MEDIA_CACHE_FRESHNESS_MS = 5 * 60 * 1000L;
    private static final String GLOBAL_MEDIA_CACHE_PREFERENCES = "global_media_search_cache";
    private ArrayList<GlobalMediaDialogSearch> globalMediaDialogSearches;
    private ArrayList<GlobalMediaDialogSearch> globalMediaDialogBatch;
    private final HashSet<Long> globalMediaDialogIds = new HashSet<>();
    private final ArrayList<Integer> globalMediaRequestIds = new ArrayList<>();
    private final HashSet<MessageHashId> globalMediaMessageIds = new HashSet<>();
    private ArrayList<GlobalMediaDialogSearch> globalMediaCacheBatch;
    private GlobalMediaDialogSearch globalMediaCacheLoadingDialog;
    private int globalMediaCacheBatchCursor;
    private int globalMediaCacheRequestToken;
    private int globalMediaCacheObserverAccount = -1;
    private boolean globalMediaCacheObserverRegistered;
    private boolean globalMediaCacheRequestInFlight;
    private Runnable globalMediaDispatchRunnable;
    private int globalMediaSearchGeneration = -1;
    private int globalMediaSearchAccount;
    private int globalMediaSearchFolder;
    private int globalMediaBatchCursor;
    private int globalMediaBatchCompleted;
    private int globalMediaRequestsInFlight;
    private long globalMediaLastRequestTime;
    private boolean globalMediaWaitingForDialogs;
    private boolean hidePhotoOnlyMedia;
    private boolean hideShortVideos;
    private boolean hideManagedChats;
    private int shortVideoThresholdSeconds = 60;

    private static final String MEDIA_FILTER_PREFERENCES = "media_group_filter";
    private static final String PREF_HIDE_PHOTOS = "hide_photo_only";
    private static final String PREF_HIDE_SHORT_VIDEOS = "hide_short_videos";
    private static final String PREF_HIDE_MANAGED_CHATS = "hide_managed_chats";
    private static final String PREF_SHORT_VIDEO_SECONDS = "short_video_seconds";

    private String currentDataQuery;

    private static SpannableStringBuilder[] arrowSpan = new SpannableStringBuilder[3];

    private int photoViewerClassGuid;

    private final MessageHashId messageHashIdTmp = new MessageHashId(0, 0);
    private OnlyUserFiltersAdapter dialogsAdapter;
    private SharedPhotoVideoAdapter sharedPhotoVideoAdapter;
    private SharedDocumentsAdapter sharedDocumentsAdapter;
    private SharedLinksAdapter sharedLinksAdapter;
    private SharedDocumentsAdapter sharedAudioAdapter;
    private SharedDocumentsAdapter sharedVoiceAdapter;

    private int searchIndex;

    ArrayList<Object> localTipChats = new ArrayList<>();
    ArrayList<FiltersView.DateData> localTipDates = new ArrayList<>();
    boolean localTipArchive;

    Runnable clearCurrentResultsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isLoading) {
                messages.clear();
                rawMessages.clear();
                globalMediaMessageIds.clear();
                sections.clear();
                sectionArrays.clear();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        }
    };

    private PhotoViewer.PhotoViewerProvider provider = new PhotoViewer.EmptyPhotoViewerProvider() {

        @Override
        public int getTotalImageCount() {
            if (isCustomMediaFilterActive()) {
                return messages.size() + (endReached ? 0 : 1);
            }
            return totalCount;
        }

        @Override
        public boolean loadMore() {
            if (!endReached) {
                search(currentSearchDialogId, currentSearchCommunityId, currentSearchMinDate, currentSearchMaxDate, currentSearchFilter, currentIncludeFolder, lastMessagesSearchString, false);
            }
            return true;
        }

        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            if (messageObject == null) {
                return null;
            }
            final RecyclerListView listView = recyclerListView;
            for (int a = 0, count = listView.getChildCount(); a < count; a++) {
                View view = listView.getChildAt(a);
                int[] coords = new int[2];
                ImageReceiver imageReceiver = null;
                if (view instanceof SharedPhotoVideoCell) {
                    SharedPhotoVideoCell cell = (SharedPhotoVideoCell) view;
                    for (int i = 0; i < 6; i++) {
                        MessageObject message = cell.getMessageObject(i);
                        if (message == null) {
                            break;
                        }
                        if (message.getId() == messageObject.getId()) {
                            BackupImageView imageView = cell.getImageView(i);
                            imageReceiver = imageView.getImageReceiver();
                            imageView.getLocationInWindow(coords);
                        }
                    }
                } else if (view instanceof SharedDocumentCell) {
                    SharedDocumentCell cell = (SharedDocumentCell) view;
                    MessageObject message = cell.getMessage();
                    if (message.getId() == messageObject.getId()) {
                        BackupImageView imageView = cell.getImageView();
                        imageReceiver = imageView.getImageReceiver();
                        imageView.getLocationInWindow(coords);
                    }
                } else if (view instanceof ContextLinkCell) {
                    ContextLinkCell cell = (ContextLinkCell) view;
                    MessageObject message = (MessageObject) cell.getParentObject();
                    if (message != null && message.getId() == messageObject.getId()) {
                        imageReceiver = cell.getPhotoImage();
                        cell.getLocationInWindow(coords);
                    }
                }
                if (imageReceiver != null) {
                    PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
                    object.viewX = coords[0];
                    object.viewY = coords[1] - (Build.VERSION.SDK_INT >= 21 ? 0 : AndroidUtilities.statusBarHeight);
                    object.parentView = listView;
                    listView.getLocationInWindow(coords);
                    object.animatingImageViewYOffset = -coords[1];
                    object.imageReceiver = imageReceiver;
                    object.allowTakeAnimation = false;
                    object.radius = object.imageReceiver.getRoundRadius(true);
                    object.thumb = object.imageReceiver.getBitmapSafe();
                    object.parentView.getLocationInWindow(coords);
                    object.clipTopAddition = 0;

                    if (PhotoViewer.isShowingImage(messageObject)) {
                        final View pinnedHeader = listView.getPinnedHeader();
                        if (pinnedHeader != null) {
                            int top = 0;
                            if (view instanceof SharedDocumentCell) {
                                top += AndroidUtilities.dp(8f);
                            }
                            final int topOffset = (int) (top - object.viewY);
                            if (topOffset > view.getHeight()) {
                                listView.scrollBy(0, -(topOffset + pinnedHeader.getHeight()));
                            } else {
                                int bottomOffset = (int) (object.viewY - listView.getHeight());
                                if (view instanceof SharedDocumentCell) {
                                    bottomOffset -= AndroidUtilities.dp(8f);
                                }
                                if (bottomOffset >= 0) {
                                    listView.scrollBy(0, bottomOffset + view.getHeight());
                                }
                            }
                        }
                    }

                    return object;
                }
            }
            return null;
        }

        @Override
        public CharSequence getTitleFor(int i) {
            return createFromInfoString(messages.get(i), 0);
        }

        @Override
        public CharSequence getSubtitleFor(int i) {
            return LocaleController.formatDateAudio(messages.get(i).messageOwner.date, false);
        }
    };

    private Delegate delegate;
    private SearchViewPager.ChatPreviewDelegate chatPreviewDelegate;
    public final LinearLayoutManager layoutManager;
    private final FlickerLoadingView loadingView;
    private boolean firstLoading = true;
    private AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker();
    public int keyboardHeight;
    private final FloatingDateView floatingDateView;

    private final Runnable hideFloatingDateRunnable = () -> hideFloatingDateView();

    private UiCallback uiCallback;

    public FilteredSearchView(@NonNull BaseFragment fragment) {
        super(fragment.getParentActivity());
        parentFragment = fragment;
        Context context = parentActivity = fragment.getParentActivity();
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(MEDIA_FILTER_PREFERENCES, Context.MODE_PRIVATE);
        hidePhotoOnlyMedia = preferences.getBoolean(PREF_HIDE_PHOTOS, false);
        hideShortVideos = preferences.getBoolean(PREF_HIDE_SHORT_VIDEOS, false);
        hideManagedChats = preferences.getBoolean(PREF_HIDE_MANAGED_CHATS, false);
        shortVideoThresholdSeconds = Math.max(0, preferences.getInt(PREF_SHORT_VIDEO_SECONDS, 60));
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        recyclerListView = new RecyclerListView(context) {

            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (getAdapter() == sharedPhotoVideoAdapter) {
                    for (int i = 0; i < getChildCount(); i++) {
                        if (getChildViewHolder(getChildAt(i)).getItemViewType() == 1) {
                            canvas.save();
                            canvas.translate(getChildAt(i).getX(), getChildAt(i).getY() - getChildAt(i).getMeasuredHeight() + AndroidUtilities.dp(2));
                            getChildAt(i).draw(canvas);
                            canvas.restore();
                            invalidate();
                        }
                    }
                }
                super.dispatchDraw(canvas);
            }

            @Override
            public boolean drawChild(Canvas canvas, View child, long drawingTime) {
                if (getAdapter() == sharedPhotoVideoAdapter) {
                    if (getChildViewHolder(child).getItemViewType() == 1) {
                        return true;
                    }
                }
                return super.drawChild(canvas, child, drawingTime);
            }
        };
        recyclerListView.setOnItemClickListener((view, position) -> {
            if (view instanceof SharedDocumentCell) {
                FilteredSearchView.this.onItemClick(position, view, ((SharedDocumentCell) view).getMessage(), 0);
            } else if (view instanceof SharedLinkCell) {
                FilteredSearchView.this.onItemClick(position, view, ((SharedLinkCell) view).getMessage(), 0);
            } else if (view instanceof SharedAudioCell) {
                FilteredSearchView.this.onItemClick(position, view, ((SharedAudioCell) view).getMessage(), 0);
            } else if (view instanceof ContextLinkCell) {
                FilteredSearchView.this.onItemClick(position, view, ((ContextLinkCell) view).getMessageObject(), 0);
            } else if (view instanceof DialogCell) {
                FilteredSearchView.this.onItemClick(position, view, ((DialogCell) view).getMessage(), 0);
            }
        });
        recyclerListView.setOnItemLongClickListener(new RecyclerListView.OnItemLongClickListenerExtended() {
            @Override
            public boolean onItemClick(View view, int position, float x, float y) {
                if (view instanceof SharedDocumentCell) {
                    FilteredSearchView.this.onItemLongClick(((SharedDocumentCell) view).getMessage(), view, 0);
                } else if (view instanceof SharedLinkCell) {
                    FilteredSearchView.this.onItemLongClick(((SharedLinkCell) view).getMessage(), view, 0);
                } else if (view instanceof SharedAudioCell) {
                    FilteredSearchView.this.onItemLongClick(((SharedAudioCell) view).getMessage(), view, 0);
                } else if (view instanceof ContextLinkCell) {
                    FilteredSearchView.this.onItemLongClick(((ContextLinkCell) view).getMessageObject(), view, 0);
                } else if (view instanceof DialogCell) {
                    if (!uiCallback.actionModeShowing()) {
                        if (((DialogCell) view).isPointInsideAvatar(x, y)) {
                            chatPreviewDelegate.startChatPreview(recyclerListView, (DialogCell) view);
                            return true;
                        }
                    }
                    FilteredSearchView.this.onItemLongClick(((DialogCell) view).getMessage(), view, 0);
                }
                return true;
            }

            @Override
            public void onMove(float dx, float dy) {
                chatPreviewDelegate.move(dy);
            }

            @Override
            public void onLongClickRelease() {
                chatPreviewDelegate.finish();
            }
        });

        layoutManager = new LinearLayoutManager(context);
        recyclerListView.setLayoutManager(layoutManager);
        addView(loadingView = new FlickerLoadingView(context) {
            @Override
            public int getColumnsCount() {
                return columnsCount;
            }
        });
        addView(recyclerListView);

        recyclerListView.setSectionsType(RecyclerListView.SECTIONS_TYPE_DATE);
        recyclerListView.setSkipDrawSection(true);
        recyclerListView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(parentActivity.getCurrentFocus());
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (recyclerView.getAdapter() == null || adapter == null) {
                    return;
                }
                int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                int visibleItemCount = Math.abs(lastVisibleItem - firstVisibleItem) + 1;
                int totalItemCount = recyclerView.getAdapter().getItemCount();
                if (!isLoading && visibleItemCount > 0 && lastVisibleItem >= totalItemCount - 10 && !endReached) {
                    AndroidUtilities.runOnUIThread(() -> {
                        search(currentSearchDialogId, currentSearchCommunityId, currentSearchMinDate, currentSearchMaxDate, currentSearchFilter, currentIncludeFolder, lastMessagesSearchString, false);
                    });
                }

                if (adapter == sharedPhotoVideoAdapter) {
                    if (dy != 0 && !messages.isEmpty() && TextUtils.isEmpty(currentDataQuery)) {
                        showFloatingDateView();
                    }
                    RecyclerListView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(firstVisibleItem);
                    if (holder != null && holder.getItemViewType() == 0) {
                        if (holder.itemView instanceof SharedPhotoVideoCell) {
                            SharedPhotoVideoCell cell = (SharedPhotoVideoCell) holder.itemView;
                            MessageObject messageObject = cell.getMessageObject(0);
                            if (messageObject != null) {
                                floatingDateView.setCustomDate(messageObject.messageOwner.date);
                            }
                        }
                    }
                } else {
                    boolean hideFloatingView = true;
                    View pinnedHeader = recyclerListView.getPinnedHeader();
                    if (pinnedHeader instanceof GraySectionCell) {
                        GraySectionCell cell = (GraySectionCell) pinnedHeader;
                        CharSequence text = cell.getText();
                        if (!TextUtils.isEmpty(text) && cell.getAlpha() > 0) {
                            hideFloatingView = false;
                            floatingDateView.setCustomText(text.toString());
                            if (dy != 0) {
                                showFloatingDateView();
                            }
                        }
                    }
                    if (hideFloatingView) {
                        hideFloatingDateView();
                    }
                }
            }
        });

        floatingDateView = new FloatingDateView(context);
        floatingDateView.setCustomDate((int) (System.currentTimeMillis() / 1000));
        // floatingDateView.setOverrideColor(Theme.key_chat_mediaTimeBackground, Theme.key_chat_mediaTimeText);
        addView(floatingDateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 33, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, -2, 0, 0));

        dialogsAdapter = new OnlyUserFiltersAdapter();
        sharedPhotoVideoAdapter = new SharedPhotoVideoAdapter(getContext());
        sharedDocumentsAdapter = new SharedDocumentsAdapter(getContext(), 1);
        sharedLinksAdapter = new SharedLinksAdapter(getContext());
        sharedAudioAdapter = new SharedDocumentsAdapter(getContext(), 4);
        sharedVoiceAdapter = new SharedDocumentsAdapter(getContext(), 2);

        emptyView = new StickerEmptyView(context, loadingView, StickerEmptyView.STICKER_TYPE_SEARCH);
        addView(emptyView);
        recyclerListView.setEmptyView(emptyView);
        emptyView.setVisibility(View.GONE);
        checkUi_floatingDateView();
    }

    public void showMediaFilterDialog() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextCheckCell photoCell = new TextCheckCell(context, 21, true);
        photoCell.setTextAndCheck(getString(R.string.MediaFilterHidePhotos), hidePhotoOnlyMedia, false);
        photoCell.setBackground(Theme.getSelectorDrawable(false));
        photoCell.setOnClickListener(v -> photoCell.setChecked(!photoCell.isChecked()));
        container.addView(photoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        TextCheckCell durationCell = new TextCheckCell(context, 21, true);
        durationCell.setTextAndCheck(getString(R.string.MediaFilterHideShortVideos), hideShortVideos, false);
        durationCell.setBackground(Theme.getSelectorDrawable(false));
        container.addView(durationCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        LinearLayout durationRow = new LinearLayout(context);
        durationRow.setOrientation(LinearLayout.HORIZONTAL);
        durationRow.setGravity(Gravity.CENTER_VERTICAL);
        durationRow.setPadding(dp(24), 0, dp(24), dp(8));

        EditText minutesInput = createDurationInput(context, shortVideoThresholdSeconds / 60);
        EditText secondsInput = createDurationInput(context, shortVideoThresholdSeconds % 60);
        durationRow.addView(minutesInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        durationRow.addView(createDurationLabel(context, getString(R.string.MediaFilterMinutes)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 16, 0));
        durationRow.addView(secondsInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        durationRow.addView(createDurationLabel(context, getString(R.string.MediaFilterSeconds)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0, 0));
        container.addView(durationRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        Runnable updateDurationEnabled = () -> {
            boolean enabled = durationCell.isChecked();
            minutesInput.setEnabled(enabled);
            secondsInput.setEnabled(enabled);
            durationRow.setAlpha(enabled ? 1f : 0.5f);
        };
        durationCell.setOnClickListener(v -> {
            durationCell.setChecked(!durationCell.isChecked());
            updateDurationEnabled.run();
        });
        updateDurationEnabled.run();

        TextCheckCell managedChatsCell = new TextCheckCell(context, 21, true);
        managedChatsCell.setTextAndCheck(getString(R.string.MediaFilterHideManagedChats), hideManagedChats, false);
        managedChatsCell.setBackground(Theme.getSelectorDrawable(false));
        managedChatsCell.setOnClickListener(v -> managedChatsCell.setChecked(!managedChatsCell.isChecked()));
        container.addView(managedChatsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, parentFragment.getResourceProvider());
        builder.setTitle(getString(R.string.MediaFilterTitle));
        builder.setView(container);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setNeutralButton(getString(R.string.Reset), (dialog, which) -> applyMediaFilter(false, false, false, 60));
        builder.setPositiveButton(getString(R.string.ApplyTheme), (dialog, which) -> {
            int minutes = parseDurationValue(minutesInput, 0, 999);
            int seconds = parseDurationValue(secondsInput, 0, 59);
            applyMediaFilter(photoCell.isChecked(), durationCell.isChecked(), managedChatsCell.isChecked(), minutes * 60 + seconds);
        });
        parentFragment.showDialog(builder.create());
    }

    private EditText createDurationInput(Context context, int value) {
        EditText editText = new EditText(context);
        editText.setText(String.valueOf(value));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, parentFragment.getResourceProvider()));
        editText.setGravity(Gravity.CENTER);
        editText.setSingleLine(true);
        editText.setSelectAllOnFocus(true);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setBackground(Theme.createEditTextDrawable(context, true));
        return editText;
    }

    private TextView createDurationLabel(Context context, CharSequence text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, parentFragment.getResourceProvider()));
        textView.setGravity(Gravity.CENTER_VERTICAL);
        return textView;
    }

    private int parseDurationValue(EditText editText, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(editText.getText().toString())));
        } catch (Exception ignore) {
            return min;
        }
    }

    private void applyMediaFilter(boolean hidePhotos, boolean hideShort, boolean hideManaged, int thresholdSeconds) {
        hidePhotoOnlyMedia = hidePhotos;
        hideShortVideos = hideShort;
        hideManagedChats = hideManaged;
        shortVideoThresholdSeconds = Math.max(0, thresholdSeconds);
        ApplicationLoader.applicationContext.getSharedPreferences(MEDIA_FILTER_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HIDE_PHOTOS, hidePhotoOnlyMedia)
                .putBoolean(PREF_HIDE_SHORT_VIDEOS, hideShortVideos)
                .putBoolean(PREF_HIDE_MANAGED_CHATS, hideManagedChats)
                .putInt(PREF_SHORT_VIDEO_SECONDS, shortVideoThresholdSeconds)
                .apply();
        rebuildVisibleMessages();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        layoutManager.scrollToPositionWithOffset(0, 0);
        loadMoreForActiveFilterIfNeeded();
    }

    private boolean isCustomMediaFilterActive() {
        return currentSearchFilter != null
                && currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MEDIA
                && (hidePhotoOnlyMedia || hideShortVideos || hideManagedChats);
    }

    private void rebuildVisibleMessages() {
        messages.clear();
        messagesById.clear();
        sections.clear();
        sectionArrays.clear();

        if (!isCustomMediaFilterActive()) {
            messages.addAll(rawMessages);
        } else {
            HashMap<String, ArrayList<MessageObject>> groups = new HashMap<>();
            for (MessageObject messageObject : rawMessages) {
                String groupKey = getMediaGroupKey(messageObject);
                if (groupKey != null) {
                    ArrayList<MessageObject> group = groups.get(groupKey);
                    if (group == null) {
                        group = new ArrayList<>();
                        groups.put(groupKey, group);
                    }
                    group.add(messageObject);
                }
            }

            HashSet<String> pendingGroupKeys = new HashSet<>();
            if (globalMediaSearchGeneration != -1 && globalMediaDialogSearches != null) {
                for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                    if ((!dialogSearch.endReached || dialogSearch.failed) && dialogSearch.pendingGroupKey != null) {
                        pendingGroupKeys.add(dialogSearch.pendingGroupKey);
                    }
                    if (!dialogSearch.historyEndReached && dialogSearch.historyPendingGroupKey != null) {
                        pendingGroupKeys.add(dialogSearch.historyPendingGroupKey);
                    }
                    if (!dialogSearch.cacheLoaded && dialogSearch.cachePendingGroupKey != null) {
                        pendingGroupKeys.add(dialogSearch.cachePendingGroupKey);
                    }
                }
            } else if (!endReached && !rawMessages.isEmpty()) {
                String pendingGroupKey = getMediaGroupKey(rawMessages.get(rawMessages.size() - 1));
                if (pendingGroupKey != null) {
                    pendingGroupKeys.add(pendingGroupKey);
                }
            }
            HashMap<String, Boolean> groupVisibility = new HashMap<>();
            for (MessageObject messageObject : rawMessages) {
                String groupKey = getMediaGroupKey(messageObject);
                if (groupKey == null) {
                    if (shouldShowStandaloneMedia(messageObject)) {
                        messages.add(messageObject);
                    }
                    continue;
                }
                if (pendingGroupKeys.contains(groupKey)) {
                    continue;
                }
                Boolean show = groupVisibility.get(groupKey);
                if (show == null) {
                    show = shouldShowMediaGroup(groups.get(groupKey));
                    groupVisibility.put(groupKey, show);
                }
                if (show) {
                    messages.add(messageObject);
                }
            }
        }

        if (currentSearchFilter != null
                && currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MEDIA
                && TextUtils.isEmpty(currentSearchString)) {
            messages.sort((left, right) -> Integer.compare(right.messageOwner.date, left.messageOwner.date));
        }

        for (MessageObject messageObject : messages) {
            ArrayList<MessageObject> messageObjectsByDate = sectionArrays.get(messageObject.monthKey);
            if (messageObjectsByDate == null) {
                messageObjectsByDate = new ArrayList<>();
                sectionArrays.put(messageObject.monthKey, messageObjectsByDate);
                sections.add(messageObject.monthKey);
            }
            messageObjectsByDate.add(messageObject);
            messagesById.put(messageObject.getId(), messageObject);
        }
    }

    private String getMediaGroupKey(MessageObject messageObject) {
        long groupId = messageObject.getGroupId();
        if (groupId == 0) {
            return null;
        }
        return messageObject.getDialogId() + ":" + groupId;
    }

    private boolean shouldShowStandaloneMedia(MessageObject messageObject) {
        if (hideManagedChats && isManagedChat(messageObject)) {
            return false;
        }
        if (hidePhotoOnlyMedia && messageObject.isPhoto()) {
            return false;
        }
        return !hideShortVideos || !isShortVideo(messageObject);
    }

    private boolean shouldShowMediaGroup(ArrayList<MessageObject> group) {
        if (hideManagedChats && !group.isEmpty() && isManagedChat(group.get(0))) {
            return false;
        }
        boolean hasVideo = false;
        boolean allItemsArePhotos = true;
        boolean allVideosAreShort = true;
        for (MessageObject messageObject : group) {
            if (messageObject.isVideo()) {
                hasVideo = true;
                allItemsArePhotos = false;
                if (!isShortVideo(messageObject)) {
                    allVideosAreShort = false;
                }
            } else if (!messageObject.isPhoto()) {
                allItemsArePhotos = false;
            }
        }
        if (hidePhotoOnlyMedia && allItemsArePhotos) {
            return false;
        }
        return !hideShortVideos || !hasVideo || !allVideosAreShort;
    }

    private boolean isManagedChat(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        long dialogId = messageObject.getDialogId();
        if (dialogId >= 0) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(messageObject.currentAccount).getChat(-dialogId);
        return ChatObject.hasAdminRights(chat);
    }

    private boolean isShortVideo(MessageObject messageObject) {
        if (!messageObject.isVideo()) {
            return false;
        }
        double duration = messageObject.getDuration();
        return duration > 0 && duration < shortVideoThresholdSeconds;
    }

    private void loadMoreForActiveFilterIfNeeded() {
        if ((isCustomMediaFilterActive() || globalMediaSearchGeneration != -1)
                && !isLoading && !endReached && messages.size() < columnsCount * 6) {
            AndroidUtilities.runOnUIThread(() -> search(currentSearchDialogId, currentSearchCommunityId, currentSearchMinDate,
                    currentSearchMaxDate, currentSearchFilter, currentIncludeFolder, lastMessagesSearchString, false));
        }
    }

    private static class GlobalMediaDialogSearch {
        final long dialogId;
        final TLRPC.InputPeer peer;
        String cacheStateKey;
        int offsetId;
        int count;
        int failedAttempts;
        int headBoundaryId;
        int catchupOffsetId;
        int catchupBoundaryId;
        int pendingHeadBoundaryId;
        int cacheOffsetId;
        int cacheTargetOffsetId;
        long lastHeadSyncAt;
        boolean historyEndReached;
        boolean catchingUp;
        boolean hasSavedState;
        boolean cacheLoaded;
        boolean endReached;
        boolean failed;
        String pendingGroupKey;
        String historyPendingGroupKey;
        String cachePendingGroupKey;

        GlobalMediaDialogSearch(long dialogId, TLRPC.InputPeer peer) {
            this.dialogId = dialogId;
            this.peer = peer;
        }
    }

    private String getGlobalMediaCacheStateKey(long dialogId) {
        long userId = UserConfig.getInstance(globalMediaSearchAccount).getClientUserId();
        return "a" + globalMediaSearchAccount
                + "_u" + userId
                + "_f" + globalMediaSearchFolder
                + "_min" + currentSearchMinDate
                + "_max" + currentSearchMaxDate
                + "_d" + dialogId + "_";
    }

    private SharedPreferences getGlobalMediaCachePreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(GLOBAL_MEDIA_CACHE_PREFERENCES, Activity.MODE_PRIVATE);
    }

    private void loadGlobalMediaCacheState(GlobalMediaDialogSearch dialogSearch) {
        SharedPreferences preferences = getGlobalMediaCachePreferences();
        String key = dialogSearch.cacheStateKey = getGlobalMediaCacheStateKey(dialogSearch.dialogId);
        dialogSearch.hasSavedState = preferences.getBoolean(key + "initialized", false);
        if (dialogSearch.hasSavedState) {
            dialogSearch.offsetId = preferences.getInt(key + "history_offset", 0);
            dialogSearch.historyEndReached = preferences.getBoolean(key + "history_end", false);
            dialogSearch.headBoundaryId = preferences.getInt(key + "head_boundary", 0);
            dialogSearch.lastHeadSyncAt = preferences.getLong(key + "head_updated", 0);
            dialogSearch.count = preferences.getInt(key + "count", 0);
            dialogSearch.catchingUp = preferences.getBoolean(key + "catching_up", false);
            dialogSearch.catchupOffsetId = preferences.getInt(key + "catchup_offset", 0);
            dialogSearch.catchupBoundaryId = preferences.getInt(key + "catchup_boundary", 0);
            dialogSearch.pendingHeadBoundaryId = preferences.getInt(key + "pending_head_boundary", 0);
            dialogSearch.historyPendingGroupKey = preferences.getString(key + "history_pending_group", null);

            long now = System.currentTimeMillis();
            boolean stale = dialogSearch.lastHeadSyncAt <= 0 || now < dialogSearch.lastHeadSyncAt
                    || now - dialogSearch.lastHeadSyncAt >= GLOBAL_MEDIA_CACHE_FRESHNESS_MS;
            if (!dialogSearch.catchingUp && stale) {
                dialogSearch.catchingUp = true;
                dialogSearch.catchupOffsetId = 0;
                dialogSearch.catchupBoundaryId = dialogSearch.headBoundaryId;
                dialogSearch.pendingHeadBoundaryId = 0;
            }
        } else {
            dialogSearch.catchingUp = true;
            dialogSearch.catchupOffsetId = 0;
            dialogSearch.catchupBoundaryId = 0;
            dialogSearch.pendingHeadBoundaryId = 0;
        }
        dialogSearch.cacheTargetOffsetId = dialogSearch.offsetId;
        dialogSearch.cacheOffsetId = 0;
        dialogSearch.cacheLoaded = false;
        dialogSearch.endReached = !dialogSearch.catchingUp && dialogSearch.historyEndReached;
        if (dialogSearch.catchingUp) {
            saveGlobalMediaCacheState(dialogSearch);
        }
    }

    private void saveGlobalMediaCacheState(GlobalMediaDialogSearch dialogSearch) {
        writeGlobalMediaCacheState(
                dialogSearch.cacheStateKey,
                dialogSearch.offsetId,
                dialogSearch.historyEndReached,
                dialogSearch.headBoundaryId,
                dialogSearch.lastHeadSyncAt,
                dialogSearch.count,
                dialogSearch.catchingUp,
                dialogSearch.catchupOffsetId,
                dialogSearch.catchupBoundaryId,
                dialogSearch.pendingHeadBoundaryId,
                dialogSearch.historyPendingGroupKey
        );
        dialogSearch.hasSavedState = true;
    }

    private void saveGlobalMediaCacheStateAfterMessagesStored(GlobalMediaDialogSearch dialogSearch) {
        final String key = dialogSearch.cacheStateKey;
        final int offsetId = dialogSearch.offsetId;
        final boolean historyEndReached = dialogSearch.historyEndReached;
        final int headBoundaryId = dialogSearch.headBoundaryId;
        final long lastHeadSyncAt = dialogSearch.lastHeadSyncAt;
        final int count = dialogSearch.count;
        final boolean catchingUp = dialogSearch.catchingUp;
        final int catchupOffsetId = dialogSearch.catchupOffsetId;
        final int catchupBoundaryId = dialogSearch.catchupBoundaryId;
        final int pendingHeadBoundaryId = dialogSearch.pendingHeadBoundaryId;
        final String historyPendingGroupKey = dialogSearch.historyPendingGroupKey;
        MessagesStorage.getInstance(globalMediaSearchAccount).getStorageQueue().postRunnable(() -> writeGlobalMediaCacheState(
                key, offsetId, historyEndReached, headBoundaryId, lastHeadSyncAt, count, catchingUp,
                catchupOffsetId, catchupBoundaryId, pendingHeadBoundaryId, historyPendingGroupKey));
        dialogSearch.hasSavedState = true;
    }

    private void writeGlobalMediaCacheState(String key, int offsetId, boolean historyEndReached, int headBoundaryId,
            long lastHeadSyncAt, int count, boolean catchingUp, int catchupOffsetId, int catchupBoundaryId,
            int pendingHeadBoundaryId, String historyPendingGroupKey) {
        SharedPreferences.Editor editor = getGlobalMediaCachePreferences().edit();
        editor.putBoolean(key + "initialized", true)
                .putInt(key + "history_offset", offsetId)
                .putBoolean(key + "history_end", historyEndReached)
                .putInt(key + "head_boundary", headBoundaryId)
                .putLong(key + "head_updated", lastHeadSyncAt)
                .putInt(key + "count", count)
                .putBoolean(key + "catching_up", catchingUp)
                .putInt(key + "catchup_offset", catchupOffsetId)
                .putInt(key + "catchup_boundary", catchupBoundaryId)
                .putInt(key + "pending_head_boundary", pendingHeadBoundaryId)
                .putString(key + "history_pending_group", historyPendingGroupKey)
                .apply();
    }

    private void setGlobalMediaCacheObserverAccount(int account) {
        if (globalMediaCacheObserverRegistered
                && (globalMediaCacheObserverAccount != account || account < 0)) {
            NotificationCenter.getInstance(globalMediaCacheObserverAccount).removeObserver(this, NotificationCenter.mediaDidLoad);
            globalMediaCacheObserverRegistered = false;
        }
        globalMediaCacheObserverAccount = account;
        if (globalMediaCacheObserverAccount >= 0 && isAttachedToWindow() && !globalMediaCacheObserverRegistered) {
            NotificationCenter.getInstance(globalMediaCacheObserverAccount).addObserver(this, NotificationCenter.mediaDidLoad);
            globalMediaCacheObserverRegistered = true;
        }
    }

    private boolean isInCurrentGlobalMediaDateRange(MessageObject messageObject) {
        long date = messageObject.messageOwner.date * 1000L;
        return (currentSearchMinDate <= 0 || date >= currentSearchMinDate)
                && (currentSearchMaxDate <= 0 || date <= currentSearchMaxDate);
    }

    private boolean addGlobalMediaMessage(MessageObject messageObject) {
        if (messageObject == null || !isInCurrentGlobalMediaDateRange(messageObject)) {
            return false;
        }
        MessageHashId key = new MessageHashId(messageObject.getId(), messageObject.getDialogId());
        if (!globalMediaMessageIds.add(key)) {
            return false;
        }
        messageObject.setQuery("");
        rawMessages.add(messageObject);
        return true;
    }

    private void startGlobalMediaCacheLoadRound() {
        if (!isAttachedToWindow() || globalMediaSearchGeneration == -1 || globalMediaDialogSearches == null
                || globalMediaCacheRequestInFlight || globalMediaCacheBatch != null) {
            return;
        }
        globalMediaCacheBatch = new ArrayList<>();
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            if (!dialogSearch.cacheLoaded) {
                globalMediaCacheBatch.add(dialogSearch);
            }
        }
        globalMediaCacheBatchCursor = 0;
        if (globalMediaCacheBatch.isEmpty()) {
            globalMediaCacheBatch = null;
            return;
        }
        dispatchGlobalMediaCacheLoad();
    }

    private void dispatchGlobalMediaCacheLoad() {
        if (!isAttachedToWindow() || globalMediaSearchGeneration == -1 || globalMediaCacheBatch == null
                || globalMediaCacheRequestInFlight || globalMediaCacheBatchCursor >= globalMediaCacheBatch.size()) {
            return;
        }
        globalMediaCacheLoadingDialog = globalMediaCacheBatch.get(globalMediaCacheBatchCursor);
        globalMediaCacheRequestInFlight = true;
        MediaDataController.getInstance(globalMediaSearchAccount).loadMediaFromCacheNoHoles(
                globalMediaCacheLoadingDialog.dialogId,
                GLOBAL_MEDIA_PAGE_SIZE,
                globalMediaCacheLoadingDialog.cacheOffsetId,
                MediaDataController.MEDIA_PHOTOVIDEO,
                currentSearchMinDate > 0 ? (int) (currentSearchMinDate / 1000) : 0,
                currentSearchMaxDate > 0 ? (int) (currentSearchMaxDate / 1000) : 0,
                parentFragment.getClassGuid(),
                globalMediaCacheRequestToken
        );
    }

    private void onGlobalMediaCacheLoaded(Object... args) {
        if (args.length < 8 || !globalMediaCacheRequestInFlight || globalMediaCacheLoadingDialog == null
                || globalMediaDialogSearches == null || globalMediaSearchGeneration == -1
                || (Integer) args[3] != parentFragment.getClassGuid()
                || (Integer) args[4] != MediaDataController.MEDIA_PHOTOVIDEO
                || (Integer) args[7] != globalMediaCacheRequestToken
                || (Long) args[0] != globalMediaCacheLoadingDialog.dialogId) {
            return;
        }

        GlobalMediaDialogSearch dialogSearch = globalMediaCacheLoadingDialog;
        globalMediaCacheRequestInFlight = false;
        globalMediaCacheLoadingDialog = null;
        ArrayList<MessageObject> cachedMessages = (ArrayList<MessageObject>) args[2];
        boolean changed = false;
        int oldestMessageId = 0;
        MessageObject oldestMessageObject = null;
        if (cachedMessages != null) {
            for (MessageObject messageObject : cachedMessages) {
                changed |= addGlobalMediaMessage(messageObject);
                oldestMessageId = messageObject.getId();
                oldestMessageObject = messageObject;
            }
        }

        boolean reachedTarget = dialogSearch.cacheTargetOffsetId <= 0 || oldestMessageId <= dialogSearch.cacheTargetOffsetId;
        boolean topReached = (Boolean) args[5];
        if (cachedMessages == null || cachedMessages.size() < GLOBAL_MEDIA_PAGE_SIZE || oldestMessageId <= 0 || reachedTarget || topReached) {
            dialogSearch.cacheLoaded = true;
            dialogSearch.cachePendingGroupKey = null;
        } else {
            dialogSearch.cacheOffsetId = oldestMessageId;
            dialogSearch.cachePendingGroupKey = oldestMessageObject != null ? getMediaGroupKey(oldestMessageObject) : null;
        }

        if (changed) {
            updateGlobalMediaResults(globalMediaSearchGeneration);
        }
        globalMediaCacheBatchCursor++;
        if (globalMediaCacheBatchCursor >= globalMediaCacheBatch.size()) {
            globalMediaCacheBatch = null;
            startGlobalMediaCacheLoadRound();
        } else {
            dispatchGlobalMediaCacheLoad();
        }
    }

    private void resetGlobalMediaDialogSearch() {
        setGlobalMediaCacheObserverAccount(-1);
        if (globalMediaDispatchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaDispatchRunnable);
            globalMediaDispatchRunnable = null;
        }
        for (int i = 0; i < globalMediaRequestIds.size(); i++) {
            ConnectionsManager.getInstance(globalMediaSearchAccount).cancelRequest(globalMediaRequestIds.get(i), true);
        }
        globalMediaRequestIds.clear();
        globalMediaCacheRequestToken++;
        globalMediaCacheBatch = null;
        globalMediaCacheLoadingDialog = null;
        globalMediaCacheBatchCursor = 0;
        globalMediaCacheRequestInFlight = false;
        globalMediaDialogSearches = null;
        globalMediaDialogBatch = null;
        globalMediaDialogIds.clear();
        globalMediaMessageIds.clear();
        globalMediaSearchGeneration = -1;
        globalMediaBatchCursor = 0;
        globalMediaBatchCompleted = 0;
        globalMediaRequestsInFlight = 0;
        globalMediaWaitingForDialogs = false;
    }

    private void ensureGlobalMediaAdapter() {
        if (adapter != sharedPhotoVideoAdapter) {
            adapter = sharedPhotoVideoAdapter;
            recyclerListView.setAdapter(adapter);
        }
    }

    private void continueGlobalMediaSearch(int generation) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration) {
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(globalMediaSearchAccount);
        if (!messagesController.isServerDialogsEndReached(globalMediaSearchFolder)) {
            globalMediaWaitingForDialogs = true;
            appendGlobalMediaDialogs(messagesController);
            setGlobalMediaCacheObserverAccount(globalMediaSearchAccount);
            startGlobalMediaCacheLoadRound();
            if (!messagesController.isLoadingDialogs(globalMediaSearchFolder)) {
                messagesController.loadDialogs(globalMediaSearchFolder, 0, 100, false);
            }
            return;
        }

        globalMediaWaitingForDialogs = false;
        appendGlobalMediaDialogs(messagesController);
        setGlobalMediaCacheObserverAccount(globalMediaSearchAccount);
        startGlobalMediaCacheLoadRound();
        startGlobalMediaDialogBatch(generation);
    }

    private void appendGlobalMediaDialogs(MessagesController messagesController) {
        if (globalMediaDialogSearches == null) {
            globalMediaDialogSearches = new ArrayList<>();
        }
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(messagesController.getDialogs(globalMediaSearchFolder));
        dialogs.sort((left, right) -> Integer.compare(right.last_message_date, left.last_message_date));
        for (TLRPC.Dialog dialog : dialogs) {
            if (dialog == null || dialog.isFolder || dialog.id == 0 || DialogObject.isEncryptedDialog(dialog.id)) {
                continue;
            }
            if (!globalMediaDialogIds.add(dialog.id)) {
                continue;
            }
            TLRPC.InputPeer peer = messagesController.getInputPeer(dialog.id);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                globalMediaDialogIds.remove(dialog.id);
                continue;
            }
            GlobalMediaDialogSearch dialogSearch = new GlobalMediaDialogSearch(dialog.id, peer);
            loadGlobalMediaCacheState(dialogSearch);
            globalMediaDialogSearches.add(dialogSearch);
        }
        updateGlobalMediaTotalCount();
    }

    private void updateGlobalMediaTotalCount() {
        long count = 0;
        if (globalMediaDialogSearches != null) {
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                count += dialogSearch.count;
            }
        }
        totalCount = (int) Math.min(Integer.MAX_VALUE, count);
    }

    private void startGlobalMediaDialogBatch(int generation) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration || globalMediaDialogSearches == null) {
            return;
        }
        globalMediaDialogBatch = new ArrayList<>();
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            if (!dialogSearch.endReached) {
                globalMediaDialogBatch.add(dialogSearch);
            }
        }
        globalMediaBatchCursor = 0;
        globalMediaBatchCompleted = 0;
        globalMediaRequestsInFlight = 0;
        if (globalMediaDialogBatch.isEmpty()) {
            isLoading = false;
            endReached = true;
            updateGlobalMediaTotalCount();
            updateGlobalMediaResults(generation);
            return;
        }
        // Fetch one page from each remaining dialog before starting the next round.
        isLoading = true;
        endReached = false;
        dispatchGlobalMediaDialogSearches(generation);
    }

    private void dispatchGlobalMediaDialogSearches(int generation) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration || globalMediaDialogBatch == null
                || globalMediaRequestsInFlight != 0 || globalMediaBatchCursor >= globalMediaDialogBatch.size()
                || !isAttachedToWindow()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long delay = globalMediaLastRequestTime == 0 ? 0
                : GLOBAL_MEDIA_REQUEST_INTERVAL_MS - (now - globalMediaLastRequestTime);
        if (delay > 0) {
            if (globalMediaDispatchRunnable == null) {
                globalMediaDispatchRunnable = () -> {
                    globalMediaDispatchRunnable = null;
                    dispatchGlobalMediaDialogSearches(generation);
                };
                AndroidUtilities.runOnUIThread(globalMediaDispatchRunnable, delay);
            }
            return;
        }

        GlobalMediaDialogSearch dialogSearch = globalMediaDialogBatch.get(globalMediaBatchCursor++);
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = dialogSearch.peer;
        request.q = "";
        request.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        request.limit = GLOBAL_MEDIA_PAGE_SIZE;
        request.offset_id = dialogSearch.catchingUp ? dialogSearch.catchupOffsetId : dialogSearch.offsetId;
        if (currentSearchMinDate > 0) {
            request.min_date = (int) (currentSearchMinDate / 1000);
        }
        if (currentSearchMaxDate > 0) {
            request.max_date = (int) (currentSearchMaxDate / 1000);
        }

        globalMediaLastRequestTime = now;
        globalMediaRequestsInFlight = 1;
        final int[] requestIdHolder = new int[]{-1};
        int requestId = ConnectionsManager.getInstance(globalMediaSearchAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (requestIdHolder[0] >= 0) {
                    globalMediaRequestIds.remove((Integer) requestIdHolder[0]);
                }
                if (generation != requestIndex || generation != globalMediaSearchGeneration) {
                    return;
                }
                globalMediaRequestsInFlight--;
                globalMediaBatchCompleted++;
                if (error == null && response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    dialogSearch.failedAttempts = 0;
                    dialogSearch.failed = false;
                    MessagesStorage.getInstance(globalMediaSearchAccount).putUsersAndChats(result.users, result.chats, true, true);
                    MessagesController controller = MessagesController.getInstance(globalMediaSearchAccount);
                    controller.putUsers(result.users, false);
                    controller.putChats(result.chats, false);
                    controller.removeDeletedMessagesFromArray(dialogSearch.dialogId, result.messages);
                    dialogSearch.count = Math.max(result.count, result.messages.size());
                    boolean pageEndReached = result.messages.isEmpty()
                            || (!result.inexact && result.messages.size() < GLOBAL_MEDIA_PAGE_SIZE);
                    MessageObject lastMessageObject = null;
                    int oldestMessageId = 0;
                    if (!result.messages.isEmpty()) {
                        oldestMessageId = result.messages.get(result.messages.size() - 1).id;
                        for (TLRPC.Message message : result.messages) {
                            message.dialog_id = dialogSearch.dialogId;
                        }
                        MessagesStorage.getInstance(globalMediaSearchAccount).putMessages(
                                result.messages, true, true, true, 0, ChatActivity.MODE_DEFAULT, 0);
                        for (TLRPC.Message message : result.messages) {
                            MessageObject messageObject = new MessageObject(globalMediaSearchAccount, message, false, true);
                            addGlobalMediaMessage(messageObject);
                            lastMessageObject = messageObject;
                        }
                    }
                    if (dialogSearch.catchingUp) {
                        if (dialogSearch.catchupOffsetId == 0) {
                            dialogSearch.pendingHeadBoundaryId = oldestMessageId;
                        }
                        boolean initialHeadScan = dialogSearch.catchupBoundaryId <= 0;
                        boolean catchupComplete = result.messages.isEmpty() || initialHeadScan
                                || oldestMessageId <= dialogSearch.catchupBoundaryId || pageEndReached;
                        if (catchupComplete) {
                            dialogSearch.headBoundaryId = dialogSearch.pendingHeadBoundaryId;
                            dialogSearch.lastHeadSyncAt = System.currentTimeMillis();
                            dialogSearch.catchingUp = false;
                            dialogSearch.catchupOffsetId = 0;
                            dialogSearch.catchupBoundaryId = 0;
                            dialogSearch.pendingHeadBoundaryId = 0;
                            if (initialHeadScan) {
                                dialogSearch.offsetId = oldestMessageId;
                                dialogSearch.historyEndReached = pageEndReached;
                                dialogSearch.historyPendingGroupKey = !dialogSearch.historyEndReached && lastMessageObject != null
                                        ? getMediaGroupKey(lastMessageObject) : null;
                            }
                            dialogSearch.endReached = dialogSearch.historyEndReached;
                            dialogSearch.pendingGroupKey = initialHeadScan && !dialogSearch.historyEndReached
                                    && lastMessageObject != null ? getMediaGroupKey(lastMessageObject) : null;
                        } else {
                            dialogSearch.catchupOffsetId = oldestMessageId;
                            dialogSearch.endReached = false;
                            dialogSearch.pendingGroupKey = lastMessageObject != null ? getMediaGroupKey(lastMessageObject) : null;
                        }
                    } else {
                        if (oldestMessageId != 0) {
                            dialogSearch.offsetId = oldestMessageId;
                        }
                        dialogSearch.historyEndReached = pageEndReached;
                        dialogSearch.endReached = dialogSearch.historyEndReached;
                        dialogSearch.pendingGroupKey = !dialogSearch.historyEndReached && lastMessageObject != null
                                ? getMediaGroupKey(lastMessageObject) : null;
                        dialogSearch.historyPendingGroupKey = dialogSearch.pendingGroupKey;
                    }
                    if (result.messages.isEmpty()) {
                        saveGlobalMediaCacheState(dialogSearch);
                    } else {
                        saveGlobalMediaCacheStateAfterMessagesStored(dialogSearch);
                    }
                } else {
                    dialogSearch.failedAttempts++;
                    dialogSearch.failed = true;
                    dialogSearch.endReached = dialogSearch.failedAttempts >= 2;
                    if (error != null) {
                        FileLog.e("Media search failed for dialog " + dialogSearch.dialogId + ": " + error.text);
                        String floodWaitPrefix = "FLOOD_WAIT_";
                        if (error.text != null && error.text.startsWith(floodWaitPrefix)) {
                            try {
                                int waitSeconds = Integer.parseInt(error.text.substring(floodWaitPrefix.length()));
                                long retryAt = android.os.SystemClock.elapsedRealtime() + Math.max(0, waitSeconds) * 1000L;
                                globalMediaLastRequestTime = Math.max(globalMediaLastRequestTime, retryAt - GLOBAL_MEDIA_REQUEST_INTERVAL_MS);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    } else {
                        FileLog.e("Media search returned an unexpected response for dialog " + dialogSearch.dialogId);
                    }
                }

                if (globalMediaBatchCompleted == globalMediaDialogBatch.size()) {
                    globalMediaDialogBatch = null;
                    isLoading = false;
                    endReached = true;
                    for (GlobalMediaDialogSearch dialogState : globalMediaDialogSearches) {
                        endReached &= dialogState.endReached;
                    }
                    updateGlobalMediaTotalCount();
                    updateGlobalMediaResults(generation);
                    startGlobalMediaDialogBatch(generation);
                } else {
                    updateGlobalMediaResults(generation);
                    dispatchGlobalMediaDialogSearches(generation);
                }
            }));
        requestIdHolder[0] = requestId;
        globalMediaRequestIds.add(requestId);
    }

    private void updateGlobalMediaResults(int generation) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration) {
            return;
        }
        ArrayList<MessageObject> previouslyVisibleMessages = new ArrayList<>(messages);
        rawMessages.sort((left, right) -> Integer.compare(right.messageOwner.date, left.messageOwner.date));
        rebuildVisibleMessages();
        if (PhotoViewer.getInstance().isVisible()) {
            for (MessageObject messageObject : messages) {
                if (!previouslyVisibleMessages.contains(messageObject)) {
                    PhotoViewer.getInstance().addPhoto(messageObject, photoViewerClassGuid);
                }
            }
        }
        ensureGlobalMediaAdapter();
        firstLoading = false;
        if (messages.isEmpty() && isLoading) {
            emptyView.showProgress(true, false);
        } else {
            emptyView.showProgress(false);
        }
        adapter.notifyDataSetChanged();
    }

    BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableFactory;

    public void setBlurredBackgroundDrawableFactory(BlurredBackgroundDrawableViewFactory factory) {
        blurredBackgroundDrawableFactory = factory;
        floatingDateView.setBlurredBackgroundDrawable(factory.create(floatingDateView, BlurredBackgroundProviderImpl.searchFloatingDate(null)));
    }

    public static CharSequence createFromInfoString(MessageObject messageObject, int arrowType) {
        return createFromInfoString(messageObject, true, arrowType);
    }

    public static CharSequence createFromInfoString(MessageObject messageObject, boolean includeChat, int arrowType) {
        return createFromInfoString(messageObject, includeChat, arrowType, null);
    }

    public static CharSequence createFromInfoString(MessageObject messageObject, boolean includeChat, int arrowType, TextPaint textPaint) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return "";
        }
        if (messageObject.isQuickReply()) {
            QuickRepliesController.QuickReply reply = QuickRepliesController.getInstance(messageObject.currentAccount).findReply(messageObject.getQuickReplyId());
            return reply == null ? "" : reply.name;
        }
        if (messageObject.isSponsored()) {
            if (messageObject.sponsoredCanReport) {
                return getString(R.string.SponsoredMessageAd);
            } else if (messageObject.sponsoredRecommended) {
                return getString(R.string.SponsoredMessage2Recommended);
            } else {
                return getString(R.string.SponsoredMessage2);
            }
        }
        if (arrowSpan[arrowType] == null) {
            arrowSpan[arrowType] = new SpannableStringBuilder(">");
            int resId;
            if (arrowType == 0) {
                resId = R.drawable.attach_arrow_right;
            } else if (arrowType == 1) {
                resId = R.drawable.msg_mini_arrow_mediathin;
            } else if (arrowType == 2) {
                resId = R.drawable.msg_mini_arrow_mediabold;
            } else {
                return "";
            }
            Drawable arrowDrawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, resId).mutate();
            ColoredImageSpan span = new ColoredImageSpan(arrowDrawable, arrowType == 0 ? ColoredImageSpan.ALIGN_CENTER : ColoredImageSpan.ALIGN_BASELINE);
//            arrowDrawable.setBounds(0, 0, AndroidUtilities.dp(13), AndroidUtilities.dp(13));
            if (arrowType == 1 || arrowType == 2) {
                span.setScale(.85f);
            }
            arrowSpan[arrowType].setSpan(span, 0, arrowSpan[arrowType].length(), 0);
        }
        CharSequence fromName = null;
        TLRPC.User user = null;
        TLRPC.Chat chatFrom = null, chatTo = null;
        if (messageObject.messageOwner.saved_peer_id != null) {
            if (messageObject.getSavedDialogId() >= 0) {
                user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(messageObject.getSavedDialogId());
            } else if (messageObject.getSavedDialogId() < 0) {
                chatFrom = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-messageObject.getSavedDialogId());
            }
        } else {
            if (messageObject.messageOwner.from_id.user_id != 0) {
                user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(messageObject.messageOwner.from_id.user_id);
            }
            if (messageObject.messageOwner.from_id.chat_id != 0) {
                chatFrom = MessagesController.getInstance(UserConfig.selectedAccount).getChat(messageObject.messageOwner.peer_id.chat_id);
            }
            if (chatFrom == null) {
                chatFrom = messageObject.messageOwner.from_id.channel_id != 0 ? MessagesController.getInstance(UserConfig.selectedAccount).getChat(messageObject.messageOwner.peer_id.channel_id) : null;
            }
            chatTo = messageObject.messageOwner.peer_id.channel_id != 0 ? MessagesController.getInstance(UserConfig.selectedAccount).getChat(messageObject.messageOwner.peer_id.channel_id) : null;
            if (chatTo == null) {
                chatTo = messageObject.messageOwner.peer_id.chat_id != 0 ? MessagesController.getInstance(UserConfig.selectedAccount).getChat(messageObject.messageOwner.peer_id.chat_id) : null;
            }
            if (!ChatObject.isChannelAndNotMegaGroup(chatTo) && !includeChat) {
                chatTo = null;
            }
        }
        if (user != null && chatTo != null) {
            CharSequence chatTitle = chatTo.title;
            if (ChatObject.isForum(chatTo)) {
                TLRPC.TL_forumTopic topic = MessagesController.getInstance(UserConfig.selectedAccount).getTopicsController().findTopic(chatTo.id, MessageObject.getTopicId(messageObject.currentAccount, messageObject.messageOwner, true));
                if (topic != null) {
                    chatTitle = ForumUtilities.getTopicSpannedName(topic, null, false);
                }
            }
            chatTitle = Emoji.replaceEmoji(chatTitle, textPaint == null ? null : textPaint.getFontMetricsInt(), false);
            SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
            spannableStringBuilder
                    .append(Emoji.replaceEmoji(UserObject.getFirstName(user), textPaint == null ? null : textPaint.getFontMetricsInt(), false))
                    .append(' ')
                    .append(arrowSpan[arrowType])
                    .append(' ')
                    .append(chatTitle);
            fromName = spannableStringBuilder;
        } else if (user != null) {
            fromName = Emoji.replaceEmoji(UserObject.getUserName(user), textPaint == null ? null : textPaint.getFontMetricsInt(), false);
        } else if (chatFrom != null) {
            CharSequence chatTitle = chatFrom.title;
            if (ChatObject.isForum(chatFrom)) {
                TLRPC.TL_forumTopic topic = MessagesController.getInstance(UserConfig.selectedAccount).getTopicsController().findTopic(chatFrom.id, MessageObject.getTopicId(messageObject.currentAccount, messageObject.messageOwner, true));
                if (topic != null) {
                    chatTitle = ForumUtilities.getTopicSpannedName(topic, null, false);
                }
            }
            chatTitle = Emoji.replaceEmoji(chatTitle, textPaint == null ? null : textPaint.getFontMetricsInt(),  false);
            fromName = chatTitle;
        }
        return fromName == null ? "" : fromName;
    }

    public void search(long dialogId, long communityId, long minDate, long maxDate, FiltersView.MediaFilterData currentSearchFilter, boolean includeFolder, String query, boolean clearOldResults) {
        if (query == null) query = "";
        final String finalQuery = query;
        String currentSearchFilterQueryString = String.format(Locale.ENGLISH, "%d%d%d%d%d%s%s", dialogId, communityId, minDate, maxDate, currentSearchFilter == null ? -1 : currentSearchFilter.filterType, query, includeFolder);
        boolean filterAndQueryIsSame = lastSearchFilterQueryString != null && lastSearchFilterQueryString.equals(currentSearchFilterQueryString);
        if (filterAndQueryIsSame && !clearOldResults && globalMediaDialogBatch != null) {
            return;
        }
        boolean forceClear = !filterAndQueryIsSame && clearOldResults;
        this.currentSearchFilter = currentSearchFilter;
        this.currentSearchDialogId = dialogId;
        this.currentSearchCommunityId = communityId;
        this.currentSearchMinDate = minDate;
        this.currentSearchMaxDate = maxDate;
        this.currentSearchString = query;
        this.currentIncludeFolder = includeFolder;
        if (searchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        }
        AndroidUtilities.cancelRunOnUIThread(clearCurrentResultsRunnable);
        if (filterAndQueryIsSame && clearOldResults) {
            return;
        }
        if (forceClear || currentSearchFilter == null && communityId == 0 && dialogId == 0 && minDate == 0 && maxDate == 0) {
            messages.clear();
            rawMessages.clear();
            globalMediaMessageIds.clear();
            sections.clear();
            sectionArrays.clear();
            isLoading = true;
            emptyView.setVisibility(View.VISIBLE);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            requestIndex++;
            firstLoading = true;
            if (recyclerListView.getPinnedHeader() != null) {
                recyclerListView.getPinnedHeader().setAlpha(0);
            }
            localTipChats.clear();
            localTipDates.clear();
            if (!forceClear) {
                return;
            }
        } else if (clearOldResults && !messages.isEmpty()) {
            return;
        }
        isLoading = true;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        if (!filterAndQueryIsSame) {
            clearCurrentResultsRunnable.run();
            emptyView.showProgress(true, !clearOldResults);
        }

        if (TextUtils.isEmpty(query)) {
            localTipDates.clear();
            localTipChats.clear();
            if (delegate != null) {
                delegate.updateFiltersView(false, null, null, false);
            }
        }
        requestIndex++;
        final int requestId = requestIndex;
        int currentAccount = UserConfig.selectedAccount;

        boolean usePerDialogMediaSearch = dialogId == 0 && communityId == 0
                && currentSearchFilter != null
                && currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MEDIA
                && TextUtils.isEmpty(finalQuery);
        if (usePerDialogMediaSearch) {
            if (!filterAndQueryIsSame || globalMediaSearchAccount != currentAccount || globalMediaSearchFolder != (includeFolder ? 1 : 0)) {
                resetGlobalMediaDialogSearch();
                rawMessages.clear();
                globalMediaMessageIds.clear();
                messages.clear();
                messagesById.clear();
                sections.clear();
                sectionArrays.clear();
                totalCount = 0;
                endReached = false;
                globalMediaDialogSearches = null;
                globalMediaSearchAccount = currentAccount;
                globalMediaSearchFolder = includeFolder ? 1 : 0;
            }
            globalMediaSearchGeneration = requestId;
            lastMessagesSearchString = finalQuery;
            lastSearchFilterQueryString = currentSearchFilterQueryString;
            currentDataQuery = finalQuery;
            isLoading = true;
            endReached = false;
            ensureGlobalMediaAdapter();
            continueGlobalMediaSearch(requestId);
            return;
        } else if (globalMediaSearchGeneration != -1 || !globalMediaRequestIds.isEmpty()) {
            resetGlobalMediaDialogSearch();
        }

        AndroidUtilities.runOnUIThread(searchRunnable = () -> {
            TLMethod<TLRPC.messages_Messages> request;

            ArrayList<Object> resultArray = null;
            if (dialogId != 0 && communityId == 0) {
                final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
                req.q = finalQuery;
                req.limit = 20;
                req.filter = currentSearchFilter == null ? new TLRPC.TL_inputMessagesFilterEmpty() : currentSearchFilter.filter;
                req.peer = AccountInstance.getInstance(currentAccount).getMessagesController().getInputPeer(dialogId);
                if (minDate > 0) {
                    req.min_date = (int) (minDate / 1000);
                }
                if (maxDate > 0) {
                    req.max_date = (int) (maxDate / 1000);
                }
                if (filterAndQueryIsSame && finalQuery.equals(lastMessagesSearchString) && !rawMessages.isEmpty()) {
                    MessageObject lastMessage = rawMessages.get(rawMessages.size() - 1);
                    req.offset_id = lastMessage.getId();
                } else {
                    req.offset_id = 0;
                }
                request = req;
            } else {
                if (!TextUtils.isEmpty(finalQuery)) {
                    resultArray = new ArrayList<>();
                    ArrayList<CharSequence> resultArrayNames = new ArrayList<>();
                    ArrayList<TLRPC.User> encUsers = new ArrayList<>();
                    MessagesStorage.getInstance(currentAccount).localSearch(0, finalQuery, resultArray, resultArrayNames, encUsers, null, includeFolder ? 1 : 0);
                }

                final TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
                req.limit = 20;
                req.q = finalQuery;
                req.filter = currentSearchFilter == null ? new TLRPC.TL_inputMessagesFilterEmpty() : currentSearchFilter.filter;
                req.community = MessagesController.getInstance(currentAccount).getInputChannel(communityId);
                if (minDate > 0) {
                    req.min_date = (int) (minDate / 1000);
                }
                if (maxDate > 0) {
                    req.max_date = (int) (maxDate / 1000);
                }
                if (filterAndQueryIsSame && finalQuery.equals(lastMessagesSearchString) && !rawMessages.isEmpty()) {
                    MessageObject lastMessage = rawMessages.get(rawMessages.size() - 1);
                    req.offset_id = lastMessage.getId();
                    req.offset_rate = nextSearchRate;
                    long id = MessageObject.getPeerId(lastMessage.messageOwner.peer_id);
                    req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(id);
                } else {
                    req.offset_rate = 0;
                    req.offset_id = 0;
                    req.offset_peer = new TLRPC.TL_inputPeerEmpty();
                }
                req.flags |= 1;
                req.folder_id = includeFolder ? 1 : 0;
                request = req;
            }

            lastMessagesSearchString = finalQuery;
            lastSearchFilterQueryString = currentSearchFilterQueryString;

            ArrayList<Object> finalResultArray = resultArray;
            final ArrayList<FiltersView.DateData> dateData = new ArrayList<>();
            FiltersView.fillTipDates(lastMessagesSearchString, dateData);
            ConnectionsManager.getInstance(currentAccount).sendRequestTyped(request, (response, error) -> {
                ArrayList<MessageObject> messageObjects = new ArrayList<>();
                if (error == null) {
                    TLRPC.messages_Messages res = response;
                    int n = res.messages.size();
                    for (int i = 0; i < n; i++) {
                        MessageObject messageObject = new MessageObject(currentAccount, res.messages.get(i), false, true);
                        messageObject.setQuery(finalQuery);
                        messageObjects.add(messageObject);
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (requestId != requestIndex) {
                        return;
                    }
                    isLoading = false;
                    if (error != null) {
                        emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle2));
                        emptyView.subtitle.setVisibility(View.VISIBLE);
                        emptyView.subtitle.setText(LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitle2));
                        emptyView.showProgress(false, true);
                        return;
                    }

                    emptyView.showProgress(false);

                    TLRPC.messages_Messages res = response;
                    nextSearchRate = res.next_rate;
                    if (!TLObject.hasFlag(res.flags, TLObject.FLAG_0) && !messageObjects.isEmpty()) {
                        nextSearchRate = messageObjects.get(messageObjects.size() - 1).messageOwner.date;
                    }
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                    MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                    MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                    ArrayList<MessageObject> previouslyVisibleMessages = new ArrayList<>(messages);
                    if (!filterAndQueryIsSame) {
                        messages.clear();
                        rawMessages.clear();
                        globalMediaMessageIds.clear();
                        messagesById.clear();
                        sections.clear();
                        sectionArrays.clear();
                    }
                    totalCount = res.count;
                    currentDataQuery = finalQuery;
                    int n = messageObjects.size();
                    for (int i = 0; i < n; i++) {
                        rawMessages.add(messageObjects.get(i));
                    }
                    if (rawMessages.size() > totalCount) {
                        totalCount = rawMessages.size();
                    }
                    endReached = messageObjects.isEmpty() || (!res.inexact && rawMessages.size() >= totalCount);
                    rebuildVisibleMessages();
                    if (PhotoViewer.getInstance().isVisible()) {
                        for (MessageObject messageObject : messages) {
                            if (!previouslyVisibleMessages.contains(messageObject)) {
                                PhotoViewer.getInstance().addPhoto(messageObject, photoViewerClassGuid);
                            }
                        }
                    }

                    if (messages.isEmpty()) {
                        if (currentSearchFilter != null) {
                            if (TextUtils.isEmpty(currentDataQuery) && dialogId == 0 && minDate == 0) {
                                emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle));
                                String str;
                                if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_FILES) {
                                    str = LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitleFiles);
                                } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MEDIA) {
                                    str = LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitleMedia);
                                } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_LINKS) {
                                    str = LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitleLinks);
                                } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MUSIC) {
                                    str = LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitleMusic);
                                } else {
                                    str = LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitleVoice);
                                }
                                emptyView.subtitle.setVisibility(View.VISIBLE);
                                emptyView.subtitle.setText(str);
                            } else {
                                emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle2));
                                emptyView.subtitle.setVisibility(View.VISIBLE);
                                emptyView.subtitle.setText(LocaleController.getString(R.string.SearchEmptyViewFilteredSubtitle2));
                            }
                        } else {
                            emptyView.title.setText(LocaleController.getString(R.string.SearchEmptyViewTitle2));
                            emptyView.subtitle.setVisibility(View.GONE);
                        }
                    }

                    if (currentSearchFilter != null) {
                        switch (currentSearchFilter.filterType) {
                            case FiltersView.FILTER_TYPE_MEDIA:
                                if (TextUtils.isEmpty(currentDataQuery)) {
                                    adapter = sharedPhotoVideoAdapter;
                                } else {
                                    adapter = dialogsAdapter;
                                }
                                break;
                            case FiltersView.FILTER_TYPE_FILES:
                                adapter = sharedDocumentsAdapter;
                                break;
                            case FiltersView.FILTER_TYPE_LINKS:
                                adapter = sharedLinksAdapter;
                                break;
                            case FiltersView.FILTER_TYPE_MUSIC:
                                adapter = sharedAudioAdapter;
                                break;
                            case FiltersView.FILTER_TYPE_VOICE:
                                adapter = sharedVoiceAdapter;
                                break;
                        }
                    } else {
                        adapter = dialogsAdapter;
                    }
                    if (recyclerListView.getAdapter() != adapter) {
                        recyclerListView.setAdapter(adapter);
                    }

                    if (!filterAndQueryIsSame) {
                        localTipChats.clear();
                        if (finalResultArray != null) {
                            localTipChats.addAll(finalResultArray);
                        }
                        if (finalQuery != null && finalQuery.length() >= 3 && (LocaleController.getString(R.string.SavedMessages).toLowerCase().startsWith(finalQuery) ||
                                "saved messages".startsWith(finalQuery))) {
                            boolean found = false;
                            for (int i = 0; i < localTipChats.size(); i++) {
                                if (localTipChats.get(i) instanceof TLRPC.User)
                                    if (UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser().id == ((TLRPC.User) localTipChats.get(i)).id) {
                                        found = true;
                                        break;
                                    }
                            }
                            if (!found) {
                                localTipChats.add(0, UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser());
                            }
                        }
                        localTipDates.clear();
                        localTipDates.addAll(dateData);
                        localTipArchive = false;
                        if (finalQuery != null && finalQuery.length() >= 3 && (LocaleController.getString(R.string.ArchiveSearchFilter).toLowerCase().startsWith(finalQuery) ||
                                "archive".startsWith(finalQuery))) {
                            localTipArchive = true;
                        }
                        if (delegate != null) {
                            delegate.updateFiltersView(TextUtils.isEmpty(currentDataQuery), localTipChats, localTipDates, localTipArchive);
                        }
                    }
                    firstLoading = false;
                    View progressView = null;
                    int progressViewPosition = -1;
                    for (int i = 0; i < n; i++) {
                        View child = recyclerListView.getChildAt(i);
                        if (child instanceof FlickerLoadingView) {
                            progressView = child;
                            progressViewPosition = recyclerListView.getChildAdapterPosition(child);
                        }
                    }
                    final View finalProgressView = progressView;
                    if (progressView != null) {
                        recyclerListView.removeView(progressView);
                    }
                    if ((loadingView.getVisibility() == View.VISIBLE && recyclerListView.getChildCount() == 0) || (recyclerListView.getAdapter() != sharedPhotoVideoAdapter && progressView != null)) {
                        int finalProgressViewPosition = progressViewPosition;
                        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                getViewTreeObserver().removeOnPreDrawListener(this);
                                int n = recyclerListView.getChildCount();
                                AnimatorSet animatorSet = new AnimatorSet();
                                for (int i = 0; i < n; i++) {
                                    View child = recyclerListView.getChildAt(i);
                                    if (finalProgressView != null) {
                                        if (recyclerListView.getChildAdapterPosition(child) < finalProgressViewPosition) {
                                            continue;
                                        }
                                    }
                                    child.setAlpha(0);
                                    int s = Math.min(recyclerListView.getMeasuredHeight(), Math.max(0, child.getTop()));
                                    int delay = (int) ((s / (float) recyclerListView.getMeasuredHeight()) * 100);
                                    ObjectAnimator a = ObjectAnimator.ofFloat(child, View.ALPHA, 0, 1f);
                                    a.setStartDelay(delay);
                                    a.setDuration(200);
                                    animatorSet.playTogether(a);
                                }
                                animatorSet.addListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        notificationsLocker.unlock();
                                    }
                                });
                                notificationsLocker.lock();
                                animatorSet.start();

                                if (finalProgressView != null && finalProgressView.getParent() == null) {
                                    recyclerListView.addView(finalProgressView);
                                    RecyclerView.LayoutManager layoutManager = recyclerListView.getLayoutManager();
                                    if (layoutManager != null) {
                                        layoutManager.ignoreView(finalProgressView);
                                        Animator animator = ObjectAnimator.ofFloat(finalProgressView, ALPHA, finalProgressView.getAlpha(), 0);
                                        animator.addListener(new AnimatorListenerAdapter() {
                                            @Override
                                            public void onAnimationEnd(Animator animation) {
                                                finalProgressView.setAlpha(1f);
                                                layoutManager.stopIgnoringView(finalProgressView);
                                                recyclerListView.removeView(finalProgressView);
                                            }
                                        });
                                        animator.start();
                                    }
                                }
                                return true;
                            }
                        });
                    }
                    adapter.notifyDataSetChanged();
                    loadMoreForActiveFilterIfNeeded();
                });
            });
        }, (filterAndQueryIsSame && !rawMessages.isEmpty()) ? 0 : 350);

        if (currentSearchFilter == null) {
            loadingView.setViewType(FlickerLoadingView.DIALOG_TYPE);
        } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MEDIA) {
            if (!TextUtils.isEmpty(currentSearchString)) {
                loadingView.setViewType(FlickerLoadingView.DIALOG_TYPE);
            } else {
                loadingView.setViewType(FlickerLoadingView.PHOTOS_TYPE);
            }
        } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_FILES) {
            loadingView.setViewType(FlickerLoadingView.FILES_TYPE);
        } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MUSIC || currentSearchFilter.filterType == FiltersView.FILTER_TYPE_VOICE) {
            loadingView.setViewType(FlickerLoadingView.AUDIO_TYPE);
        } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_LINKS) {
            loadingView.setViewType(FlickerLoadingView.LINKS_TYPE);
        }
    }

    public void setPagesPaddings(int top, int bottom) {
        setPagesPaddings(top, bottom, false);
    }

    public void setPagesPaddings(int top, int bottom, boolean doNotRequestLayout) {
        setClipToPadding(false);
        ignoreRequestLayout = doNotRequestLayout;

        setPadding(0, top, 0, bottom);
        recyclerListView.setPadding(0, top, 0, bottom, doNotRequestLayout);

        MarginLayoutParams lp = null;
        lp = (MarginLayoutParams) recyclerListView.getLayoutParams();
        lp.topMargin = -top;
        lp.bottomMargin = -bottom;

        ignoreRequestLayout = false;
    }

    public void update() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public void setKeyboardHeight(int keyboardSize, boolean animated) {
        emptyView.setKeyboardHeight(keyboardSize, animated);
    }

    public void messagesDeleted(long channelId, ArrayList<Integer> markAsDeletedMessages) {
        boolean changed = false;
        for (int j = 0; j < rawMessages.size(); j++) {
            MessageObject messageObject = rawMessages.get(j);
            long dialogId = messageObject.getDialogId();
            int currentChannelId = dialogId < 0 && ChatObject.isChannel((int) -dialogId, UserConfig.selectedAccount) ? (int) -dialogId : 0;
            if (currentChannelId == channelId) {
                for (int i = 0; i < markAsDeletedMessages.size(); i++) {
                    if (messageObject.getId() == markAsDeletedMessages.get(i)) {
                        changed = true;
                        rawMessages.remove(j);
                        j--;
                        totalCount--;
                    }
                }
            }
        }
        if (changed) {
            rebuildVisibleMessages();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    private class SharedPhotoVideoAdapter extends RecyclerListView.SelectionAdapter {

        private Context mContext;

        public SharedPhotoVideoAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            if (messages.isEmpty()) {
                return 0;
            }
            return (int) Math.ceil(messages.size() / (float) columnsCount) +  (endReached ? 0 : 1);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new SharedPhotoVideoCell(mContext, SharedPhotoVideoCell.VIEW_TYPE_GLOBAL_SEARCH);
                    SharedPhotoVideoCell cell = (SharedPhotoVideoCell) view;
                    cell.setDelegate(new SharedPhotoVideoCell.SharedPhotoVideoCellDelegate() {
                        @Override
                        public void didClickItem(SharedPhotoVideoCell cell, int index, MessageObject messageObject, int a) {
                            onItemClick(index, cell, messageObject, a);
                        }

                        @Override
                        public boolean didLongClickItem(SharedPhotoVideoCell cell, int index, MessageObject messageObject, int a) {
                            if (uiCallback.actionModeShowing()) {
                                didClickItem(cell, index, messageObject, a);
                                return true;
                            }
                            return onItemLongClick(messageObject, cell, a);
                        }
                    });
                    break;
                case 2:
                    view = new GraySectionCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_graySection) & 0xf2ffffff);
                    break;
                case 1:
                default:
                    FlickerLoadingView flickerLoadingView = new FlickerLoadingView(mContext) {
                        @Override
                        public int getColumnsCount() {
                            return columnsCount;
                        }
                    };
                    flickerLoadingView.setIsSingleCell(true);
                    flickerLoadingView.setViewType(FlickerLoadingView.PHOTOS_TYPE);
                    view = flickerLoadingView;
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                ArrayList<MessageObject> messageObjects = messages;
                SharedPhotoVideoCell cell = (SharedPhotoVideoCell) holder.itemView;
                cell.setItemsCount(columnsCount);
                cell.setIsFirst(position == 0);
                for (int a = 0; a < columnsCount; a++) {
                    int index = position * columnsCount + a;
                    if (index < messageObjects.size()) {
                        MessageObject messageObject = messageObjects.get(index);
                        cell.setItem(a, messages.indexOf(messageObject), messageObject);
                        if (uiCallback.actionModeShowing()) {
                            messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                            cell.setChecked(a, uiCallback.isSelected(messageHashIdTmp), true);
                        } else {
                            cell.setChecked(a, false, true);
                        }
                    } else {
                        cell.setItem(a, index, null);
                    }
                }
                cell.requestLayout();
            } else if (holder.getItemViewType() == 3) {
                DialogCell cell = (DialogCell) holder.itemView;
                cell.useSeparator = (position != getItemCount() - 1);
                MessageObject messageObject = messages.get(position);
                boolean animated = cell.getMessage() != null && cell.getMessage().getId() == messageObject.getId();
                cell.useFromUserAsAvatar = useFromUserAsAvatar;
                cell.setDialog(messageObject.getDialogId(), messageObject, messageObject.messageOwner.date, false, false);
                if (uiCallback.actionModeShowing()) {
                    messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                    cell.setChecked(uiCallback.isSelected(messageHashIdTmp), animated);
                } else {
                    cell.setChecked(false, animated);
                }
            } else if (holder.getItemViewType() == 1) {
                FlickerLoadingView flickerLoadingView = (FlickerLoadingView) holder.itemView;
                int count = (int) Math.ceil(messages.size() / (float) columnsCount);
                flickerLoadingView.skipDrawItemsCount(columnsCount - (columnsCount * count - messages.size()));
            }
        }

        @Override
        public int getItemViewType(int position) {
            int count = (int) Math.ceil(messages.size() / (float) columnsCount);
            if (position < count) {
                return 0;
            }
            return 1;
        }
    }

    private boolean useFromUserAsAvatar;
    public void setUseFromUserAsAvatar(boolean value) {
        useFromUserAsAvatar = value;
    }

    private void onItemClick(int index, View view, MessageObject message, int a) {
        if (message == null) {
            return;
        }
        if (uiCallback.actionModeShowing()) {
            uiCallback.toggleItemSelection(message, view, a);
            return;
        }
        if (view instanceof DialogCell) {
            uiCallback.goToMessage(message);
            return;
        }
        if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MEDIA) {
            PhotoViewer.getInstance().setParentActivity(parentFragment);
            PhotoViewer.getInstance().openPhoto(messages, index, 0, 0, 0, provider);
            photoViewerClassGuid = PhotoViewer.getInstance().getClassGuid();
        } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MUSIC || currentSearchFilter.filterType == FiltersView.FILTER_TYPE_VOICE) {
            if (view instanceof SharedAudioCell) {
                ((SharedAudioCell) view).didPressedButton();
            }
        } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_FILES) {
            if (view instanceof SharedDocumentCell) {
                SharedDocumentCell cell = (SharedDocumentCell) view;
                TLRPC.Document document = message.getDocument();
                if (cell.isLoaded()) {
                    if (message.canPreviewDocument()) {
                        PhotoViewer.getInstance().setParentActivity(parentFragment);
                        index = messages.indexOf(message);
                        if (index < 0) {
                            ArrayList<MessageObject> documents = new ArrayList<>();
                            documents.add(message);
                            PhotoViewer.getInstance().setParentActivity(parentFragment);
                            PhotoViewer.getInstance().openPhoto(documents, 0, 0, 0, 0, provider);
                            photoViewerClassGuid = PhotoViewer.getInstance().getClassGuid();
                        } else {
                            PhotoViewer.getInstance().setParentActivity(parentFragment);
                            PhotoViewer.getInstance().openPhoto(messages, index, 0, 0, 0, provider);
                            photoViewerClassGuid = PhotoViewer.getInstance().getClassGuid();
                        }
                        return;
                    }
                    AndroidUtilities.openDocument(message, parentActivity, parentFragment);
                } else if (!cell.isLoading()) {
                    MessageObject messageObject = cell.getMessage();
                    messageObject.putInDownloadsStore = true;
                    AccountInstance.getInstance(UserConfig.selectedAccount).getFileLoader().loadFile(document, messageObject, FileLoader.PRIORITY_LOW, 0);
                    cell.updateFileExistIcon(true);
                } else {
                    AccountInstance.getInstance(UserConfig.selectedAccount).getFileLoader().cancelLoadFile(document);
                    cell.updateFileExistIcon(true);
                }
            }
        } else if (currentSearchFilter.filterType == FiltersView.FILTER_TYPE_LINKS) {
            try {
                TLRPC.WebPage webPage = message.messageOwner.media != null ? message.messageOwner.media.webpage : null;
                String link = null;
                if (webPage != null && !(webPage instanceof TLRPC.TL_webPageEmpty)) {
                    if (webPage.cached_page != null) {
                        if (LaunchActivity.instance != null && LaunchActivity.instance.getBottomSheetTabs() != null && LaunchActivity.instance.getBottomSheetTabs().tryReopenTab(message) != null) {
                            return;
                        }
                        parentFragment.createArticleViewer(false).open(message);
                        return;
                    } else if (webPage.embed_url != null && webPage.embed_url.length() != 0) {
                        openWebView(webPage, message);
                        return;
                    } else {
                        link = webPage.url;
                    }
                }
                if (link == null) {
                    link = ((SharedLinkCell) view).getLink(0);
                }
                if (link != null) {
                    openUrl(link);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private class SharedLinksAdapter extends RecyclerListView.SectionsAdapter {

        private Context mContext;

        private final SharedLinkCell.SharedLinkCellDelegate sharedLinkCellDelegate = new SharedLinkCell.SharedLinkCellDelegate() {

            @Override
            public void needOpenWebView(TLRPC.WebPage webPage, MessageObject message) {
                openWebView(webPage, message);
            }

            @Override
            public boolean canPerformActions() {
                return !uiCallback.actionModeShowing();
            }

            @Override
            public void onLinkPress(String urlFinal, boolean longPress) {
                if (longPress) {
                    BottomSheet.Builder builder = new BottomSheet.Builder(parentActivity);
                    builder.setTitle(urlFinal);
                    builder.setItems(new CharSequence[]{LocaleController.getString(R.string.Open), LocaleController.getString(R.string.Copy)}, (dialog, which) -> {
                        if (which == 0) {
                            openUrl(urlFinal);
                        } else if (which == 1) {
                            String url = urlFinal;
                            if (url.startsWith("mailto:")) {
                                url = url.substring(7);
                            } else if (url.startsWith("tel:")) {
                                url = url.substring(4);
                            }
                            AndroidUtilities.addToClipboard(url);
                        }
                    });
                    parentFragment.showDialog(builder.create());
                } else {
                    openUrl(urlFinal);
                }
            }
        };

        public SharedLinksAdapter(Context context) {
            mContext = context;
        }

        @Override
        public Object getItem(int section, int position) {
            return null;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder, int section, int row) {
            return true;
        }

        @Override
        public int getSectionCount() {
            if (messages.isEmpty()) {
                return 0;
            }
            if (sections.isEmpty() && isLoading) {
                return 0;
            }
            return sections.size() + (sections.isEmpty() || endReached ? 0 : 1);
        }

        @Override
        public int getCountForSection(int section) {
            if (section < sections.size()) {
                return sectionArrays.get(sections.get(section)).size() + (section == 0 ? 0 : 1);
            }
            return 1;
        }

        @Override
        public View getSectionHeaderView(int section, View view) {
            if (view == null) {
                view = new GraySectionCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_graySection) & 0xf2ffffff);
            }
            if (section == 0) {
                view.setAlpha(0f);
                return view;
            }
            if (section < sections.size()) {
                view.setAlpha(1.0f);
                String name = sections.get(section);
                ArrayList<MessageObject> messageObjects = sectionArrays.get(name);
                MessageObject messageObject = messageObjects.get(0);
                ((GraySectionCell) view).setText(LocaleController.formatSectionDate(messageObject.messageOwner.date));

            }
            return view;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new GraySectionCell(mContext);
                    break;
                case 1:
                    view = new SharedLinkCell(mContext, SharedLinkCell.VIEW_TYPE_GLOBAL_SEARCH);
                    ((SharedLinkCell) view).setDelegate(sharedLinkCellDelegate);
                    break;
                case 2:
                default:
                    FlickerLoadingView flickerLoadingView = new FlickerLoadingView(mContext);
                    flickerLoadingView.setViewType(FlickerLoadingView.LINKS_TYPE);
                    flickerLoadingView.setIsSingleCell(true);
                    view = flickerLoadingView;
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(int section, int position, RecyclerView.ViewHolder holder) {
            if (holder.getItemViewType() != 2) {
                String name = sections.get(section);
                ArrayList<MessageObject> messageObjects = sectionArrays.get(name);
                switch (holder.getItemViewType()) {
                    case 0: {
                        MessageObject messageObject = messageObjects.get(0);
                        ((GraySectionCell) holder.itemView).setText(LocaleController.formatSectionDate(messageObject.messageOwner.date));
                        break;
                    }
                    case 1: {
                        if (section != 0) {
                            position--;
                        }
                        SharedLinkCell sharedLinkCell = (SharedLinkCell) holder.itemView;
                        MessageObject messageObject = messageObjects.get(position);
                        boolean animated = sharedLinkCell.getMessage() != null && sharedLinkCell.getMessage().getId() == messageObject.getId();
                        sharedLinkCell.setLink(messageObject, position != messageObjects.size() - 1 || section == sections.size() - 1 && isLoading);
                        sharedLinkCell.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                sharedLinkCell.getViewTreeObserver().removeOnPreDrawListener(this);
                                if (uiCallback.actionModeShowing()) {
                                    messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                                    sharedLinkCell.setChecked(uiCallback.isSelected(messageHashIdTmp), animated);
                                } else {
                                    sharedLinkCell.setChecked(false, animated);
                                }
                                return true;
                            }
                        });
                        break;
                    }
                }
            }
        }

        @Override
        public int getItemViewType(int section, int position) {
            if (section < sections.size()) {
                if (section != 0 && position == 0) {
                    return 0;
                }
                return 1;
            }
            return 2;
        }

        @Override
        public String getLetter(int position) {
            return null;
        }

        @Override
        public void getPositionForScrollProgress(RecyclerListView listView, float progress, int[] position) {
            position[0] = 0;
            position[1] = 0;
        }
    }

    private class SharedDocumentsAdapter extends RecyclerListView.SectionsAdapter {

        private Context mContext;
        private int currentType;

        public SharedDocumentsAdapter(Context context, int type) {
            mContext = context;
            currentType = type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder, int section, int row) {
            return section == 0 || row != 0;
        }

        @Override
        public int getSectionCount() {
            if (sections.isEmpty()) {
                return 0;
            }
            return sections.size() + (sections.isEmpty() || endReached ? 0 : 1);
        }

        @Override
        public Object getItem(int section, int position) {
            return null;
        }

        @Override
        public int getCountForSection(int section) {
            if (section < sections.size()) {
                return sectionArrays.get(sections.get(section)).size() + (section == 0 ? 0 : 1);
            }
            return 1;
        }

        @Override
        public View getSectionHeaderView(int section, View view) {
            if (view == null) {
                view = new GraySectionCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_graySection) & 0xf2ffffff);
            }
            if (section == 0) {
                view.setAlpha(0f);
                return view;
            }
            if (section < sections.size()) {
                view.setAlpha(1.0f);
                String name = sections.get(section);
                ArrayList<MessageObject> messageObjects = sectionArrays.get(name);
                MessageObject messageObject = messageObjects.get(0);
                String str = LocaleController.formatSectionDate(messageObject.messageOwner.date);
                ((GraySectionCell) view).setText(str);

            }
            return view;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new GraySectionCell(mContext);
                    break;
                case 1:
                    view = new SharedDocumentCell(mContext, SharedDocumentCell.VIEW_TYPE_GLOBAL_SEARCH);
                    break;
                case 2:
                    FlickerLoadingView flickerLoadingView = new FlickerLoadingView(mContext);
                    if (currentType == 2 || currentType == 4) {
                        flickerLoadingView.setViewType(FlickerLoadingView.AUDIO_TYPE);
                    } else {
                        flickerLoadingView.setViewType(FlickerLoadingView.FILES_TYPE);
                    }
                    flickerLoadingView.setIsSingleCell(true);
                    view = flickerLoadingView;
                    break;
                case 3:
                default:
                    view = new SharedAudioCell(mContext, SharedAudioCell.VIEW_TYPE_GLOBAL_SEARCH, null) {
                        @Override
                        public boolean needPlayMessage(MessageObject messageObject) {
                            if (messageObject.isVoice() || messageObject.isRoundVideo()) {
                                boolean result = MediaController.getInstance().playMessage(messageObject);
                                MediaController.getInstance().setVoiceMessagesPlaylist(result ? messages : null, false);
                                return result;
                            } else if (messageObject.isMusic()) {
                                MediaController.PlaylistGlobalSearchParams params = new MediaController.PlaylistGlobalSearchParams(currentDataQuery, currentSearchDialogId, currentSearchMinDate, currentSearchMinDate, currentSearchFilter);
                                params.endReached = endReached;
                                params.nextSearchRate = nextSearchRate;
                                params.totalCount = totalCount;
                                params.folderId = currentIncludeFolder ? 1 : 0;
                                return MediaController.getInstance().setPlaylist(messages, messageObject, 0, params);
                            }
                            return false;
                        }
                    };
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(int section, int position, RecyclerView.ViewHolder holder) {
            if (holder.getItemViewType() != 2) {
                String name = sections.get(section);
                ArrayList<MessageObject> messageObjects = sectionArrays.get(name);
                switch (holder.getItemViewType()) {
                    case 0: {
                        MessageObject messageObject = messageObjects.get(0);
                        String str = LocaleController.formatSectionDate(messageObject.messageOwner.date);
                        ((GraySectionCell) holder.itemView).setText(str);
                        break;
                    }
                    case 1: {
                        if (section != 0) {
                            position--;
                        }
                        SharedDocumentCell sharedDocumentCell = (SharedDocumentCell) holder.itemView;
                        MessageObject messageObject = messageObjects.get(position);
                        boolean animated = sharedDocumentCell.getMessage() != null && sharedDocumentCell.getMessage().getId() == messageObject.getId();
                        sharedDocumentCell.setDocument(messageObject, position != messageObjects.size() - 1 || section == sections.size() - 1 && isLoading);
                        sharedDocumentCell.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                sharedDocumentCell.getViewTreeObserver().removeOnPreDrawListener(this);
                                if (uiCallback.actionModeShowing()) {
                                    messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                                    sharedDocumentCell.setChecked(uiCallback.isSelected(messageHashIdTmp), animated);
                                } else {
                                    sharedDocumentCell.setChecked(false, animated);
                                }
                                return true;
                            }
                        });
                        break;
                    }
                    case 3: {
                        if (section != 0) {
                            position--;
                        }
                        SharedAudioCell sharedAudioCell = (SharedAudioCell) holder.itemView;
                        MessageObject messageObject = messageObjects.get(position);
                        boolean animated = sharedAudioCell.getMessage() != null && sharedAudioCell.getMessage().getId() == messageObject.getId();
                        sharedAudioCell.setMessageObject(messageObject, position != messageObjects.size() - 1 || section == sections.size() - 1 && isLoading);
                        sharedAudioCell.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                sharedAudioCell.getViewTreeObserver().removeOnPreDrawListener(this);
                                if (uiCallback.actionModeShowing()) {
                                    messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                                    sharedAudioCell.setChecked(uiCallback.isSelected(messageHashIdTmp), animated);
                                } else {
                                    sharedAudioCell.setChecked(false, animated);
                                }
                                return true;
                            }
                        });
                        break;
                    }
                }
            }
        }

        @Override
        public int getItemViewType(int section, int position) {
            if (section < sections.size()) {
                if (section != 0 && position == 0) {
                    return 0;
                } else {
                    if (currentType == 2 || currentType == 4) {
                        return 3;
                    } else {
                        return 1;
                    }
                }
            }
            return 2;
        }

        @Override
        public String getLetter(int position) {
            return null;
        }

        @Override
        public void getPositionForScrollProgress(RecyclerListView listView, float progress, int[] position) {
            position[0] = 0;
            position[1] = 0;
        }
    }

    private void openUrl(String link) {
        if (AndroidUtilities.shouldShowUrlInAlert(link)) {
            AlertsCreator.showOpenUrlAlert(parentFragment, link, true, true);
        } else {
            Browser.openUrl(parentActivity, link);
        }
    }

    private void openWebView(TLRPC.WebPage webPage, MessageObject message) {
        EmbedBottomSheet.show(parentFragment, message, provider, webPage.site_name, webPage.description, webPage.url, webPage.embed_url, webPage.embed_width, webPage.embed_height, false);
    }

    int lastAccount;
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter notificationCenter = NotificationCenter.getInstance(lastAccount = UserConfig.selectedAccount);
        notificationCenter.addObserver(this, NotificationCenter.emojiLoaded);
        notificationCenter.addObserver(this, NotificationCenter.dialogsNeedReload);
        if (globalMediaSearchGeneration != -1) {
            setGlobalMediaCacheObserverAccount(globalMediaSearchAccount);
            startGlobalMediaCacheLoadRound();
        }
        if (globalMediaWaitingForDialogs && globalMediaSearchGeneration == requestIndex) {
            continueGlobalMediaSearch(globalMediaSearchGeneration);
        } else if (globalMediaDialogBatch != null && globalMediaRequestsInFlight == 0
                && globalMediaSearchGeneration == requestIndex) {
            dispatchGlobalMediaDialogSearches(globalMediaSearchGeneration);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter notificationCenter = NotificationCenter.getInstance(lastAccount);
        notificationCenter.removeObserver(this, NotificationCenter.emojiLoaded);
        notificationCenter.removeObserver(this, NotificationCenter.dialogsNeedReload);
        setGlobalMediaCacheObserverAccount(-1);
        if (globalMediaCacheRequestInFlight) {
            globalMediaCacheRequestToken++;
            globalMediaCacheRequestInFlight = false;
            globalMediaCacheLoadingDialog = null;
        }
        globalMediaCacheBatch = null;
        globalMediaCacheBatchCursor = 0;
        if (globalMediaDispatchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaDispatchRunnable);
            globalMediaDispatchRunnable = null;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded) {
            int n = recyclerListView.getChildCount();
            for (int i = 0; i < n; i++) {
                if (recyclerListView.getChildAt(i) instanceof DialogCell) {
                    ((DialogCell) recyclerListView.getChildAt(i)).update(0);
                }
                recyclerListView.getChildAt(i).invalidate();
            }
        } else if (id == NotificationCenter.dialogsNeedReload
                && account == globalMediaSearchAccount
                && globalMediaWaitingForDialogs
                && globalMediaSearchGeneration == requestIndex) {
            continueGlobalMediaSearch(globalMediaSearchGeneration);
        } else if (id == NotificationCenter.mediaDidLoad && account == globalMediaSearchAccount) {
            onGlobalMediaCacheLoaded(args);
        }
    }

    private boolean onItemLongClick(MessageObject item, View view, int a) {
        if (!uiCallback.actionModeShowing()) {
            uiCallback.showActionMode();
        }
        if (uiCallback.actionModeShowing()) {
            uiCallback.toggleItemSelection(item, view, a);
        }
        return true;
    }

    public static class MessageHashId {
        public long dialogId;
        public int messageId;

        public MessageHashId(int messageId, long dialogId) {
            this.dialogId = dialogId;
            this.messageId = messageId;
        }

        public void set(int messageId, long dialogId) {
            this.dialogId = dialogId;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MessageHashId that = (MessageHashId) o;
            return dialogId == that.dialogId && messageId == that.messageId;
        }

        @Override
        public int hashCode() {
            return messageId;
        }
    }

    class OnlyUserFiltersAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new DialogCell(null, parent.getContext(), true, true) {
                        @Override
                        public boolean isForumCell() {
                            return false;
                        }
                    };
                    break;
                case 3:
                    FlickerLoadingView flickerLoadingView = new FlickerLoadingView(parent.getContext());
                    flickerLoadingView.setIsSingleCell(true);
                    flickerLoadingView.setViewType(FlickerLoadingView.DIALOG_TYPE);
                    view = flickerLoadingView;
                    break;
                default:
                case 2:
                    GraySectionCell cell = new GraySectionCell(parent.getContext());
                    cell.setText(LocaleController.getString(R.string.SearchMessages));
                    view = cell;
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                DialogCell cell = ((DialogCell) holder.itemView);
                MessageObject messageObject = messages.get(position);
                cell.useFromUserAsAvatar = useFromUserAsAvatar;
                cell.setDialog(messageObject.getDialogId(), messageObject, messageObject.messageOwner.date, false, false);
                cell.useSeparator = position != getItemCount() - 1;
                boolean animated = cell.getMessage() != null && cell.getMessage().getId() == messageObject.getId();
                cell.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        cell.getViewTreeObserver().removeOnPreDrawListener(this);
                        if (uiCallback.actionModeShowing()) {
                            messageHashIdTmp.set(messageObject.getId(), messageObject.getDialogId());
                            cell.setChecked(uiCallback.isSelected(messageHashIdTmp), animated);
                        } else {
                            cell.setChecked(false, animated);
                        }
                        return true;
                    }
                });
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position >= messages.size()) {
                return 3;
            }
            return 0;
        }

        @Override
        public int getItemCount() {
            if (messages.isEmpty()) {
                return 0;
            }
            return messages.size() + (endReached ? 0 : 1);
        }
    }

    boolean ignoreRequestLayout;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int oldColumnsCount = columnsCount;
        if (AndroidUtilities.isTablet()) {
            columnsCount = 3;
        } else {
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                columnsCount = 6;
            } else {
                columnsCount = 3;
            }
        }
        if (oldColumnsCount != columnsCount && adapter == sharedPhotoVideoAdapter) {
            ignoreRequestLayout = true;
            adapter.notifyDataSetChanged();
            ignoreRequestLayout = false;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void requestLayout() {
        if (ignoreRequestLayout) {
            return;
        }
        super.requestLayout();
    }

    public void setDelegate(Delegate delegate, boolean update) {
        this.delegate = delegate;
        if (update && delegate != null) {
            if (!localTipChats.isEmpty()) {
                delegate.updateFiltersView(false, localTipChats, localTipDates, localTipArchive);
            }
        }
    }

    public void setUiCallback(UiCallback callback) {
        this.uiCallback = callback;
    }

    public interface Delegate {
        void updateFiltersView(boolean showMediaFilters, ArrayList<Object> users, ArrayList<FiltersView.DateData> dates, boolean archive);
    }

    public interface UiCallback {
        void goToMessage(MessageObject messageObject);

        boolean actionModeShowing();

        void toggleItemSelection(MessageObject item, View view, int a);

        boolean isSelected(MessageHashId messageHashId);

        void showActionMode();

        int getFolderId();
    }

    private void showFloatingDateView() {
        AndroidUtilities.cancelRunOnUIThread(hideFloatingDateRunnable);
        AndroidUtilities.runOnUIThread(hideFloatingDateRunnable, 1650);
        animatorFloatingDataVisible.setValue(true, true);
    }

    private void hideFloatingDateView() {
        AndroidUtilities.cancelRunOnUIThread(hideFloatingDateRunnable);
        animatorFloatingDataVisible.setValue(false, true);
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_FLOATING_DATE_VISIBLE) {
            checkUi_floatingDateView();
        }
    }

    private void checkUi_floatingDateView() {
        final float factor = animatorFloatingDataVisible.getFloatValue();

        floatingDateView.setTranslationY(-dp(24) * (1f - factor));
        floatingDateView.setAlpha(factor);
        floatingDateView.setVisibility(factor > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    public void setChatPreviewDelegate(SearchViewPager.ChatPreviewDelegate chatPreviewDelegate) {
        this.chatPreviewDelegate = chatPreviewDelegate;
    }

    private static class FloatingDateView extends View {
        private final AnimatedTextView.AnimatedTextDrawable mDrawable;
        private BlurredBackgroundDrawable bgDrawable;
        private String text;

        public FloatingDateView(Context context) {
            super(context);
            mDrawable = new AnimatedTextView.AnimatedTextDrawable(true, false, false);
            mDrawable.setTextColor(Color.WHITE);
            mDrawable.setGravity(Gravity.CENTER);
            mDrawable.setTypeface(AndroidUtilities.bold());
            mDrawable.setTextSize(dp(14));
            mDrawable.setCallback(this);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mDrawable.setBounds(0, 0, w, h);
        }

        public void setBlurredBackgroundDrawable(BlurredBackgroundDrawable d) {
            bgDrawable = d;
            bgDrawable.setRadius(dp(11.5f));
            bgDrawable.setPadding(dp(5));
        }

        public void setCustomDate(int date) {
            setCustomText(LocaleController.formatDateChat(date));
        }

        public void setCustomText(String text) {
            if (!TextUtils.equals(this.text, text)) {
                this.text = text;
                mDrawable.setText(text, true);
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            final int w = (int) (mDrawable.getCurrentWidth() + dpf2(30));
            final int l = (getWidth() - w) / 2;
            final int r = l + w;
            if (bgDrawable != null) {
                bgDrawable.setBounds(l, 0, r, getHeight());
                bgDrawable.draw(canvas);
            }
            mDrawable.draw(canvas);
        }

        @Override
        protected boolean verifyDrawable(@NonNull Drawable who) {
            return super.verifyDrawable(who) || who == mDrawable;
        }

        public void updateColors() {
            if (bgDrawable != null) {
                bgDrawable.updateColors();
            }
        }
    }

    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            if (floatingDateView != null) {
                floatingDateView.updateColors();
            }
        };

        ArrayList<ThemeDescription> arrayList = new ArrayList<>();
        arrayList.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));

        arrayList.add(new ThemeDescription(this, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(this, 0, null, null, null, null, Theme.key_dialogBackground));
        arrayList.add(new ThemeDescription(this, 0, null, null, null, null, Theme.key_windowBackgroundGray));

        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"dateTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_PROGRESSBAR, new Class[]{SharedDocumentCell.class}, new String[]{"progressView"}, null, null, null, Theme.key_sharedMedia_startStopLoadIcon));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"statusImageView"}, null, null, null, Theme.key_sharedMedia_startStopLoadIcon));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOX, new Class[]{SharedDocumentCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkbox));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{SharedDocumentCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkboxCheck));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"thumbImageView"}, null, null, null, Theme.key_files_folderIcon));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedDocumentCell.class}, new String[]{"extTextView"}, null, null, null, Theme.key_files_iconText));

        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{LoadingCell.class}, new String[]{"progressBar"}, null, null, null, Theme.key_progressCircle));

        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOX, new Class[]{SharedAudioCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkbox));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{SharedAudioCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkboxCheck));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedAudioCell.class}, Theme.chat_contextResult_titleTextPaint, null, null, Theme.key_windowBackgroundWhiteBlackText));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{SharedAudioCell.class}, Theme.chat_contextResult_descriptionTextPaint, null, null, Theme.key_windowBackgroundWhiteGrayText2));

        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOX, new Class[]{SharedLinkCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkbox));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{SharedLinkCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkboxCheck));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{SharedLinkCell.class}, new String[]{"titleTextPaint"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{SharedLinkCell.class}, null, null, null, Theme.key_windowBackgroundWhiteLinkText));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{SharedLinkCell.class}, Theme.linkSelectionPaint, null, null, Theme.key_windowBackgroundWhiteLinkSelection));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{SharedLinkCell.class}, new String[]{"letterDrawable"}, null, null, null, Theme.key_sharedMedia_linkPlaceholderText));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{SharedLinkCell.class}, new String[]{"letterDrawable"}, null, null, null, Theme.key_sharedMedia_linkPlaceholder));

        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR | ThemeDescription.FLAG_SECTIONS, new Class[]{SharedMediaSectionCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_SECTIONS, new Class[]{SharedMediaSectionCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{SharedMediaSectionCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));

        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class, ProfileSearchCell.class}, null, Theme.avatarDrawables, null, Theme.key_avatar_text));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_countPaint, null, null, Theme.key_chats_unreadCounter));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_countGrayPaint, null, null, Theme.key_chats_unreadCounterMuted));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_countTextPaint, null, null, Theme.key_chats_unreadCounterText));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class, ProfileSearchCell.class}, null, new Drawable[]{Theme.dialogs_lockDrawable}, null, Theme.key_chats_secretIcon));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class, ProfileSearchCell.class}, null, new Drawable[]{Theme.dialogs_scamDrawable, Theme.dialogs_fakeDrawable}, null, Theme.key_chats_draft));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, new Drawable[]{Theme.dialogs_pinnedDrawable, Theme.dialogs_pinnedDrawable2, Theme.dialogs_reorderDrawable}, null, Theme.key_chats_pinnedIcon));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class, ProfileSearchCell.class}, null, new Paint[]{Theme.dialogs_namePaint[0], Theme.dialogs_namePaint[1], Theme.dialogs_searchNamePaint}, null, null, Theme.key_chats_name));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class, ProfileSearchCell.class}, null, new Paint[]{Theme.dialogs_nameEncryptedPaint[0], Theme.dialogs_nameEncryptedPaint[1], Theme.dialogs_searchNameEncryptedPaint}, null, null, Theme.key_chats_secretName));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_messagePaint[1], null, null, Theme.key_chats_message_threeLines));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_messagePaint[0], null, null, Theme.key_chats_message));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_messageNamePaint, null, null, Theme.key_chats_nameMessage_threeLines));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, null, null, Theme.key_chats_draft));

        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, Theme.dialogs_messagePrintingPaint, null, null, Theme.key_chats_actionMessage));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_timePaint, null, null, Theme.key_chats_date));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_pinnedPaint, null, null, Theme.key_chats_pinnedOverlay));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_tabletSeletedPaint, null, null, Theme.key_chats_tabletSelectedOverlay));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, new Drawable[]{Theme.dialogs_checkDrawable}, null, Theme.key_chats_sentCheck));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, new Drawable[]{Theme.dialogs_checkReadDrawable, Theme.dialogs_halfCheckDrawable}, null, Theme.key_chats_sentReadCheck));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, new Drawable[]{Theme.dialogs_clockDrawable}, null, Theme.key_chats_sentClock));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, Theme.dialogs_errorPaint, null, null, Theme.key_chats_sentError));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, new Drawable[]{Theme.dialogs_errorDrawable}, null, Theme.key_chats_sentErrorIcon));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class, ProfileSearchCell.class}, null, new Drawable[]{Theme.dialogs_verifiedCheckDrawable}, null, Theme.key_chats_verifiedCheck));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class, ProfileSearchCell.class}, null, new Drawable[]{Theme.dialogs_verifiedDrawable}, null, Theme.key_chats_verifiedBackground));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, new Drawable[]{Theme.dialogs_muteDrawable}, null, Theme.key_chats_muteIcon));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, new Drawable[]{Theme.dialogs_mentionDrawable}, null, Theme.key_chats_mentionIcon));

        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, null, null, Theme.key_chats_archivePinBackground));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, null, null, Theme.key_chats_archiveBackground));

        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, null, null, Theme.key_chats_onlineCircle));
        arrayList.add(new ThemeDescription(recyclerListView, 0, new Class[]{DialogCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOX, new Class[]{DialogCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{DialogCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_checkboxCheck));

        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_SECTIONS, new Class[]{GraySectionCell.class}, new String[]{"textView"}, null, null, null, Theme.key_graySectionText));
        arrayList.add(new ThemeDescription(recyclerListView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR | ThemeDescription.FLAG_SECTIONS, new Class[]{GraySectionCell.class}, null, null, null, Theme.key_graySection));

        arrayList.add(new ThemeDescription(emptyView.title, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        arrayList.add(new ThemeDescription(emptyView.subtitle, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText));


        return arrayList;
    }
}
