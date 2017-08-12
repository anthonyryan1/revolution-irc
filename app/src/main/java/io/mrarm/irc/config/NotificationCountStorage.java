package io.mrarm.irc.config;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NotificationCountStorage {

    private static NotificationCountStorage sInstance;

    private static final int FLUSH_DELAY = 10 * 1000;

    public static NotificationCountStorage getInstance(Context ctx) {
        if (sInstance == null)
            sInstance = new NotificationCountStorage(new File(ctx.getFilesDir(), "notification-count.db").getAbsolutePath());
        return sInstance;
    }

    private SQLiteDatabase mDatabase;
    private SQLiteStatement mGetNotificationCountStatement;
    private SQLiteStatement mIncrementNotificationCountStatement;
    private SQLiteStatement mCreateNotificationCountStatement;
    private Handler mHandler;
    private Map<UUID, Map<String, Integer>> mChangeQueue;

    public NotificationCountStorage(String path) {
        mDatabase = SQLiteDatabase.openOrCreateDatabase(path, null);
        mDatabase.execSQL("CREATE TABLE IF NOT EXISTS 'notification_count' (server TEXT, channel TEXT, count INTEGER)");
        mGetNotificationCountStatement = mDatabase.compileStatement("SELECT count FROM 'notification_count' WHERE server=?1 AND channel=?2");
        mIncrementNotificationCountStatement = mDatabase.compileStatement("UPDATE 'notification_count' SET count=count+?3 WHERE server=?1 AND channel=?2");
        mCreateNotificationCountStatement = mDatabase.compileStatement("INSERT INTO 'notification_count' (server, channel, count) VALUES (?1, ?2, ?3)");

        HandlerThread thread = new HandlerThread("NotificationCountStorage Thread");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    @Override
    protected void finalize() throws Throwable {
        mDatabase.close();
        super.finalize();
    }

    private int getChannelCounter(UUID server, String channel) {
        mGetNotificationCountStatement.bindString(1, server.toString());
        mGetNotificationCountStatement.bindString(2, channel);
        long ret;
        try {
            ret = mGetNotificationCountStatement.simpleQueryForLong();
        } catch (SQLiteDoneException e) {
            ret = 0;
        }
        mGetNotificationCountStatement.clearBindings();
        return (int) ret;
    }

    private void incrementChannelCounter(UUID server, String channel, int i) {
        mIncrementNotificationCountStatement.bindString(1, server.toString());
        mIncrementNotificationCountStatement.bindString(2, channel);
        mIncrementNotificationCountStatement.bindLong(3, i);
        int ii = mIncrementNotificationCountStatement.executeUpdateDelete();
        mIncrementNotificationCountStatement.clearBindings();
        if (ii == 0) {
            mCreateNotificationCountStatement.bindString(1, server.toString());
            mCreateNotificationCountStatement.bindString(2, channel);
            mCreateNotificationCountStatement.bindLong(3, i);
            mCreateNotificationCountStatement.execute();
            mCreateNotificationCountStatement.clearBindings();
        }
    }

    private void resetChannelCounter(UUID server, String channel) {
        mDatabase.execSQL("DELETE FROM 'notification_count' WHERE server=?1 AND channel=?2",
                new Object[] { server.toString(), channel });
    }

    private void removeServerCounters(UUID server) {
        mDatabase.execSQL("DELETE FROM 'notification_count' WHERE server=?1",
                new Object[] { server.toString() });
    }

    private void flushQueuedChanges() {
        Map<UUID, Map<String, Integer>> map;
        synchronized (this) {
            map = mChangeQueue;
            mChangeQueue = null;
        }
        if (map == null)
            return;
        for (Map.Entry<UUID, Map<String, Integer>> vals : map.entrySet()) {
            for (Map.Entry<String, Integer> v : vals.getValue().entrySet()) {
                incrementChannelCounter(vals.getKey(), v.getKey(), v.getValue());
            }
        }
    }

    public void requestGetChannelCounter(UUID server, String channel, WeakReference<OnChannelCounterResult> result) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mFlushQueuedChangesRunnable);
            flushQueuedChanges();

            int res = getChannelCounter(server, channel);
            OnChannelCounterResult cb = result.get();
            if (cb != null)
                cb.onChannelCounterResult(server, channel, res);
        });
    }

    public void requestIncrementChannelCounter(UUID server, String channel) {
        synchronized (this) {
            boolean requestQueue = false;
            if (mChangeQueue == null) {
                mChangeQueue = new HashMap<>();
                requestQueue = true;
            }
            if (!mChangeQueue.containsKey(server))
                mChangeQueue.put(server, new HashMap<>());
            Map<String, Integer> m = mChangeQueue.get(server);
            Integer i = m.get(channel);
            m.put(channel, (i == null ? 0 : i) + 1);
            if (requestQueue)
                mHandler.postDelayed(mFlushQueuedChangesRunnable, FLUSH_DELAY);
        }
    }

    public void requestResetChannelCounter(UUID server, String channel) {
        synchronized (this) {
            if (mChangeQueue != null && mChangeQueue.containsKey(server))
                mChangeQueue.get(server).remove(channel);
        }
        mHandler.post(() -> {
            mHandler.removeCallbacks(mFlushQueuedChangesRunnable);
            flushQueuedChanges();

            resetChannelCounter(server, channel);
        });
    }

    public void requestRemoveServerCounters(UUID server) {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mFlushQueuedChangesRunnable);
            flushQueuedChanges();

            removeServerCounters(server);
        });
    }

    public interface OnChannelCounterResult {
        void onChannelCounterResult(UUID server, String channel, int result);
    }

    private final Runnable mFlushQueuedChangesRunnable = this::flushQueuedChanges;

}