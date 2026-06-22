package com.tradingbot.analyzer;

import java.util.Locale;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
import android.content.SharedPreferences;
import java.util.regex.*;
import java.util.Map;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    // ✅ Singleton
    private static NotificationService serviceInstance;
    public static NotificationService getInstance() {
        return serviceInstance;
    }
    // ✅ CORRECTIF : Variable de verrouillage globale pour l'import SQLite
    public static volatile boolean isDatabaseImportInProgress = false;
    public EventDatabase getEventDb() {
        return this.eventDb;
    }
    //private static final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>();
    private static final String CHANNEL_ID = "trading_alerts";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String PREF_GROQ_KEY   = "groq_key";
    private static final String PREF_TG_TOKEN   = "tg_token";
    private static final String PREF_TG_CHAT_ID = "tg_chat_id";
    private static final String PREF_MACRO_KEY  = "macro_api_key";
    private static final String PREFS_NAME      = "TradingBot";
    private static final long GEO_THROTTLE_MS   = 12 * 60 * 1000L;  // 12 minutes pour géo
    private volatile long lastAnalysisTime = 0;
    private volatile long lastGeoTime = 0;
    // Seuil minimal de prix pour considérer la donnée comme valide
    private static final double MIN_VALID_PRICE = 0.0;
    // Seuil de divergence (0.5% est plus sûr pour éviter le bruit sur le Forex)
    private static final double DIVERGENCE_THRESHOLD = 0.5;
    private final ConcurrentHashMap<String, PrevailingDirection> lastForecast = new ConcurrentHashMap<>();
// Protection anti-spam : évite de scanner en boucle si le marché est instable
private final ConcurrentHashMap<String, Long> lastAlertsSent = new ConcurrentHashMap<>();
private static final long ALERT_COOLDOWN_MS = 60 * 60 * 1000L; // 1 heure de cooldown par actif

// 🛡️ CORRECTIF SPAM INERTIE MACRO : le rappel "Driver déjà actif" partait sans
// aucune limite de fréquence, donc chaque notification rejetée (même non pertinente,
// ex: une dépêche nécrologique mal classée) renvoyait le même message Telegram en boucle.
private final ConcurrentHashMap<String, Long> lastInertiaReminderSent = new ConcurrentHashMap<>();
private static final long INERTIA_REMINDER_COOLDOWN_MS = 30 * 60 * 1000L; // 1 rappel max toutes les 30 min par type de driver
    
    private static class PrevailingDirection {
        final String direction; // "BULLISH", "BEARISH" ou "NEUTRE"
        final double referencePrice;
        final long timestamp;
        PrevailingDirection(String dir, double price, long ts) {
            this.direction = dir;
            this.referencePrice = price;
            this.timestamp = ts;
        }
    }
    
    private void checkAndSendMissedWeeklyReport() {
    SharedPreferences prefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
    long lastWeeklySentMs = prefs.getLong("last_weekly_sent_ms", 0L);

    // Trouver le dernier vendredi à 22h00 (Mada)
    Calendar lastFriday = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
    lastFriday.set(Calendar.HOUR_OF_DAY, 22);
    lastFriday.set(Calendar.MINUTE, 0);
    lastFriday.set(Calendar.SECOND, 0);
    lastFriday.set(Calendar.MILLISECOND, 0);
    // Reculer jusqu'au dernier vendredi
    while (lastFriday.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
        lastFriday.add(Calendar.DAY_OF_MONTH, -1);
    }
    // Si ce vendredi est dans le futur, reculer d'une semaine
    if (lastFriday.getTimeInMillis() > System.currentTimeMillis()) {
        lastFriday.add(Calendar.DAY_OF_MONTH, -7);
    }

    long lastFridayMs = lastFriday.getTimeInMillis();
    boolean alreadySent = lastWeeklySentMs >= lastFridayMs;

    if (!alreadySent) {
        Log.d(TAG, "[WEEKLY] Rapport manqué détecté — envoi immédiat du rattrapage");
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("📅 [WEEKLY] Rapport manqué → envoi du rattrapage");
        }
        // Délai 30s pour laisser le service se stabiliser avant l'appel Groq
        scheduler.schedule(this::generateAndSendWeeklyReport, 30, TimeUnit.SECONDS);
    } else {
        Log.d(TAG, "[WEEKLY] Rapport déjà envoyé cette semaine — pas de rattrapage nécessaire");
    }
    }

    private void checkAndSendMissedMonthlyReport() {
    SharedPreferences prefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
    long lastMonthlySentMs = prefs.getLong("last_monthly_sent_ms", 0L);

    Calendar lastEndOfMonth = Calendar.getInstance(
        TimeZone.getTimeZone("Indian/Antananarivo"));
    lastEndOfMonth.set(Calendar.HOUR_OF_DAY, 23);
    lastEndOfMonth.set(Calendar.MINUTE, 0);
    lastEndOfMonth.set(Calendar.SECOND, 0);
    lastEndOfMonth.set(Calendar.MILLISECOND, 0);
    lastEndOfMonth.set(Calendar.DAY_OF_MONTH, 1);
    lastEndOfMonth.add(Calendar.DAY_OF_MONTH, -1);

    long lastEndOfMonthMs = lastEndOfMonth.getTimeInMillis();

    if (lastMonthlySentMs < lastEndOfMonthMs
            && lastEndOfMonthMs < System.currentTimeMillis()) {
        Log.d(TAG, "[MONTHLY] Rapport mensuel manqué → rattrapage dans 60s");
        if (MainActivity.instance != null)
            MainActivity.instance.addLog(
                "📊 [MONTHLY] Rapport manqué → rattrapage en cours");
        scheduler.schedule(this::generateAndPurgeMonthlyReport, 60, TimeUnit.SECONDS);
    } else {
        Log.d(TAG, "[MONTHLY] Rapport mensuel déjà envoyé — pas de rattrapage");
    }
}
    
    public static final List<String> TWELVE_DATA_ASSETS = Arrays.asList(
    "SP500", "NASDAQ", "GOLD", "GBPUSD", "USDJPY", "USOIL");
@Override
public void onListenerConnected() {
    super.onListenerConnected();
    Log.i(TAG, "✅ [LISTENER] Connecté — le bot reçoit bien le flux système.");
    if (MainActivity.instance != null) {
        MainActivity.instance.addLog("✅ [SYSTÈME] Accès notifications confirmé par Android.");
    }
}

