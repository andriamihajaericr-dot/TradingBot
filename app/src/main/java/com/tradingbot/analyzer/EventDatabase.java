package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.util.List;

public class EventDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "trading_bot.db";
    private static final int DATABASE_VERSION = 2;
    public static final String TABLE_EVENTS = "events";

    public EventDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_EVENTS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "fingerprint TEXT UNIQUE, " +
                "package_name TEXT, " +
                "source TEXT, " +
                "event_type TEXT, " +
                "title TEXT, " +
                "feed_content TEXT, " +
                "target_assets TEXT, " +
                "impact TEXT, " +
                "unix_timestamp INTEGER, " +
                "sync_status TEXT DEFAULT 'synced')";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN sync_status TEXT DEFAULT 'synced'");
            } catch (Exception e) {
                Log.e("EventDatabase", "Erreur d'upgrade ou colonne existante.");
            }
        }
    }

    public boolean saveEvent(String fingerprint, String packageName, String source, String eventType, 
                             String title, String feedContent, String targetAssets, String impact, 
                             int unixTimestamp, String syncStatus) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("fingerprint", fingerprint);
        values.put("package_name", packageName);
        values.put("source", source);
        values.put("event_type", eventType);
        values.put("title", title);
        values.put("feed_content", feedContent);
        values.put("target_assets", targetAssets);
        values.put("impact", impact);
        values.put("unix_timestamp", unixTimestamp);
        values.put("sync_status", syncStatus);

        long result = db.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return result != -1;
    }

    public boolean eventExists(String fingerprint) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_EVENTS, new String[]{"id"}, "fingerprint = ?", 
                new String[]{fingerprint}, null, null, null);
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    public Cursor getUnsyncedEvents(long currentUnixTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        long thirtyDaysAgo = currentUnixTime - (30L * 24 * 60 * 60);
        return db.query(TABLE_EVENTS, null, "sync_status = ? AND unix_timestamp >= ?", 
                new String[]{"en_attente", String.valueOf(thirtyDaysAgo)}, null, null, "id ASC", null);
    }

    public void markEventAsSynced(String fingerprint, String newImpact) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("sync_status", "synced");
        values.put("impact", newImpact);
        db.update(TABLE_EVENTS, values, "fingerprint = ?", new String[]{fingerprint});
    }

    public String getRecentEventsForAssets(List<String> assets, int limit) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        if (assets == null || assets.isEmpty()) return "";
        
        StringBuilder whereClause = new StringBuilder();
        String[] whereArgs = new String[assets.size()];
        for (int i = 0; i < assets.size(); i++) {
            whereClause.append("target_assets LIKE ?");
            whereArgs[i] = "%" + assets.get(i) + "%";
            if (i < assets.size() - 1) whereClause.append(" OR ");
        }

        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, new String[]{"source", "title", "feed_content", "unix_timestamp"}, 
                    whereClause.toString(), whereArgs, null, null, "id DESC", String.valueOf(limit));

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    sb.append("- Source: ").append(cursor.getString(0))
                      .append(" | Alerte: ").append(cursor.getString(1))
                      .append(" (").append(cursor.getString(2)).append(")\n");
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur extraction contexte", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }

    public String getDailyMacroDrivers(long currentUnixTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        long twentyFourHoursAgo = currentUnixTime - (24 * 60 * 60);

        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, new String[]{"source", "title", "target_assets"}, 
                    "unix_timestamp >= ? AND (impact LIKE ? OR impact LIKE ?)", 
                    new String[]{String.valueOf(twentyFourHoursAgo), "%DRIVER%", "%PIVOT%"}, null, null, "id ASC", null);

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    sb.append("• [").append(cursor.getString(0)).append("] ")
                      .append(cursor.getString(1)).append(" | Actifs: ").append(cursor.getString(2)).append("\n");
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur Daily Drivers", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }

    public String getMonthlyMacroRegistry(long currentUnixTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        long thirtyDaysAgo = currentUnixTime - (30L * 24 * 60 * 60);

        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, new String[]{"source", "title", "target_assets", "unix_timestamp"},
                    "unix_timestamp >= ? AND (impact LIKE ? OR impact LIKE ?)",
                    new String[]{String.valueOf(thirtyDaysAgo), "%DRIVER%", "%PIVOT%"}, null, null, "id ASC", null);

            if (cursor != null && cursor.moveToFirst()) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM", java.util.Locale.getDefault());
                do {
                    long ts = cursor.getLong(3) * 1000;
                    sb.append("[").append(sdf.format(new java.util.Date(ts))).append("] ")
                      .append("(").append(cursor.getString(0)).append(") ").append(cursor.getString(1)).append("\n");
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur Registre Mensuel", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }

    public void purgeOldEvents(long currentUnixTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        long thirtyDaysAgo = currentUnixTime - (30L * 24 * 60 * 60);
        db.delete(TABLE_EVENTS, "unix_timestamp < ?", new String[]{String.valueOf(thirtyDaysAgo)});
    }
}
