package me.devsaki.hentoid.viewmodels;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.annimon.stream.IntStream;
import com.annimon.stream.Stream;
import com.annimon.stream.function.Consumer;

import org.apache.commons.lang3.tuple.ImmutablePair;

import java.io.File;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.schedulers.Schedulers;
import me.devsaki.hentoid.database.CollectionDAO;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.util.ArchiveHelper;
import me.devsaki.hentoid.util.Consts;
import me.devsaki.hentoid.util.ContentHelper;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.ToastUtil;
import me.devsaki.hentoid.util.exception.ContentNotRemovedException;
import me.devsaki.hentoid.widget.ContentSearchManager;
import timber.log.Timber;


public class ImageViewerViewModel extends AndroidViewModel {

    private static final String KEY_IS_SHUFFLED = "is_shuffled";

    // Collection DAO
    private final CollectionDAO collectionDao;

    // Settings
    private boolean isShuffled = false;                                              // True if images have to be shuffled; false if presented in the book order
    private boolean showFavourites = false;                                          // True if viewer only shows favourite images; false if shows all pages

    // Collection data
    private final MutableLiveData<Content> content = new MutableLiveData<>();        // Current content
    private List<Long> contentIds = Collections.emptyList();                         // Content Ids of the whole collection ordered according to current filter
    private int currentContentIndex = -1;                                            // Index of current content within the above list
    private long loadedBookId = -1;                                                  // ID of currently loaded book

    // Pictures data
    private LiveData<List<ImageFile>> currentImageSource;
    private final MediatorLiveData<List<ImageFile>> images = new MediatorLiveData<>();  // Currently displayed set of images
    private final MutableLiveData<Integer> startingIndex = new MutableLiveData<>();     // 0-based index of the current image
    private final MutableLiveData<Boolean> shuffled = new MutableLiveData<>();          // shuffle state of the current book

    // Write cache for read indicator (no need to update DB and JSON at every page turn)
    private final Set<Integer> readPageNumbers = new HashSet<>();

    // Technical
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable searchDisposable = Disposables.empty();


    public ImageViewerViewModel(@NonNull Application application, @NonNull CollectionDAO collectionDAO) {
        super(application);
        collectionDao = collectionDAO;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        collectionDao.cleanup();
        searchDisposable.dispose();
        compositeDisposable.clear();
    }

    @NonNull
    public LiveData<Content> getContent() {
        return content;
    }

    @NonNull
    public LiveData<List<ImageFile>> getImages() {
        return images;
    }

    @NonNull
    public LiveData<Integer> getStartingIndex() {
        return startingIndex;
    }

    @NonNull
    public LiveData<Boolean> getShuffled() {
        return shuffled;
    }


    public void onSaveState(Bundle outState) {
        outState.putBoolean(KEY_IS_SHUFFLED, isShuffled);
    }

    public void onRestoreState(@Nullable Bundle savedState) {
        if (savedState == null) return;
        isShuffled = savedState.getBoolean(KEY_IS_SHUFFLED, false);
    }

    public void loadFromContent(long contentId) {
        if (contentId > 0) {
            Content loadedContent = collectionDao.selectContent(contentId);
            if (loadedContent != null)
                processContent(loadedContent);
        }
    }

    public void loadFromSearchParams(long contentId, @NonNull Bundle bundle) {
        // Technical
        ContentSearchManager searchManager = new ContentSearchManager(collectionDao);
        searchManager.loadFromBundle(bundle);

        searchDisposable.dispose();
        searchDisposable = searchManager.searchLibraryForId().subscribe(
                list -> {
                    contentIds = list;
                    loadFromContent(contentId);
                },
                throwable -> {
                    Timber.w(throwable);
                    ToastUtil.toast("Book list loading failed");
                }
        );
    }

    public void setStartingIndex(int index) {
        startingIndex.setValue(index);
    }

