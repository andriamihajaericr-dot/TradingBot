package com.tradingbot.analyzer;

import java.util.Locale; 
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "trading_alerts";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    
    // URL de secours pour récupérer l'historique macroéconomique en cas de déconnexion prolongée
    private static final String BACKUP_FEED_URL = "https://www.financialjuice.com/feed"; 

    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;
    private boolean isSyncing = false;

    public static void sendTelegramSecure(String message) {
        new Thread(() -> {
            try {
                if (MainActivity.TELEGRAM_TOKEN.isEmpty() || MainActivity.TELEGRAM_CHAT_ID.isEmpty()) return;
                String urlString = "https://api.telegram.org/bot" + MainActivity.TELEGRAM_TOKEN 
                        + "/sendMessage?chat_id=" + MainActivity.TELEGRAM_CHAT_ID 
                        + "&parse_mode=Markdown&text=" + URLEncoder.encode(message, "UTF-8");
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { Log.e(TAG, "Échec Telegram (Hors-ligne)"); }
        }).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        createNotificationChannel();
        startDailyBriefScheduler();
        startMonthlyReportScheduler();
        registerNetworkCallback();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!getSharedPreferences("TradingBot", MODE_PRIVATE).getBoolean("bot_active", false)) return;

        String packageName = sbn.getPackageName().toLowerCase();
        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String unifiedFeed = (title + " " + text).trim();

        if (unifiedFeed.length() < 10) return;

        String sourceName = "Source Institutionnelle";
        if (packageName.contains("financialjuice")) sourceName = "FinancialJuice";
        else if (packageName.contains("investing")) sourceName = "Investing.com";
        else if (packageName.contains("twitter") || packageName.contains("periscope")) sourceName = "X / Twitter";
        else return; 

        processRawMacroFeed(sourceName, title, text, unifiedFeed, packageName, sbn.getPostTime(), false);
    }

    /**
     * Coeur du traitement : Valide, filtre et stocke les drivers
     */
    private void processRawMacroFeed(String sourceName, String title, String text, String unifiedFeed, String packageName, long postTime, boolean isFromScraper) {
        List<String> targetAssets = filterActiveAssets(unifiedFeed);
        boolean isDriverChanged = detectDriverDeviation(unifiedFeed);
        boolean isFomcPivot = unifiedFeed.toUpperCase().contains("FOMC") || unifiedFeed.toUpperCase().contains("FED ");
        
        long exactTimestamp = parseTimeFromText(unifiedFeed, postTime);
        String fingerPrint = generateSecureHash(title + text);
        
        if (eventDb.eventExists(fingerPrint)) return;
        long unixSeconds = exactTimestamp / 1000;

        if (!isDriverChanged && !isFomcPivot) {
            eventDb.saveEvent(fingerPrint, packageName, sourceName, "Macro-Feed", title, unifiedFeed, 
                    String.join(", ", targetAssets), "Conforme", (int) unixSeconds, "synced");
            return; 
        }

        String initialImpact = isFomcPivot ? "PIVOT CRITIQUE FOMC" : "CHANGEMENT DE DRIVER MACRO";
        String syncStatus = (isDeviceOnline() && !isFromScraper) ? "en_attente" : "en_attente";
        
        // Sauvegarde locale sécurisée
        boolean saved = eventDb.saveEvent(fingerPrint, packageName, sourceName, "Macro-Choc", title, unifiedFeed, 
                String.join(", ", targetAssets), initialImpact, (int) unixSeconds, syncStatus);

        if (saved && isDeviceOnline()) {
            triggerQueueSynchronization();
        }
    }

    private boolean detectDriverDeviation(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("HIGHER THAN EXPECTED") || upper.contains("LOWER THAN EXPECTED") ||
            upper.contains("ABOVE FORECAST") || upper.contains("BELOW FORECAST") ||
            upper.contains("BEATS ESTIMATES") || upper.contains("MISSES ESTIMATES") ||
            upper.contains("SURPRISE") || upper.contains("SHOCK") || upper.contains("UNEXPECTED") ||
            upper.contains("BREAKING") || upper.contains("REVISE")) {
            return true;
        }
        Pattern pattern = Pattern.compile("(ACTUAL|ACT):?\\s*([\\d\\.\\-%]+).*?(FORECAST|EST|EXP):?\\s*([\\d\\.\\-%]+)");
        Matcher matcher = pattern.matcher(upper);
        if (matcher.find()) {
            try {
                double actual = Double.parseDouble(matcher.group(2).replaceAll("[^\\d\\.]", ""));
                double forecast = Double.parseDouble(matcher.group(4).replaceAll("[^\\d\\.]", ""));
                return actual != forecast;
            } catch (Exception e) { return true; }
        }
        return false;
    }

    /**
     * 📡 SYNCHRONISATEUR DE FILE : Traite les données locales et récupère les données manquantes sur internet
     */
    private synchronized void triggerQueueSynchronization() {
        if (isSyncing || !isDeviceOnline()) return;
        isSyncing = true;

        exec.submit(() -> {
            try {
                long now = System.currentTimeMillis() / 1000;
                
                // 1. Récupération forcé de l'historique sur les serveurs si on a été déconnecté longtemps
                fetchMissingDriversFromWeb();

                // 2. Traitement de la file d'attente globale accumulée
                Cursor cursor = eventDb.getUnsyncedEvents(now);
                if (cursor != null && cursor.moveToFirst()) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("🔄 [RÉSEAU] Alignement des drivers suite à reconnexion...");
                    }
                    do {
                        String fingerprint = cursor.getString(cursor.getColumnIndexOrThrow("fingerprint"));
                        String source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                        String feed = cursor.getString(cursor.getColumnIndexOrThrow("feed_content"));
                        String assetsStr = cursor.getString(cursor.getColumnIndexOrThrow("target_assets"));
                        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("unix_timestamp")) * 1000;
                        String impactType = cursor.getString(cursor.getColumnIndexOrThrow("impact"));
                        
                        List<String> assets = Arrays.asList(assetsStr.split(", "));
                        String historyContext = eventDb.getRecentEventsForAssets(assets, 5);
                        boolean isFomc = impactType.contains("FOMC");

                        boolean success = processSingleEventDelayed(source, feed, historyContext, assets, timestamp, isFomc, fingerprint);
                        if (!success) break;

                    } while (cursor.moveToNext());
                    cursor.close();
                }
            } catch (Exception e) { Log.e(TAG, "Erreur synchronisation", e); }
            isSyncing = false;
        });
    }

    /**
     * 🌐 MOTEUR DE RECUPERATION RETROACTIF (Scraper de Secours)
     * Se connecte aux flux web pour extraire ce qui s'est passé pendant votre absence
     */
    private void fetchMissingDriversFromWeb() {
        try {
            URL url = new URL(BACKUP_FEED_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            if (conn.getResponseCode() == 200) {
                BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = rd.readLine()) != null) sb.append(line);
                rd.close();

                // Parsing XML standardisé des flux RSS d'actualité financière
                Pattern itemPattern = Pattern.compile("<item>.*?<title>(.*?)</title>.*?<description>(.*?)</description>.*?</item>");
                Matcher matcher = itemPattern.matcher(sb.toString());
                
                while (matcher.find()) {
                    String title = matcher.group(1).replaceAll("<!\\[CDATA\\[|]]>", "").trim();
                    String desc = matcher.group(2).replaceAll("<!\\[CDATA\\[|]]>", "").trim();
                    String fullFeed = title + " " + desc;
                    
                    // Réinjection rétroactive dans le moteur de tri
                    processRawMacroFeed("FinancialJuice (Web-Sync)", title, desc, fullFeed, "com.financialjuice.web", System.currentTimeMillis(), true);
                }
            }
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "Le serveur de secours web n'a pas répondu ou flux protégé.", e); }
    }

    private boolean processSingleEventDelayed(String source, String feed, String history, List<String> assets, long timestamp, boolean isFomc, String fingerprint) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
            String timeString = sdf.format(new Date(timestamp)) + " (Mada)";

            JSONObject payload = new JSONObject(); payload.put("model", GROQ_MODEL); payload.put("temperature", 0.02);
            JSONArray messages = new JSONArray();
            
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es Macro-Strategist. Donne l'impact pour les actifs spécifiés à partir de l'alerte différée suivante. Conclus par [ACHAT CHOC], [VENTE CHOC] ou [NEUTRE]."));
            messages.put(new JSONObject().put("role", "user").put("content", "ALERTE RECUPERÉE : " + feed + "\nContext historique :\n" + history));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY);
            conn.setDoOutput(true);
            
            OutputStream os = conn.getOutputStream(); os.write(payload.toString().getBytes("UTF-8")); os.flush(); os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) response.append(line); br.close();

                JSONObject json = new JSONObject(response.toString());
                String aiAnalysis = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                String tgMsg = "⏳ *ALERTE RETROACTIVE | RECONNEXION REUSSIE*\n" +
                               "📡 Origine : " + source.toUpperCase() + "\n" +
                               "🕒 Émis le : " + timeString + "\n\n" + aiAnalysis;
                
                String urlTg = "https://api.telegram.org/bot" + MainActivity.TELEGRAM_TOKEN + "/sendMessage?chat_id=" + MainActivity.TELEGRAM_CHAT_ID + "&parse_mode=Markdown&text=" + URLEncoder.encode(tgMsg, "UTF-8");
                HttpURLConnection connTg = (HttpURLConnection) new URL(urlTg).openConnection();
                int tgCode = connTg.getResponseCode(); connTg.disconnect();

                if (tgCode == 200) {
                    eventDb.markEventAsSynced(fingerprint, isFomc ? "PIVOT CRITIQUE FOMC (OK)" : "CHANGEMENT DE DRIVER MACRO (OK)");
                    return true;
                }
            }
        } catch (Exception e) { Log.e(TAG, "Échec traitement différé", e); }
        return false;
    }

    private void startDailyBriefScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        nextRun.set(Calendar.HOUR_OF_DAY, 7); nextRun.set(Calendar.MINUTE, 0); nextRun.set(Calendar.SECOND, 0);
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) nextRun.add(Calendar.DAY_OF_YEAR, 1);
        scheduler.scheduleAtFixedRate(this::generateAndSendDailyBrief, nextRun.getTimeInMillis() - System.currentTimeMillis(), 24L * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void generateAndSendDailyBrief() {
        try {
            long now = System.currentTimeMillis() / 1000;
            String dailyDrivers = eventDb.getDailyMacroDrivers(now);
            if (dailyDrivers.isEmpty()) return;

            JSONObject payload = new JSONObject(); payload.put("model", GROQ_MODEL); payload.put("temperature", 0.1);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Fais un briefing matinal synthétique, clair et exploitable des chocs macro d'hier pour la session d'aujourd'hui."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES HIER :\n" + dailyDrivers));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream(); os.write(payload.toString().getBytes("UTF-8")); os.flush(); os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder(); String l;
                while ((l = br.readLine()) != null) r.append(l); br.close();
                String dailySummary = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🌅 *DAILY BRIEF MACRO — ANTANANARIVO*\n\n" + dailySummary);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur Daily Brief", e); }
    }

    private void startMonthlyReportScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        nextRun.set(Calendar.HOUR_OF_DAY, 23); nextRun.set(Calendar.MINUTE, 0); nextRun.set(Calendar.SECOND, 0);
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.MONTH, 1);
            nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        }
        scheduler.scheduleAtFixedRate(this::generateAndPurgeMonthlyReport, nextRun.getTimeInMillis() - System.currentTimeMillis(), 30L * 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void generateAndPurgeMonthlyReport() {
        try {
            long now = System.currentTimeMillis() / 1000;
            String monthlyRegistry = eventDb.getMonthlyMacroRegistry(now);
            if (monthlyRegistry.isEmpty()) return;

            JSONObject payload = new JSONObject(); payload.put("model", GROQ_MODEL); payload.put("temperature", 0.1);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Analyse le registre des chocs macro du mois écoulé pour dégager la structure fondamentale dominante pour le début du mois prochain. Conclure de façon macro-structurelle."));
            messages.put(new JSONObject().put("role", "user").put("content", "REGISTRE MENSUEL :\n" + monthlyRegistry));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream(); os.write(payload.toString().getBytes("UTF-8")); os.flush(); os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder(); String l;
                while ((l = br.readLine()) != null) r.append(l); br.close();
                String report = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                sendTelegramSecure("📊 *RAPPORT MACRO MENSUEL & TRANSITION J+30*\n\n" + report);
                eventDb.purgeOldEvents(now);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur rapport mensuel", e); }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) { triggerQueueSynchronization(); }
            });
        }
    }

    private boolean isDeviceOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork(); if (net == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(net);
            return cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        return false;
    }

    private long parseTimeFromText(String text, long defaultPostTime) {
        String lowerText = text.toLowerCase();
        Pattern minsPattern = Pattern.compile("(\\d+)\\s*min?(s|ute|utes)?\\s*(ago)?");
        Matcher minsMatcher = minsPattern.matcher(lowerText);
        if (minsMatcher.find()) {
            try { return System.currentTimeMillis() - ((long) Integer.parseInt(minsMatcher.group(1)) * 60 * 1000); } catch (Exception e) {}
        }
        return defaultPostTime; 
    }

    private List<String> filterActiveAssets(String text) {
        List<String> assets = new ArrayList<>();
        String upper = text.toUpperCase();
        if (upper.contains("GOLD") || upper.contains("XAU") || upper.contains("OR ")) assets.add("GOLD");
        if (upper.contains("OIL") || upper.contains("WTI") || upper.contains("CRUDE")) assets.add("USOIL");
        if (upper.contains("NASDAQ") || upper.contains("NAS100") || upper.contains("TECH")) assets.add("NASDAQ");
        if (upper.contains("SP500") || upper.contains("S&P")) assets.add("SP500");
        if (upper.contains("BITCOIN") || upper.contains("BTC")) assets.add("BITCOIN");
        if (upper.contains("YIELD") || upper.contains("US10Y") || upper.contains("BOND")) assets.add("US10Y");
        if (upper.contains("GBP") || upper.contains("CABLE")) assets.add("GBPUSD");
        if (upper.contains("EUROZONE") || upper.contains("EUR ") || upper.contains("ECB")) assets.add("EURUSD");
        if (assets.isEmpty()) assets.add("GLOBAL-MACRO");
        return assets;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Trading Core Alerts", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private String generateSecureHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) { return String.valueOf(System.currentTimeMillis()); }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        scheduler.shutdown();
    }
}