@Override
public void onListenerDisconnected() {
    super.onListenerDisconnected();
    Log.e(TAG, "🔴 [LISTENER] DÉCONNECTÉ — plus aucune notification ne sera captée !");
    if (MainActivity.instance != null) {
        MainActivity.instance.addLog("🔴 [SYSTÈME] Accès notifications COUPÉ (vérifie Paramètres > Accès aux notifications).");
    }
}
    // Ajoutez cette méthode à la fin de votre classe NotificationService.java
    public static String formatEventForDisplay(EconomicCalendarAPI.CalendarEvent e) {
        if (e.actual == null || e.actual.equals("N/A") || e.actual.isEmpty()) {
            return e.indicator + " | Prévu: " + e.forecast;
        }
        
        try {
            // Nettoyage des chaînes pour obtenir uniquement des nombres
            double a = Double.parseDouble(e.actual.replaceAll("[^\\d.\\-]", ""));
            double f = Double.parseDouble(e.forecast.replaceAll("[^\\d.\\-]", ""));
            
            // Logique de flèche
            String fleche = (a > f) ? "↑" : "↓";
            if (a == f) fleche = "="; // Optionnel : cas d'égalité
            
            return String.format("%s | Prévu: %s | Réel: %s %s", e.indicator, e.forecast, e.actual, fleche);
        } catch (Exception ex) {
            // En cas d'erreur de conversion, on retourne le format de base
            return e.indicator + " | Prévu: " + e.forecast + " | Réel: " + e.actual;
        }
    }
    
    private static final Map<String, String> EMOJI_ASSET_MAP = new HashMap<>();
    static {
        EMOJI_ASSET_MAP.put("📈", "US10Y"); EMOJI_ASSET_MAP.put("💻", "NASDAQ");
        EMOJI_ASSET_MAP.put("📊", "SP500"); EMOJI_ASSET_MAP.put("🏆", "GOLD");
        EMOJI_ASSET_MAP.put("🛢️", "USOIL"); EMOJI_ASSET_MAP.put("🇪🇺", "EURUSD");
        EMOJI_ASSET_MAP.put("🇯🇵", "USDJPY"); EMOJI_ASSET_MAP.put("🇨🇦", "USDCAD");
        EMOJI_ASSET_MAP.put("🇬🇧", "GBPUSD"); EMOJI_ASSET_MAP.put("🇦🇺", "AUDUSD");
        EMOJI_ASSET_MAP.put("₿", "BITCOIN");
    }

    private void captureForecastFromReport(String report) {
    if (report == null || report.isEmpty()) return;
    // 🛡️ Guard : si aucune ligne bullet dans le rapport, 
    //            aucune prévision à capturer → 0 appel réseau
    if (!report.contains("•")) return;
    try {
    Map<String, Double> currentPrices = MarketDataFetcher.getPrices(new ArrayList<>(TWELVE_DATA_ASSETS)); // 6 actifs actifs uniquement
    long now = System.currentTimeMillis();
    
            for (String line : report.split("\n")) {
                if (!line.startsWith("•")) continue;
                String[] parts = line.split(":");
                if (parts.length < 2) continue;
    
                // Robustesse : trim pour ignorer les espaces avant/après
                String leftPart = parts[0].trim();
                String rightPart = parts[1].trim();
    
                String asset = null;
                for (Map.Entry<String, String> entry : EMOJI_ASSET_MAP.entrySet()) {
                    if (leftPart.contains(entry.getKey())) {
                        asset = entry.getValue();
                        break;
                    }
                }
                if (asset == null) continue;
    
                String direction = "NEUTRE";
                // Vérification sur le segment nettoyé
                if (rightPart.contains("BULLISH") || rightPart.contains("ACHAT")) direction = "BULLISH";
                else if (rightPart.contains("BEARISH") || rightPart.contains("VENTE")) direction = "BEARISH";
    
                double price = currentPrices.getOrDefault(asset, 0.0);
                
                // Utilisation de la constante de validation
                if (price > MIN_VALID_PRICE) {
                    lastForecast.put(asset, new PrevailingDirection(direction, price, now));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur parsing prévisions", e);
        }
    }
    
    private void checkForecastDivergence() {
        if (lastForecast.isEmpty()) return;
        List<String> assets = new ArrayList<>(lastForecast.keySet());
        if (!MarketDataFetcher.tryAcquireBatchSlot()) {
        Log.w(TAG, "[DIVERGENCE] Slot Twelve Data occupé — vérification divergence ignorée ce cycle");
        return;
        }
        Map<String, Double> currentPrices = MarketDataFetcher.getPrices(assets);
        long now = System.currentTimeMillis();
    
        for (Map.Entry<String, PrevailingDirection> entry : lastForecast.entrySet()) {
            String asset = entry.getKey();
            PrevailingDirection forecast = entry.getValue();
            Double currentPrice = currentPrices.get(asset);
            
            if (currentPrice == null || currentPrice <= 0) continue;
    
            // 1. Vérification atomique du cooldown
            // On utilise 'compute' pour garantir que l'accès et la mise à jour sont liés
            final boolean[] canAlert = {false};
            lastAlertsSent.compute(asset, (k, lastTime) -> {
                if (lastTime == null || (now - lastTime) >= ALERT_COOLDOWN_MS) {
                    canAlert[0] = true;
                    return now; // Met à jour le timestamp
                }
                return lastTime; // Trop tôt, ne change pas le timestamp
            });
    
            if (!canAlert[0]) continue;
    
            // 2. Calcul de la variation
            double changePercent = (currentPrice - forecast.referencePrice) / forecast.referencePrice * 100.0;
            boolean contradiction = (forecast.direction.equals("BULLISH") && changePercent < -DIVERGENCE_THRESHOLD) ||
                                    (forecast.direction.equals("BEARISH") && changePercent > DIVERGENCE_THRESHOLD);
    
            if (contradiction) {
                Log.w(TAG, "Divergence détectée pour " + asset + " : " + changePercent + "%");
    
                // 3. Déclenchement du scan — protégé par le même slot anti-429 que le reste
                // du pipeline (PreMarketScanner.scan() fait son propre appel getMarketDataBatch()
                // qui n'était pas couvert par le tryAcquireBatchSlot() de la ligne 240 ci-dessus).
                if (MarketDataFetcher.tryAcquireBatchSlot()) {
                    MarketDataFetcher.PreMarketScanner.scan(new MarketDataFetcher.PreMarketScanner.PreMarketCallback() {
                        @Override
                        public void onAlerte(String rapport, boolean isChoc) {
                            String alerteDiv = "🔄 *ALERTE DIVERGENCE MARCHÉ*\n" +
                                               "Actif : " + asset + " (" + String.format("%.2f", changePercent) + "%)\n" +
                                               "Nouvelle analyse pré-market :\n" + rapport;
                            sendTelegramSecure(alerteDiv, NotificationService.this);
                        }
                    });
                } else {
                    Log.w(TAG, "[DIVERGENCE] Slot Twelve Data occupé — scan pré-market ignoré ce cycle pour " + asset);
                }
    
                // 4. On supprime la prévision pour arrêter le monitoring sur cet actif 
                // tant qu'une nouvelle analyse IA (pipeline complet) n'a pas rafraîchi la donnée.
                lastForecast.remove(asset);
            }
        }
    }
    
    private static final String SYSTEM_PROMPT = "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif.\n" +
        "Tu analyses le flux d'actualité en appliquant une HIERARCHIE STRICTE DES DRIVERS.\n\n" +
        "MATRICE DE DOMINANCE (Priorité absolue) :\n" +
        "1. RANG SUPRÊME    : Politiques monétaires (FED, BCE, BoJ, BoE, RBA, BoC) et indicateurs clés (CPI, PCE, NFP, PPI, FOMC, GDP, ISM, Michigan Sentiment, PMI Flash, Ventes au détail, Chômage).\n" +
        "- Jerome Powell (Chair) : signal à suivre pour la direction long terme.\n" +
        "- Kevin Warsh (futur Chair pressenti) : signal à pondération MAXIMALE, traiter comme décision FOMC imminente.\n" + // <--- PATCH ICI
        "- Autres membres FOMC : à pondérer selon leur hawkish/dovish-ness habituel.\n" +
        "2. RANG SECONDAIRE : PIB/GDP, PMI, ISM, Ventes au détail, Stocks EIA, Stimulus Fiscal / Dépenses Publiques.\n" +
        "3. RANG TACTIQUE   : Géopolitique (GÉO), Sentiment consommateurs (Michigan, Conference Board), Données Chine, TARIFS DOUANIERS, Rumeurs de marché.\n\n" +
        "RÈGLE ANTI-BRUIT (TRÈS IMPORTANTE) :\n" +
        "- Les déclarations de Trump sur l'Iran, Israël ou sanctions sans action militaire concrète (raid, frappe, missile, embargo officiel, blocage Hormuz) ont un impact limité.\n" +
        "- Ne transforme JAMAIS une simple déclaration diplomatique ou répétition de news en choc majeur.\n" +
        "- Un événement Géo doit comporter une action concrète ou une mesure officielle forte pour justifier un impact élevé.\n" +
        "- Les nouvelles sur des 'accords pour rouvrir Hormuz', 'discussions', 'possibilités d'apaisement' = baisse de tension → impact RISK-ON modéré, conviction plafonnée à 45%.\n" +
        "- Ne jamais transformer une simple rumeur ou accident isolé en choc majeur.\n" +
        "- ₿ BITCOIN : Actif amplificateur, pas initiateur. Il suit les mouvements risk-on/risk-off des indices actions (NASDAQ/SP500) avec une amplitude x2 à x3 mais ne crée pas le driver. Ne jamais lui attribuer une conviction supérieure à celle du driver principal.\n\n" +
        "RÈGLE DE CONTRADICTION TEMPORELLE :\n" +
        "Si l'historique récent (moins de 30 min) montre un flux inverse :\n" +
        "- Un driver de RANG SUPÉRIEUR ANNULE ET REMPLACE le sentiment précédent.\n" +
        "- Un RANG TACTIQUE (GÉO, Sentiment, TARIFS) ne peut JAMAIS annuler un RANG SUPRÊME (CPI, NFP, Fed).\n" +
        "- En cas de coexistence impossible, signale les deux drivers sans forcer l'arbitrage.\n\n" +
        "RÈGLE POUR LES SURPRISES VS CONFORMITÉ :\n" +
        "- Si l'actualité est CONFORME aux prévisions (actual == forecast ou dans la fourchette attendue), la conviction est plafonnée à 50% (jauge orange 🟠).\n" +
        "- Dans ce cas, utilise exclusivement les mentions 'INCLINATION BULLISH MAIS NEUTRE' ou 'INCLINATION BEARISH MAIS NEUTRE' sur les actifs concernés.\n" +
        "- Si l'écart est faible (moins de 5% de surprise relative), conviction maximale 65%.\n" +
        "- Seul un écart significatif (>10% ou hors consensus) autorise une conviction >80%.\n\n" +
        "════════════════════════════════════════════════════════\n" +
        " RÈGLES DE DIRECTIONNALITÉ INTER-MARCHÉS — EXHAUSTIVES\n" +
        "════════════════════════════════════════════════════════\n\n" +
        "A. NEWS ÉTATS-UNIS — POLITIQUE MONÉTAIRE / CPI / NFP\n" +
        "───────────────────────────────────────────────────────\n" +
        "   HAWKISH US (CPI > prévisions, NFP fort, Fed hawkish, nomination hawkish) :\n" +
        "   • 📈 US10Y    : BULLISH 🟢  | Rendements montent avec les anticipations de hausse\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢  | Dollar fort face au CAD\n" +
        "   • 🇯🇵 USDJPY : BULLISH 🟢  | Dollar fort face au Yen ← TOUJOURS BULLISH sur HAWKISH US\n" +
        "   • 🏆 GOLD    : BEARISH 🔴  | Dollar fort pénalise l'or\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Taux hauts compressent les valorisations tech\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Actif risk-on pénalisé par le resserrement (effet amplifié x2 à x3)\n" +
        "   • 🇪🇺 EURUSD : BEARISH 🔴  | Dollar fort écrase l'Euro\n" +
        "   • 🇬🇧 GBPUSD : BEARISH 🔴  | Dollar fort écrase la Livre\n" +
        "   • 🇦🇺 AUDUSD : BEARISH 🔴  | Devise risk-on pénalisée\n" +
        "   • 🛢️ USOIL   : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FORT (MKT RISK-OFF) 🐻\n\n" +
        "   DOVISH US (CPI < prévisions, NFP faible, Fed dovish, anticipation de baisses de taux) :\n" +
        "   • 📈 US10Y    : BEARISH 🔴  | Rendements baissent avec les anticipations de baisse\n" +
        "   • 🇨🇦 USDCAD : BEARISH 🔴  | Dollar faible face au CAD\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Dollar faible face au Yen ← TOUJOURS BEARISH sur DOVISH US\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Dollar faible propulse l'or\n" +
        "   • 💻 NASDAQ  : BULLISH 🟢  | Taux bas soutiennent les valorisations tech\n" +
        "   • 📊 SP500   : BULLISH 🟢  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BULLISH 🟢  | Liquidité favorable aux actifs risk-on (effet amplifié x2 à x3)\n" +
        "   • 🇪🇺 EURUSD : BULLISH 🟢  | Dollar faible renforce l'Euro\n" +
        "   • 🇬🇧 GBPUSD : BULLISH 🟢  | Dollar faible renforce la Livre\n" +
        "   • 🇦🇺 AUDUSD : BULLISH 🟢  | Devise risk-on bénéficie du Dollar faible\n" +
        "   • 🛢️ USOIL   : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FAIBLE (MKT RISK-ON) 🐂\n\n" +
        "   CAS MIXTE (ex: CPI core baisse mais headline monte) :\n" +
        "   Utilise le composant le plus surveillé par la Fed (Core > Headline).\n" +
        "   Signale la divergence dans le FAIT MARQUANT. Conviction plafonnée à 65%.\n\n" +
        "B. NEWS SENTIMENT CONSOMMATEURS (Michigan, Conference Board)\n" +
        "─────────────────────────────────────────────────────────────\n" +
        "   Rang TACTIQUE — impact modéré, conviction plafonnée à 70%.\n" +
        "   Sentiment BAS (< prévisions) → Signal DOVISH modéré :\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Crainte de ralentissement de la consommation\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Refuge en cas de pessimisme économique\n" +
        "   • 📈 US10Y    : NEUTRE          | Pas de signal monétaire direct\n" +
        "   • 🇯🇵 USDJPY : NEUTRE          | Pas de choc suffisant pour déplacer le Yen\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴  | Demande anticipée en baisse\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Actif risk-on pénalisé (amplitude corrélée aux indices)\n" +
        "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇦🇺 AUDUSD, 🇨🇦 USDCAD : NEUTRE\n" +
        "   🏁 FLUX DOMINANT : RISK-OFF MODÉRÉ (MKT INCERTAIN) ⚠️\n\n" +
        "   Sentiment HAUT (> prévisions) → Signal HAWKISH modéré :\n" +
        "   Inverser toutes les directions ci-dessus.\n" +
        "   🏁 FLUX DOMINANT : RISK-ON MODÉRÉ (MKT CONFIANT) 🐂\n\n" +
        "C. NEWS BANQUES CENTRALES ÉTRANGÈRES (BoJ, BCE/ECB, BoE, RBA, BoC)\n" +
        "────────────────────────────────────────────────────────────────\n" +
        "   VERROU MONÉTAIRE ABSOLU : La devise locale réagit exclusivement à sa propre banque centrale.\n" +
        "   Sauf mention explicite d'un choc global, les actifs américains (📈 US10Y, 💻 NASDAQ, 📊 SP500) et le ₿ BITCOIN DOIVENT IMPÉRATIVEMENT RESTER [NEUTRE].\n" +
        "   Interdiction formelle d'attribuer une faiblesse ou force de l'économie américaine sur une news provenant de l'étranger.\n\n" +
        "   DOVISH étranger → La devise locale s'effondre, provoquant une hausse mécanique du Dollar américain par effet de flux (Dollar Fort par différentiel) :\n" +
        "   • 🇪🇺 BCE/ECB DOVISH  → 🇪🇺 EURUSD: BEARISH 🔴 | 🇬🇧 GBPUSD: BEARISH 🔴 | 🇦🇺 AUDUSD: BEARISH 🔴 | 🇨🇦 USDCAD: BULLISH 🟢 | 🇯🇵 USDJPY: BULLISH 🟢 | 🛢️ USOIL: BEARISH 🔴 | 🏆 GOLD: NEUTRE\n" +
        "   • 🇯🇵 BoJ DOVISH      → 🇯🇵 USDJPY: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇨🇦 BoC DOVISH      → 🇨🇦 USDCAD: BULLISH 🟢 | 🛢️ USOIL: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇬🇧 BoE DOVISH      → 🇬🇧 GBPUSD: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇦🇺 RBA DOVISH      → 🇦🇺 AUDUSD: BEARISH 🔴 | 🛢️ USOIL: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FAIBLE / DOLLAR FORT par différentiel 🐻\n\n" +
        "   HAWKISH étranger → La devise locale explose, provoquant une baisse mécanique du Dollar américain par effet de flux (Dollar Faible par différentiel) :\n" +
        "   • 🇪🇺 BCE/ECB HAWKISH → 🇪🇺 EURUSD: BULLISH 🟢 | 🇬🇧 GBPUSD: BULLISH 🟢 | 🇦🇺 AUDUSD: BULLISH 🟢 | 🇨🇦 USDCAD: BEARISH 🔴 | 🇯🇵 USDJPY: BEARISH 🔴 | 🛢️ USOIL: BULLISH 🟢 | 🏆 GOLD: NEUTRE\n" +
        "   • 🇯🇵 BoJ HAWKISH     → 🇯🇵 USDJPY: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇨🇦 BoC HAWKISH     → 🇨🇦 USDCAD: BEARISH 🔴 | 🛢️ USOIL: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇬🇧 BoE HAWKISH     → 🇬🇧 GBPUSD: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇦🇺 RBA HAWKISH     → 🇦🇺 AUDUSD: BULLISH 🟢 | 🛢️ USOIL: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FORTE / DOLLAR FAIBLE par différentiel 🐂\n\n" +
        "   NOTE CANADA/PÉTROLE : Le CAD et USOIL sont corrélés. BoC HAWKISH = économie forte = demande pétrolière = USOIL BULLISH. BoC DOVISH = économie faible = USOIL BEARISH.\n\n" +
        "D. GÉO — STIMULUS MILITAIRE / DÉPENSES DE DÉFENSE EUROPÉENNES (OTAN, 2% PIB)\n" +
        "─────────────────────────────────────────────────────────────────────────────\n" +
        "   VECTEUR = LIQUIDITÉ. C'est un stimulus fiscal localisé (relance budgétaire) sur l'Europe.\n" +
        "   • 🇪🇺 EURUSD : BULLISH 🟢  | Soutien budgétaire et relance de l'économie européenne\n" +
        "   • 🇬🇧 GBPUSD : BULLISH 🟢  | Alignement stratégique des dépenses de l'OTAN / UK\n" +
        "   • 🛢️ USOIL   : BULLISH 🟢  | Augmentation mécanique de la demande d'énergie militaire\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Le Yen s'apprécie comme actif refuge face aux incertitudes budgétaires\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Crainte d'inflation par creusement du deficit budgétaire public\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Risk-off immédiat sur les actifs spéculatifs (liquidation forcée)\n" +
        "   • 📈 US10Y    : NEUTRE\n" +
        "   • 🏆 GOLD    : NEUTRE\n" +
        "   • 🇨🇦 USDCAD : BEARISH 🔴  | Effet de flux : le Dollar fléchit face aux devises européennes/refuges\n" +
        "   • 🇦🇺 AUDUSD : BULLISH 🟢  | Devise cyclique soutenue par l'injection globale de liquidité fiscale\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : EURO FORT / YEN FORT (MKT RISK-OFF) 🐻\n\n" +
        "E1. GÉO — CONFLITS / PANIQUE / MOYEN-ORIENT / CHINE\n" +
        "────────────────────────────────────────────────────\n" +
        "   VECTEUR = GÉO. RISK-OFF classique, fuite vers les refuges.\n" +
        "   CHOC GÉOPOLITIQUE / ESCALADE :\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Refuge universel absolu\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Le Yen s'apprécie comme refuge supérieur au dollar (le graphique baisse)\n" +
        "   • 🛢️ USOIL   : BULLISH 🟢  | Si Moyen-Orient / Detroit d'Ormuz impliqué (menace sur l'offre)\n" +
        "                  NEUTRE          | Si conflit local sans aucun impact sur les routes pétrolières\n" +
        "   • 🇦🇺 AUDUSD : BEARISH 🔴  | Devise risk-on fortement pénalisée en RISK-OFF\n" +
        "   • 🇨🇦 USDCAD : [BULLISH 🟢 si USOIL NEUTRE] / [NEUTRE si USOIL BULLISH] | Justification selon la divergence pétrole/cad. Mentionner obligatoirement la divergence dans le FAIT MARQUANT.\n" +
        "   • 🇪🇺 EURUSD : BEARISH 🔴  | L'Euro subit le choc de l'instabilité internationale\n" +
        "   • 🇬🇧 GBPUSD : BEARISH 🔴  | La Livre subit la baisse générale de l'aversion au risque\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Les marchés actions capitulent face à l'incertitude\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Retrait immédiat des capitaux des actifs spéculatifs\n" +
        "   • 📈 US10Y   : BEARISH 🔴  | Fuite vers la qualité (les investisseurs achètent des obligations, les taux baissent)\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : YEN FORT / OR FORT (MKT RISK-OFF) 🐻\n\n" +
        "   DÉSESCALADE MOYEN-ORIENT (Discussions, Accords, Trêve) :\n" +
        "   Impact modéré, conviction plafonnée à 45%.\n" +
        "   • 🏆 GOLD    : BEARISH 🔴  | Sortie des refuges\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴  | Prime de risque géopolitique s'efface sur le brut\n" +
        "   • 💻 NASDAQ  : BULLISH 🟢  | Soulagement des indices actions\n" +
        "   • 📊 SP500   : BULLISH 🟢  | Même direction que NASDAQ — obligatoire\n" +
        "   • 🇯🇵 USDJPY : BULLISH 🟢  | Le Yen capitule comme refuge\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢  | Pétrole baisse = le CAD s'affaiblit mécaniquement face au USD\n" +
        "   • 🇦🇺 AUDUSD : BULLISH 🟢  | Retour de l'appétit pour le risque sur les devises cycliques\n" +
        "   • ₿ BITCOIN  : BULLISH 🟢  | Retour des flux spéculatifs (amplitude x2 à x3 par rapport aux actions)\n" +
        "   • 📈 US10Y, 🇪🇺 EURUSD, 🇬🇧 GBPUSD : NEUTRE | Retrait ordonné des capitaux sans panique\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : RISK-ON RETOUR (MKT APPAISÉ) 🐂\n\n" +
        "F. STOCKS PÉTROLE EIA / OPEC\n" +
        "─────────────────────────────\n" +
        "   Rang SECONDAIRE. Impact principal sur USOIL et CAD.\n" +
        "   Stocks EIA > prévisions (surplus) → offre excédentaire :\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢  | Le CAD s'affaiblit en corrélation directe avec la chute du brut\n" +
        "   • Tous les autres actifs : NEUTRE\n" +
        "   Stocks EIA < prévisions (déficit) → tension sur l'offre :\n" +
        "   • 🛢️ USOIL   : BULLISH 🟢\n" +
        "   • 🇨🇦 USDCAD : BEARISH 🔴  | Le CAD se renforce en même temps que le pétrole grimpe\n" +
        "   • Tous les autres actifs : NEUTRE\n\n" +
        "G. TARIFS DOUANIERS (Chine, UE, USA, etc.)\n" +
        "────────────────────────────────────────────\n" +
        "   Rang TACTIQUE, impact modéré à élevé selon l'ampleur. Conviction plafonnée à 70%.\n" +
        "   Annonce de SURTAXE / GUERRE COMMERCIALE (ex: +25% sur produits chinois) :\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Crainte sur les chaînes d'approvisionnement tech\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • 🇨🇳 AUDUSD : BEARISH 🔴  | Devise proxy de la Chine, fortement pénalisée\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴  | Anticipation de ralentissement de la demande mondiale\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Yen refuge s'apprécie (le graphique baisse)\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Valeur refuge\n" +
        "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇨🇦 USDCAD : NEUTRE\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Risk-off sur actifs spéculatifs\n" +
        "   • 📈 US10Y    : NEUTRE\n" +
        "   🏁 FLUX DOMINANT : RISK-OFF / YEN FORT / OR FORT 🐻\n\n" +
        "   DÉSESCALADE TARIFAIRE (suspension, baisse, accord) :\n" +
        "   Inverser toutes les directions ci-dessus, conviction plafonnée à 50%.\n" +
        "   🏁 FLUX DOMINANT : RISK-ON / APPÉTIT POUR LE RISQUE 🐂\n\n" +
        "H. ÉVÉNEMENTS SPÉCIAUX (IPO, SPAC, MERGERS)\n" +
        "─────────────────────────────────────────────\n" +
        "   Rang TACTIQUE. Impact basé sur le sentiment de marché (Hype vs Risk-On).\n" +
        "   Annonce IPO majeure (ex: SpaceX) ou Fusion/Acquisition géante :\n\n" +
        "   • 💻 NASDAQ  : BULLISH 🟢 | Hype et injection de liquidité sectorielle.\n" +
        "   • 📊 SP500   : BULLISH 🟢 | Même direction que NASDAQ — obligatoire.\n" +
        "   • ₿ BITCOIN  : BULLISH 🟢 | Actif corrélé positivement à l'appétit pour le risque.\n" +
        "   • 🏆 GOLD    : BEARISH 🔴 | Délaissé au profit des actifs à rendement plus élevé.\n" +
        "   • 📈 US10Y   : NEUTRE      | Pas d'impact direct.\n" +
        "   • 🛢️ USOIL   : NEUTRE      | Pas d'impact direct sauf si lié à l'énergie.\n" +
        "   • 🇯🇵 USDJPY  : BEARISH 🔴 | Yen délaissé (Risk-On).\n" +
        "   • 🇪🇺 EURUSD  : BULLISH 🟢 | Dollar faible par appétit pour le risque.\n" +
        "   • 🇬🇧 GBPUSD  : BULLISH 🟢 | Dollar faible par appétit pour le risque.\n" +
        "   • 🇦🇺 AUDUSD  : BULLISH 🟢 | Devise cyclique soutenue.\n" +
        "   • 🇨🇦 USDCAD  : BEARISH 🔴 | Dollar faible face aux devises cycliques.\n\n" +
        "   🏁 FLUX DOMINANT : RISK-ON / APPÉTIT POUR LE RISQUE 🐂\n\n" +
        "<HARD_CONSTRAINTS>\n" +
        "CONTRAINTE 1 — SECTIONS INTERDITES :\n" +
        "   N'écris JAMAIS 'TIMING D'EFFET', 'ACTION TRADING', 'CONTEXTE' ou toute autre section\n" +
        "   absente du FORMAT DE SORTIE ci-dessous. STRICTEMENT INTERDIT.\n\n" +
        "CONTRAINTE 2 — ÉMOJI UNIQUE :\n" +
        "   Le symbole '📢' est STRICTEMENT RÉSERVÉ au seul 'FAIT MARQUANT'. Un seul et unique '📢' par réponse.\n\n" +
        "CONTRAINTE 3 — SYMÉTRIE NASDAQ/SP500 ABSOLUE :\n" +
        "   💻 NASDAQ et 📊 SP500 ont TOUJOURS exactement la même directionnalité (BULLISH, BEARISH ou Neutre).\n" +
        "   Aucune exception tolérée.\n\n" +
        "CONTRAINTE 4 — COHÉRENCE USDJPY / FLUX DOMINANT :\n" +
        "   - Si 🇯🇵 USDJPY est en BEARISH 🔴 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'DOLLAR FORT'.\n" +
        "   - Si 🇯🇵 USDJPY est en BULLISH 🟢 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'YEN FORT'.\n" +
        "   - En cas de contradiction, le flux doit mentionner YEN FORT si USDJPY est en BEARISH.\n\n" +
        "CONTRAINTE 5 — JAUGE CONVICTION OBLIGATOIRE :\n" +
        "   Tu dois obligatoirement générer la jauge visuelle d'émojis avant le pourcentage. Format strict :\n" +
        "   📊 CONVICTION : [EMOJIS] XX%\n" +
        "   Génération selon les paliers suivants :\n" +
        "   - Pourcentage < 40%  → ⚪⚪⚪⚪⚪\n" +
        "   - Pourcentage 41-60% → 🟠🟠🟠⚪⚪\n" +
        "   - Pourcentage 61-80% → 🟡🟡🟡🟡⚪\n" +
        "   - Pourcentage > 81%  → 🔴🔴🔴🔴🔴\n" +
        "   Exemple valide : 📊 CONVICTION : 🟡🟡🟡🟡⚪ 75%\n\n" +
        "CONTRAINTE 6 — INTERDICTION D'OMISSION :\n" +
        "   Les 11 actifs listés dans le FORMAT DE SORTIE doivent TOUS apparaître explicitement dans la réponse,\n" +
        "   sans aucune exception, même s'ils reçoivent la mention NEUTRE ou une INCLINATION.\n\n" +
        "CONTRAINTE 7 — SÉCURITÉ BANQUES CENTRALES ÉTRANGÈRES (BASÉE SUR LE CONTENU) :\n" +
        "   - Si le texte du flux (le contenu) mentionne une banque centrale étrangère (BCE, ECB, BOJ, BOE, RBA, BOC), tu as l'INTERDICTION ABSOLUE de mettre BULLISH, BEARISH ou toute INCLINATION sur NASDAQ, SP500, US10Y et BITCOIN. Ils doivent obligatoirement être marqués [NEUTRE] avec la raison exacte suivante : \"Pas d'impact direct – actif américain / crypto\".\n" +
        "   - Quelle que soit la source ou l'émetteur de la notification (Twitter, FinancialJuice, etc.), c'est la nature du contenu textuel qui déclenche cette règle.\n" +
        "   - RÈGLE DE DIRECTIONNALITÉ DE LA DEVISE LOCALE :\n" +
        "      * Banque centrale étrangère DOVISH (baisse des taux, ton accommodant) -> sa devise locale baisse face au USD. Exemple strict : BCE DOVISH = EURUSD BEARISH 🔴. (Mettre BULLISH est une erreur éliminatoire).\n" +
        "      * Banque centrale étrangère HAWKISH (hausse des taux, ton restrictif) -> sa devise locale monte face au USD. Exemple strict : BCE HAWKISH = EURUSD BULLISH 🟢.\n" +
        "   - Les autres paires de devises (GBPUSD, AUDUSD, USDJPY, USDCAD) et actifs (GOLD, USOIL) se conforment strictement aux directives de flux et de corrélation de la RÈGLE C (Différentiel de taux / effet dollar), ou restent [NEUTRE] s'ils ne sont pas mentionnés.\n" +
        "   - ⚠️ TOUTE INFRACTION À CETTE RÈGLE (ex: BCE HAWKISH → EURUSD BEARISH) ENTRAÎNE LE REJET AUTOMATIQUE DE LA RÉPONSE. CETTE RÈGLE PRÉVAUT SUR TOUTE AUTRE CONSIDÉRATION.\n\n" +
        "CONTRAINTE 7B — SÉCURITÉ FORMATAGE TELEGRAM :\n" +
        "   - Il est STRICTEMENT INTERDIT d'utiliser des doubles astérisques (**) pour mettre du texte en gras. Seul l'astérisque simple (*) ou les crochets sont autorisés pour éviter de corrompre le formatage Telegram.\n\n" +
        "CONTRAINTE 8 — COMPLÉTUDE ABSOLUE DE LA MATRICE :\n" +
        "   - Tu dois obligatoirement copier-coller la liste complète des 11 actifs dans l'ordre exact du format de sortie. Aucune ligne ne peut être omise ou supprimée, sous aucun prétexte.\n" +
        "   - Si un actif n'est pas directement touché ou doit rester neutre par application de la CONTRAINTE 7, sa mention réglementaire stricte doit être : `NEUTRE | Pas d'impact direct de ce driver.` (ou la raison spécifique exigée par la contrainte 7).\n" +
        "   - Cette règle de complétude prévaut sur toute logique de concision.\n\n" +
        "CONTRAINTE 9 — NOMBRE EXACT DE LIGNES D'IMPACTS :\n" +
        "   ⚠️ TOUTE RÉPONSE DOIT CONTENIR EXACTEMENT 11 LIGNES D’IMPACTS (une par actif), même si l'actif est neutre.\n" +
        "   Aucune ligne ne peut être omise, supprimée ou ajoutée. Le non-respect de cette règle entraîne le rejet automatique de la réponse.\n\n" +
       "CONTRAINTE 10 — VALEUR EXACTE DU VECTEUR CIBLE :\n" +
        "Le champ 🎯 VECTEUR CIBLE doit être choisi UNIQUEMENT parmi : HAWKISH, DOVISH, GÉO, LIQUIDITÉ, CHINE, TARIFS, IPO.\n" +
        "Toute autre valeur est interdite et invalide la réponse.\n" +
        "   La réponse doit utiliser exactement un de ces six termes, sans ajout ni modification.\n\n" +
        "CONTRAINTE 11 — HIÉRARCHIE ABSOLUE ET EXCEPTION DE CRISE :\n" +
        "   - En règle générale, le RANG SUPRÊME (Politique Monétaire, CPI, PCE) l'emporte sur le RANG TACTIQUE (GÉO).\n" +
        "   - ⚠️ EXCEPTION ABSOLUE (RÉGIME DE GUERRE) : Si le flux fait état d'une ESCALADE MILITAIRE DIRECTE ou MENACE SUR L'OFFRE (ex: Hormuz, frappes US-Iran), le driver GÉO devient PRIORITAIRE sur l'Inflation pour l'Or et le Pétrole.\n" +
        "   - Alignement obligatoire de la matrice des 11 actifs dans ce cas précis :\n" +
        "      * 🏆 GOLD    : BULLISH 🟢 [Flux refuge dominant]\n" +
        "      * 🛢️ USOIL   : BULLISH 🟢 [Prime de risque sur l'offre]\n" +
        "      * 📈 US10Y   : BULLISH 🟢 [PCE Hawkish / Taux sous pression]\n" +
        "      * 💻 NASDAQ  : BEARISH 🔴 [Double flux négatif : Taux hauts + Risk-Off]\n" +
        "      * 📊 SP500   : BEARISH 🔴 [Strictement identique au NASDAQ]\n" +
        "      * ₿ BITCOIN  : BEARISH 🔴 [Capitulation des actifs spéculatifs]\n" +
        "      * 🇪🇺 EURUSD  : BEARISH 🔴 [Dollar fort + Proximité du choc géo]\n" +
        "      * 🇬🇧 GBPUSD  : BEARISH 🔴 [Dollar fort par arbitrage]\n" +
        "      * 🇦🇺 AUDUSD  : BEARISH 🔴 [Liquidation de la devise cyclique/commodity non-pétrole]\n" +
        "      * 🇯🇵 USDJPY  : BEARISH 🔴 [Régime de dominance géopolitique – Yen refuge prioritaire]\n" +
        "      * 🇨🇦 USDCAD  : NEUTRE ou BEARISH 🔴 [Le choc USOIL haussier compense et annule la force du Dollar. Préciser l'arbitrage].\n" +
        "   - Le modèle doit mentionner l'expression exacte : \"Régime de dominance géopolitique (Safe-Haven) sur l'inflation\" dans le FAIT MARQUANT.\n\n" +
        "</HARD_CONSTRAINTS>\n\n" +
        "EXEMPLE D'APPLICATION (INDÉPENDANT DE LA SOURCE) :\n" +
        "   Si l'actualité dit : \"BCE dovish, Schnabel s'inquiète de la croissance européenne\", la réponse DOIT copier l'intégralité des 11 lignes ainsi :\n" +
        "   • 📈 US10Y    : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
        "   • 💻 NASDAQ  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
        "   • 📊 SP500   : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
        "   • 🏆 GOLD    : NEUTRE | Pas d'impact direct de ce driver.\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴 | BCE dovish -> baisse de l'activité économique de la zone euro pénalisant le brut.\n" +
        "   • 🇪🇺 EURUSD : BEARISH 🔴 | BCE dovish -> baisse et affaiblissement de l'euro.\n" +
        "   • 🇯🇵 USDJPY : BULLISH 🟢 | Hausse mécanique par différentiel (Dollar Fort face au Yen).\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢 | Hausse mécanique par différentiel (Dollar Fort face au CAD).\n" +
        "   • 🇬🇧 GBPUSD : BEARISH 🔴 | Baisse mécanique par différentiel (Dollar Fort écrase la Livre).\n" +
        "   • 🇦🇺 AUDUSD : BEARISH 🔴 | Baisse mécanique par différentiel (Dollar Fort écrase l'Aussie).\n" +
        "   • ₿ BITCOIN  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n\n" +
        "FORMAT DE SORTIE STRICT ET OBLIGATOIRE :\n" +
        "🚨 [NOM DE L'EMETTEUR OU SOURCE]\n" +
        "🕒 [Insère ici la date et l'heure fournies dans le CONTEXTE TEMPOREL au début du message] (Mada)\n" +
        "📊 CONVICTION : [JAUGE_EMOJIS] XX%\n" +
        "🎯 VECTEUR CIBLE : [HAWKISH / DOVISH / GÉO / LIQUIDITÉ / CHINE / TARIFS]\n" +
        "📢 FAIT MARQUANT : [Analyse pro de la situation en français. Mentionner l'arbitrage si écrasement d'un driver récent ou divergence.]\n\n" +
        "--- IMPACTS ACQUISITION ---\n" +
        "⚠️ EXIGENCE DE DYNAMISME ANALYTIQUE : Interdiction absolue d'utiliser des raisons théoriques standardisées ou de répéter la même justification d'une ligne à l'autre. Chaque raison DOIT lier explicitement l'actif concerné aux données CONCRÈTES, FACTUELLES ou CHIFFRÉES contenues dans le texte du flux.\n\n" +
        "• 📈 US10Y    : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 💻 NASDAQ  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 📊 SP500   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🏆 GOLD    : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🛢️ USOIL   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇪🇺 EURUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇯🇵 USDJPY : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇨🇦 USDCAD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇬🇧 GBPUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇦🇺 AUDUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• ₿ BITCOIN  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n\n" +
        "🏁 FLUX DOMINANT : [Chaîne de caractères exacte issue des règles de directionnalité]";

    private String getGroqApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GROQ_KEY, "");
    }
    private boolean estEvenementSuprême(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("FOMC") || upper.contains("FED ") ||
               upper.contains("CPI")  || upper.contains("PCE")  ||
               upper.contains("NFP")  || upper.contains("BCE")  ||
               upper.contains("ECB")  || upper.contains("BOJ")  ||
               upper.contains("BOE")  || upper.contains("RBA")  ||
               upper.contains("BOC")  || upper.contains("PIB")  ||
               upper.contains("GDP")  || upper.contains("OPEC") ||
               upper.contains("INFLATION") || upper.contains("INTEREST RATE") ||
               upper.contains("POWELL") || upper.contains("LAGARDE") ||
               upper.contains("PMI") || upper.contains("ISM");
    }

    private Calendar getMadaCalendar() {
      return Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
    }
    private long calculateMillisUntilNextMadaMidnight() {
        Calendar madaNow = getMadaCalendar(); 
        
        Calendar madaMidnight = (Calendar) madaNow.clone();
        madaMidnight.set(Calendar.HOUR_OF_DAY, 0);
        madaMidnight.set(Calendar.MINUTE, 0);
        madaMidnight.set(Calendar.SECOND, 0);
        madaMidnight.set(Calendar.MILLISECOND, 0);
        
        if (madaNow.after(madaMidnight)) {
            madaMidnight.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        return madaMidnight.getTimeInMillis() - madaNow.getTimeInMillis();
    }
    // === AJOUT À FAIRE ===
    private final ExecutorService tradingPipelineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;
    private volatile boolean isSyncing = false;
    private static final String PREF_LAST_DAILY_REPORT = "last_daily_report_";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    // Volatile pour la cohérence multi-thread (Point 7)
    private volatile long lastSpeechTime = 0;
    private volatile String lastSpeaker = "";

    private int extrairePourcentageConviction(String aiReport) {
        Pattern p = Pattern.compile("CONVICTION\\s*:\\s*.*?(\\d{1,3})%");
        Matcher m = p.matcher(aiReport);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static String injectLivePrices(String groqReport, List<String> assets,
        Map<String, MarketDataFetcher.MarketData> cachedData) {
    if (groqReport == null || groqReport.isEmpty() || assets == null || assets.isEmpty()) 
        return groqReport;

        try {
        
        // Réutilise le cache si disponible, sinon appel réseau en dernier recours
        Map<String, MarketDataFetcher.MarketData> liveDataMap = null;
        if (cachedData != null && !cachedData.isEmpty()) {
            liveDataMap = cachedData; // Cache disponible — 0 appel réseau
        } else if (MarketDataFetcher.tryAcquireBatchSlot()) {
            liveDataMap = MarketDataFetcher.getMarketDataBatch(assets); // Fallback réseau
        } else {
            Log.w(TAG, "[INJECT] Slot occupé — injectLivePrices ignoré ce cycle");
        }
        if (liveDataMap == null || liveDataMap.isEmpty()) return groqReport;

        String[] lignes = groqReport.split("\n");
        StringBuilder reportAjuste = new StringBuilder();

        for (String ligne : lignes) {
            String ligneModifiee = ligne;
            
            for (String asset : assets) {
                // Regex blindée contre les légères variations de mise en forme de l'IA
                String patternStr = "^\\s*[•\\-*]?\\s*\\S*\\s*" + Pattern.quote(asset) + "\\s*:.*";
                
                if (ligne.matches(patternStr)) {
                    MarketDataFetcher.MarketData data = liveDataMap.get(asset);
                    if (data != null && data.price > 0) {
                        String sign = (data.changePercent >= 0) ? "+" : "";
                        String badgeMarche = String.format(Locale.US,
                            " (%.4f | %s%.2f%%)", data.price, sign, data.changePercent);
                        
                        // Insertion propre juste avant les deux-points explicatifs
                        int indexColon = ligne.indexOf(":");
                        if (indexColon != -1) {
                            ligneModifiee = ligne.substring(0, indexColon) + badgeMarche + " :" + ligne.substring(indexColon + 1);
                        } else {
                            ligneModifiee = ligne + badgeMarche;
                        }
                    }
                    break; // Un seul actif traitable par ligne de liste
                }
            }
            reportAjuste.append(ligneModifiee).append("\n");
        }
        return reportAjuste.toString().trim();
    } catch (Exception e) {
        Log.e(TAG, "Erreur lors de l'injection des prix live", e);
        return groqReport;
    }
}

private void processAnalysisWithAI(String sourceName, String title, String body, List<String> enrichedAssets, String fingerprint, String customSystemPrompt, boolean isSupremeRank,
    Map<String, MarketDataFetcher.MarketData> cachedMarketData){// ← AJOUT
    final String systemPrompt = (customSystemPrompt != null && !customSystemPrompt.isEmpty())
       ? customSystemPrompt
       : SYSTEM_PROMPT
    ;
    // 2. Génération dynamique de l'horodatage actuel au format de Madagascar (EAT)
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE);
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
    String currentMadaTime = sdf.format(new java.util.Date());

    // Sécurisation anti-NullPointerException de la liste des actifs
    String assetsString = (enrichedAssets != null) ? enrichedAssets.toString() : "[]";

    // ✅ CORRECTION 1 : Rendre 'userContent' FINAL pour permettre sa lecture sécurisée dans le Thread d'arrière-plan
    final String userContent = "CONTEXTE TEMPOREL : " + currentMadaTime + "\n"
            + "SOURCE DE LA NEWS : " + sourceName + "\n"
            + "TITRE : " + title + "\n"
            + "CORPS DE LA NOTIFICATION : " + body + "\n"
            + "ACTIFS PRÉ-QUALIFIÉS : " + assetsString;

    Executors.newSingleThreadExecutor().execute(new Runnable() {
        @Override
        public void run() {
            java.net.HttpURLConnection conn = null;
            EventDatabase db = EventDatabase.getInstance(NotificationService.this);
            if (db == null || fingerprint == null) {
                Log.e(TAG, "Instance SQLite ou empreinte manquante. Avortement du pipeline.");
                return;
            }
    
            try {
                List<String> historique = db.obtenirTexteEvenementsRecents();
                String promptFinal = construirePromptFinalAvecPrompt(body, historique, systemPrompt);
                JSONObject jsonPayload = new JSONObject();
                jsonPayload.put("model", GROQ_MODEL);
                jsonPayload.put("temperature", 0.02);
    
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", promptFinal));
                messages.put(new JSONObject().put("role", "user").put("content", userContent));
                jsonPayload.put("messages", messages);
    
                String apiKey = getGroqApiKey();
                if (apiKey.isEmpty()) {
                    Log.e(TAG, "[GROQ] Clé API absente. Analyse annulée.");
                    db.markEventAsSynced(fingerprint, "FAILED_MISSING_API_KEY");
                    return;
                }
    
                java.net.URL url = new java.net.URL(GROQ_URL);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
    
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }
    
                int status = conn.getResponseCode();
                if (status == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder response = new StringBuilder();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }
    
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String aiReport = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
    
                    if (aiReport == null || aiReport.length() < 50) {
                        Log.w(TAG, "[GROQ] Rapport reçu trop court ou vide.");
                        db.markEventAsSynced(fingerprint, "FAILED_EMPTY_LLM_REPORT");
                        return;
                    }
    
                    // Filtrage intelligent des signaux d'impacts macroéconomiques
                    StringBuilder filteredMessage = new StringBuilder();
                    String[] lines = aiReport.split("\n");
                    int activeSignalsCount = 0;
                    boolean inImpactSection = false;
    
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        if (trimmed.startsWith("🚨") || trimmed.startsWith("📊") || trimmed.startsWith("🎯") ||
                            trimmed.startsWith("📢") || trimmed.startsWith("🏁") || trimmed.startsWith("--- IMPACTS")) {
                            filteredMessage.append(line).append("\n");
                            if (trimmed.startsWith("--- IMPACTS")) inImpactSection = true;
                            continue;
                        }
                        if (inImpactSection && trimmed.startsWith("•")) {
                            String upperLine = line.toUpperCase(Locale.ROOT);
                            boolean isInclinationNeutral = upperLine.contains("MAIS NEUTRE");
                            boolean isSignificant = !isInclinationNeutral &&
                             (upperLine.contains("BULLISH") || upperLine.contains("BEARISH"));
                                if (isSignificant) {
                                    filteredMessage.append(line).append("\n");
                                    activeSignalsCount++;
                                }
                        }
                    }
    
                    // ✅ Application du filtre conviction
                    // APRÈS
                    if (activeSignalsCount > 0) {
                    int convictionPercent = extrairePourcentageConviction(aiReport);
                      if (convictionPercent >= 40 || isSupremeRank) {
                            // ✅ Injection des prix live inline sur chaque ligne d'actif
                            String enrichedReport = injectLivePrices(
                                filteredMessage.toString().trim(),
                                enrichedAssets,
                                cachedMarketData
                            );
                            String finalPayload = "⚡ *ANALYSE MACRO ÉCONOMIQUE*\n" + enrichedReport;
                            sendTelegramSecure(finalPayload, NotificationService.this);
                            db.markEventAsSynced(fingerprint, "PROCESSED_OK");
                        } else {
                            Log.d(TAG, "Conviction trop faible (" + convictionPercent + "%) et non suprême → message ignoré");
                            db.markEventAsSynced(fingerprint, "LOW_CONVICTION_FILTERED");
                        }
                    } else {
                        db.markEventAsSynced(fingerprint, "FILTERED_ALL_NEUTRAL");
                    }
                } else {
                    Log.e(TAG, "[GROQ] Erreur de serveur HTTP Code : " + status);
                    db.markEventAsSynced(fingerprint, "FAILED_SERVER_HTTP_" + status);
                }
            } catch (Exception e) {
                Log.e(TAG, "[GROQ] Échec lors de l'exécution réseau / SQLite", e);
                if (db != null) {
                    try {
                        db.markEventAsSynced(fingerprint, "FAILED_CRITICAL_EXCEPTION");
                    } catch (Exception ex) {
                        Log.e(TAG, "Impossible de forcer la mise à jour du verrou SQLite", ex);
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        });    
    }
    // Point 5 : Déconnexion sécurisée encapsulée dans un bloc finally
    public static void sendTelegramSecure(String message, Context context) {
    new Thread(() -> {
        try {
            android.content.SharedPreferences prefs =
                context.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
            String token  = prefs.getString("tg_token", "");
            String chatId = prefs.getString("tg_chat_id", "");
            if (token.isEmpty() || chatId.isEmpty()) {
                Log.w(TAG, "[TELEGRAM] Token ou Chat ID manquant — envoi annulé.");
                return;
            }

            // ✅ Découpage automatique si message > 4000 chars (limite Telegram = 4096)
            int MAX = 4000;
            List<String> chunks = new ArrayList<>();
            if (message.length() <= MAX) {
                chunks.add(message);
            } else {
                // Découpe proprement sur les sauts de ligne pour ne pas couper une ligne en deux
                String[] lines = message.split("\n");
                StringBuilder current = new StringBuilder();
                for (String line : lines) {
                    if (current.length() + line.length() + 1 > MAX) {
                        chunks.add(current.toString().trim());
                        current = new StringBuilder();
                    }
                    current.append(line).append("\n");
                }
                if (current.length() > 0) chunks.add(current.toString().trim());
            }

            for (String chunk : chunks) {
                sendChunkToTelegram(chunk, token, chatId, "Markdown");
                if (chunks.size() > 1) Thread.sleep(500); // anti-flood entre morceaux
            }

        } catch (Exception e) {
            Log.e(TAG, "[TELEGRAM] Erreur envoi", e);
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ [TELEGRAM] Erreur : " + e.getMessage());
        }
    }).start();
}

    // ✅ NOUVELLE méthode helper — envoie un seul chunk, retente en texte brut si Markdown rejeté
    private static void sendChunkToTelegram(String text, String token,
                                             String chatId, String parseMode) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
    
            JSONObject payload = new JSONObject();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            if (parseMode != null && !parseMode.isEmpty())
                payload.put("parse_mode", parseMode);
    
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input, 0, input.length);
            }
    
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "[TELEGRAM] ✅ Chunk envoyé (" + text.length() + " chars)");
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("✅ [TELEGRAM] Message envoyé.");
            } else if (code == 400 && "Markdown".equals(parseMode)) {
                // ✅ Markdown rejeté (astérisque non fermé, etc.) → retente en texte brut
                Log.w(TAG, "[TELEGRAM] 400 Markdown rejeté → retry en texte brut");
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("⚠️ [TELEGRAM] Markdown invalide → retry texte brut");
                conn.disconnect();
                sendChunkToTelegram(text, token, chatId, "");
            } else {
                // Lit le corps d'erreur pour avoir le message exact de Telegram
                StringBuilder errBody = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) errBody.append(l);
                } catch (Exception ignored) {}
                String errMsg = "❌ [TELEGRAM] HTTP " + code + " : " + errBody;
                Log.e(TAG, errMsg);
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog(errMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "[TELEGRAM] Échec chunk", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // APRÈS
    if (!getSharedPreferences("TradingBot", MODE_PRIVATE).getBoolean("bot_active", false)) {
        String pkg = sbn.getPackageName();
        if (pkg != null && (pkg.contains("financialjuice") || pkg.contains("nikkei") || pkg.contains("forex.portal"))) {
            Log.w(TAG, "⏸️ [BOT INACTIF] Notif de '" + pkg + "' ignorée — bot_active=false.");
        }
        return;
    }
        // ✅ CORRECTIF BUG 9 : ignore toute notification pendant un import/restauration de base
        // (MainActivity ferme et recrée le fichier .db -> tout traitement ici lirait/écrirait
        // sur une connexion fermée ou un fichier en cours de remplacement).
        if (isDatabaseImportInProgress) {
            Log.w(TAG, "[SERVICE] Notification ignorée : import de base de données en cours.");
            return;
        }
        
            // Détection et filtrage immédiat des packages sources autorisés
            String packageName = sbn.getPackageName().toLowerCase(Locale.ROOT);
            String sourceName = "Source Institutionnelle";
            if (packageName.contains("financialjuice")) {
            sourceName = "FinancialJuice";
            } else if (packageName.contains("nikkei")) {
                sourceName = "TradingEconomics";
            } else if (packageName.contains("forex.portal")) {
                sourceName = "Myfxbook";
            } else if (packageName.contains("twitter") || packageName.contains("periscope")) {
                sourceName = "X /Twitter";
            } else if (packageName.contains("chrome") || packageName.equals("com.android.chrome")) {
                sourceName = "Chrome";
            // APRÈS
            } else {
                Log.v(TAG, "[FILTRE PACKAGE] Ignoré (source non suivie) : " + packageName);
                return;
            }
        
            // Extraction sécurisée des chaînes de caractères brutes fournies par Android
            Bundle extras = sbn.getNotification().extras;
            final String title = extras.getString(Notification.EXTRA_TITLE, "");
            String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
            String text = extras.getString(Notification.EXTRA_TEXT, "");
        
            // 🔴 SÉCURITÉ REGEX : Conservation du texte brut le plus complet pour l'extraction mathématique d'EconomicAnalyzer
            final String bodyTextRaw = bigText.length() > text.length() ? bigText : text;
        
            // Reconstruction standard du flux texte unifié pour le reste des modules
            String subText = extras.getString(Notification.EXTRA_SUB_TEXT, "");
            String summary = extras.getString(Notification.EXTRA_SUMMARY_TEXT, "");
            String tempBody = bodyTextRaw;
            if (subText.length() > tempBody.length()) tempBody = subText;
            if (summary.length() > tempBody.length()) tempBody = summary;
        
            String tempUnifiedFeed = (title + " " + tempBody).trim();
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                StringBuilder bundled = new StringBuilder(title).append(" ");
                for (CharSequence line : lines) {
                    if (line != null) bundled.append(line).append(" ");
                }
                String bundledFeed = bundled.toString().trim();
                if (bundledFeed.length() > tempUnifiedFeed.length()) {
                    tempUnifiedFeed = bundledFeed;
                }
            }
        
            // APRÈS
            if (tempUnifiedFeed.length() < 6) {
               Log.w(TAG, "⚠️ [EXTRACTION VIDE] '" + sourceName + "' rejeté : texte trop court (titre='" + title 
               + "', len=" + tempUnifiedFeed.length() + "). Possible notif groupée/RemoteViews non standard.");
               return;
             }
            final String finalUnifiedFeed = tempUnifiedFeed;
            final String finalSourceName = sourceName;
            final long postTimeMs = sbn.getPostTime();  
             // ==================== 🔥 INTERCEPTION ET ENRICHISSEMENT FRED IMMÉDIAT ====================
            String upperCheck = finalUnifiedFeed.toUpperCase(Locale.ROOT);
            final String[][] FRED_INDICATORS = {
                // Emploi 
                {"JOBLESS CLAIMS",    "ICSA",     "K"},
                {"INITIAL CLAIMS",    "ICSA",     "K"},
                {"CHÔMAGE US",        "ICSA",     "K"},
                // Inflation
                {"CPI",               "CPIAUCSL", "%"},
                {"CONSUMER PRICE",    "CPIAUCSL", "%"},
                {"PCE",               "PCEPILFE", "%"},
                {"CORE PCE",          "PCEPILFE", "%"},
                {"PPI",               "PPIACO",   "%"},
                // Consommation / Sentiment
                {"RETAIL SALES",      "RSXFS",    "%"},
                {"VENTES AU DÉTAIL",  "RSXFS",    "%"},
                {"MICHIGAN",          "UMCSENT",  ""},
                {"CONSUMER SENTIMENT","UMCSENT",  ""},
                // PMI / ISM
                {"ISM MANUFACTUR",    "NAPM",     ""},
            };
            
            String matchedSeries = null;
            String matchedFormat = null;
            String matchedLabel  = null;
            
            for (String[] indicator : FRED_INDICATORS) {
                if (upperCheck.contains(indicator[0])) {
                    matchedSeries = indicator[1];
                    matchedFormat = indicator[2];
                    matchedLabel  = indicator[0];
                    break; // Premier match suffit
                }
            }
            
            if (matchedSeries != null) {
                final String seriesId = matchedSeries;
                final String format   = matchedFormat;
                final String label    = matchedLabel;
            
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "🔄 [FRED] Requête pour " + label + " (série: " + seriesId + ")...");
                        String actualValue = EconomicCalendarAPI.fetchFredActualValue(seriesId, format);
            
                        if (actualValue != null && !actualValue.isEmpty()) {
                            long currentUnixTimestamp = System.currentTimeMillis() / 1000L;
                            boolean updated = EventDatabase.getInstance(getApplicationContext())
                                         .updateActualIfMissing(title, currentUnixTimestamp, actualValue);
                            if (updated) {
                                Log.d(TAG, "✅ [FRED] Synchronisé : " + label + " = " + actualValue);
                                if (MainActivity.instance != null) {
                                    MainActivity.instance.addLog("✅ [FRED] " + label + " → " + actualValue);
                                }
                            } else {
                                Log.d(TAG, "ℹ️ [FRED] " + label + " = " + actualValue + " — aucun événement N/A à mettre à jour.");
                            }
                        } else {
                            Log.w(TAG, "⚠️ [FRED FAILURE] Impossible d'extraire la donnée pour : " + label);
                            if (MainActivity.instance != null) {
                                MainActivity.instance.addLog("⚠️ [FRED] Échec récupération : " + label);
                            }
                        }
                    }
                });
            }
            // =========================================================================================
         // 2️⃣ BASCOULEMENT IMMÉDIAT ET ISOLÉ DANS LE PIPELINE ASYNCHRONE (THREAD D'ARRIÈRE-PLAN)
            // Utilisation de l'exécuteur existant de la classe pour stabiliser la RAM et le CPU
            tradingPipelineExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String upperFeed = finalUnifiedFeed.toUpperCase(Locale.ROOT);
                        long currentTime = System.currentTimeMillis();
                        String currentSpeaker = "";
        
                        // Identification des Speakers de Banques Centrales
                        if (upperFeed.contains("POWELL") || upperFeed.contains("WARSH") || upperFeed.contains("WALLER") ||
                            upperFeed.contains("BARKIN") || upperFeed.contains("GOOLSBEE") || upperFeed.contains("WILLIAMS") ||
                            upperFeed.contains("KUGLER") || upperFeed.contains("BOSTIC") || upperFeed.contains("DALY") ||
                            upperFeed.contains("LOGAN") || upperFeed.contains("FED")) {
                            currentSpeaker = "FED";
                        } else if (upperFeed.contains("LAGARDE") || upperFeed.contains("SCHNABEL") || upperFeed.contains("NAGEL")) {
                            currentSpeaker = "ECB";
                        }
        
                        EconomicEventDetector.DetectedEvent detectedEvt = EconomicEventDetector.detectEvent(title, finalUnifiedFeed);
                        String eventTypeStr = detectedEvt.eventType;
                        boolean isSupremeRank = false;
    
                        // 🛡️ Préservation de TOUTE la logique géo en aval (allocation d'actifs, bypass throttle, seuils)
                        // EconomicEventDetector retourne des types granulaires (GEO-MIDDLE-EAST, GEO-EUROPE-EST,
                        // GEO-ASIA-PACIFIC) là où l'ancien code inline utilisait un seul marqueur générique "GEOPOLITICAL".
                        // On normalise ici pour ne rien casser dans le reste de la méthode.
                        boolean isGeoDetected = eventTypeStr.startsWith("GEO-")
                                || upperFeed.contains("HORMUZ") || upperFeed.contains("ORMUZ") || upperFeed.contains("IRAN") || upperFeed.contains("ISRAEL")
                                || upperFeed.contains("HEZBOLLAH") || upperFeed.contains("HOUTHI") || upperFeed.contains("GAZA") || upperFeed.contains("LEBANON")
                                || upperFeed.contains("MOYEN-ORIENT") || upperFeed.contains("MIDDLE EAST") || upperFeed.contains("WAR") || upperFeed.contains("STRIKE")
                                || upperFeed.contains("FRAPPE") || upperFeed.contains("ESCALADE") || upperFeed.contains("CONFLIT") || upperFeed.contains("MILITARY")
                                || upperFeed.contains("TAIWAN") || upperFeed.contains("UKRAINE") || upperFeed.contains("RUSSIA");
    
                        if (isGeoDetected) {
                            eventTypeStr = "GEOPOLITICAL"; // Conserve le marqueur exact attendu par toute la logique en aval
                            isSupremeRank = false;
                        } else if (upperFeed.contains("OIL") || upperFeed.contains("WTI") || upperFeed.contains("BRENT") || upperFeed.contains("CRUDE") ||
                                   upperFeed.contains("EIA") || upperFeed.contains("OPEC") || upperFeed.contains("INVENTORIES") || upperFeed.contains("PETROLE")) {
                            // Cas non couvert nommément par EconomicEventDetector → on garde le comportement historique
                            eventTypeStr = "OIL-INVENTORY";
                        }
                        // Sinon : eventTypeStr reste la valeur riche fournie par EconomicEventDetector
                        // (ex: FED-WARSH-SIGNAL, INFLATION-DATA, ISM-INDICATOR, PMI-FLASH, MICHIGAN-SENTIMENT,
                        // GDP-ADVANCE, TRADE-TARIFF, CHINA-MACRO, etc.)
        
                        // 3️⃣ SYNCHRONISATION MACRO DÉTERMINISTE avec enrichissement calendaire
                        // Enrichir le contenu avec les données du calendrier (ACTUAL/FORECAST) si disponibles
                        String enrichedBody = EventValidator.enrichWithCalendar(title, bodyTextRaw, postTimeMs);
                        EconomicAnalyzer.EvaluationResult ecoResult = EconomicAnalyzer.analyserEvenement(title, enrichedBody);
                        Log.d(TAG, "Devise détectée : " + ecoResult.currency + ", poids : " + ecoResult.weight);
                        // Le poids n'est plus forcé à 5 ou 3 statiquement, il découle de la surprise de l'écart mathématique (1 à 4)
                        int finalCalculatedWeight = ecoResult.weight;
                        // Ajustement du pavillon suprême selon le verdict de l'analyseur mathématique ou de l'urgence géopolitique
                        // 🆕 Exploite désormais detectedEvt.getRawImpact() (HIGH/MEDIUM/LOW/NEUTRE) en plus du score
                        // mathématique d'EconomicAnalyzer, pour que les types riches (Warsh, ISM, PMI Flash, Michigan,
                        // GDP-Advance) déclenchent eux aussi le statut suprême même si le calcul de poids reste bas.
                        String rawImpact = detectedEvt.getRawImpact();
                        if (finalCalculatedWeight >= 3 || currentSpeaker.equals("FED") || eventTypeStr.equals("GEOPOLITICAL")
                                || "HIGH".equals(rawImpact) || "FED-WARSH-SIGNAL".equals(detectedEvt.eventType)) {
                            isSupremeRank = true;
                        }
        
                        // 4️⃣ Anti-spam / Protection contre les flux de paroles répétitifs des speakers
                        String speakerToken = currentSpeaker.trim();
                        if (!speakerToken.isEmpty()) {
                            if (!isSupremeRank && speakerToken.equals(lastSpeaker) && (currentTime - lastSpeechTime < 60000)) {
                                Log.d(TAG, "Doublon de discours filtré (" + speakerToken + ")");
                                return;
                            }
                            lastSpeechTime = currentTime;
                            lastSpeaker = speakerToken;
                        }
                        // 5️⃣ Matrice de ciblage et d'allocation des Actifs Financiers (Thread-safe via liste locale)
                        List<String> enrichedAssets = new ArrayList<>();
                        if (upperFeed.contains("EUR") || upperFeed.contains("ECB") || upperFeed.contains("LAGARDE")) enrichedAssets.add("EURUSD");
                        if (upperFeed.contains("JPY") || upperFeed.contains("YEN") || upperFeed.contains("BOJ")) enrichedAssets.add("USDJPY");
                        if (upperFeed.contains("GBP") || upperFeed.contains("BOE")) enrichedAssets.add("GBPUSD");
                        if (upperFeed.contains("AUD") || upperFeed.contains("RBA")) enrichedAssets.add("AUDUSD");
                        if (upperFeed.contains("CAD") || upperFeed.contains("BOC")) enrichedAssets.add("USDCAD");
                        if (upperFeed.contains("GOLD") || upperFeed.contains("XAU")) enrichedAssets.add("GOLD");
                        if (upperFeed.contains("NASDAQ") || upperFeed.contains("TECH") || upperFeed.contains("AI")) enrichedAssets.add("NASDAQ");
                        if (upperFeed.contains("SP500") || upperFeed.contains("S&P")) enrichedAssets.add("SP500");
                        if (upperFeed.contains("BITCOIN") || upperFeed.contains("BTC")) enrichedAssets.add("BITCOIN");
        
                        // Association contextuelle Pétrole / Risque d'approvisionnement (Hormuz)
                        if (upperFeed.contains("OIL") || upperFeed.contains("WTI") || upperFeed.contains("CRUDE") || 
                            upperFeed.contains("EIA") || upperFeed.contains("HORMUZ") || upperFeed.contains("ORMUZ")) {
                            if (!enrichedAssets.contains("USOIL")) enrichedAssets.add("USOIL");
                            if (!enrichedAssets.contains("USDCAD")) enrichedAssets.add("USDCAD");
                            if (!enrichedAssets.contains("GOLD")) enrichedAssets.add("GOLD");
                        }
        
                        // Profil d'allocation en Régime de Crise Géopolitique
                        if (eventTypeStr.equals("GEOPOLITICAL")) {
                            String[] geoAssets = {"GOLD", "USOIL", "USDJPY", "US10Y", "NASDAQ", "SP500"};
                            for (String asset : geoAssets) {
                                if (!enrichedAssets.contains(asset)) enrichedAssets.add(asset);
                            }
                        }
        
                        // Profil d'allocation standard lors des chocs macroéconomiques majeurs
                        if (isSupremeRank && !eventTypeStr.equals("GEOPOLITICAL")) {
                            String[] macroAssets = {"US10Y", "NASDAQ", "SP500", "GOLD", "EURUSD", "USDJPY", "BITCOIN"};
                            for (String asset : macroAssets) {
                                if (!enrichedAssets.contains(asset)) enrichedAssets.add(asset);
                            }
                        }
        
                        // Panier de secours par défaut si aucun mot-clé d'actif n'a matché
                        if (enrichedAssets.isEmpty()) {
                            enrichedAssets.add("NASDAQ");
                            enrichedAssets.add("SP500");
                            enrichedAssets.add("US10Y");
                        }
        
                        // 6️⃣ Validation de cohérence temporelle et historique via EventValidator
                        // APRÈS
                        // ✅ CORRECTIF : on injecte la source déjà identifiée (finalSourceName) dans le contenu passé
                        // au validateur, pour que calculateBreakingNewsConfidence() puisse réellement la reconnaître
                        // au lieu de chercher en vain le mot "financialjuice" dans le texte de l'actu elle-même.
                        EventValidator.ValidationResult validationResult = EventValidator.validate(NotificationService.this, title, bodyTextRaw + " [" + finalSourceName + "]", currentTime, enrichedAssets);
                        
                        // Log dans l'Ui
                        if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(finalSourceName + ": " + (validationResult.isConfirmed ? "CONFIRMÉ" : "REJETÉ") + " - " + validationResult.reason);
                        }
                        // Coupe-circuit du Validateur : On bloque les doublons temporels, sauf s'il s'agit d'un choc absolu de poids 4
                        if (validationResult != null && !validationResult.isConfirmed) {
                        // Cas particulier : inertie macro (driver déjà actif) → on envoie un rappel Telegram
                        if (validationResult.isInertiaBlock) {
                            // 🛡️ CORRECTIF SPAM : on déduit le type de driver depuis la raison pour
                            // appliquer un cooldown par type (FED-MONETARY-POLICY, CORE-MACRO, etc.)
                            // au lieu d'envoyer un rappel à chaque notification filtrée pendant 2h.
                            String inertiaKey = eventTypeStr;
                            long nowMs = System.currentTimeMillis();
                            Long lastSentForType = lastInertiaReminderSent.get(inertiaKey);
                            if (lastSentForType != null && (nowMs - lastSentForType) < INERTIA_REMINDER_COOLDOWN_MS) {
                                Log.d(TAG, "[RAPPEL] Driver " + inertiaKey + " déjà rappelé récemment, ignoré (cooldown).");
                                return; // On arrête le traitement normal sans renvoyer Telegram
                            }
                            lastInertiaReminderSent.put(inertiaKey, nowMs);

                            String reminderMsg = "⏳ *RAPPEL : DRIVER DÉJÀ ACTIF*\n" +
                                                 "🔹 " + validationResult.reason + "\n\n" +
                                                 "📋 *Dernier événement similaire :*\n" +
                                                 validationResult.lastEventSummary;
                            sendTelegramSecure(reminderMsg, NotificationService.this);
                            Log.d(TAG, "[RAPPEL] Driver actif : rappel envoyé.");
                            return; // On arrête le traitement normal
                        }
                        // Pour les autres cas de rejet (doublon, rumeur, faible confiance, etc.)
                        if (finalCalculatedWeight < 4) {
                            Log.d(TAG, "[COUPE-CIRCUIT TIMING] Événement rejeté : " + validationResult.reason);
                            return;
                        }
                        }
    
                     // APRÈS (remplace le bloc déjà patché précédemment)
                    boolean isConfirmedCalendarDrop = validationResult != null && validationResult.isCalendarIntercept;
                    if (finalCalculatedWeight < 3 && !eventTypeStr.equals("GEOPOLITICAL") && !isConfirmedCalendarDrop) {
                        String dropMsg = "[COUPE-CIRCUIT MACRO] " + finalSourceName + " — \"" + title + "\" rejeté (poids=" + finalCalculatedWeight + ", type=" + eventTypeStr + ")";
                        Log.d(TAG, dropMsg);
                        if (MainActivity.instance != null) MainActivity.instance.addLog("⚪ " + dropMsg);
                        return;
                    }
                    if (isConfirmedCalendarDrop && finalCalculatedWeight < 3) {
                        Log.d(TAG, "🟢 [BYPASS CALENDRIER] Sauvegarde forcée malgré poids=" + finalCalculatedWeight + " (Interception Calendrier Macro confirmée).");
                    }
                    // 8️⃣ Génération de la signature cryptographique et persistance SQLite en base de données
                    String fingerprint = generateSecureHash(packageName + "_" + title + "_" + bodyTextRaw + "_" + (postTimeMs / 60000));
    
                    StringBuilder assetsSb = new StringBuilder();
                    for (int i = 0; i < enrichedAssets.size(); i++) {
                        assetsSb.append(enrichedAssets.get(i));
                        if (i < enrichedAssets.size() - 1) assetsSb.append(",");
                    }
    
                    // Sauvegarde de l'événement en base de données persistante avec injection du vrai poids macro calculé
                    boolean saved = eventDb.saveEvent(
                            fingerprint, packageName, finalSourceName, eventTypeStr, title, bodyTextRaw,
                            assetsSb.toString(), "pending", postTimeMs / 1000, "pending", finalCalculatedWeight);
    
                     if (saved) {
                     Log.i(TAG, "[DATABASE] Match " + eventTypeStr + " enregistré avec succès. Poids affecté : " + finalCalculatedWeight);
                       if (MainActivity.instance != null) {
                       MainActivity.instance.addLog("💾 [DB] " + finalSourceName + " enregistré (" + eventTypeStr + ", poids " + finalCalculatedWeight + ") — \"" + title + "\"");
                       }
                    }
                      else {
                      Log.w(TAG, "[DATABASE] ⚠️ ÉCHEC saveEvent pour '" + title + "' (fingerprint=" + fingerprint + ") — doublon déjà connu ou erreur SQLite.");
                      }

                    // 9️⃣ Enrichissement dynamique et forcé du Prompt Système IA avec les flèches théoriques de l'analyseur
                    // 9️⃣ Enrichissement dynamique du Prompt Système IA
                    // 9️⃣ ENRICHISSEMENT MARKET DATA & PROMPT IA (Pipeline Intégré)
                    // 📋 IA (Pipeline Intégré) : Préparation du Snapshot Marché Temps Réel
                      // ✅ CORRECTION : renommer la variable String pour éviter le conflit avec la Map
                    String marketSnapshotString = "Marché non analysé.";
                    java.util.Map<String, MarketDataFetcher.MarketData> batchSnapshot = null;
                    try {
                        // Filtrer uniquement les 6 actifs Twelve Data parmi enrichedAssets
                        List<String> twelveFiltered = new ArrayList<>();
                        for (String a : enrichedAssets) {
                            if (TWELVE_DATA_ASSETS.contains(a)) twelveFiltered.add(a);
                        }
                        // tryAcquireBatchSlot — évite les appels simultanés
                        if (!twelveFiltered.isEmpty() && MarketDataFetcher.tryAcquireBatchSlot()) {
                            batchSnapshot = MarketDataFetcher.getMarketDataBatch(twelveFiltered);
                        } else {
                            Log.w(TAG, "[BATCH] Slot occupé ou aucun actif Twelve Data — cache LKV utilisé");
                        }
                        if (batchSnapshot != null && !batchSnapshot.isEmpty()) {
                            StringBuilder sb = new StringBuilder("Données de marché (Live Batch) : ");
                            boolean premierActif = true;
                    
                            for (java.util.Map.Entry<String, MarketDataFetcher.MarketData> entry : batchSnapshot.entrySet()) {
                                MarketDataFetcher.MarketData mData = entry.getValue();
                                
                                // 🛡️ MULTI-PROTECTION : Filtrage préventif des valeurs nulles ou prix aberrants (<= 0)
                                if (mData == null || mData.price <= 0) {
                                    Log.w(TAG, "Snapshot - Actif ignoré car données invalides ou nulles : " + entry.getKey());
                                    continue; 
                                }
                    
                                // ✂️ SUPPRESSION DU SÉPARATEUR DE FIN : Ajouté uniquement avant les éléments suivants
                                if (!premierActif) {
                                    sb.append(" | ");
                                }
                                premierActif = false;
                    
                                String sign = (mData.changePercent >= 0) ? "+" : "";
                                sb.append(entry.getKey())
                                  .append(" => ")
                                  .append(String.format(Locale.US, "%.4f (%s%.2f%%)", mData.price, sign, mData.changePercent));
                            }
                    
                            // Vérification finale au cas où TOUS les actifs auraient été rejetés par le filtre de sécurité
                            if (premierActif) {
                                marketSnapshotString = "Données de marché indisponibles (aucun prix valide extrait).";
                            } else {
                                marketSnapshotString = sb.toString();
                            }
                        } else {
                            marketSnapshotString = "Données de marché indisponibles (Twelve Data hors-ligne ou limite atteinte).";
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Échec de la génération du snapshot marché", e);
                        marketSnapshotString = "Erreur technique lors de l'acquisition des données.";
                    }
                    
                    // Construction du prompt enrichi (utilise la variable String renommée)
                    String baseSystemPrompt = SYSTEM_PROMPT;
                    String promptAI = "📊 [CONTEXTE MARCHÉ ACTUEL] : " + marketSnapshotString + "\n\n" + baseSystemPrompt;
                    
                    if (ecoResult.isParsed) {
                        promptAI = "⚠️ [GUIDAGE MATRICIEL INTERNE] : \n" +
                                "L'analyseur mathématique déterministe a détecté un écart type. " +
                                "Direction recommandée : " + ecoResult.directionText + "\n\n" + promptAI;
                    }
                    // 🛡️ THROTTLE GÉO CIBLÉ (12 min) — seul angle mort non couvert par l'inertie macro 2h,
                    // qui exclut volontairement les types GEO-* (cf. EventValidator.validate(), ÉTAPE 4).
                    // Remplace l'ancien GEO_THROTTLE_MS mort de processIncomingMacroFeed(), sans réimporter
                    // le reste de sa mécanique (rollback, etc.) devenue inutile face aux 3 autres garde-fous actifs.
                    if (eventTypeStr.equals("GEOPOLITICAL")) {
                        if (!isSupremeRank && (currentTime - lastGeoTime < GEO_THROTTLE_MS)) {
                            Log.d(TAG, "[THROTTLE GÉO] Notification bloquée (12 min) - dernier choc géo il y a "
                                    + (currentTime - lastGeoTime) / 1000 + "s");
                            return;
                        }
                        lastGeoTime = currentTime;
                    }
    // 🔟 Exécution finale de l'analyse cognitive LLM 
    // Récupération du snapshot marché juste avant l'appel (variable Map, pas de conflit)
    // 🔟 Exécution finale de l'analyse cognitive LLM
    // Réutilise batchSnapshot déjà récupéré ligne 1103 — 0 appel réseau supplémentaire
    processAnalysisWithAI(finalSourceName, title, bodyTextRaw, enrichedAssets, fingerprint, promptAI, isSupremeRank, batchSnapshot);
            } catch (Exception e) {
                        Log.e(TAG, "Erreur critique au sein de l'exécution asynchrone de la pipeline", e);
                    }
                }
                });
            }
           /**
     * Retourne true uniquement pendant les sessions actives
     * (Londres 08h-17h UTC ou New York 13h30-21h UTC).
     * Évite les appels Twelve Data inutiles la nuit ou le week-end.
     */
    private static boolean isMarketHours() {
        java.util.Calendar utc = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"));
        int dow  = utc.get(java.util.Calendar.DAY_OF_WEEK);
        int hour = utc.get(java.util.Calendar.HOUR_OF_DAY);
        int min  = utc.get(java.util.Calendar.MINUTE);
        int totalMin = hour * 60 + min;
    
        // Week-end → fermé
        if (dow == java.util.Calendar.SATURDAY ||
            dow == java.util.Calendar.SUNDAY) return false;
    
        // Session Londres  : 08h00–17h00 UTC (480–1020 min)
        // Session New York : 13h30–21h00 UTC (810–1260 min)
        return (totalMin >= 480 && totalMin <= 1020) ||
               (totalMin >= 810 && totalMin <= 1260);
       }
    
          public static void sendToGroqAndTelegram(String source, String title, String body, List<String> assets, Context context) {
            if (context == null) return;
            String fingerprint = String.valueOf((source + title + body).hashCode());
            NotificationService instance = serviceInstance;
        
            // ✅ Sauvegarder dans SQLite pour inclusion dans le Daily Report (INCHANGÉ)
            // ✅ Ne pas re-sauvegarder si déjà en DB — updateActualIfMissing l'a déjà mis à jour
        // Sauvegarder uniquement si vraiment absent (fingerprint non existant)
              // 2. Gestion intelligente des actifs (Priorité aux paramètres, repli sur la liste globale)
            List<String> finalAssetsList = (assets != null && !assets.isEmpty()) ? assets : new ArrayList<>(Arrays.asList(
                "GOLD","NASDAQ","SP500","BITCOIN","EURUSD",
                "USDJPY","GBPUSD","AUDUSD","USDCAD","USOIL","US10Y"
            ));
            // Conversion de la Liste en String pour correspondre au schéma SQLite
            String assetsStr = String.join(",", finalAssetsList);
            // ✅ AUDIT EXHAUSTIF (bug 9) : double garde NPE — instance peut être null (service non démarré)
            // et getEventDb() peut être null (import de base en cours) ; sans ce garde, un appelant futur
            // sans try/catch dédié ferait planter le service au lieu de simplement ignorer la sauvegarde.
            EventDatabase db = (instance != null) ? instance.getEventDb() : null;
            if (db != null) {
                if (!db.isEventAlreadySaved(title, System.currentTimeMillis() / 1000)) {
                    int dynamicWeight = EconomicCalendarAPI.isSupremeCalendarIndicator(title) ? 5 : 3;
                    db.saveEvent(
                        fingerprint, "com.tradingbot.calendar", source,
                        "CALENDAR-RESULT", title, body, assetsStr,
                        "pending", System.currentTimeMillis() / 1000,
                        "pending", dynamicWeight
                    );
                }
            } else {
                Log.w(TAG, "[sendToGroqAndTelegram] Sauvegarde DB ignorée (service/DB indisponible) pour : " + title);
            }
           // 🚀 INJECTION : Déportation réseau simplifiée et ultra-rapide via Batch API
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
        StringBuilder blocPrix = new StringBuilder();
    
        // Déclaré ici — accessible partout dans le try ET le catch
        java.util.Map<String, MarketDataFetcher.MarketData> batchPrices = null;
    
        if (assets != null && !assets.isEmpty()) {
        blocPrix.append("\n\n📊 *COURS INSTANTANÉS AU MOMENT DE L'IMPACT :*");
    
        // Filtrer uniquement les 6 actifs Twelve Data — assets complet va à Groq
        List<String> twelveAssets = new ArrayList<>();
        for (String asset : assets) {
            if (TWELVE_DATA_ASSETS.contains(asset)) {
                twelveAssets.add(asset);
            }
        }
    
        if (!twelveAssets.isEmpty()) {
        // tryAcquireBatchSlot() est synchronized — un seul thread obtient le slot
        if (MarketDataFetcher.tryAcquireBatchSlot()) {
            batchPrices = MarketDataFetcher.getMarketDataBatch(twelveAssets);
            if (batchPrices == null) batchPrices = new java.util.HashMap<>();
        } else {
            // Slot occupé — utilise le cache LKV existant sans appel réseau
            Log.w(TAG, "[TWELVE DATA] Slot occupé — cache LKV utilisé pour ce flux");
            batchPrices = new java.util.HashMap<>();
        }
            for (String asset : twelveAssets) {
                MarketDataFetcher.MarketData data = batchPrices.get(asset);
                if (data != null && data.price > 0) {
                    String tendance = data.changePercent >= 0 ? "📈" : "📉";
                    String formatPrix = (data.price > 1000) ? "\n%s %s : *%,.2f* (%+.2f%%)" : "\n%s %s : *%.5f* (%+.2f%%)";
                    blocPrix.append(String.format(Locale.US, formatPrix, tendance, asset, data.price, data.changePercent));
                } else {
                    blocPrix.append("\n🔸 ").append(asset).append(" : (Cours indisponible)");
                }
            }
        }
       }
    
        String bodyEnrichi = body + blocPrix.toString();
    
        if (instance != null) {
            // batchPrices réutilisé — 0 appel réseau supplémentaire
            instance.processAnalysisWithAI(source, title, bodyEnrichi, assets, fingerprint, SYSTEM_PROMPT, true, batchPrices);
        } else {
            String msg = "📅 *RÉSULTAT CALENDAIRE*\n📌 *" + title + "*\n📊 " + bodyEnrichi;
            sendTelegramSecure(msg, context);
        }
    
    } catch (Exception e) {
        Log.e(TAG, "Erreur critique lors de l'enrichissement par Batch API", e);
        if (instance != null) {
            // batchPrices peut être null si exception avant son initialisation — cachedMarketData=null est géré dans processAnalysisWithAI
            instance.processAnalysisWithAI(source, title, body, assets, fingerprint, SYSTEM_PROMPT, true, null);
        } else {
            String msg = "📅 *RÉSULTAT CALENDAIRE*\n📌 *" + title + "*\n📊 " + body;
            sendTelegramSecure(msg, context);
        }
    }
            }
    }).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = EventDatabase.getInstance(this);
        
        // ── Liaison du contexte pour l'extraction de la clé macro_api_key ──
        EconomicCalendarAPI.init(this);
        EventValidator.setAppContext(this); 
        serviceInstance = this;                 // ✅ Assure la survie de l'instance pour l'IA
        
        // ── Déportation du préchargement réseau dans un thread d'arrière-plan ──
    new Thread(new Runnable() {
    @Override
    public void run() {
        try {
            // Guard : évite le double appel avec BACKFILL qui se déclenche
            // quasi simultanément via registerNetworkCallback().onAvailable()
            long now = System.currentTimeMillis();
            if (now - lastCalendarBackfillMillis < CALENDAR_BACKFILL_GUARD_MS) {
                Log.d(TAG, "[SERVICE] Préchargement initial ignoré — BACKFILL déjà actif.");
                return;
            }
            lastCalendarBackfillMillis = now;
            EventValidator.preloadCalendar();
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] Erreur lors du préchargement du calendrier", e);
        }
    }
    }).start();
    
        createNotificationChannel();
        startDailyBriefScheduler();
        startMonthlyReportScheduler();
        registerNetworkCallback();
        
        // Planification unifiée : purge SQLite + nettoyage RAM + préchargement toutes les 24h à Minuit (Madagascar)
        long initialDelayMillis = calculateMillisUntilNextMadaMidnight();
        long period24HoursMillis = 24 * 60 * 60 * 1000L; 
    
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (isSyncing) return;
                isSyncing = true;
                try {
                    Log.d(TAG, "🕒 [MAINTENANCE] Déclenchement automatique de minuit (Heure de Madagascar)...");
                    
                    // 1. Purge SQLite des événements ayant dépassé la fenêtre de validité de 48 heures
                    // 1. Purge SQLite à deux vitesses (bruit 48h / piliers macro 45j) — purgeOldEvents calcule
                    // lui-même les deux seuils en interne à partir du timestamp courant, donc on lui passe "now" brut.
                    long nowSeconds = System.currentTimeMillis() / 1000;
                    eventDb.purgeOldEvents(nowSeconds);
                    Log.d(TAG, "[MAINTENANCE] Base de données SQLite purgée (bruit > 48h, piliers > 45j).");
                    // 2. Nettoyage de la table des empreintes pour éviter les fuites RAM
                    EventValidator.cleanupOldFingerprints();
                    Log.d(TAG, "[MAINTENANCE] Table des empreintes mémoires RAM nettoyée.");
    
                    // 3. Re-synchronisation du calendrier pour la nouvelle journée
                    EventValidator.preloadCalendar();
                    lastAlertsSent.clear();
                    Log.d(TAG, "[MAINTENANCE] Cooldowns d'alertes réinitialisés.");
                    
                } catch (Exception e) {
                    Log.e(TAG, "[MAINTENANCE] Erreur lors de la maintenance à minuit", e);
                } finally {
                    isSyncing = false;
                }
            }
        }, initialDelayMillis, period24HoursMillis, TimeUnit.MILLISECONDS);
         // ✅ NOUVEAU (remplace le 6h)
        long fifteenMinutesMillis = 15 * 60 * 1000L;
       scheduler.scheduleAtFixedRate(new Runnable() {
       @Override
       public void run() {
        try {
            EventValidator.preloadCalendar();
        } catch (Exception e) {
            Log.e(TAG, "[CALENDAR] Erreur refresh 15min", e);
        }
        }
        }, fifteenMinutesMillis, fifteenMinutesMillis, TimeUnit.MILLISECONDS);
        
    scheduler.scheduleAtFixedRate(new Runnable() {
    @Override
    public void run() {
        try {
            // 🛡️ Guard : appel Twelve Data uniquement si lastForecast
            // contient des données ET si on est en heure de marché active
            if (lastForecast.isEmpty()) return; // Rien à surveiller → 0 appel réseau
            if (!isMarketHours()) return;        // Hors session → 0 appel réseau
            
            checkForecastDivergence();
        } catch (Exception e) {
            // 🚨 SÉCURITÉ : Capture impérative pour éviter que le scheduler ne s'arrête à tout jamais
            Log.e(TAG, "⚠️ Échec critique lors du check de divergence de prévisions (Scheduler 15min)", e);
            
            // Optionnel : pousser le log sur l'interface utilisateur si l'instance est disponible
            if (MainActivity.instance != null) {
                try {
                    MainActivity.instance.addLog("❌ Erreur de surveillance macro : " + e.getMessage());
                } catch (Exception ignored) {
                    // Évite un crash en cascade si l'UI est instable à ce moment précis
                }
            }
        }
    }
      }, 15, 15, TimeUnit.MINUTES);
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

    // Point 6 : Connexion fermée de manière étanche dans le bloc finally
    private void fetchMissingDataFromInstitutionalAPI() {
        // 🟢 1. Récupération sécurisée des événements historiques (7 jours)
        List<EconomicCalendarAPI.CalendarEvent> historicalEvents = EconomicCalendarAPI.fetchHistoricalEvents(this, 7);
        
        // 🟢 2. Initialisation du StringBuilder qui manquait au compilateur
        StringBuilder apiMacroBlock = new StringBuilder();
        
        for (EconomicCalendarAPI.CalendarEvent e : historicalEvents) {
            if (e == null) continue;
        
            // Vérification des critères d'impact et de publication des chiffres
            if (e.importance != null && e.importance.equalsIgnoreCase("HIGH") && e.actual != null && e.forecast != null) {
                
                String dateStr = e.timestamp; 
                String countryOrCurrency = (e.country != null) ? e.country : "USD";
                
                // 🟢 FIX : Remplacement de logToMain par le Log d'Android standard
                Log.i("NotificationService", String.format("High Impact Event: %s, Region: %s, Event: %s, Act: %s, Fcst: %s",
                        dateStr, countryOrCurrency, e.indicator, e.actual, e.forecast));
        
                // Formatage de la ligne pour le bloc macro envoyé à Groq
                apiMacroBlock.append("- ")
                            .append(e.indicator)
                            .append(" (").append(countryOrCurrency).append(") | ")
                            .append("Actual: ").append(e.actual).append(" | ")
                            .append("Forecast: ").append(e.forecast).append("\n");
            }
        }
        
        // 🟢 3. Envoi groupé à l'analyse Groq si des données sont présentes
        if (apiMacroBlock.length() > 0) {
            dispatchHistoricalBulkToGroq(apiMacroBlock.toString());
        }
        if (apiMacroBlock.length() > 0) {
            dispatchHistoricalBulkToGroq(apiMacroBlock.toString());
        }
    }

    private void dispatchHistoricalBulkToGroq(String bulkData) {
        HttpURLConnection conn = null;
        try {
            String apiKey = getGroqApiKey();
            if (apiKey.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es un Macro-Strategist de premier plan. Analyse ce relevé complet de données à fort impact."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES EXTRAITES :\n" + bulkData));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            conn = (HttpURLConnection) url.openConnection();
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

                eventDb.saveEvent(generateSecureHash(analysis), "com.tradingbot.sync", "API Sync", "Weekly-Sync", "Audit Global", analysis, "ALL_ASSETS", "ALIGNE_OK", (long)(System.currentTimeMillis()/1000), "synced", 5);
            }
        } catch (Exception e) { Log.e(TAG, "Échec dispatch historique Groq", e); }
        finally {
            if (conn != null) conn.disconnect();
        }
    }

    // Points 3 & 4 : Traitement rigoureux et robuste de isGeoEvent, filtrage strict de la casse et return / markEvent synchronisés
    private boolean executeAnalysisPipeline(String source, String feed, String history, 
                                            List<String> assets, long ts, String fingerprint) {
        int maxRetries = 3;
        int attempt = 0;

        String apiKey = getGroqApiKey();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String tgToken  = prefs.getString(PREF_TG_TOKEN, "");
        String tgChatId = prefs.getString(PREF_TG_CHAT_ID, "");
        if (apiKey.isEmpty() || tgToken.isEmpty() || tgChatId.isEmpty()) return false;

        while (attempt < maxRetries) {
            HttpURLConnection conn = null;
            try {
                String upperFeed = feed.toUpperCase(Locale.ROOT);
                boolean isGeoEvent = isGeoEvent(upperFeed);

                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
                sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
                String timeString = sdf.format(new Date(ts));

                URL url = new URL(GROQ_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("model", GROQ_MODEL);
                payload.put("temperature", 0.02);
                JSONArray messages = new JSONArray();

                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));

                String assetSpecs = "Spécifications strictes des Pictogrammes d'Actifs à insérer devant chaque ligne :\n" +
                                    "GOLD: 🏆, USOIL: 🛢️, NASDAQ: 💻, SP500: 📊, US10Y: 📈, BITCOIN: ₿, " +
                                    "EURUSD: 🇪🇺, GBPUSD: 🇬🇧, AUDUSD: 🇦🇺, USDCAD: 🇨🇦, USDJPY: 🇯🇵";
                messages.put(new JSONObject().put("role", "system").put("content", assetSpecs));
                messages.put(new JSONObject().put("role", "user").put("content", "Flux brut reçu : " + feed + "\nMémoire contextuelle ordonnée par importance :\n" + history));
                payload.put("messages", messages);

                // Protection automatique de l'OutputStream (Try-with-resources)
               try (OutputStream os = conn.getOutputStream()) {
                   os.write(payload.toString().getBytes("UTF-8"));
                   os.flush();
               } // Le flux d'écriture se ferme automatiquement ici, même si un crash réseau survient

               if (conn.getResponseCode() == 200) {
               StringBuilder r = new StringBuilder();
    
               // Protection automatique de l'InputStream
               try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                  String l;
                     while ((l = br.readLine()) != null) {
                       r.append(l);
                     }
               } // Le BufferedReader et conn.getInputStream() se ferment automatiquement ICI

               // Extraction du JSON sécurisée
                  // Extraction du JSON sécurisée
              String aiResult = new JSONObject(r.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    
              if (aiResult == null || aiResult.isEmpty() || aiResult.length() < 50) {
                 throw new Exception("Invalid API response");
              }

              // ====================== FILTRAGE INTELLIGENT ======================
              StringBuilder filteredMessage = new StringBuilder();
              String[] lines = aiResult.split("\n");
              int activeSignalsCount = 0;
              boolean inImpactSection = false;

              for (String line : lines) {
                  String trimmed = line.trim();
                  if (trimmed.isEmpty()) continue;

                  // Toujours garder les headers
                  if (trimmed.startsWith("🚨") || 
                      trimmed.startsWith("📊") || 
                      trimmed.startsWith("🎯") || 
                      trimmed.startsWith("📢") || 
                      trimmed.startsWith("🏁") ||
                      trimmed.startsWith("--- IMPACTS")) {
                      
                      filteredMessage.append(line).append("\n");
                      if (trimmed.startsWith("--- IMPACTS")) {
                          inImpactSection = true;
                      }
                      continue;
                  }

                  // Dans la section IMPACTS
                                    // Dans la section IMPACTS
                  if (inImpactSection && trimmed.startsWith("•")) {
                      String upperLine = line.toUpperCase(Locale.ROOT);

                      boolean isInclinationNeutral = upperLine.contains("MAIS NEUTRE");
                      boolean isSignificant = !isInclinationNeutral && (upperLine.contains("BULLISH") || upperLine.contains("BEARISH"));

                      if (isSignificant) {
                          filteredMessage.append(line).append("\n");
                          activeSignalsCount++;
                      }
                      // Ignorer les NEUTRE pour Telegram
                  }
              }
                // ====================== ENVOI TELEGRAM ======================
    if (activeSignalsCount > 0) {
    // Extraction précise du pourcentage de conviction depuis aiResult
    int convictionPercent = extrairePourcentageConviction(aiResult);
    boolean isSupremeRank = estEvenementSuprême(feed); // feed = le texte brut de l'événement

    // Seuil : conviction >= 40% OU événement de Rang Suprême (Fed, CPI, NFP, etc.)
    if (convictionPercent >= 40 || isSupremeRank) {
        String finalPayload = "⚡ *ANALYSE  MACRO ÉCONOMIQUES Pipeline*\n"
                + "🕒 " + timeString + " (Mada)\n"
                + "📡 Source : " + source + "\n"
                + filteredMessage.toString().trim();

        if (finalPayload.length() < 200) {
            eventDb.markEventAsSynced(fingerprint, "TOO_SHORT");
            return true;
        }

        Log.d(TAG, "📤 Envoi Telegram pour fingerprint=" + fingerprint + ", signaux impactants=" + activeSignalsCount);
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog(source + ": Envoi Telegram " + fingerprint);
        }
        sendTelegramSecure(finalPayload, this);
        lastAnalysisTime = System.currentTimeMillis();
        if (isGeoEvent) { lastGeoTime = System.currentTimeMillis(); }
        eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
        
        // Enregistrement des prévisions pour le moniteur de divergence
        captureForecastFromReport(aiResult); 
        return true;
        } else {
            Log.d(TAG, "Conviction trop faible (" + convictionPercent + "%) et non suprême → message ignoré");
            eventDb.markEventAsSynced(fingerprint, "LOW_CONVICTION_FILTERED");
            return true;
        }
        } else {
            // Aucun signal fort → on marque comme filtré
            eventDb.markEventAsSynced(fingerprint, "FILTERED_ALL_NEUTRAL");
            Log.d(TAG, "Tous les actifs neutres → pas d'envoi Telegram");
            return true;
        }
        } else {
            throw new Exception("API Error: " + conn.getResponseCode());
        }

        } catch (Exception e) {
                attempt++;
                Log.e(TAG, "Tentative " + attempt + "/" + maxRetries + " échouée", e);
                if (attempt < maxRetries) {
                    try { Thread.sleep(2000 * attempt); } catch (InterruptedException ie) { return false; }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    private List<String> filterActiveAssets(String text) {
        List<String> assets = new ArrayList<>();
        String upper = text.toUpperCase();

        if (upper.contains("WARSH")       || upper.contains("POWELL")          ||
            upper.contains("BARKIN")      || upper.contains("GOOLSBEE")        ||
            upper.contains("HAMMACK")     || upper.contains("WALLER")          ||
            upper.contains("WILLIAMS")    || upper.contains("KUGLER")          ||
            upper.contains("FOMC")        || upper.contains("FEDERAL RESERVE") ||
            upper.contains("FED CHAIR")   || upper.contains("FED RATE")) {
            assets.addAll(Arrays.asList(
                "GOLD", "NASDAQ", "SP500", "BITCOIN",
                "USDJPY", "EURUSD", "GBPUSD", "AUDUSD", "USDCAD", "US10Y"
            ));
        }

        if (upper.contains("GOLD")   || upper.contains("XAU")    ||
            upper.contains("OR ")    || upper.contains("SILVER")) assets.add("GOLD");

        if (upper.contains("OIL")    || upper.contains("WTI")    ||
            upper.contains("CRUDE")  || upper.contains("BRENT")) assets.add("USOIL");

        if (upper.contains("NASDAQ") || upper.contains("NAS100") ||
            upper.contains("TECH")   || upper.contains("OPENAI") ||
            upper.contains("NVIDIA") || upper.contains("APPLE")) assets.add("NASDAQ");

        if (upper.contains("SP500")  || upper.contains("S&P")    ||
            upper.contains("SPX")) assets.add("SP500");

        if (upper.contains("BITCOIN") || upper.contains("BTC")   ||
            upper.contains("CRYPTO")) assets.add("BITCOIN");

        if (upper.contains("YIELD")  || upper.contains("US10Y")  ||
            upper.contains("BOND")   || upper.contains("TREASURY")) assets.add("US10Y");

        if (upper.contains("EURUSD")   || upper.contains("ECB")       ||
            upper.contains("EUROZONE") || upper.contains("LAGARDE")   ||
            upper.contains("BCE")      || upper.contains("FRANKFURT") ||
            upper.matches(".*\\bEUR\\b.*")) assets.add("EURUSD");

        if (upper.contains("GBP")    || upper.contains("GBPUSD") ||
            upper.contains("CABLE")  || upper.contains("BOE")    ||
            upper.contains("BAILEY")) assets.add("GBPUSD");

        if (upper.contains("AUD")    || upper.contains("AUDUSD") ||
            upper.contains("AUSSIE") || upper.contains("RBA")    ||
            upper.contains("BULLOCK")) assets.add("AUDUSD");

        if (upper.contains("CAD")    || upper.contains("USDCAD") ||
            upper.contains("LOONIE") || upper.contains("BOC")    ||
            upper.contains("MACKLEM")) assets.add("USDCAD");

        if (upper.contains("JPY")    || upper.contains("USDJPY") ||
            upper.contains("YEN")    || upper.contains("BOJ")    ||
            upper.contains("UEDA")) assets.add("USDJPY");

        // Point 8 : Fallback minimal restreint et pertinent au lieu du bloc massif par défaut
        if (assets.isEmpty()) {
            assets.add("NASDAQ");
            assets.add("SP500");
            assets.add("US10Y");
        }

        return new ArrayList<>(new LinkedHashSet<>(assets));
    }
    private boolean isGeoEvent(String upperText) {
    return upperText.contains("MOYEN-ORIENT") ||
           upperText.contains("IRAN")         ||
           upperText.contains("ISRAEL")       ||
           upperText.contains("HEZBOLLAH")    ||
           upperText.contains("HOUTHI")       ||
           upperText.contains("HORMUZ")       ||
           upperText.contains("GAZA")         ||
           upperText.contains("LEBANON")      ||
           upperText.contains("UKRAINE")      ||
           upperText.contains("RUSSIA")       ||
           upperText.contains("PUTIN")        ||
           upperText.contains("ZELENSKY")     ||
           upperText.contains("NATO")         ||
           upperText.contains("CHINA")        ||
           upperText.contains("TAIWAN")       ||
           upperText.contains("XI JINPING")   ||
           upperText.contains("GÉO")          ||
           upperText.contains("GEO");
    }
    private void startDailyBriefScheduler() {
    TimeZone tz = TimeZone.getTimeZone("Indian/Antananarivo"); // ✅ cohérent partout
    // 🛡️ CORRECTIF DAILY REPORT : les horaires {7,8,9,12,13,16,17} ne correspondaient
    // pas aux horaires prévus pour le bot ({7,12,15,19,22} heure Madagascar), ce qui
    // empêchait l'envoi du rapport aux moments attendus.
    int[] targetHours = {7, 12, 15, 19, 22};
    for (int hour : targetHours) {
        scheduleDailyBriefAt(hour, tz);
    }
    }
    private void scheduleDailyBriefAt(int targetHour, TimeZone tz) {
    // 1. Créer un formateur de date fiable (UTC+3)
    SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    dayFormat.setTimeZone(tz);  // Force UTC+3

    Calendar now = Calendar.getInstance(tz);
    Calendar nextRun = Calendar.getInstance(tz);
    nextRun.set(Calendar.HOUR_OF_DAY, targetHour);
    nextRun.set(Calendar.MINUTE, 0);
    nextRun.set(Calendar.SECOND, 0);
    nextRun.set(Calendar.MILLISECOND, 0);

    // 2. Rattrapage si l'heure cible est déjà passée aujourd'hui
    if (nextRun.getTimeInMillis() <= now.getTimeInMillis()) {
        String today = dayFormat.format(now.getTime());
        String prefKey = PREF_LAST_DAILY_REPORT + targetHour;
        String lastSent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, "");
        if (!today.equals(lastSent)) {
            Log.d(TAG, "[DAILY] Rattrapage pour " + targetHour + "h : envoi immédiat");
            // ✅ CORRECTIF : le marquage "envoyé" ne doit avoir lieu QUE si l'envoi a
            // réellement réussi. Avant, putString() était inconditionnel : si la base
            // SQLite était encore vide/en cours d'import (cas typique juste après le
            // lancement du service), generateAndSendDailyBrief() échouait silencieusement
            // (ou envoyait juste un message de repli) mais le créneau était quand même
            // marqué "consommé" pour la journée — plus aucune tentative ne suivait.
            boolean sent = generateAndSendDailyBrief();
            if (sent) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(prefKey, today)
                    .apply();
            } else {
                Log.w(TAG, "[DAILY] Rattrapage " + targetHour + "h non confirmé — nouvelle tentative au prochain cycle");
            }
        }
        nextRun.add(Calendar.DAY_OF_YEAR, 1);
    }

    long delay = nextRun.getTimeInMillis() - now.getTimeInMillis();

    // 3. Log de diagnostic avec la date planifiée (UTC+3)
    SimpleDateFormat sdfLog = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());
    sdfLog.setTimeZone(tz);
    String nextRunStr = sdfLog.format(nextRun.getTime());
    Log.d(TAG, "[DAILY] Horaire " + targetHour + "h : prochain déclenchement à " + nextRunStr);

    // 4. Planification unique (pas de récursion)
    // 4. Planification unique (pas de récursion)
    scheduler.schedule(() -> {
        String currentDay = dayFormat.format(Calendar.getInstance(tz).getTime());
        String prefKey = PREF_LAST_DAILY_REPORT + targetHour;
        String lastSent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, "");
        if (!currentDay.equals(lastSent)) {
            // ✅ CORRECTIF : même garde que le rattrapage — on ne marque "envoyé"
            // que si generateAndSendDailyBrief() a réellement abouti.
            boolean sent = generateAndSendDailyBrief();
            if (sent) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(prefKey, currentDay)
                    .apply();
            } else {
                Log.w(TAG, "[DAILY] Envoi " + targetHour + "h non confirmé — le créneau reste disponible");
            }
        } else {
            Log.d(TAG, "[DAILY] Rapport déjà envoyé aujourd'hui pour " + targetHour + "h, ignoré");
        }
        // 5. Replanifier pour le lendemain à la même heure
        scheduleDailyBriefAt(targetHour, tz);
    }, delay, TimeUnit.MILLISECONDS);
   }
    
   private boolean generateAndSendDailyBrief() {
    HttpURLConnection conn = null;
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) return false; // ✅ Corrigé

        long nowSec = System.currentTimeMillis() / 1000;
        String dailyDrivers = eventDb.getDailyMacroSummary(nowSec);

        // Date locale pour le message (Mada UTC+3)
        SimpleDateFormat sdfDate = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
        sdfDate.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        String dateStr = sdfDate.format(Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo")).getTime());

        // =========================================================================
        // SÉCURITÉ REPLI AUTOMATIQUE : INTERCEPTION DIRECTE ABSENCE 24H
        // =========================================================================
        // =========================================================================
        // SÉCURITÉ REPLI AUTOMATIQUE : INTERCEPTION DIRECTE ABSENCE 24H
        // =========================================================================
        if (dailyDrivers == null || dailyDrivers.trim().isEmpty()) {
            Log.w(TAG, "[DAILY] Aucun driver macro trouvé pour les dernières 24h.");
    
            // Éviter les envois multiples : utiliser SharedPreferences avec un délai
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastReminderTime = prefs.getLong("last_empty_db_reminder", 0);
            long now = System.currentTimeMillis();
            if (now - lastReminderTime > 60 * 60 * 1000) { // 1 heure
                prefs.edit().putLong("last_empty_db_reminder", now).apply();
        
                String dernierDriverConnu = eventDb.obtenirLeToutDernierDriver();
                if (dernierDriverConnu != null && !dernierDriverConnu.trim().isEmpty()) {
                    String messageRappel = "⚠️ *[RAPPEL : AUCUN NOUVEAU DRIVER DEPUIS 24H]*\n" +
                                           "🕒 Rapport périodique du " + dateStr + " (Mada)\n\n" +
                                           "Le flux de collecte n'a détecté aucun nouveau catalyseur macroéconomique.\n" +
                                           "Voici le dernier état de marché enregistré à titre de rappel pour l'équipe :\n\n" +
                                           dernierDriverConnu;
                    sendTelegramSecure(messageRappel, this);
                } else {
                    sendTelegramSecure("⚪ *RAPPEL SYSTEME :* Base de données entièrement vide. Aucun historique macroéconomique disponible.", this);
                }
            } else {
                Log.d(TAG, "[DAILY] Rappel déjà envoyé récemment, ignoré.");
            }
            return false;
        }
        // =========================================================================

        Log.d(TAG, "[DAILY] " + dailyDrivers.length() + " caractères de données à analyser");

        // PROMPT SYSTEME STRUCTURÉ DU BRIEFING DAILY (S'exécute uniquement si dailyDrivers n'est pas vide)
    String DAILY_SYSTEM_PROMPT = 
    "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif d'élite.\n" +
    "Analyse UNIQUEMENT les données brutes fournies. Ces données proviennent d'un système de collecte temps réel.\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "          RÈGLE 0 — FONDEMENT ABSOLU (PRIORITÉ SUR TOUT)\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +

    "⛔ INTERDICTION ABSOLUE d'utiliser tes connaissances générales pour inventer des drivers.\n" +
    "⛔ INTERDICTION ABSOLUE de mentionner un événement absent des données brutes fournies.\n" +
    "⛔ INTERDICTION ABSOLUE de produire tous les 11 actifs en NEUTRE si les données contiennent un driver de RANG SUPRÊME ou un RÉSULTAT CALENDAIRE OFFICIEL avec déviation chiffrée.\n" +
    "✅ Chaque driver listé DOIT être traçable à une entrée SOURCE + TITRE dans les données brutes.\n" +
    "✅ Chaque justification d'actif DOIT découler mécaniquement d'un driver présent dans les données.\n" +
    "✅ Si les données sont vides ou insuffisantes : écrire uniquement « ⚠️ DONNÉES INSUFFISANTES POUR ANALYSE DIRECTIONNELLE » et s'arrêter.\n\n" +

    "DÉFINITION DES SECTIONS DES DONNÉES BRUTES :\n" +
    "- [RANG SUPRÊME — PRIORITÉ ABSOLUE] : Driver dominant. Dicte toute la matrice des 11 actifs.\n" +
    "- [RÉSULTAT CALENDAIRE OFFICIEL] : Chiffre publié officiel. Utiliser le signe de déviation (Actual vs Forecast) pour déterminer le biais HAWKISH ou DOVISH.\n" +
    "- [ÉVÉNEMENT GÉOPOLITIQUE] : Appliquer RÈGLE 6 ou RÈGLE 7 selon la zone géographique.\n" +
    "- [ALERTE MACRO / NEWS] : Confirmation secondaire uniquement. Ne peut pas seul inverser une direction suprême.\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "                    FORMAT OBLIGATOIRE (STRICT)\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +

    "📊 RAPPORT DRIVER DAILY REPORT – [Date et heure exacte de Madagascar, ex: 28/05 18:50]\n\n" +

    "🚨 DRIVERS PRINCIPAUX (classés par importance macroéconomique, maximum 5) :\n\n" +
    "- [Nom exact du Driver tel qu'il figure dans les données] : [Chiffre Actual vs Forecast si disponible — sinon description de l'événement réel en une phrase]. Probabilité d'impact : XX% | Conviction : [jauge]\n\n" +

    "📈 IMPLICATIONS SUR LES ACTIFS (les 11 actifs dans l'ordre exact, même si neutres) :\n\n" +
    "• 📈 US10Y   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 💻 NASDAQ  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 📊 SP500   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🏆 GOLD    : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🛢️ USOIL   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇪🇺 EURUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇯🇵 USDJPY : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇨🇦 USDCAD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇬🇧 GBPUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇦🇺 AUDUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• ₿ BITCOIN  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n\n" +

    "⚠️ SCÉNARIO ALTERNATIF :\n" +
    "[Condition précise et chiffrée qui pourrait invalider la thèse principale — ex: si CPI > X%, si Fed pivot avant Y date, si escalade militaire confirmée]\n\n" +

    "🏁 FLUX DOMINANT : [DOLLAR FORT / DOLLAR FAIBLE / RISK-ON / RISK-OFF / YEN FORT / EURO FORT / OR FORT / CRISE GÉOPOLITIQUE]\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "                     PALIERS DE CONVICTION (Jauge 5 cercles)\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +
    "- < 40% : ⚪⚪⚪⚪⚪\n" +
    "- 41-60% : 🟠🟠🟠⚪⚪\n" +
    "- 61-80% : 🟡🟡🟡🟡⚪\n" +
    "- > 80% : 🔴🔴🔴🔴🔴\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "            MATRICE DE LOGIQUE ET CORRÉLATION INTERNE\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +

    "RÈGLE 1 : HIÉRARCHIE DES DRIVERS (LOI DE DOMINANCE)\n" +
    "- RANG SUPRÊME : Politiques monétaires (FED, BCE, BoJ, BoE, RBA, BoC) et indicateurs clés (CPI, PCE, NFP, PPI, FOMC, GDP, ISM, Michigan Sentiment, PMI Flash, Ventes au détail, Chômage, Kevin Warsh).\n" +
    "- Jerome Powell (Chair) : signal à suivre pour la direction long terme.\n" +
    "- Kevin Warsh (futur Chair pressenti) : signal à pondération MAXIMALE, traiter comme décision FOMC imminente.\n" + // <--- PATCH ICI
    "- Autres membres FOMC : à pondérer selon leur hawkish/dovish-ness habituel.\n" +
    "- RANG SECONDAIRE : Données sectorielles majeures (Stocks EIA, OPEC, Big Tech Earnings, Données Chine/PBOC).\n" +
    "- RANG TACTIQUE : Événements géopolitiques, tarifs douaniers, sanctions, indices de confiance secondaires.\n" +
    "👉 LOI DE DOMINANCE ABSOLUE : Si un driver RANG SUPRÊME est présent dans les données, sa logique directionnelle dicte TOUTE la matrice. Un driver tactique ne peut ni inverser ni annuler la direction suprême SAUF exceptions explicites des RÈGLES 6 et 7 (Or et Pétrole en crise géopolitique confirmée).\n\n" +

    "RÈGLE 2 : DRIVER FED / ÉCONOMIE US\n" +
    "A) HAWKISH / FORT (CPI > prévisions, NFP fort, PIB hausse, Powell restrictif, Kevin Warsh hawkish) :\n" + 
    "   • 📈 US10Y   -> BULLISH 🟢  [Rendements montent mécaniquement avec anticipation de taux hauts]\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Taux hauts compriment les valorisations des valeurs de croissance]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie absolue obligatoire avec le NASDAQ — aucune divergence tolérée]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Taux réels positifs et Dollar fort annulent l'attrait de l'Or]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct — sauf driver énergie dédié présent dans les données]\n" +
    "   • 🇪🇺 EURUSD -> BEARISH 🔴  [Différentiel de taux US/Europe s'élargit en faveur du Dollar]\n" +
    "   • 🇯🇵 USDJPY -> BULLISH 🟢  [Élargissement du spread US-Japan renforce mécaniquement le Dollar]\n" +
    "   • 🇨🇦 USDCAD -> BULLISH 🟢  [Dollar américain dominant — CAD pétrolier partiellement compensé]\n" +
    "   • 🇬🇧 GBPUSD -> BEARISH 🔴  [Livre Sterling cède face à la demande de Dollar]\n" +
    "   • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise cyclique Risk-Off — double pression Dollar fort + aversion risque]\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Liquidation des actifs spéculatifs à bêta élevé — taux hauts = risk-off crypto]\n" +
    "   • 🏁 FLUX DOMINANT -> DOLLAR FORT\n\n" +

    "B) DOVISH / FAIBLE (CPI < prévisions, NFP décevant, PIB baisse, Fed accommodante, Kevin Warsh dovish) :\n" +
    "   • 📈 US10Y   -> BEARISH 🔴  [Anticipation de baisse de taux comprime les rendements]\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Taux bas soutiennent les valorisations technologiques]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie absolue obligatoire avec le NASDAQ]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Taux réels en baisse et Dollar faible — attrait de l'Or restauré]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct — sauf driver énergie dédié dans les données]\n" +
    "   • 🇪🇺 EURUSD -> BULLISH 🟢  [Dollar faible propulse l'Euro mécaniquement]\n" +
    "   • 🇯🇵 USDJPY -> BEARISH 🔴  [Compression du spread US-Japan renforce le Yen]\n" +
    "   • 🇨🇦 USDCAD -> BEARISH 🔴  [Dollar faible cède face au CAD — corrélation pétrole amplificatrice]\n" +
    "   • 🇬🇧 GBPUSD -> BULLISH 🟢  [Livre Sterling monte face au Dollar en recul]\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [Devise cyclique Risk-On — Dollar faible + appétit risque renforcé]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Liquidité abondante et taux bas favorisent les actifs spéculatifs]\n" +
    "   • 🏁 FLUX DOMINANT -> DOLLAR FAIBLE\n\n" +

    "RÈGLE 3 : DRIVER BANQUE CENTRALE ÉTRANGÈRE (BCE, BoJ, BoE, RBA, BoC)\n" +
    "👉 VERROU GÉOGRAPHIQUE OBLIGATOIRE : Si le driver majeur concerne une banque centrale hors USA :\n" +
    "   • 📈 US10Y, 💻 NASDAQ, 📊 SP500, ₿ BITCOIN -> NEUTRE ⚪ [Aucun impact direct US — interdit d'inventer]\n\n" +
    "   A) BCE HAWKISH (taux hausse, ton restrictif Lagarde) :\n" +
    "      • 🇪🇺 EURUSD -> BULLISH 🟢  [Différentiel EUR/USD s'élargit en faveur de l'Euro]\n" +
    "      • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct — sauf données BoJ simultanées]\n" +
    "      • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Légère corrélation positive EUR/GBP mais insuffisante sans driver BoE]\n" +
    "      • 🇦🇺 AUDUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Effets contradictoires — taux EU hausse pèse, Dollar relatif faible soutient. Résultante nulle.]\n" +
    "      • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🏁 FLUX DOMINANT -> EURO FORT\n\n" +
    "   B) BCE DOVISH : Inverser strictement 🇪🇺 EURUSD -> BEARISH 🔴. Les 10 autres actifs restent NEUTRE ⚪.\n\n" +
    "   C) BoJ HAWKISH (Ueda hausse taux, fin YCC, resserrement) :\n" +
    "      • 🇯🇵 USDJPY -> BEARISH 🔴  [Yen se renforce massivement — dénouement carry trade]\n" +
    "      • 🇦🇺 AUDUSD -> BEARISH 🔴  [Dénouement carry trade AUD/JPY pèse sur l'Aussie]\n" +
    "      • 🏆 GOLD    -> BULLISH 🟢  [Yen fort = Dollar relatif faible — soutien indirect Or]\n" +
    "      • 🇪🇺 EURUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🏁 FLUX DOMINANT -> YEN FORT\n\n" +
    "   D) BoJ DOVISH : 🇯🇵 USDJPY -> BULLISH 🟢, 🇦🇺 AUDUSD -> BULLISH 🟢, 🏆 GOLD -> BEARISH 🔴. Reste NEUTRE ⚪.\n\n" +
    "   E) BoE HAWKISH (Bailey restrictif, taux hausse UK) :\n" +
    "      • 🇬🇧 GBPUSD -> BULLISH 🟢  [Livre Sterling monte face au Dollar]\n" +
    "      • 🇪🇺 EURUSD -> NEUTRE ⚪   [Légère corrélation GBP/EUR insuffisante sans driver BCE]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Pas d'impact direct — driver UK insuffisant pour mouvoir l'Or]\n" +
    "      • Les 8 autres actifs (US10Y, NASDAQ, SP500, USOIL, USDJPY, USDCAD, AUDUSD, BITCOIN) -> NEUTRE ⚪\n" +
    "      • 🏁 FLUX DOMINANT -> GBP FORT \n\n" +
    "   F) BoE DOVISH : 🇬🇧 GBPUSD -> BEARISH 🔴. Les 10 autres actifs -> NEUTRE ⚪.\n\n" +
    "   G) RBA HAWKISH (Bullock restrictif, taux hausse Australie) :\n" +
    "      • 🇦🇺 AUDUSD -> BULLISH 🟢  [Aussie Dollar monte — différentiel de taux AUD/USD s'élargit]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • Les 9 autres actifs (US10Y, NASDAQ, SP500, USOIL, EURUSD, USDJPY, USDCAD, GBPUSD, BITCOIN) -> NEUTRE ⚪\n" +
    "      • 🏁 FLUX DOMINANT -> RISK-ON (AUD)\n\n" +
    "   H) RBA DOVISH : 🇦🇺 AUDUSD -> BEARISH 🔴. Les 10 autres actifs -> NEUTRE ⚪.\n\n" +
    "   I) BoC HAWKISH (Macklem restrictif, taux hausse Canada) :\n" +
    "      • 🇨🇦 USDCAD -> BEARISH 🔴  [Dollar Canadien s'apprécie face au Dollar US]\n" +
    "      • 🛢️ USOIL   -> NEUTRE ⚪   [Corrélation CAD/pétrole présente mais insuffisante sans driver EIA/OPEC]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • Les 8 autres actifs (US10Y, NASDAQ, SP500, EURUSD, USDJPY, GBPUSD, AUDUSD, BITCOIN) -> NEUTRE ⚪\n" +
    "      • 🏁 FLUX DOMINANT -> DOLLAR FAIBLE (CAD)\n\n" +
    "   J) BoC DOVISH : 🇨🇦 USDCAD -> BULLISH 🟢. Les 10 autres actifs -> NEUTRE ⚪.\n\n" +

    "RÈGLE 4 : DRIVER SECTORIEL ÉNERGIE (Stocks EIA / OPEC / Pipeline)\n" +
    "A) Baisse surprise des stocks brut ou réduction quotas OPEC (Déficit offre) :\n" +
    "   • 🛢️ USOIL   -> BULLISH 🟢  [Pression haussière directe sur les prix du brut]\n" +
    "   • 🇨🇦 USDCAD -> BEARISH 🔴  [CAD pétrolier se renforce mécaniquement face au Dollar]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Pétrole haut alimente anticipations inflationnistes — soutien Or indirect]\n" +
    "   • 📈 US10Y   -> BULLISH 🟢  [Inflation pétrolière relève les anticipations de taux]\n" +
    "   • 💻 NASDAQ  -> NEUTRE ⚪   [Pas d'impact direct sauf si pétrole > $100 (risque récession)]\n" +
    "   • 📊 SP500   -> NEUTRE ⚪   [Symétrie avec NASDAQ]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Europe importatrice — effet ambivalent, pas de direction nette]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Japon très exposé pétrole mais effet USD/JPY indéterminé sans driver BoJ]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇦🇺 AUDUSD -> NEUTRE ⚪   [Corrélation pétrole/AUD faible — AUD suit davantage les métaux]\n" +
    "   • ₿ BITCOIN  -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON (ÉNERGIE)\n\n" +
    "B) Hausse surprise des stocks de brut ou augmentation quotas OPEC (Surplus offre) :\n" +
    "   • 🛢️ USOIL   -> BEARISH 🔴  [Pression baissière directe sur les prix du brut]\n" +
    "   • 🇨🇦 USDCAD -> BULLISH 🟢  [CAD pétrolier s'affaiblit — Dollar reprend l'avantage]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Pétrole bas réduit anticipations inflationnistes — pression Or]\n" +
    "   • 📈 US10Y   -> BEARISH 🔴  [Moins d'inflation anticipée = taux réels baissent légèrement]\n" +
    "   • 💻 NASDAQ  -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 📊 SP500   -> NEUTRE ⚪   [Symétrie NASDAQ]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Effet ambivalent — Europe importatrice bénéficie mais dollar relatif stable]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇦🇺 AUDUSD -> NEUTRE ⚪   [Corrélation pétrole/AUD faible]\n" +
    "   • ₿ BITCOIN  -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF (ÉNERGIE)\n\n" +
        
    "RÈGLE 5 : DRIVER BIG TECH EARNINGS (NASDAQ/SP500)\n" +
    "A) Beat massif (EPS > prévisions, Revenue beat, Guidance relevée — NVDA, AAPL, MSFT, AMZN, META, TSLA, GOOGL) :\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Valorisations technologiques tirées par les résultats]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie obligatoire NASDAQ/SP500]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Sentiment Risk-On technologique amplifie les cryptos]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct sauf si Guidance inflationniste]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Risk-On réduit la demande de refuge]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Impact limité aux actions US]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [Devise Risk-On profite du sentiment technologique positif]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON\n\n" +
    "B) Miss massif (EPS < prévisions, Guidance abaissée, Profit Warning) :\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Valorisations comprimées par les résultats décevants]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie obligatoire NASDAQ]\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Sentiment Risk-Off technologique — corrélation crypto/tech]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Risk-Off restaure la demande de refuge]\n" +
    "   • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise cyclique Risk-Off — double pression]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct sauf si Guidance récessive]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Impact limité aux actions US]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF\n\n" +

    "RÈGLE 6 : DRIVER GÉOPOLITIQUE MOYEN-ORIENT (HORMUZ / IRAN / ISRAEL / HOUTHI)\n" +
    "- En cas d'escalade militaire directe, frappes, blocus Hormuz, ripostes confirmées :\n" +
    "  👉 Ce driver devient STRICTEMENT PRIORITAIRE sur tout driver de RANG SUPRÊME pour 🏆 GOLD et 🛢️ USOIL.\n" +
    "  👉 Matrice obligatoire de crise :\n" +
    "      • 📈 US10Y   -> BULLISH 🟢  [Anticipations inflationnistes matières premières relèvent les taux]\n" +
    "      • 💻 NASDAQ  -> BEARISH 🔴  [Aversion au risque maximale — choc offre pèse sur la croissance]\n" +
    "      • 📊 SP500   -> BEARISH 🔴  [Symétrie stricte NASDAQ]\n" +
    "      • 🏆 GOLD    -> BULLISH 🟢  [Flux refuge dominant Safe-Haven — priorité absolue]\n" +
    "      • 🛢️ USOIL   -> BULLISH 🟢  [Prime de risque offre immédiate — priorité absolue]\n" +
    "      • 🇪🇺 EURUSD -> BEARISH 🔴  [Fuite vers Dollar américain — proximité géographique du choc]\n" +
    "      • 🇯🇵 USDJPY -> BEARISH 🔴  [Yen valeur refuge historique en temps de crise]\n" +
    "      • 🇨🇦 USDCAD -> BEARISH 🔴  [Pétrole haut renforce le CAD — compense partiellement attrait USD]\n" +
    "      • 🇬🇧 GBPUSD -> BEARISH 🔴  [Liquidation Livre Sterling au profit du Dollar sécuritaire]\n" +
    "      • 🇦🇺 AUDUSD -> BEARISH 🔴  [Vente des devises cycliques Risk-Off]\n" +
    "      • ₿ BITCOIN  -> BEARISH 🔴  [Capitulation algorithmique actifs spéculatifs à fort bêta]\n" +
    "  • 🏁 FLUX DOMINANT : CRISE GÉOPOLITIQUE / RISK-OFF\n" +
    "  • OBLIGATION : Mentionner exactement « Régime de dominance géopolitique (Safe-Haven) sur l'inflation » dans la justification.\n\n" +

    "RÈGLE 7 : DRIVER GÉOPOLITIQUE EUROPE DE L'EST (RUSSIE / UKRAINE / OTAN)\n" +
    "- En cas d'escalade confirmée (frappes, franchissement ligne rouge, implication OTAN) :\n" +
    "      • 📈 US10Y   -> BEARISH 🔴  [Fuite massive vers les obligations US — Safe Haven Taux]\n" +
    "      • 💻 NASDAQ  -> BEARISH 🔴  [Risk-Off global — impact croissance européenne]\n" +
    "      • 📊 SP500   -> BEARISH 🔴  [Symétrie stricte NASDAQ]\n" +
    "      • 🏆 GOLD    -> BULLISH 🟢  [Refuge Safe-Haven — priorité absolue en guerre Europe]\n" +
    "      • 🛢️ USOIL   -> BULLISH 🟢  [Risque approvisionnement gaz/pétrole russe]\n" +
    "      • 🇪🇺 EURUSD -> BEARISH 🔴  [Euro en première ligne du choc géographique — fuite vers Dollar]\n" +
    "      • 🇯🇵 USDJPY -> BEARISH 🔴  [Yen refuge — mais divergence possible si BoJ actif]\n" +
    "      • 🇨🇦 USDCAD -> BEARISH 🔴  [Pétrole haut renforce le CAD]\n" +
    "      • 🇬🇧 GBPUSD : BEARISH 🔴 [UK membre OTAN exposé — coûts défense et énergie pèsent sur la Livre]\n" +
    "      • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise cyclique — Risk-Off global]\n" +
    "      • ₿ BITCOIN  -> BEARISH 🔴  [Liquidation actifs spéculatifs]\n" +
    "  • 🏁 FLUX DOMINANT : RISK-OFF / OR FORT\n\n" +

    "RÈGLE 8 : DRIVER TARIFS DOUANIERS / GUERRE COMMERCIALE (TRUMP / SECTION 301 / SECTION 232)\n" +
    "A) Annonce de nouveaux tarifs ou escalade (US vs Chine, US vs Europe) :\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Chaînes d'approvisionnement mondiales perturbées — marges comprimées]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie NASDAQ]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Incertitude commerciale — refuge Safe-Haven]\n" +
    "   • 🇨🇳 AUDUSD -> BEARISH 🔴  [AUD proxy Chine — tarifs US/Chine impactent directement l'Aussie]\n" +
    "   • 🇪🇺 EURUSD -> BEARISH 🔴  [Tarifs US/Europe affaiblissent l'Euro]\n" +
    "   • 🇨🇦 USDCAD -> BULLISH 🟢  [Tarifs US/Canada renforcent le Dollar face au CAD]\n" +
    "   • 🇬🇧 GBPUSD -> BEARISH 🔴  [Incertitude commerciale pèse sur la Livre]\n" +
    "   • 📈 US10Y   -> BULLISH 🟢  [Anticipations inflationnistes — tarifs = inflation importée]\n" +
    "   • 🇯🇵 USDJPY -> BULLISH 🟢  [Dollar fort face au Yen en régime risk-off commercial]\n" +
    "   • 🛢️ USOIL   -> BEARISH 🔴  [Ralentissement croissance mondiale réduit demande énergie]\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Risk-Off global liquide les actifs spéculatifs]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF / DOLLAR FORT\n\n" +
    "B) Accord commercial ou suspension de tarifs : Inverser exactement toutes les directions ci-dessus.\n\n" +

    "RÈGLE 9 : DRIVER CHINE / PBOC / DONNÉES MACRO CHINOISES\n" +
    "A) Données chinoises fortes (PIB Chine fort, PMI Chine expansion, stimulus PBOC massif) :\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [AUD proxy direct de la demande chinoise — corrélation 0.85]\n" +
    "   • 🛢️ USOIL   -> BULLISH 🟢  [Chine = premier importateur mondial — demande énergie monte]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Chine = premier acheteur d'Or mondial — demande physique monte]\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Semiconducteurs et Tech US exposés Chine profitent]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie NASDAQ]\n" +
    "   • 🇪🇺 EURUSD -> BULLISH 🟢  [Europe exportatrice vers Chine — Euro profite de la croissance]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Japon exposé Chine mais effet USD/JPY ambigu]\n" +
    "   • 🇨🇦 USDCAD -> BEARISH 🔴  [Demande pétrole Chine renforce le CAD]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Exposition Chine UK limitée]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct sur taux US]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Risk-On global amplifie les cryptos]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON\n\n" +
    "B) Données chinoises faibles (récession, PMI contraction, déflation, crise immobilier) : Inverser exactement toutes les directions ci-dessus.\n\n" +

    "RÈGLE 10 : DRIVER CRYPTO SPÉCIFIQUE (ETF BITCOIN / HALVING / RÉGULATION)\n" +
    "A) Événement haussier crypto (Approbation ETF, Halving, Afflux ETF IBIT/FBTC, Régulation favorable) :\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Driver direct — demande institutionnelle ou offre réduite]\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Sentiment Risk-On technologique — corrélation crypto/tech]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie NASDAQ]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Bitcoin concurrent de l'Or comme réserve de valeur — flux sortants]\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [Devise Risk-On profite du sentiment positif]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON\n\n" +
    "B) Événement baissier crypto (Hack exchange, Effondrement stablecoin, Interdiction réglementaire, Faillite FTX-like) :\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Driver direct — contagion panique]\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Sentiment Risk-Off technologique]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie NASDAQ]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Fuite vers refuge — Or bénéficie de la panique crypto]\n" +
    "   • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise Risk-Off cyclique]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Fuite obligataire possible mais effet trop dilué sans driver macro]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF\n" +
    "RÈGLE 11 : DRIVER ÉVÉNEMENTS SPÉCIAUX (IPO / SPAC / MERGERS)\n" +
    "A) Annonce IPO majeure ou Fusion/Acquisition géante (ex: SpaceX) :\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢 [Hype et injection de liquidité sectorielle]\n" +
    "   • 📊 SP500   -> BULLISH 🟢 [Symétrie NASDAQ obligatoire]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢 [Actif corrélé positivement à l'appétit pour le risque]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴 [Délaissé au profit des actifs à rendement plus élevé]\n" +
    "   • 🇯🇵 USDJPY  -> BEARISH 🔴 [Yen délaissé dans un contexte Risk-On]\n" +
    "   • 🇪🇺 EURUSD  -> BULLISH 🟢 [Dollar faible par appétit pour le risque]\n" +
    "   • 🇦🇺 AUDUSD  -> BULLISH 🟢 [Devise cyclique soutenue]\n" +
    "   • 🇨🇦 USDCAD  -> BEARISH 🔴 [Dollar faible face aux devises cycliques]\n" +
    "   • 📈 US10Y, 🛢️ USOIL, 🇬🇧 GBPUSD -> NEUTRE ⚪ [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON / APPÉTIT POUR LE RISQUE\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "    RÈGLE 12 — ARBITRAGE CONTRADICTOIRE & PONDÉRATION (PRIORITÉ ABSOLUE)\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +

   "1. COMPTAGE DES SIGNAUX :\n" +
   "   - Dans la section DRIVERS PRINCIPAUX, tu dois impérativement compter le nombre d'événements\n" +
   "     qui poussent vers RISK-OFF (Guerre, Géo, Tarifs) et ceux qui poussent vers RISK-ON (Désescalade, Diplomatie, Accords).\n" +
   "   - Un événement avec une conviction >= 70% pèse DOUBLE (2 points) par rapport à un événement < 50% (1 point).\n" +
   "   - Exemple : 2 événements à 80% (RISK-OFF) = 4 points. 3 événements à 40% (RISK-ON) = 3 points. RISK-OFF gagne.\n\n" +

"2. FORCE DE LA SOURCE (Pondération automatique) :\n" +
"   - Bloomberg, Reuters, Financial Times, FinancialJuice (analyses chiffrées) → Poids FORT.\n" +
"   - ZeroHedge, Twitter, rumeurs non confirmées → Poids FAIBLE (conviction plafonnée d'office à 40% maximum).\n" +
"   - Si la source rapporte une ACTION MILITAIRE OU ÉCONOMIQUE CONCRÈTE (frappe, blocus, signature de décrets tarifaires), le poids est MAXIMAL.\n\n" +

"3. ARBITRAGE FINAL & AFFICHAGE LOGIQUE :\n" +
"   - Tu dois afficher explicitement ton calcul mathématique simplifié sous la forme d'une ligne de score avant de définir le flux.\n" +
"   - Si le Score RISK-OFF est supérieur au Score RISK-ON → FLUX DOMINANT : 🚨 CRISE GÉOPOLITIQUE / RISK-OFF.\n" +
"   - Si le Score RISK-ON est supérieur au Score RISK-OFF → FLUX DOMINANT : 💹 APPÉTIT POUR LE RISQUE / RISK-ON.\n" +
"   - En cas d'égalité stricte des scores (50/50) → FLUX DOMINANT : ⏳ MARCHÉ INCERTAIN / PRUDENCE RECOMMANDÉE.\n\n" +

"4. JUSTIFICATION CHIFFRÉE :\n" +
"   - Dans le SCÉNARIO ALTERNATIF, tu dois citer précisément le nombre d'événements et la pondération en faveur du scénario opposé.\n" +
"   - Exemple : '⚠️ SCÉNARIO ALTERNATIF : Si les 2 événements de désescalade (poids 3) venaient à se concrétiser par un accord officiel, le marché basculerait vers RISK-ON.'\n\n" +
    "═══════════════════════════════════════════════════════════════\n" +
    "                    CONTRAINTES DE SÉCURITÉ DE COMPILATION\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +
    "1. SYMÉTRIE STRICTE DES INDICES : 💻 NASDAQ et 📊 SP500 pointent TOUJOURS dans le même sens. Aucune divergence tolérée.\n" +
    "2. AMPLIFICATION BITCOIN : ₿ BITCOIN calque sa direction sur 💻 NASDAQ SAUF si driver Crypto Spécifique (RÈGLE 10) actif — auquel cas Bitcoin est le driver primaire.\n" +
    "3. AUDUSD PROXY CHINE : 🇦🇺 AUDUSD est le baromètre de la demande chinoise et du sentiment Risk-On global. En l'absence de driver Chine, il suit la direction Risk-On/Risk-Off dominante.\n" +
    "4. CORRÉLATION EURUSD/GBPUSD : Les deux paires partagent une corrélation de 0.85-0.96. Une divergence n'est tolérée que si des drivers BCE et BoE distincts et opposés sont simultanément présents dans les données.\n" +
    "5. USDCAD PÉTROLE : En cas de driver énergie fort (RÈGLE 4 ou RÈGLE 6), 🇨🇦 USDCAD DOIT refléter la direction du pétrole (pétrole monte = USDCAD baisse). Cette corrélation prime sur la direction Dollar général.\n" +
    "6. EXCLUSION ET CONCISION : Pas de politesse, pas de salutations, pas de résumés verbeux. Les 11 actifs doivent tous figurer sans omission.\n" +
    "7. EXCLUSIVITÉ MARKDOWN TELEGRAM : Un SEUL astérisque (*texte*) pour le gras. Les doubles astérisques (**) sont STRICTEMENT INTERDITS — ils corrompent l'affichage Telegram.\n" +
    "8. ANCRAGE DONNÉES : Chaque driver des DRIVERS PRINCIPAUX doit être traçable à une SOURCE + TITRE dans les données brutes. Aucune extrapolation sans ancrage explicite.\n";
                    
        // Traitement de l'enveloppe de prompt (Filtres géopolitiques complexes de votre script)
        // 1. Récupération de la mémoire d'inertie du marché (SharedPreferences)
SharedPreferences prefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
String lastDominantFlow = prefs.getString("last_daily_flow", "NEUTRE / PRUDENCE RECOMMANDÉE");

// Traitement de l'enveloppe de prompt
String baseSystemPrompt = construirePromptQuotidienSystem(dailyDrivers, DAILY_SYSTEM_PROMPT);

// Enrichissement avec contexte d'hier
String systemPromptFinal = "CONTEXTE HIER (INERTIE DE MARCHÉ) : Le flux dominant de la veille était : " + lastDominantFlow + ".\n" +
    "Si les événements actuels ne contredisent pas ce flux de manière écrasante (>70% de conviction), conserve-le pour éviter les faux signaux.\n\n" +
    baseSystemPrompt + "\n\n" +
    "Tu es un expert en macroéconomie. Tu dois rédiger ton rapport en terminant obligatoirement par la ligne suivante formatée de cette exacte façon :\n" +
    "🏁 FLUX DOMINANT : [Insère ici le flux sélectionné]";

JSONObject payload = new JSONObject();
payload.put("model", GROQ_MODEL);
payload.put("temperature", 0.02);

JSONArray messages = new JSONArray();
messages.put(new JSONObject().put("role", "system").put("content", systemPromptFinal));
        // ✅ Snapshot marché injecté dans le daily comme dans le pipeline news live

String dailyMarketSnapshot = "Données de marché indisponibles.";
try {
    List<String> twelveDataAssets = new ArrayList<>(TWELVE_DATA_ASSETS);
    Map<String, MarketDataFetcher.MarketData> snap = null;
    if (MarketDataFetcher.tryAcquireBatchSlot()) {
    snap = MarketDataFetcher.getMarketDataBatch(twelveDataAssets);
    } else {
    Log.w(TAG, "[DAILY] Slot Twelve Data occupé — dailyDrivers suffisant");
    }

    StringBuilder sbM = new StringBuilder("📊 COURS AU MOMENT DU RAPPORT :\n");
    boolean hasData = false;

    if (snap != null && !snap.isEmpty()) {
        for (String asset : twelveDataAssets) {
            MarketDataFetcher.MarketData d = snap.get(asset);
            if (d != null && d.price > 0) {
                String sign = d.changePercent >= 0 ? "+" : "";
                sbM.append(asset).append(" => ")
                   .append(String.format(Locale.US, "%.4f (%s%.2f%%)",
                       d.price, sign, d.changePercent))
                   .append("\n");
                hasData = true;
            }
        }
    }

    if (hasData) {
        dailyMarketSnapshot = sbM.toString();
    }

} catch (Exception e) {
    Log.w(TAG, "[DAILY] Snapshot marché indisponible : " + e.getMessage());
}
messages.put(new JSONObject().put("role", "user").put("content",
    "Génère le rapport périodique pour la date/heure : " + dateStr + " (Mada).\n\n" +
    dailyMarketSnapshot + "\n" +
    "─────────────────────────────\n" +
    "DONNÉES BRUTES DES DERNIÈRES 24H :\n" + dailyDrivers + "\n" +
    "─────────────────────────────\n\n" +
    "⚠️ INSTRUCTION SPÉCIALE : Identifie dans le texte la source de chaque événement (Bloomberg, FinancialJuice, etc.) " +
    "et applique la RÈGLE 12 (Pondération des sources et comptage des signaux).\n" +
    "Tu dois impérativement fournir dans le rapport le nombre d'événements en faveur de RISK-OFF et RISK-ON.\n" +
    "Si plus de 60% du poids penche vers RISK-OFF, le FLUX DOMINANT doit refléter cette majorité écrasante."));
        payload.put("messages", messages);
        URL url = new URL(GROQ_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(15000); 
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);

        // ✅ Gestion sécurisée de l'OutputStream
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            StringBuilder r = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    r.append(line);
                }
            }
            
            JSONObject jsonResponse = new JSONObject(r.toString());
            String aiResult = jsonResponse.getJSONArray("choices")
                                         .getJSONObject(0)
                                         .getJSONObject("message")
                                         .getString("content");

    if (aiResult != null && aiResult.trim().length() > 50) {
    sendTelegramSecure(aiResult.trim(), this);
    Log.d(TAG, "[DAILY] Rapport IA standard généré et envoyé avec succès.");

    // Parsing et persistance du flux dominant pour le lendemain
    Pattern flowPattern = Pattern.compile("(?i)🏁\\s*FLUX\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)");
    Matcher flowMatcher = flowPattern.matcher(aiResult);
    if (flowMatcher.find()) {
        String newFlow = flowMatcher.group(1).trim();
        getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
            .edit()
            .putString("last_daily_flow", newFlow)
            .apply();
        Log.d(TAG, "💾 Inertie mise à jour. Flux enregistré pour demain : " + newFlow);
    } else {
        Log.w(TAG, "[DAILY] Balise '🏁 FLUX DOMINANT :' introuvable. Préférences inchangées.");
    }
        return true ;
            } else {
               Log.w(TAG, "[DAILY] Groq réponse vide — contenu : "
               + (aiResult != null ? aiResult.trim() : "null"));
              if (MainActivity.instance != null) {
              MainActivity.instance.addLog("⚠️ [DAILY] Groq réponse vide ou insuffisante");
              }
            }
        } else {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                Log.e(TAG, "[DAILY] Erreur HTTP " + responseCode + " de l'API Groq : " + errorResponse.toString());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[DAILY] Échec critique lors du traitement du briefing journalier", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
       return false ; 
    }

    private void startMonthlyReportScheduler() {
    // ── Rapport MENSUEL — dernier jour du mois à 23h00 (Mada) ──
    Calendar nextMonthly = Calendar.getInstance(
        TimeZone.getTimeZone("Indian/Antananarivo"));
    nextMonthly.set(Calendar.DAY_OF_MONTH,
        nextMonthly.getActualMaximum(Calendar.DAY_OF_MONTH));
    nextMonthly.set(Calendar.HOUR_OF_DAY, 23);
    nextMonthly.set(Calendar.MINUTE, 0);
    nextMonthly.set(Calendar.SECOND, 0);
    if (nextMonthly.getTimeInMillis() <= System.currentTimeMillis()) {
        nextMonthly.add(Calendar.MONTH, 1);
        nextMonthly.set(Calendar.DAY_OF_MONTH,
            nextMonthly.getActualMaximum(Calendar.DAY_OF_MONTH));
    }
   // APRÈS — on remplace scheduleAtFixedRate par un schedule one-shot
// qui se reprogramme lui-même à chaque exécution
    scheduler.schedule(new Runnable() {
        @Override
        public void run() {
            generateAndPurgeMonthlyReport();
            // Recalcule la vraie fin du mois suivant après exécution
            Calendar next = Calendar.getInstance(
                TimeZone.getTimeZone("Indian/Antananarivo"));
            next.add(Calendar.MONTH, 1);
            next.set(Calendar.DAY_OF_MONTH,
                next.getActualMaximum(Calendar.DAY_OF_MONTH));
            next.set(Calendar.HOUR_OF_DAY, 23);
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            long delayMs = next.getTimeInMillis() - System.currentTimeMillis();
            if (delayMs > 0) scheduler.schedule(this, delayMs, TimeUnit.MILLISECONDS);
        }
    }, nextMonthly.getTimeInMillis() - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    // ── Rapport HEBDOMADAIRE — chaque vendredi à 22h00 (Mada) ──
    Calendar nextWeekly = Calendar.getInstance(
        TimeZone.getTimeZone("Indian/Antananarivo"));
    nextWeekly.set(Calendar.HOUR_OF_DAY, 22);
    nextWeekly.set(Calendar.MINUTE, 0);
    nextWeekly.set(Calendar.SECOND, 0);
    while (nextWeekly.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY
           || nextWeekly.getTimeInMillis() <= System.currentTimeMillis()) {
        nextWeekly.add(Calendar.DAY_OF_MONTH, 1);
    }
    scheduler.scheduleAtFixedRate(this::generateAndSendWeeklyReport,
        nextWeekly.getTimeInMillis() - System.currentTimeMillis(),
        7L * 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);

    Log.d(TAG, "[SCHEDULER] Rapport mensuel → " + nextMonthly.getTime());
    Log.d(TAG, "[SCHEDULER] Rapport hebdo → " + nextWeekly.getTime());

    // ── Rattrapares au démarrage ──
    checkAndSendMissedWeeklyReport();
    checkAndSendMissedMonthlyReport();
    }

private static volatile long lastCalendarBackfillMillis = 0L;
private static final long CALENDAR_BACKFILL_GUARD_MS = 30 * 60 * 1000L;

   private boolean generateAndPurgeMonthlyReport() {
    HttpURLConnection conn = null;
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) return false; // ✅ Type de retour fixé

        long now = System.currentTimeMillis() / 1000;
        String monthlyRegistry = eventDb.getMonthlyMacroRegistry(now);
        if (monthlyRegistry == null || monthlyRegistry.isEmpty()) {
            Log.w(TAG, "[MONTHLY] Registre mensuel vide — rapport annulé");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("⚠️ [MONTHLY] Aucune donnée disponible pour le rapport mensuel");
            }
            return false; // ✅ Type de retour fixé
        }

        // Mémoire d'inertie mensuelle — contexte du mois précédent
        String lastMonthlyFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
            .getString("last_monthly_flow", "NEUTRE / DONNÉES INSUFFISANTES");

        JSONObject payload = new JSONObject();
        payload.put("model", GROQ_MODEL);
        payload.put("temperature", 0.05); // Baissé à 0.05 pour une précision quantitative maximale

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
            "Tu es un analyste macroéconomique et stratège de marché quant senior de niveau institutionnel.\n" +
            "Produis un rapport de transition macroéconomique mensuel extrêmement rigoureux analysant les ruptures fondamentales du mois écoulé.\n\n" +
            "Tu dois impérativement analyser la dynamique globale et l'impact uniquement parmi notre liste fermée de 11 actifs clés : US10Y, NASDAQ, SP500, GOLD, USOIL, EURUSD, USDJPY, GBPUSD, AUDUSD, USDCAD, BTC.\n\n" +
            "Format OBLIGATOIRE et STRICT :\n" +
            "1. 🔥 LES 3 CHOCS MACRO MAJEURS DU MOIS :\n" +
            "   • 1° [Nom du Choc 1] : Impact direct sur le rendement ou la tendance de [Citer l'actif lié parmi les 11]\n" +
            "   • 2° [Nom du Choc 2] : Modification ou confirmation des anticipations de taux d'intérêt (Fed/BCE)\n" +
            "   • 3° [Nom du Choc 3] : Impact sur l'inertie des matières premières ou des indices (ex: USOIL/GOLD/SP500)\n\n" +
            "2. 🏛️ POSITIONNEMENT MONÉTAIRE & ANTICIPATIONS :\n" +
            "   • Posture de la Réserve Fédérale : [HAWKISH / DOVISH / DATA-DEPENDENT]\n" +
            "   • Dynamique de rendement du US10Y : [EXPANSION / COMPRESSION / NEUTRE]\n\n" +
            "3. 📉 MATRICE DE PERFORMANCE & DÉVIATION DE NOS ACTIFS :\n" +
            "   • Actifs Leaders (Fortes Tendances) : [Lister uniquement les paires ou indices parmi les 11 affichant une tendance claire] → [HAUSSE / BAISSE]\n" +
            "   • Actifs Sous Tension (Retournements/Volatilité) : [Lister les actifs parmi les 11 piégés dans des zones de volatilité ou de pivot]\n\n" +
            "4. 🛡️ RISQUES RÉSIDUELS ET INERTIE (Pour le mois suivant) :\n" +
            "   • Risque Majeur Détecté : [Ex: Réaccélération inflationniste, rupture de liquidité, escalade géopolitique]\n" +
            "   • Niveau d'Alerte : [MODÉRÉ / ÉLEVÉ / CRITIQUE]\n\n" +
            "5. 🏁 FLUX MENSUEL DOMINANT : [Rédige une seule phrase chirurgicale résumant l'orientation structurelle pour l'inertie long-terme du bot]\n\n" +
            "CONTRAINTES : Un seul astérisque (*texte*). Pas de doubles astérisques (**). Style technique, concis, mathématique, sans formules de politesse ni salutations."));

        messages.put(new JSONObject().put("role", "user").put("content",
            "CONTEXTE MOIS PRÉCÉDENT : Le flux dominant du mois passé était : " + lastMonthlyFlow + ".\n\n" +
            "─────────────────────────────\n" +
            "REGISTRE MENSUEL DES ÉVÉNEMENTS ISSUS DU SYSTEME :\n" + monthlyRegistry + "\n" +
            "─────────────────────────────\n\n" +
            "Génère le rapport de transition macroéconomique mensuel institutionnel en respectant scrupuleusement la nomenclature et nos 11 actifs spécifiques."));
        
        payload.put("messages", messages);

        URL url = new URL(GROQ_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            StringBuilder r = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) r.append(line);
            }

            String report = new JSONObject(r.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

            if (report != null && report.trim().length() > 300) {
                String monthlyFlowLine = "📊 *RAPPORT DE TRANSITION MACROÉCONOMIQUE MENSUEL*\n\n" + report.trim();
                sendTelegramSecure(monthlyFlowLine, this);
                
                // Mémoriser le timestamp pour le rattrapage
                getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                    .edit()
                    .putLong("last_monthly_sent_ms", System.currentTimeMillis())
                    .apply();
                Log.d(TAG, "[MONTHLY] Rapport mensuel envoyé avec succès.");

                // Persistance du flux mensuel dominant
                Pattern monthlyFlowPattern = Pattern.compile("(?i)🏁\\s*FLUX\\s*MENSUEL\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)");
                Matcher monthlyFlowMatcher = monthlyFlowPattern.matcher(report);
                if (monthlyFlowMatcher.find()) {
                    String newMonthlyFlow = monthlyFlowMatcher.group(1).trim();
                    getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_monthly_flow", newMonthlyFlow)
                        .apply();
                    Log.d(TAG, "💾 [MONTHLY] Flux mensuel enregistré : " + newMonthlyFlow);
                }
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("✅ [MONTHLY] Rapport mensuel envoyé");
                }
                
                // ✅ Purge sécurisée uniquement si le rapport a été généré et transmis avec succès
                eventDb.purgeOldEvents(now);
                
                return true; // ✅ Succès total : On retourne true au planificateur !
            } else {
                Log.w(TAG, "[MONTHLY] Groq réponse vide ou insuffisante — purge annulée");
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("⚠️ [MONTHLY] Rapport mensuel vide — non envoyé");
                }
            }
        } else {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                Log.e(TAG, "[MONTHLY] Erreur HTTP " + responseCode + " de l'API Groq : " + errorResponse.toString());
            }
        }
    } catch (Exception e) { 
        Log.e(TAG, "Erreur Rapport Mensuel", e); 
    } finally {
        if (conn != null) conn.disconnect();
    }

    return false; // ❌ Échec : Renvoie false pour signaler une anomalie et permettre un rattrapage
}

