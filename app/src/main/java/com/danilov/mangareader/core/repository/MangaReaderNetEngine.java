package com.danilov.mangareader.core.repository;

import android.util.Log;

import com.danilov.mangareader.core.http.HttpBytesReader;
import com.danilov.mangareader.core.http.HttpRequestException;
import com.danilov.mangareader.core.model.Manga;
import com.danilov.mangareader.core.model.MangaChapter;
import com.danilov.mangareader.core.model.MangaSuggestion;
import com.danilov.mangareader.core.util.IoUtils;
import com.danilov.mangareader.core.util.ServiceContainer;
import com.danilov.mangareader.core.util.Utils;

import org.apache.http.protocol.HTTP;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by Semyon on 19.10.2014.
 */
public class MangaReaderNetEngine implements RepositoryEngine {

    private static final String TAG = "MangaReaderNetEngine";

    private String baseSuggestionUri = "http://www.mangareader.net/actions/search/?q=";

    private String baseUri = "http://www.mangareader.net";

    @Override
    public String getLanguage() {
        return "English";
    }

    @Override
    public List<MangaSuggestion> getSuggestions(String query) throws RepositoryException {
        HttpBytesReader httpBytesReader = ServiceContainer.getService(HttpBytesReader.class);
        List<MangaSuggestion> suggestions = null;
        if (httpBytesReader != null) {
            Exception ex = null;
            try {
                String uri = baseSuggestionUri + URLEncoder.encode(query, Charset.forName(HTTP.UTF_8).name()) + "&limit=100";
                byte[] response = httpBytesReader.fromUri(uri);
                String responseString = IoUtils.convertBytesToString(response);
                suggestions = parseSuggestionsResponse(responseString);
            } catch (UnsupportedEncodingException e) {
                ex = e;
            } catch (HttpRequestException e) {
                ex = e;
            }
            if (ex != null) {
                throw new RepositoryException(ex.getMessage());
            }
        }
        return suggestions;
    }

    private List<MangaSuggestion> parseSuggestionsResponse(final String responseString) {
        List<MangaSuggestion> mangaSuggestions = new ArrayList<MangaSuggestion>();
        String[] lines = responseString.split("\n");
        if (lines.length > 0) {
            for (String line : lines) {
                String[] content = line.split("\\|");
                if (content.length > 5) {
                    String title = content[0];
                    String link = content[4];
                    MangaSuggestion suggestion = new MangaSuggestion(title, link, Repository.MANGAREADERNET);
                    mangaSuggestions.add(suggestion);
                }
            }
        }
        return mangaSuggestions;
    }

    @Override
    public List<Manga> queryRepository(String query) {
        return null;
    }

    @Override
    public boolean queryForMangaDescription(Manga manga) throws RepositoryException {
        HttpBytesReader httpBytesReader = ServiceContainer.getService(HttpBytesReader.class);
        if (httpBytesReader != null) {
            String uri = baseUri + manga.getUri();
            byte[] response = null;
            try {
                response = httpBytesReader.fromUri(uri);
            } catch (HttpRequestException e) {
                if (e.getMessage() != null) {
                    Log.d(TAG, e.getMessage());
                } else {
                    Log.d(TAG, "Failed to load manga description");
                }
                throw new RepositoryException(e.getMessage());
            }
            String responseString = IoUtils.convertBytesToString(response);
            return parseMangaDescriptionResponse(manga, Utils.toDocument(responseString));
        }
        return false;
    }


    @Override
    public boolean queryForChapters(final Manga manga) throws RepositoryException {
        HttpBytesReader httpBytesReader = ServiceContainer.getService(HttpBytesReader.class);
        if (httpBytesReader != null) {
            String uri = baseUri + manga.getUri();
            byte[] response = null;
            try {
                response = httpBytesReader.fromUri(uri);
            } catch (HttpRequestException e) {
                if (e.getMessage() != null) {
                    Log.d(TAG, e.getMessage());
                } else {
                    Log.d(TAG, "Failed to load manga description");
                }
                throw new RepositoryException(e.getMessage());
            }
            String responseString = IoUtils.convertBytesToString(response);
            List<MangaChapter> chapters = parseMangaChaptersResponse(manga, Utils.toDocument(responseString));
            manga.setChapters(chapters);
            return true;
        }
        return false;
    }

