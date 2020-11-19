package awais.instagrabber.customviews;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.transition.ChangeBounds;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import awais.instagrabber.adapters.FeedAdapterV2;
import awais.instagrabber.customviews.helpers.GridSpacingItemDecoration;
import awais.instagrabber.customviews.helpers.PostFetcher;
import awais.instagrabber.customviews.helpers.RecyclerLazyLoaderAtBottom;
import awais.instagrabber.interfaces.FetchListener;
import awais.instagrabber.models.FeedModel;
import awais.instagrabber.models.PostChild;
import awais.instagrabber.models.PostsLayoutPreferences;
import awais.instagrabber.utils.Utils;
import awais.instagrabber.viewmodels.FeedViewModel;
import awais.instagrabber.workers.DownloadWorker;

public class PostsRecyclerView extends RecyclerView {
    private static final String TAG = "PostsRecyclerView";

    private StaggeredGridLayoutManager layoutManager;
    private PostsLayoutPreferences layoutPreferences;
    private PostFetcher.PostFetchService postFetchService;
    private Transition transition;
    private PostFetcher postFetcher;
    private ViewModelStoreOwner viewModelStoreOwner;
    private FeedAdapterV2 feedAdapter;
    private LifecycleOwner lifeCycleOwner;
    private FeedViewModel feedViewModel;
    private boolean initCalled = false;
    private GridSpacingItemDecoration gridSpacingItemDecoration;
    private RecyclerLazyLoaderAtBottom lazyLoader;
    private FeedAdapterV2.FeedItemCallback feedItemCallback;
    private boolean shouldScrollToTop;
    private FeedAdapterV2.SelectionModeCallback selectionModeCallback;

    private final List<FetchStatusChangeListener> fetchStatusChangeListeners = new ArrayList<>();

    private final FetchListener<List<FeedModel>> fetchListener = new FetchListener<List<FeedModel>>() {
        @Override
        public void onResult(final List<FeedModel> result) {
            final int currentPage = lazyLoader.getCurrentPage();
            if (currentPage == 0) {
                feedViewModel.getList().postValue(result);
                shouldScrollToTop = true;
                dispatchFetchStatus();
                return;
            }
            final List<FeedModel> models = feedViewModel.getList().getValue();
            final List<FeedModel> modelsCopy = models == null ? new ArrayList<>() : new ArrayList<>(models);
            modelsCopy.addAll(result);
            feedViewModel.getList().postValue(modelsCopy);
            dispatchFetchStatus();
        }

        @Override
        public void onFailure(final Throwable t) {
            Log.e(TAG, "onFailure: ", t);
        }
    };

    private final RecyclerView.SmoothScroller smoothScroller = new LinearSmoothScroller(getContext()) {
        @Override
        protected int getVerticalSnapPreference() {
            return LinearSmoothScroller.SNAP_TO_START;
        }
    };

    public PostsRecyclerView(@NonNull final Context context) {
        super(context);
    }

