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
            if (MACRO_API_KEY.equals("ykVnU2LFYM8nT6qj6aJlZZSWaMsciVJj")) return;

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
        // 🔑 Récupération de la clé API et des configurations Telegram depuis les SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("TradingBot", MODE_PRIVATE);
        String apiKey = prefs.getString("claude_key", "");
        String tgToken = prefs.getString("tg_token", "");
        String tgChatId = prefs.getString("tg_chat_id", "");
        
        String GROQ_MODEL = "llama3-70b-8192"; 

        // Sécurité : Si les configurations sont absentes, on avorte proprement
        if (apiKey.isEmpty() || tgToken.isEmpty() || tgChatId.isEmpty()) {
            Log.e(TAG, "Échec pipeline : Configurations ou clés API manquantes dans l'application.");
            return false;
        }

        // 1. Préparation de l'horodatage pour Madagascar (UTC+3)
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT+3"));
        String timeString = sdf.format(new java.util.Date(ts));

        // 2. Configuration de la connexion HTTP vers Groq API
        java.net.URL url = new java.net.URL("https://api.groq.com/openai/v1/chat/completions");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        // 3. Construction du Payload JSON pour l'analyse macroéconomique
        JSONObject payload = new JSONObject();
        payload.put("model", GROQ_MODEL);
        payload.put("temperature", 0.02); // Stabilité algorithmique maximale

        JSONArray messages = new JSONArray();

        // 🔥 PROMPT SYSTÈME INSTITUTIONNEL ET DIRECTIVES FOREX ACCRUES
        messages.put(new JSONObject().put("role", "system").put("content", 
            "Tu es un terminal de trading quantitatif et macroéconomique haute fréquence.\n" +
            "Tu dois synthétiser le flux entrant de manière ultra-concise, froide et mathématique.\n\n" +
            
            "CHARTE DE TRANSMISSION STRICTE :\n" +
            "- Mode HAWKISH / Inflation / Taux Forts = USD fort, US10Y [ACHAT] | GOLD, NASDAQ, SP500, BITCOIN [VENTE].\n" +
            "- Mode DOVISH / Récession / Injections = USD faible, US10Y [VENTE] | GOLD, NASDAQ, SP500, BITCOIN [ACHAT].\n\n" +
            
            "RÈGLES FOREX IMPÉRATIVES (Sens du graphique) :\n" +
            "- USD fort = AUDUSD, EURUSD, GBPUSD en [VENTE CHOC]\n" +
            "- USD fort = USDCAD, USDJPY en [ACHAT CHOC] (L'USD est la devise de base, le graphique monte)\n\n" +
            
            "FORMAT DE RÉPONSE ATTENDU (Strict, direct, aucune phrase d'introduction, aucun blabla) :\n\n" +
            "🚨 [CHOC MACRO : INSÉRER NOM DU DRIVER EX: CPI / FOMC / MINUTES]\n" +
            "📊 CONVICTION ALGORITHMIQUE : [█████] 100% (adapter le pourcentage et la jauge selon l'importance de la news)\n" +
            "🎯 VECTEUR CENTRAL : [HAWKISH / DOVISH / GÉOPOLITIQUE]\n\n" +
            "--- IMPACTS ACTIFS INTER-MARCHÉS ---\n" +
            "• GOLD   : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• NASDAQ : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• SP500  : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• USOIL  : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• US10Y  : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• BITCOIN: [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• EURUSD : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• GBPUSD : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• AUDUSD : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• USDCAD : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n" +
            "• USDJPY : [VENTE CHOC / ACHAT CHOC / NEUTRE] | Justification technique en 5 mots.\n\n" +
            "🏁 FLUX DIRECTIONNEL GLOBAL : [HAUSSIER / BAISSIER / STABLE]"
        ));

        // Injection des données de flux reçues par notification et de l'historique de la base locale
        messages.put(new JSONObject().put("role", "user").put("content", "Flux : " + feed + "\nMémoire :\n" + history));
        payload.put("messages", messages);

        // 4. Envoi effectif de la requête réseau
        java.io.OutputStream os = conn.getOutputStream();
        os.write(payload.toString().getBytes("UTF-8"));
        os.flush(); os.close();

        // 5. Lecture et traitement sélectif de la réponse
        if (conn.getResponseCode() == 200) {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder r = new StringBuilder(); String l;
            while ((l = br.readLine()) != null) r.append(l); br.close();

            String aiResult = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

            // ✂️ FILTRE ANTI-NEUTRE INTÉGRÉ
            StringBuilder filteredMessage = new StringBuilder();
            String[] lines = aiResult.split("\n");
            int activeSignalsCount = 0;

            for (String line : lines) {
                // Si la ligne traite d'un actif mais qu'il est qualifié de neutre, on le supprime du rapport Telegram
                if (line.contains("•") && line.contains("[NEUTRE]")) {
                    continue; 
                }
                
                // On stocke et valide uniquement les mouvements de forte intensité macroéconomique
                if (line.contains("[ACHAT CHOC]") || line.contains("[VENTE CHOC]")) {
                    filteredMessage.append(line).append("\n");
                    activeSignalsCount++;
                } else if (!line.contains("•")) {
                    // On conserve l'ossature visuelle (Titres, En-têtes, Conclusion du momentum)
                    filteredMessage.append(line).append("\n");
                }
            }

            // 🚀 EXPÉDITION FILTRÉE SUR TELEGRAM
            // Le message ne part sur Telegram que s'il y a un réel intérêt opérationnel (impact > 0)
            if (activeSignalsCount > 0) {
                String finalTelegramPayload = "⚡ *ANALYSE DE DRIVER MACRO PONDÉRÉ*\n"
                        + "🕒 " + timeString + " (Mada)\n" 
                        + "📡 Source : " + source + "\n" 
                        + "📋 Actifs Impactés : " + activeSignalsCount + "/11\n\n" 
                        + filteredMessage.toString().trim();
                        
                sendTelegramSecure(finalTelegramPayload);
            } else {
                Log.d(TAG, "Filtrage actif : Aucun mouvement à haute intensité sur vos 11 actifs.");
            }

            // 🏁 Validation finale dans la base sqlite locale
            eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
            return true;
        } else {
            Log.e(TAG, "Erreur renvoyée par l'API Groq. Code HTTP : " + conn.getResponseCode());
        }
     } catch (Exception e) { 
        Log.e(TAG, "Échec critique du traitement macro-analytique", e); 
     }
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
