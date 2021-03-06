package me.devsaki.hentoid.database;

import android.content.Context;
import android.util.SparseIntArray;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.paging.DataSource;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;

import com.annimon.stream.Stream;

import org.apache.commons.lang3.tuple.ImmutablePair;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import io.objectbox.BoxStore;
import io.objectbox.android.ObjectBoxDataSource;
import io.objectbox.android.ObjectBoxLiveData;
import io.objectbox.query.Query;
import io.objectbox.relation.ToOne;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ErrorRecord;
import me.devsaki.hentoid.database.domains.Group;
import me.devsaki.hentoid.database.domains.GroupItem;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.database.domains.QueueRecord;
import me.devsaki.hentoid.database.domains.SiteBookmark;
import me.devsaki.hentoid.database.domains.SiteHistory;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Grouping;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.Preferences;
import timber.log.Timber;

public class ObjectBoxDAO implements CollectionDAO {

    private final ObjectBoxDB db;


    @IntDef({Mode.SEARCH_CONTENT_MODULAR, Mode.COUNT_CONTENT_MODULAR, Mode.SEARCH_CONTENT_UNIVERSAL, Mode.COUNT_CONTENT_UNIVERSAL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {
        int SEARCH_CONTENT_MODULAR = 0;
        int COUNT_CONTENT_MODULAR = 1;
        int SEARCH_CONTENT_UNIVERSAL = 2;
        int COUNT_CONTENT_UNIVERSAL = 3;
    }

    ObjectBoxDAO(ObjectBoxDB db) {
        this.db = db;
    }

    public ObjectBoxDAO(Context ctx) {
        db = ObjectBoxDB.getInstance(ctx);
    }

    // Use for testing (store generated by the test framework)
    public ObjectBoxDAO(BoxStore store) {
        db = ObjectBoxDB.getInstance(store);
    }


    public void cleanup() {
        db.closeThreadResources();
    }

    @Override
    public long getDbSizeBytes() {
        return db.getDbSizeBytes();
    }

    @Override
    public Single<List<Long>> getStoredBookIds(boolean nonFavouritesOnly, boolean includeQueued) {
        return Single.fromCallable(() -> Helper.getListFromPrimitiveArray(db.selectStoredContentIds(nonFavouritesOnly, includeQueued)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<List<Long>> getRecentBookIds(long groupId, int orderField, boolean orderDesc, boolean favouritesOnly) {
        return Single.fromCallable(() -> contentIdSearch(Mode.SEARCH_CONTENT_MODULAR, "", groupId, Collections.emptyList(), orderField, orderDesc, favouritesOnly))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<List<Long>> searchBookIds(String query, long groupId, List<Attribute> metadata, int orderField, boolean orderDesc, boolean favouritesOnly) {
        return Single.fromCallable(() -> contentIdSearch(Mode.SEARCH_CONTENT_MODULAR, query, groupId, metadata, orderField, orderDesc, favouritesOnly))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<List<Long>> searchBookIdsUniversal(String query, long groupId, int orderField, boolean orderDesc, boolean favouritesOnly) {
        return
                Single.fromCallable(() -> contentIdSearch(Mode.SEARCH_CONTENT_UNIVERSAL, query, groupId, Collections.emptyList(), orderField, orderDesc, favouritesOnly))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<AttributeQueryResult> getAttributeMasterDataPaged(
            @NonNull List<AttributeType> types,
            String filter,
            List<Attribute> attrs,
            boolean filterFavourites,
            int page,
            int booksPerPage,
            int orderStyle) {
        return Single
                .fromCallable(() -> pagedAttributeSearch(types, filter, attrs, filterFavourites, orderStyle, page, booksPerPage))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<SparseIntArray> countAttributesPerType(List<Attribute> filter) {
        return Single.fromCallable(() -> count(filter))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public LiveData<List<Content>> getErrorContent() {
        return new ObjectBoxLiveData<>(db.selectErrorContentQ());
    }

    public LiveData<Integer> countAllBooks() {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ObjectBoxLiveData<Content> livedata = new ObjectBoxLiveData<>(db.selectVisibleContentQ());

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    public LiveData<Integer> countBooks(String query, long groupId, List<Attribute> metadata, boolean favouritesOnly) {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ObjectBoxLiveData<Content> livedata = new ObjectBoxLiveData<>(db.selectContentSearchContentQ(query, groupId, metadata, favouritesOnly, Preferences.Constant.ORDER_FIELD_NONE, false));

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    public LiveData<PagedList<Content>> getRecentBooks(long groupId, int orderField, boolean orderDesc, boolean favouritesOnly, boolean loadAll) {
        return getPagedContent(Mode.SEARCH_CONTENT_MODULAR, "", groupId, Collections.emptyList(), orderField, orderDesc, favouritesOnly, loadAll);
    }

    public LiveData<PagedList<Content>> searchBooks(String query, long groupId, List<Attribute> metadata, int orderField, boolean orderDesc, boolean favouritesOnly, boolean loadAll) {
        return getPagedContent(Mode.SEARCH_CONTENT_MODULAR, query, groupId, metadata, orderField, orderDesc, favouritesOnly, loadAll);
    }

    public LiveData<PagedList<Content>> searchBooksUniversal(String query, long groupId, int orderField, boolean orderDesc, boolean favouritesOnly, boolean loadAll) {
        return getPagedContent(Mode.SEARCH_CONTENT_UNIVERSAL, query, groupId, Collections.emptyList(), orderField, orderDesc, favouritesOnly, loadAll);
    }

    public LiveData<PagedList<Content>> selectNoContent() {
        return new LivePagedListBuilder<>(new ObjectBoxDataSource.Factory<>(db.selectNoContentQ()), 1).build();
    }


    private LiveData<PagedList<Content>> getPagedContent(
            int mode,
            String filter,
            long groupId,
            List<Attribute> metadata,
            int orderField,
            boolean orderDesc,
            boolean favouritesOnly,
            boolean loadAll) {
        boolean isCustomOrder = (orderField == Preferences.Constant.ORDER_FIELD_CUSTOM);

        ImmutablePair<Long, DataSource.Factory<Integer, Content>> contentRetrieval;
        if (isCustomOrder)
            contentRetrieval = getPagedContentByList(mode, filter, groupId, metadata, orderField, orderDesc, favouritesOnly);
        else
            contentRetrieval = getPagedContentByQuery(mode, filter, groupId, metadata, orderField, orderDesc, favouritesOnly);

        int nbPages = Preferences.getContentPageQuantity();
        int initialLoad = nbPages * 2;
        if (loadAll) {
            // Trump Android's algorithm by setting a number of pages higher that the actual number of results
            // to avoid having a truncated result set (see issue #501)
            initialLoad = (int) Math.ceil(contentRetrieval.left * 1.0 / nbPages) * nbPages;
        }

        PagedList.Config cfg = new PagedList.Config.Builder().setEnablePlaceholders(!loadAll).setInitialLoadSizeHint(initialLoad).setPageSize(nbPages).build();
        return new LivePagedListBuilder<>(contentRetrieval.right, cfg).build();
    }

    private ImmutablePair<Long, DataSource.Factory<Integer, Content>> getPagedContentByQuery(
            int mode,
            String filter,
            long groupId,
            List<Attribute> metadata,
            int orderField,
            boolean orderDesc,
            boolean favouritesOnly) {
        boolean isRandom = (orderField == Preferences.Constant.ORDER_FIELD_RANDOM);

        Query<Content> query;
        if (Mode.SEARCH_CONTENT_MODULAR == mode) {
            query = db.selectContentSearchContentQ(filter, groupId, metadata, favouritesOnly, orderField, orderDesc);
        } else { // Mode.SEARCH_CONTENT_UNIVERSAL
            query = db.selectContentUniversalQ(filter, groupId, favouritesOnly, orderField, orderDesc);
        }

        if (isRandom)
            return new ImmutablePair<>(query.count(), new ObjectBoxRandomDataSource.RandomDataSourceFactory<>(query));
        else return new ImmutablePair<>(query.count(), new ObjectBoxDataSource.Factory<>(query));
    }

    private ImmutablePair<Long, DataSource.Factory<Integer, Content>> getPagedContentByList(
            int mode,
            String filter,
            long groupId,
            List<Attribute> metadata,
            int orderField,
            boolean orderDesc,
            boolean favouritesOnly) {
        long[] ids;

        if (Mode.SEARCH_CONTENT_MODULAR == mode) {
            ids = db.selectContentSearchContentByGroupItem(filter, groupId, metadata, favouritesOnly, orderField, orderDesc);
        } else { // Mode.SEARCH_CONTENT_UNIVERSAL
            ids = db.selectContentUniversalByGroupItem(filter, groupId, favouritesOnly, orderField, orderDesc);
        }

        return new ImmutablePair<>((long) ids.length, new ObjectBoxPredeterminedDataSource.PredeterminedDataSourceFactory<>(db::selectContentById, ids));
    }

    @Nullable
    public Content selectContent(long id) {
        return db.selectContentById(id);
    }

    public List<Content> selectContent(long[] id) {
        return db.selectContentById(Helper.getListFromPrimitiveArray(id));
    }

    @Nullable
    public Content selectContentBySourceAndUrl(@NonNull Site site, @NonNull String url) {
        return db.selectContentBySourceAndUrl(site, url);
    }

    @Nullable
    public Content selectContentByFolderUri(@NonNull final String folderUri, boolean onlyFlagged) {
        return db.selectContentByFolderUri(folderUri, onlyFlagged);
    }

    public long insertContent(@NonNull final Content content) {
        return db.insertContent(content);
    }

    public void updateContentStatus(@NonNull final StatusContent updateFrom, @NonNull final StatusContent updateTo) {
        db.updateContentStatus(updateFrom, updateTo);
    }

    public void deleteContent(@NonNull final Content content) {
        db.deleteContent(content);
    }

    public List<ErrorRecord> selectErrorRecordByContentId(long contentId) {
        return db.selectErrorRecordByContentId(contentId);
    }

    public void insertErrorRecord(@NonNull final ErrorRecord record) {
        db.insertErrorRecord(record);
    }

    public void deleteErrorRecords(long contentId) {
        db.deleteErrorRecords(contentId);
    }

    @Override
    public long countAllExternalBooks() {
        return db.selectAllExternalBooksQ().count();
    }

    public long countAllInternalBooks(boolean favsOnly) {
        return db.selectAllInternalBooksQ(favsOnly).count();
    }

    public long countAllQueueBooks() {
        return db.selectAllQueueBooksQ().count();
    }

    public List<Content> selectAllInternalBooks(boolean favsOnly) {
        return db.selectAllInternalBooksQ(favsOnly).find();
    }

    @Override
    public void deleteAllExternalBooks() {
        db.deleteContentById(db.selectAllExternalBooksQ().findIds());
    }

    @Override
    public List<Group> selectGroups(int grouping) {
        return db.selectGroupsQ(grouping, null, 0, false, Preferences.Constant.ARTIST_GROUP_VISIBILITY_ARTISTS_GROUPS).find();
    }

    @Override
    public LiveData<List<Group>> selectGroups(int grouping, @Nullable String query, int orderField, boolean orderDesc, int artistGroupVisibility) {
        LiveData<List<Group>> livedata = new ObjectBoxLiveData<>(db.selectGroupsQ(grouping, query, orderField, orderDesc, artistGroupVisibility));
        LiveData<List<Group>> workingData = livedata;

        // Download date grouping, groups are empty as they are dynamically generated
        //   -> Manually add items inside each of them
        //   -> Manually set a cover for each of them
        if (grouping == Grouping.DL_DATE.getId()) {
            MediatorLiveData<List<Group>> livedata2 = new MediatorLiveData<>();
            livedata2.addSource(livedata, v -> {
                List<Group> enrichedWithItems = Stream.of(v).map(g -> enrichGroupWithItemsByDlDate(g, g.propertyMin, g.propertyMax)).toList();
                livedata2.setValue(enrichedWithItems);
            });
            workingData = livedata2;
        }

        // Order by number of children (ObjectBox can't do that natively)
        if (Preferences.Constant.ORDER_FIELD_CHILDREN == orderField) {
            MediatorLiveData<List<Group>> result = new MediatorLiveData<>();
            result.addSource(workingData, v -> {
                int sortOrder = orderDesc ? -1 : 1;
                List<Group> orderedByNbChildren = Stream.of(v).sortBy(g -> g.getItems().size() * sortOrder).toList();
                result.setValue(orderedByNbChildren);
            });
            return result;
        } else return workingData;
    }

    private Group enrichGroupWithItemsByDlDate(@NonNull final Group g, int minDays, int maxDays) {
        List<GroupItem> items = selectGroupItemsByDlDate(minDays, maxDays);
        g.setItems(items);
        if (!items.isEmpty()) g.picture.setTarget(items.get(0).content.getTarget().getCover());

        return g;
    }

    @Nullable
    public Group selectGroup(long groupId) {
        return db.selectGroup(groupId);
    }

    @Nullable
    public Group selectGroupByName(int grouping, @NonNull final String name) {
        return db.selectGroupByName(grouping, name);
    }

    // Does NOT check name unicity
    public long insertGroup(Group group) {
        // Auto-number max order when not provided
        if (-1 == group.order)
            group.order = db.getMaxGroupOrderFor(group.grouping) + 1;
        return db.insertGroup(group);
    }

    public long countGroupsFor(Grouping grouping) {
        return db.countGroupsFor(grouping);
    }

    public LiveData<Integer> countLiveGroupsFor(@NonNull final Grouping grouping) {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ObjectBoxLiveData<Group> livedata = new ObjectBoxLiveData<>(db.selectGroupsByGroupingQ(grouping.getId()));

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    public void deleteGroup(long groupId) {
        db.deleteGroup(groupId);
    }

    public void deleteAllGroups(Grouping grouping) {
        db.deleteGroupItemsByGrouping(grouping.getId());
        db.selectGroupsByGroupingQ(grouping.getId()).remove();
    }

    public void flagAllGroups(Grouping grouping) {
        db.flagGroupsById(db.selectGroupsByGroupingQ(grouping.getId()).findIds(), true);
    }

    public void deleteAllFlaggedGroups() {
        Query<Group> flaggedGroups = db.selectFlaggedGroupsQ();

        // Delete related GroupItems first
        List<Group> groups = flaggedGroups.find();
        for (Group g : groups) db.deleteGroupItemsByGroup(g.id);

        // Actually delete the Group
        flaggedGroups.remove();
    }

    public long insertGroupItem(GroupItem item) {
        // Auto-number max order when not provided
        if (-1 == item.order)
            item.order = db.getMaxGroupItemOrderFor(item.getGroupId()) + 1;

        // If target group doesn't have a cover, get the corresponding Content's
        ToOne<ImageFile> groupCover = item.group.getTarget().picture;
        if (!groupCover.isResolvedAndNotNull())
            groupCover.setAndPutTarget(item.content.getTarget().getCover());

        return db.insertGroupItem(item);
    }

    public List<GroupItem> selectGroupItems(long contentId, Grouping grouping) {
        return db.selectGroupItems(contentId, grouping.getId());
    }

    public List<GroupItem> selectGroupItemsByDlDate(int minDays, int maxDays) {
        return db.selectGroupItemsByDlDate(minDays, maxDays);
    }

    public void deleteGroupItem(long groupItemId) {
        db.deleteGroupItem(groupItemId);
    }

    public void deleteGroupItems(@NonNull final List<Long> groupItemIds) {
        // Check if one of the GroupItems to delete is linked to the content that contains the group's cover picture
        List<GroupItem> groupItems = db.selectGroupItems(Helper.getPrimitiveLongArrayFromList(groupItemIds));
        for (GroupItem gi : groupItems) {
            ToOne<ImageFile> groupPicture = gi.group.getTarget().picture;
            // If so, remove the cover picture
            if (groupPicture.isResolvedAndNotNull() && groupPicture.getTarget().getContent().getTargetId() == gi.content.getTargetId())
                gi.group.getTarget().picture.setAndPutTarget(null);
        }

        db.deleteGroupItems(Helper.getPrimitiveLongArrayFromList(groupItemIds));
    }


    public List<Content> selectAllQueueBooks() {
        return db.selectAllQueueBooksQ().find();
    }

    public void flagAllInternalBooks() {
        db.flagContentById(db.selectAllInternalBooksQ(false).findIds(), true);
    }

    public void deleteAllInternalBooks(boolean resetRemainingImagesStatus) {
        db.deleteContentById(db.selectAllInternalBooksQ(false).findIds());

        // Switch status of all remaining images (i.e. from queued books) to SAVED, as we cannot guarantee the files are still there
        if (resetRemainingImagesStatus) {
            long[] remainingContentIds = db.selectAllQueueBooksQ().findIds();
            for (long contentId : remainingContentIds)
                db.updateImageContentStatus(contentId, null, StatusContent.SAVED);
        }
    }

    public void deleteAllFlaggedBooks(boolean resetRemainingImagesStatus) {
        db.deleteContentById(db.selectAllFlaggedBooksQ().findIds());

        // Switch status of all remaining images (i.e. from queued books) to SAVED, as we cannot guarantee the files are still there
        if (resetRemainingImagesStatus) {
            long[] remainingContentIds = db.selectAllQueueBooksQ().findIds();
            for (long contentId : remainingContentIds)
                db.updateImageContentStatus(contentId, null, StatusContent.SAVED);
        }
    }

    public void flagAllErrorBooksWithJson() {
        db.flagContentById(db.selectAllErrorJsonBooksQ().findIds(), true);
    }

    public void deleteAllQueuedBooks() {
        Timber.i("Cleaning up queue");
        db.deleteContentById(db.selectAllQueueBooksQ().findIds());
        db.deleteQueue();
    }

    public void insertImageFile(@NonNull ImageFile img) {
        db.insertImageFile(img);
    }

    public void replaceImageList(long contentId, @NonNull final List<ImageFile> newList) {
        db.deleteImageFiles(contentId);
        for (ImageFile img : newList) img.setContentId(contentId);
        db.insertImageFiles(newList);
    }

    public void updateImageContentStatus(long contentId, StatusContent updateFrom, @NonNull StatusContent updateTo) {
        db.updateImageContentStatus(contentId, updateFrom, updateTo);
    }

    public void updateImageFileStatusParamsMimeTypeUriSize(@NonNull ImageFile image) {
        db.updateImageFileStatusParamsMimeTypeUriSize(image);
    }

    public void deleteImageFiles(@NonNull List<ImageFile> imgs) {
        // Delete the page
        db.deleteImageFiles(imgs);

        // Lists all relevant content
        List<Long> contents = Stream.of(imgs).filter(i -> i.getContent() != null).map(i -> i.getContent().getTargetId()).distinct().toList();

        // Update the content with its new size
        for (Long contentId : contents) {
            Content content = db.selectContentById(contentId);
            if (content != null) {
                content.computeSize();
                db.insertContent(content);
            }
        }
    }

    @Nullable
    public ImageFile selectImageFile(long id) {
        return db.selectImageFile(id);
    }

    public LiveData<List<ImageFile>> getDownloadedImagesFromContent(long id) {
        return new ObjectBoxLiveData<>(db.selectDownloadedImagesFromContent(id));
    }

    public Map<StatusContent, ImmutablePair<Integer, Long>> countProcessedImagesById(long contentId) {
        return db.countProcessedImagesById(contentId);
    }

    public Map<Site, ImmutablePair<Integer, Long>> getMemoryUsagePerSource() {
        return db.selectMemoryUsagePerSource();
    }


    public void addContentToQueue(@NonNull final Content content, StatusContent targetImageStatus) {
        if (targetImageStatus != null)
            db.updateImageContentStatus(content.getId(), null, targetImageStatus);

        content.setStatus(StatusContent.DOWNLOADING);
        db.insertContent(content);

        List<QueueRecord> queue = db.selectQueue();
        int lastIndex = 1;
        if (!queue.isEmpty())
            lastIndex = queue.get(queue.size() - 1).getRank() + 1;
        db.insertQueue(content.getId(), lastIndex);
    }

    private List<Long> contentIdSearch(@Mode int mode, String filter, long groupId, List<Attribute> metadata, int orderField, boolean orderDesc, boolean favouritesOnly) {

        if (Mode.SEARCH_CONTENT_MODULAR == mode) {
            return Helper.getListFromPrimitiveArray(db.selectContentSearchId(filter, groupId, metadata, favouritesOnly, orderField, orderDesc));
        } else if (Mode.SEARCH_CONTENT_UNIVERSAL == mode) {
            return Helper.getListFromPrimitiveArray(db.selectContentUniversalId(filter, groupId, favouritesOnly, orderField, orderDesc));
        } else {
            return Collections.emptyList();
        }
    }

    private AttributeQueryResult pagedAttributeSearch(
            @NonNull List<AttributeType> attrTypes,
            String filter,
            List<Attribute> attrs,
            boolean filterFavourites,
            int sortOrder,
            int pageNum,
            int itemPerPage) {
        AttributeQueryResult result = new AttributeQueryResult();

        if (!attrTypes.isEmpty()) {
            if (attrTypes.get(0).equals(AttributeType.SOURCE)) {
                result.attributes.addAll(db.selectAvailableSources(attrs));
                result.totalSelectedAttributes = result.attributes.size();
            } else {
                for (AttributeType type : attrTypes) {
                    // TODO fix sorting when concatenating both lists
                    result.attributes.addAll(db.selectAvailableAttributes(type, attrs, filter, filterFavourites, sortOrder, pageNum, itemPerPage));
                    result.totalSelectedAttributes += db.countAvailableAttributes(type, attrs, filter, filterFavourites);
                }
            }
        }

        return result;
    }

    private SparseIntArray count(List<Attribute> filter) {
        SparseIntArray result;

        if (null == filter || filter.isEmpty()) {
            result = db.countAvailableAttributesPerType();
            result.put(AttributeType.SOURCE.getCode(), db.selectAvailableSources().size());
        } else {
            result = db.countAvailableAttributesPerType(filter);
            result.put(AttributeType.SOURCE.getCode(), db.selectAvailableSources(filter).size());
        }

        return result;
    }

    public LiveData<List<QueueRecord>> getQueueContent() {
        return new ObjectBoxLiveData<>(db.selectQueueContentsQ());
    }

    public List<QueueRecord> selectQueue() {
        return db.selectQueue();
    }

    public void updateQueue(@NonNull List<QueueRecord> queue) {
        db.updateQueue(queue);
    }

    public void deleteQueue(@NonNull Content content) {
        db.deleteQueue(content);
    }

    public void deleteQueue(int index) {
        db.deleteQueue(index);
    }

    public SiteHistory getHistory(@NonNull Site s) {
        return db.selectHistory(s);
    }

    public void insertSiteHistory(@NonNull Site site, @NonNull String url) {
        db.insertSiteHistory(site, url);
    }

    public long countAllBookmarks() {
        return db.selectBookmarksQ(null).count();
    }

    public List<SiteBookmark> selectAllBookmarks() {
        return db.selectBookmarksQ(null).find();
    }

    public Set<String> selectAllBookmarkUrls() {
        return new HashSet<>(Arrays.asList(db.selectAllBooksmarkUrls()));
    }

    public void deleteAllBookmarks() {
        db.selectBookmarksQ(null).remove();
    }

    public List<SiteBookmark> selectBookmarks(@NonNull Site s) {
        return db.selectBookmarksQ(s).find();
    }

    public long insertBookmark(@NonNull final SiteBookmark bookmark) {
        return db.insertBookmark(bookmark);
    }

    public void insertBookmarks(@NonNull List<SiteBookmark> bookmarks) {
        db.insertBookmarks(bookmarks);
    }

    public void deleteBookmark(long bookmarkId) {
        db.deleteBookmark(bookmarkId);
    }


    // ONE-TIME USE QUERIES (MIGRATION & CLEANUP)

    @Override
    public Single<List<Long>> getOldStoredBookIds() {
        return Single.fromCallable(() -> Helper.getListFromPrimitiveArray(db.selectOldStoredContentQ().findIds()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public long countOldStoredContent() {
        return db.selectOldStoredContentQ().count();
    }
}
