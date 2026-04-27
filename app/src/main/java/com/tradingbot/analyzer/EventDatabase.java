// EventDatabase.java
package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventDatabase extends SQLiteOpenHelper {
    
    private static final String DB_NAME = "trading_events.db";
    private static final int DB_VERSION = 1;
    
    // Cache pour détecter les doublons rapides
    private static final Map<String, Long> recentFingerprints = new HashMap<>();
    private static final long DUPLICATE_WINDOW_MS = 5 * 60 * 1000; // 5 minutes
    
    public EventDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "event_id TEXT UNIQUE," +
            "source TEXT," +
            "app_name TEXT," +
            "timestamp INTEGER," +
            "event_type TEXT," +
            "title TEXT," +
            "content TEXT," +
            "assets TEXT," +
            "impact TEXT," +
            "processed INTEGER DEFAULT 0," +
            "analysis TEXT," +
            "created_at INTEGER)");
        
        db.execSQL("CREATE INDEX idx_timestamp ON events(timestamp, processed)");
        db.execSQL("CREATE INDEX idx_event_id ON events(event_id)");
        db.execSQL("CREATE INDEX idx_impact ON events(impact)");
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS events");
        onCreate(db);
    }
    
    public boolean saveEvent(String eventId, String source, String appName, 
                            String eventType, String title, String content, 
                            String assets, String impact) {
        // Vérifier doublon rapide en mémoire
        String fingerprint = generateFingerprint(title, content);
        Long lastSeen = recentFingerprints.get(fingerprint);
        
        if (lastSeen != null && 
            System.currentTimeMillis() - lastSeen < DUPLICATE_WINDOW_MS) {
            return false; // Doublon récent
        }
        
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("event_id", eventId);
        cv.put("source", source);
        cv.put("app_name", appName);
        cv.put("timestamp", System.currentTimeMillis());
        cv.put("event_type", eventType);
        cv.put("title", title);
        cv.put("content", content);
        cv.put("assets", assets);
        cv.put("impact", impact);
        cv.put("created_at", System.currentTimeMillis());
        
        long result = db.insertWithOnConflict("events", null, cv, 
            SQLiteDatabase.CONFLICT_IGNORE);
        db.close();
        
        if (result != -1) {
            recentFingerprints.put(fingerprint, System.currentTimeMillis());
            cleanupOldFingerprints();
            return true;
        }
        return false;
    }
    
    public List<StoredEvent> getUnprocessedEvents() {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        Cursor cursor = db.rawQuery(
            "SELECT * FROM events WHERE processed = 0 ORDER BY timestamp ASC", null);
        
        while (cursor.moveToNext()) {
            events.add(cursorToEvent(cursor));
        }
        
        cursor.close();
        db.close();
        return events;
    }
    
    public List<StoredEvent> getEventsInTimeWindow(long timestamp, long windowMs) {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        long start = timestamp - windowMs;
        long end = timestamp + windowMs;
        
        Cursor cursor = db.rawQuery(
            "SELECT * FROM events WHERE timestamp BETWEEN ? AND ? " +
            "AND (impact = 'Haussier' OR impact = 'Baissier') " +
            "ORDER BY timestamp ASC",
            new String[]{String.valueOf(start), String.valueOf(end)});
        
        while (cursor.moveToNext()) {
            events.add(cursorToEvent(cursor));
        }
        
        cursor.close();
        db.close();
        return events;
    }
    
    public void markProcessed(int id, String analysis) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("processed", 1);
        cv.put("analysis", analysis);
        db.update("events", cv, "id = ?", new String[]{String.valueOf(id)});
        db.close();
    }
    
    public void cleanOldEvents() {
        SQLiteDatabase db = getWritableDatabase();
        long cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        db.delete("events", "timestamp < ? AND processed = 1", 
            new String[]{String.valueOf(cutoff)});
        db.close();
    }
    
    private StoredEvent cursorToEvent(Cursor cursor) {
        StoredEvent event = new StoredEvent();
        event.id = cursor.getInt(cursor.getColumnIndex("id"));
        event.eventId = cursor.getString(cursor.getColumnIndex("event_id"));
        event.source = cursor.getString(cursor.getColumnIndex("source"));
        event.appName = cursor.getString(cursor.getColumnIndex("app_name"));
        event.timestamp = cursor.getLong(cursor.getColumnIndex("timestamp"));
        event.eventType = cursor.getString(cursor.getColumnIndex("event_type"));
        event.title = cursor.getString(cursor.getColumnIndex("title"));
        event.content = cursor.getString(cursor.getColumnIndex("content"));
        event.assets = cursor.getString(cursor.getColumnIndex("assets"));
        event.impact = cursor.getString(cursor.getColumnIndex("impact"));
        return event;
    }
    
    private String generateFingerprint(String title, String content) {
        String key = title + content.substring(0, Math.min(100, content.length()));
        return String.valueOf(key.hashCode());
    }
    
    private void cleanupOldFingerprints() {
        long cutoff = System.currentTimeMillis() - DUPLICATE_WINDOW_MS;
        recentFingerprints.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
    
    public static class StoredEvent {
        public int id;
        public String eventId;
        public String source;
        public String appName;
        public long timestamp;
        public String eventType;
        public String title;
        public String content;
        public String assets;
        public String impact;
    }
}
