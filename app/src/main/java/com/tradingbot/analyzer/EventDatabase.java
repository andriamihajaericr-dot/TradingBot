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
import java.util.Locale;
import java.util.ArrayList;

public class EventDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "trading_bot.db";
    private static final int DATABASE_VERSION = 3;
    public static final String TABLE_EVENTS = "events";

    // Implémentation du Singleton pour la sécurité d'accès concurrentiel (WAL)
    private static volatile EventDatabase instance;

    public static EventDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (EventDatabase.class) {
                if (instance == null) {
                    instance = new EventDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private EventDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // Activation du mode WAL (Write-Ahead Logging) pour éviter les accès bloquants
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

        // Index principal (le plus important)
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
            // FIX #7 : index créé directement dans le bloc de migration v3 (garantie)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_time_weight ON " +
                       TABLE_EVENTS + "(unix_timestamp, driver_weight);");
        }
    }

    // FIX #1 : timestamp en long (évite la troncature Y2K38 avec un int)
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
        // CORRECTION : Fenêtre élargie à 6h (21600s) pour couvrir les périodes offline prolongées
        long threshold = currentUnixTime - 21600;
        // FIX #2 : valeur unifiée "pending" (cohérence avec NotificationService)
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
    SQLiteDatabase db = this.getReadableDatabase();
    StringBuilder sb = new StringBuilder();
    long twentyFourHoursAgo = currentUnixTime - (24 * 60 * 60);

    // Ajustement : driver_weight >= 3 pour capturer les PMI/ISM dans le rapport
    String selection = "unix_timestamp >= ? AND (impact LIKE ? OR impact LIKE ? OR driver_weight >= 3)";
    String[] whereArgs = new String[]{
            String.valueOf(twentyFourHoursAgo),
            "%DRIVER%",
            "%PIVOT%"
    };

    Cursor cursor = null;
    try {
        // CORRECTION : On récupère source et title en plus pour donner la matière exacte à l'IA
        cursor = db.query(TABLE_EVENTS, new String[]{"source", "title", "feed_content", "impact"}, selection, whereArgs, null, null, "unix_timestamp ASC");
        if (cursor != null && cursor.moveToFirst()) {
            do {
                String src = cursor.getString(0);
                String title = cursor.getString(1);
                String content = cursor.getString(2);
                String impact = cursor.getString(3);

                // Nettoyage de l'en-tête temporel pour économiser les tokens de l'IA
                if (content.contains("\n\n")) {
                    String[] parts = content.split("\n\n", 2);
                    if (parts.length > 1) content = parts[1]; 
                }

                // Formatage quantitatif hautement lisible pour Groq/Gemini
                sb.append("--- ALERTE MACRO ---\n");
                sb.append("Source: ").append(src).append("\n");
                sb.append("Titre: ").append(title).append("\n");
                sb.append("Contenu: ").append(content).append("\n");
                sb.append("Impact calculé: ").append(impact).append("\n\n");
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

    // FIX #3 : double DELETE encapsulé dans une transaction atomique
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
        long twoHoursAgo = currentUnixTime - (2 * 60 * 60); // 2 heures
        
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

    /**
     * Récupère l'intégralité du contenu textuel des notifications des 30 dernières minutes
     */
    /**
     * Récupère l'intégralité du contenu textuel des notifications des 30 dernières minutes
     * CORRECTION : Remplacement de la colonne fictive 'description' par 'feed_content'
     */
    public List<String> obtenirTexteEvenementsRecents() {
        List<String> historique = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        
        // 30 minutes converties en secondes
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
    /**
 * Extrait l'unique dernier driver macroéconomique de la base de données
 * pour servir de rappel brut.
 */
public String obtenirLeToutDernierDriver() {
    StringBuilder sb = new StringBuilder();
    SQLiteDatabase db = this.getReadableDatabase();
    Cursor cursor = null;
    try {
        // ✅ SÉCURITÉ : On cherche le dernier événement validé et synchronisé par le bot
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
    //LISTER LES DONNÉES DE LA BASE DE DONNÉES 
    public void diagnostiquerTableEvents() {
    SQLiteDatabase db = this.getReadableDatabase();
    Cursor cursor = null;
    try {
        // On récupère les 5 derniers éléments insérés, sans aucun filtre
        cursor = db.rawQuery(
            "SELECT id, sync_status, source, title, driver_weight FROM " + TABLE_EVENTS + 
            " ORDER BY id DESC LIMIT 5", 
            null
        );

        Log.d("BOT_DIAGNOSTIC", "=== VÉRIFICATION DE LA BASE DE DONNÉES ===");
        if (cursor != null && cursor.moveToFirst()) {
            int count = 0;
            do {
                count++;
                int id = cursor.getInt(0);
                String syncStatus = cursor.getString(1);
                String source = cursor.getString(2);
                String title = cursor.getString(3);
                int weight = cursor.getInt(4);

                Log.d("BOT_DIAGNOSTIC", "Élément #" + count + " [ID: " + id + "]");
                Log.d("BOT_DIAGNOSTIC", "   -> Source      : " + source);
                Log.d("BOT_DIAGNOSTIC", "   -> Statut Sync : " + syncStatus);
                Log.d("BOT_DIAGNOSTIC", "   -> Poids       : " + weight);
                Log.d("BOT_DIAGNOSTIC", "   -> Titre       : " + title);
            } while (cursor.moveToNext());
        } else {
            Log.w("BOT_DIAGNOSTIC", "❌ La table TABLE_EVENTS est STRICTEMENT vide. Aucun enregistrement trouvé.");
        }
        Log.d("BOT_DIAGNOSTIC", "========================================");
    } catch (Exception e) {
        Log.e("BOT_DIAGNOSTIC", "Erreur lors du diagnostic", e);
    } finally {
        if (cursor != null) cursor.close();
    }
    }
}