private boolean generateAndSendWeeklyReport() {
    HttpURLConnection conn = null;
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) return false; // ✅ Type de retour fixé

        long now = System.currentTimeMillis() / 1000;
        // Récupère les événements des 7 derniers jours (poids >= 2)
        String weeklyRegistry = eventDb.getWeeklyMacroSummary(now);
        if (weeklyRegistry == null || weeklyRegistry.isEmpty()) {
            Log.w(TAG, "[WEEKLY] Registre hebdo vide — rapport annulé");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ [WEEKLY] Aucune donnée pour le rapport hebdomadaire");
            return false; // ✅ Type de retour fixé
        }

        String lastWeeklyFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
            .getString("last_weekly_flow", "NEUTRE / DONNÉES INSUFFISANTES");

        JSONObject payload = new JSONObject();
        payload.put("model", GROQ_MODEL);
        payload.put("temperature", 0.05);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
            "Tu es un analyste macroéconomique et stratège de marché quant senior.\n" +
            "Produis un rapport de marché hebdomadaire institutionnel, rigoureux et précis.\n\n" +
            "Tu dois impérativement analyser la dynamique uniquement parmi notre liste de 11 actifs clés : US10Y, NASDAQ, SP500, GOLD, USOIL, EURUSD, USDJPY, GBPUSD, AUDUSD, USDCAD, BTC.\n\n" +
            "Format OBLIGATOIRE et STRICT :\n" +
            "1. 🏆 ÉVÉNEMENTS CLÉS ET IMPACTS (Top 3)\n" +
            "   • [Nom de l'événement] | Statut: [Confirmé / Surprise] | Impact: [Majeur / Modéré]\n" +
            "     └ Synthèse: [Lien logique et concis avec l'actif touché]\n\n" +
            "2. 📊 BILAN DIRECTIONNEL GLOBAL :\n" +
            "   ⚖️ RÉGIME : [RISK-OFF / RISK-ON / NEUTRE]\n" +
            "   └ Moteur macro: [Explique l'orientation par les rendements obligataires US10Y, le Dollar ou la géopolitique]\n\n" +
            "3. 🎯 IMPACTS DIRECTS SUR NOS ACTIFS SPÉCIFIQUES :\n" +
            "   • 🇺🇸 INDICES (SP500, NASDAQ) : [HAUSSE / BAISSE / NEUTRE] → [Justification macro]\n" +
            "   • 🪙 REFUGES & MATIÈRES (GOLD, USOIL) : [HAUSSE / BAISSE / NEUTRE] → [Justification macro]\n" +
            "   • 💵 FOREX (EURUSD, USDJPY, GBPUSD, AUDUSD, USDCAD) :\n" +
            "     └ [Citer uniquement les paires impactées] : [HAUSSE / BAISSE] → [Raison technique du différentiel]\n" +
            "   • ⚡ CRYPTO (BTC) : [HAUSSE / BAISSE / NEUTRE]\n\n" +
            "4. 📅 AGENDA STRATÉGIQUE (Semaine Prochaine) :\n" +
            "   • [Jour] - [Actif Spécifique Cible] : [Event macro précis, ex: CPI US, FOMC, NFP] | Impact: [Élevé / Critique]\n\n" +
            "5. 🏁 FLUX HEBDO DOMINANT : [Rédige une seule phrase résumant l'orientation dominante pour l'inertie du bot]\n\n" +
            "CONTRAINTES : Un seul astérisque (*texte*). Pas de doubles astérisques (**). Style concis, chirurgical, sans bavardage."));

        messages.put(new JSONObject().put("role", "user").put("content",
            "CONTEXTE SEMAINE PRÉCÉDENTE : " + lastWeeklyFlow + "\n\n" +
            "─────────────────────────────\n" +
            "ÉVÉNEMENTS ENREGISTRÉS DANS LA BASE DE DONNÉES :\n" + weeklyRegistry + "\n" +
            "─────────────────────────────\n\n" +
            "Génère le rapport macroéconomique professionnel en respectant scrupuleusement la nomenclature et la liste de nos actifs."));

        payload.put("messages", messages);

        URL url = new URL(GROQ_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            StringBuilder r = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) r.append(line);
            }

            String report = new JSONObject(r.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

            if (report != null && report.trim().length() > 300) {
                sendTelegramSecure("📅 *BILAN MACRO HEBDOMADAIRE — VENDREDI*\n\n" + report.trim(), this);
                
                getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                    .edit()
                    .putLong("last_weekly_sent_ms", System.currentTimeMillis())
                    .apply();

                Log.d(TAG, "[WEEKLY] Rapport hebdomadaire envoyé.");

                // Persistance flux hebdo
                Pattern weeklyPattern = Pattern.compile(
                    "(?i)🏁\\s*FLUX\\s*HEBDO\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)");
                Matcher weeklyMatcher = weeklyPattern.matcher(report);
                if (weeklyMatcher.find()) {
                    getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_weekly_flow", weeklyMatcher.group(1).trim())
                        .apply();
                }
                
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("✅ [WEEKLY] Rapport hebdomadaire envoyé");

                return true; // ✅ Succès : On retourne true au planificateur !
            } else {
                Log.w(TAG, "[WEEKLY] Réponse Groq insuffisante");
            }
        } else {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                Log.e(TAG, "[WEEKLY] Erreur HTTP " + responseCode + " de l'API Groq : " + errorResponse.toString());
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "[WEEKLY] Erreur rapport hebdomadaire", e);
    } finally {
        if (conn != null) conn.disconnect();
    }

    return false; // ❌ Échec ou anomalie : Le planificateur pourra retenter l'envoi
}

