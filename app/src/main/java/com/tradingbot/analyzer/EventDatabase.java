package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class EventDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "trading_events.db";
    private static final int DATABASE_VERSION = 3; // Incrémenté pour nouvelles colonnes
    
    private static final String TABLE_EVENTS = "events";
    
    // Colonnes
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
    private static final String COLUMN_CONFIDENCE = "confidence"; // NOUVEAU
    private static final String COLUMN_SOURCE_TYPE = "source_type"; // NOUVEAU (notification, calendar, rss)

    public EventDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_EVENTS + " (" +
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
        
        db.execSQL(createTable);
        
        // Index pour optimisation
        db.execSQL("CREATE INDEX idx_event_id ON " + TABLE_EVENTS + 
            "(" + COLUMN_EVENT_ID + ")");
        db.execSQL("CREATE INDEX idx_timestamp ON " + TABLE_EVENTS + 
            "(" + COLUMN_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_impact ON " + TABLE_EVENTS + 
            "(" + COLUMN_IMPACT + ")");
        db.execSQL("CREATE INDEX idx_processed ON " + TABLE_EVENTS + 
            "(" + COLUMN_PROCESSED + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Migration v1 → v2
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + 
                " ADD COLUMN " + COLUMN_ANALYSIS + " TEXT");
        }
        
        if (oldVersion < 3) {
            // Migration v2 → v3
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + 
                " ADD COLUMN " + COLUMN_CONFIDENCE + " INTEGER DEFAULT 50");
            db.execSQL("ALTER TABLE " + TABLE_EVENTS + 
                " ADD COLUMN " + COLUMN_SOURCE_TYPE + " TEXT DEFAULT 'notification'");
        }
    }

    // Sauvegarder un événement
    public boolean saveEvent(
        String eventId,
        String packageName,
        String appName,
        String eventType,
        String title,
        String content,
        String assets,
        String impact
    ) {
        return saveEvent(eventId, packageName, appName, eventType, title, 
            content, assets, impact, 50, "notification");
    }
    
    // NOUVEAU: Sauvegarder avec confiance et type de source
    public boolean saveEvent(
        String eventId,
        String packageName,
        String appName,
        String eventType,
        String title,
        String content,
        String assets,
        String impact,
        int confidence,
        String sourceType
    ) {
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
            
            long result = db.insertWithOnConflict(
                TABLE_EVENTS,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            );
            
            return result != -1;
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur save: " + e.getMessage());
            }
            return false;
        }
    }

    // NOUVEAU: Vérifier si événement existe déjà (par event_id)
    public boolean eventExists(String eventId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.query(
                TABLE_EVENTS,
                new String[]{COLUMN_ID},
                COLUMN_EVENT_ID + " = ?",
                new String[]{eventId},
                null, null, null
            );
            
            return cursor.getCount() > 0;
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur exists: " + e.getMessage());
            }
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // Récupérer événements non traités
    public List<StoredEvent> getUnprocessedEvents() {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.query(
                TABLE_EVENTS,
                null,
                COLUMN_PROCESSED + " = 0",
                null,
                null, null,
                COLUMN_TIMESTAMP + " ASC"
            );
            
            while (cursor.moveToNext()) {
                events.add(cursorToEvent(cursor));
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur unprocessed: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return events;
    }

    // Récupérer événements dans fenêtre de temps
    public List<StoredEvent> getEventsInTimeWindow(long centerTime, long windowMs) {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            long startTime = centerTime - windowMs;
            long endTime = centerTime + windowMs;
            
            cursor = db.query(
                TABLE_EVENTS,
                null,
                COLUMN_TIMESTAMP + " BETWEEN ? AND ?",
                new String[]{String.valueOf(startTime), String.valueOf(endTime)},
                null, null,
                COLUMN_TIMESTAMP + " DESC"
            );
            
            while (cursor.moveToNext()) {
                events.add(cursorToEvent(cursor));
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur timeWindow: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return events;
    }

    // NOUVEAU: Récupérer événements par actif
    public List<StoredEvent> getEventsByAsset(String asset, long sinceTimestamp) {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.query(
                TABLE_EVENTS,
                null,
                COLUMN_ASSETS + " LIKE ? AND " + COLUMN_TIMESTAMP + " >= ?",
                new String[]{"%" + asset + "%", String.valueOf(sinceTimestamp)},
                null, null,
                COLUMN_TIMESTAMP + " DESC"
            );
            
            while (cursor.moveToNext()) {
                events.add(cursorToEvent(cursor));
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur getByAsset: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return events;
    }

    // NOUVEAU: Récupérer statistiques par actif
    public int getEventCountByAsset(String asset, long sinceTimestamp) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.query(
                TABLE_EVENTS,
                new String[]{"COUNT(*) as count"},
                COLUMN_ASSETS + " LIKE ? AND " + COLUMN_TIMESTAMP + " >= ?",
                new String[]{"%" + asset + "%", String.valueOf(sinceTimestamp)},
                null, null, null
            );
            
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur countByAsset: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return 0;
    }

    // NOUVEAU: Récupérer statistiques par source
    public int getEventCountBySource(String sourceType, long sinceTimestamp) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.query(
                TABLE_EVENTS,
                new String[]{"COUNT(*) as count"},
                COLUMN_SOURCE_TYPE + " = ? AND " + COLUMN_TIMESTAMP + " >= ?",
                new String[]{sourceType, String.valueOf(sinceTimestamp)},
                null, null, null
            );
            
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur countBySource: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return 0;
    }

    // NOUVEAU: Récupérer événements haute confiance
    public List<StoredEvent> getHighConfidenceEvents(int minConfidence, long sinceTimestamp) {
        List<StoredEvent> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.query(
                TABLE_EVENTS,
                null,
                COLUMN_CONFIDENCE + " >= ? AND " + COLUMN_TIMESTAMP + " >= ?",
                new String[]{String.valueOf(minConfidence), String.valueOf(sinceTimestamp)},
                null, null,
                COLUMN_TIMESTAMP + " DESC"
            );
            
            while (cursor.moveToNext()) {
                events.add(cursorToEvent(cursor));
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur highConfidence: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return events;
    }

    // Marquer événement comme traité
    public void markProcessed(int id, String analysis) {
        SQLiteDatabase db = getWritableDatabase();
        
        try {
            ContentValues values = new ContentValues();
            values.put(COLUMN_PROCESSED, 1);
            values.put(COLUMN_ANALYSIS, analysis);
            
            db.update(
                TABLE_EVENTS,
                values,
                COLUMN_ID + " = ?",
                new String[]{String.valueOf(id)}
            );
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur markProcessed: " + e.getMessage());
            }
        }
    }

    // NOUVEAU: Mettre à jour confiance
    public void updateConfidence(String eventId, int confidence) {
        SQLiteDatabase db = getWritableDatabase();
        
        try {
            ContentValues values = new ContentValues();
            values.put(COLUMN_CONFIDENCE, confidence);
            
            db.update(
                TABLE_EVENTS,
                values,
                COLUMN_EVENT_ID + " = ?",
                new String[]{eventId}
            );
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur updateConfidence: " + e.getMessage());
            }
        }
    }

    // Nettoyer anciens événements (> 30 jours)
    public void cleanOldEvents() {
        SQLiteDatabase db = getWritableDatabase();
        
        try {
            long cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
            
            int deleted = db.delete(
                TABLE_EVENTS,
                COLUMN_TIMESTAMP + " < ?",
                new String[]{String.valueOf(cutoffTime)}
            );
            
            if (MainActivity.instance != null && deleted > 0) {
                MainActivity.instance.addLog("[DB] Nettoyage: " + deleted + 
                    " événements supprimés");
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur clean: " + e.getMessage());
            }
        }
    }

    // NOUVEAU: Obtenir statistiques globales
    public DatabaseStats getStats() {
        DatabaseStats stats = new DatabaseStats();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            // Total événements
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_EVENTS, null);
            if (cursor.moveToFirst()) {
                stats.totalEvents = cursor.getInt(0);
            }
            cursor.close();
            
            // Événements traités
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_EVENTS + 
                " WHERE " + COLUMN_PROCESSED + " = 1", null);
            if (cursor.moveToFirst()) {
                stats.processedEvents = cursor.getInt(0);
            }
            cursor.close();
            
            // Événements aujourd'hui
            long todayStart = getTodayStartTimestamp();
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_EVENTS + 
                " WHERE " + COLUMN_TIMESTAMP + " >= ?", 
                new String[]{String.valueOf(todayStart)});
            if (cursor.moveToFirst()) {
                stats.eventsToday = cursor.getInt(0);
            }
            cursor.close();
            
            // Confiance moyenne
            cursor = db.rawQuery("SELECT AVG(" + COLUMN_CONFIDENCE + ") FROM " + 
                TABLE_EVENTS + " WHERE " + COLUMN_TIMESTAMP + " >= ?", 
                new String[]{String.valueOf(todayStart)});
            if (cursor.moveToFirst()) {
                stats.avgConfidence = cursor.getInt(0);
            }
            cursor.close();
            
            // Par source
            cursor = db.rawQuery(
                "SELECT " + COLUMN_SOURCE_TYPE + ", COUNT(*) FROM " + TABLE_EVENTS +
                " WHERE " + COLUMN_TIMESTAMP + " >= ? GROUP BY " + COLUMN_SOURCE_TYPE,
                new String[]{String.valueOf(todayStart)}
            );
            while (cursor.moveToNext()) {
                String source = cursor.getString(0);
                int count = cursor.getInt(1);
                stats.bySource.put(source, count);
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur stats: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return stats;
    }

    // NOUVEAU: Récupérer dernier événement par actif
    public StoredEvent getLastEventByAsset(String asset) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.query(
                TABLE_EVENTS,
                null,
                COLUMN_ASSETS + " LIKE ?",
                new String[]{"%" + asset + "%"},
                null, null,
                COLUMN_TIMESTAMP + " DESC",
                "1"
            );
            
            if (cursor.moveToFirst()) {
                return cursorToEvent(cursor);
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DB] Erreur lastByAsset: " + e.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        
        return null;
    }

    // Convertir Cursor → StoredEvent
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
        event.processed = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PROCESSED)) == 1;
        event.analysis = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ANALYSIS));
        
        // Nouvelles colonnes (avec fallback)
        int confIndex = cursor.getColumnIndex(COLUMN_CONFIDENCE);
        event.confidence = confIndex >= 0 ? cursor.getInt(confIndex) : 50;
        
        int sourceIndex = cursor.getColumnIndex(COLUMN_SOURCE_TYPE);
        event.sourceType = sourceIndex >= 0 ? 
            cursor.getString(sourceIndex) : "notification";
        
        return event;
    }

    private long getTodayStartTimestamp() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // Classe pour événement stocké
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
        public boolean processed;
        public String analysis;
        public int confidence;
        public String sourceType;
    }

    // NOUVEAU: Classe pour statistiques
    public static class DatabaseStats {
        public int totalEvents = 0;
        public int processedEvents = 0;
        public int eventsToday = 0;
        public int avgConfidence = 0;
        public java.util.Map<String, Integer> bySource = new java.util.HashMap<>();
        
        @Override
        public String toString() {
            return "Total: " + totalEvents + 
                   " | Traités: " + processedEvents + 
                   " | Aujourd'hui: " + eventsToday + 
                   " | Confiance moy: " + avgConfidence + "%";
        }
    }
}
