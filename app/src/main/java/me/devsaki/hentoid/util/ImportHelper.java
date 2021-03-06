package me.devsaki.hentoid.util;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import me.devsaki.hentoid.HentoidApp;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.activities.bundles.ImportActivityBundle;
import me.devsaki.hentoid.database.CollectionDAO;
import me.devsaki.hentoid.database.ObjectBoxDAO;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.json.JsonContent;
import me.devsaki.hentoid.notification.import_.ImportNotificationChannel;
import me.devsaki.hentoid.services.ExternalImportService;
import me.devsaki.hentoid.services.ImportService;
import timber.log.Timber;

import static android.os.Build.VERSION_CODES.O;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

public class ImportHelper {

    private ImportHelper() {
        throw new IllegalStateException("Utility class");
    }


    private static final String EXTERNAL_LIB_TAG = "external-library";

    private static final int RQST_STORAGE_PERMISSION_HENTOID = 3;
    private static final int RQST_STORAGE_PERMISSION_EXTERNAL = 4;

    @IntDef({Result.OK_EMPTY_FOLDER, Result.OK_LIBRARY_DETECTED, Result.OK_LIBRARY_DETECTED_ASK, Result.CANCELED, Result.INVALID_FOLDER, Result.DOWNLOAD_FOLDER, Result.APP_FOLDER, Result.CREATE_FAIL, Result.OTHER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {
        int OK_EMPTY_FOLDER = 0; // OK - Existing, empty Hentoid folder
        int OK_LIBRARY_DETECTED = 1; // OK - En existing Hentoid folder with books
        int OK_LIBRARY_DETECTED_ASK = 2; // OK - Existing Hentoid folder with books + we need to ask the user if he wants to import them
        int CANCELED = 3; // Operation canceled
        int INVALID_FOLDER = 4; // File or folder is invalid, cannot be found
        int APP_FOLDER = 5; // Selected folder is the app folder and can't be used as an external folder
        int DOWNLOAD_FOLDER = 6; // Selected folder is the device's download folder and can't be used as a primary folder (downloads visibility + storage calculation issues)
        int CREATE_FAIL = 7; // Hentoid folder could not be created
        int OTHER = 8; // Any other issue
    }

    private static final FileHelper.NameFilter hentoidFolderNames = displayName -> displayName.equalsIgnoreCase(Consts.DEFAULT_LOCAL_DIRECTORY)
            || displayName.equalsIgnoreCase(Consts.DEFAULT_LOCAL_DIRECTORY_OLD);

    /**
     * Import options for the Hentoid folder
     */
    public static class ImportOptions {
        public boolean rename; // If true, rename folders with current naming convention
        public boolean cleanNoJson; // If true, delete folders where no JSON file is found
        public boolean cleanNoImages; // If true, delete folders where no supported images are found
    }

    /**
     * Indicate whether the given folder name is a valid Hentoid folder name
     *
     * @param folderName Folder name to test
     * @return True if the given folder name is a valid Hentoid folder name; false if not
     */
    public static boolean isHentoidFolderName(@NonNull final String folderName) {
        return hentoidFolderNames.accept(folderName);
    }

    /**
     * Open the SAF folder picker
     *
     * @param caller     Caller fragment
     * @param isExternal True if the picker is used to import the external library; false if not TODO this parameter is weirdly designed
     */
    public static void openFolderPicker(@NonNull final Fragment caller, boolean isExternal) {
        Intent intent = getFolderPickerIntent(caller.requireContext());
        caller.startActivityForResult(intent, isExternal ? RQST_STORAGE_PERMISSION_EXTERNAL : RQST_STORAGE_PERMISSION_HENTOID);
    }

    /**
     * Open the SAF folder picker
     *
     * @param caller     Caller activity
     * @param isExternal True if the picker is used to import the external library; false if not TODO this parameter is weirdly designed
     */
    public static void openFolderPicker(@NonNull final Activity caller, boolean isExternal) {
        Intent intent = getFolderPickerIntent(caller.getParent());
        caller.startActivityForResult(intent, isExternal ? RQST_STORAGE_PERMISSION_EXTERNAL : RQST_STORAGE_PERMISSION_HENTOID);
    }

    /**
     * Get the intent for the SAF folder picker properly set up, positioned on the Hentoid primary folder
     *
     * @param context Context to be used
     * @return Intent for the SAF folder picker
     */
    private static Intent getFolderPickerIntent(@NonNull final Context context) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.putExtra(DocumentsContract.EXTRA_PROMPT, "Allow Write Permission");
        }
        // http://stackoverflow.com/a/31334967/1615876
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);

