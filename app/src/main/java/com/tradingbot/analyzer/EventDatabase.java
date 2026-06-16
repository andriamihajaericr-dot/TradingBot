package com.tradingbot.analyzer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.TimeZone;  
import java.util.Date;      
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

public class EventDatabase extends SQLiteOpenHelper {
    private static final String TAG = "EventDatabase";   
    private static final String DATABASE_NAME = "trading_bot.db";
    private static final int DATABASE_VERSION = 3;
    public static final String TABLE_EVENTS = "events";
    
    // Implémentation du Singleton pour la sécurité d'accès concurrentiel (WAL)
    private static volatile EventDatabase instance;
    public static EventDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (EventDatabase.class) {
                if (instance == null) {
                    if (context == null) {
                        throw new IllegalStateException("EventDatabase.getInstance() requires a non-null Context");
                    }
                    instance = new EventDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }
    // À ajouter dans EventDatabase.java si absent
    @Override
    public void close() {
        super.close(); // ✅ Utilise la fermeture native de SQLiteOpenHelper d'Android
    }

   // À ajouter dans votre classe EventDatabase.java
     public synchronized void updateContent(String indicator, long timestamp, String newContent) {
    if (indicator == null || newContent == null) return;

    SQLiteDatabase db = this.getWritableDatabase();
    ContentValues values = new ContentValues();
    values.put("feed_content", newContent); // ✅ Colonne correcte

    // ✅ Fenêtre ±2h identique à updateActualIfMissing pour cohérence
    long secondsTimestamp = (timestamp > 9999999999L) ? (timestamp / 1000L) : timestamp;
    long windowStart = secondsTimestamp - (2 * 60 * 60);
    long windowEnd   = secondsTimestamp + (2 * 60 * 60);
    String searchPattern = "%" + indicator.trim() + "%";

    try {
        int rows = db.update(
            TABLE_EVENTS,           // ✅ Table correcte
            values,
            "unix_timestamp >= ? AND unix_timestamp <= ? AND (title LIKE ? OR feed_content LIKE ?)",
            new String[]{
                String.valueOf(windowStart),
                String.valueOf(windowEnd),
                searchPattern,
                searchPattern
            }
        );
        if (rows > 0) {
            Log.d("EventDatabase", "✅ updateContent : " + rows + " ligne(s) mises à jour pour " + indicator);
        } else {
            Log.w("EventDatabase", "⚠️ updateContent : aucune ligne trouvée pour " + indicator);
        }
    } catch (Exception e) {
        Log.e("EventDatabase", "Erreur updateContent pour " + indicator, e);
    }
     }

    // =========================================================================
    // ✅ CORRECTIF : Ajout de la méthode manquante appelée par MainActivity
    // =========================================================================
    public static void resetInstance() {
        synchronized (EventDatabase.class) {
            if (instance != null) {
                try {
                    instance.close(); // Ferme proprement la connexion existante si ouverte
                } catch (Exception ignored) {}
                instance = null; // Libère l'instance pour forcer une recréation au prochain getInstance()
                Log.d(TAG, "Instance de la base de données réinitialisée avec succès.");
            }
        }
    }

    private EventDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
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
                "driver_weight INTEGER DEFAULT 1)";
        db.execSQL(createTable);

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_time_weight ON " + 
                   TABLE_EVENTS + "(unix_timestamp, driver_weight);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_EVENTS + " ADD COLUMN driver_weight INTEGER DEFAULT 1");
            } catch (Exception e) {
                Log.d("EventDatabase", "driver_weight déjà présent");
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_time_weight ON " +
                       TABLE_EVENTS + "(unix_timestamp, driver_weight);");
        }
    }

    public synchronized boolean saveEvent(String fingerprint, String pkg, String src, String type,
                                          String title, String content, String assets, String impact,
                                          long timestamp, String status, int weight) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("fingerprint", fingerprint);
        cv.put("package_name", pkg);
        cv.put("source", src);
        cv.put("event_type", type);
        cv.put("title", title);
        cv.put("feed_content", content);
        cv.put("target_assets", assets);
        cv.put("impact", impact);
        cv.put("unix_timestamp", timestamp);
        cv.put("sync_status", status);
        cv.put("driver_weight", weight);

        long result = db.insertWithOnConflict(TABLE_EVENTS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return result != -1;
    }

    public Cursor getUnsyncedEvents(long currentUnixTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        long threshold = currentUnixTime - 21600;
        return db.query(TABLE_EVENTS, null,
                "sync_status = ? AND unix_timestamp >= ? AND driver_weight >= 2",
                new String[]{"pending", String.valueOf(threshold)},
                null, null, "unix_timestamp ASC");
    }

    public synchronized void markEventAsSynced(String fingerprint, String finalImpact) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("sync_status", "synced");
        cv.put("impact", finalImpact);
        db.update(TABLE_EVENTS, cv, "fingerprint = ?", new String[]{fingerprint});
    }
    
    public synchronized void updateEventWeight(String hash, int weight) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("driver_weight", weight); 
        try {
            db.update(TABLE_EVENTS, values, "fingerprint = ?", new String[]{hash});
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de la mise à jour du poids pour le hash : " + hash, e);
        }
    }
    
    public String getRecentEventsForAssets(List<String> assets, int limit) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();

        if (assets == null || assets.isEmpty()) return "";

        StringBuilder selection = new StringBuilder("sync_status = 'synced' AND (");
        for (int i = 0; i < assets.size(); i++) {
            selection.append("target_assets LIKE ?");
            if (i < assets.size() - 1) selection.append(" OR ");
        }
        selection.append(")");

        String[] whereArgs = new String[assets.size()];
        for (int i = 0; i < assets.size(); i++) {
            whereArgs[i] = "%" + assets.get(i) + "%";
        }

        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS,
                    new String[]{"unix_timestamp", "source", "feed_content", "impact"},
                    selection.toString(), whereArgs, null, null, "unix_timestamp DESC", String.valueOf(limit));

            if (cursor != null && cursor.moveToFirst()) {
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
                do {
                    long ts = cursor.getLong(0) * 1000;
                    String timeStr = sdf.format(new Date(ts));
                    sb.append("[").append(timeStr).append("] ")
                      .append(cursor.getString(1)).append(": ")
                      .append(cursor.getString(2)).append(" -> ").append(cursor.getString(3)).append("\n");
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur extraction mémoire contextuelle", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }

    public String getDailyMacroSummary(long currentUnixTime) {
    long twentyFourHoursAgo = currentUnixTime - (24 * 60 * 60);
    long sevenDaysAgo       = currentUnixTime - (7L * 24 * 60 * 60);

    // Clause 1 : tous événements >= poids 2 des dernières 24h
    // Clause 2 : piliers macro suprêmes (poids 5) sur 7 jours — mémoire inter-sessions
    // Clause 3 : géopolitique et calendaire même à poids 1, sur 7 jours
    String selection =
        "(unix_timestamp >= ? AND driver_weight >= 2) OR " +
        "(unix_timestamp >= ? AND driver_weight = 5) OR " +
        "(unix_timestamp >= ? AND (" +
            "event_type LIKE '%GEO%' OR " +
            "event_type = 'GEOPOLITICAL' OR " +
            "event_type = 'CALENDAR-RESULT' OR " +
            "impact LIKE '%Choc Géopolitique%'" +
        ") AND driver_weight >= 1)";

    String[] whereArgs = new String[]{
        String.valueOf(twentyFourHoursAgo),
        String.valueOf(sevenDaysAgo),
        String.valueOf(sevenDaysAgo)
    };

    SQLiteDatabase db = this.getReadableDatabase();
    StringBuilder sb = new StringBuilder();
    Cursor cursor = null;

    try {
        // ✅ target_assets et unix_timestamp ajoutés — essentiels pour l'IA
        cursor = db.query(TABLE_EVENTS,
                new String[]{"source", "title", "feed_content", "impact",
                             "event_type", "driver_weight", "target_assets", "unix_timestamp"},
                selection, whereArgs, null, null,
                "driver_weight DESC, unix_timestamp ASC"); // Suprêmes en premier

        if (cursor != null && cursor.moveToFirst()) {

            // Formatter les timestamps en heure Madagascar
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
            sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));

            do {
                String src      = cursor.getString(0);
                String title    = cursor.getString(1);
                String content  = cursor.getString(2);
                String impact   = cursor.getString(3);
                String type     = cursor.getString(4);
                int    weight   = cursor.getInt(5);
                String assets   = cursor.getString(6);
                long   tsEpoch  = cursor.getLong(7);

                // ✅ Nettoyage header sécurisé — ne supprime que si header reconnu
                if (content != null && content.contains("\n\n")) {
                    String[] parts = content.split("\n\n", 2);
                    // Ne splitter que si la première partie ressemble à un header court
                    if (parts.length > 1 && parts[0].length() < 120) {
                        content = parts[1];
                    }
                }

                // ✅ Classification des sections avec rang explicite
                boolean isCalendar = "CALENDAR-RESULT".equals(type) ||
                    (impact != null && impact.startsWith("CALENDRIER ÉCONOMIQUE"));
                boolean isGeo = type != null && (
                    type.contains("GEO") || type.equals("GEOPOLITICAL"));
                boolean isSupreme = weight >= 4;

                if (isCalendar) {
                    sb.append("--- 📅 RÉSULTAT CALENDAIRE OFFICIEL")
                      .append(isSupreme ? " [RANG SUPRÊME — PRIORITÉ ABSOLUE]" : "")
                      .append(" ---\n");
                } else if (isGeo) {
                    sb.append("--- 🌍 ÉVÉNEMENT GÉOPOLITIQUE")
                      .append(isSupreme ? " [ESCALADE CONFIRMÉE]" : "")
                      .append(" ---\n");
                } else if (isSupreme) {
                    sb.append("--- 🔴 DRIVER MACROÉCONOMIQUE SUPRÊME [PRIORITÉ ABSOLUE] ---\n");
                } else {
                    sb.append("--- ⚡ ALERTE MACRO / NEWS ---\n");
                }

                // ✅ Heure Madagascar pour chaque événement
                String heureEvent = sdf.format(new java.util.Date(tsEpoch * 1000L));

                sb.append("Heure (Mada): ").append(heureEvent).append("\n");
                sb.append("Source: ").append(src   != null ? src   : "N/A").append("\n");
                sb.append("Titre: ").append(title   != null ? title : "N/A").append("\n");
                sb.append("Contenu: ").append(content != null ? content : "N/A").append("\n");
                sb.append("Impact: ").append(impact  != null ? impact : "NEUTRE").append("\n");

                // ✅ Actifs ciblés — guide l'IA sur la matrice directionnelle
                if (assets != null && !assets.trim().isEmpty()) {
                    sb.append("Actifs ciblés: ").append(assets).append("\n");
                }

                sb.append("Poids macro: ").append(weight).append("/5\n\n");

            } while (cursor.moveToNext());
        }

    } catch (Exception e) {
        Log.e("EventDatabase", "Erreur construction Daily Macro Summary", e);
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
            cursor = db.query(TABLE_EVENTS, new String[]{"feed_content", "impact"},
                    "unix_timestamp >= ? AND (driver_weight = 5 OR impact LIKE '%PIVOT%')",
                    new String[]{String.valueOf(thirtyDaysAgo)}, null, null, "unix_timestamp ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    sb.append("(").append(cursor.getString(1)).append(") ").append(cursor.getString(0)).append("\n");
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
        db.beginTransaction();
        try {
            long fortyEightHoursAgo = currentUnixTime - (2 * 24 * 60 * 60);
            int softDeleted = db.delete(TABLE_EVENTS, "unix_timestamp < ? AND driver_weight < 5", new String[]{String.valueOf(fortyEightHoursAgo)});
            Log.d("EventDatabase", "Purge Flux/Bruit effectuée : " + softDeleted + " lignes supprimées.");

            long fortyFiveDaysAgo = currentUnixTime - (45L * 24 * 60 * 60);
            int hardDeleted = db.delete(TABLE_EVENTS, "unix_timestamp < ? AND driver_weight = 5", new String[]{String.valueOf(fortyFiveDaysAgo)});
            Log.d("EventDatabase", "Purge Piliers ancres effectuée : " + hardDeleted + " lignes nettoyées.");

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }   
    
    public boolean isDriverActiveRecently(String eventType, long currentUnixTime) {
        if (eventType == null || eventType.isEmpty()) return false;
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        long twoHoursAgo = currentUnixTime - (2 * 60 * 60); 
        
        try {
            cursor = db.rawQuery(
                "SELECT 1 FROM " + TABLE_EVENTS + 
                " WHERE event_type = ? AND unix_timestamp >= ? AND driver_weight >= 4 LIMIT 1",
                new String[]{eventType, String.valueOf(twoHoursAgo)}
            );
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur filtre inertie macro", e);
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public String getLastEventByType(String eventType) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS, 
                    new String[]{"title", "feed_content", "impact", "unix_timestamp"},
                    "event_type = ? AND sync_status = 'synced'",
                    new String[]{eventType}, null, null, "unix_timestamp DESC", "1");
            if (cursor != null && cursor.moveToFirst()) {
                String title = cursor.getString(0);
                String content = cursor.getString(1);
                String impact = cursor.getString(2);
                long ts = cursor.getLong(3);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
                String timeStr = sdf.format(new java.util.Date(ts * 1000));
                String shortContent = content.length() > 200 ? content.substring(0, 200) + "…" : content;
                return "🕒 " + timeStr + "\n📌 " + title + "\n📝 " + shortContent + "\n⚡ Impact: " + impact;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur getLastEventByType", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return "Aucun historique trouvé pour ce driver.";
    }

    public List<String> obtenirTexteEvenementsRecents() {
        List<String> historique = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        
        long trenteMinutesEnSec = (System.currentTimeMillis() / 1000L) - (30 * 60);
        
        try {
            cursor = db.rawQuery(
                "SELECT feed_content FROM " + TABLE_EVENTS + " WHERE unix_timestamp >= ? ORDER BY unix_timestamp DESC",
                new String[]{String.valueOf(trenteMinutesEnSec)}
            );
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    historique.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur lors de la récupération de l'historique récent des 30 min", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return historique;
    }

    public String obtenirLeToutDernierDriver() {
        StringBuilder sb = new StringBuilder();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                "SELECT unix_timestamp, title, feed_content FROM " + TABLE_EVENTS + 
                " WHERE sync_status = 'synced' ORDER BY unix_timestamp DESC LIMIT 1", 
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                long ts = cursor.getLong(0);
                String titre = cursor.getString(1);
                String contenu = cursor.getString(2);
                
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE);
                String dateStr = sdf.format(new java.util.Date(ts * 1000L));
                
                sb.append("📅 *Dernier état de marché validé (").append(dateStr).append(")* \n")
                  .append("🔹 *").append(titre).append("* \n")
                  .append(contenu);
            }
        } catch (Exception e) {
            Log.e("EventDatabase", "Erreur lors de la récupération du dernier driver historique", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }

    public void diagnostiquerTableEvents() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                "SELECT id, sync_status, source, title, driver_weight FROM " + TABLE_EVENTS + 
                " ORDER BY id DESC LIMIT 5", 
                null
            );
    
            System.out.println("=== GITHUB RUNNER DIAGNOSTIC: TABLE EVENTS ===");
            if (cursor != null && cursor.moveToFirst()) {
                int count = 0;
                do {
                    count++;
                    int id = cursor.getInt(0);
                    String syncStatus = cursor.getString(1);
                    String source = cursor.getString(2);
                    String title = cursor.getString(3);
                    int weight = cursor.getInt(4);
    
                    System.out.println("Élément #" + count + " [ID: " + id + "]");
                    System.out.println("   -> Source      : " + source);
                    System.out.println("   -> Statut Sync : " + syncStatus);
                    System.out.println("   -> Poids       : " + weight);
                    System.out.println("   -> Titre       : " + title);
                } while (cursor.moveToNext());
            } else {
                System.out.println("❌ La table TABLE_EVENTS est STRICTEMENT vide sur ce Runner GitHub.");
            }
            System.out.println("========================================");
        } catch (Exception e) {
            System.out.println("Erreur lors du diagnostic : " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public String getDerniersDriversGeo(long currentUnixTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        long fortyEightHoursAgo = currentUnixTime - (48 * 60 * 60);
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS,
        new String[]{"title", "impact", "unix_timestamp"},
        "unix_timestamp >= ? AND (" +
        "event_type = 'GEOPOLITICAL' OR " +
        "event_type LIKE '%GEO%' OR " +
        "impact LIKE '%Choc Géopolitique%' OR " +
        "impact LIKE '%GÉOPOLITIQUE%' OR " +
        "impact LIKE '%GEO%' OR " +
        "title LIKE '%IRAN%' OR title LIKE '%iran%' OR " +
        "title LIKE '%ISRAEL%' OR title LIKE '%israel%' OR " +
        "title LIKE '%TEHRAN%' OR title LIKE '%tehran%' OR " +
        "title LIKE '%AIRSTRIKE%' OR title LIKE '%airstrike%' OR " +
        "title LIKE '%STRIKE%' OR title LIKE '%strike%' OR " +
        "title LIKE '%ATTACK%' OR title LIKE '%attack%' OR " +
        "title LIKE '%MISSILE%' OR title LIKE '%missile%' OR " +
        "title LIKE '%DRONE%' OR title LIKE '%drone%' OR " +
        "title LIKE '%UKRAINE%' OR title LIKE '%ukraine%' OR " +
        "title LIKE '%RUSSIA%' OR title LIKE '%russia%' OR " +
        "title LIKE '%HORMUZ%' OR title LIKE '%hormuz%' OR " +
        "title LIKE '%RED SEA%' OR title LIKE '%red sea%' OR " +
        "title LIKE '%HEZBOLLAH%' OR title LIKE '%hezbollah%' OR " +
        "title LIKE '%HOUTHI%' OR title LIKE '%houthi%' OR " +
        "title LIKE '%GAZA%' OR title LIKE '%gaza%' OR " +
        "title LIKE '%TAIWAN%' OR title LIKE '%taiwan%' OR " +
        "title LIKE '%PUTIN%' OR title LIKE '%putin%' OR " +
        "title LIKE '%WAR%' OR title LIKE '%war%' OR " +
        "title LIKE '%CONFLICT%' OR title LIKE '%conflict%' OR " +
        "feed_content LIKE '%airstrike%' OR " +
        "feed_content LIKE '%missile%' OR " +
        "feed_content LIKE '%GEOPOLIT%')",
        new String[]{String.valueOf(fortyEightHoursAgo)},
        null, null, "unix_timestamp DESC", "5");

            if (cursor != null && cursor.moveToFirst()) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
                sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
                do {
                    long ts = cursor.getLong(2);
                    String dateStr = sdf.format(new Date(ts * 1000));
                    sb.append("⚠️ [").append(dateStr).append("] ")
                      .append(cursor.getString(0))
                      .append(" | ").append(cursor.getString(1))
                      .append("\n");
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur getDerniersDriversGeo", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }

    public String diagnostiquerDriverSpecifique(String keyword) {
        SQLiteDatabase db = this.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        long fortyEightHoursAgo = (System.currentTimeMillis() / 1000) - (48 * 60 * 60);
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_EVENTS,
                new String[]{"title", "source", "impact", "driver_weight",
                             "sync_status", "unix_timestamp", "event_type"},
                "unix_timestamp >= ? AND (title LIKE ? OR feed_content LIKE ?)",
                new String[]{
                    String.valueOf(fortyEightHoursAgo),
                    "%" + keyword + "%",
                    "%" + keyword + "%"
                },
                null, null, "unix_timestamp DESC");

            if (cursor != null && cursor.getCount() > 0) {
                sb.append("✅ [DB] ").append(cursor.getCount())
                  .append(" entrée(s) trouvée(s) pour : ").append(keyword).append("\n");
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
                sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
                cursor.moveToFirst(); 
                do {
                    long ts = cursor.getLong(5);
                    String dateStr = sdf.format(new Date(ts * 1000));
                    sb.append("  → [").append(dateStr).append("] ")
                      .append(cursor.getString(0))
                      .append(" | Source: ").append(cursor.getString(1))
                      .append(" | Poids: ").append(cursor.getInt(3))
                      .append(" | Status: ").append(cursor.getString(4))
                      .append(" | Type: ").append(cursor.getString(6))
                      .append("\n");
                } while (cursor.moveToNext());
            } else {
                sb.append("❌ [DB] Aucune entrée pour : ").append(keyword)
                  .append(" (dernières 48h)\n");
            }
        } catch (Exception e) {
            sb.append("⚠️ Erreur diagnostic : ").append(e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return sb.toString();
    }

    public String detecterRegimeMarche(long currentUnixTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        long sevenDaysAgo = currentUnixTime - (7 * 24 * 60 * 60);
        Cursor cursor = null;

        int scoreHawkish  = 0;
        int scoreDovish   = 0;
        int scoreGeo      = 0;
        int scoreRiskOn   = 0;
        int scoreRiskOff  = 0;
        int totalEvents   = 0;

        try {
            cursor = db.query(TABLE_EVENTS,
                new String[]{"impact", "event_type", "driver_weight", "title"},
                "unix_timestamp >= ? AND driver_weight >= 3",
                new String[]{String.valueOf(sevenDaysAgo)},
                null, null, "unix_timestamp DESC");

            if (cursor == null || cursor.getCount() == 0) {
                return "RÉGIME INDÉTERMINÉ | Aucun driver macro significatif sur 7 jours.";
            }

            cursor.moveToFirst();
            do {
                String impact    = cursor.getString(0) != null ? cursor.getString(0).toUpperCase() : "";
                String eventType = cursor.getString(1) != null ? cursor.getString(1).toUpperCase() : "";
                int weight       = cursor.getInt(2);
                String title     = cursor.getString(3) != null ? cursor.getString(3).toUpperCase() : "";

                totalEvents++;

                int multiplier = (weight >= 5) ? 3 : (weight == 4) ? 2 : 1;

                if (impact.contains("BIAIS HAUSSIER") || impact.contains("HAWKISH") ||
                    impact.contains("HIGHER THAN EXPECTED") || impact.contains("ABOVE FORECAST") ||
                    impact.contains("BEATS ESTIMATES") || impact.contains("BETTER THAN EXPECTED") ||
                    title.contains("HAWKISH") || title.contains("RATE HIKE") ||
                    title.contains("BEATS") || title.contains("ABOVE FORECAST")) {
                    scoreHawkish += multiplier;
                    scoreRiskOff += multiplier; 
                }

                else if (impact.contains("BIAIS BAISSIER") || impact.contains("DOVISH") ||
                         impact.contains("LOWER THAN EXPECTED") || impact.contains("BELOW FORECAST") ||
                         impact.contains("MISSES ESTIMATES") || impact.contains("WORSE THAN EXPECTED") ||
                         title.contains("DOVISH") || title.contains("RATE CUT") ||
                         title.contains("MISSES") || title.contains("BELOW FORECAST")) {
                    scoreDovish += multiplier;
                    scoreRiskOn += multiplier; 
                }

                if (eventType.contains("GEO") ||
                    impact.contains("CHOC GÉOPOLITIQUE") ||
                    impact.contains("GEO") ||
                    title.contains("IRAN") || title.contains("ISRAEL") ||
                    title.contains("UKRAINE") || title.contains("RUSSIA") ||
                    title.contains("MISSILE") || title.contains("STRIKE") ||
                    title.contains("HORMUZ") || title.contains("RED SEA") ||
                    title.contains("TAIWAN") || title.contains("HOUTHI")) {
                    scoreGeo     += multiplier;
                    scoreRiskOff += multiplier;
                }

            } while (cursor.moveToNext());

        } catch (Exception e) {
            Log.e(TAG, "Erreur detecterRegimeMarche", e);
            return "RÉGIME INDÉTERMINÉ | Erreur de calcul.";
        } finally {
            if (cursor != null) cursor.close();
        }

        int scoreTotal = scoreHawkish + scoreDovish + scoreGeo;
        if (scoreTotal == 0) {
            return "RÉGIME NEUTRE | Aucun signal directionnel dominant sur 7 jours.";
        }

        String regimePrincipal;
        String regimeDetail;

        if (scoreGeo > 0 && (scoreGeo * 100 / scoreTotal) >= 60) {
            regimePrincipal = "RÉGIME RISK-OFF GÉOPOLITIQUE DOMINANT 🔴";
            regimeDetail    = "Les chocs géopolitiques dominent le flux macro. " +
                              "Or et Yen sont les refuges prioritaires. " +
                              "Pétrole sous pression haussière structurelle.";
        }
        else if (scoreHawkish > scoreDovish && scoreHawkish > scoreGeo &&
                 (scoreHawkish * 100 / scoreTotal) >= 50) {
            regimePrincipal = "RÉGIME HAWKISH DOMINANT 🦅";
            regimeDetail    = "Les données macro US confirment un contexte de resserrement monétaire. " +
                              "Dollar structurellement fort. " +
                              "Actions et actifs risk-on sous pression.";
        }
        else if (scoreDovish > scoreHawkish && scoreDovish > scoreGeo &&
                 (scoreDovish * 100 / scoreTotal) >= 50) {
            regimePrincipal = "RÉGIME DOVISH DOMINANT 🕊️";
            regimeDetail    = "Les données macro US confirment un contexte accommodant. " +
                              "Dollar structurellement faible. " +
                              "Actions et actifs risk-on favorisés.";
        }
        else if (scoreHawkish > 0 && scoreGeo > 0 &&
                 Math.abs(scoreHawkish - scoreGeo) <= 2) {
            regimePrincipal = "RÉGIME STAGFLATIONNISTE / DOUBLE CHOC ⚠️";
            regimeDetail    = "Coexistence d'un choc HAWKISH (inflation) et d'un choc GÉOPOLITIQUE. " +
                              "Or est le seul actif avec double bénéfice. " +
                              "Pétrole haussier. Toutes les devises sous pression.";
        }
        else if (scoreHawkish > 0 && scoreDovish > 0 &&
                 Math.abs(scoreHawkish - scoreDovish) <= 2) {
            regimePrincipal = "RÉGIME MIXTE / INCERTAIN 🔄";
            regimeDetail    = "Les signaux macro sont contradictoires sur 7 jours. " +
                              "Conviction réduite sur tous les actifs. " +
                              "Attendre une confirmation directionnelle.";
        }
        else {
            regimePrincipal = "RÉGIME NEUTRE FAIBLE SIGNAL 〰️";
            regimeDetail    = "Aucun signal macro dominant détecté. Marché en attente de catalyseur.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(regimePrincipal).append("\n");
        sb.append("─────────────────────────────────\n");
        sb.append("📊 Score HAWKISH  : ").append(scoreHawkish).append("\n");
        sb.append("📊 Score DOVISH   : ").append(scoreDovish).append("\n");
        sb.append("📊 Score GEO      : ").append(scoreGeo).append("\n");
        sb.append("📊 Score RISK-ON  : ").append(scoreRiskOn).append("\n");
        sb.append("📊 Score RISK-OFF : ").append(scoreRiskOff).append("\n");
        sb.append("📊 Total événements analysés : ").append(totalEvents).append("\n");
        sb.append("─────────────────────────────────\n");
        sb.append("📝 ").append(regimeDetail);

        return sb.toString();
    }

    public List<String> getMissingSupremeRankIndicators(long currentUnixTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        List<String> missing = new ArrayList<>();
        long thirtyDaysAgo = currentUnixTime - (30L * 24 * 60 * 60);

        String[][] supremeEvents = {
            {"NFP", "NON-FARM", "PAYROLL", "EMPLOYMENT CHANGE"},          
            {"CPI", "CORE CPI", "CONSUMER PRICE"},                         
            {"FOMC", "FEDERAL RESERVE", "RATE DECISION"},                  
            {"PCE", "CORE PCE", "PERSONAL CONSUMPTION"},                   
            {"PPI", "PRODUCER PRICE"},                                      
            {"JOBLESS CLAIMS", "INITIAL CLAIMS"},                           
            {"GDP", "GROSS DOMESTIC"},                                      
            {"ADP", "ADP EMPLOYMENT"},                                      
            {"JOLTS", "JOB OPENINGS"}                                       
        };

        for (String[] keywords : supremeEvents) {
            String mainKeyword = keywords[0];
            StringBuilder whereClause = new StringBuilder();
            whereClause.append("unix_timestamp >= ? AND driver_weight >= 4 AND (");
            for (int i = 0; i < keywords.length; i++) {
                if (i > 0) whereClause.append(" OR ");
                whereClause.append("title LIKE ? OR feed_content LIKE ?");
            }
            whereClause.append(")");

            List<String> args = new ArrayList<>();
            args.add(String.valueOf(thirtyDaysAgo));
            for (String kw : keywords) {
                args.add("%" + kw + "%");
                args.add("%" + kw + "%");
            }

            Cursor cursor = null;
            try {
                cursor = db.query(TABLE_EVENTS, new String[]{"COUNT(*)"} ,
                        whereClause.toString(),
                        args.toArray(new String[0]),
                        null, null, null);

                if (cursor != null && cursor.moveToFirst()) {
                    int count = cursor.getInt(0);
                    if (count == 0) {
                        missing.add(mainKeyword);
                        Log.d(TAG, "[BACKFILL] Manquant dans DB : " + mainKeyword);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur getMissingSupremeRankIndicators", e);
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        return missing;
    }
        
    public boolean isEventAlreadySaved(String indicator, long unixTimestamp) {
        if (indicator == null || indicator.trim().isEmpty()) return false;
        
        SQLiteDatabase db = this.getReadableDatabase();
        if (db == null || !db.isOpen()) return false;
        
        long secondsTimestamp = (unixTimestamp > 9999999999L) ? (unixTimestamp / 1000L) : unixTimestamp;
        
        long windowStart = secondsTimestamp - (2 * 60 * 60); 
        long windowEnd   = secondsTimestamp + (2 * 60 * 60);
        
        String searchPattern = "%" + indicator.trim() + "%";
        
        try (Cursor cursor = db.query(
                TABLE_EVENTS,
                new String[]{"COUNT(*)"},
                "unix_timestamp >= ? AND unix_timestamp <= ? AND (title LIKE ? OR feed_content LIKE ?)",
                new String[]{
                    String.valueOf(windowStart),
                    String.valueOf(windowEnd),
                    searchPattern,
                    searchPattern
                },
                null, null, null)) {
            
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) > 0; 
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de la vérification du doublon pour l'indicateur : " + indicator, e);
        }
        
        return false; 
    } 
    /**
     * Met à jour la valeur "Actual" manquante pour un indicateur donné, recalcule son impact
     * via le moteur quantitatif et déclenche le pipeline de notification Telegram.
     *
     * @param indicator     Nom ou mot-clé de l'indicateur économique.
     * @param unixTimestamp Timestamp de la publication (gère secondes et millisecondes).
     * @param actualValue   La nouvelle valeur réelle publiée.
     * @return true si au moins un événement correspondant a été mis à jour et synchronisé.
     */
    public synchronized boolean updateActualIfMissing(String indicator, long unixTimestamp, String actualValue) {
        // Validation stricte des entrées pour éviter des écritures aberrantes
        if (indicator == null || indicator.trim().isEmpty() || actualValue == null || actualValue.equalsIgnoreCase("N/A")) {
            return false;
        }

        SQLiteDatabase db = this.getWritableDatabase();
        
        // 🛡️ SÉCURITÉ TIMESTAMP : conversion automatique si le timestamp envoyé est par erreur en millisecondes
        long secondsTimestamp = (unixTimestamp > 9999999999L) ? (unixTimestamp / 1000L) : unixTimestamp;
        
        // Fenêtre de tolérance de ±2h autour de la release officielle
        long windowStart = secondsTimestamp - (2 * 60 * 60); 
        long windowEnd   = secondsTimestamp + (2 * 60 * 60);
        String searchPattern = "%" + indicator.trim() + "%";

        Cursor cursor = null;
        boolean updatedAtLeastOne = false; // 🔄 OPTIMISATION : Évite le court-circuit prématuré de la boucle

        try {
            // ✅ PROJECTION SÉCURISÉE : Récupération explicite des 3 colonnes requises pour éviter tout crash
            cursor = db.query(TABLE_EVENTS,
                    new String[]{"id", "feed_content", "title"},
                    "unix_timestamp >= ? AND unix_timestamp <= ? AND (title LIKE ? OR feed_content LIKE ?)",
                    new String[]{String.valueOf(windowStart), String.valueOf(windowEnd), searchPattern, searchPattern},
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(0);
                    String content = cursor.getString(1);
                    String title = cursor.getString(2); // Extraction sécurisée du titre original

                    // On n'applique le rattrapage que si l'événement possède encore le tag initial "Actual: N/A"
                    if (content != null && content.contains("Actual: N/A")) {
                        String updatedContent = content.replace("Actual: N/A", "Actual: " + actualValue);
                        
                        // 🧠 MOTEUR QUANTITATIF : Recalcul immédiat du biais, de l'impact macro et de la déviation
                        EconomicAnalyzer.EvaluationResult evalResult = EconomicAnalyzer.analyserEvenement(title, updatedContent);
                        
                        ContentValues cv = new ContentValues();
                        cv.put("feed_content", updatedContent);
                        cv.put("impact", evalResult.marketImpact + " | " + evalResult.directionText);
                        cv.put("driver_weight", evalResult.weight);
                        cv.put("sync_status", "synced"); // On verrouille l'état comme traité/synchronisé
                        
                        db.update(TABLE_EVENTS, cv, "id = ?", new String[]{String.valueOf(id)});
                        
                        // 🚀 PIPELINE TELEGRAM : Recherche résiliente du contexte Android actif pour l'expédition
                        android.content.Context context = (MainActivity.instance != null) ? MainActivity.instance : NotificationService.getInstance();
                        if (context != null) {
                            // Collecte dynamique des liaisons intermarchés (Forex, Indices, Gold)
                            List<String> assets = EconomicCalendarAPI.mapIndicatorToAssetsIntermarket(title, "United States");
                            
                            // 🛡️ SÉCURITÉ NPE : Si la liste revient nulle, on l'initialise à vide pour éviter un crash interne
                            if (assets == null) {
                                assets = new ArrayList<>();
                            }
                            
                            String source = "CalendarSync";
                            String body = updatedContent;
                            
                            // Injection de la direction mathématique exacte (déviation par rapport aux prévisions)
                            if (evalResult.isParsed && !Double.isNaN(evalResult.deviation)) {
                                String deviationText = evalResult.deviation > 0 ? "HIGHER" : (evalResult.deviation < 0 ? "LOWER" : "AS EXPECTED");
                                body += " | Deviation: " + deviationText;
                            }
                            
                            // Envoi instantané vers le traitement LLM et Telegram
                            NotificationService.sendToGroqAndTelegram(source, title, body, assets, context.getApplicationContext());
                        } else {
                            // 🛡️ LOG D'ALERTE CONTEXTE : Permet de diagnostiquer une absence de contexte dans Logcat sans crasher
                            Log.w(TAG, "🤖 [WARNING] updateActualIfMissing pour '" + title + "' mis à jour en DB mais aucun Context actif pour notifier Telegram.");
                        }
                        
                        updatedAtLeastOne = true; // Une ligne a été traitée avec succès, on continue la boucle pour les suivantes
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur critique dans updateActualIfMissing pour " + indicator, e);
        } finally {
            // 🛡️ NETTOYAGE DES RESSOURCES : On ferme toujours le curseur pour éviter les fuites de mémoire (Cursor Leaks)
            if (cursor != null) cursor.close();
        }
        
        return updatedAtLeastOne; // Retourne true si au moins une mise à jour/notification a eu lieu
    }
}