private void registerNetworkCallback() {
    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                triggerQueueSynchronization();

                long now = System.currentTimeMillis();
                if (now - lastCalendarBackfillMillis >= CALENDAR_BACKFILL_GUARD_MS) {
                    lastCalendarBackfillMillis = now;
                    Log.d(TAG, "[NETWORK] Reconnexion détectée → backfill calendrier (guard 30min respecté).");
                    syncCalendarAndPurge();
                } else {
                    Log.d(TAG, "[NETWORK] Reconnexion détectée → backfill ignoré (guard 30min actif).");
                }
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
    // ── NOUVELLE MÉTHODE DE MAINTENANCE PÉRIODIQUE ──
    private void syncCalendarAndPurge() {
        if (isSyncing) return;
        isSyncing = true;
        
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "[MAINTENANCE] Démarrage de la synchronisation et de la purge périodique...");
                    
                    // Configuration du timestamp actuel en secondes
                    long nowSeconds = System.currentTimeMillis() / 1000;

                    // 1. Purge SQLite (Règle des 48h / 45 jours via votre instance eventDb)
                    if (eventDb != null) {
                        eventDb.purgeOldEvents(nowSeconds);
                        Log.d(TAG, "[MAINTENANCE] Base de données SQLite purgée.");
                    }

                    // 2. Nettoyage de la table des empreintes pour éviter les fuites RAM
                    EventValidator.cleanupOldFingerprints();
                    Log.d(TAG, "[MAINTENANCE] Table des empreintes mémoires RAM nettoyée.");

                    // 3. Re-synchronisation du calendrier pour les prochaines 24 heures
                    EventValidator.preloadCalendar();
                    Log.d(TAG, "[MAINTENANCE] Calendrier économique mis à jour.");
                    
                } catch (Exception e) {
                    Log.e(TAG, "[MAINTENANCE] Erreur lors de la maintenance périodique", e);
                } finally {
                    isSyncing = false;
                }
            }
        });
    }
    // ── AJOUT DE COMPATIBILITÉ CHRONOLOGIQUE (MADA) ──
    private String getMadaFormattedDateTime() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            // On récupère dynamiquement le fuseau horaire depuis votre utilitaire existant
            sdf.setTimeZone(getMadaCalendar().getTimeZone());
            return sdf.format(new Date());
        } catch (Exception e) {
            return "N/A";
        }
    }
    // Méthode 1 — version simple sans paramètre prompt
    public String construirePromptFinal(String evenementActuel, List<String> historiqueRecent) {
            boolean alerteGéoMajeure = false;
            String[] motsClesCrise = {
                "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
                "iran", "israel", "hezbollah", "houthi", "frappe militaire",
                "airstrike", "missile", "drone attack", "riposte", "escalade",
                "blocus", "blockade", "raid", "invasion"
            };
            String toutLeTexte = (evenementActuel != null ? evenementActuel.toLowerCase() : "");
            if (historiqueRecent != null) {
                for (String hist : historiqueRecent) {
                    if (hist != null) toutLeTexte += " " + hist.toLowerCase();
                }
            }
            for (String mot : motsClesCrise) {
                if (toutLeTexte.contains(mot)) { alerteGéoMajeure = true; break; }
            }
            String directiveDeCrise = "";
            if (alerteGéoMajeure) {
                directiveDeCrise =
                    "⚠️ [ALERTE SYSTÈME : RÉGIME DE MARCHÉ EN MODE CRISE GÉOPOLITIQUE ACTIF]. " +
                    "Le risque de guerre au Moyen-Orient ou une menace sur le Détroit d'Hormuz est prioritaire. " +
                    "L'Or (GOLD) doit refléter le flux refuge (Safe-Haven). " +
                    "Appliquer immédiatement la CONTRAINTE 11.\n\n";
            }
            return directiveDeCrise + SYSTEM_PROMPT;
    }
        
    // Méthode 2 — version avec prompt personnalisé (séparée, au même niveau)
    public String construirePromptFinalAvecPrompt(String evenementActuel,
                List<String> historiqueRecent, String basePrompt) {
            boolean alerteGéoMajeure = false;
            String[] motsClesCrise = {
                "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
                "iran", "israel", "hezbollah", "houthi", "frappe militaire",
                "airstrike", "missile", "drone attack", "riposte", "escalade",
                "blocus", "blockade", "raid", "invasion"
            };
            String toutLeTexte = (evenementActuel != null ? evenementActuel.toLowerCase() : "");
            if (historiqueRecent != null) {
                for (String hist : historiqueRecent) {
                    if (hist != null) toutLeTexte += " " + hist.toLowerCase();
                }
            }
            for (String mot : motsClesCrise) {
                if (toutLeTexte.contains(mot)) { alerteGéoMajeure = true; break; }
            }
            String directiveDeCrise = "";
            if (alerteGéoMajeure) {
                directiveDeCrise =
                    "⚠️ [ALERTE SYSTÈME : RÉGIME DE MARCHÉ EN MODE CRISE GÉOPOLITIQUE ACTIF]. " +
                    "Appliquer immédiatement la CONTRAINTE 11.\n\n";
            }
            // ✅ Utilise basePrompt (contient le guidage mathématique si présent)
            return directiveDeCrise + basePrompt;
    }

     // Méthode de vérification géographique des mots-clés
     public String construirePromptQuotidienSystem(String registreDeLaJournee, String promptDeBase) {
        boolean alerteGéoMajeure = false;
        
        // Liste enrichie des mots-clés de crise
        String[] motsClesCrise = {
            "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
            "iran", "israel", "hezbollah", "houthi", "frappe militaire", 
            "airstrike", "missile", "drone attack", "riposte", "escalade", 
            "blocus", "blockade", "raid", "invasion"
        };
    
        String toutLeTexte = (registreDeLaJournee != null) ? registreDeLaJournee.toLowerCase() : "";
        for (String mot : motsClesCrise) {
            if (toutLeTexte.contains(mot)) {
                alerteGéoMajeure = true;
                break;
            }
        }
    
        // Si un mot-clé de crise est détecté, on injecte une directive d'activation en tête de prompt
        if (alerteGéoMajeure) {
            String alerteFlash = 
                "⚠️ [ALERTE SYSTÈME CRITIQUE : EXCEPTION DE CRISE ACTIVE].\n" +
                "Le registre des dernières 24h fait état d'une ESCALADE MILITAIRE DIRECTE ou d'une MENACE SUR L'OFFRE (notamment Hormuz).\n" +
                "CONSIGNE : Tu te trouves dans le cas d'exception absolue décrit à la CONTRAINTE 11. Active immédiatement la matrice géopolitique prioritaire (Régime de dominance géopolitique sur l'inflation) pour l'alignement des 11 actifs et le fait marquant.\n\n";
            
            return alerteFlash + promptDeBase;
        }
    
        // Sinon, on renvoie le prompt standard (la hiérarchie normale s'applique)
        return promptDeBase;
    }

  @Override
  public void onDestroy() {
    super.onDestroy();

    serviceInstance = null; // ✅ Libération immédiate du singleton

    // 1. Demande d'arrêt simultanée et non bloquante
    scheduler.shutdown();
    exec.shutdown();
    tradingPipelineExecutor.shutdown();

    // 2. Attente de la terminaison propre (500ms max pour éviter les ANR sous Android)
    try {
        if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            scheduler.shutdownNow();
        }
        if (!exec.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            exec.shutdownNow();
        }
        if (!tradingPipelineExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            tradingPipelineExecutor.shutdownNow();
        }
    } catch (InterruptedException e) {
        // En cas d'interruption, on force uniquement les exécuteurs encore actifs
        if (!scheduler.isTerminated()) scheduler.shutdownNow();
        if (!exec.isTerminated()) exec.shutdownNow();
        if (!tradingPipelineExecutor.isTerminated()) tradingPipelineExecutor.shutdownNow();
        Thread.currentThread().interrupt();
    }

    // 3. Arrêt simplifié de MarketDataFetcher (gestion des erreurs interne)
    MarketDataFetcher.shutdownExecutor();

    // 4. Fermeture sécurisée de la base de données
    if (eventDb != null) {
        eventDb.close(); 
    }

    Log.d(TAG, "[SERVICE] Service arrêté proprement");
}
        
}
