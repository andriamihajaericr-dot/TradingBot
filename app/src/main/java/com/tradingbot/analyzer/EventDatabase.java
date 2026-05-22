package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class EventDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "trading_bot.db";
    // PASSAGE À LA VERSION 3 pour appliquer l'ajout de la colonne driver_weight
    private static final int DATABASE_VERSION = 3; 
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
                "sync_status TEXT DEFAULT 'synced', " +
                "driver_weight INTEGER DEFAULT 1)"; // Nouvelle colonne Hedge Fund
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN sync_status TEXT DEFAULT 'synced'");
            } catch (Exception e) { Log.e("EventDatabase", "Erreur upgrade V2", e); }
        }
        if (oldVersion < 3) {
            try {
                // Migration vers la V3 : Ajout de la colonne de poids/importance des drivers
                db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN driver_weight INTEGER DEFAULT 1");
                // On met à jour rétroactivement les anciens FOMC/CPI si existants pour ne pas les perdre
                db.execSQL("UPDATE " + TABLE_EVENTS + " SET driver_weight = 5 WHERE feed_content LIKE '%CPI%' OR feed_content LIKE '%FOMC%' OR feed_content LIKE '%FED%'");
            } catch (Exception e) { Log.e("EventDatabase", "Erreur upgrade V3", e); }
        }
    }

    // Modification de la signature pour sauvegarder le driver_weight
    public boolean saveEvent(String fingerprint, String packageName, String source, String eventType, 
                             String title, String feedContent, String targetAssets, String impact, 
                             int unixTimestamp, String syncStatus, int driverWeight) {
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
        values.put("driver_weight", driverWeight); // Sauvegarde du poids réel de la news

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

    /**
     * MÉTHODE HEDGE FUND : Extraction de la mémoire contextuelle à double vitesse.
     * Va chercher les 3 plus grosses ancres macro des 45 derniers jours (Poids 5 : FED, CPI, NFP)
     * ET les 4 derniers flashs d'actualité récents pour capturer le flux immédiat.
     */
    public String getRecentEventsForAssets(List<String> assets, int limit) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        if (assets == null || assets.isEmpty()) return "";
        
        // Construction de la clause WHERE pour cibler les actifs concernés
        StringBuilder whereClause = new StringBuilder();
        String[] whereArgs = new String[assets.size()];
        for (int i = 0; i < assets.size(); i++) {
            whereClause.append("target_assets LIKE ?");
            whereArgs[i] = "%" + assets.get(i) + "%";
            if (i < assets.size() - 1) whereClause.append(" OR ");
        }

        long now = System.currentTimeMillis() / 1000;
        long fortyFiveDaysAgo = now - (45L * 24 * 60 * 60);

        // --- PARTIE 1 : RÉCUPÉRATION DES ANCRES PILIERS (Poids = 5) ---
        sb.append("=== ANCRES MACRO DU MOIS (PILIER) ===\n");
        String sqlAnchors = "SELECT source, title, feed_content, unix_timestamp FROM " + TABLE_EVENTS +
                            " WHERE (" + whereClause.toString() + ") AND driver_weight = 5 AND unix_timestamp >= " + fortyFiveDaysAgo +
                            " ORDER BY id DESC LIMIT 3";
        
        Cursor cursorAnchors = null;
        try {
            cursorAnchors = db.rawQuery(sqlAnchors, whereArgs);
            if (cursorAnchors != null && cursorAnchors.moveToFirst()) {
                do {
                    sb.append("- ANCRE (Poids 5) | Source: ").append(cursorAnchors.getString(0))
                      .append(" | ").append(cursorAnchors.getString(1))
                      .append(" (").append(cursorAnchors.getString(2)).append(")\n");
                } while (cursorAnchors.moveToNext());
            } else {
                sb.append("(Aucune ancre majeure récente en mémoire)\n");
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur extraction ancres macro", e);
        } finally {
            if (cursorAnchors != null) cursorAnchors.close();
        }

        // --- PARTIE 2 : RÉCUPÉRATION DU FLUX IMMÉDIAT (Poids < 5 ou tout événement récent) ---
        sb.append("\n=== FLUX DE MARCHÉ RÉCENT (DERNIÈRES MINUTES) ===\n");
        String sqlFlux = "SELECT source, title, feed_content, unix_timestamp FROM " + TABLE_EVENTS +
                         " WHERE (" + whereClause.toString() + ") AND driver_weight < 5" +
                         " ORDER BY id DESC LIMIT 4";
        
        Cursor cursorFlux = null;
        try {
            cursorFlux = db.rawQuery(sqlFlux, whereArgs);
            if (cursorFlux != null && cursorFlux.moveToFirst()) {
                do {
                    sb.append("- FLUX | Source: ").append(cursorFlux.getString(0))
                      .append(" | ").append(cursorFlux.getString(1))
                      .append(" (").append(cursorFlux.getString(2)).append(")\n");
                } while (cursorFlux.moveToNext());
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur extraction flux récent", e);
        } finally {
            if (cursorFlux != null) cursorFlux.close();
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
                    "unix_timestamp >= ? AND (impact LIKE ? OR impact LIKE ? OR driver_weight >= 4)", 
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
                    "unix_timestamp >= ? AND (impact LIKE ? OR impact LIKE ? OR driver_weight = 5)",
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

    /**
     * PURGE SÉLECTIVE HEDGE FUND : On efface les petits tweets et bruits de marché de plus de 48h,
     * MAIS on protège les Ancres Macro (Poids = 5 : CPI, NFP, FOMC) qui restent en mémoire pendant 45 jours.
     */
    public void purgeOldEvents(long currentUnixTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        // 1. Nettoyage du bruit (Poids < 5) après 48 heures seulement
        long fortyEightHoursAgo = currentUnixTime - (2 * 24 * 60 * 60);
        int softDeleted = db.delete(TABLE_EVENTS, "unix_timestamp < ? AND driver_weight < 5", new String[]{String.valueOf(fortyEightHoursAgo)});
        Log.d("EventDatabase", "Purge Flux/Bruit effectuée : " + softDeleted + " lignes supprimées.");

        // 2. Nettoyage des gros Piliers (Poids = 5) uniquement après 45 jours
        long fortyFiveDaysAgo = currentUnixTime - (45L * 24 * 60 * 60);
        int hardDeleted = db.delete(TABLE_EVENTS, "unix_timestamp < ? AND driver_weight = 5", new String[]{String.valueOf(fortyFiveDaysAgo)});
        Log.d("EventDatabase", "Purge Piliers Macro effectuée : " + hardDeleted + " lignes supprimées.");
    }
}