    private void setImages(@NonNull Content theContent, @NonNull List<ImageFile> imgs) {
        compositeDisposable.add(
                Single.fromCallable(() -> doSetImages(theContent, imgs))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .map(p -> initViewer(p.left, p.right))
                        .observeOn(Schedulers.io())
                        .subscribe(
                                // Cache JSON and record 1 more view for the new content
                                c -> postLoadProcessing(getApplication().getApplicationContext(), c),
                                Timber::e
                        )
        );
    }

    private ImmutablePair<Content, List<ImageFile>> doSetImages(@NonNull Content theContent, @NonNull List<ImageFile> imgs) throws IOException {
        boolean missingUris = Stream.of(imgs).filter(img -> img.getFileUri().isEmpty()).count() > 0;
        List<ImageFile> imageFiles = new ArrayList<>(imgs);

        // Reattach actual files to the book's pictures if they are empty or have no URI's
        if (missingUris || imgs.isEmpty()) {
            List<DocumentFile> pictureFiles = ContentHelper.getPictureFilesFromContent(getApplication(), theContent);
            if (!pictureFiles.isEmpty()) {
                if (imgs.isEmpty()) {
                    imageFiles = ContentHelper.createImageListFromFiles(pictureFiles);
                    theContent.setImageFiles(imageFiles);
                    collectionDao.insertContent(theContent);
                } else
                    ContentHelper.matchFilesToImageList(pictureFiles, imageFiles);
            }
        }

        // Empty the cache folder where previous cached images might be
        // TODO create a smarter cache that works with a window of a given size
        File cachePicFolder = getOrCreatePictureCacheFolder();
        if (cachePicFolder != null) {
            File[] files = cachePicFolder.listFiles();
            if (files != null)
                for (File f : files)
                    if (!f.delete()) Timber.w("Unable to delete file %s", f.getAbsolutePath());


            // Extract the images if they are contained within an archive
            if (theContent.isArchive()) {
                // Unzip the archive in the app's cache folder
                DocumentFile zipFile = FileHelper.getFileFromSingleUriString(getApplication(), theContent.getStorageUri());
                // TODO stream that so that loading isn't blocked
                if (zipFile != null) {
                    List<Uri> unzippedFilesUris = ArchiveHelper.extractArchiveEntries(
                            getApplication(),
                            zipFile,
                            Stream.of(imageFiles).map(i -> i.getFileUri().replace(theContent.getStorageUri() + File.separator, "")).toList(),
                            cachePicFolder,
                            null);

                    // Feed the Uri's of unzipped files back into the corresponding images for viewing
                    for (ImageFile img : imageFiles) {
                        for (Uri unzippedUri : unzippedFilesUris)
                            if (FileHelper.getFileNameWithoutExtension(img.getFileUri()).equalsIgnoreCase(FileHelper.getFileNameWithoutExtension(unzippedUri.getPath()))) {
                                img.setFileUri(unzippedUri.toString());
                                break;
                            }
                    }
                }
            }
        }

        return new ImmutablePair<>(theContent, imageFiles);
    }

    private Content initViewer(@NonNull Content theContent, @NonNull List<ImageFile> imageFiles) {
        sortAndSetImages(imageFiles, isShuffled);

        if (theContent.getId() != loadedBookId) { // To be done once per book only
            int startingIndex = 0;
            if (Preferences.isViewerResumeLastLeft())
                startingIndex = theContent.getLastReadPageIndex();
            setStartingIndex(startingIndex);

            // Init the read pages write cache
            readPageNumbers.clear();
            Collection<Integer> readPages = Stream.of(imageFiles).filter(ImageFile::isRead).map(ImageFile::getOrder).toList();

            // Fix pre-v1.13 books where ImageFile.read has no value
            if (readPages.isEmpty() && theContent.getLastReadPageIndex() > 0) {
                int lastReadPageNumber = imageFiles.get(theContent.getLastReadPageIndex()).getOrder();
                readPageNumbers.addAll(IntStream.rangeClosed(1, lastReadPageNumber).boxed().toList());
            } else {
                readPageNumbers.addAll(readPages);
            }

            // Mark initial page as read
            if (startingIndex < imageFiles.size())
                markPageAsRead(imageFiles.get(startingIndex).getOrder());
        }

        loadedBookId = theContent.getId();
        return theContent;
    }

