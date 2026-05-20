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
        
        // Exécution dans un thread séparé pour éviter de bloquer l'application Android (NetworkOnMainThreadException)
        new Thread(() -> {
            try {
                // Vérification que les clés de configuration sont bien chargées dans la MainActivity
                if (MainActivity.TELEGRAM_TOKEN.isEmpty() || MainActivity.TELEGRAM_CHAT_ID.isEmpty()) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("❌ [TELEGRAM] Envoi impossible : Clés manquantes dans l'interface.");
                    }
                    return;
                }

                // Construction de l'URL de l'API Bot Telegram avec encodage UTF-8 des caractères spéciaux
                String urlString = "https://api.telegram.org/bot" + MainActivity.TELEGRAM_TOKEN 
                        + "/sendMessage?chat_id=" + MainActivity.TELEGRAM_CHAT_ID 
                        + "&parse_mode=Markdown&text=" + URLEncoder.encode(message, "UTF-8");

                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("📤 [TELEGRAM] Message transmis avec succès.");
                    }
                } else {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("❌ [TELEGRAM] Échec de l'envoi. Code serveur : " + responseCode);
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Erreur critique d'envoi Telegram", e);
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("❌ [TELEGRAM] Erreur réseau : " + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        createNotificationChannel();
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[SERVICE] Moteur Macro Institutionnel Opérationnel.");
        }
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

        boolean isInstitutionalSource = packageName.contains("financialjuice") 
                || packageName.contains("twitter") 
                || packageName.contains("periscope")
                || packageName.contains("investing");

        List<String> targetAssets = filterActiveAssets(unifiedFeed);
        EconomicEventDetector.DetectedEvent inputEvent = EconomicEventDetector.detectEvent(title, text);
        
        if ("Neutre".equalsIgnoreCase(inputEvent.impact) && !isInstitutionalSource) {
            return; 
        }
        
        boolean isDriverChanged = detectDriverDeviation(unifiedFeed);
        
        if (isDriverChanged && isInstitutionalSource) {
            inputEvent.impact = "CHANGEMENT DE DRIVER MACRO";
        } else if ("Neutre".equalsIgnoreCase(inputEvent.impact) && isInstitutionalSource) {
            inputEvent.impact = "ALERTE MACRO EN DIRECT (CONFORME)"; 
        }

        long exactTimestamp = parseTimeFromText(unifiedFeed, sbn.getPostTime());

        String fingerPrint = generateSecureHash(title + text);
        if (eventDb.eventExists(fingerPrint)) return;

        String sourceName = packageName.contains("financialjuice") ? "FinancialJuice" :
                           packageName.contains("investing") ? "Investing.com" : "X/Twitter";

        long unixSeconds = exactTimestamp / 1000;

        boolean logged = eventDb.saveEvent(fingerPrint, packageName, sourceName, 
                inputEvent.eventType, title, unifiedFeed, String.join(", ", targetAssets), 
                inputEvent.impact, (int) unixSeconds, "notification"); 
        
        if (logged) {
            SystemMonitor.registerEvent(sourceName, targetAssets);
            exec.submit(() -> runSeniorAnalystPipeline(fingerPrint, unifiedFeed, inputEvent, targetAssets, exactTimestamp, isDriverChanged));
        }
    }

    private boolean detectDriverDeviation(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("HIGHER THAN EXPECTED") || upper.contains("LOWER THAN EXPECTED") ||
            upper.contains("ABOVE FORECAST") || upper.contains("BELOW FORECAST") ||
            upper.contains("BEATS ESTIMATES") || upper.contains("MISSES ESTIMATES") ||
            upper.contains("SURPRISE") || upper.contains("SHOCK") || upper.contains("UNEXPECTED")) {
            return true;
        }
        
        Pattern pattern = Pattern.compile("(ACTUAL|ACT):?\\s*([\\d\\.\\-%]+).*?(FORECAST|EST|EXP):?\\s*([\\d\\.\\-%]+)");
        Matcher matcher = pattern.matcher(upper);
        if (matcher.find()) {
            try {
                String actualStr = matcher.group(2).replaceAll("[^\\d\\.]", "");
                String forecastStr = matcher.group(4).replaceAll("[^\\d\\.]", "");
                double actual = Double.parseDouble(actualStr);
                double forecast = Double.parseDouble(forecastStr);
                return actual != forecast;
            } catch (Exception e) {
                return true; 
            }
        }
        return false;
    }

    private long parseTimeFromText(String text, long defaultPostTime) {
        String lowerText = text.toLowerCase();
        
        Pattern minsPattern = Pattern.compile("(\\d+)\\s*min?(s|ute|utes)?\\s*(ago)?");
        Matcher minsMatcher = minsPattern.matcher(lowerText);
        if (minsMatcher.find()) {
            try {
                int minutesAgo = Integer.parseInt(minsMatcher.group(1));
                return System.currentTimeMillis() - ((long) minutesAgo * 60 * 1000);
            } catch (Exception e) { /**/ }
        }

        if (lowerText.contains("just now")) {
            return System.currentTimeMillis();
        }

        Pattern timePattern = Pattern.compile("([0-1]?[0-9]|2[0-3]):([0-5][0-9])");
        Matcher timeMatcher = timePattern.matcher(lowerText);
        if (timeMatcher.find()) {
            try {
                int hour = Integer.parseInt(timeMatcher.group(1));
                int minute = Integer.parseInt(timeMatcher.group(2));
                
                TimeZone sourceTimeZone = TimeZone.getTimeZone("UTC");
                if (lowerText.contains("est") || lowerText.contains("edt") || lowerText.contains("am") || lowerText.contains("pm") || lowerText.contains("us")) {
                    sourceTimeZone = TimeZone.getTimeZone("America/New_York");
                } else if (lowerText.contains("bst") || lowerText.contains("gmt")) {
                    sourceTimeZone = TimeZone.getTimeZone("Europe/London");
                }

                Calendar sourceCal = Calendar.getInstance(sourceTimeZone);
                sourceCal.setTimeInMillis(defaultPostTime);
                sourceCal.set(Calendar.HOUR_OF_DAY, hour);
                sourceCal.set(Calendar.MINUTE, minute);
                sourceCal.set(Calendar.SECOND, 0);

                TimeZone madaTimeZone = TimeZone.getTimeZone("Indian/Antananarivo");
                Calendar madaCal = Calendar.getInstance(madaTimeZone);
                madaCal.setTimeInMillis(sourceCal.getTimeInMillis());

                if (madaCal.getTimeInMillis() > System.currentTimeMillis() + (2 * 60 * 60 * 1000)) {
                    madaCal.add(Calendar.DAY_OF_YEAR, -1);
                }
                return madaCal.getTimeInMillis();
            } catch (Exception e) { /**/ }
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
        if (upper.contains("AUD")) assets.add("AUDUSD");
        if (upper.contains("CAD")) assets.add("USDCAD");
        if (upper.contains("JPY")) assets.add("USDJPY");
        if (upper.contains("EUROZONE") || upper.contains("EUR ") || upper.contains("ECB")) assets.add("EURUSD");
        
        if (assets.isEmpty()) assets.add("GLOBAL-MACRO");
        return assets;
    }

    private void runSeniorAnalystPipeline(String hash, String feed, EconomicEventDetector.DetectedEvent ev, List<String> assets, long eventTimestamp, boolean driverChanged) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
            String timeString = sdf.format(new Date(eventTimestamp)) + " (Mada)";

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.02);

            JSONArray messages = new JSONArray();
            String systemPrompt = "Tu es un analyste macroéconomique de haut niveau. " +
                    "Analyse si cette publication modifie le driver fondamental des actifs spécifiés. No chit-chat. Format direct.";
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            
            String userPrompt = "ALERTE CHRONOLOGIQUE À [" + timeString + "] :\n" + feed + 
                    "\n\nACTIFS CONCERNÉS : " + String.join(", ", assets) + 
                    "\nANOMALIE DE DRIVER DÉTECTÉE PAR LE SYSTEME : " + (driverChanged ? "OUI" : "NON");
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
            os.flush();
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONObject json = new JSONObject(response.toString());
                String aiAnalysis = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                String alertTitle = driverChanged ? "*⚡ DÉVIATION CRITIQUE : CHANGEMENT DE DRIVER MACRO*" : "*🚨 RAPPORT FLUSH MACRO (CONFORME)*";
                
                String tgMsg = alertTitle + " - " + timeString + "\n" +
                               "*Origine :* " + ev.description + "\n*Actifs Cibles :* " + String.join(", ", assets) + "\n\n" +
                               "*ANALYSE D'IMPACT SUR LES REVOLUTIONS DE FLUX :*\n" + aiAnalysis;
                
                sendTelegramSecure(tgMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec exécution Pipeline Groq", e);
        }
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
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }
} // 🌟 Cette accolade ferme la classe NotificationService. Elle est indispensable !

    
