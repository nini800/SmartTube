package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorting of the playlist screen. The choice is remembered per playlist.<br/>
 * YouTube has no sort for playlists on TV, so a sorted playlist is loaded completely and sorted here.
 */
public class PlaylistSorter {
    private static final String TAG = PlaylistSorter.class.getSimpleName();
    private static final String PREFS_NAME = "pinou_playlist_sort";
    public static final int SORT_PLAYLIST_ORDER = 0;
    public static final int SORT_UPLOAD_DATE = 1;
    public static final int SORT_TITLE = 2;
    public static final int SORT_CHANNEL = 3;
    public static final int SORT_DURATION = 4;
    public static final int SORT_VIEWS = 5;
    public static final int[] SORT_TYPES = {SORT_PLAYLIST_ORDER, SORT_UPLOAD_DATE, SORT_TITLE, SORT_CHANNEL, SORT_DURATION, SORT_VIEWS};
    /**
     * Safety limit: 200 pages is about 20 000 videos.
     */
    private static final int MAX_PAGES = 200;
    private static final long UNKNOWN = Long.MIN_VALUE;
    private static final Pattern AGE_PATTERN = Pattern.compile(
            "(\\d+)\\s*(seconds?|secondes?|minutes?|hours?|heures?|days?|jours?|weeks?|semaines?|months?|mois|years?|années?|ans?)(?![a-z])");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d[\\d ,.]*");

    public static class Sort {
        public final int type;
        public final boolean descending;

        public Sort(int type, boolean descending) {
            this.type = type;
            this.descending = descending;
        }

        public boolean isDefault() {
            return type == SORT_PLAYLIST_ORDER && !descending;
        }
    }

    /**
     * Direction used when picking a sort type: newest/most viewed first, otherwise ascending.
     */
    public static boolean isDescendingByDefault(int type) {
        return type == SORT_UPLOAD_DATE || type == SORT_VIEWS;
    }

    public static Sort getSort(Context context, String playlistKey) {
        String value = getPrefs(context).getString(playlistKey, null);

        if (value != null) {
            String[] parts = value.split(",");
            try {
                return new Sort(Integer.parseInt(parts[0]), parts.length > 1 && Boolean.parseBoolean(parts[1]));
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        return new Sort(SORT_PLAYLIST_ORDER, false);
    }

    public static void setSort(Context context, String playlistKey, Sort sort) {
        SharedPreferences.Editor editor = getPrefs(context).edit();

        if (sort.isDefault()) {
            editor.remove(playlistKey);
        } else {
            editor.putString(playlistKey, sort.type + "," + sort.descending);
        }

        editor.apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Blocking. Loads all pages of the playlist and returns them sorted as one group.
     */
    public static VideoGroup loadAllSorted(ContentService service, MediaGroup firstPage, String title, Sort sort) {
        VideoGroup all = VideoGroup.from(firstPage);
        MediaGroup page = firstPage;

        for (int i = 1; i < MAX_PAGES && page != null; i++) {
            page = service.continueGroup(page);

            if (page != null) {
                VideoGroup.from(all, page);
            }
        }

        if (TextUtils.isEmpty(all.getTitle())) {
            all.setTitle(title);
        }

        Log.d(TAG, "Sorting %s videos of %s", all.getSize(), all.getTitle());

        VideoGroup sorted = VideoGroup.from(sort(all.getVideos(), sort));
        sorted.setTitle(all.getTitle());

        return sorted;
    }

    public static List<Video> sort(List<Video> videos, Sort sort) {
        List<Video> result = new ArrayList<>(videos);

        if (sort.type == SORT_PLAYLIST_ORDER) {
            if (sort.descending) {
                Collections.reverse(result);
            }
            return result;
        }

        if (sort.type == SORT_TITLE || sort.type == SORT_CHANNEL) {
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            Comparator<Video> comparator = (a, b) -> collator.compare(textKey(a, sort.type), textKey(b, sort.type));
            Collections.sort(result, sort.descending ? Collections.reverseOrder(comparator) : comparator); // stable: ties keep the playlist order
            return result;
        }

        // Numeric keys. Unknown values always go last.
        List<long[]> keys = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            keys.add(new long[] {numericKey(result.get(i), sort.type), i});
        }

        Collections.sort(keys, (a, b) -> {
            if (a[0] == UNKNOWN || b[0] == UNKNOWN) {
                return a[0] == b[0] ? Long.compare(a[1], b[1]) : (a[0] == UNKNOWN ? 1 : -1);
            }
            int compare = sort.descending ? Long.compare(b[0], a[0]) : Long.compare(a[0], b[0]);
            return compare != 0 ? compare : Long.compare(a[1], b[1]);
        });

        List<Video> sorted = new ArrayList<>();
        for (long[] key : keys) {
            sorted.add(result.get((int) key[1]));
        }

        return sorted;
    }

    private static String textKey(Video video, int type) {
        String value = type == SORT_TITLE ? video.getTitle() : video.getAuthor();
        return value != null ? value : "";
    }

    private static long numericKey(Video video, int type) {
        switch (type) {
            case SORT_DURATION:
                long durationMs = video.getDurationMs();
                return durationMs > 0 ? durationMs : UNKNOWN;
            case SORT_UPLOAD_DATE:
                long ageSec = parseAgeSec(video);
                return ageSec != UNKNOWN ? -ageSec : UNKNOWN; // younger = more recent
            case SORT_VIEWS:
                return parseViews(video);
        }

        return UNKNOWN;
    }

    /**
     * Approximate age from texts like "3 weeks ago" or "il y a 3 semaines".
     */
    private static long parseAgeSec(Video video) {
        List<String> candidates = new ArrayList<>();
        if (video.mediaItem != null && video.mediaItem.getProductionDate() != null) {
            candidates.add(video.mediaItem.getProductionDate());
        }
        candidates.addAll(getInfoParts(video));

        for (String text : candidates) {
            String lower = normalize(text);
            if (!lower.contains("ago") && !lower.contains("il y a")) {
                continue;
            }
            Matcher matcher = AGE_PATTERN.matcher(lower);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1)) * unitSec(matcher.group(2));
            }
        }

