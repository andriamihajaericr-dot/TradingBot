package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.util.*;

public class EventDatabase extends SQLiteOpenHelper {

    private static final String TAG = "EventDatabase";
    private static final String DATABASE_NAME = "trading_events_pro.db";
    private static final int DATABASE_VERSION = 4;
    private static final String TABLE_EVENTS = "events";
    private static final String TABLE_CONTEXT = "market_context";

    // Colonnes Table Événements
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_EVENT_ID = "event_id";
    private static final String COLUMN_PACKAGE_NAME = "package_name";
    private static final String COLUMN_APP_NAME = "app_name";
    private static final String COLUMN_EVENT_TYPE = "event_type";
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_CONTENT = "content";
    private static final String COLUMN_ASSETS = "assets";
    private static final String COLUMN_IMPACT = "impact";
    private static final String COLUMN_TIMESTAMP = "timestamp";
    private static final String COLUMN_PROCESSED = "processed";
    private static final String COLUMN_ANALYSIS = "analysis";
    private static final String COLUMN_CONFIDENCE = "confidence";
    private static final String COLUMN_SOURCE_TYPE = "source_type";

    // Colonnes Table Contexte
    private static final String COLUMN_REGIME_KEY = "regime_key";
    private static final String COLUMN_REGIME_VALUE = "regime_value";

    public EventDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTableEvents = "CREATE TABLE " + TABLE_EVENTS + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_EVENT_ID + " TEXT UNIQUE NOT NULL, " +
                COLUMN_PACKAGE_NAME + " TEXT, " +
                COLUMN_APP_NAME + " TEXT NOT NULL, " +
                COLUMN_EVENT_TYPE + " TEXT NOT NULL, " +
                COLUMN_TITLE + " TEXT, " +
                COLUMN_CONTENT + " TEXT NOT NULL, " +
                COLUMN_ASSETS + " TEXT NOT NULL, " +
                COLUMN_IMPACT + " TEXT NOT NULL, " +
                COLUMN_TIMESTAMP + " INTEGER NOT NULL, " +
                COLUMN_PROCESSED + " INTEGER DEFAULT 0, " +
                COLUMN_ANALYSIS + " TEXT, " +
                COLUMN_CONFIDENCE + " INTEGER DEFAULT 50, " +
                COLUMN_SOURCE_TYPE + " TEXT DEFAULT 'notification'" +
                ")";
        db.execSQL(createTableEvents);

        String createTableContext = "CREATE TABLE " + TABLE_CONTEXT + " (" +
                COLUMN_REGIME_KEY + " TEXT PRIMARY KEY, " +
                COLUMN_REGIME_VALUE + " TEXT NOT NULL)";
        db.execSQL(createTableContext);

        db.execSQL("CREATE INDEX idx_timestamp ON " + TABLE_EVENTS + "(" + COLUMN_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_event_id ON " + TABLE_EVENTS + "(" + COLUMN_EVENT_ID + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_EVENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONTEXT);
        onCreate(db);
    }

    public void updateMarketRegime(String regime) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_REGIME_KEY, "CURRENT_REGIME");
        values.put(COLUMN_REGIME_VALUE, regime);
        db.insertWithOnConflict(TABLE_CONTEXT, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getCurrentMarketRegime() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_CONTEXT, new String[]{COLUMN_REGIME_VALUE},
                COLUMN_REGIME_KEY + " = ?", new String[]{"CURRENT_REGIME"}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            String regime = cursor.getString(0);
            cursor.close();
            return regime;
        }
        if (cursor != null) cursor.close();
        return "RISK_ON_STANDARD"; // Fallback neutre par défaut
    }

    public boolean saveEvent(String eventId, String packageName, String appName,
                             String eventType, String title, String content,
                             String assets, String impact, int confidence, String sourceType) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues values = new ContentValues();
            values.put(COLUMN_EVENT_ID, eventId);
            values.put(COLUMN_PACKAGE_NAME, packageName);
            values.put(COLUMN_APP_NAME, appName);
            values.put(COLUMN_EVENT_TYPE, eventType);
            values.put(COLUMN_TITLE, title);
            values.put(COLUMN_CONTENT, content);
            values.put(COLUMN_ASSETS, assets);
            values.put(COLUMN_IMPACT, impact);
            values.put(COLUMN_TIMESTAMP, System.currentTimeMillis());
            values.put(COLUMN_PROCESSED, 0);
            values.put(COLUMN_CONFIDENCE, confidence);
            values.put(COLUMN_SOURCE_TYPE, sourceType);

            long result = db.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            return result != -1;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean eventExists(String eventId) {
        if (eventId == null) return false;
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, new String[]{COLUMN_ID}, COLUMN_EVENT_ID + " = ?", new String[]{eventId}, null, null, null);
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public List<StoredEvent> getEventsInTimeWindow(long centerTime, long windowMs) {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            long start = centerTime - windowMs;
            long end = centerTime + windowMs;
            cursor = db.query(TABLE_EVENTS, null, COLUMN_TIMESTAMP + " BETWEEN ? AND ?", new String[]{String.valueOf(start), String.valueOf(end)}, null, null, COLUMN_TIMESTAMP + " DESC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    events.add(cursorToEvent(cursor));
                }
            }
        } catch (Exception e) {} finally {
            if (cursor != null) cursor.close();
        }
        return events;
    }

    public List<StoredEvent> getEventsByAsset(String asset, long sinceTimestamp) {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, null, COLUMN_ASSETS + " LIKE ? AND " + COLUMN_TIMESTAMP + " >= ?", new String[]{"%" + asset + "%", String.valueOf(sinceTimestamp)}, null, null, COLUMN_TIMESTAMP + " DESC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    events.add(cursorToEvent(cursor));
                }
            }
        } catch (Exception e) {} finally {
            if (cursor != null) cursor.close();
        }
        return events;
    }

    public void cleanOldEvents() {
        SQLiteDatabase db = getWritableDatabase();
        long cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        db.delete(TABLE_EVENTS, COLUMN_TIMESTAMP + " < ?", new String[]{String.valueOf(cutoff)});
    }

    private StoredEvent cursorToEvent(Cursor cursor) {
        StoredEvent event = new StoredEvent();
        event.id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID));
        event.eventId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EVENT_ID));
        event.packageName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME));
        event.appName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_APP_NAME));
        event.eventType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EVENT_TYPE));
        event.title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE));
        event.content = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT));
        event.assets = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ASSETS));
        event.impact = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMPACT));
        event.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP));
        event.analysis = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ANALYSIS));
        event.confidence = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CONFIDENCE));
        event.sourceType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOURCE_TYPE));
        return event;
    }

    public static class StoredEvent {
        public int id;
        public String eventId;
        public String packageName;
        public String appName;
        public String eventType;
        public String title;
        public String content;
        public String assets;
        public String impact;
        public long timestamp;
        public String analysis;
        public int confidence;
        public String sourceType;
    }
}
