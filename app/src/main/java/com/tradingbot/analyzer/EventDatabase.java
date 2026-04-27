// EventDatabase.java
package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class EventDatabase extends SQLiteOpenHelper {
    
    private static final String DB_NAME = "trading_events.db";
    private static final int DB_VERSION = 1;
    
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
            "event_type TEXT," + // 'news' ou 'economic'
            "title TEXT," +
            "content TEXT," +
            "assets TEXT," +
            "processed INTEGER DEFAULT 0," +
            "analysis TEXT," +
            "created_at INTEGER)");
        
        db.execSQL("CREATE INDEX idx_timestamp ON events(timestamp, processed)");
        db.execSQL("CREATE INDEX idx_event_id ON events(event_id)");
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS events");
        onCreate(db);
    }
    
    // Sauvegarder un événement
    public boolean saveEvent(String eventId, String source, String appName, 
                            String eventType, String title, String content, String assets) {
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
        cv.put("created_at", System.currentTimeMillis());
        
        long result = db.insertWithOnConflict("events", null, cv, 
            SQLiteDatabase.CONFLICT_IGNORE);
        db.close();
        return result != -1;
    }
    
    // Récupérer les événements non traités
    public List<StoredEvent> getUnprocessedEvents() {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        Cursor cursor = db.rawQuery(
            "SELECT * FROM events WHERE processed = 0 ORDER BY timestamp ASC", null);
        
        while (cursor.moveToNext()) {
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
            events.add(event);
        }
        
        cursor.close();
        db.close();
        return events;
    }
    
    // Marquer comme traité
    public void markProcessed(int id, String analysis) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("processed", 1);
        cv.put("analysis", analysis);
        db.update("events", cv, "id = ?", new String[]{String.valueOf(id)});
        db.close();
    }
    
    // Événements dans une fenêtre de temps (pour combinaison)
    public List<StoredEvent> getEventsInTimeWindow(long timestamp, long windowMs) {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        long start = timestamp - windowMs;
        long end = timestamp + windowMs;
        
        Cursor cursor = db.rawQuery(
            "SELECT * FROM events WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC",
            new String[]{String.valueOf(start), String.valueOf(end)});
        
        while (cursor.moveToNext()) {
            StoredEvent event = new StoredEvent();
            event.id = cursor.getInt(cursor.getColumnIndex("id"));
            event.eventId = cursor.getString(cursor.getColumnIndex("event_id"));
            event.eventType = cursor.getString(cursor.getColumnIndex("event_type"));
            event.title = cursor.getString(cursor.getColumnIndex("title"));
            event.content = cursor.getString(cursor.getColumnIndex("content"));
            event.assets = cursor.getString(cursor.getColumnIndex("assets"));
            events.add(event);
        }
        
        cursor.close();
        db.close();
        return events;
    }
    
    // Nettoyer les vieux événements (> 30 jours)
    public void cleanOldEvents() {
        SQLiteDatabase db = getWritableDatabase();
        long cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        db.delete("events", "timestamp < ? AND processed = 1", 
            new String[]{String.valueOf(cutoff)});
        db.close();
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
    }
}
