package me.devsaki.hentoid.parsers.images;

import android.util.Pair;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.events.DownloadEvent;
import me.devsaki.hentoid.util.JsonHelper;
import me.devsaki.hentoid.util.exception.PreparationInterruptedException;
import me.devsaki.hentoid.util.network.HttpHelper;
import timber.log.Timber;

import static me.devsaki.hentoid.util.network.HttpHelper.getOnlineDocument;

/**
 * Created by robb_w on 2020/11
 * Handles parsing of content from manhwahentai.me
 */
public class ManhwaParser extends BaseParser {

    private boolean processHalted = false;

    @Override
    protected List<String> parseImages(@NonNull Content content) throws Exception {
        EventBus.getDefault().register(this);
        List<String> result = new ArrayList<>();

        try {
            String downloadParamsStr = content.getDownloadParams();
            if (null == downloadParamsStr || downloadParamsStr.isEmpty()) {
                Timber.e("Download parameters not set");
                return result;
            }

            Map<String, String> downloadParams;
            try {
                downloadParams = JsonHelper.jsonToObject(downloadParamsStr, JsonHelper.MAP_STRINGS);
            } catch (IOException e) {
                Timber.e(e);
                return result;
            }

            List<Pair<String, String>> headers = new ArrayList<>();
            String cookieStr = downloadParams.get(HttpHelper.HEADER_COOKIE_KEY);
            if (null != cookieStr)
                headers.add(new Pair<>(HttpHelper.HEADER_COOKIE_KEY, cookieStr));

            // 1. Scan the gallery page for chapter URLs
            // NB : We can't just guess the URLs by starting to 1 and increment them
            // because the site provides "subchapters" (e.g. 4.6, 2.5)
            List<String> chapterUrls = new ArrayList<>();
            Document doc = getOnlineDocument(content.getGalleryUrl(), headers, Site.MANHWA.canKnowHentoidAgent());
            if (doc != null) {
                List<Element> chapters = doc.select("[class^=wp-manga-chapter] a");
                for (Element e : chapters) chapterUrls.add(e.attr("href"));
            }
            Collections.reverse(chapterUrls); // Put the chapters in the correct reading order

            progressStart(chapterUrls.size());

            // 2. Open each chapter URL and get the image data until all images are found
            for (String url : chapterUrls) {
                if (processHalted) break;
                doc = getOnlineDocument(url, headers, Site.MANHWA.canKnowHentoidAgent());
                if (doc != null) {
                    List<Element> images = doc.select(".reading-content img");
                    result.addAll(Stream.of(images).map(i -> i.attr("src")).filterNot(String::isEmpty).toList());
                }
                progressPlus();
            }
            progressComplete();

            // If the process has been halted manually, the result is incomplete and should not be returned as is
            if (processHalted) throw new PreparationInterruptedException();

            return result;
        } finally {
            EventBus.getDefault().unregister(this);
        }
    }

    /**
     * Download event handler called by the event bus
     *
     * @param event Download event
     */
    @Subscribe
    public void onDownloadEvent(DownloadEvent event) {
        switch (event.eventType) {
            case DownloadEvent.EV_PAUSE:
            case DownloadEvent.EV_CANCEL:
            case DownloadEvent.EV_SKIP:
                processHalted = true;
                break;
            default:
                // Other events aren't handled here
        }
    }
}