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
import java.util.regex.*;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "trading_alerts";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;
    private volatile boolean isSyncing = false;

    public static void sendTelegramSecure(String message, Context context) {
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
                String token = prefs.getString("tg_token", "");
                String chatId = prefs.getString("tg_chat_id", "");
                
                if (token.isEmpty() || chatId.isEmpty()) return;
                
                URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);

                JSONObject payload = new JSONObject();
                payload.put("chat_id", chatId);
                payload.put("text", message);
                payload.put("parse_mode", "Markdown");

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { 
                Log.e(TAG, "Échec Telegram POST", e); 
            }
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

        processIncomingMacroFeed(sourceName, title, text, unifiedFeed, packageName, sbn.getPostTime());
    }

    private void processIncomingMacroFeed(String source, String title, String text, String feed, String pkg, long postTime) {
        List<String> targetAssets = filterActiveAssets(feed);
        boolean isFomcPivot = feed.toUpperCase().contains("FOMC") || feed.toUpperCase().contains("FED ");
        int weight = assignDriverWeight(feed);

        if (weight < 4 && !isFomcPivot && !detectDriverDeviation(feed)) {
            String hash = generateSecureHash(title + text);
            eventDb.saveEvent(hash, pkg, source, "Soft-Data", title, feed, String.join(", ", targetAssets), "Conforme (Filtré)", (int)(postTime/1000), "synced");
            return;
        }

        String initialImpact = isFomcPivot ? "💥 PIVOT MAJEUR BANQUE CENTRALE" : "⚡ CHOC DRIVER MACRO PONDÉRÉ (Poids: " + weight + ")";
        String hash = generateSecureHash(title + text);

        boolean saved = eventDb.saveEvent(hash, pkg, source, "Macro-Choc", title, feed, String.join(", ", targetAssets), initialImpact, (int)(postTime/1000), "en_attente");

        if (saved && isDeviceOnline()) {
            triggerQueueSynchronization();
        }
    }

    private int assignDriverWeight(String text) {
        String u = text.toUpperCase();
        if (u.contains("CPI") || u.contains("INFLATION") || u.contains("NFP") || u.contains("NON-FARM PAYROLLS") || u.contains("FOMC") || u.contains("INTEREST RATE") || u.contains("RBA") || u.contains("BOC") || u.contains("BOJ")) return 5;
        if (u.contains("GDP") || u.contains("PIB") || u.contains("RETAIL SALES") || u.contains("EMPLOYMENT RATE") || u.contains("STOCKS")) return 4;
        if (u.contains("PMI") || u.contains("ISM") || u.contains("MICHIGAN")) return 3;
        return 1;
    }

    private boolean detectDriverDeviation(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("HIGHER THAN EXPECTED") || upper.contains("LOWER THAN EXPECTED") ||
            upper.contains("ABOVE FORECAST") || upper.contains("BELOW FORECAST") ||
            upper.contains("SURPRISE") || upper.contains("SHOCK") || upper.contains("MISSES")) return true;

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
            Cursor cursor = null;
            try {
                long now = System.currentTimeMillis() / 1000;
                fetchMissingDataFromInstitutionalAPI();

                cursor = eventDb.getUnsyncedEvents(now);
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
                        if (!success) {
                            Log.w(TAG, "Échec de traitement du nœud : " + fingerprint);
                        }

                    } while (cursor.moveToNext());
                }
            } catch (Exception e) { 
                Log.e(TAG, "Erreur synchronisation réseau", e); 
            } finally {
                if (cursor != null) cursor.close();
                isSyncing = false;
            }
        });
    }

    private void fetchMissingDataFromInstitutionalAPI() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("TradingBot", MODE_PRIVATE);
            String macroApiKey = prefs.getString("macro_api_key", "");
            
            if (macroApiKey.isEmpty()) return;

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
            String todayStr = dateFormat.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, -2);
            String twoDaysAgoStr = dateFormat.format(cal.getTime());

            String urlString = String.format("https://financialmodelingprep.com/api/v3/economic_calendar?from=%s&to=%s&apikey=%s", twoDaysAgoStr, todayStr, macroApiKey);
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder(); 
                String line;
                while ((line = rd.readLine()) != null) response.append(line); 
                rd.close();

                JSONArray calendarEvents = new JSONArray(response.toString());
                StringBuilder apiMacroBlock = new StringBuilder();

                for (int i = 0; i < calendarEvents.length(); i++) {
                    JSONObject event = calendarEvents.getJSONObject(i);
                    String impact = event.optString("impact", "LOW");
                    String currency = event.optString("currency", "USD");
                    
                    if (impact.equalsIgnoreCase("HIGH") && 
                       (currency.equals("USD") || currency.equals("AUD") || currency.equals("CAD") || 
                        currency.equals("JPY") || currency.equals("EUR") || currency.equals("GBP"))) {
                        
                        String date = event.optString("date", "");
                        String eventName = event.optString("event", "");
                        double actual = event.optDouble("actual", 0.0);
                        double estimate = event.optDouble("estimate", 0.0);

                        if (actual != estimate) {
                            apiMacroBlock.append(String.format("- [%s] (%s) %s | Actuel: %s vs Attendu: %s\n", date, currency, eventName, actual, estimate));
                        }
                    }
                }

                if (apiMacroBlock.length() > 0) {
                    dispatchWeeklyBulkToGroq(apiMacroBlock.toString());
                }
            }
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "Échec de récupération API historique", e); }
    }

    private void dispatchWeeklyBulkToGroq(String bulkData) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("TradingBot", MODE_PRIVATE);
            String apiKey = prefs.getString("claude_key", "");
            if (apiKey.isEmpty()) return;

            JSONObject payload = new JSONObject(); 
            payload.put("model", GROQ_MODEL); 
            payload.put("temperature", 0.1);
            
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es un Macro-Strategist de premier plan. Analyse ce relevé complet de données à fort impact survenu pendant notre coupure réseau. Identifie les déviations majeures et dresse la matrice de momentum pour GOLD, NASDAQ, USOIL, US10Y, EURUSD, GBPUSD, BITCOIN, AUDUSD, USDCAD et USDJPY."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES EXTRAITES :\n" + bulkData));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); 
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            
            OutputStream os = conn.getOutputStream(); 
            os.write(payload.toString().getBytes("UTF-8")); 
            os.flush(); 
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder(); 
                String l;
                while ((l = br.readLine()) != null) r.append(l); 
                br.close();

                String analysis = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🚨 *RAPPORT CRITIQUE DE RATTRAPAGE INTER-MARCHÉS (J+7)*\n\n" + analysis, this);
                
                eventDb.saveEvent(generateSecureHash(analysis), "com.tradingbot.sync", "API Sync", "Weekly-Sync", "Audit Global", analysis, "ALL_ASSETS", "ALIGNE_OK", (int)(System.currentTimeMillis()/1000), "synced");
            }
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "Échec dispatch historique Groq", e); }
    }

    private boolean executeAnalysisPipeline(String source, String feed, String history, List<String> assets, long ts, String fingerprint) {
        int maxRetries = 3;
        int attempt = 0;
        
        while (attempt < maxRetries) {
            try {
                android.content.SharedPreferences prefs = getSharedPreferences("TradingBot", MODE_PRIVATE);
                String apiKey = prefs.getString("claude_key", "");
                String tgToken = prefs.getString("tg_token", "");
                String tgChatId = prefs.getString("tg_chat_id", "");

                if (apiKey.isEmpty() || tgToken.isEmpty() || tgChatId.isEmpty()) {
                    Log.e(TAG, "Échec pipeline : Configurations manquantes.");
                    return false;
                }

                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
                sdf.setTimeZone(TimeZone.getTimeZone("GMT+3"));
                String timeString = sdf.format(new Date(ts));

                URL url = new URL(GROQ_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("model", GROQ_MODEL);
                payload.put("temperature", 0.02);

                // --- OPTIMISATION DU SYSTEM PROMPT POUR INCLURE LES CAUSES/RAISONS ---
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", 
                    "Tu es un terminal de trading macro-quantitatif ultra-précis.\n" +
                    "Analyse le flux d'actualité fourni. Extrais d'abord le fait marquant précis (la cause).\n" +
                    "Ensuite, pour chaque actif impacté, donne l'action ET la raison logique concise (max 7 mots par actif).\n\n" +
                    "RÈGLES DE DIRECTIONNALITÉ :\n" +
                    "- USD FORT (Hawkish / Chiffres US robustes) = GOLD, EURUSD, GBPUSD, AUDUSD, NASDAQ, SP500, BITCOIN [VENTE] | US10Y, USDCAD, USDJPY [ACHAT]\n" +
                    "- USD FAIBLE (Dovish / Chiffres US mauvais) = Inverse\n\n" +
                    "FORMAT REQUIS STRICT (respecte scrupuleusement les balises et sauts de ligne) :\n" +
                    "🚨 [NOM DE L'EMETTEUR OU DRIVER]\n" +
                    "📊 CONVICTION : [█████] XX%\n" +
                    "🎯 VECTEUR : [HAWKISH/DOVISH/GÉO/LIQUIDITÉ]\n" +
                    "📢 FAIT MARQUANT : [Traduis et résume brièvement en français ce qui vient de se passer ou ce qui a été dit]\n\n" +
                    "--- IMPACTS ACQUISITION ---\n" +
                    "• [NOM_ACTIF] : [ACHAT CHOC ou VENTE CHOC ou NEUTRE] | [Raison courte en français]\n\n" +
                    "🏁 FLUX : [HAUSSIER/BAISSIER/STABLE]"
                ));

                messages.put(new JSONObject().put("role", "user").put("content", "Flux brut reçu : " + feed + "\nMémoire contextuelle :\n" + history));
                payload.put("messages", messages);

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush(); 
                os.close();

                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder r = new StringBuilder(); 
                    String l;
                    while ((l = br.readLine()) != null) r.append(l); 
                    br.close();

                    String aiResult = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                    if (aiResult.isEmpty() || aiResult.length() < 50) {
                        throw new Exception("Invalid API response");
                    }

                    // Filtrage des lignes pour nettoyer l'affichage Telegram
                    StringBuilder filteredMessage = new StringBuilder();
                    String[] lines = aiResult.split("\n");
                    int activeSignalsCount = 0;
                    int neutralCount = 0;

                    for (String line : lines) {
                        if (line.contains("•") && line.contains("NEUTRE")) {
                            neutralCount++;
                            continue; 
                        }
                        
                        if (line.contains("ACHAT CHOC") || line.contains("VENTE CHOC")) {
                            filteredMessage.append(line).append("\n");
                            activeSignalsCount++;
                        } else if (!line.contains("•")) {
                            filteredMessage.append(line).append("\n");
                        }
                    }

                    if (neutralCount > 8) {
                        eventDb.markEventAsSynced(fingerprint, "FILTERED_NEUTRAL");
                        return true;
                    }

                    if (activeSignalsCount > 0) {
                        String finalPayload = "⚡ *ANALYSE DRIVER MACRO EXPLICATIVE*\n"
                                + "🕒 " + timeString + " (Mada)\n" 
                                + "📡 Source : " + source + "\n" 
                                + filteredMessage.toString().trim();
                                
                        sendTelegramSecure(finalPayload, this);
                    }

                    eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
                    return true;
                    
                } else {
                    throw new Exception("API Error: " + conn.getResponseCode());
                }
                
            } catch (Exception e) { 
                attempt++;
                Log.e(TAG, "Tentative " + attempt + "/" + maxRetries + " échouée", e);
                if (attempt < maxRetries) {
                    try { Thread.sleep(2000 * attempt); } catch (InterruptedException ie) { return false; }
                }
            }
        }
        return false;
    }

    private List<String> filterActiveAssets(String text) {
        List<String> assets = new ArrayList<>();
        String upper = text.toUpperCase();

        if (upper.contains("GOLD") || upper.contains("XAU") || upper.contains("OR ") || upper.contains("SILVER")) assets.add("GOLD");
        if (upper.contains("OIL") || upper.contains("WTI") || upper.contains("CRUDE") || upper.contains("BRENT")) assets.add("USOIL");
        if (upper.contains("NASDAQ") || upper.contains("NAS100") || upper.contains("TECH") || upper.contains("OPENAI") || upper.contains("NVIDIA") || upper.contains("APPLE")) assets.add("NASDAQ");
        if (upper.contains("SP500") || upper.contains("S&P") || upper.contains("SPX")) assets.add("SP500");
        if (upper.contains("BITCOIN") || upper.contains("BTC") || upper.contains("CRYPTO")) assets.add("BITCOIN");
        if (upper.contains("YIELD") || upper.contains("US10Y") || upper.contains("BOND") || upper.contains("TREASURY")) assets.add("US10Y");
        if (upper.contains("EUR ") || upper.contains("EURUSD") || upper.contains("ECB") || upper.contains("EUROZONE")) assets.add("EURUSD");
        if (upper.contains("GBP") || upper.contains("GBPUSD") || upper.contains("CABLE") || open.contains("BOE")) assets.add("GBPUSD");
        if (upper.contains("AUD") || upper.contains("AUDUSD") || upper.contains("AUSSIE") || upper.contains("RBA")) assets.add("AUDUSD");
        if (upper.contains("CAD") || upper.contains("USDCAD") || upper.contains("LOONIE") || upper.contains("BOC")) assets.add("USDCAD");
        if (upper.contains("JPY") || upper.contains("USDJPY") || upper.contains("YEN") || upper.contains("BOJ")) assets.add("USDJPY");

        if (assets.isEmpty()) {
            assets.addAll(Arrays.asList("GOLD", "NASDAQ", "USOIL", "EURUSD", "AUDUSD", "USDCAD", "USDJPY"));
        }
        return assets;
    }

    private void startDailyBriefScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        nextRun.set(Calendar.HOUR_OF_DAY, 7); 
        nextRun.set(Calendar.MINUTE, 0); 
        nextRun.set(Calendar.SECOND, 0);
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) nextRun.add(Calendar.DAY_OF_YEAR, 1);
        scheduler.scheduleAtFixedRate(this::generateAndSendDailyBrief, nextRun.getTimeInMillis() - System.currentTimeMillis(), 24L * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void generateAndSendDailyBrief() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("TradingBot", MODE_PRIVATE);
            String apiKey = prefs.getString("claude_key", "");
            if (apiKey.isEmpty()) return;

            long now = System.currentTimeMillis() / 1000;
            String dailyDrivers = eventDb.getDailyMacroDrivers(now);
            if (dailyDrivers.isEmpty()) return;

            JSONObject payload = new JSONObject(); 
            payload.put("model", GROQ_MODEL); 
            payload.put("temperature", 0.1);
            
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Rédige un briefing matinal synthétique des chocs macroéconomiques enregistrés la veille."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES HIER :\n" + dailyDrivers));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); 
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            
            OutputStream os = conn.getOutputStream(); 
            os.write(payload.toString().getBytes("UTF-8")); 
            os.flush(); 
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder(); 
                String l;
                while ((l = br.readLine()) != null) r.append(l); 
                br.close();
                
                String summary = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🌅 *DAILY BRIEF STRATÉGIQUE*\n\n" + summary, this);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur Daily Brief", e); }
    }

    private void startMonthlyReportScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        nextRun.set(Calendar.HOUR_OF_DAY, 23); 
        nextRun.set(Calendar.MINUTE, 0); 
        nextRun.set(Calendar.SECOND, 0);
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.MONTH, 1);
            nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        }
        scheduler.scheduleAtFixedRate(this::generateAndPurgeMonthlyReport, nextRun.getTimeInMillis() - System.currentTimeMillis(), 30L * 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void generateAndPurgeMonthlyReport() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("TradingBot", MODE_PRIVATE);
            String apiKey = prefs.getString("claude_key", "");
            if (apiKey.isEmpty()) return;

            long now = System.currentTimeMillis() / 1000;
            String monthlyRegistry = eventDb.getMonthlyMacroRegistry(now);
            if (monthlyRegistry.isEmpty()) return;

            JSONObject payload = new JSONObject(); 
            payload.put("model", GROQ_MODEL); 
            payload.put("temperature", 0.1);
            
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Analyse le registre mensuel des ruptures fondamentales pour extraire la structure globale de transition pour le début du mois suivant."));
            messages.put(new JSONObject().put("role", "user").put("content", "REGISTRE MENSUEL :\n" + monthlyRegistry));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); 
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            
            OutputStream os = conn.getOutputStream(); 
            os.write(payload.toString().getBytes("UTF-8")); 
            os.flush(); 
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder(); 
                String l;
                while ((l = br.readLine()) != null) r.append(l); 
                br.close();
                
                String report = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                sendTelegramSecure("📊 *RAPPORT DE TRANSITION MACROÉCONOMIQUE MENSUEL*\n\n" + report, this);
                eventDb.purgeOldEvents(now);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur Rapport Mensuel", e); }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) { 
                    triggerQueueSynchronization(); 
                }
            });
        }
    }

    private boolean isDeviceOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork(); 
            if (net == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(net);
            return cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        return false;
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
        } catch (Exception e) { 
            return String.valueOf(System.currentTimeMillis()); 
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Trading Core Alerts", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        scheduler.shutdownNow();
        exec.shutdownNow();
    }
}
