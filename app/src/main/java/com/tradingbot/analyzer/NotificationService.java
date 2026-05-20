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
    
    // API Rest Macroéconomique de Secours (FMP ou Alpha Vantage)
    private static final String MACRO_API_KEY = "ykVnU2LFYM8nT6qj6aJlZZSWaMsciVJj";
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

        // Élimination du bruit pour les actualités mineures sans écart
        if (weight < 4 && !isFomcPivot && !detectDriverDeviation(feed)) {
            String hash = generateSecureHash(title + text);
            eventDb.saveEvent(hash, pkg, source, "Soft-Data", title, feed, String.join(", ", targetAssets), "Conforme (Filtré)", (int)(postTime/1000), "synced");
            return;
        }

        String initialImpact = isFomcPivot ? "💥 PIVOT MAJEUR BANQUE CENTRALE" : "⚡ CHOC DRIVER MACRO PONDÉRÉ (Poids: " + weight + ")";
        String hash = generateSecureHash(title + text);

        // Stockage d'urgence immédiat en mode 'en_attente'
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
            try {
                long now = System.currentTimeMillis() / 1000;
                
                // 1. Récupération historique API si deconnexion prolongée
                fetchMissingDataFromInstitutionalAPI();

                // 2. Vidage de la file d'attente locale accumulée
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
            } catch (Exception e) { Log.e(TAG, "Erreur synchronisation réseau", e); }
            isSyncing = false;
        });
    }

    private void fetchMissingDataFromInstitutionalAPI() {
        try {
            if (MACRO_API_KEY.equals("VOTRE_CLE_API_MACRO_GRATUITE")) return;

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
            String todayStr = dateFormat.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, -7);
            String sevenDaysAgoStr = dateFormat.format(cal.getTime());

            URL url = new URL(String.format(ECONOMIC_CALENDAR_URL, sevenDaysAgoStr, todayStr));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);

            if (conn.getResponseCode() == 200) {
                BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder(); String line;
                while ((line = rd.readLine()) != null) response.append(line); rd.close();

                JSONArray calendarEvents = new JSONArray(response.toString());
                StringBuilder apiMacroBlock = new StringBuilder();

                for (int i = 0; i < calendarEvents.length(); i++) {
                    JSONObject event = calendarEvents.getJSONObject(i);
                    String impact = event.optString("impact", "LOW");
                    String currency = event.optString("currency", "USD");
                    
                    // On filtre pour cibler la macro globale et nos actifs spécifiques (USD, AUD, CAD, JPY, EUR, GBP)
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
            JSONObject payload = new JSONObject(); payload.put("model", GROQ_MODEL); payload.put("temperature", 0.1);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es un Macro-Strategist de premier plan. Analyse ce relevé complet de données à fort impact survenu pendant notre coupure réseau. Identifie les déviations majeures et dresse la matrice de momentum pour GOLD, NASDAQ, USOIL, US10Y, EURUSD, GBPUSD, BITCOIN, AUDUSD, USDCAD et USDJPY."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES EXTRAITES :\n" + bulkData));
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

                String analysis = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🚨 *RAPPORT CRITIQUE DE RATTRAPAGE INTER-MARCHÉS (J+7)*\n\n" + analysis);
                
                eventDb.saveEvent(generateSecureHash(analysis), "com.tradingbot.sync", "API Sync", "Weekly-Sync", "Audit Global", analysis, "ALL_ASSETS", "ALIGNE_OK", (int)(System.currentTimeMillis()/1000), "synced");
            }
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "Échec dispatch historique Groq", e); }
    }

    private boolean executeAnalysisPipeline(String source, String feed, String history, List<String> assets, long ts, String fingerprint) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
            String timeString = sdf.format(new Date(ts)) + " (Mada)";

            JSONObject payload = new JSONObject(); payload.put("model", GROQ_MODEL); payload.put("temperature", 0.02);
            JSONArray messages = new JSONArray();
            // 🔥 PROMPT SYSTÈME ULTIME INTER-MARCHÉS BLINDÉ POUR VOS 11 ACTIFS
            messages.put(new JSONObject().put("role", "system").put("content", 
                   "Tu es un algorithme de trading macro-quantitatif institutionnel. " +
                   "Ton rôle est d'analyser l'impact du flux d'actualités en direct par rapport au contexte historique.\n\n" +
    
                   "CHARTE DE CORRÉLATION MACRO STRICTE :\n" +
                   "- Un biais HAWKISH (Fed agressive, inflation forte, taux élevés) = USD fort, US10Y en [ACHAT CHOC] | GOLD, NASDAQ, SP500, BITCOIN en [VENTE CHOC].\n" +
                   "- Un biais DOVISH (Fed souple, baisse des taux, injection de liquidités) = USD faible, US10Y en [VENTE CHOC] | GOLD, NASDAQ, SP500, BITCOIN en [ACHAT CHOC].\n" +
                   "- Actif Énergie (USOIL) = Hausse si tensions géopolitiques concrètes au Moyen-Orient ou baisse des stocks. Baisse si résolution diplomatique.\n\n" +
    
                   "GUIDE DES PAIRES FOREX SPÉCIFIQUES (TRÈS STRICT) :\n" +
                   "1. AUDUSD : Si l'USD est fort (Hawkish), le graphique BAISSE [VENTE CHOC]. Si l'USD est faible, il MONTE.\n" +
                   "2. EURUSD & GBPUSD : Si l'USD est fort (Hawkish), les graphiques BAISSENT [VENTE CHOC].\n" +
                   "3. USDCAD : Si l'USD est fort (Hawkish), le graphique MONTE [ACHAT CHOC] (Le Dollar US écrase le Dollar Canadien).\n" +
                   "4. USDJPY : Si les taux US montent (US10Y en ACHAT), le graphique MONTE en flèche [ACHAT CHOC] (Le Dollar US écrase le Yen Japonais).\n\n"
                                                                    
                   "CONSIGNES DE SÉCURITÉ :\n" +
                   "- N'invente aucune donnée historique. Ignore les figures politiques obsolètes du passé.\n" +
                   "- Ne réponds JAMAIS par des termes génériques comme 'Marchés boursiers' ou 'Actions'.\n\n" +
    
                   "FORMAT DE RÉPONSE IMPÉRATIF (Génère STRICTEMENT cette liste pour les actifs cibles, aucun autre texte) :\n" +
                   "• GOLD : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• NASDAQ : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• SP500 : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• USOIL : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• US10Y : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• BITCOIN : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• EURUSD : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• GBPUSD : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• AUDUSD : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• USDCAD : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n" +
                   "• USDJPY : [ACHAT CHOC / VENTE CHOC / NEUTRE] - Justification très courte.\n\n" +
                   "CONCLUSION : 'VECTEUR DE MOMENTUM DÉFINITIF : [HAUSSIER / BAISSIER / NEUTRE]'"
            ));
            messages.put(new JSONObject().put("role", "user").put("content", "Flux : " + feed + "\nMémoire :\n" + history));
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
                sendTelegramSecure("⚡ *ANALYSE DE DRIVER MACRO PONDÉRÉ*\n🕒 " + timeString + "\n📡 Source : " + source + "\n📋 Actifs : " + String.join(", ", assets) + "\n\n" + aiResult);
                
                eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
                return true;
            }
        } catch (Exception e) { Log.e(TAG, "Échec pipeline unitaire", e); }
        return false;
    }

    /**
     * 📋 CARTOGRAPHIE EXACTE ET INTELLIGENTE DES ACTIFS DU PORTEFOUILLE
     */
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
        if (upper.contains("GBP") || upper.contains("GBPUSD") || upper.contains("CABLE") || upper.contains("BOE")) assets.add("GBPUSD");
        
        // 🌟 Nouveaux Actifs Forex ajoutés :
        if (upper.contains("AUD") || upper.contains("AUDUSD") || upper.contains("AUSSIE") || upper.contains("RBA")) assets.add("AUDUSD");
        if (upper.contains("CAD") || upper.contains("USDCAD") || upper.contains("LOONIE") || upper.contains("BOC")) assets.add("USDCAD");
        if (upper.contains("JPY") || upper.contains("USDJPY") || upper.contains("YEN") || upper.contains("BOJ")) assets.add("USDJPY");

        // Sécurité systémique si aucun actif n'est directement nommé dans la news macro
        if (assets.isEmpty()) {
            assets.addAll(Arrays.asList("GOLD", "NASDAQ", "USOIL", "EURUSD", "AUDUSD", "USDCAD", "USDJPY"));
        }
        return assets;
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
            messages.put(new JSONObject().put("role", "system").put("content", "Rédige un briefing matinal synthétique des chocs macroéconomiques enregistrés la veille."));
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
                String summary = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🌅 *DAILY BRIEF STRATÉGIQUE AMÉLIORÉ*\n\n" + summary);
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
            messages.put(new JSONObject().put("role", "system").put("content", "Analyse le registre mensuel des ruptures fondamentales pour extraire la structure globale de transition pour le début du mois suivant."));
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
        } catch (Exception e) { Log.e(TAG, "Erreur Rapport Mensuel", e); }
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
        scheduler.shutdown();
    }
}