    public PostsRecyclerView(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public PostsRecyclerView(@NonNull final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PostsRecyclerView setViewModelStoreOwner(final ViewModelStoreOwner owner) {
        if (initCalled) {
            throw new IllegalArgumentException("init already called!");
        }
        this.viewModelStoreOwner = owner;
        return this;
    }

    public PostsRecyclerView setLifeCycleOwner(final LifecycleOwner lifeCycleOwner) {
        if (initCalled) {
            throw new IllegalArgumentException("init already called!");
        }
        this.lifeCycleOwner = lifeCycleOwner;
        return this;
    }

    public PostsRecyclerView setPostFetchService(final PostFetcher.PostFetchService postFetchService) {
        if (initCalled) {
            throw new IllegalArgumentException("init already called!");
        }
        this.postFetchService = postFetchService;
        return this;
    }

    public PostsRecyclerView setFeedItemCallback(@NonNull final FeedAdapterV2.FeedItemCallback feedItemCallback) {
        this.feedItemCallback = feedItemCallback;
        return this;
    }

    public PostsRecyclerView setSelectionModeCallback(@NonNull final FeedAdapterV2.SelectionModeCallback selectionModeCallback) {
        this.selectionModeCallback = selectionModeCallback;
        return this;
    }

    public PostsRecyclerView setLayoutPreferences(final PostsLayoutPreferences layoutPreferences) {
        this.layoutPreferences = layoutPreferences;
        if (initCalled) {
            if (layoutPreferences == null) return this;
            feedAdapter.setLayoutPreferences(layoutPreferences);
            updateLayout();
        }
        return this;
    }

    public void init() {
        initCalled = true;
        if (viewModelStoreOwner == null) {
            throw new IllegalArgumentException("ViewModelStoreOwner cannot be null");
        } else if (lifeCycleOwner == null) {
            throw new IllegalArgumentException("LifecycleOwner cannot be null");
        } else if (postFetchService == null) {
            throw new IllegalArgumentException("PostFetchService cannot be null");
        }
        if (layoutPreferences == null) {
            layoutPreferences = PostsLayoutPreferences.builder().build();
            // Utils.settingsHelper.putString(Constants.PREF_POSTS_LAYOUT, layoutPreferences.getJson());
        }
        gridSpacingItemDecoration = new GridSpacingItemDecoration(Utils.convertDpToPx(2));
        initTransition();
        initAdapter();
        initLayoutManager();
        initSelf();
        initDownloadWorkerListener();
    }

    private void initTransition() {
        transition = new ChangeBounds();
        transition.setDuration(300);
    }

    private void initLayoutManager() {
        layoutManager = new StaggeredGridLayoutManager(layoutPreferences.getColCount(), StaggeredGridLayoutManager.VERTICAL);
        setLayoutManager(layoutManager);
    }

    private void initAdapter() {
        feedAdapter = new FeedAdapterV2(layoutPreferences, feedItemCallback, selectionModeCallback);
        feedAdapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        setAdapter(feedAdapter);
    }

    private void initSelf() {
        feedViewModel = new ViewModelProvider(viewModelStoreOwner).get(FeedViewModel.class);
        feedViewModel.getList().observe(lifeCycleOwner, list -> feedAdapter.submitList(list, () -> {
            if (!shouldScrollToTop) return;
            smoothScrollToPosition(0);
            shouldScrollToTop = false;
        }));
        postFetcher = new PostFetcher(postFetchService, fetchListener);
        if (layoutPreferences.getHasGap()) {
            addItemDecoration(gridSpacingItemDecoration);
        }
        setHasFixedSize(true);
        setNestedScrollingEnabled(true);
        lazyLoader = new RecyclerLazyLoaderAtBottom(layoutManager, (page) -> {
            if (postFetcher.hasMore()) {
                postFetcher.fetch();
                dispatchFetchStatus();
            }
        });
        addOnScrollListener(lazyLoader);
        postFetcher.fetch();
        dispatchFetchStatus();
    }

    private void initDownloadWorkerListener() {
        WorkManager.getInstance(getContext())
                   .getWorkInfosByTagLiveData("download")
                   .observe(lifeCycleOwner, workInfoList -> {
                       for (final WorkInfo workInfo : workInfoList) {
                           if (workInfo == null) continue;
                           final Data progress = workInfo.getProgress();
                           final float progressPercent = progress.getFloat(DownloadWorker.PROGRESS, 0);
                           if (progressPercent != 100) continue;
                           final String url = progress.getString(DownloadWorker.URL);
                           final List<FeedModel> feedModels = feedViewModel.getList().getValue();
                           if (feedModels == null) continue;
                           for (int i = 0; i < feedModels.size(); i++) {
                               final FeedModel feedModel = feedModels.get(i);
                               final List<String> displayUrls = getDisplayUrl(feedModel);
                               if (displayUrls.contains(url)) {
                                   feedAdapter.notifyItemChanged(i);
                                   break;
                               }
                           }
                       }
                   });
    }

    private List<String> getDisplayUrl(final FeedModel feedModel) {
        List<String> urls = Collections.emptyList();
        switch (feedModel.getItemType()) {
            case MEDIA_TYPE_IMAGE:
            case MEDIA_TYPE_VIDEO:
                urls = Collections.singletonList(feedModel.getDisplayUrl());
                break;
            case MEDIA_TYPE_SLIDER:
                final List<PostChild> sliderItems = feedModel.getSliderItems();
                if (sliderItems != null) {
                    final ImmutableList.Builder<String> builder = ImmutableList.builder();
                    for (final PostChild child : sliderItems) {
                        builder.add(child.getDisplayUrl());
                    }
                    urls = builder.build();
                }
                break;
            default:
        }
        return urls;
    }

    private void updateLayout() {
        post(() -> {
            TransitionManager.beginDelayedTransition(this, transition);
            feedAdapter.notifyDataSetChanged();
            final int itemDecorationCount = getItemDecorationCount();
            if (!layoutPreferences.getHasGap()) {
                if (itemDecorationCount == 1) {
                    removeItemDecoration(gridSpacingItemDecoration);
                }
            } else {
                if (itemDecorationCount == 0) {
                    addItemDecoration(gridSpacingItemDecoration);
                }
            }
            if (layoutPreferences.getType() == PostsLayoutPreferences.PostsLayoutType.LINEAR) {
                if (layoutManager.getSpanCount() != 1) {
                    layoutManager.setSpanCount(1);
                    setAdapter(null);
                    setAdapter(feedAdapter);
                }
            } else {
                boolean shouldRedraw = layoutManager.getSpanCount() == 1;
                layoutManager.setSpanCount(layoutPreferences.getColCount());
                if (shouldRedraw) {
                    setAdapter(null);
                    setAdapter(feedAdapter);
                }
            }
        });
    }

    public void refresh() {
        if (lazyLoader != null) {
            lazyLoader.resetState();
        }
        if (postFetcher != null) {
            postFetcher.reset();
            postFetcher.fetch();
        }
        dispatchFetchStatus();
    }

    public boolean isFetching() {
        return postFetcher != null && postFetcher.isFetching();
    }

    public PostsRecyclerView addFetchStatusChangeListener(final FetchStatusChangeListener fetchStatusChangeListener) {
        if (fetchStatusChangeListener == null) return this;
        fetchStatusChangeListeners.add(fetchStatusChangeListener);
        return this;
    }

    public void removeFetchStatusListener(final FetchStatusChangeListener fetchStatusChangeListener) {
        if (fetchStatusChangeListener == null) return;
        fetchStatusChangeListeners.remove(fetchStatusChangeListener);
    }

    private void dispatchFetchStatus() {
        for (final FetchStatusChangeListener listener : fetchStatusChangeListeners) {
            listener.onFetchStatusChange(isFetching());
        }
    }

    public PostsLayoutPreferences getLayoutPreferences() {
        return layoutPreferences;
    }

    public void endSelection() {
        feedAdapter.endSelection();
    }

    public interface FetchStatusChangeListener {
        void onFetchStatusChange(boolean fetching);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        lifeCycleOwner = null;
    }

    @Override
    public void smoothScrollToPosition(final int position) {
        smoothScroller.setTargetPosition(position);
        layoutManager.startSmoothScroll(smoothScroller);
    }
}
