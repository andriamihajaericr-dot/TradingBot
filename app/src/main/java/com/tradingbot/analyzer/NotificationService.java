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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
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
        startDailyBriefScheduler(); // 🌅 Lancement du planificateur de résumé automatique
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

        List<String> targetAssets = filterActiveAssets(unifiedFeed);
        EconomicEventDetector.DetectedEvent inputEvent = EconomicEventDetector.detectEvent(title, text);
        
        boolean isDriverChanged = detectDriverDeviation(unifiedFeed);
        boolean isFomcPivot = unifiedFeed.toUpperCase().contains("FOMC") || unifiedFeed.toUpperCase().contains("FED ");

        long exactTimestamp = parseTimeFromText(unifiedFeed, sbn.getPostTime());
        String fingerPrint = generateSecureHash(title + text);
        if (eventDb.eventExists(fingerPrint)) return;

        long unixSeconds = exactTimestamp / 1000;

        // 📉 CAS 1 : La news est conforme ET ce n'est pas un événement majeur (FOMC) -> On filtre pour couper le bruit
        if (!isDriverChanged && !isFomcPivot) {
            eventDb.saveEvent(fingerPrint, packageName, sourceName, inputEvent.eventType, title, unifiedFeed, 
                    String.join(", ", targetAssets), "Conforme (Ignoré)", (int) unixSeconds, "database_only");
            return; 
        }

        // ⚡ CAS 2 : Événement Pivot Majeur (FOMC) ou Déviation Fondamentale Réelle
        inputEvent.impact = isFomcPivot ? "PIVOT CRITIQUE FOMC" : "CHANGEMENT DE DRIVER MACRO";
        
        boolean logged = eventDb.saveEvent(fingerPrint, packageName, sourceName, 
                inputEvent.eventType, title, unifiedFeed, String.join(", ", targetAssets), 
                inputEvent.impact, (int) unixSeconds, "telegram"); 
        
        if (logged) {
            SystemMonitor.registerEvent(sourceName, targetAssets);
            final String finalSource = sourceName;
            
            // On extrait l'historique complet (y compris les news CPI/NFP conformes stockées en tâche de fond)
            String historyContext = eventDb.getRecentEventsForAssets(targetAssets, 6);
            
            exec.submit(() -> runSeniorAnalystPipeline(finalSource, unifiedFeed, historyContext, targetAssets, exactTimestamp, isFomcPivot));
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
     * Pipeline d'Analyse IA : Gère les déviations standards ET la modélisation prédictive FOMC
     */
    private void runSeniorAnalystPipeline(String source, String feed, String history, List<String> assets, long eventTimestamp, boolean isFomcPivot) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
            String timeString = sdf.format(new Date(eventTimestamp)) + " (Mada)";

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", isFomcPivot ? 0.15 : 0.02); // Plus de créativité de synthèse si c'est le FOMC

            JSONArray messages = new JSONArray();
            
            String systemPrompt = "Tu es le Chef de la Stratégie Macroéconomique d'un fonds quantitatif. " +
                    "Ton travail est d'analyser les ruptures de flux et d'interpréter les implications des banques centrales (FED/FOMC) en croisant les données historiques reçues (CPI, NFP). " +
                    "Sois ultra-précis, froid, mathématique. Pas de blabla.";
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            
            StringBuilder userPrompt = new StringBuilder();
            if (isFomcPivot) {
                userPrompt.append("🚨 !!! ALERTE MAJEURE BANQUE CENTRALE : COMPLEXE FOMC !!!\n");
            }
            userPrompt.append("Flux actuel : ").append(feed).append("\n Source : ").append(source).append("\n\n")
                      .append("--- MÉMOIRE MACRO DE LA BASE DE DONNÉES (CONTEXTE RECENT) ---\n")
                      .append(history.isEmpty() ? "Aucune donnée macro mémorisée récemment.\n" : history).append("\n")
                      .append("--- INSTRUCTIONS DE PROTOCOLE D'EXÉCUTION ---\n");

            if (isFomcPivot) {
                userPrompt.append("Tu dois obligatoirement croiser ce flash FOMC avec les données CPI (Inflation) et NFP (Emploi) présentes dans la mémoire ci-dessus.\n")
                          .append("Rédige ton analyse sous cette forme exacte :\n")
                          .append("1) CORRÉLATION HISTORIQUE : (Ex: 'Le CPI en hausse de mardi combiné à ce FOMC implique...')\n")
                          .append("2) RÉSULTAT DU DRIVER DES TAUX D'INTÉRÊT : [HAUSSIER], [BAISSIER] ou [STABLE]\n")
                          .append("3) IMPACT PAR ACTIF (").append(String.join(", ", assets)).append(") : Conclus chaque actif par [ACHAT CHOC], [VENTE CHOC] ou [NEUTRE].");
            } else {
                userPrompt.append("Pour chaque actif cible (").append(String.join(", ", assets)).append(") :\n")
                          .append("1) Impact direct du flux.\n")
                          .append("2) Bilan de la dynamique cumulative par rapport à l'historique.\n")
                          .append("3) Tag de décision technique : [BIAIS HAUSSIER], [BIAIS BAISSIER] ou [STABLE/NEUTRE].");
            }
            
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt.toString()));
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

                String header = isFomcPivot ? "*🔥 DECISION BANQUE CENTRALE | INTERPRÉTATION AUTOMATIQUE FOMC*" : "*⚡ DEVIATION CRITIQUE | " + source.toUpperCase() + "*";
                String tgMsg = header + "\n🕒 " + timeString + "\n\n" + aiAnalysis;
                
                sendTelegramSecure(tgMsg);
            }
        } catch (Exception e) { Log.e(TAG, "Échec Pipeline Analyste", e); }
    }

    /**
     * 🌅 PLANIFICATEUR DU RÉSUMÉ JOURNALIER (DAILY BRIEF)
     * Calcule le temps restant jusqu'au prochain matin à 07h00 (Heure Madagascar UTC+3)
     */
    private void startDailyBriefScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        nextRun.set(Calendar.HOUR_OF_DAY, 7);
        nextRun.set(Calendar.MINUTE, 0);
        nextRun.set(Calendar.SECOND, 0);

        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }

        long initialDelay = nextRun.getTimeInMillis() - System.currentTimeMillis();
        long period = 24 * 60 * 60 * 1000; // Exécution toutes les 24 heures

        scheduler.scheduleAtFixedRate(this::generateAndSendDailyBrief, initialDelay, period, TimeUnit.MILLISECONDS);
        Log.d(TAG, "Planificateur Daily Brief armé. Premier déclenchement dans : " + (initialDelay / 1000 / 60) + " minutes.");
    }

    /**
     * Génère la synthèse globale matinale basée sur les données de la veille stockées en BDD
     */
    private void generateAndSendDailyBrief() {
        try {
            long now = System.currentTimeMillis();
            String dailyDrivers = eventDb.getDailyMacroDrivers(now);
            
            if (dailyDrivers.isEmpty()) {
                sendTelegramSecure("🌅 *DAILY BRIEF MACRO — ANTANANARIVO*\n\n" +
                        "📊 *Statut :* Marché calme. Aucun changement de driver macroéconomique majeur enregistré au cours des dernières 24 heures.\n" +
                        "💡 *Biais général :* Préservation des tendances de fond antérieures.");
                return;
            }

            // Sollicitation de Groq pour condenser l'historique des dernières 24 heures en une matrice décisionnelle
            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", 
                "Tu es un Analyste Inter-Marchés. Rédige un briefing matinal synthétique, clair et exploitable pour un trader à partir de la liste des chocs macro survenus hier. Indique s'il y a eu un changement de driver structurel. No chit-chat."));
            
            messages.put(new JSONObject().put("role", "user").put("content", 
                "DONNÉES BRUTES DES CHOCS MACRO DES DERNIÈRES 24 HEURES :\n" + dailyDrivers + 
                "\n\nFournis : 1) La synthèse du sentiment global de marché. 2) La matrice des biais par actif pour la session à venir."));
            
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
                String dailySummary = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                String tgBrief = "🌅 *DAILY BRIEF MACRO — TERMINAL DE TRADING*\n" +
                                 "📅 Date : " + new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date()) + " (07:00 Mada)\n\n" +
                                 dailySummary;
                
                sendTelegramSecure(tgBrief);
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec de la génération du Daily Brief matinal", e);
        }
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
        scheduler.shutdown(); // Fermeture propre du planificateur si le service s'arrête
    }
}