    public void onShuffleClick() {
        isShuffled = !isShuffled;
        shuffled.postValue(isShuffled);

        List<ImageFile> imgs = getImages().getValue();
        if (imgs != null) sortAndSetImages(imgs, isShuffled);
    }

    private void sortAndSetImages(@NonNull List<ImageFile> imgs, boolean shuffle) {
        if (shuffle) {
            Collections.shuffle(imgs);
            // Don't keep the cover
            imgs = Stream.of(imgs).filter(img -> !img.isCover()).toList();
        } else {
            // Sort images according to their Order; don't keep the cover
            imgs = Stream.of(imgs).sortBy(ImageFile::getOrder).filter(img -> !img.isCover()).toList();
        }

        if (showFavourites)
            imgs = Stream.of(imgs).filter(ImageFile::isFavourite).toList();

        for (int i = 0; i < imgs.size(); i++) imgs.get(i).setDisplayOrder(i);

        images.setValue(imgs);
    }

    public void onLeaveBook(int index, int highestImageIndexReached) {
        List<ImageFile> theImages = images.getValue();
        Content theContent = content.getValue();
        if (null == theImages || null == theContent) return;

        int readThresholdPref = Preferences.getViewerReadThreshold();
        int readThresholdPosition;
        switch (readThresholdPref) {
            case Preferences.Constant.VIEWER_READ_THRESHOLD_2:
                readThresholdPosition = 2;
                break;
            case Preferences.Constant.VIEWER_READ_THRESHOLD_5:
                readThresholdPosition = 5;
                break;
            case Preferences.Constant.VIEWER_READ_THRESHOLD_ALL:
                readThresholdPosition = theImages.size() - 1;
                break;
            default:
                readThresholdPosition = 1;
        }
        boolean updateReads = (highestImageIndexReached + 1 >= readThresholdPosition || theContent.getReads() > 0);

        // Reset the memorized page index if it represents the last page
        int indexToSet = (index == theImages.size() - 1) ? 0 : index;

        compositeDisposable.add(
                Completable.fromRunnable(() -> doLeaveBook(theContent.getId(), indexToSet, updateReads))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> { // No feedback needed
                                },
                                Timber::e
                        )
        );
    }

    private void doLeaveBook(final long contentId, int indexToSet, boolean updateReads) {
        Helper.assertNonUiThread();

        // Get a fresh version of current content in case it has been updated since the initial load
        // (that can be the case when viewing a book that is being downloaded)
        Content savedContent = collectionDao.selectContent(contentId);
        if (null == savedContent) return;

        List<ImageFile> theImages = savedContent.getImageFiles();
        if (null == theImages) return;

        // Update image read status with the cached read statuses
        long previousReadPagesCount = Stream.of(theImages).filter(ImageFile::isRead).count();
        if (readPageNumbers.size() > previousReadPagesCount) {
            for (ImageFile img : theImages)
                if (readPageNumbers.contains(img.getOrder())) img.setRead(true);
        }

        if (indexToSet != savedContent.getLastReadPageIndex() || updateReads || readPageNumbers.size() > previousReadPagesCount)
            ContentHelper.updateContentReads(getApplication(), collectionDao, savedContent, indexToSet, theImages);
    }

    public void toggleShowFavouritePages(Consumer<Boolean> callback) {
        Content c = content.getValue();
        if (c != null) {
            showFavourites = !showFavourites;
            processContent(c);
            callback.accept(showFavourites);
        }
    }

    public void togglePageFavourite(ImageFile file, Consumer<ImageFile> callback) {
        compositeDisposable.add(
                Single.fromCallable(() -> doTogglePageFavourite(getApplication().getApplicationContext(), file.getId()))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                result -> onToggleFavouriteSuccess(result, callback),
                                Timber::e
                        )
        );
    }

    private void onToggleFavouriteSuccess(ImageFile result, Consumer<ImageFile> callback) {
        List<ImageFile> imgs = getImages().getValue();
        if (imgs != null) {
            for (ImageFile img : imgs)
                if (img.getId() == result.getId()) {
                    img.setFavourite(result.isFavourite()); // Update new state in memory
                    result.setDisplayOrder(img.getDisplayOrder()); // Set the display order of the item to
                    callback.accept(result); // Inform the view
                }
        }
        compositeDisposable.clear();
    }

    /**
     * Toggles favourite flag in DB and in the content JSON
     *
     * @param context Context to be used for this operation
     * @param imageId ID of the image whose flag to toggle
     * @return ImageFile with the new state
     */
    private ImageFile doTogglePageFavourite(Context context, long imageId) {
        Helper.assertNonUiThread();
        ImageFile img = collectionDao.selectImageFile(imageId);

        if (img != null) {
            img.setFavourite(!img.isFavourite());

            // Persist in DB
            collectionDao.insertImageFile(img);

            // Persist in JSON
            Content theContent = img.getContent().getTarget();
            if (!theContent.getJsonUri().isEmpty())
                ContentHelper.updateContentJson(context, theContent);
            else ContentHelper.createContentJson(context, theContent);

            return img;
        } else
            throw new InvalidParameterException(String.format("Invalid image ID %s", imageId));
    }

    public void deleteBook(Consumer<Throwable> onError) {
        Content targetContent = collectionDao.selectContent(loadedBookId);
        if (null == targetContent) return;

        // Unplug image source listener (avoid displaying pages as they are being deleted; it messes up with DB transactions)
        if (currentImageSource != null) images.removeSource(currentImageSource);

        compositeDisposable.add(
                Completable.fromAction(() -> doDeleteBook(targetContent))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> {
                                    currentImageSource = null;
                                    // Switch to the next book if the list is populated (multi-book)
                                    if (!contentIds.isEmpty()) {
                                        contentIds.remove(currentContentIndex);
                                        if (currentContentIndex >= contentIds.size() && currentContentIndex > 0)
                                            currentContentIndex--;
                                        if (contentIds.size() > currentContentIndex)
                                            loadFromContent(contentIds.get(currentContentIndex));
                                    } else { // Close the viewer if the list is empty (single book)
                                        content.postValue(null);
                                    }
                                },
                                e -> {
                                    onError.accept(e);
                                    // Restore image source listener on error
                                    images.addSource(currentImageSource, imgs -> setImages(targetContent, imgs));
                                }
                        )
        );
    }

    private void doDeleteBook(@NonNull Content targetContent) throws ContentNotRemovedException {
        Helper.assertNonUiThread();
        ContentHelper.removeQueuedContent(getApplication(), collectionDao, targetContent);
    }

    public void deletePage(int pageIndex, Consumer<Throwable> onError) {
        List<ImageFile> imageFiles = images.getValue();
        if (imageFiles != null && imageFiles.size() > pageIndex)
            deletePages(Stream.of(imageFiles.get(pageIndex)).toList(), onError);
    }

    public void deletePages(List<ImageFile> pages, Consumer<Throwable> onError) {
        compositeDisposable.add(
                Completable.fromRunnable(() -> doDeletePages(pages))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> { // Update is done through LiveData
                                },
                                e -> {
                                    Timber.e(e);
                                    onError.accept(e);
                                }
                        )
        );
    }

    private void doDeletePages(@NonNull List<ImageFile> pages) {
        Helper.assertNonUiThread();
        ContentHelper.removePages(pages, collectionDao, getApplication());
    }

    public void setCover(ImageFile page) {
        compositeDisposable.add(
                Completable.fromRunnable(() -> doSetCover(page))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> { // Update is done through LiveData
                                },
                                Timber::e
                        )
        );
    }

    private void doSetCover(@NonNull ImageFile page) {
        Helper.assertNonUiThread();
        ContentHelper.setContentCover(page, collectionDao, getApplication());
    }

    public void loadNextContent() {
        if (currentContentIndex < contentIds.size() - 1) currentContentIndex++;
        if (!contentIds.isEmpty())
            loadFromContent(contentIds.get(currentContentIndex));
    }

    public void loadPreviousContent() {
        if (currentContentIndex > 0) currentContentIndex--;
        if (!contentIds.isEmpty())
            loadFromContent(contentIds.get(currentContentIndex));
    }

    private void processContent(@NonNull Content theContent) {
        currentContentIndex = contentIds.indexOf(theContent.getId());
        if (-1 == currentContentIndex) currentContentIndex = 0;

        theContent.setFirst(0 == currentContentIndex);
        theContent.setLast(currentContentIndex >= contentIds.size() - 1);
        content.postValue(theContent);

        // Observe the content's images
        // NB : It has to be dynamic to be updated when viewing a book from the queue screen
        if (currentImageSource != null) images.removeSource(currentImageSource);
        currentImageSource = collectionDao.getDownloadedImagesFromContent(theContent.getId());
        images.addSource(currentImageSource, imgs -> setImages(theContent, imgs));
    }

    private void postLoadProcessing(@NonNull Context context, @NonNull Content content) {
        // Cache images in the Json file
        cacheJson(context, content);
    }

    public void updateContentPreferences(@NonNull final Map<String, String> newPrefs) {
        Content theContent = content.getValue();
        if (null == theContent) return;

        compositeDisposable.add(
                Completable.fromRunnable(() -> doUpdateContentPreferences(getApplication().getApplicationContext(), theContent, newPrefs))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> { // Update is done through LiveData
                                },
                                Timber::e
                        )
        );
    }

    private void doUpdateContentPreferences(@NonNull final Context context, @NonNull final Content c, @NonNull final Map<String, String> newPrefs) {
        Helper.assertNonUiThread();

        Content theContent = collectionDao.selectContent(c.getId());
        if (null == theContent) return;

        theContent.setBookPreferences(newPrefs);
        // Persist in DB
        collectionDao.insertContent(theContent);
        // Repost the updated content
        content.postValue(theContent);

        // Persist in JSON
        if (!theContent.getJsonUri().isEmpty())
            ContentHelper.updateContentJson(context, theContent);
        else ContentHelper.createContentJson(context, theContent);
    }

    // Cache JSON URI in the database to speed up favouriting
    private void cacheJson(@NonNull Context context, @NonNull Content content) {
        Helper.assertNonUiThread();
        if (!content.getJsonUri().isEmpty() || content.isArchive()) return;

        DocumentFile folder = FileHelper.getFolderFromTreeUriString(context, content.getStorageUri());
        if (null == folder) return;

        DocumentFile foundFile = FileHelper.findFile(getApplication(), folder, Consts.JSON_FILE_NAME_V2);
        if (null == foundFile) {
            Timber.e("JSON file not detected in %s", content.getStorageUri());
            return;
        }

        // Cache the URI of the JSON to the database
        content.setJsonUri(foundFile.getUri().toString());
        collectionDao.insertContent(content);
    }

    @Nullable
    private File getOrCreatePictureCacheFolder() {
        File cacheDir = getApplication().getCacheDir();
        File pictureCacheDir = new File(cacheDir.getAbsolutePath() + File.separator + Consts.PICTURE_CACHE_FOLDER);
        if (pictureCacheDir.exists()) return pictureCacheDir;
        else if (pictureCacheDir.mkdir()) return pictureCacheDir;
        else return null;
    }

    public void markPageAsRead(int pageNumber) {
        readPageNumbers.add(pageNumber);
    }
}