        // Start the SAF at the specified location
        if (Build.VERSION.SDK_INT >= O && !Preferences.getStorageUri().isEmpty()) {
            DocumentFile file = FileHelper.getFolderFromTreeUriString(context, Preferences.getStorageUri());
            if (file != null)
                intent.putExtra(EXTRA_INITIAL_URI, file.getUri());
        }

        HentoidApp.LifeCycleListener.disable(); // Prevents the app from displaying the PIN lock when returning from the SAF dialog
        return intent;
    }

    /**
     * Process the result of the SAF picker
     *
     * @param context     Context to be used
     * @param requestCode Request code transmitted by the picker
     * @param resultCode  Result code transmitted by the picker
     * @param data        Intent data transmitted by the picker
     * @return Standardized result - see ImportHelper.Result
     */
    public static @Result
    int processPickerResult(
            @NonNull final Context context,
            int requestCode,
            int resultCode,
            final Intent data) {
        HentoidApp.LifeCycleListener.enable(); // Restores autolock on app going to background

        // Return from the SAF picker
        if ((requestCode == RQST_STORAGE_PERMISSION_HENTOID || requestCode == RQST_STORAGE_PERMISSION_EXTERNAL) && resultCode == Activity.RESULT_OK) {
            // Get Uri from Storage Access Framework
            Uri treeUri = data.getData();
            if (treeUri != null) {
                if (requestCode == RQST_STORAGE_PERMISSION_EXTERNAL)
                    return setAndScanExternalFolder(context, treeUri);
                else
                    return setAndScanHentoidFolder(context, treeUri, true, null);
            } else return Result.INVALID_FOLDER;
        } else if (resultCode == Activity.RESULT_CANCELED) {
            return Result.CANCELED;
        } else return Result.OTHER;
    }

    /**
     * Scan the given tree URI for a Hentoid folder
     * If none is found there, try to create one
     *
     * @param context         Context to be used
     * @param treeUri         Tree URI of the folder where to find or create the Hentoid folder
     * @param askScanExisting If true and an existing non-empty Hentoid folder is found, the user will be asked if he wants to import its contents
     * @param options         Import options - See ImportHelper.ImportOptions
     * @return Standardized result - see ImportHelper.Result
     */
    public static @Result
    int setAndScanHentoidFolder(
            @NonNull final Context context,
            @NonNull final Uri treeUri,
            boolean askScanExisting,
            @Nullable final ImportOptions options) {

        // Persist I/O permissions
        Uri externalUri = null;
        if (!Preferences.getExternalLibraryUri().isEmpty())
            externalUri = Uri.parse(Preferences.getExternalLibraryUri());
        FileHelper.persistNewUriPermission(context, treeUri, externalUri);

        // Check if the folder exists
        DocumentFile docFile = DocumentFile.fromTreeUri(context, treeUri);
        if (null == docFile || !docFile.exists()) {
            Timber.e("Could not find the selected file %s", treeUri.toString());
            return Result.INVALID_FOLDER;
        }
        // Check if the folder is not the device's Download folder
        List<String> pathSegments = treeUri.getPathSegments();
        if (pathSegments.size() > 1 && (pathSegments.get(1).equalsIgnoreCase("download") || pathSegments.get(1).equalsIgnoreCase("primary:download"))) {
            Timber.e("Device's download folder detected : %s", treeUri.toString());
            return Result.DOWNLOAD_FOLDER;
        }
        // Retrieve or create the Hentoid folder
        DocumentFile hentoidFolder = addHentoidFolder(context, docFile);
        if (null == hentoidFolder) {
            Timber.e("Could not create Hentoid folder in root %s", docFile.getUri().toString());
            return Result.CREATE_FAIL;
        }
        // Set the folder as the app's downloads folder
        int result = FileHelper.checkAndSetRootFolder(context, hentoidFolder);
        if (result < 0) {
            Timber.e("Could not set the selected root folder (error = %d) %s", result, hentoidFolder.getUri().toString());
            return Result.INVALID_FOLDER;
        }

        // Scan the folder for an existing library; start the import
        if (hasBooks(context)) {
            if (!askScanExisting) {
                runHentoidImport(context, options);
                return Result.OK_LIBRARY_DETECTED;
            } else return Result.OK_LIBRARY_DETECTED_ASK;
        } else {
            // New library created - drop and recreate db (in case user is re-importing)
            CollectionDAO dao = new ObjectBoxDAO(context);
            try {
                dao.deleteAllInternalBooks(true);
            } finally {
                dao.cleanup();
            }
            return Result.OK_EMPTY_FOLDER;
        }
    }

    /**
     * Scan the given tree URI for external books or Hentoid books
     *
     * @param context Context to be used
     * @param treeUri Tree URI of the folder where to find external books or Hentoid books
     * @return Standardized result - see ImportHelper.Result
     */
    public static @Result
    int setAndScanExternalFolder(
            @NonNull final Context context,
            @NonNull final Uri treeUri) {

        // Persist I/O permissions
        Uri hentoidUri = null;
        if (!Preferences.getStorageUri().isEmpty())
            hentoidUri = Uri.parse(Preferences.getStorageUri());
        FileHelper.persistNewUriPermission(context, treeUri, hentoidUri);

        // Check if the folder exists
        DocumentFile docFile = DocumentFile.fromTreeUri(context, treeUri);
        if (null == docFile || !docFile.exists()) {
            Timber.e("Could not find the selected file %s", treeUri.toString());
            return Result.INVALID_FOLDER;
        }
        String folderUri = docFile.getUri().toString();
        if (folderUri.equalsIgnoreCase(Preferences.getStorageUri())) {
            Timber.w("Trying to set the app folder as the external library %s", treeUri.toString());
            return Result.APP_FOLDER;
        }
        // Set the folder as the app's external library folder
        Preferences.setExternalLibraryUri(folderUri);

        // Start the import
        runExternalImport(context);
        return Result.OK_LIBRARY_DETECTED;
    }

    /**
     * Show the dialog to ask the user if he wants to import existing books
     *
     * @param context        Context to be used
     * @param cancelCallback Callback to run when the dialog is canceled
     */
    public static void showExistingLibraryDialog(
            @NonNull final Context context,
            @Nullable Runnable cancelCallback
    ) {
        new MaterialAlertDialogBuilder(context, ThemeHelper.getIdForCurrentTheme(context, R.style.Theme_Light_Dialog))
                .setIcon(R.drawable.ic_warning)
                .setCancelable(false)
                .setTitle(R.string.app_name)
                .setMessage(R.string.contents_detected)
                .setPositiveButton(R.string.yes,
                        (dialog1, which) -> {
                            dialog1.dismiss();
                            runHentoidImport(context, null);
                        })
                .setNegativeButton(R.string.no,
                        (dialog2, which) -> {
                            dialog2.dismiss();
                            if (cancelCallback != null) cancelCallback.run();
                        })
                .create()
                .show();
    }

    /**
     * Detect whether the current Hentoid folder contains books or not
     * by counting the elements inside each site's download folder (but not its subfolders)
     * <p>
     * NB : this method works approximately because it doesn't try to count JSON files
     * However, findFilesRecursively -the method used by ImportService- is too slow on certain phones
     * and might cause freezes -> we stick to that approximate method for ImportActivity
     *
     * @param context Context to be used
     * @return True if the current Hentoid folder contains at least one book; false if not
     */
    private static boolean hasBooks(@NonNull final Context context) {
        List<DocumentFile> downloadDirs = new ArrayList<>();

        ContentProviderClient client = context.getContentResolver().acquireContentProviderClient(Uri.parse(Preferences.getStorageUri()));
        if (null == client) return false;
        try {
            for (Site s : Site.values()) {
                DocumentFile downloadDir = ContentHelper.getOrCreateSiteDownloadDir(context, client, s);
                if (downloadDir != null) downloadDirs.add(downloadDir);
            }

            for (DocumentFile downloadDir : downloadDirs) {
                List<DocumentFile> contentFiles = FileHelper.listFolders(context, downloadDir, client);
                if (!contentFiles.isEmpty()) return true;
            }
        } finally {
            // ContentProviderClient.close only available on API level 24+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                client.close();
            else
                client.release();
        }

        return false;
    }


    @Nullable
    private static DocumentFile addHentoidFolder(@NonNull final Context context, @NonNull final DocumentFile baseFolder) {
        String folderName = baseFolder.getName();
        if (null == folderName) folderName = "";

        // Don't create a .Hentoid subfolder inside the .Hentoid (or Hentoid) folder the user just selected...
        if (!isHentoidFolderName(folderName)) {
            DocumentFile targetFolder = getExistingHentoidDirFrom(context, baseFolder);

            // If not, create one
            if (targetFolder.getUri().equals(baseFolder.getUri()))
                return targetFolder.createDirectory(Consts.DEFAULT_LOCAL_DIRECTORY);
            else return targetFolder;
        }
        return baseFolder;
    }

    // Try and detect any ".Hentoid" or "Hentoid" folder inside the selected folder
    public static DocumentFile getExistingHentoidDirFrom(@NonNull final Context context, @NonNull final DocumentFile root) {
        if (!root.exists() || !root.isDirectory() || null == root.getName()) return root;

        // Selected folder _is_ the Hentoid folder
        if (isHentoidFolderName(root.getName())) return root;

        // If not, look for it in its children
        List<DocumentFile> hentoidDirs = FileHelper.listFoldersFilter(context, root, hentoidFolderNames);
        if (!hentoidDirs.isEmpty()) return hentoidDirs.get(0);
        else return root;
    }

    private static void runHentoidImport(
            @NonNull final Context context,
            @Nullable final ImportOptions options
    ) {
        ImportNotificationChannel.init(context);
        Intent intent = ImportService.makeIntent(context);

        ImportActivityBundle.Builder builder = new ImportActivityBundle.Builder();
        builder.setRefreshRename(null != options && options.rename);
        builder.setRefreshCleanNoJson(null != options && options.cleanNoJson);
        builder.setRefreshCleanNoImages(null != options && options.cleanNoImages);
        intent.putExtras(builder.getBundle());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static void runExternalImport(
            @NonNull final Context context
    ) {
        ImportNotificationChannel.init(context);
        Intent intent = ExternalImportService.makeIntent(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static Content scanBookFolder(
            @NonNull final Context context,
            @NonNull final DocumentFile bookFolder,
            @NonNull final ContentProviderClient client,
            @NonNull final List<String> parentNames,
            @NonNull final StatusContent targetStatus,
            @NonNull final CollectionDAO dao,
            @Nullable final List<DocumentFile> imageFiles,
            @Nullable final DocumentFile jsonFile) {
        Timber.d(">>>> scan book folder %s", bookFolder.getUri());

        Content result = null;
        if (jsonFile != null) {
            try {
                JsonContent content = JsonHelper.jsonToObject(context, jsonFile, JsonContent.class);
                result = content.toEntity(dao);
                result.setJsonUri(jsonFile.getUri().toString());
            } catch (IOException ioe) {
                Timber.w(ioe);
            }
        }
        if (null == result) {
            String title = bookFolder.getName();
            if (null == title) title = "";
            title = title.replace("_", " ");
            // Remove expressions between []'s
            title = title.replaceAll("\\[[^(\\[\\])]*\\]", "");
            title = title.trim();
            result = new Content().setTitle(title);
            Site site = Site.NONE;
            if (!parentNames.isEmpty()) {
                for (String parent : parentNames)
                    for (Site s : Site.values())
                        if (parent.equalsIgnoreCase(s.getFolder())) {
                            site = s;
                            break;
                        }
            }
            result.setSite(site);
            result.setDownloadDate(bookFolder.lastModified());
            result.addAttributes(parentNamesAsTags(parentNames));
        }
        if (targetStatus.equals(StatusContent.EXTERNAL))
            result.addAttributes(newExternalAttribute());

        result.setStatus(targetStatus).setStorageUri(bookFolder.getUri().toString());
        List<ImageFile> images = new ArrayList<>();
        scanImages(context, bookFolder, client, targetStatus, false, images, imageFiles);
        boolean coverExists = Stream.of(images).anyMatch(ImageFile::isCover);
        if (!coverExists) createCover(images);
        result.setImageFiles(images);
        if (0 == result.getQtyPages())
            result.setQtyPages(images.size() - 1); // Minus the cover
        result.computeSize();
        return result;
    }

    public static Content scanChapterFolders(
            @NonNull final Context context,
            @NonNull final DocumentFile parent,
            @NonNull final List<DocumentFile> chapterFolders,
            @NonNull final ContentProviderClient client,
            @NonNull final List<String> parentNames,
            @NonNull final CollectionDAO dao,
            @Nullable final DocumentFile jsonFile) {
        Timber.d(">>>> scan chapter folder %s", parent.getUri());

        Content result = null;
        if (jsonFile != null) {
            try {
                JsonContent content = JsonHelper.jsonToObject(context, jsonFile, JsonContent.class);
                result = content.toEntity(dao);
                result.setJsonUri(jsonFile.getUri().toString());
            } catch (IOException ioe) {
                Timber.w(ioe);
            }
        }
        if (null == result) {
            result = new Content().setSite(Site.NONE).setTitle((null == parent.getName()) ? "" : parent.getName()).setUrl("");
            result.setDownloadDate(parent.lastModified());
            result.addAttributes(parentNamesAsTags(parentNames));
        }
        result.addAttributes(newExternalAttribute());

        result.setStatus(StatusContent.EXTERNAL).setStorageUri(parent.getUri().toString());
        List<ImageFile> images = new ArrayList<>();
        // Scan pages across all subfolders
        for (DocumentFile chapterFolder : chapterFolders)
            scanImages(context, chapterFolder, client, StatusContent.EXTERNAL, true, images, null);
        boolean coverExists = Stream.of(images).anyMatch(ImageFile::isCover);
        if (!coverExists) createCover(images);
        result.setImageFiles(images);
        if (0 == result.getQtyPages())
            result.setQtyPages(images.size() - 1); // Minus the cover
        result.computeSize();
        return result;
    }

    private static void scanImages(
            @NonNull final Context context,
            @NonNull final DocumentFile bookFolder,
            @NonNull final ContentProviderClient client,
            @NonNull final StatusContent targetStatus,
            boolean addFolderNametoImgName,
            @NonNull final List<ImageFile> images,
            @Nullable List<DocumentFile> imageFiles) {
        int order = (images.isEmpty()) ? 0 : Stream.of(images).map(ImageFile::getOrder).max(Integer::compareTo).get();
        String folderName = (null == bookFolder.getName()) ? "" : bookFolder.getName();
        if (null == imageFiles)
            imageFiles = FileHelper.listFiles(context, bookFolder, client, ImageHelper.getImageNamesFilter());

        String namePrefix = "";
        if (addFolderNametoImgName) namePrefix = folderName + "-";

        images.addAll(ContentHelper.createImageListFromFiles(imageFiles, targetStatus, order, namePrefix));
    }

    /**
     * Create a cover and add it to the given image list
     *
     * @param images Image list to generate the cover from (and add it to)
     */
    public static void createCover(@NonNull final List<ImageFile> images) {
        if (!images.isEmpty()) {
            ImageFile firstImg = images.get(0);
            ImageFile cover = new ImageFile(0, "", images.get(0).getStatus(), images.size());
            cover.setName(Consts.THUMB_FILE_NAME);
            cover.setUrl(firstImg.getUrl());
            cover.setFileUri(firstImg.getFileUri());
            cover.setSize(firstImg.getSize());
            cover.setMimeType(firstImg.getMimeType());
            cover.setIsCover(true);
            images.add(0, cover);
        }
    }

    private static List<Attribute> newExternalAttribute() {
        return Stream.of(new Attribute(AttributeType.TAG, EXTERNAL_LIB_TAG, EXTERNAL_LIB_TAG, Site.NONE)).toList();
    }

    public static void removeExternalAttribute(@NonNull final Content content) {
        content.putAttributes(Stream.of(content.getAttributes()).filterNot(a -> a.getName().equalsIgnoreCase(EXTERNAL_LIB_TAG)).toList());
    }

    private static AttributeMap parentNamesAsTags(@NonNull final List<String> parentNames) {
        AttributeMap result = new AttributeMap();
        // Don't include the very first one, it's the name of the root folder of the library
        if (parentNames.size() > 1) {
            for (int i = 1; i < parentNames.size(); i++)
                result.add(new Attribute(AttributeType.TAG, parentNames.get(i), parentNames.get(i), Site.NONE));
        }
        return result;
    }

    public static List<Content> scanForArchives(
            @NonNull final Context context,
            @NonNull final List<DocumentFile> subFolders,
            @NonNull final ContentProviderClient client,
            @NonNull final List<String> parentNames) {
        List<Content> result = new ArrayList<>();

        for (DocumentFile subfolder : subFolders) {
            List<DocumentFile> archives = FileHelper.listFiles(context, subfolder, client, ArchiveHelper.getArchiveNamesFilter());
            for (DocumentFile archive : archives) {
                Content c = scanArchive(context, archive, parentNames, StatusContent.EXTERNAL);
                if (!c.getStatus().equals(StatusContent.IGNORED))
                    result.add(c);
            }
        }

        return result;
    }

    public static Content scanArchive(
            @NonNull final Context context,
            @NonNull final DocumentFile archive,
            @NonNull final List<String> parentNames,
            @NonNull final StatusContent targetStatus) {
        List<ArchiveHelper.ArchiveEntry> entries = Collections.emptyList();

        try {
            entries = ArchiveHelper.getArchiveEntries(context, archive);
        } catch (Exception e) {
            Timber.w(e);
        }

        // Look for the folder with the most images
        Collection<List<ArchiveHelper.ArchiveEntry>> imageEntries = Stream.of(entries)
                .filter(s -> ImageHelper.isImageExtensionSupported(FileHelper.getExtension(s.path)))
                .collect(Collectors.groupingBy(ImportHelper::getFolders))
                .values();

        if (imageEntries.isEmpty()) return new Content().setStatus(StatusContent.IGNORED);

        // Sort by number of images desc
        List<ArchiveHelper.ArchiveEntry> entryList = Stream.of(imageEntries).sortBy(ie -> -ie.size()).toList().get(0);

        List<ImageFile> images = ContentHelper.createImageListFromArchiveEntries(archive.getUri(), entryList, targetStatus, 1, "");
        boolean coverExists = Stream.of(images).anyMatch(ImageFile::isCover);
        if (!coverExists) createCover(images);

        // Create content envelope
        Content result = new Content().setSite(Site.NONE).setTitle((null == archive.getName()) ? "" : FileHelper.getFileNameWithoutExtension(archive.getName())).setUrl("");
        result.setDownloadDate(archive.lastModified());
        result.addAttributes(parentNamesAsTags(parentNames));
        result.addAttributes(newExternalAttribute());

        result.setStatus(targetStatus).setStorageUri(archive.getUri().toString()); // Here storage URI is a file URI, not a folder

        result.setImageFiles(images);
        if (0 == result.getQtyPages())
            result.setQtyPages(images.size() - 1); // Minus the cover
        result.computeSize();
        return result;
    }

    private static String getFolders(@NonNull final ArchiveHelper.ArchiveEntry entry) {
        String path = entry.path;
        int separatorIndex = path.lastIndexOf('/');
        if (-1 == separatorIndex) return "";

        return path.substring(0, separatorIndex);
    }
}
