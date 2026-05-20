package com.tradingbot.analyzer;

import java.util.Locale; 
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.core.app.NotificationCompat;
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
    private EventDatabase eventDb;

    public static void sendTelegramSecure(String message) {
        Log.d(TAG, "Routage Sortant Telegram : " + message);
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
                int responseCode = conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { Log.e(TAG, "Erreur Telegram", e); }
        }).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        createNotificationChannel();
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

        // 1. Détection dynamique de l'origine de l'information
        String sourceName = "Source Institutionnelle";
        if (packageName.contains("financialjuice")) sourceName = "FinancialJuice";
        else if (packageName.contains("investing")) sourceName = "Investing.com";
        else if (packageName.contains("twitter") || packageName.contains("periscope")) sourceName = "X / Twitter";
        else return; // 🛑 On IGNORE immédiatement toutes les applications non-financières pour couper le bruit

        List<String> targetAssets = filterActiveAssets(unifiedFeed);
        EconomicEventDetector.DetectedEvent inputEvent = EconomicEventDetector.detectEvent(title, text);
        
        // 2. FILTRAGE ANTI-BRUIT STRICT (Écart fondamental mathématique)
        boolean isDriverChanged = detectDriverDeviation(unifiedFeed);
        
        // 📉 Révolution : Si l'info est conforme (pas d'écart), on l'enregistre en BDD pour l'historique mais on N'ENVOIE PAS sur Telegram
        if (!isDriverChanged) {
            long exactTimestamp = parseTimeFromText(unifiedFeed, sbn.getPostTime());
            String fingerPrint = generateSecureHash(title + text);
            eventDb.saveEvent(fingerPrint, packageName, sourceName, inputEvent.eventType, title, unifiedFeed, 
                    String.join(", ", targetAssets), "Conforme (Ignoré)", (int)(exactTimestamp / 1000), "database_only");
            return; 
        }

        // Si on arrive ici, c'est qu'il y a une déviation ou un choc réel de marché
        inputEvent.impact = "CHANGEMENT DE DRIVER MACRO";
        long exactTimestamp = parseTimeFromText(unifiedFeed, sbn.getPostTime());
        String fingerPrint = generateSecureHash(title + text);
        
        if (eventDb.eventExists(fingerPrint)) return;

        long unixSeconds = exactTimestamp / 1000;
        boolean logged = eventDb.saveEvent(fingerPrint, packageName, sourceName, 
                inputEvent.eventType, title, unifiedFeed, String.join(", ", targetAssets), 
                inputEvent.impact, (int) unixSeconds, "telegram"); 
        
        if (logged) {
            SystemMonitor.registerEvent(sourceName, targetAssets);
            final String finalSource = sourceName;
            
            // 3. Récupération de l'historique récent pour cet actif (Pour la conclusion globale)
            String historyContext = eventDb.getRecentEventsForAssets(targetAssets, 3);
            
            exec.submit(() -> runSeniorAnalystPipeline(finalSource, unifiedFeed, historyContext, targetAssets, exactTimestamp));
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

    private void runSeniorAnalystPipeline(String source, String feed, String history, List<String> assets, long eventTimestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
            String timeString = sdf.format(new Date(eventTimestamp)) + " (Mada)";

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.05); // Légèrement augmenté pour une meilleure synthèse globale

            JSONArray messages = new JSONArray();
            
            // 🎯 LE PROMPT OPTIMISÉ (Analyse d'Impact + Conclusion Globale Cumulative)
            String systemPrompt = "Tu es un Macro-Strategist de premier plan dans un Hedge Fund. " +
                    "Ton rôle est d'analyser les chocs macroéconomiques et d'en formuler la SYNTHÈSE GLOBALE CUMULATIVE. " +
                    "Ne commente que les actifs spécifiés. Sois direct, froid et analytique. Pas d'introduction, pas de salutations.";
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            
            String userPrompt = "--- DERNIÈRE ALERTE EN DIRECT ---\n" +
                    "Source : " + source + "\n" +
                    "Flux : " + feed + "\n\n" +
                    "--- HISTORIQUE RÉCENT DE CES ACTIFS (CONTEXTE GLOBAL) ---\n" +
                    (history.isEmpty() ? "Aucun événement récent en mémoire." : history) + "\n\n" +
                    "--- CONSIGNES DE RÉDACTION ---\n" +
                    "Pour chaque actif cible (" + String.join(", ", assets) + ") :\n" +
                    "1) Donne l'impact de la news actuelle.\n" +
                    "2) Fais une CONCLUSION GLOBALE synthétisant la news actuelle avec l'historique récent pour cet actif.\n" +
                    "3) Termine STRICTEMENT par ton biais d'exécution direct : [ACHAT CHOC], [VENTE CHOC] ou [NEUTRE/ATTENTE].";
            
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush(); os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONObject json = new JSONObject(response.toString());
                String aiAnalysis = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                // ✨ Titre dynamique intégrant la vraie source
                String tgMsg = "*⚡ DEVIATION CRITIQUE | " + source.toUpperCase() + "*\n" +
                               "🕒 Heure : " + timeString + "\n" +
                               "📋 Actifs : " + String.join(", ", assets) + "\n\n" +
                               aiAnalysis;
                
                sendTelegramSecure(tgMsg);
            }
        } catch (Exception e) { Log.e(TAG, "Échec Pipeline", e); }
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
}
