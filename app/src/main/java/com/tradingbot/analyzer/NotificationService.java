package com.tradingbot.analyzer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class NotificationService extends NotificationListenerService {

    private static final String CHANNEL_ID = "trading_alerts";
    private static final int    NOTIF_ID   = 2001;
    private final ExecutorService exec = Executors.newFixedThreadPool(3);

    // =========================================================
    //  ✅ APPLICATIONS AUTORISÉES UNIQUEMENT
    //  Telegram, WhatsApp, SMS etc. sont ignorés automatiquement
    // =========================================================
    private static final List<String> ALLOWED_APPS = Arrays.asList(
        "com.twitter.android",       // X/Twitter app
        "com.brave.browser",         // Brave browser
        "com.android.chrome",        // Chrome
        "com.chrome.beta",           // Chrome Beta
        "org.mozilla.firefox",       // Firefox
        "org.mozilla.fenix",         // Firefox Fenix
        "com.coinglass.app",         // Coinglass
        "com.financialjuice",        // FinancialJuice
        "com.investing.app",         // Investing.com
        "com.reuters.news"           // Reuters
    );

    // =========================================================
    //  MOTS-CLÉS DÉCLENCHEURS
    // =========================================================
    private static final List<String> KEYWORDS = Arrays.asList(
        // Géopolitique
        "war", "attack", "missile", "sanction", "conflict", "crisis",
        "invasion", "nuclear", "terror", "breaking", "urgent", "alert",
        "flash", "guerre", "attaque", "conflit", "crise",
        // Macro économique
        "fed", "rate", "inflation", "cpi", "nfp", "gdp", "fomc",
        "powell", "recession", "taux", "jobs", "unemployment",
        // Or / Matières premières
        "gold", "xauusd", "silver", "oil", "crude",
        // Crypto
        "bitcoin", "btc", "crypto", "etf", "binance", "coinbase", "hack",
        // Forex
        "dollar", "usd", "gbp", "jpy", "eur", "forex",
        // Indices
        "nasdaq", "sp500", "dow", "stock", "market",
        // Sources
        "reuters", "bloomberg", "breaking news"
    );

    // =========================================================
    //  ACTIFS ET LEURS MOTS-CLÉS
    // =========================================================
    private static final String[][] ASSETS = {
        {"GOLD",   "gold,xauusd,or,fed,rate,war,conflict,sanction,nuclear,inflation,powell,silver"},
        {"BTCUSD", "bitcoin,btc,crypto,etf,binance,coinbase,hack,sec"},
        {"GBPUSD", "gbp,pound,boe,uk,britain,sterling"},
        {"USDJPY", "jpy,yen,boj,japan"},
        {"NASDAQ", "nasdaq,sp500,dow,wall street,stock,tech"},
    };

    // =========================================================
    //  DÉCLENCHEMENT AUTOMATIQUE À CHAQUE NOTIFICATION
    // =========================================================
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {

        // Vérifier si le bot est actif
        boolean botActive = getSharedPreferences("TradingBot", MODE_PRIVATE)
            .getBoolean("bot_active", false);
        if (!botActive) return;

        // ✅ FILTRE PAR APPLICATION — ignore Telegram, WhatsApp, SMS etc.
        String packageName = sbn.getPackageName();
        boolean isAllowed = false;
        for (String allowed : ALLOWED_APPS) {
            if (packageName.toLowerCase().contains(allowed.toLowerCase())) {
                isAllowed = true;
                break;
            }
        }
        if (!isAllowed) {
            // App non autorisée — ignorer silencieusement
            return;
        }

        // Extraire le texte de la notification
        Bundle extras = sbn.getNotification().extras;
        String title   = extras.getString(Notification.EXTRA_TITLE, "");
        String text    = extras.getString(Notification.EXTRA_TEXT, "");
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
        String full    = bigText.isEmpty() ? text : bigText;
        String combined = (title + " " + full).trim();

        // Vérifier que le texte n'est pas vide
        if (combined.isEmpty()) return;

        // ✅ FILTRE PAR MOTS-CLÉS — ignore les notifications non trading
        if (!isTradingRelevant(combined)) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog(
                    "⏭️ Ignoré (" + getAppName(packageName) + "): non trading"
                );
            return;
        }

        // Notification pertinente détectée !
        String appName = getAppName(packageName);
        if (MainActivity.instance != null)
            MainActivity.instance.addLog(
                "🔔 " + appName + ": "
                + combined.substring(0, Math.min(60, combined.length())) + "..."
            );

        // Traitement asynchrone
        final String finalText = combined;
        final String finalApp  = appName;
        exec.submit(() -> processNotification(this, finalApp, finalText));
    }

    // =========================================================
    //  PIPELINE COMPLET : FILTRE → CLAUDE → TELEGRAM → NOTIF
    // =========================================================
    public static void processNotification(Context ctx, String appName, String text) {
        // 1. Détecter les actifs concernés
        List<String> assets = detectAssets(text);
        String assetsStr    = String.join(", ", assets);

        // 2. Analyser avec Claude API
        String analysis = analyzeWithClaude(text, assetsStr);

        // 3. Timestamp
        String ts = new java.text.SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss", Locale.getDefault()
        ).format(new Date());

        // 4. Envoyer sur Telegram
        String tgMsg = "🔔 *ALERTE TRADING*\n"
            + "📱 Source: " + appName + "\n\n"
            + "📰 *News:*\n"
            + text.substring(0, Math.min(300, text.length())) + "\n\n"
            + "📊 *ANALYSE FONDAMENTALE:*\n"
            + analysis + "\n\n"
            + "⏰ " + ts;

        sendTelegram(tgMsg);

        // 5. Notification push locale
        showLocalNotif(ctx, assetsStr, analysis);

        // 6. Log dans l'interface
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("✅ Analyse envoyée — " + assetsStr);
    }

    // =========================================================
    //  APPEL CLAUDE API (Haiku — moins cher ~$0.000002/appel)
    // =========================================================
    private static String analyzeWithClaude(String text, String assets) {
        try {
            URL url = new URL("https://api.anthropic.com/v1/messages");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-api-key", MainActivity.CLAUDE_API_KEY);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);

            String prompt = "Tu es analyste financier expert en trading.\n"
                + "Breaking news: \"" + text + "\"\n"
                + "Actifs concernés: " + assets + "\n\n"
                + "Analyse fondamentale COURTE et DIRECTE:\n\n"
                + "Pour chaque actif:\n"
                + "📊 IMPACT: Haussier / Baissier / Neutre\n"
                + "✅ SIGNAL: BUY / SELL / WAIT\n"
                + "💡 RAISON: 1 phrase max\n"
                + "⚡ CONVICTION: Faible / Moyenne / Forte\n\n"
                + "🎯 RÉSUMÉ GLOBAL: [actif prioritaire] → [BUY/SELL] — conviction [niveau]";

            // Construire le JSON
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", prompt);

            JSONArray msgs = new JSONArray();
            msgs.put(msg);

            JSONObject body = new JSONObject();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 500);
            body.put("messages", msgs);

            // Envoyer la requête
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            // Lire la réponse
            int code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                code == 200 ? c.getInputStream() : c.getErrorStream()
            ));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            if (code == 200) {
                JSONObject resp = new JSONObject(sb.toString());
                return resp.getJSONArray("content").getJSONObject(0).getString("text");
            } else {
                return "❌ Erreur API (" + code + "): " + sb.toString();
            }

        } catch (Exception e) {
            return "❌ Erreur: " + e.getMessage();
        }
    }

    // =========================================================
    //  ENVOI TELEGRAM
    // =========================================================
    private static void sendTelegram(String message) {
        try {
            String enc = URLEncoder.encode(message, "UTF-8");
            URL url = new URL(
                "https://api.telegram.org/bot" + MainActivity.TELEGRAM_TOKEN
                + "/sendMessage?chat_id=" + MainActivity.TELEGRAM_CHAT_ID
                + "&text=" + enc
                + "&parse_mode=Markdown"
            );
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            c.disconnect();

            if (code != 200 && MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ Telegram erreur: " + code);

        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ Telegram: " + e.getMessage());
        }
    }

    // =========================================================
    //  NOTIFICATION PUSH LOCALE SUR LE TÉLÉPHONE
    // =========================================================
    private static void showLocalNotif(Context ctx, String assets, String analysis) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Trading Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Alertes de trading automatiques");
            nm.createNotificationChannel(ch);
        }

        // Résumé court pour la notification
        String summary = analysis.length() > 120
            ? analysis.substring(analysis.length() - 120)
            : analysis;

        nm.notify(NOTIF_ID, new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔔 Signal Trading — " + assets)
            .setContentText(summary)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(analysis))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(new long[]{0, 300, 100, 300})
            .build()
        );
    }

    // =========================================================
    //  HELPERS
    // =========================================================
    private boolean isTradingRelevant(String text) {
        String lower = text.toLowerCase();
        for (String kw : KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private static List<String> detectAssets(String text) {
        String lower = text.toLowerCase();
        List<String> found = new ArrayList<>();
        for (String[] a : ASSETS) {
            for (String kw : a[1].split(",")) {
                if (lower.contains(kw.trim()) && !found.contains(a[0])) {
                    found.add(a[0]);
                    break;
                }
            }
        }
        // Par défaut si aucun actif détecté
        if (found.isEmpty()) {
            found.add("GOLD");
            found.add("BTCUSD");
        }
        return found;
    }

    private String getAppName(String pkg) {
        if (pkg.contains("twitter") || pkg.contains(".x")) return "X/Twitter";
        if (pkg.contains("brave"))      return "Brave";
        if (pkg.contains("chrome"))     return "Chrome";
        if (pkg.contains("firefox"))    return "Firefox";
        if (pkg.contains("coinglass"))  return "Coinglass";
        if (pkg.contains("financial"))  return "FinancialJuice";
        if (pkg.contains("investing"))  return "Investing.com";
        if (pkg.contains("reuters"))    return "Reuters";
        return pkg;
    }
}
