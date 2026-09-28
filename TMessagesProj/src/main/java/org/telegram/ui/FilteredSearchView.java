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
import org.telegram.ui.Components.BulletinFactory;
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
    private static final int GLOBAL_MEDIA_PAGE_SIZE = 10;
    private static final int GLOBAL_MEDIA_REFRESH_BATCH_SIZE = 100;
    private static final int GLOBAL_MEDIA_WINDOW_SIZE = 1000;
    private static final int GLOBAL_MEDIA_WINDOW_ADVANCE = 400;
    private static final long GLOBAL_MEDIA_REQUEST_INTERVAL_MS = 200;
    private static final long GLOBAL_MEDIA_HEAD_STALENESS_MS = 5 * 60 * 1000L;
    private static final int GLOBAL_MEDIA_EMPTY_PAGE_AUTO_LOAD_LIMIT = 5;
    private ArrayList<GlobalMediaDialogSearch> globalMediaDialogSearches;
    private ArrayList<GlobalMediaDialogSearch> globalMediaDialogBatch;
    private final ArrayList<GlobalMediaDialogSearch> globalMediaUnavailableDialogs = new ArrayList<>();
    private String globalMediaLastErrorCode;
    private boolean globalMediaUnavailableNoticeShown;
    private final HashSet<Long> globalMediaDialogIds = new HashSet<>();
    private final ArrayList<Integer> globalMediaRequestIds = new ArrayList<>();
    private final HashSet<MessageHashId> globalMediaMessageIds = new HashSet<>();
    private final HashMap<String, ArrayList<MessageObject>> globalMediaGroupContext = new HashMap<>();
    private Runnable globalMediaDispatchRunnable;
    private int globalMediaSearchGeneration = -1;
    private int globalMediaSearchAccount;
    private int globalMediaSearchFolder;
    private int globalMediaBatchCursor;
    private int globalMediaBatchCompleted;
    private int globalMediaRequestsInFlight;
    private long globalMediaLastRequestTime;
    private Runnable globalMediaResultsUpdateRunnable;
    private boolean globalMediaWindowResumeScheduled;
    private int globalMediaScrollState = RecyclerView.SCROLL_STATE_IDLE;
    private boolean globalMediaLoadRequestedForGesture;
    private boolean globalMediaResultsDirty;
    private int globalMediaMessagesSinceRefresh;
    private MessageObject globalMediaWindowAnchorMessage;
    private long globalMediaWindowAnchorDialogId;
    private int globalMediaWindowAnchorMessageId;
    private int globalMediaWindowAnchorTop;
    private boolean globalMediaWaitingForDialogs;
    private boolean globalMediaCacheBootstrapStarted;
    private boolean globalMediaCacheBootstrapFinished;
    private boolean globalMediaDiscoveryPending;
    private boolean globalMediaPreviewRequested;
    private final HashSet<Long> globalMediaPreviewDialogIds = new HashSet<>();
    private final ArrayList<TLRPC.Message> globalMediaPendingLiveMessages = new ArrayList<>();
    private final HashSet<MessageHashId> globalMediaPendingLiveIds = new HashSet<>();
    private Runnable globalMediaLiveMergeRunnable;
    private TextView globalMediaSyncLabel;
    private final HashSet<Long> globalMediaHeadCheckedDialogs = new HashSet<>();
    private final HashSet<Long> globalMediaHeadCompletedDialogs = new HashSet<>();
    private int globalMediaHistoryPagesRemaining = 200;
    private int globalMediaHistoryPagesCompleted;
    private boolean globalMediaLiveOverflow;
    private boolean globalMediaProgressLoaded;
    private boolean globalMediaProgressLoading;
    private boolean globalMediaPageRequestInFlight;
    private boolean globalMediaPageHasMore = true;
    private boolean globalMediaOlderHasMore = true;
    private boolean globalMediaNewerHasMore;
    private boolean globalMediaPageNewer;
    private boolean globalMediaPageReplace;
    private boolean globalMediaFailurePaused;
    private int globalMediaPageRequestToken;
    private int globalMediaPageCursorDate;
    private long globalMediaPageCursorDialogId;
    private int globalMediaPageCursorMessageId;
    private int globalMediaOlderCursorDate;
    private long globalMediaOlderCursorDialogId;
    private int globalMediaOlderCursorMessageId;
    private int globalMediaNewestCursorDate;
    private long globalMediaNewestCursorDialogId;
    private int globalMediaNewestCursorMessageId;
    private int globalMediaCoverageTargetDate;
    private int globalMediaEmptyPageAutoLoads;
    private boolean globalMediaCoverageWaiting;
    private boolean globalMediaViewerLoad;
    private boolean globalMediaForceHistory;
    private int globalMediaProgressRequestToken;
    private boolean globalMediaSnapshotActive;
    private boolean globalMediaHeadRefreshPending;
    private boolean globalMediaHeadRefreshRunning;
    private final HashMap<Long, Integer> globalMediaSnapshotHeadBoundaries = new HashMap<>();
    private final HashMap<Long, Integer> globalMediaSnapshotFloors = new HashMap<>();
    private final HashSet<Long> globalMediaSnapshotHistoryEnded = new HashSet<>();
    private final HashSet<String> globalMediaSnapshotPendingGroupKeys = new HashSet<>();
    private MessagesStorage.GlobalMediaPage globalMediaPendingPage;
    private int globalMediaPendingPageGeneration;
    private int globalMediaPendingPageToken;
    private Runnable globalMediaApplyPageRunnable;
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
            if (globalMediaSearchGeneration != -1) {
                return messages.size() + (hasMoreGlobalMediaPages() ? 1 : 0);
            }
            if (isCustomMediaFilterActive()) {
                return messages.size() + (endReached ? 0 : 1);
            }
            return totalCount;
        }

        @Override
        public boolean loadMore() {
            if (globalMediaSearchGeneration != -1) {
                if (rawMessages.size() >= GLOBAL_MEDIA_WINDOW_SIZE) {
                    return true;
                }
                globalMediaViewerLoad = true;
                resumeGlobalMediaWindow();
                return true;
            }
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
            return i >= 0 && i < messages.size() ? createFromInfoString(messages.get(i), 0) : "";
        }

        @Override
        public CharSequence getSubtitleFor(int i) {
            return i >= 0 && i < messages.size() ? LocaleController.formatDateAudio(messages.get(i).messageOwner.date, false) : "";
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
                globalMediaScrollState = newState;
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    globalMediaLoadRequestedForGesture = false;
                    globalMediaHistoryPagesRemaining = 200;
                    AndroidUtilities.hideKeyboard(parentActivity.getCurrentFocus());
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    maybePrefetchGlobalMedia();
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
                if (globalMediaSearchGeneration != -1 && visibleItemCount > 0
                        && lastVisibleItem >= totalItemCount - 20 && hasMoreGlobalMediaPages() && dy > 0) {
                    if (globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE) {
                        globalMediaFailurePaused = false;
                        globalMediaEmptyPageAutoLoads = 0;
                    }
                    maybePrefetchGlobalMedia();
                } else if (globalMediaSearchGeneration != -1 && globalMediaNewerHasMore && visibleItemCount > 0
                        && firstVisibleItem <= 10 && dy < 0
                        && (!isGlobalMediaRoundRunning() || globalMediaSnapshotActive)
                        && globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE
                        && !globalMediaLoadRequestedForGesture) {
                    globalMediaLoadRequestedForGesture = true;
                    globalMediaFailurePaused = false;
                    globalMediaCoverageWaiting = false;
                    globalMediaEmptyPageAutoLoads = 0;
                    AndroidUtilities.runOnUIThread(() -> requestGlobalMediaNewerPage(globalMediaSearchGeneration, false));
                } else if (globalMediaSearchGeneration == -1 && !isLoading && visibleItemCount > 0
                        && lastVisibleItem >= totalItemCount - 10 && !endReached) {
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
        emptyView.setOnClickListener(v -> {
            if (globalMediaSearchGeneration != -1 && globalMediaFailurePaused && !isGlobalMediaRoundRunning()) {
                globalMediaFailurePaused = false;
                globalMediaEmptyPageAutoLoads = 0;
                globalMediaCoverageWaiting = false;
                retryGlobalMediaSearch();
            }
        });
        emptyView.subtitle.setOnClickListener(v -> emptyView.performClick());
        addView(emptyView);
        recyclerListView.setEmptyView(emptyView);
        emptyView.setVisibility(View.GONE);
        globalMediaSyncLabel = new TextView(context);
        globalMediaSyncLabel.setText(getString(R.string.GlobalMediaSyncing));
        globalMediaSyncLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        globalMediaSyncLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        globalMediaSyncLabel.setGravity(Gravity.CENTER);
        globalMediaSyncLabel.setPadding(dp(12), dp(5), dp(12), dp(5));
        globalMediaSyncLabel.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        globalMediaSyncLabel.setVisibility(View.GONE);
        globalMediaSyncLabel.setOnClickListener(v -> {
            if (globalMediaSearchGeneration != -1 && globalMediaHistoryPagesRemaining <= 0
                    && !isGlobalMediaRoundRunning()) {
                globalMediaHistoryPagesRemaining = 200;
                globalMediaEmptyPageAutoLoads = 0;
                globalMediaFailurePaused = false;
                resumeGlobalMediaWindow();
            }
        });
        addView(globalMediaSyncLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT, 12, 0, 12, 12));
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
        globalMediaEmptyPageAutoLoads = 0;
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
            for (MessageObject messageObject : rawMessages) {
                if (globalMediaSearchGeneration == -1 || isInCurrentGlobalMediaDateRange(messageObject)) {
                    messages.add(messageObject);
                }
            }
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
            for (java.util.Map.Entry<String, ArrayList<MessageObject>> entry : globalMediaGroupContext.entrySet()) {
                ArrayList<MessageObject> group = groups.computeIfAbsent(entry.getKey(), unused -> new ArrayList<>());
                HashSet<MessageHashId> seen = new HashSet<>();
                for (MessageObject groupedMessage : group) {
                    seen.add(new MessageHashId(groupedMessage.getId(), groupedMessage.getDialogId()));
                }
                for (MessageObject contextMessage : entry.getValue()) {
                    MessageHashId key = new MessageHashId(contextMessage.getId(), contextMessage.getDialogId());
                    if (seen.add(key)) {
                        group.add(contextMessage);
                    }
                }
            }

            HashSet<String> pendingGroupKeys = new HashSet<>();
            if (globalMediaSearchGeneration != -1 && globalMediaSnapshotActive) {
                pendingGroupKeys.addAll(globalMediaSnapshotPendingGroupKeys);
            } else if (globalMediaSearchGeneration != -1 && globalMediaDialogSearches != null) {
                for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                    if (dialogSearch.pendingGroupKey != null) {
                        pendingGroupKeys.add(dialogSearch.pendingGroupKey);
                    }
                    if (dialogSearch.historyPendingGroupKey != null) {
                        pendingGroupKeys.add(dialogSearch.historyPendingGroupKey);
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
                    if (shouldShowStandaloneMedia(messageObject)
                            && (globalMediaSearchGeneration == -1 || isInCurrentGlobalMediaDateRange(messageObject))) {
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
                if (show && (globalMediaSearchGeneration == -1 || isInCurrentGlobalMediaDateRange(messageObject))) {
                    messages.add(messageObject);
                }
            }
        }

        if (currentSearchFilter != null
                && currentSearchFilter.filterType == FiltersView.FILTER_TYPE_MEDIA
                && TextUtils.isEmpty(currentSearchString)) {
            messages.sort(this::compareGlobalMediaMessages);
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
        if (globalMediaSearchGeneration == -1 && isCustomMediaFilterActive()
                && !isLoading && !endReached && messages.size() < columnsCount * 6) {
            AndroidUtilities.runOnUIThread(() -> search(currentSearchDialogId, currentSearchCommunityId, currentSearchMinDate,
                    currentSearchMaxDate, currentSearchFilter, currentIncludeFolder, lastMessagesSearchString, false));
        }
    }

    private static class GlobalMediaDialogSearch {
        final long dialogId;
        final TLRPC.InputPeer peer;
        MessagesStorage.GlobalMediaSearchProgress progress = new MessagesStorage.GlobalMediaSearchProgress();
        int offsetId;
        int count;
        int failedAttempts;
        int headBoundaryId;
        int catchupOffsetId;
        int catchupBoundaryId;
        int pendingHeadBoundaryId;
        long lastHeadSyncAt;
        boolean historyEndReached;
        boolean catchingUp;
        boolean hasSavedState;
        boolean progressLoaded;
        boolean refreshHead;
        boolean endReached;
        boolean failed;
        String pendingGroupKey;
        String historyPendingGroupKey;

        GlobalMediaDialogSearch(long dialogId, TLRPC.InputPeer peer) {
            this.dialogId = dialogId;
            this.peer = peer;
        }
    }

    private boolean isInCurrentGlobalMediaDateRange(MessageObject messageObject) {
        long date = messageObject.messageOwner.date * 1000L;
        return (currentSearchMinDate <= 0 || date >= currentSearchMinDate)
                && (currentSearchMaxDate <= 0 || date <= currentSearchMaxDate);
    }

    private int compareGlobalMediaMessages(MessageObject left, MessageObject right) {
        int dateComparison = Integer.compare(right.messageOwner.date, left.messageOwner.date);
        if (dateComparison != 0) {
            return dateComparison;
        }
        int dialogComparison = Long.compare(right.getDialogId(), left.getDialogId());
        if (dialogComparison != 0) {
            return dialogComparison;
        }
        return Integer.compare(right.getId(), left.getId());
    }

    private boolean isGlobalMediaWindowFull() {
        return rawMessages.size() >= GLOBAL_MEDIA_WINDOW_SIZE;
    }

    private boolean hasMoreGlobalMediaPages() {
        if (globalMediaSearchGeneration == -1 || !globalMediaProgressLoaded || globalMediaOlderHasMore
                || globalMediaPageRequestInFlight || globalMediaProgressLoading
                || globalMediaCoverageWaiting && !globalMediaHeadRefreshRunning) {
            return true;
        }
        int userMinDate = currentSearchMinDate > 0 ? (int) (currentSearchMinDate / 1000) : 0;
        if (globalMediaDialogSearches != null) {
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                if (!dialogSearch.progressLoaded) {
                    return true;
                }
                if (!dialogSearch.progress.historyEndReached) {
                    int floor = dialogSearch.progress.headFloorDate;
                    if (userMinDate <= 0 || floor <= 0 || floor >= userMinDate) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isGlobalMediaRoundRunning() {
        return globalMediaPendingPage != null || globalMediaPageRequestInFlight || globalMediaProgressLoading || globalMediaCoverageWaiting
                || globalMediaRequestsInFlight != 0 || globalMediaDialogBatch != null
                || globalMediaDispatchRunnable != null;
    }

    private ArrayList<Long> getGlobalMediaDialogIds() {
        HashSet<Long> ids = new HashSet<>(globalMediaPreviewDialogIds);
        if (globalMediaDialogSearches != null) {
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                ids.add(dialogSearch.dialogId);
            }
        }
        return new ArrayList<>(ids);
    }

    private MessagesStorage.GlobalMediaSearchProgress copyGlobalMediaProgress(MessagesStorage.GlobalMediaSearchProgress source) {
        MessagesStorage.GlobalMediaSearchProgress copy = new MessagesStorage.GlobalMediaSearchProgress();
        if (source != null) {
            copy.initialized = source.initialized;
            copy.historyOffsetId = source.historyOffsetId;
            copy.headBoundaryId = source.headBoundaryId;
            copy.headFloorDate = source.headFloorDate;
            copy.lastHeadSyncAt = source.lastHeadSyncAt;
            copy.historyEndReached = source.historyEndReached;
            copy.catchingUp = source.catchingUp;
            copy.catchupOffsetId = source.catchupOffsetId;
            copy.catchupBoundaryId = source.catchupBoundaryId;
            copy.pendingHeadBoundaryId = source.pendingHeadBoundaryId;
            copy.pendingGroupId = source.pendingGroupId;
        }
        return copy;
    }

    private void loadGlobalMediaProgress(int generation) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration || globalMediaProgressLoading
                || globalMediaDialogSearches == null) {
            return;
        }
        ArrayList<Long> idsToLoad = new ArrayList<>();
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            if (!dialogSearch.progressLoaded) {
                idsToLoad.add(dialogSearch.dialogId);
            }
        }
        if (idsToLoad.isEmpty()) {
            onGlobalMediaProgressReady(generation);
            return;
        }
        globalMediaProgressLoading = true;
        final int token = ++globalMediaProgressRequestToken;
        MessagesStorage.getInstance(globalMediaSearchAccount).loadGlobalMediaSearchProgress(idsToLoad, states -> {
            if (generation != requestIndex || generation != globalMediaSearchGeneration || token != globalMediaProgressRequestToken) {
                return;
            }
            globalMediaProgressLoading = false;
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                if (dialogSearch.progressLoaded) {
                    continue;
                }
                MessagesStorage.GlobalMediaSearchProgress loaded = states.get(dialogSearch.dialogId);
                if (loaded != null) {
                    dialogSearch.progress = loaded;
                } else {
                    dialogSearch.progress = new MessagesStorage.GlobalMediaSearchProgress();
                }
                MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
                long now = System.currentTimeMillis();
                boolean headIsStale = !progress.initialized || progress.lastHeadSyncAt <= 0
                        || now < progress.lastHeadSyncAt || now - progress.lastHeadSyncAt >= GLOBAL_MEDIA_HEAD_STALENESS_MS;
                dialogSearch.refreshHead = progress.initialized && headIsStale && !progress.catchingUp;
                globalMediaHeadCheckedDialogs.add(dialogSearch.dialogId);
                if (progress.initialized && !headIsStale && !progress.catchingUp) {
                    globalMediaHeadCompletedDialogs.add(dialogSearch.dialogId);
                }
                globalMediaHeadRefreshPending |= !progress.initialized || dialogSearch.refreshHead || progress.catchingUp;
                dialogSearch.progressLoaded = true;
                dialogSearch.failed = false;
                dialogSearch.historyEndReached = progress.historyEndReached;
                dialogSearch.historyPendingGroupKey = !progress.historyEndReached && progress.pendingGroupId != 0
                        ? dialogSearch.dialogId + ":" + progress.pendingGroupId : null;
                dialogSearch.pendingGroupKey = progress.catchingUp ? dialogSearch.historyPendingGroupKey : null;
                dialogSearch.endReached = progress.historyEndReached && !progress.catchingUp;
            }
            onGlobalMediaProgressReady(generation);
        });
    }

    private void onGlobalMediaProgressReady(int generation) {
        globalMediaProgressLoaded = true;
        globalMediaFailurePaused = false;
        if (!globalMediaSnapshotActive) {
            refreshGlobalMediaSnapshotBounds();
        }
        boolean dialogsReady = MessagesController.getInstance(globalMediaSearchAccount)
                .isServerDialogsEndReached(globalMediaSearchFolder);
        if (rawMessages.isEmpty()) {
            requestGlobalMediaDatabasePage(generation, false, true);
        } else if (globalMediaSnapshotActive || dialogsReady) {
            startGlobalMediaHeadRefresh(generation);
        }
        if (!dialogsReady) {
            continueGlobalMediaSearch(generation);
        }
        if (!globalMediaSnapshotActive && !dialogsReady) {
            startGlobalMediaDialogBatch(generation);
        }
    }

    private void refreshGlobalMediaSnapshotBounds() {
        globalMediaSnapshotHeadBoundaries.clear();
        globalMediaSnapshotFloors.clear();
        globalMediaSnapshotHistoryEnded.clear();
        globalMediaSnapshotPendingGroupKeys.clear();
        boolean canUseSnapshot = false;
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
            if (!dialogSearch.progressLoaded || !progress.initialized
                    || (!progress.historyEndReached && progress.headFloorDate <= 0)) {
                continue;
            }
            canUseSnapshot |= progress.headBoundaryId > 0;
            globalMediaSnapshotHeadBoundaries.put(dialogSearch.dialogId, progress.headBoundaryId);
            globalMediaSnapshotFloors.put(dialogSearch.dialogId, progress.headFloorDate);
            if (progress.historyEndReached) {
                globalMediaSnapshotHistoryEnded.add(dialogSearch.dialogId);
            } else if (progress.pendingGroupId != 0) {
                globalMediaSnapshotPendingGroupKeys.add(dialogSearch.dialogId + ":" + progress.pendingGroupId);
            }
        }
        globalMediaSnapshotActive = canUseSnapshot;
    }

    private void requestGlobalMediaDatabasePage(int generation, boolean newer, boolean replace) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration || !globalMediaProgressLoaded
                || globalMediaPageRequestInFlight || globalMediaPendingPage != null
                || globalMediaProgressLoading || !isAttachedToWindow()) {
            return;
        }
        if (globalMediaFailurePaused && !replace) {
            return;
        }
        globalMediaFailurePaused = false;
        globalMediaPageNewer = newer;
        globalMediaPageReplace = replace;
        if (!replace && !isGlobalMediaCoverageComplete(0) && globalMediaHistoryPagesRemaining > 0) {
            globalMediaCoverageWaiting = true;
            startGlobalMediaDialogBatch(generation);
            return;
        }
        int cursorDate = 0;
        long cursorDialogId = 0;
        int cursorMessageId = 0;
        if (!replace) {
            if (newer) {
                cursorDate = globalMediaNewestCursorDate;
                cursorDialogId = globalMediaNewestCursorDialogId;
                cursorMessageId = globalMediaNewestCursorMessageId;
                if (cursorDate == 0 && !rawMessages.isEmpty()) {
                    MessageObject cursor = rawMessages.get(0);
                    cursorDate = cursor.messageOwner.date;
                    cursorDialogId = cursor.getDialogId();
                    cursorMessageId = cursor.getId();
                }
            } else {
                cursorDate = globalMediaOlderCursorDate;
                cursorDialogId = globalMediaOlderCursorDialogId;
                cursorMessageId = globalMediaOlderCursorMessageId;
                if (cursorDate == 0 && !rawMessages.isEmpty()) {
                    MessageObject cursor = rawMessages.get(rawMessages.size() - 1);
                    cursorDate = cursor.messageOwner.date;
                    cursorDialogId = cursor.getDialogId();
                    cursorMessageId = cursor.getId();
                }
            }
        }
        globalMediaPageCursorDate = cursorDate;
        globalMediaPageCursorDialogId = cursorDialogId;
        globalMediaPageCursorMessageId = cursorMessageId;
        globalMediaPageRequestInFlight = true;
        isLoading = true;
        if (messages.isEmpty()) {
            emptyView.showProgress(true, false);
        }
        final int token = ++globalMediaPageRequestToken;
        // Cached and preview rows are deliberately visible before every peer has
        // been covered. Coverage still governs deeper network history scanning.
        int minDate = currentSearchMinDate > 0 ? (int) (currentSearchMinDate / 1000) : 0;
        MessagesStorage.getInstance(globalMediaSearchAccount).loadGlobalMediaPage(
                getGlobalMediaDialogIds(), MediaDataController.MEDIA_PHOTOVIDEO,
                minDate,
                currentSearchMaxDate > 0 ? (int) (currentSearchMaxDate / 1000) : 0,
                GLOBAL_MEDIA_REFRESH_BATCH_SIZE, cursorDate, cursorDialogId, cursorMessageId, newer, null,
                page -> onGlobalMediaDatabasePage(generation, token, page));
    }

    private void onGlobalMediaDatabasePage(int generation, int token, MessagesStorage.GlobalMediaPage page) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration || token != globalMediaPageRequestToken) {
            return;
        }
        globalMediaPageRequestInFlight = false;
        if (page == null || page.failed) {
            globalMediaLastErrorCode = "LOCAL_MEDIA_READ_FAILED";
            globalMediaFailurePaused = true;
            globalMediaCoverageWaiting = false;
            isLoading = false;
            endReached = false;
            showGlobalMediaRetry(true);
            FileLog.e("Global media database page failed; keeping the current local cursor.");
            return;
        }
        MessagesController controller = MessagesController.getInstance(globalMediaSearchAccount);
        controller.putUsers(page.users, true);
        controller.putChats(page.chats, true);
        globalMediaPageHasMore = page.hasMore;
        if (globalMediaPageNewer) {
            globalMediaNewerHasMore = page.hasMore;
        } else {
            globalMediaOlderHasMore = page.hasMore;
        }
        globalMediaPendingPage = page;
        globalMediaPendingPageGeneration = generation;
        globalMediaPendingPageToken = token;
        scheduleGlobalMediaPageApply();
    }

    private void scheduleGlobalMediaPageApply() {
        if (globalMediaPendingPage == null || globalMediaApplyPageRunnable != null) {
            return;
        }
        globalMediaApplyPageRunnable = () -> {
            if (globalMediaPendingPage == null) {
                globalMediaApplyPageRunnable = null;
                return;
            }
            if (globalMediaPendingPageGeneration != requestIndex
                    || globalMediaPendingPageGeneration != globalMediaSearchGeneration
                    || globalMediaPendingPageToken != globalMediaPageRequestToken) {
                globalMediaPendingPage = null;
                globalMediaApplyPageRunnable = null;
                return;
            }
            if (globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE
                    || PhotoViewer.getInstance().isVisible() && !globalMediaViewerLoad) {
                AndroidUtilities.runOnUIThread(globalMediaApplyPageRunnable, 120);
                return;
            }
            MessagesStorage.GlobalMediaPage page = globalMediaPendingPage;
            int generation = globalMediaPendingPageGeneration;
            globalMediaPendingPage = null;
            globalMediaApplyPageRunnable = null;
            applyGlobalMediaDatabasePage(generation, page);
        };
        AndroidUtilities.runOnUIThread(globalMediaApplyPageRunnable, 0);
    }

    private void applyGlobalMediaDatabasePage(int generation, MessagesStorage.GlobalMediaPage page) {
        int previousItemCount = adapter == null ? 0 : adapter.getItemCount();
        globalMediaCoverageWaiting = false;
        if (!PhotoViewer.getInstance().isVisible() && globalMediaWindowAnchorMessageId == 0) {
            int firstVisibleRow = layoutManager.findFirstVisibleItemPosition();
            if (firstVisibleRow >= 0 && !messages.isEmpty()) {
                MessageObject anchor = messages.get(Math.min(messages.size() - 1, firstVisibleRow * columnsCount));
                globalMediaWindowAnchorDialogId = anchor.getDialogId();
                globalMediaWindowAnchorMessageId = anchor.getId();
                View anchorView = layoutManager.findViewByPosition(firstVisibleRow);
                globalMediaWindowAnchorTop = anchorView == null ? 0
                        : layoutManager.getDecoratedTop(anchorView) - recyclerListView.getPaddingTop();
            }
        }
        if (globalMediaPageReplace) {
            rawMessages.clear();
            globalMediaMessageIds.clear();
            globalMediaGroupContext.clear();
            globalMediaOlderCursorDate = 0;
            globalMediaOlderCursorDialogId = 0;
            globalMediaOlderCursorMessageId = 0;
            globalMediaNewestCursorDate = 0;
            globalMediaNewestCursorDialogId = 0;
            globalMediaNewestCursorMessageId = 0;
            globalMediaOlderHasMore = true;
            globalMediaNewerHasMore = false;
        }
        if (!page.messages.isEmpty()) {
            if (globalMediaPageNewer) {
                globalMediaNewestCursorDate = page.cursorDate;
                globalMediaNewestCursorDialogId = page.cursorDialogId;
                globalMediaNewestCursorMessageId = page.cursorMessageId;
            } else {
                globalMediaOlderCursorDate = page.cursorDate;
                globalMediaOlderCursorDialogId = page.cursorDialogId;
                globalMediaOlderCursorMessageId = page.cursorMessageId;
                globalMediaOlderHasMore = page.hasMore;
                if (globalMediaNewestCursorDate == 0) {
                    TLRPC.Message newest = page.messages.get(0);
                    globalMediaNewestCursorDate = newest.date;
                    globalMediaNewestCursorDialogId = newest.dialog_id;
                    globalMediaNewestCursorMessageId = newest.id;
                }
            }
        }
        for (TLRPC.Message message : page.messages) {
            if (message == null) {
                continue;
            }
            if (message.dialog_id == 0) {
                continue;
            }
            MessageObject messageObject = new MessageObject(globalMediaSearchAccount, message, false, true);
            if (!isInCurrentGlobalMediaDateRange(messageObject)) {
                continue;
            }
            MessageHashId key = new MessageHashId(messageObject.getId(), messageObject.getDialogId());
            if (globalMediaMessageIds.add(key)) {
                messageObject.setQuery("");
                rawMessages.add(messageObject);
            }
        }
        mergeGlobalMediaGroupContext(page.groupMessages);
        rawMessages.sort(this::compareGlobalMediaMessages);
        if (rawMessages.size() > GLOBAL_MEDIA_WINDOW_SIZE && !PhotoViewer.getInstance().isVisible()) {
            trimGlobalMediaWindow(false);
        }
        pruneGlobalMediaGroupContext();
        isLoading = false;
        endReached = !hasMoreGlobalMediaPages();
        updateGlobalMediaTotalCount();
        updateGlobalMediaResults(generation, previousItemCount);
        if (messages.isEmpty() && !globalMediaPreviewRequested) {
            requestGlobalMediaPreview(generation);
        }
        if (messages.isEmpty() && endReached && !globalMediaUnavailableDialogs.isEmpty()) {
            globalMediaFailurePaused = true;
            globalMediaLastErrorCode = "MEDIA_CHATS_UNAVAILABLE";
            showGlobalMediaRetry(true);
        } else if (messages.size() < columnsCount * 6 && !endReached
                && globalMediaEmptyPageAutoLoads < GLOBAL_MEDIA_EMPTY_PAGE_AUTO_LOAD_LIMIT) {
            globalMediaEmptyPageAutoLoads++;
            AndroidUtilities.runOnUIThread(() -> {
                if (generation == requestIndex && generation == globalMediaSearchGeneration
                        && (!isGlobalMediaRoundRunning() || globalMediaSnapshotActive)) {
                    resumeGlobalMediaWindow();
                }
            }, GLOBAL_MEDIA_REQUEST_INTERVAL_MS);
        } else if (messages.isEmpty() && !endReached && globalMediaEmptyPageAutoLoads >= GLOBAL_MEDIA_EMPTY_PAGE_AUTO_LOAD_LIMIT) {
            globalMediaFailurePaused = true;
            showGlobalMediaRetry(false);
        }
        if (globalMediaSnapshotActive && globalMediaHeadRefreshPending) {
            startGlobalMediaHeadRefresh(generation);
        }
        showGlobalMediaUnavailableNotice();
        if (globalMediaDiscoveryPending) {
            continueGlobalMediaSearch(generation);
        }
        updateGlobalMediaSyncLabel();
        maybePrefetchGlobalMedia();
    }

    private int getGlobalMediaCoverageTargetDate() {
        int target = rawMessages.isEmpty() ? 0 : rawMessages.get(rawMessages.size() - 1).messageOwner.date;
        int userMin = currentSearchMinDate > 0 ? (int) (currentSearchMinDate / 1000) : 0;
        return Math.max(target, userMin);
    }

    private boolean isGlobalMediaCoverageComplete(int ignoredTargetDate) {
        if (!globalMediaProgressLoaded || globalMediaDialogSearches == null) {
            return false;
        }
        int targetDate = getGlobalMediaCoverageTargetDate();
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
            if (!dialogSearch.progressLoaded || !progress.initialized || progress.catchingUp) {
                return false;
            }
            if (!progress.historyEndReached && progress.headFloorDate <= 0) {
                return false;
            }
            // A cached/official preview is not continuous coverage. Include the
            // whole boundary second (and albums) before declaring this window covered.
            if (!progress.historyEndReached && targetDate > 0 && progress.headFloorDate >= targetDate) {
                return false;
            }
        }
        return true;
    }

    private int getGlobalMediaEffectiveMinDate() {
        int userMinDate = currentSearchMinDate > 0 ? (int) (currentSearchMinDate / 1000) : 0;
        int frontier = 0;
        if (globalMediaSnapshotActive) {
            for (Long dialogId : globalMediaSnapshotFloors.keySet()) {
                if (globalMediaSnapshotHistoryEnded.contains(dialogId)) {
                    continue;
                }
                int floor = globalMediaSnapshotFloors.get(dialogId);
                if (floor <= 0) {
                    return userMinDate;
                }
                frontier = Math.max(frontier, floor);
            }
            if (frontier > 0 && frontier < Integer.MAX_VALUE) {
                frontier++;
            }
            return Math.max(userMinDate, frontier);
        }
        if (globalMediaDialogSearches != null) {
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
                if (progress.historyEndReached) {
                    continue;
                }
                if (!progress.initialized || progress.catchingUp || progress.headFloorDate <= 0) {
                    return userMinDate;
                }
                frontier = Math.max(frontier, progress.headFloorDate);
            }
        }
        if (frontier > 0 && frontier < Integer.MAX_VALUE) {
            frontier++;
        }
        return Math.max(userMinDate, frontier);
    }

    private boolean mergeGlobalMediaGroupContext(ArrayList<TLRPC.Message> groupMessages) {
        if (groupMessages == null || groupMessages.isEmpty()) {
            return false;
        }
        HashSet<String> visibleGroupKeys = new HashSet<>();
        for (MessageObject messageObject : rawMessages) {
            String key = getMediaGroupKey(messageObject);
            if (key != null) {
                visibleGroupKeys.add(key);
            }
        }
        HashMap<String, ArrayList<MessageObject>> incoming = new HashMap<>();
        for (TLRPC.Message message : groupMessages) {
            if (message == null || message.dialog_id == 0 || message.grouped_id == 0) {
                continue;
            }
            String key = message.dialog_id + ":" + message.grouped_id;
            if (!visibleGroupKeys.contains(key)) {
                continue;
            }
            ArrayList<MessageObject> group = incoming.computeIfAbsent(key, unused -> new ArrayList<>());
            group.add(new MessageObject(globalMediaSearchAccount, message, false, true));
        }
        boolean changed = false;
        for (String key : incoming.keySet()) {
            ArrayList<MessageObject> all = globalMediaGroupContext.computeIfAbsent(key, unused -> new ArrayList<>());
            HashSet<MessageHashId> seen = new HashSet<>();
            for (MessageObject item : all) {
                seen.add(new MessageHashId(item.getId(), item.getDialogId()));
            }
            for (MessageObject item : incoming.get(key)) {
                MessageHashId hash = new MessageHashId(item.getId(), item.getDialogId());
                if (seen.add(hash)) {
                    all.add(item);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void pruneGlobalMediaGroupContext() {
        if (globalMediaGroupContext.isEmpty()) {
            return;
        }
        HashSet<String> liveGroupKeys = new HashSet<>();
        for (MessageObject messageObject : rawMessages) {
            String key = getMediaGroupKey(messageObject);
            if (key != null) {
                liveGroupKeys.add(key);
            }
        }
        globalMediaGroupContext.keySet().removeIf(key -> !liveGroupKeys.contains(key));
    }

    private void showGlobalMediaRetry(boolean failed) {
        if (!messages.isEmpty()) {
            return;
        }
        // Strings are resolved by LocaleController; TextView's resource overload
        // cannot read the generated localization IDs used by this project.
        String title = getString(failed ? R.string.ErrorOccurred : R.string.SearchEmptyViewTitle2);
        if (failed && !TextUtils.isEmpty(globalMediaLastErrorCode)) {
            title += "\n" + globalMediaLastErrorCode;
        }
        emptyView.title.setText(title);
        emptyView.subtitle.setVisibility(View.VISIBLE);
        emptyView.subtitle.setText(getString(R.string.Retry));
        emptyView.showProgress(false, false);
        emptyView.setVisibility(View.VISIBLE);
    }

    private void retryGlobalMediaSearch() {
        globalMediaLastErrorCode = null;
        if (!globalMediaUnavailableDialogs.isEmpty()) {
            for (GlobalMediaDialogSearch dialogSearch : globalMediaUnavailableDialogs) {
                MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
                if (progress.initialized && !progress.catchingUp) {
                    progress.catchingUp = true;
                    progress.catchupOffsetId = 0;
                    progress.catchupBoundaryId = progress.headBoundaryId;
                    progress.pendingHeadBoundaryId = 0;
                }
                globalMediaDialogSearches.add(dialogSearch);
            }
            globalMediaUnavailableDialogs.clear();
            globalMediaUnavailableNoticeShown = false;
            globalMediaSnapshotActive = false;
        }
        // A failed/empty page can have no scroll range or older cursor. Explicit
        // retry must still be able to request it, even when endReached was true.
        requestGlobalMediaDatabasePage(globalMediaSearchGeneration, false, messages.isEmpty());
    }

    private static boolean isGlobalMediaPeerUnavailable(String error) {
        return "CHANNEL_PRIVATE".equals(error) || "CHANNEL_INVALID".equals(error)
                || "CHAT_ADMIN_REQUIRED".equals(error) || "CHAT_ID_INVALID".equals(error)
                || "INPUT_USER_DEACTIVATED".equals(error) || "PEER_ID_INVALID".equals(error)
                || "PEER_ID_NOT_SUPPORTED".equals(error) || "USER_ID_INVALID".equals(error);
    }

    private void skipUnavailableGlobalMediaDialog(int generation, GlobalMediaDialogSearch dialogSearch) {
        // Exclude only for this search. Do not persist a false history end or
        // advance the checkpoint: retry/reopening can recover access later.
        globalMediaDialogSearches.remove(dialogSearch);
        globalMediaUnavailableDialogs.add(dialogSearch);
        globalMediaSnapshotHeadBoundaries.remove(dialogSearch.dialogId);
        globalMediaSnapshotFloors.remove(dialogSearch.dialogId);
        globalMediaSnapshotHistoryEnded.remove(dialogSearch.dialogId);
        finishGlobalMediaNetworkPage(generation, true);
    }

    private void showGlobalMediaUnavailableNotice() {
        if (!globalMediaUnavailableNoticeShown && !globalMediaUnavailableDialogs.isEmpty()
                && !messages.isEmpty() && isAttachedToWindow()) {
            globalMediaUnavailableNoticeShown = true;
            BulletinFactory.of(parentFragment).createSimpleBulletin(R.raw.error,
                    getString(R.string.GlobalMediaUnavailableChats)).show();
        }
    }

    private void onGlobalMediaSyncRoundFinished(int generation, boolean success) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration) {
            return;
        }
        boolean historyRequestedDuringRefresh = globalMediaHeadRefreshRunning && globalMediaCoverageWaiting;
        globalMediaCoverageWaiting = false;
        globalMediaRequestsInFlight = 0;
        if (!success) {
            if (globalMediaHeadRefreshRunning && globalMediaSnapshotActive) {
                globalMediaHeadRefreshRunning = false;
                globalMediaHeadRefreshPending = false;
                return;
            }
            globalMediaFailurePaused = true;
            isLoading = false;
            endReached = false;
            showGlobalMediaRetry(true);
            return;
        }
        if (!globalMediaSnapshotActive) {
            refreshGlobalMediaSnapshotBounds();
        } else if (globalMediaHeadRefreshRunning) {
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                if (dialogSearch.progress.catchingUp) {
                    globalMediaCoverageWaiting = historyRequestedDuringRefresh;
                    startGlobalMediaDialogBatch(generation);
                    return;
                }
            }
            globalMediaHeadRefreshRunning = false;
            globalMediaHeadRefreshPending = false;
            isLoading = false;
            if (globalMediaHistoryPagesRemaining > 0
                    && (historyRequestedDuringRefresh || !isGlobalMediaCoverageComplete(0))) {
                globalMediaCoverageWaiting = true;
                globalMediaPageNewer = false;
                globalMediaPageReplace = false;
                startGlobalMediaDialogBatch(generation);
            }
            if (globalMediaDiscoveryPending) {
                continueGlobalMediaSearch(generation);
            }
            updateGlobalMediaSyncLabel();
            maybePrefetchGlobalMedia();
            return;
        } else {
            refreshGlobalMediaSnapshotFloors();
        }
        boolean headsComplete = true;
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            headsComplete &= dialogSearch.progress.initialized && !dialogSearch.progress.catchingUp
                    && !dialogSearch.refreshHead;
        }
        if (headsComplete) {
            globalMediaHeadRefreshPending = false;
        }
        if (!isGlobalMediaCoverageComplete(0) && globalMediaHistoryPagesRemaining > 0) {
            globalMediaCoverageWaiting = true;
            startGlobalMediaDialogBatch(generation);
            return;
        }
        globalMediaFailurePaused = false;
        requestGlobalMediaDatabasePage(generation, globalMediaPageNewer, globalMediaPageReplace && rawMessages.isEmpty());
    }

    private void startGlobalMediaHeadRefresh(int generation) {
        if (!globalMediaSnapshotActive || !globalMediaHeadRefreshPending || globalMediaHeadRefreshRunning
                || generation != requestIndex || generation != globalMediaSearchGeneration) {
            return;
        }
        boolean hasHeadWork = false;
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
            if (dialogSearch.refreshHead && progress.initialized && !progress.catchingUp) {
                progress.catchingUp = true;
                progress.catchupOffsetId = 0;
                progress.catchupBoundaryId = progress.headBoundaryId;
                progress.pendingHeadBoundaryId = 0;
                dialogSearch.pendingGroupKey = null;
                hasHeadWork = true;
            } else if (!progress.initialized || progress.catchingUp) {
                hasHeadWork = true;
            }
            dialogSearch.refreshHead = false;
        }
        globalMediaHeadRefreshPending = false;
        if (!hasHeadWork) {
            return;
        }
        globalMediaHeadRefreshRunning = true;
        globalMediaCoverageWaiting = false;
        startGlobalMediaDialogBatch(generation);
    }

    private void refreshGlobalMediaSnapshotFloors() {
        if (!globalMediaSnapshotActive || globalMediaDialogSearches == null) {
            return;
        }
        globalMediaSnapshotPendingGroupKeys.clear();
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            if (!globalMediaSnapshotHeadBoundaries.containsKey(dialogSearch.dialogId)) {
                continue;
            }
            MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
            if (progress.historyEndReached) {
                globalMediaSnapshotHistoryEnded.add(dialogSearch.dialogId);
            } else if (progress.pendingGroupId != 0) {
                globalMediaSnapshotPendingGroupKeys.add(dialogSearch.dialogId + ":" + progress.pendingGroupId);
            }
            if (progress.headFloorDate > 0) {
                Integer oldFloor = globalMediaSnapshotFloors.get(dialogSearch.dialogId);
                globalMediaSnapshotFloors.put(dialogSearch.dialogId,
                        oldFloor == null || oldFloor <= 0 ? progress.headFloorDate : Math.min(oldFloor, progress.headFloorDate));
            }
        }
    }

    private void trimGlobalMediaWindow(boolean discardNewestPage) {
        if (PhotoViewer.getInstance().isVisible()) {
            return;
        }
        if (rawMessages.size() <= GLOBAL_MEDIA_WINDOW_SIZE && !discardNewestPage) {
            return;
        }
        rawMessages.sort(this::compareGlobalMediaMessages);
        if (discardNewestPage) {
            int removeCount = Math.min(GLOBAL_MEDIA_WINDOW_ADVANCE, rawMessages.size());
            String boundaryGroup = removeCount > 0 ? getMediaGroupKey(rawMessages.get(removeCount - 1)) : null;
            while (boundaryGroup != null && removeCount < rawMessages.size()
                    && boundaryGroup.equals(getMediaGroupKey(rawMessages.get(removeCount)))) {
                removeCount++;
            }
            rawMessages.subList(0, removeCount).clear();
            if (!rawMessages.isEmpty()) {
                MessageObject newest = rawMessages.get(0);
                globalMediaNewestCursorDate = newest.messageOwner.date;
                globalMediaNewestCursorDialogId = newest.getDialogId();
                globalMediaNewestCursorMessageId = newest.getId();
                globalMediaNewerHasMore = true;
            }
            globalMediaResultsDirty = true;
        }
        if (rawMessages.size() > GLOBAL_MEDIA_WINDOW_SIZE) {
            int windowStart = 0;
            if (globalMediaWindowAnchorMessageId != 0) {
                int anchorIndex = findGlobalMediaMessageIndex(globalMediaWindowAnchorDialogId, globalMediaWindowAnchorMessageId);
                if (anchorIndex >= 0) {
                    windowStart = Math.max(0, Math.min(anchorIndex - GLOBAL_MEDIA_WINDOW_ADVANCE,
                            rawMessages.size() - GLOBAL_MEDIA_WINDOW_SIZE));
                }
            }
            int windowEnd = windowStart + GLOBAL_MEDIA_WINDOW_SIZE;
            if (windowStart > 0) {
                String firstGroup = getMediaGroupKey(rawMessages.get(windowStart));
                while (firstGroup != null && windowStart > 0
                        && firstGroup.equals(getMediaGroupKey(rawMessages.get(windowStart - 1)))) {
                    windowStart--;
                }
            }
            // Preserve the column of every retained thumbnail. Raw-message
            // boundaries need not be visible row boundaries after filtering.
            // Album filter context is retained separately by globalMediaGroupContext.
            windowStart = alignGlobalMediaWindowStart(windowStart);
            windowEnd = windowStart + GLOBAL_MEDIA_WINDOW_SIZE;
            if (windowEnd < rawMessages.size()) {
                String lastGroup = getMediaGroupKey(rawMessages.get(windowEnd - 1));
                while (lastGroup != null && windowEnd < rawMessages.size()
                        && lastGroup.equals(getMediaGroupKey(rawMessages.get(windowEnd)))) {
                    windowEnd++;
                }
            }
            if (windowEnd < rawMessages.size()) {
                MessageObject retainedOldest = rawMessages.get(windowEnd - 1);
                globalMediaOlderCursorDate = retainedOldest.messageOwner.date;
                globalMediaOlderCursorDialogId = retainedOldest.getDialogId();
                globalMediaOlderCursorMessageId = retainedOldest.getId();
                globalMediaOlderHasMore = true;
                globalMediaPageHasMore = true;
                rawMessages.subList(windowEnd, rawMessages.size()).clear();
            }
            if (windowStart > 0) {
                MessageObject retainedNewest = rawMessages.get(windowStart);
                globalMediaNewestCursorDate = retainedNewest.messageOwner.date;
                globalMediaNewestCursorDialogId = retainedNewest.getDialogId();
                globalMediaNewestCursorMessageId = retainedNewest.getId();
                globalMediaNewerHasMore = true;
                rawMessages.subList(0, windowStart).clear();
            }
        }
        globalMediaMessageIds.clear();
        for (MessageObject messageObject : rawMessages) {
            globalMediaMessageIds.add(new MessageHashId(messageObject.getId(), messageObject.getDialogId()));
        }
        pruneGlobalMediaGroupContext();
    }

    private int alignGlobalMediaWindowStart(int windowStart) {
        if (windowStart <= 0 || messages.isEmpty()) {
            return windowStart;
        }
        HashSet<MessageHashId> removed = new HashSet<>();
        for (int i = 0; i < windowStart; i++) {
            MessageObject message = rawMessages.get(i);
            removed.add(new MessageHashId(message.getId(), message.getDialogId()));
        }
        int removedVisible = 0;
        for (MessageObject message : messages) {
            if (!removed.contains(new MessageHashId(message.getId(), message.getDialogId()))) {
                break;
            }
            removedVisible++;
        }
        int partialRow = removedVisible % columnsCount;
        if (partialRow == 0) {
            return windowStart;
        }
        MessageObject rowStart = messages.get(removedVisible - partialRow);
        int rawIndex = findGlobalMediaMessageIndex(rowStart.getDialogId(), rowStart.getId());
        return rawIndex >= 0 ? rawIndex : windowStart;
    }

    private int findGlobalMediaMessageIndex(long dialogId, int messageId) {
        for (int i = 0; i < rawMessages.size(); i++) {
            MessageObject item = rawMessages.get(i);
            if (item.getDialogId() == dialogId && item.getId() == messageId) {
                return i;
            }
        }
        return -1;
    }

    private void flushGlobalMediaResults(int generation) {
        if (!globalMediaResultsDirty) {
            return;
        }
        globalMediaResultsDirty = false;
        globalMediaMessagesSinceRefresh = 0;
        scheduleGlobalMediaResultsUpdate(generation);
    }

    private void scheduleGlobalMediaResultsUpdate(int generation) {
        if (globalMediaResultsUpdateRunnable != null) {
            return;
        }
        globalMediaResultsUpdateRunnable = () -> {
            if (globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE
                    || PhotoViewer.getInstance().isVisible() && !globalMediaViewerLoad) {
                AndroidUtilities.runOnUIThread(globalMediaResultsUpdateRunnable, 150);
                return;
            }
            globalMediaResultsUpdateRunnable = null;
            updateGlobalMediaResults(generation);
        };
        AndroidUtilities.runOnUIThread(globalMediaResultsUpdateRunnable, 200);
    }

    private void maybePrefetchGlobalMedia() {
        if (globalMediaSearchGeneration == -1 || !isAttachedToWindow() || globalMediaFailurePaused
                || isGlobalMediaRoundRunning() || globalMediaWindowResumeScheduled
                || PhotoViewer.getInstance().isVisible() || messages.isEmpty()
                || globalMediaEmptyPageAutoLoads >= GLOBAL_MEDIA_EMPTY_PAGE_AUTO_LOAD_LIMIT
                || !hasMoreGlobalMediaPages()) {
            return;
        }
        int lastVisible = layoutManager.findLastVisibleItemPosition();
        if (lastVisible < 0 || lastVisible < adapter.getItemCount() - 20) {
            return;
        }
        int generation = globalMediaSearchGeneration;
        globalMediaWindowResumeScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            globalMediaWindowResumeScheduled = false;
            if (generation == requestIndex && generation == globalMediaSearchGeneration
                    && !globalMediaFailurePaused && !isGlobalMediaRoundRunning()) {
                globalMediaEmptyPageAutoLoads++;
                resumeGlobalMediaWindow();
            }
        }, GLOBAL_MEDIA_REQUEST_INTERVAL_MS);
    }

    private void resumeGlobalMediaWindow() {
        if (globalMediaSearchGeneration == -1 || !hasMoreGlobalMediaPages()
                || globalMediaPageRequestInFlight || globalMediaPendingPage != null || globalMediaProgressLoading
                || globalMediaCoverageWaiting && !globalMediaHeadRefreshRunning
                || globalMediaDialogBatch != null && !globalMediaHeadRefreshRunning) {
            return;
        }
        if (globalMediaFailurePaused) {
            return;
        }
        if (PhotoViewer.getInstance().isVisible() && rawMessages.size() >= GLOBAL_MEDIA_WINDOW_SIZE) {
            return;
        }
        // Keep the displayed window intact until the new page is applied at idle.
        // applyGlobalMediaDatabasePage captures the current stable-ID anchor first.
        if (globalMediaProgressLoaded && !isGlobalMediaCoverageComplete(0)) {
            globalMediaCoverageWaiting = true;
            startGlobalMediaDialogBatch(globalMediaSearchGeneration);
        } else if (globalMediaOlderHasMore || rawMessages.isEmpty()) {
            requestGlobalMediaDatabasePage(globalMediaSearchGeneration, false, false);
        } else {
            globalMediaCoverageWaiting = true;
            startGlobalMediaDialogBatch(globalMediaSearchGeneration);
        }
    }

    private void requestGlobalMediaNewerPage(int generation, boolean replace) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration || !globalMediaNewerHasMore
                || globalMediaPageRequestInFlight || globalMediaProgressLoading
                || globalMediaCoverageWaiting && !globalMediaSnapshotActive) {
            return;
        }
        requestGlobalMediaDatabasePage(generation, true, replace);
    }

    private void resetGlobalMediaDialogSearch() {
        if (globalMediaDispatchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaDispatchRunnable);
            globalMediaDispatchRunnable = null;
        }
        for (int i = 0; i < globalMediaRequestIds.size(); i++) {
            ConnectionsManager.getInstance(globalMediaSearchAccount).cancelRequest(globalMediaRequestIds.get(i), true);
        }
        globalMediaRequestIds.clear();
        globalMediaPageRequestToken++;
        globalMediaProgressRequestToken++;
        globalMediaPageRequestInFlight = false;
        globalMediaProgressLoading = false;
        globalMediaPendingPage = null;
        if (globalMediaApplyPageRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaApplyPageRunnable);
            globalMediaApplyPageRunnable = null;
        }
        globalMediaDialogSearches = null;
        globalMediaDialogBatch = null;
        globalMediaUnavailableDialogs.clear();
        globalMediaLastErrorCode = null;
        globalMediaUnavailableNoticeShown = false;
        globalMediaDialogIds.clear();
        globalMediaMessageIds.clear();
        globalMediaGroupContext.clear();
        globalMediaSnapshotHeadBoundaries.clear();
        globalMediaSnapshotFloors.clear();
        globalMediaSnapshotHistoryEnded.clear();
        globalMediaSnapshotPendingGroupKeys.clear();
        globalMediaSnapshotActive = false;
        globalMediaHeadRefreshPending = false;
        globalMediaHeadRefreshRunning = false;
        globalMediaProgressLoaded = false;
        globalMediaPageHasMore = true;
        globalMediaOlderHasMore = true;
        globalMediaNewerHasMore = false;
        globalMediaPageCursorDate = 0;
        globalMediaPageCursorDialogId = 0;
        globalMediaPageCursorMessageId = 0;
        globalMediaOlderCursorDate = 0;
        globalMediaOlderCursorDialogId = 0;
        globalMediaOlderCursorMessageId = 0;
        globalMediaNewestCursorDate = 0;
        globalMediaNewestCursorDialogId = 0;
        globalMediaNewestCursorMessageId = 0;
        globalMediaFailurePaused = false;
        globalMediaCoverageWaiting = false;
        globalMediaEmptyPageAutoLoads = 0;
        globalMediaViewerLoad = false;
        globalMediaWindowResumeScheduled = false;
        globalMediaLoadRequestedForGesture = false;
        globalMediaResultsDirty = false;
        globalMediaMessagesSinceRefresh = 0;
        globalMediaWindowAnchorMessage = null;
        globalMediaWindowAnchorDialogId = 0;
        globalMediaWindowAnchorMessageId = 0;
        globalMediaWindowAnchorTop = 0;
        if (globalMediaResultsUpdateRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaResultsUpdateRunnable);
            globalMediaResultsUpdateRunnable = null;
        }
        globalMediaSearchGeneration = -1;
        globalMediaBatchCursor = 0;
        globalMediaBatchCompleted = 0;
        globalMediaRequestsInFlight = 0;
        globalMediaWaitingForDialogs = false;
        globalMediaCacheBootstrapStarted = false;
        globalMediaCacheBootstrapFinished = false;
        globalMediaDiscoveryPending = false;
        globalMediaPreviewRequested = false;
        globalMediaPreviewDialogIds.clear();
        globalMediaPendingLiveMessages.clear();
        globalMediaPendingLiveIds.clear();
        globalMediaHeadCheckedDialogs.clear();
        globalMediaHeadCompletedDialogs.clear();
        globalMediaHistoryPagesRemaining = 200;
        globalMediaHistoryPagesCompleted = 0;
        globalMediaLiveOverflow = false;
        if (globalMediaLiveMergeRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaLiveMergeRunnable);
            globalMediaLiveMergeRunnable = null;
        }
        updateGlobalMediaSyncLabel();
    }

    private void ensureGlobalMediaAdapter() {
        // Match SharedMediaLayout: paging must not run default move/change animations.
        if (recyclerListView.getItemAnimator() != null) {
            recyclerListView.setItemAnimator(null);
        }
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
        if (!globalMediaCacheBootstrapFinished) {
            if (!globalMediaCacheBootstrapStarted) {
                globalMediaCacheBootstrapStarted = true;
                globalMediaProgressLoading = true;
                MessagesStorage.getInstance(globalMediaSearchAccount).loadGlobalMediaCachedDialogs(globalMediaSearchFolder, cached -> {
                    if (generation != requestIndex || generation != globalMediaSearchGeneration) return;
                    globalMediaProgressLoading = false;
                    globalMediaCacheBootstrapFinished = true;
                    messagesController.putUsers(cached.users, true);
                    messagesController.putChats(cached.chats, true);
                    if (globalMediaDialogSearches == null) globalMediaDialogSearches = new ArrayList<>();
                    for (Long dialogId : cached.dialogIds) {
                        globalMediaPreviewDialogIds.add(dialogId);
                        TLRPC.InputPeer peer = messagesController.getInputPeer(dialogId);
                        if (peer != null && !(peer instanceof TLRPC.TL_inputPeerEmpty) && globalMediaDialogIds.add(dialogId)) {
                            globalMediaDialogSearches.add(new GlobalMediaDialogSearch(dialogId, peer));
                        }
                    }
                    if (globalMediaDialogSearches.isEmpty()) {
                        globalMediaProgressLoaded = true;
                        requestGlobalMediaDatabasePage(generation, false, true);
                        continueGlobalMediaSearch(generation);
                    } else {
                        globalMediaDiscoveryPending = true;
                        loadGlobalMediaProgress(generation);
                    }
                });
            }
            return;
        }
        if (!messagesController.isServerDialogsEndReached(globalMediaSearchFolder)) {
            globalMediaWaitingForDialogs = true;
            appendGlobalMediaDialogs(messagesController);
            updateGlobalMediaSyncLabel();
            if (!globalMediaProgressLoading && globalMediaDialogBatch == null
                    && globalMediaRequestsInFlight == 0 && !globalMediaPageRequestInFlight
                    && globalMediaPendingPage == null) {
                for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                    if (!dialogSearch.progressLoaded) {
                        loadGlobalMediaProgress(generation);
                        break;
                    }
                }
            }
            if (!messagesController.isLoadingDialogs(globalMediaSearchFolder)) {
                messagesController.loadDialogs(globalMediaSearchFolder, 0, 100, false);
            }
            return;
        }

        globalMediaWaitingForDialogs = false;
        updateGlobalMediaSyncLabel();
        appendGlobalMediaDialogs(messagesController);
        if (globalMediaPageRequestInFlight || globalMediaPendingPage != null || globalMediaProgressLoading
                || globalMediaDialogBatch != null || globalMediaHeadRefreshRunning) {
            globalMediaDiscoveryPending = true;
            return;
        }
        globalMediaDiscoveryPending = false;
        boolean allLoaded = true;
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            allLoaded &= dialogSearch.progressLoaded;
        }
        if (allLoaded) {
            globalMediaProgressLoaded = true;
            long now = System.currentTimeMillis();
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
                boolean stale = !progress.initialized || progress.lastHeadSyncAt <= 0
                        || now < progress.lastHeadSyncAt || now - progress.lastHeadSyncAt >= GLOBAL_MEDIA_HEAD_STALENESS_MS;
                if (globalMediaHeadCheckedDialogs.add(dialogSearch.dialogId)) {
                    dialogSearch.refreshHead = progress.initialized && stale && !progress.catchingUp;
                    globalMediaHeadRefreshPending |= !progress.initialized || dialogSearch.refreshHead || progress.catchingUp;
                }
            }
            onGlobalMediaProgressReady(generation);
        } else {
            globalMediaProgressLoaded = false;
            loadGlobalMediaProgress(generation);
        }
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
        if (generation != requestIndex || generation != globalMediaSearchGeneration || globalMediaDialogSearches == null
                || globalMediaDialogBatch != null || globalMediaRequestsInFlight != 0) {
            return;
        }
        globalMediaDialogBatch = new ArrayList<>();
        // First establish or repair every head. Once heads are continuous, only
        // advance the newest remaining frontier, not the already old channels.
        for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
            if (!dialogSearch.progress.initialized || dialogSearch.progress.catchingUp) {
                globalMediaDialogBatch.add(dialogSearch);
            }
        }
        if (globalMediaDialogBatch.isEmpty()) {
            int targetDate = getGlobalMediaCoverageTargetDate();
            int newestFloor = -1;
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                if (!dialogSearch.progress.historyEndReached && globalMediaHistoryPagesRemaining > 0
                        && (targetDate == 0 || dialogSearch.progress.headFloorDate >= targetDate)) {
                    newestFloor = Math.max(newestFloor, dialogSearch.progress.headFloorDate);
                }
            }
            for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                if (!dialogSearch.progress.historyEndReached && globalMediaHistoryPagesRemaining > 0
                        && dialogSearch.progress.headFloorDate == newestFloor) {
                    globalMediaDialogBatch.add(dialogSearch);
                }
            }
        }
        globalMediaBatchCursor = 0;
        globalMediaBatchCompleted = 0;
        if (globalMediaDialogBatch.isEmpty()) {
            globalMediaDialogBatch = null;
            onGlobalMediaSyncRoundFinished(generation, true);
            return;
        }
        isLoading = true;
        showGlobalMediaSyncProgress();
        dispatchGlobalMediaDialogSearches(generation);
    }

    private void showGlobalMediaSyncProgress() {
        updateGlobalMediaSyncLabel();
        if (messages.isEmpty() && globalMediaDialogBatch != null && isAttachedToWindow()) {
            emptyView.title.setText(getString(R.string.Loading));
            emptyView.subtitle.setVisibility(View.VISIBLE);
            emptyView.subtitle.setText(globalMediaBatchCompleted + " / " + globalMediaDialogBatch.size());
            emptyView.showProgress(false, false);
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    private void requestGlobalMediaPreview(int generation) {
        if (globalMediaPreviewRequested || generation != requestIndex || generation != globalMediaSearchGeneration) {
            return;
        }
        globalMediaPreviewRequested = true;
        TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
        request.q = "";
        request.limit = 20;
        request.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        request.community = MessagesController.getInstance(globalMediaSearchAccount).getInputChannel(0);
        request.offset_peer = new TLRPC.TL_inputPeerEmpty();
        request.flags |= 1;
        request.folder_id = globalMediaSearchFolder;
        final int[] requestIdHolder = new int[]{-1};
        int requestId = ConnectionsManager.getInstance(globalMediaSearchAccount).sendRequest(request,
                (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (requestIdHolder[0] >= 0) {
                        globalMediaRequestIds.remove((Integer) requestIdHolder[0]);
                    }
                    if (generation != requestIndex || generation != globalMediaSearchGeneration
                            || error != null || !(response instanceof TLRPC.messages_Messages)) {
                        return;
                    }
                    TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    MessagesController controller = MessagesController.getInstance(globalMediaSearchAccount);
                    controller.putUsers(result.users, false);
                    controller.putChats(result.chats, false);
                    MessagesStorage storage = MessagesStorage.getInstance(globalMediaSearchAccount);
                    storage.putUsersAndChats(result.users, result.chats, true, true);
                    HashMap<Long, ArrayList<TLRPC.Message>> byDialog = new HashMap<>();
                    for (TLRPC.Message message : result.messages) {
                        if (message == null || message.id <= 0) continue;
                        long dialogId = MessageObject.getPeerId(message.peer_id);
                        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) continue;
                        message.dialog_id = dialogId;
                        byDialog.computeIfAbsent(dialogId, ignored -> new ArrayList<>()).add(message);
                    }
                    ArrayList<Long> peers = new ArrayList<>(byDialog.keySet());
                    globalMediaPreviewDialogIds.addAll(peers);
                    storage.rememberGlobalMediaPreviewPeers(globalMediaSearchFolder, peers);
                    for (Long dialogId : peers) {
                        ArrayList<TLRPC.Message> peerMessages = byDialog.get(dialogId);
                        controller.removeDeletedMessagesFromArray(dialogId, peerMessages);
                        storage.putGlobalMediaSearchMessages(peerMessages, dialogId, null, stored -> {
                            if (stored && generation == requestIndex && generation == globalMediaSearchGeneration) {
                                storage.loadGlobalMediaStoredMessages(peerMessages,
                                        page -> queueGlobalMediaStoredPage(generation, page));
                            }
                        });
                    }
                }));
        requestIdHolder[0] = requestId;
        globalMediaRequestIds.add(requestId);
    }

    private void queueGlobalMediaStoredPage(int generation, MessagesStorage.GlobalMediaPage page) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration
                || page == null || page.failed) return;
        MessagesController controller = MessagesController.getInstance(globalMediaSearchAccount);
        controller.putUsers(page.users, true);
        controller.putChats(page.chats, true);
        queueGlobalMediaLiveMessages(generation, page.groupMessages);
    }

    private void queueGlobalMediaLiveMessages(int generation, ArrayList<TLRPC.Message> incoming) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration
                || incoming == null || incoming.isEmpty()) {
            return;
        }
        for (TLRPC.Message message : incoming) {
            if (message == null || message.dialog_id == 0 || message.id <= 0) continue;
            MessageHashId id = new MessageHashId(message.id, message.dialog_id);
            if (globalMediaPendingLiveIds.add(id)) {
                globalMediaPendingLiveMessages.add(message);
            }
        }
        if (globalMediaPendingLiveMessages.size() > GLOBAL_MEDIA_WINDOW_SIZE) {
            globalMediaLiveOverflow = true;
            globalMediaPendingLiveMessages.sort((left, right) -> {
                int date = Integer.compare(right.date, left.date);
                if (date != 0) return date;
                int dialog = Long.compare(right.dialog_id, left.dialog_id);
                return dialog != 0 ? dialog : Integer.compare(right.id, left.id);
            });
            globalMediaPendingLiveMessages.subList(GLOBAL_MEDIA_WINDOW_SIZE, globalMediaPendingLiveMessages.size()).clear();
            globalMediaPendingLiveIds.clear();
            for (TLRPC.Message message : globalMediaPendingLiveMessages) {
                globalMediaPendingLiveIds.add(new MessageHashId(message.id, message.dialog_id));
            }
        }
        if (globalMediaLiveMergeRunnable != null) return;
        globalMediaLiveMergeRunnable = () -> {
            if (generation != requestIndex || generation != globalMediaSearchGeneration) {
                globalMediaPendingLiveMessages.clear();
                globalMediaPendingLiveIds.clear();
                globalMediaLiveMergeRunnable = null;
                return;
            }
            if (globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE || PhotoViewer.getInstance().isVisible()
                    || globalMediaPendingPage != null || globalMediaPageRequestInFlight) {
                AndroidUtilities.runOnUIThread(globalMediaLiveMergeRunnable, 150);
                return;
            }
            globalMediaLiveMergeRunnable = null;
            ArrayList<TLRPC.Message> pending = new ArrayList<>(globalMediaPendingLiveMessages);
            globalMediaPendingLiveMessages.clear();
            globalMediaPendingLiveIds.clear();
            if (globalMediaLiveOverflow) {
                globalMediaLiveOverflow = false;
                int minDate = getGlobalMediaCoverageTargetDate();
                int maxDate = globalMediaNewerHasMore && !rawMessages.isEmpty()
                        ? rawMessages.get(0).messageOwner.date
                        : currentSearchMaxDate > 0 ? (int) (currentSearchMaxDate / 1000) : 0;
                // Dropped pending rows are durable, but may lie above the old DB
                // seek cursor. Re-read this window rather than losing that interval.
                MessagesStorage.getInstance(globalMediaSearchAccount).loadGlobalMediaPage(
                        getGlobalMediaDialogIds(), MediaDataController.MEDIA_PHOTOVIDEO,
                        minDate, maxDate, GLOBAL_MEDIA_WINDOW_SIZE, 0, 0, 0, false, null,
                        page -> queueGlobalMediaStoredPage(generation, page));
            }
            int previousItemCount = adapter == null ? 0 : adapter.getItemCount();
            MessageObject initialOldest = rawMessages.isEmpty() ? null : rawMessages.get(rawMessages.size() - 1);
            MessageObject initialNewest = rawMessages.isEmpty() ? null : rawMessages.get(0);
            boolean initialWindowFull = rawMessages.size() >= GLOBAL_MEDIA_WINDOW_SIZE;
            boolean changed = false;
            for (TLRPC.Message message : pending) {
                if (message == null || message.dialog_id == 0 || message.id <= 0) continue;
                MessageObject item = new MessageObject(globalMediaSearchAccount, message, false, true);
                if (!isInCurrentGlobalMediaDateRange(item)) continue;
                MessageHashId id = new MessageHashId(item.getId(), item.getDialogId());
                if (globalMediaMessageIds.contains(id)) continue;
                if (initialWindowFull && initialOldest != null
                        && compareGlobalMediaMessages(item, initialOldest) > 0) {
                    continue; // Persisted: the older seek page will find it when needed.
                }
                if (globalMediaNewerHasMore && initialNewest != null
                        && compareGlobalMediaMessages(item, initialNewest) < 0) {
                    continue; // Persisted: the newer seek page will find it when needed.
                }
                if (globalMediaMessageIds.add(id)) {
                    item.setQuery("");
                    rawMessages.add(item);
                    changed = true;
                }
            }
            boolean contextChanged = mergeGlobalMediaGroupContext(pending);
            if (changed || contextChanged) {
                rawMessages.sort(this::compareGlobalMediaMessages);
                updateGlobalMediaResults(generation, previousItemCount);
                // Live rows are not a contiguous seek page. Never move an older
                // cursor to their date, or intervening cached rows would be skipped.
                if (globalMediaOlderCursorDate == 0) {
                    requestGlobalMediaDatabasePage(generation, false, true);
                }
            }
            updateGlobalMediaSyncLabel();
        };
        AndroidUtilities.runOnUIThread(globalMediaLiveMergeRunnable, 250);
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
        if (dialogSearch.progress.initialized && !dialogSearch.progress.catchingUp
                && globalMediaHistoryPagesRemaining <= 0) {
            globalMediaDialogBatch = null;
            onGlobalMediaSyncRoundFinished(generation, true);
            return;
        }
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = MessagesController.getInstance(globalMediaSearchAccount).getInputPeer(dialogSearch.dialogId);
        if (request.peer == null || request.peer instanceof TLRPC.TL_inputPeerEmpty) {
            request.peer = dialogSearch.peer;
        }
        request.q = "";
        request.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        request.limit = GLOBAL_MEDIA_PAGE_SIZE;
        final MessagesStorage.GlobalMediaSearchProgress before = copyGlobalMediaProgress(dialogSearch.progress);
        final boolean headScan = !before.initialized || before.catchingUp;
        request.offset_id = headScan ? before.catchupOffsetId : before.historyOffsetId;
        // Coverage is account/dialog-wide. Date filters apply only to DB reads.

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
                if (error == null && response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    boolean pageEndReached = result.messages.isEmpty()
                            || (!result.inexact && result.messages.size() < GLOBAL_MEDIA_PAGE_SIZE);
                    int oldestMessageId = Integer.MAX_VALUE;
                    int newestMessageId = 0;
                    int oldestDate = Integer.MAX_VALUE;
                    long pendingGroupId = 0;
                    for (TLRPC.Message message : result.messages) {
                        if (message.id <= 0 || message.date <= 0) {
                            globalMediaLastErrorCode = "MEDIA_INVALID_MESSAGE";
                            finishGlobalMediaNetworkPage(generation, false);
                            return;
                        }
                        if (message.id < oldestMessageId) {
                            oldestMessageId = message.id;
                            pendingGroupId = message.grouped_id;
                        }
                        newestMessageId = Math.max(newestMessageId, message.id);
                        oldestDate = Math.min(oldestDate, message.date);
                        message.dialog_id = dialogSearch.dialogId;
                    }
                    if (result.messages.isEmpty()) {
                        oldestMessageId = 0;
                        oldestDate = 0;
                    } else if (request.offset_id > 0 && oldestMessageId >= request.offset_id) {
                        // A repeated page cannot establish any additional coverage.
                        globalMediaLastErrorCode = "MEDIA_CURSOR_NOT_ADVANCING";
                        finishGlobalMediaNetworkPage(generation, false);
                        return;
                    }
                    MessagesStorage.GlobalMediaSearchProgress after = copyGlobalMediaProgress(before);
                    if (headScan) {
                        if (request.offset_id == 0) {
                            after.pendingHeadBoundaryId = newestMessageId;
                        }
                        boolean initialHeadScan = !before.initialized;
                        boolean catchupComplete = initialHeadScan || pageEndReached
                                || oldestMessageId <= before.catchupBoundaryId;
                        if (catchupComplete) {
                            after.initialized = true;
                            after.headBoundaryId = after.pendingHeadBoundaryId;
                            after.lastHeadSyncAt = System.currentTimeMillis();
                            after.catchingUp = false;
                            after.catchupOffsetId = 0;
                            after.catchupBoundaryId = 0;
                            after.pendingHeadBoundaryId = 0;
                            if (initialHeadScan) {
                                after.historyOffsetId = oldestMessageId;
                                after.headFloorDate = oldestDate;
                                after.historyEndReached = pageEndReached;
                            } else if (pageEndReached) {
                                // A catch-up that reaches the actual end also covers all history.
                                after.historyEndReached = true;
                            }
                        } else {
                            after.catchingUp = true;
                            after.catchupOffsetId = oldestMessageId;
                        }
                    } else {
                        if (oldestMessageId != 0) {
                            after.historyOffsetId = oldestMessageId;
                            after.headFloorDate = before.headFloorDate <= 0 ? oldestDate : Math.min(before.headFloorDate, oldestDate);
                        }
                        after.historyEndReached = pageEndReached;
                    }
                    final String newPendingGroup = !after.historyEndReached && pendingGroupId != 0
                            ? dialogSearch.dialogId + ":" + pendingGroupId : null;
                    final boolean historyAdvanced = !headScan || !before.initialized;
                    if (historyAdvanced || after.historyEndReached) {
                        after.pendingGroupId = after.historyEndReached ? 0 : pendingGroupId;
                    }
                    MessagesController controller = MessagesController.getInstance(globalMediaSearchAccount);
                    controller.putUsers(result.users, false);
                    controller.putChats(result.chats, false);
                    controller.removeDeletedMessagesFromArray(dialogSearch.dialogId, result.messages);
                    MessagesStorage storage = MessagesStorage.getInstance(globalMediaSearchAccount);
                    storage.putUsersAndChats(result.users, result.chats, true, true);
                    storage.putGlobalMediaSearchMessages(result.messages, dialogSearch.dialogId, after, stored -> {
                        if (generation != requestIndex || generation != globalMediaSearchGeneration) {
                            return;
                        }
                        if (stored) {
                            if (headScan && !after.catchingUp) {
                                globalMediaHeadCompletedDialogs.add(dialogSearch.dialogId);
                            } else if (!headScan) {
                                globalMediaHistoryPagesRemaining--;
                                globalMediaHistoryPagesCompleted++;
                            }
                            dialogSearch.progress = after;
                            dialogSearch.progressLoaded = true;
                            dialogSearch.failed = false;
                            dialogSearch.failedAttempts = 0;
                            dialogSearch.endReached = after.historyEndReached && !after.catchingUp;
                            dialogSearch.historyEndReached = after.historyEndReached;
                            dialogSearch.count = Math.max(result.count, result.messages.size());
                            if (historyAdvanced || after.historyEndReached) {
                                dialogSearch.historyPendingGroupKey = newPendingGroup;
                            }
                            dialogSearch.pendingGroupKey = after.catchingUp ? newPendingGroup : dialogSearch.historyPendingGroupKey;
                            storage.loadGlobalMediaStoredMessages(result.messages,
                                    page -> queueGlobalMediaStoredPage(generation, page));
                        } else {
                            globalMediaLastErrorCode = "LOCAL_MEDIA_WRITE_FAILED";
                        }
                        finishGlobalMediaNetworkPage(generation, stored);
                    });
                } else {
                    if (error != null && error.text != null && (error.text.startsWith("FLOOD_WAIT_") || error.text.startsWith("FLOOD_PREMIUM_WAIT_"))) {
                        try {
                            int waitSeconds = Integer.parseInt(error.text.substring(error.text.lastIndexOf('_') + 1));
                            long retryAt = android.os.SystemClock.elapsedRealtime() + Math.max(1, waitSeconds) * 1000L;
                            globalMediaLastRequestTime = Math.max(globalMediaLastRequestTime, retryAt - GLOBAL_MEDIA_REQUEST_INTERVAL_MS);
                            globalMediaRequestsInFlight = 0;
                            globalMediaBatchCursor--;
                            dispatchGlobalMediaDialogSearches(generation);
                            return;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (error != null && isGlobalMediaPeerUnavailable(error.text)) {
                        skipUnavailableGlobalMediaDialog(generation, dialogSearch);
                        return;
                    }
                    globalMediaLastErrorCode = error == null ? "MEDIA_UNEXPECTED_RESPONSE"
                            : error.text != null && error.text.matches("[A-Z][A-Z0-9_]{0,79}")
                            ? error.text : "MEDIA_RPC_ERROR_" + error.code;
                    dialogSearch.failed = true;
                    FileLog.e("Global media sync paused: " + (error == null ? "unexpected response" : error.text));
                    finishGlobalMediaNetworkPage(generation, false);
                }
            }));
        requestIdHolder[0] = requestId;
        globalMediaRequestIds.add(requestId);
    }

    private void finishGlobalMediaNetworkPage(int generation, boolean success) {
        globalMediaRequestsInFlight = 0;
        if (!success) {
            globalMediaDialogBatch = null;
            onGlobalMediaSyncRoundFinished(generation, false);
            return;
        }
        globalMediaBatchCompleted++;
        showGlobalMediaSyncProgress();
        if (globalMediaDialogBatch == null || globalMediaBatchCompleted >= globalMediaDialogBatch.size()) {
            globalMediaDialogBatch = null;
            updateGlobalMediaSyncLabel();
            updateGlobalMediaTotalCount();
            onGlobalMediaSyncRoundFinished(generation, true);
        } else {
            dispatchGlobalMediaDialogSearches(generation);
        }
    }

    private void updateGlobalMediaSyncLabel() {
        if (globalMediaSyncLabel == null) return;
        boolean historyPaused = globalMediaSearchGeneration != -1 && globalMediaHistoryPagesRemaining <= 0
                && !isGlobalMediaCoverageComplete(0);
        boolean syncing = globalMediaSearchGeneration != -1 && (globalMediaWaitingForDialogs
                || globalMediaDialogBatch != null || globalMediaRequestsInFlight != 0
                || globalMediaHeadRefreshPending || globalMediaHeadRefreshRunning || globalMediaCoverageWaiting
                || historyPaused);
        globalMediaSyncLabel.setVisibility(syncing ? View.VISIBLE : View.GONE);
        if (syncing) {
            int total = globalMediaDialogSearches == null ? 0 : globalMediaDialogSearches.size();
            boolean heads = globalMediaHeadRefreshRunning || globalMediaHeadRefreshPending;
            int completed = Math.min(total, globalMediaHeadCompletedDialogs.size());
            if (!heads && globalMediaDialogSearches != null) {
                completed = 0;
                int target = getGlobalMediaCoverageTargetDate();
                for (GlobalMediaDialogSearch dialogSearch : globalMediaDialogSearches) {
                    MessagesStorage.GlobalMediaSearchProgress progress = dialogSearch.progress;
                    if (progress.initialized && !progress.catchingUp && (progress.historyEndReached
                            || progress.headFloorDate > 0 && (target == 0 || progress.headFloorDate < target))) {
                        completed++;
                    }
                }
            }
            String phase = getString(heads
                    ? R.string.GlobalMediaSyncHeads : R.string.GlobalMediaSyncHistory);
            globalMediaSyncLabel.setText(globalMediaWaitingForDialogs
                    ? getString(R.string.GlobalMediaSyncDiscovering)
                    : phase + " " + completed + " / " + total
                    + " · " + globalMediaHistoryPagesCompleted + " " + getString(R.string.GlobalMediaSyncPages)
                    + (historyPaused ? " · " + getString(R.string.GlobalMediaSyncContinue) : ""));
        }
    }

    private void updateGlobalMediaResults(int generation) {
        updateGlobalMediaResults(generation, -1);
    }

    private void updateGlobalMediaResults(int generation, int previousItemCount) {
        if (generation != requestIndex || generation != globalMediaSearchGeneration) {
            return;
        }
        if (globalMediaScrollState != RecyclerView.SCROLL_STATE_IDLE
                || PhotoViewer.getInstance().isVisible() && !globalMediaViewerLoad) {
            globalMediaResultsDirty = true;
            scheduleGlobalMediaResultsUpdate(generation);
            return;
        }
        boolean viewerAppend = PhotoViewer.getInstance().isVisible() && globalMediaViewerLoad;
        ArrayList<MessageObject> previousMessages = new ArrayList<>(messages);
        int anchorRow = layoutManager.findFirstVisibleItemPosition();
        long anchorDialogId = globalMediaWindowAnchorDialogId;
        int anchorMessageId = globalMediaWindowAnchorMessageId;
        int anchorTop = globalMediaWindowAnchorTop;
        if (anchorRow >= 0 && adapter == sharedPhotoVideoAdapter && !messages.isEmpty()
                && anchorMessageId == 0) {
            int anchorMessageIndex = Math.min(messages.size() - 1, anchorRow * columnsCount);
            MessageObject anchorMessage = messages.get(anchorMessageIndex);
            anchorDialogId = anchorMessage.getDialogId();
            anchorMessageId = anchorMessage.getId();
            View anchorView = layoutManager.findViewByPosition(anchorRow);
            if (anchorView != null) {
                anchorTop = layoutManager.getDecoratedTop(anchorView) - recyclerListView.getPaddingTop();
            }
        }
        HashSet<MessageHashId> previouslyVisibleMessages = new HashSet<>();
        if (PhotoViewer.getInstance().isVisible()) {
            for (MessageObject messageObject : messages) {
                previouslyVisibleMessages.add(new MessageHashId(messageObject.getId(), messageObject.getDialogId()));
            }
        }
        trimGlobalMediaWindow(false);
        rawMessages.sort(this::compareGlobalMediaMessages);
        rebuildVisibleMessages();
        boolean unchangedPrefix = !previousMessages.isEmpty() && messages.size() >= previousMessages.size();
        for (int i = 0; unchangedPrefix && i < previousMessages.size(); i++) {
            MessageObject oldMessage = previousMessages.get(i);
            MessageObject newMessage = messages.get(i);
            unchangedPrefix = oldMessage.getId() == newMessage.getId()
                    && oldMessage.getDialogId() == newMessage.getDialogId();
        }
        boolean sameVisibleIds = previousMessages.size() == messages.size();
        for (int i = 0; sameVisibleIds && i < messages.size(); i++) {
            MessageObject oldMessage = previousMessages.get(i);
            MessageObject newMessage = messages.get(i);
            sameVisibleIds = oldMessage.getId() == newMessage.getId()
                    && oldMessage.getDialogId() == newMessage.getDialogId();
        }
        if (PhotoViewer.getInstance().isVisible()) {
            for (MessageObject messageObject : messages) {
                if (!previouslyVisibleMessages.contains(new MessageHashId(messageObject.getId(), messageObject.getDialogId()))) {
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
            if (messages.isEmpty() && !globalMediaFailurePaused) {
                emptyView.title.setText(getString(R.string.SearchEmptyViewTitle2));
                emptyView.subtitle.setVisibility(View.VISIBLE);
                emptyView.subtitle.setText(getString(R.string.SearchEmptyViewFilteredSubtitle2));
            }
        }
        if (!viewerAppend && (!sameVisibleIds || previousItemCount >= 0
                && adapter.getItemCount() != previousItemCount)) {
            if (unchangedPrefix && previousItemCount >= 0 && adapter == sharedPhotoVideoAdapter) {
                int newItemCount = adapter.getItemCount();
                int firstChangedRow = previousMessages.size() / columnsCount;
                int commonItemCount = Math.min(previousItemCount, newItemCount);
                if (firstChangedRow < commonItemCount) {
                    adapter.notifyItemRangeChanged(firstChangedRow, commonItemCount - firstChangedRow);
                }
                if (newItemCount > previousItemCount) {
                    adapter.notifyItemRangeInserted(previousItemCount, newItemCount - previousItemCount);
                } else if (newItemCount < previousItemCount) {
                    adapter.notifyItemRangeRemoved(newItemCount, previousItemCount - newItemCount);
                }
            } else {
                adapter.notifyDataSetChanged();
            }
        }
        if (!viewerAppend && !unchangedPrefix && anchorMessageId != 0) {
            int anchorIndex = findVisibleGlobalMediaMessageIndex(anchorDialogId, anchorMessageId);
            if (anchorIndex >= 0 && anchorIndex / columnsCount != anchorRow) {
                layoutManager.scrollToPositionWithOffset(anchorIndex / columnsCount, anchorTop);
            }
        }
        globalMediaWindowAnchorMessage = null;
        globalMediaWindowAnchorDialogId = 0;
        globalMediaWindowAnchorMessageId = 0;
        globalMediaWindowAnchorTop = 0;
        if (viewerAppend) {
            globalMediaViewerLoad = false;
            globalMediaResultsDirty = true;
            scheduleGlobalMediaResultsUpdate(generation);
        }
    }

    private int findVisibleGlobalMediaMessageIndex(long dialogId, int messageId) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject item = messages.get(i);
            if (item.getDialogId() == dialogId && item.getId() == messageId) {
                return i;
            }
        }
        return -1;
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
        if (filterAndQueryIsSame && !clearOldResults
                && (globalMediaDialogBatch != null || globalMediaPageRequestInFlight || globalMediaProgressLoading
                || globalMediaCoverageWaiting || globalMediaPendingPage != null)) {
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
        scheduleGlobalMediaPageApply();
        if (globalMediaWaitingForDialogs && globalMediaSearchGeneration == requestIndex) {
            continueGlobalMediaSearch(globalMediaSearchGeneration);
        } else if (globalMediaDialogBatch != null && globalMediaRequestsInFlight == 0
                && globalMediaSearchGeneration == requestIndex) {
            dispatchGlobalMediaDialogSearches(globalMediaSearchGeneration);
        } else if (globalMediaSnapshotActive && globalMediaHeadRefreshPending && globalMediaSearchGeneration == requestIndex) {
            startGlobalMediaHeadRefresh(globalMediaSearchGeneration);
        } else if (globalMediaCoverageWaiting && globalMediaSearchGeneration == requestIndex) {
            startGlobalMediaDialogBatch(globalMediaSearchGeneration);
        } else if (globalMediaProgressLoaded && rawMessages.isEmpty() && globalMediaSearchGeneration == requestIndex) {
            requestGlobalMediaDatabasePage(globalMediaSearchGeneration, false, true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter notificationCenter = NotificationCenter.getInstance(lastAccount);
        notificationCenter.removeObserver(this, NotificationCenter.emojiLoaded);
        notificationCenter.removeObserver(this, NotificationCenter.dialogsNeedReload);
        if (globalMediaApplyPageRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaApplyPageRunnable);
            globalMediaApplyPageRunnable = null;
        }
        if (globalMediaResultsUpdateRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(globalMediaResultsUpdateRunnable);
            globalMediaResultsUpdateRunnable = null;
        }
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