    @Override
    public List<String> getChapterImages(final MangaChapter chapter) throws RepositoryException {
        HttpBytesReader httpBytesReader = ServiceContainer.getService(HttpBytesReader.class);
        String[] imageUrisArray = null;
        if (httpBytesReader != null) {
            String uri = baseUri + chapter.getUri();
            byte[] response = null;
            try {
                response = httpBytesReader.fromUri(uri);
            } catch (HttpRequestException e) {
                if (e.getMessage() != null) {
                    Log.d(TAG, e.getMessage());
                } else {
                    Log.d(TAG, "Failed to load manga description");
                }
                throw new RepositoryException(e.getMessage());
            }
            String responseString = IoUtils.convertBytesToString(response);
            List<String> pageUris = getImagePagesUris(Utils.toDocument(responseString));
            imageUrisArray = new String[pageUris.size()];
            List<Thread> threads = new LinkedList<Thread>();
            int i = 0;
            for (String pageUri : pageUris) {
                PageImageThread pageImageThread = new PageImageThread(httpBytesReader, pageUri, i, imageUrisArray);
                threads.add(pageImageThread);
                pageImageThread.start();
                i++;
            }
            for (Thread t : threads) {
                while (t.isAlive()) {
                    try {
                        t.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return Arrays.asList(imageUrisArray);
    }

    private class PageImageThread extends Thread {

        private HttpBytesReader httpBytesReader;
        private String pageUri;
        private int pos;

        private String[] arrayRef;

        public PageImageThread(final HttpBytesReader reader, final String pageUri, final int pos, final String[] arrayRef) {
            this.httpBytesReader = reader;
            this.pageUri = pageUri;
            this.arrayRef = arrayRef;
            this.pos = pos;
        }

        @Override
        public void run() {
            String uri = baseUri + pageUri;
            byte[] response = null;
            try {
                response = httpBytesReader.fromUri(uri);
            } catch (HttpRequestException e) {
                if (e.getMessage() != null) {
                    Log.d(TAG, e.getMessage());
                } else {
                    Log.d(TAG, "Failed to load manga description");
                }
            }
            String responseString = IoUtils.convertBytesToString(response);
            String imageUri = parsePageForImageUrl(Utils.toDocument(responseString));
            arrayRef[pos] = imageUri;
        }

    }

    @Override
    public String getBaseSearchUri() {
        return null;
    }

    @Override
    public String getBaseUri() {
        return baseUri;
    }

    private String imgContainerId = "mangaimg";
    private String descriptionContainerId = "readmangasum";
    private String chaptersContainerId = "listing";

    private boolean parseMangaDescriptionResponse(Manga manga, Document document) {
        Element imageElement = document.getElementById(imgContainerId);
        if (imageElement == null) {
            manga.setCoverUri("");
        } else {
            Elements imgs = imageElement.getElementsByTag("img");
            if (!imgs.isEmpty()) {
                String coverUrl = imgs.get(0).attr("src");
                manga.setCoverUri(coverUrl);
            } else {
                manga.setCoverUri("");
            }
        }
        Element descriptionContainer = document.getElementById(descriptionContainerId);
        if (descriptionContainer != null) {
            Elements desc = descriptionContainer.getElementsByTag("p");
            if (!desc.isEmpty()) {
                String description = desc.text();
                manga.setDescription(description);
            } else {
                manga.setDescription("");
            }
        } else {
            manga.setDescription("");
        }
        Element chaptersContainer = document.getElementById(chaptersContainerId);
        if (chaptersContainer == null) {
            manga.setChaptersQuantity(0);
        } else {
            Elements chapters = chaptersContainer.getElementsByTag("a");
            manga.setChaptersQuantity(chapters.size());
        }
        return true;
    }


    //html values
    private String linkValueAttr = "href";

    private List<MangaChapter> parseMangaChaptersResponse(final Manga manga, final Document document) {
        Element chaptersContainer = document.getElementById(chaptersContainerId);
        if (chaptersContainer == null) {
            manga.setChaptersQuantity(0);
            return null;
        }
        Elements chapterLinks = chaptersContainer.getElementsByTag("a");
        List<MangaChapter> chapters = new ArrayList<MangaChapter>(chapterLinks.size());
        int number = 0;
        for (Element element : chapterLinks) {
            String link = element.attr(linkValueAttr);
            String title = element.parent().text();
            MangaChapter chapter = new MangaChapter(title, number, link);
            chapters.add(chapter);
            number++;
        }
        manga.setChaptersQuantity(chapters.size());
        return chapters;
    }

    private String selectorId = "pageMenu";

    private List<String> getImagePagesUris(final Document document) {
        Element element = document.getElementById(selectorId);
        List<String> uris = new LinkedList<String>();
        if (element == null) {
            return uris;
        }
        for (Element option : element.children()) {
            uris.add(option.attr("value"));
        }
        return uris;
    }

    private String imgHolderId = "imgholder";
    private String imgElement = "img";

    private String parsePageForImageUrl(final Document document) {
        Element element = document.getElementById(imgHolderId);
        if (element == null) {
            return null;
        }
        Elements imgs = element.getElementsByTag(imgElement);
        if (imgs.isEmpty()) {
            return null;
        }
        return imgs.get(0).attr("src");
    }

}