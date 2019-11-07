package com.github.emilbayes.downloadmanager;

import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DownloadManagerPlugin extends CordovaPlugin {
    DownloadManager downloadManager;
    private final Handler handler = new Handler();
    private Map<Integer, Integer> indexMap = new HashMap<>();
    private long mLastRxBytes = 0;
    private long mLastTxBytes = 0;
    private long mLastTime = 0;
    private long mTotalBytesDownloaded = 0;
    private Map<Integer, Integer> rangeMap = new HashMap();

    @Override
    public void initialize(final CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);

        downloadManager = (DownloadManager) cordova.getActivity().getApplication().getApplicationContext()
                .getSystemService(Context.DOWNLOAD_SERVICE);
        mLastRxBytes = TrafficStats.getTotalRxBytes();
        mLastTxBytes = TrafficStats.getTotalTxBytes();
        mLastTime = System.currentTimeMillis();
        this.initSpeedLogger();
        startFetchingDownloadSpeed();
    }

    private void startFetchingDownloadSpeed() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                double speed = getNetworkSpeed();
                if (speed > 0) {
                    int key = speed < 1024 ? getFirstBucketKey(speed) : getSecondBucketKey(speed);
                    int range = indexMap.get(key);
                    if (rangeMap.containsKey(range)) {
                        Integer rangeKey = rangeMap.get(range);
                        rangeMap.put(range, rangeKey + 1);
                    } else {
                        rangeMap.put(range, 1);
                    }
                }

                handler.postDelayed(this, 1000);
            }
        }, 2000);
    }

    public int getFirstBucketKey(double speed) {

        int result = (int) (Math.log(speed) / Math.log(2) - 3);
        return result < 1 ? 1 : result;
    }

    public int getSecondBucketKey(double speed) {
        int result = (int) (speed / 512) + 4;
        return result >= 16 ? -1 : result;
    }

    public void initSpeedLogger() {
        indexMap.put(1, 32);
        indexMap.put(2, 64);
        indexMap.put(3, 128);
        indexMap.put(4, 256);
        indexMap.put(5, 512);
        indexMap.put(6, 1024);
        indexMap.put(7, 1536);
        indexMap.put(8, 2048);
        indexMap.put(9, 2560);
        indexMap.put(10, 3072);
        indexMap.put(11, 3584);
        indexMap.put(-1, 4096);
    }

    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("enqueue"))
            return enqueue(args.getJSONObject(0), callbackContext);
        if (action.equals("query"))
            return query(args.getJSONObject(0), callbackContext);
        if (action.equals("remove"))
            return remove(args, callbackContext);
        if (action.equals("addCompletedDownload"))
            return addCompletedDownload(args.getJSONObject(0), callbackContext);
        if (action.equals("fetchSpeedLog"))
            return fetchSpeedLog(callbackContext);

        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION));
        return false;
    }

    protected boolean enqueue(JSONObject obj, CallbackContext callbackContext) throws JSONException {
        DownloadManager.Request req = deserialiseRequest(obj);

        long id = downloadManager.enqueue(req);

        callbackContext.success(Long.toString(id));

        return true;
    }

    protected boolean query(JSONObject obj, CallbackContext callbackContext) throws JSONException {
        DownloadManager.Query query = deserialiseQuery(obj);

        Cursor downloads = downloadManager.query(query);

        callbackContext.success(JSONFromCursor(downloads));

        downloads.close();

        return true;
    }

    protected boolean remove(JSONArray arr, CallbackContext callbackContext) throws JSONException {
        long[] ids = longsFromJSON(arr);

        int removed = downloadManager.remove(ids);
        callbackContext.success(removed);

        return true;
    }

    protected boolean addCompletedDownload(JSONObject obj, CallbackContext callbackContext) throws JSONException {

        long id = downloadManager.addCompletedDownload(obj.optString("title"), obj.optString("description"),
                obj.optBoolean("isMediaScannerScannable", false), obj.optString("mimeType"), obj.optString("path"),
                obj.optLong("length"), obj.optBoolean("showNotification", true));
        // NOTE: If showNotification is false, you need
        // <uses-permission android: name =
        // "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION" />

        callbackContext.success(Long.toString(id));

        return true;
    }

    protected DownloadManager.Request deserialiseRequest(JSONObject obj) throws JSONException {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(obj.getString("uri")));

        req.setTitle(obj.optString("title"));
        req.setDescription(obj.optString("description"));
        req.setMimeType(obj.optString("mimeType", null));

        if (obj.has("destinationInExternalFilesDir")) {
            Context context = cordova.getActivity().getApplication().getApplicationContext();

            JSONObject params = obj.getJSONObject("destinationInExternalFilesDir");

            req.setDestinationInExternalFilesDir(context, params.optString("dirType"), params.optString("subPath"));
        } else if (obj.has("destinationInExternalPublicDir")) {
            JSONObject params = obj.getJSONObject("destinationInExternalPublicDir");

            req.setDestinationInExternalPublicDir(params.optString("dirType"), params.optString("subPath"));
        } else if (obj.has("destinationUri"))
            req.setDestinationUri(Uri.parse(obj.getString("destinationUri")));

        req.setVisibleInDownloadsUi(obj.optBoolean("visibleInDownloadsUi", true));
        req.setNotificationVisibility(obj.optInt("notificationVisibility"));

        if (obj.has("headers")) {
            JSONArray arrHeaders = obj.optJSONArray("headers");
            for (int i = 0; i < arrHeaders.length(); i++) {
                JSONObject headerObj = arrHeaders.getJSONObject(i);
                req.addRequestHeader(headerObj.optString("header"), headerObj.optString("value"));
            }
        }

        return req;
    }

    protected DownloadManager.Query deserialiseQuery(JSONObject obj) throws JSONException {
        DownloadManager.Query query = new DownloadManager.Query();

        long[] ids = longsFromJSON(obj.optJSONArray("ids"));
        query.setFilterById(ids);

        if (obj.has("status")) {
            query.setFilterByStatus(obj.getInt("status"));
        }

        return query;
    }

    private static PluginResult OK(Map obj) throws JSONException {
        return createPluginResult(obj, PluginResult.Status.OK);
    }

    private static PluginResult ERROR(Map obj) throws JSONException {
        return createPluginResult(obj, PluginResult.Status.ERROR);
    }

    private static PluginResult createPluginResult(Map map, PluginResult.Status status) throws JSONException {
        JSONObject json = new JSONObject(map);
        PluginResult result = new PluginResult(status, json);
        return result;
    }

    private static JSONArray JSONFromCursor(Cursor cursor) throws JSONException {
        JSONArray result = new JSONArray();

        cursor.moveToFirst();
        do {
            JSONObject rowObject = new JSONObject();
            rowObject.put("id", cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_ID)));
            rowObject.put("title", cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)));
            rowObject.put("description", cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION)));
            rowObject.put("mediaType", cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)));
            // rowObject.put("localFilename",
            // cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)));
            rowObject.put("localUri", cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)));
            rowObject.put("mediaproviderUri",
                    cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_MEDIAPROVIDER_URI)));
            rowObject.put("uri", cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI)));
            rowObject.put("lastModifiedTimestamp",
                    cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)));
            rowObject.put("status", cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)));
            rowObject.put("reason", cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON)));
            rowObject.put("bytesDownloadedSoFar",
                    cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
            rowObject.put("totalSizeBytes",
                    cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
            result.put(rowObject);
        } while (cursor.moveToNext());

        return result;
    }

    private static long[] longsFromJSON(JSONArray arr) throws JSONException {
        if (arr == null)
            return null;

        long[] longs = new long[arr.length()];

        for (int i = 0; i < arr.length(); i++) {
            String str = arr.getString(i);
            longs[i] = Long.valueOf(str);
        }

        return longs;
    }

    protected boolean fetchSpeedLog(CallbackContext callbackContext) {
        try {
            JSONObject speedLog = new JSONObject();

            long totalKBdownloaded = mTotalBytesDownloaded / 1024;

            JSONObject distribution = new JSONObject();

            for (Map.Entry<Integer, Integer> entry : rangeMap.entrySet()) {
                distribution.put(entry.getKey().toString(), entry.getValue());
            }

            speedLog.put("totalKBdownloaded", totalKBdownloaded);
            speedLog.put("distributionInKBPS", distribution);

            callbackContext.success(speedLog);
            mTotalBytesDownloaded = 0;
            rangeMap.clear();
            return true;
        } catch (Exception e) {
            callbackContext.error(e.toString());
            mTotalBytesDownloaded = 0;
            rangeMap.clear();
            return false;
        }
    }

    private double getNetworkSpeed() {
        try {
            long currentRxBytes = TrafficStats.getTotalRxBytes();
            long currentTxBytes = TrafficStats.getTotalTxBytes();
            long usedRxBytes = currentRxBytes - mLastRxBytes;
            long usedTxBytes = currentTxBytes - mLastTxBytes;
            long currentTime = System.currentTimeMillis();
            long usedTime = currentTime - mLastTime;

            mLastRxBytes = currentRxBytes;
            mLastTxBytes = currentTxBytes;
            mLastTime = currentTime;

            long totalBytes = usedRxBytes + usedTxBytes;
            Log.e("Total Bytes::", totalBytes + "");
            double totalSpeed = 0;
            if (usedTime > 0) {
                totalSpeed = (double) totalBytes / usedTime;
            }
            mTotalBytesDownloaded += totalBytes;
            return totalSpeed;

        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

}
