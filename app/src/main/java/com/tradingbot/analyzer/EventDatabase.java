package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.util.List;
public class EventDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "TradingBotEvents.db";
    private static final int DATABASE_VERSION = 2; // Version augmentée pour le support des drivers macro
    private static final String TABLE_EVENTS = "macro_events";

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
                "impact_bias TEXT, " +
                "unix_timestamp INTEGER, " + // Stockage propre en secondes UNIX
                "delivery_channel TEXT" +
                ")";
        db.execSQL(createTable);
        Log.d("EventDatabase", "Table des événements macro créée avec succès.");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_EVENTS);
        onCreate(db);
    }

    /**
     * Enregistre un événement capté en direct.
     * Le paramètre unixSeconds est formaté explicitement en 'int' pour éviter les crashs de conversion.
     */
    public boolean saveEvent(String fingerprint, String packageName, String source, String eventType,
                             String title, String feedContent, String targetAssets, String impactBias, 
                             int unixSeconds, String deliveryChannel) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("fingerprint", fingerprint);
        values.put("package_name", packageName);
        values.put("source", source);
        values.put("event_type", eventType);
        values.put("title", title);
        values.put("feed_content", feedContent);
        values.put("target_assets", targetAssets);
        values.put("impact_bias", impactBias);
        values.put("unix_timestamp", unixSeconds);
        values.put("delivery_channel", deliveryChannel);

        try {
            long result = db.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            return result != -1;
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur critique d'écriture SQLite", e);
            return false;
        }
    }

    public boolean eventExists(String fingerprint) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, new String[]{"id"}, "fingerprint=?", 
                    new String[]{fingerprint}, null, null, null);
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }
    /**
     * Extrait les X derniers événements enregistrés pour un groupe d'actifs donnés.
     * Permet à l'IA de construire une conclusion macroéconomique cumulative.
     */
    public String getRecentEventsForAssets(List<String> assets, int limit) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        
        if (assets == null || assets.isEmpty()) return "";
        
        // Construction dynamique de la clause WHERE pour cibler les actifs concernés
        StringBuilder whereClause = new StringBuilder();
        String[] whereArgs = new String[assets.size()];
        for (int i = 0; i < assets.size(); i++) {
            whereClause.append("target_assets LIKE ?");
            whereArgs[i] = "%" + assets.get(i) + "%";
            if (i < assets.size() - 1) {
                whereClause.append(" OR ");
            }
        }

        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, 
                    new String[]{"source", "title", "feed_content", "unix_timestamp"}, 
                    whereClause.toString(), 
                    whereArgs, null, null, "id DESC", String.valueOf(limit));

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String source = cursor.getString(0);
                    String title = cursor.getString(1);
                    String content = cursor.getString(2);
                    sb.append("- Source: ").append(source)
                      .append(" | Alerte: ").append(title).append(" (").append(content).append(")\n");
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur lors de l'extraction du contexte historique", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }
    /**
     * Nettoie les anciens événements pour éviter de saturer la mémoire du téléphone à Madagascar.
     * Garde uniquement les dernières 48 heures de trading.
     */
    public void cleanOldEvents() {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            long threshold = (System.currentTimeMillis() / 1000) - (48 * 60 * 60);
            db.delete(TABLE_EVENTS, "unix_timestamp < ?", new String[]{String.valueOf(threshold)});
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur lors du nettoyage de la BDD", e);
        }
    }
}