        return UNKNOWN;
    }

    private static long unitSec(String unit) {
        if (unit.startsWith("sec")) return 1;
        if (unit.startsWith("min")) return 60;
        if (unit.startsWith("h")) return 3_600;
        if (unit.startsWith("d") || unit.startsWith("j")) return 86_400;
        if (unit.startsWith("w") || unit.startsWith("s")) return 7 * 86_400L;
        if (unit.startsWith("mo")) return 30 * 86_400L;
        return 365 * 86_400L; // years, ans, années
    }

    /**
     * From texts like "1.2M views", "297K views", "1,234 views" or "1,2 M de vues".
     */
    private static long parseViews(Video video) {
        for (String text : getInfoParts(video)) {
            String lower = normalize(text);
            if (!lower.contains("view") && !lower.contains("vue")) {
                continue;
            }
            Matcher matcher = NUMBER_PATTERN.matcher(lower);
            if (!matcher.find()) {
                return lower.contains("no view") || lower.contains("aucune vue") ? 0 : UNKNOWN;
            }
            String number = matcher.group().trim();
            String rest = lower.substring(matcher.end()).trim();
            String suffix = rest.split("[^a-z]", 2)[0];
            long multiplier = suffixMultiplier(suffix);
            try {
                if (multiplier > 1) {
                    return (long) (Double.parseDouble(number.replace(" ", "").replace(',', '.')) * multiplier);
                }
                return Long.parseLong(number.replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                return UNKNOWN;
            }
        }

        return UNKNOWN;
    }

    private static long suffixMultiplier(String suffix) {
        switch (suffix) {
            case "k":
                return 1_000;
            case "m":
                return 1_000_000;
            case "b":
            case "bn":
            case "md":
            case "mrd":
                return 1_000_000_000;
        }
        return 1;
    }

    private static List<String> getInfoParts(Video video) {
        List<String> parts = new ArrayList<>();
        CharSequence info = video.getSecondTitle();
        if (info != null) {
            for (String part : info.toString().split(Pattern.quote(Video.TERTIARY_TEXT_DELIM))) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static String normalize(String text) {
        return text.replace(' ', ' ').replace(' ', ' ').toLowerCase(Locale.ROOT);
    }
}
