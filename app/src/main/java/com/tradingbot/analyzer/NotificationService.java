package com.tradingbot.analyzer;

import java.util.Locale; 
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
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

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "trading_alerts";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    
    // API Macroéconomique Professionnelle (Remplacer par votre clé gratuite)
    private static final String MACRO_API_KEY = "VOTRE_CLE_ALPHA_VANTAGE_OU_FMP";
    private static final String ECONOMIC_CALENDAR_URL = "https://financialmodelingprep.com/api/v3/economic_calendar?from=%s&to=%s&apikey=" + MACRO_API_KEY;

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
            } catch (Exception e) { Log.e(TAG, "Échec envoi Telegram direct (Déconnecté)"); }
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

        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String unifiedFeed = (title + " " + text).trim();

        if (unifiedFeed.length() < 10) return;

        String packageName = sbn.getPackageName().toLowerCase();
        String sourceName = "Source Institutionnelle";
        if (packageName.contains("financialjuice")) sourceName = "FinancialJuice";
        else if (packageName.contains("investing")) sourceName = "Investing.com";
        else if (packageName.contains("twitter") || packageName.contains("periscope")) sourceName = "X / Twitter";
        else return;

        processIncomingEvent(sourceName, title, text, unifiedFeed, packageName, sbn.getPostTime());
    }

    private void processIncomingEvent(String source, String title, String text, String feed, String pkg, long postTime) {
        List<String> targetAssets = filterActiveAssets(feed);
        boolean isFomcPivot = feed.toUpperCase().contains("FOMC") || feed.toUpperCase().contains("FED ");
        int weight = assignDriverWeight(feed);

        // Si la donnée n'est pas une Hard Data ou un pivot de banque centrale, on l'évalue pour filtrer le bruit
        if (weight < 4 && !isFomcPivot && !detectDriverDeviation(feed)) {
            String hash = generateSecureHash(title + text);
            eventDb.saveEvent(hash, pkg, source, "Soft-Data", title, feed, String.join(", ", targetAssets), "Conforme (Filtré)", (int)(postTime/1000), "synced");
            return;
        }

        String initialImpact = isFomcPivot ? "💥 PIVOT CRITIQUE BANQUE CENTRALE" : "⚡ CHOC DRIVER MACRO PONDÉRÉ (Poids: " + weight + ")";
        String hash = generateSecureHash(title + text);

        boolean saved = eventDb.saveEvent(hash, pkg, source, "Macro-Choc", title, feed, String.join(", ", targetAssets), initialImpact, (int)(postTime/1000), "en_attente");

        if (saved && isDeviceOnline()) {
            triggerQueueSynchronization();
        }
    }

    private int assignDriverWeight(String text) {
        String u = text.toUpperCase();
        if (u.contains("CPI") || u.contains("INFLATION") || u.contains("NFP") || u.contains("NON-FARM PAYROLLS") || u.contains("FOMC") || u.contains("INTEREST RATE")) return 5; // Hard Data Fondator
        if (u.contains("GDP") || u.contains("PIB") || u.contains("RETAIL SALES") || u.contains("CHÔMAGE")) return 4; // Hard Data Secondaire
        if (u.contains("PMI") || u.contains("ISM") || u.contains("MICHIGAN") || u.contains("CONSUMER CONFIDENCE")) return 3; // Soft Data Conjoncturelle
        return 1; // Rumeur ou bruit de flux
    }

    private boolean detectDriverDeviation(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("HIGHER THAN EXPECTED") || upper.contains("LOWER THAN EXPECTED") ||
            upper.contains("ABOVE FORECAST") || upper.contains("BELOW FORECAST") ||
            upper.contains("SURPRISE") || upper.contains("SHOCK")) return true;

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

    private synchronized void triggerQueueSynchronization() {
        if (isSyncing || !isDeviceOnline()) return;
        isSyncing = true;

        exec.submit(() -> {
            try {
                long now = System.currentTimeMillis() / 1000;
                
                // 📊 ÉTAPE 1 : Récupération rétroactive via API pour combler les coupures prolongées (J+7)
                fetchMissingDataFromInstitutionalAPI();

                // 🧠 ÉTAPE 2 : Alignement et traitement séquentiel des éléments en attente
                Cursor cursor = eventDb.getUnsyncedEvents(now);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        String fingerprint = cursor.getString(cursor.getColumnIndexOrThrow("fingerprint"));
                        String source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                        String feed = cursor.getString(cursor.getColumnIndexOrThrow("feed_content"));
                        String assetsStr = cursor.getString(cursor.getColumnIndexOrThrow("target_assets"));
                        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("unix_timestamp")) * 1000;
                        
                        List<String> assets = Arrays.asList(assetsStr.split(", "));
                        String historyContext = eventDb.getRecentEventsForAssets(assets, 5);

                        boolean success = executeAnalysisPipeline(source, feed, historyContext, assets, timestamp, fingerprint);
                        if (!success) break;

                    } while (cursor.moveToNext());
                    cursor.close();
                }
            } catch (Exception e) { Log.e(TAG, "Erreur lors de l'alignement réseau", e); }
            isSyncing = false;
        });
    }

    /**
     * 🌐 RECUPERATION HISTORIQUE SANS SCRAPING : Interroge l'API Institutionnelle sur les 7 derniers jours
     */
    private void fetchMissingDataFromInstitutionalAPI() {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
            String todayStr = dateFormat.format(cal.getTime());
            
            cal.add(Calendar.DAY_OF_YEAR, -7); // Retour en arrière de 7 jours complets
            String sevenDaysAgoStr = dateFormat.format(cal.getTime());

            URL url = new URL(String.format(ECONOMIC_CALENDAR_URL, sevenDaysAgoStr, todayStr));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);

            if (conn.getResponseCode() == 200) {
                BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder(); String line;
                while ((line = rd.readLine()) != null) response.append(line);
                rd.close();

                JSONArray calendarEvents = new JSONArray(response.toString());
                StringBuilder apiMacroBlock = new StringBuilder();

                for (int i = 0; i < calendarEvents.length(); i++) {
                    JSONObject event = calendarEvents.getJSONObject(i);
                    String impact = event.optString("impact", "LOW");
                    
                    // On ne traite que les événements à fort impact macroéconomique
                    if (impact.equalsIgnoreCase("HIGH")) {
                        String date = event.optString("date", "");
                        String eventName = event.optString("event", "");
                        double actual = event.optDouble("actual", 0.0);
                        double estimate = event.optDouble("estimate", 0.0);

                        if (actual != estimate) { // Il y a un écart fondamental (Surprise)
                            apiMacroBlock.append(String.format("- [%s] %s | Réel: %s vs Attendu: %s\n", date, eventName, actual, estimate));
                        }
                    }
                }

                if (apiMacroBlock.length() > 0) {
                    dispatchWeeklyBulkToGroq(apiMacroBlock.toString());
                }
            }
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "Échec de synchronisation de l'API historique", e); }
    }

    private void dispatchWeeklyBulkToGroq(String bulkData) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es Macro-Strategist principal. Analyse ce relevé de données économiques issues de notre API de secours suite à une déconnexion prolongée. Identifie le nouveau vecteur de momentum et conclus explicitement pour GOLD et US10Y."));
            messages.put(new JSONObject().put("role", "user").put("content", "ÉVÉNEMENTS EXSTRAITS DE L'API :\n" + bulkData));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY);
            conn.setDoOutput(true);
            
            OutputStream os = conn.getOutputStream(); os.write(payload.toString().getBytes("UTF-8")); os.flush(); os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder(); String l;
                while ((l = br.readLine()) != null) r.append(l); br.close();

                String analysis = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🚨 *RAPPORT DE RATTRAPAGE MACROÉCONOMIQUE API (J+7)*\n\n" + analysis);
                
                eventDb.saveEvent(generateSecureHash(analysis), "com.tradingbot.sync", "API Sync Engine", "Weekly-Sync", "Audit Reconnexion", analysis, "GOLD, US10Y", "VECTEUR SÉCURISÉ", (int)(System.currentTimeMillis()/1000), "synced");
            }
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "Échec de l'audit de masse Groq", e); }
    }

    private boolean executeAnalysisPipeline(String source, String feed, String history, List<String> assets, long ts, String fingerprint) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
            String timeString = sdf.format(new Date(ts)) + " (Mada)";

            JSONObject payload = new JSONObject(); payload.put("model", GROQ_MODEL); payload.put("temperature", 0.02);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es un modèle quantitatif de trading macro. Évalue la déviation fondamentale en associant le flux actuel à l'historique fourni. Conclus pour chaque actif par [ACHAT CHOC], [VENTE CHOC] ou [NEUTRE]. Pas de phrases inutiles."));
            messages.put(new JSONObject().put("role", "user").put("content", "Flux: " + feed + "\nMémoire :\n" + history));
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

                String aiResult = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("⚡ *ANALYSE DE FLUX DE DRIVER*\n🕒 " + timeString + "\n📡 Source : " + source + "\n\n" + aiResult);
                
                eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
                return true;
            }
        } catch (Exception e) { Log.e(TAG, "Échec pipeline unitaire", e); }
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
            messages.put(new JSONObject().put("role", "system").put("content", "Rédige une note stratégique matinale concise basée sur les chocs macroéconomiques majeurs de la veille enregistrés dans notre système."));
            messages.put(new JSONObject().put("role", "user").put("content", "CHOCS ENREGISTRÉS :\n" + dailyDrivers));
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
                String summary = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🌅 *DAILY BRIEF MATINAL STRATÉGIQUE*\n\n" + summary);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur du briefing quotidien", e); }
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
            messages.put(new JSONObject().put("role", "system").put("content", "Analyse le registre mensuel des ruptures fondamentales pour extraire la structure globale dominante pour le mois qui commence."));
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

                sendTelegramSecure("📊 *RAPPORT DE TRANSITION MACROÉCONOMIQUE MENSUEL*\n\n" + report);
                eventDb.purgeOldEvents(now);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur du rapport mensuel", e); }
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
