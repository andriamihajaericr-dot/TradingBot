package com.tradingbot.analyzer;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "trading_execution_core";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService microScheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;

    private static final ConcurrentHashMap<String, Long> duplicateProtectionCache = new ConcurrentHashMap<>();
    private static final Set<String> historicalReports = Collections.synchronizedSet(new HashSet<>());

    private static final String[][] COMPLETE_ASSET_MATRICES = {
        {"US10Y", "us10y,yields,10-year treasury,bond yield,obligations"},
        {"GOLD", "gold,xauusd,bullion,or,metal precious"},
        {"SP500", "sp500,s&p 500,spx,equities"},
        {"NASDAQ", "nasdaq,nas100,ndx,tech stocks"},
        {"GBPUSD", "gbpusd,cable,pound dollar,sterling"},
        {"USOIL", "usoil,wti,crude,pétrole,brent"},
        {"AUDUSD", "audusd,aussie,australian dollar"},
        {"USDCAD", "usdcad,loonie,canada dollar"},
        {"USDJPY", "usdjpy,ninja,yen,japan asset"},
        {"BITCOIN", "bitcoin,btc,crypto,btcusd"}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        initAlertPipeline();
        
        microScheduler.scheduleAtFixedRate(() -> eventDb.cleanOldEvents(), 1, 24, TimeUnit.HOURS);
        microScheduler.scheduleAtFixedRate(() -> EventValidator.preloadCalendar(), 0, 15, TimeUnit.MINUTES);
        microScheduler.scheduleAtFixedRate(() -> handleTimedAnalystReporting(), 0, 1, TimeUnit.MINUTES);

        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[CORE] Moteur Macro Global Opérationnel.");
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!getSharedPreferences("TradingBot", MODE_PRIVATE).getBoolean("bot_active", false)) return;

        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String unifiedFeed = (title + " " + text).trim();

        if (unifiedFeed.length() < 20) return;

        List<String> targetAssets = filterActiveAssets(unifiedFeed);
        EconomicEventDetector.DetectedEvent inputEvent = EconomicEventDetector.detectEvent(title, text);
        
        if ("Neutre".equalsIgnoreCase(inputEvent.impact)) return; // Rejet du bruit analytique non directionnel

        EventValidator.ValidationResult verification = EventValidator.validate(title, unifiedFeed, System.currentTimeMillis(), targetAssets);
        String fingerPrint = generateSecureHash(title + text);
        
        if (eventDb.eventExists(fingerPrint)) return; 

        boolean logged = eventDb.saveEvent(fingerPrint, sbn.getPackageName(), "Terminal-Core", 
                inputEvent.eventType, title, unifiedFeed, String.join(", ", targetAssets), inputEvent.impact, verification.confidence, "notification");
        
        if (logged) {
            networkExecutor.submit(() -> runSeniorAnalystPipeline(fingerPrint, unifiedFeed, inputEvent, targetAssets));
        }
    }

    private void runSeniorAnalystPipeline(String id, String rawText, EconomicEventDetector.DetectedEvent ev, List<String> assets) {
        String stateRegime = eventDb.getCurrentMarketRegime();
        String fullCoTPrompt = "REGIME ECONOMIQUE EN COURS : " + stateRegime + "\n" +
                               "DONNEE MACROECONOMIQUE BRUTE : " + rawText + "\n\n" +
                               "CONSIGNES DE RAISONNEMENT MACRO (CHAIN-OF-THOUGHT) :\n" +
                               "1. Calcule la déviation exacte. Quelle est l'onde de choc immédiate sur les Taux Souverains (US10Y) et le Dollar Index (DXY) ?\n" +
                               "2. Déduis l'impact de flux de capitaux sur les actifs cibles : " + String.join(", ", assets) + ".\n" +
                               "RÈGLE STRICTISSIME : Température basse active. Interdiction formelle d'inventer des métriques ou de supputer. Pas de prose.\n\n" +
                               "EXEMPLE TECHNIQUE DE SORTIE ATTENDU :\n" +
                               "[US10Y] -> Hausse des taux de rendement.\n" +
                               "[GOLD] -> Pression baissière causale directe via coût d'opportunité.\n" +
                               "[NASDAQ / SP500] -> Compression des multiples de valorisation des actions.\n" +
                               "Génère l'analyse professionnelle brute pour les actifs détectés :";

        try {
            JSONObject payload = new JSONObject()
                .put("model", GROQ_MODEL)
                .put("temperature", 0.02) // Éradication complète des hallucinations
                .put("messages", new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", "Tu es Macro Analyste Senior en Chef (20 ans d'expérience). Style purement factuel, laconique et axé sur la causalité des flux financiers inter-marchés."))
                    .put(new JSONObject().put("role", "user").put("content", fullCoTPrompt)));

            String responseContent = requestGroqGateway(payload.toString());
            
            String finalTelegramTemplate = "🏛️ **MEMO DE RECHERCHE MACRO (Tier " + ev.tier + ")**\n" +
                                           "**Événement :** " + ev.getDescription() + "\n" +
                                           "**Biais Fondamental :** " + ev.impact + "\n\n" + responseContent;

            sendTelegramSecure(finalTelegramTemplate);
            triggerInternalSystemAlert(this, String.join(", ", assets), ev.impact);

            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[ANALYST-OK] Traité : " + ev.indicator);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception dans le pipeline de traitement Groq", e);
        }
    }

    private void handleTimedAnalystReporting() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        
        if (minute == 0 && (hour == 8 || hour == 12 || hour == 16 || hour == 21)) {
            String trackingId = hour + "_" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            if (historicalReports.contains(trackingId)) return;
            
            long window = System.currentTimeMillis() - (4 * 3600 * 1000);
            List<EventDatabase.StoredEvent> events = eventDb.getEventsInTimeWindow(window, 4 * 3600 * 1000);
            
            if (events == null || events.isEmpty()) return;

            StringBuilder sb = new StringBuilder("📊 **RAPPORT DE RECHERCHE MACRO INTER-MARCHÉS - " + hour + "h00**\n\n");
            sb.append("Régime structurel de la session : **").append(eventDb.getCurrentMarketRegime()).append("**\n\n");
            sb.append("Dernières déviations d'indicateurs enregistrées :\n");
            for (EventDatabase.StoredEvent e : events) {
                sb.append("• [").append(e.impact).append("] ").append(e.title).append("\n");
            }
            sendTelegramSecure(sb.toString());
            historicalReports.add(trackingId);
        }
    }

    private static String requestGroqGateway(String payload) throws Exception {
        URL url = new URL(GROQ_URL);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY.trim());
        c.setDoOutput(true);
        
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        if (c.getResponseCode() == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close(); c.disconnect();
            return new JSONObject(sb.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        }
        c.disconnect();
        throw new IOException("Groq API Return Invalid Status: " + c.getResponseCode());
    }

    public static void sendTelegramSecure(String message) {
        if (message == null || message.trim().isEmpty()) return;
        try {
            String uniqueSig = generateSecureHash(message);
            long time = System.currentTimeMillis();
            if (duplicateProtectionCache.containsKey(uniqueSig) && (time - duplicateProtectionCache.get(uniqueSig) < 300000)) return; 
            duplicateProtectionCache.put(uniqueSig, time);
            
            String endpoint = "https://api.telegram.org/bot" + MainActivity.TELEGRAM_TOKEN + "/sendMessage?chat_id=" + MainActivity.TELEGRAM_CHAT_ID + "&text=" + URLEncoder.encode(message, "UTF-8") + "&parse_mode=Markdown";
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setConnectTimeout(4000); c.getResponseCode(); c.disconnect();
        } catch (Exception e) {}
    }

    private static String generateSecureHash(String rawInput) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] rawHash = digest.digest(rawInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : rawHash) hexString.append(String.format("%02x", b));
            return hexString.toString();
        } catch (Exception e) { return UUID.randomUUID().toString(); }
    }

    private List<String> filterActiveAssets(String text) {
        String lower = text.toLowerCase();
        List<String> detected = new ArrayList<>();
        for (String[] target : COMPLETE_ASSET_MATRICES) {
            for (String token : target[1].split(",")) {
                if (lower.contains(token.trim())) { detected.add(target[0]); break; }
            }
        }
        if (detected.isEmpty()) { 
            detected.addAll(Arrays.asList("US10Y", "GOLD", "SP500")); 
        }
        return detected;
    }

    private void initAlertPipeline() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Macro Execution Engine", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void triggerInternalSystemAlert(Context ctx, String matrix, String structuralStatus) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification alert = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Macro Core Alert")
                .setContentText("Matrice de propagation traitée pour : " + matrix + " [" + structuralStatus + "]")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        nm.notify((int) System.currentTimeMillis(), alert);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        networkExecutor.shutdown();
        microScheduler.shutdown();
        if (eventDb != null) eventDb.close();
    }
}
