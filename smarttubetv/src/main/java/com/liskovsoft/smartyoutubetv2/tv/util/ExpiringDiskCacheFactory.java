package com.liskovsoft.smartyoutubetv2.tv.util;

import android.content.Context;

import androidx.annotation.Nullable;

import com.bumptech.glide.disklrucache.DiskLruCache;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskLruCacheWrapper;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Glide disk cache in the app's cache dir, limited by size and by age: entries older than maxAgeMs are dropped
 * when the cache is opened and when they are read, so stale thumbnails are downloaded again.
 */
public class ExpiringDiskCacheFactory implements DiskCache.Factory {
    private static final String TAG = ExpiringDiskCacheFactory.class.getSimpleName();
    // Must match DiskLruCacheWrapper, otherwise DiskLruCache wipes the cache on open
    private static final int APP_VERSION = 1;
    private static final int VALUE_COUNT = 1;
    private static final String ENTRY_FILE_SUFFIX = ".0";
    private final Context mContext;
    private final long mMaxSize;
    private final long mMaxAgeMs;

    public ExpiringDiskCacheFactory(Context context, long maxSize, long maxAgeMs) {
        mContext = context.getApplicationContext();
        mMaxSize = maxSize;
        mMaxAgeMs = maxAgeMs;
    }

    @Nullable
    @Override
    public DiskCache build() {
        // Called lazily by Glide on its disk cache thread
        File cacheDir = new File(mContext.getCacheDir(), DiskCache.Factory.DEFAULT_DISK_CACHE_DIR);

        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            return null;
        }

        removeExpired(cacheDir);

        return new ExpiringDiskCache(DiskLruCacheWrapper.create(cacheDir, mMaxSize), mMaxAgeMs);
    }

    private void removeExpired(File cacheDir) {
        File[] files = cacheDir.listFiles();

        if (files == null) {
            return;
        }

        long expiredBefore = System.currentTimeMillis() - mMaxAgeMs;
        List<String> expiredKeys = new ArrayList<>();

        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(ENTRY_FILE_SUFFIX) && file.lastModified() < expiredBefore) {
                expiredKeys.add(name.substring(0, name.length() - ENTRY_FILE_SUFFIX.length()));
            }
        }

        if (expiredKeys.isEmpty()) {
            return;
        }

        // Remove through DiskLruCache so that its journal and size stay correct
        DiskLruCache cache = null;
        try {
            cache = DiskLruCache.open(cacheDir, APP_VERSION, VALUE_COUNT, mMaxSize);
            for (String key : expiredKeys) {
                cache.remove(key);
            }
        } catch (IOException e) {
            Log.e(TAG, "Can't remove expired images: %s", e.getMessage());
        } finally {
            if (cache != null) {
                try {
                    cache.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private static class ExpiringDiskCache implements DiskCache {
        private final DiskCache mDelegate;
        private final long mMaxAgeMs;

        ExpiringDiskCache(DiskCache delegate, long maxAgeMs) {
            mDelegate = delegate;
            mMaxAgeMs = maxAgeMs;
        }

        @Nullable
        @Override
        public File get(Key key) {
            File file = mDelegate.get(key);

            // Also covers files removed by the system when it trims app caches
            if (file != null && (!file.exists() || System.currentTimeMillis() - file.lastModified() > mMaxAgeMs)) {
                mDelegate.delete(key);
                return null;
            }

            return file;
        }

        @Override
        public void put(Key key, Writer writer) {
            mDelegate.put(key, writer);
        }

        @Override
        public void delete(Key key) {
            mDelegate.delete(key);
        }

        @Override
        public void clear() {
            mDelegate.clear();
        }
    }
}
