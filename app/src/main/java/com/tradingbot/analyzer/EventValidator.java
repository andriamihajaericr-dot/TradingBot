package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import android.util.Log;
import android.content.Context;
import java.util.Collections; // ✅ ajouter si absent

public class EventValidator {

    private static Map<String, EconomicCalendarAPI.CalendarEvent> upcomingEvents =
        new ConcurrentHashMap<>();
    // ✅ Getter pour accès depuis NotificationService (Daily Report)
    public static Map<String, EconomicCalendarAPI.CalendarEvent> getUpcomingEvents() {
        return Collections.unmodifiableMap(upcomingEvents);
    }
    // Dans EventValidator.java
    public static Map<String, Long> getRecentFingerprints() {
        return recentFingerprints;
    }
    private static final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>(256);
    private static final long DUPLICATE_WINDOW_MS = 30 * 60 * 1000L; // 30 minutes
    private static final String TAG = "EventValidator";
    // ✅ Hash du dernier rapport envoyé — évite les doublons
    private static String lastCalendarHash = "";
    private static final Map<String, Long> lastAlertsSent = new ConcurrentHashMap<>();
    // ✅ Ajouter en haut de la classe (après les autres champs statiques)
    private static Context appContext = null;

    
    public static void setAppContext(Context context) {
    if (context != null) appContext = context.getApplicationContext();
    }

    private static EventDatabase getDatabase(Context context) {
        return EventDatabase.getInstance(context);
    }

    // ─────────────────────────────────────────────────────────────
    //  RÉSULTAT DE VALIDATION
    // ─────────────────────────────────────────────────────────────
    public static class ValidationResult {
        public boolean isConfirmed    = false;
        public int     confidence     = 0;
        public String  forecast       = "N/A";
        public String  previous       = "N/A";
        public String  actual         = "N/A";
        public boolean assetsEnriched = false;
        public String  reason         = "";
        public String  geoContext     = "";  // Description de la zone géopolitique détectée
        
        // Nouveaux champs pour l'inertie macro
        public boolean isInertiaBlock = false;
        public String  lastEventSummary = "";
        public ValidationResult() {}

        public ValidationResult(boolean isConfirmed, int confidence, String reason) {
            this.isConfirmed = isConfirmed;
            this.confidence  = confidence;
            this.reason      = reason;
        }
    }

    /**
     * Méthode principale de validation et d'enrichissement de la matrice d'actifs
     */
    public static ValidationResult validate(Context context, String title, String content, long timestamp, List<String> detectedAssets) {
        ValidationResult result = new ValidationResult();
    
        if (title == null) title = "";
        if (content == null) content = "";
        if (detectedAssets == null) detectedAssets = new ArrayList<>();
    
        String combined = (title + " " + content).toLowerCase(Locale.ROOT);
        String upperCombined = (title + " " + content).toUpperCase(Locale.ROOT);
        
        // ── EXTRACTION PRIORITAIRE ET SYSTÉMATIQUE DES ACTIFS (Sécurité Rang Suprême) ──
        // ── EXTRACTION PRIORITAIRE ET SYSTÉMATIQUE DES ACTIFS (Optimisé FinancialJuice & Interbancaire) ──
try {
    List<String> rawExtracted = new ArrayList<>();
    String textToScan = upperCombined;

    // 1. EURUSD
    if (textToScan.contains("EURUSD") || textToScan.contains("EUR/") || textToScan.contains("EURO") || 
        textToScan.contains("EUROZONE") || textToScan.contains("ECB") || textToScan.contains("BCE") || 
        textToScan.contains("LAGARDE") || textToScan.contains(" EZ ")) {
        rawExtracted.add("EURUSD");
    }
    // 2. USDJPY
    if (textToScan.contains("USDJPY") || textToScan.contains("JPY") || textToScan.contains("YEN") || 
        textToScan.contains("BOJ") || textToScan.contains("UEDA") || textToScan.contains("JGB")) {
        rawExtracted.add("USDJPY");
    }
    // 3. GBPUSD
    if (textToScan.contains("GBPUSD") || textToScan.contains("GBP/") || textToScan.contains("POUND") || 
        textToScan.contains("CABLE") || textToScan.contains("BOE") || textToScan.contains("BAILEY") || textToScan.contains(" UK ")) {
        rawExtracted.add("GBPUSD");
    }
    // 4. AUDUSD
    if (textToScan.contains("AUDUSD") || textToScan.contains("AUD/") || textToScan.contains("AUSSIE") || 
        textToScan.contains("RBA") || textToScan.contains("BULLOCK")) {
        rawExtracted.add("AUDUSD");
    }
    // 5. USDCAD
    if (textToScan.contains("USDCAD") || textToScan.contains("CAD/") || textToScan.contains("LOONIE") || 
        textToScan.contains("BOC") || textToScan.contains("MACKLEM")) {
        rawExtracted.add("USDCAD");
    }
    // 6. GOLD
    if (textToScan.contains("GOLD") || textToScan.contains("XAU") || textToScan.contains(" OR ") || 
        textToScan.contains("BULLION") || textToScan.contains("PRECIOUS METALS")) {
        rawExtracted.add("GOLD");
    }
    // 7. USOIL
    if (textToScan.contains("USOIL") || textToScan.contains("CRUDE") || textToScan.contains("WTI") || 
        textToScan.contains("PETROLE") || textToScan.contains("BRENT") || textToScan.contains("OPEC") || 
        textToScan.contains("OPEP") || textToScan.contains(" EIA ") || textToScan.contains(" API ")) {
        rawExtracted.add("USOIL");
    }
    // 8. NASDAQ
    if (textToScan.contains("NASDAQ") || textToScan.contains("NAS100") || textToScan.contains("USTECH") || 
        textToScan.contains("TECH") || textToScan.contains("NVIDIA") || textToScan.contains(" SOX ")) {
        rawExtracted.add("NASDAQ");
    }
    // 9. SP500
    if (textToScan.contains("SP500") || textToScan.contains("S&P") || textToScan.contains("SPX") || 
        textToScan.contains("WALL STREET") || textToScan.contains("US STOCKS") || textToScan.contains("MARKET CAP")) {
        rawExtracted.add("SP500");
    }
    // 10. BITCOIN
    if (textToScan.contains("BITCOIN") || textToScan.contains("BTC") || textToScan.contains("CRYPTO")) {
        rawExtracted.add("BITCOIN");
    }
    // 11. US10Y
    if (textToScan.contains("US10Y") || textToScan.contains("TREASURY") || textToScan.contains("YIELD") || 
        textToScan.contains("10-YEAR") || textToScan.contains("BOND") || textToScan.contains("T-NOTE") || 
        textToScan.contains("TREASURIES") || textToScan.contains("AUCTION") || textToScan.contains("POWELL") || 
        textToScan.contains("FOMC") || textToScan.contains(" FED ")) {
        rawExtracted.add("US10Y");
    }

    // Transfert sécurisé sans doublon
    for (String asset : rawExtracted) {
        if (asset != null && !detectedAssets.contains(asset)) {
            detectedAssets.add(asset);
        }
    }
    
} catch (Exception e) {
    Log.e(TAG, "Erreur lors de l'extraction brute des actifs", e);
 }
         // =========================================================================
// ⚡ INTERCEPTION & DÉROGATION ABSOLUE : FORMAT CALENDRIER ÉCONOMIQUE CHIFFRÉ
// =========================================================================
// Capture les structures de publications brutes (comme FinancialJuice ou alertes chiffrées)
if (upperCombined.contains("ACTUAL:") && (upperCombined.contains("FORECAST:") || upperCombined.contains("PREVIOUS:"))) {
    
    // 1️⃣ ── RANG SUPRÊME & MACRO US : LES PILIERS ET INDICATEURS MAJEURS SUR LE DOLLAR ──
    if (upperCombined.contains("CPI") || upperCombined.contains("PCE") || upperCombined.contains("INFLATION") || 
        upperCombined.contains("HICP") || upperCombined.contains("CONSUMER PRICE") || upperCombined.contains("PPI") || 
        upperCombined.contains("PRODUCER PRICE") || upperCombined.contains("FOMC") || upperCombined.contains(" FED ") || 
        upperCombined.contains("POWELL") || upperCombined.contains("RATE DECISION") || upperCombined.contains("INTEREST RATE") || 
        upperCombined.contains("PAYROLL") || upperCombined.contains("NFP") || upperCombined.contains("UNEMPLOYMENT") || 
        upperCombined.contains("JOBLESS") || upperCombined.contains("CLAIMS") || upperCombined.contains("GDP") || 
        upperCombined.contains(" PIB ") || upperCombined.contains("GROSS DOMESTIC") || upperCombined.contains("ISM") || 
        upperCombined.contains("PMI") || upperCombined.contains("PURCHASING MANAGERS") || upperCombined.contains("ADP") || 
        upperCombined.contains("JOLTS") || upperCombined.contains("JOB OPENINGS") || upperCombined.contains("RETAIL SALES") || 
        upperCombined.contains("MICHIGAN") || upperCombined.contains("CONSUMER SENTIMENT") || upperCombined.contains("DURABLE GOODS") || 
        upperCombined.contains("DXY") || upperCombined.contains("DOLLAR INDEX") || upperCombined.contains("TREASURY AUCTION") || 
        upperCombined.contains("YIELD SPIKE") || upperCombined.contains("BOND SELLOFF")) {
        
        if (!detectedAssets.contains("GOLD")) detectedAssets.add("GOLD");
        if (!detectedAssets.contains("NASDAQ")) detectedAssets.add("NASDAQ");
        if (!detectedAssets.contains("USDJPY")) detectedAssets.add("USDJPY");
        if (!detectedAssets.contains("US10Y")) detectedAssets.add("US10Y");
        if (!detectedAssets.contains("SP500")) detectedAssets.add("SP500");
        if (!detectedAssets.contains("USOIL")) detectedAssets.add("USOIL"); // ✅ Corrigé : Intégration de l'USOIL au Rang Suprême
    }

    // 2️⃣ ── LES AUTRES CALENDRIERS ET ACTIFS DE LA MATRICE GLOBALE ──
    
    // Zone Euro 🇪🇺 -> Impact direct EURUSD (et spreads obligataires)
    if (upperCombined.contains("ECB") || upperCombined.contains("BCE") || upperCombined.contains("LAGARDE") || 
        upperCombined.contains("EUROZONE") || upperCombined.contains("GERMAN") || upperCombined.contains(" IFO ") || 
        upperCombined.contains(" ZEW ") || upperCombined.contains("SPREAD") || upperCombined.contains(" BTP ") || 
        upperCombined.contains(" OAT ")) {
        if (!detectedAssets.contains("EURUSD")) detectedAssets.add("EURUSD");
    }
    
    // Royaume-Uni 🇬🇧 -> Impact direct GBPUSD
    if (upperCombined.contains("BOE") || upperCombined.contains("BAILEY") || upperCombined.contains(" UK ") || 
        upperCombined.contains("BRITISH") || upperCombined.contains("MPC VOTE") || upperCombined.contains("UK BUDGET") || 
        upperCombined.contains("CLAIMANT COUNT") || upperCombined.contains("AVERAGE EARNINGS")) {
        if (!detectedAssets.contains("GBPUSD")) detectedAssets.add("GBPUSD");
    }
    
    // Canada 🇨🇦 -> Impact direct USDCAD & USOIL (Corrélation étroite Pétrole/Loonie)
    if (upperCombined.contains("BOC") || upperCombined.contains("MACKLEM") || upperCombined.contains("CANADA") || 
        upperCombined.contains("CANADIAN")) {
        if (!detectedAssets.contains("USDCAD")) detectedAssets.add("USDCAD");
        if (!detectedAssets.contains("USOIL")) detectedAssets.add("USOIL");
    }
    
    // Australie & Chine 🇦🇺 🇨🇳 -> Impact direct AUDUSD (Proxy matières premières et croissance asiatique)
    if (upperCombined.contains("RBA") || upperCombined.contains("BULLOCK") || upperCombined.contains("AUSTRALIA") || 
        upperCombined.contains("CHINA") || upperCombined.contains("PBOC") || upperCombined.contains("CNY") || 
        upperCombined.contains("YUAN") || upperCombined.contains("CAIXIN") || upperCombined.contains("POLITBURO") || 
        upperCombined.contains("COPPER") || upperCombined.contains("IRON ORE")) {
        if (!detectedAssets.contains("AUDUSD")) detectedAssets.add("AUDUSD");
    }
    
    // Japon 🇯🇵 -> Impact direct USDJPY (BOJ, MOF, Inflation & Crises du Carry Trade)
    if (upperCombined.contains("BOJ") || upperCombined.contains("UEDA") || upperCombined.contains("JAPAN") || 
        upperCombined.contains("TOKYO") || upperCombined.contains("YEN") || upperCombined.contains("USDJPY") || 
        upperCombined.contains("MOF") || upperCombined.contains("INTERVENTION") || upperCombined.contains("CARRY TRADE") || 
        upperCombined.contains("TANKAN")) {
        if (!detectedAssets.contains("USDJPY")) detectedAssets.add("USDJPY"); // ✅ Corrigé : Blindage sémantique pour l'inflation nippone et abréviation USDJPY
    }
    
    // 3️⃣ ── CALENDRIERS SPÉCIFIQUES DES MATIÈRES PREMIÈRES & CRYPTO ──
    
    // Stocks Énergétiques US & OPEC 🛢️ -> Impact direct USOIL
    if (upperCombined.contains(" EIA ") || upperCombined.contains(" API ") || upperCombined.contains("CRUDE STOCKS") || 
        upperCombined.contains("OIL INVENTORIES") || upperCombined.contains("OPEC") || upperCombined.contains("NATURAL GAS") || 
        upperCombined.contains(" SPR ") || upperCombined.contains("RIG COUNT") || upperCombined.contains("BAKER HUGHES")) {
        if (!detectedAssets.contains("USOIL")) detectedAssets.add("USOIL");
    }
    
    // Calendrier Régulateur Crypto & Flux ETF ⚡ -> Impact direct BITCOIN
    if (upperCombined.contains(" SEC ") || upperCombined.contains("ETF") || upperCombined.contains("BITCOIN") || 
        upperCombined.contains("CRYPTO") || upperCombined.contains("BINANCE") || upperCombined.contains("HALVING") || 
        upperCombined.contains("TETHER") || upperCombined.contains("USDT") || upperCombined.contains("FTX")) {
        if (!detectedAssets.contains("BITCOIN")) detectedAssets.add("BITCOIN");
    }

    // 4️⃣ ── ALERTES DE RISQUES SYSTÉMIQUES DIRECTS (Sécurité intermarché Or & Indices) ──
    if (upperCombined.contains("BANK RUN") || upperCombined.contains("SYSTEMIC RISK") || 
        upperCombined.contains("BANK COLLAPSE") || upperCombined.contains("BANKING CRISIS") || 
        upperCombined.contains("FDIC") || upperCombined.contains("BAILOUT") || upperCombined.contains("CONTAGION")) {
        if (!detectedAssets.contains("GOLD")) detectedAssets.add("GOLD");
        if (!detectedAssets.contains("NASDAQ")) detectedAssets.add("NASDAQ");
        if (!detectedAssets.contains("SP500")) detectedAssets.add("SP500");
    }

    // Finalisation souveraine des résultats de l'interception
    result.confidence = 100; // Statut Souverain Institutionnel
    result.isConfirmed = true;
    result.reason = "Notification Calendrier Économique Reçue (Forçage Chiffres Actifs Globaux)";
    result.assetsEnriched = !detectedAssets.isEmpty();

    logToMain("🟢 [FORÇAGE CALENDRIER GLOBAL] Validation immédiate des chiffres bruts. Actifs liés : " + detectedAssets);
    return result; // 🚀 Sortie immédiate ! On évite l'anti-doublon, l'inertie et le filtre de confiance.
}
// =========================================================================
        // ── ÉTAPE 1 : Anti-Doublons (très haut dans le flux) ─────────────
        if (isRecentDuplicate(title, content)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Doublon récent détecté (30min)";
            result.assetsEnriched = !detectedAssets.isEmpty();
            logToMain("🔄 Doublon identifié (Enrichissement préservé)");
            return result;
        }
    
        // ── INERTIE MACRO (éviter plusieurs analyses sur le même driver majeur) ─────
        // ── INERTIE MACRO (éviter plusieurs analyses sur le même driver majeur) ─────
        String detectedType = EconomicEventDetector.detectEvent(title, content).eventType;
        EventDatabase db = (context != null) ? EventDatabase.getInstance(context) : null;
        if (!detectedType.startsWith("GEO") && db != null) {
            try {
                long currentSeconds = timestamp / 1000;
                if (db.isDriverActiveRecently(detectedType, currentSeconds)) {
                    result.isConfirmed = false;
                    result.isInertiaBlock = true;
                    result.reason = "Driver déjà actif récemment (Inertie Macro)";
                    result.assetsEnriched = !detectedAssets.isEmpty();
                    // Récupérer le dernier événement de ce type pour le rappel
                    result.lastEventSummary = db.getLastEventByType(detectedType);
                    logToMain("⏳ Driver " + detectedType + " déjà actif — envoi d'un rappel");
                    return result;
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur inertie macro", e);
            }
        }
    
        // ── ÉTAPE 2 : Filtre anti-rumeur absolu ───────────────────────────
        if (containsRumorMarkers(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Rejeté — Marqueur de rumeur ou non-confirmé détecté";
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
            logToMain("❌ Rumeur/Non-confirmé rejeté – " + shortTitle + "…");
            return result;
        }
    
        // ── ÉTAPE 3 : Filtre éditorial ───────────────────────────────────
        if (containsEditorialContent(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Bruit macroéconomique (Opinion/Éditorial pur)";
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
            logToMain("❌ Rejeté – Contenu éditorial – " + shortTitle + "…");
            return result;
        }
    
    
        // ── ÉTAPE 5 : Géopolitique ───────────────────────────────────────
        GeoAssessment geo = assessGeopoliticalEvent(combined, upperCombined);
        if (geo.confidence >= 65) {
            result.isConfirmed = true;
            result.confidence  = geo.confidence;
            result.reason      = "Événement géopolitique confirmé";
            result.geoContext  = geo.contextLabel;
    
            for (String asset : geo.impactedAssets) {
                if (asset != null && !detectedAssets.contains(asset)) {
                    detectedAssets.add(asset);
                }
            }
            result.assetsEnriched = !detectedAssets.isEmpty();
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(40, title.length())) : "?";
            logToMain("🌍 Géo confirmé [" + geo.contextLabel + "] " + geo.confidence + "% – " + shortTitle + "…");
            return result;
        }
    
        // ── ÉTAPE 6 : Breaking News générique ───────────────────────────
        result.confidence = calculateBreakingNewsConfidence(title, content);
        result.reason      = "Breaking News (Flux Interbancaire)";
    
        if (result.confidence < 70) {   
            result.confidence  = 0;
            result.isConfirmed = false;
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(40, title.length())) : "?";
            logToMain("❌ Rejeté – " + shortTitle + "… (confiance " + result.confidence + "%)");
        } else {
            result.isConfirmed = true;
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
            logToMain("⚡ Breaking News retenu – " + shortTitle + "… (confiance " + result.confidence + "%)");
        }
    
        result.assetsEnriched = !detectedAssets.isEmpty();
        return result;
    }

    private static boolean containsRumorMarkers(String text) {
        if (text == null) return false;
        return text.contains("rumor")        ||
               text.contains("rumour")       ||
               text.contains("allegedly")    ||
               text.contains("unconfirmed")  ||
               text.contains("sources say")  ||
               text.contains("source says")  ||
               text.contains("could attack") ||
               text.contains("may attack")   ||
               text.contains("might strike") ||
               text.contains("speculated")   ||
               text.contains("speculation")  ||
               text.contains("supposed to")  ||
               text.contains("not confirmed")||
               text.contains("not verified") ||
               text.contains("fake news")    ||
               text.contains("unverified");
    }

    private static boolean containsEditorialContent(String text) {
        if (text == null) return false;
        return text.contains("opinion")    ||
               text.contains("editorial")  ||
               text.contains("op-ed")      ||
               text.contains("commentary") ||
               text.contains("think tank") ||
               text.contains("columnist")  ||
               text.contains("pundit")     ||
               text.contains("satire")     ||
               text.contains("parody");
    }

    private static class GeoAssessment {
        int          confidence    = 0;
        String       contextLabel  = "";
        List<String> impactedAssets = new ArrayList<>();
    }

    private static GeoAssessment assessGeopoliticalEvent(String text, String upperText) {
        GeoAssessment geo = new GeoAssessment();
        int score = 0;
        String lowerText = text.toLowerCase(Locale.ROOT);

        // ── A. Source crédible ───────────────────────────────────────
        boolean hasCredibleSource =
            lowerText.contains("reuters")         ||
            lowerText.contains("bloomberg")       ||
            lowerText.contains("associated press")||
            lowerText.contains(" ap ")            ||
            lowerText.contains("idf")             ||
            lowerText.contains("pentagon")        ||
            lowerText.contains("nato")            ||
            lowerText.contains("white house")     ||
            lowerText.contains("ministry of")     ||
            lowerText.contains("times of israel") ||
            lowerText.contains("al jazeera")      ||
            lowerText.contains("bbc")             ||
            lowerText.contains("cnn")             ||
            lowerText.contains("axios")           ||
            lowerText.contains("haaretz")         ||
            lowerText.contains("fxhedgers")       ||
            lowerText.contains("deltaone");

        if (hasCredibleSource) score += 25;

        // ── B. Action militaire/géo concrète ─────────────────────────
        boolean hasFactualAction =
            lowerText.contains("fired")        ||
            lowerText.contains("launched")     ||
            lowerText.contains("struck")       ||
            lowerText.contains("hit")          ||
            lowerText.contains("attacked")     ||
            lowerText.contains("bombed")       ||
            lowerText.contains("intercepted")  ||
            lowerText.contains("shot down")    ||
            lowerText.contains("killed")       ||
            lowerText.contains("destroyed")    ||
            lowerText.contains("airstrike")    ||
            lowerText.contains("air strike")   ||
            lowerText.contains("missile")      ||
            lowerText.contains("drone")        ||
            lowerText.contains("explosion")    ||
            lowerText.contains("raid")         ||
            lowerText.contains("invasion");

        if (hasFactualAction) score += 32;

        // ── C. Zone géographique à impact marché direct (Enrichi) ──────────────
        boolean geoZoneFound = false;

        // Zone 1 : Moyen-Orient + Hormuz (priorité maximale)
        boolean isMoyenOrient =
            lowerText.contains("israel")       ||
            lowerText.contains("iran")         ||
            lowerText.contains("gaza")         ||
            lowerText.contains("lebanon")      ||
            lowerText.contains("hezbollah")    ||
            lowerText.contains("hamas")        ||
            lowerText.contains("houthi")       ||
            lowerText.contains("yemen")        ||
            lowerText.contains("red sea")      ||
            upperText.contains("HORMUZ")       ||
            upperText.contains("ORMUZ")        ||
            lowerText.contains("persian gulf") ||
            lowerText.contains("saudi")        ||
            lowerText.contains("tel aviv")     ||
            lowerText.contains("jerusalem")    ||
            lowerText.contains("beirut")       ||
            lowerText.contains("tehran")       ||
            lowerText.contains("middle east")  ||
            lowerText.contains("moyen-orient");

        boolean isTrumpIran = lowerText.contains("trump") && lowerText.contains("iran");

        if (isMoyenOrient) {
            if (upperText.contains("HORMUZ") || upperText.contains("ORMUZ")) {
                geo.contextLabel = "Détroit d'Hormuz - Menace sur l'offre pétrole";
                score += 40;
                geo.impactedAssets.addAll(Arrays.asList("USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN", "EURUSD", "US10Y"));
            } else if (hasFactualAction) {
                geo.contextLabel = "Moyen-Orient - Action Militaire";
                score += 35;
                geo.impactedAssets.addAll(Arrays.asList("USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN", "EURUSD", "AUDUSD", "US10Y"));
            } else if (isTrumpIran) {
                geo.contextLabel = "Moyen-Orient - Déclaration Trump/Iran";
                score += 18;   
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY", "NASDAQ", "SP500"));
            } else {
                geo.contextLabel = "Moyen-Orient / Pétrole";
                score += 22;
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY", "US10Y"));
            }
            geoZoneFound = true;
        }

        // Zone 2 : Europe de l'Est
        boolean isEuropeEst =
            lowerText.contains("ukraine")      ||
            lowerText.contains("russia")       ||
            lowerText.contains("moscow")       ||
            lowerText.contains("kyiv")         ||
            lowerText.contains("kiev")         ||
            lowerText.contains("kremlin")      ||
            lowerText.contains("nato")         ||
            lowerText.contains("putin")        ||
            lowerText.contains("zelensky")     ||
            lowerText.contains("donbas")       ||
            lowerText.contains("crimea")       ||
            lowerText.contains("black sea");

        if (isEuropeEst && !geoZoneFound) {
            geo.contextLabel = "Europe de l'Est / OTAN";
            geo.impactedAssets.addAll(Arrays.asList("EURUSD", "GBPUSD", "USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN"));
            score += 20;
            geoZoneFound = true;
        }

        // Zone 3 : Asie-Pacifique
        boolean isAsiePacifique =
            lowerText.contains("china")        ||
            lowerText.contains("taiwan")       ||
            lowerText.contains("beijing")      ||
            lowerText.contains("south china sea") ||
            lowerText.contains("north korea")  ||
            lowerText.contains("tsmc")         ||
            lowerText.contains("semiconductor");

        if (isAsiePacifique && !geoZoneFound) {
            geo.contextLabel = "Asie-Pacifique / Chine";
            geo.impactedAssets.addAll(Arrays.asList("AUDUSD", "USDJPY", "NASDAQ", "SP500", "GOLD", "BITCOIN", "USOIL"));
            score += 20;
            geoZoneFound = true;
        }

        if (!geoZoneFound && hasFactualAction) {
            geo.contextLabel = "Événement Géo Non Régionalisé";
            geo.impactedAssets.addAll(Arrays.asList("GOLD", "USDJPY", "USOIL"));
            score += 12;
        }

        // ── D. Entité précise ────────────────────────────────────────
        if (lowerText.matches(".*\\d+\\s*(drone|missile|rocket|soldier|ship|bomb).*") ||
            lowerText.matches(".*\\d+\\s*(km|miles|kilometers).*")                    ||
            lowerText.contains("confirmed dead") || lowerText.contains("confirmed killed")) {
            score += 15;
        }

        // ── E. Confirmation officielle ───────────────────────────────
        if (lowerText.contains("confirmed")       ||
            lowerText.contains("official said")   ||
            lowerText.contains("officials said")  ||
            lowerText.contains("announced")       ||
            lowerText.contains("idf confirmed")   ||
            lowerText.contains("pentagon confirmed")) {
            score += 10;
        }

        boolean hasOfficialConfirmation = lowerText.contains("confirmed") || lowerText.contains("announced");
        if (isTrumpIran && !hasFactualAction && !hasOfficialConfirmation) {
            score = Math.min(score, 55);
        }

        if (!geoZoneFound && !hasFactualAction) score = Math.max(0, score - 15);

        geo.confidence = Math.min(100, score);
        return geo;
    }
    private static EconomicCalendarAPI.CalendarEvent findMatchingEvent(
        String title, String content, long timestamp) {

        String combined = (title + " " + content).toLowerCase(Locale.ROOT);
        String normalizedCombined = combined
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
    
        long window = 30 * 60 * 1000; // ±30 minutes
        EconomicCalendarAPI.CalendarEvent bestMatch = null;
        long bestDelta = Long.MAX_VALUE;
    
        for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents.values()) {
            if (event == null || event.timestamp == null || event.indicator == null) continue;
    
            long eventTime = parseTimestamp(event.timestamp);
            long normalizedTimestamp = (timestamp > 9999999999L) ? timestamp : timestamp * 1000;
            long delta = Math.abs(eventTime - normalizedTimestamp);
    
            if (delta < window) {
                String indicator = event.indicator.toLowerCase(Locale.ROOT);
                String normalizedIndicator = indicator.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
                
                String normalizedText = normalizedCombined;
                
                // Matching plus permissif pour Jobless Claims
                boolean isJoblessMatch = (indicator.contains("jobless") || indicator.contains("initial claims")) &&
                                         (normalizedText.contains("jobless") || normalizedText.contains("initial claims") || normalizedText.contains("claims"));
                
                if (normalizedText.contains(normalizedIndicator) || 
                    matchesIndicatorKeywords(normalizedText, indicator, event.country) ||
                    isJoblessMatch) {
                    
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestMatch = event;
                    }
                }
            }
        }
        return bestMatch;
    }

     private static boolean matchesIndicatorKeywords(String text, String indicator, String country) {
        if (text == null || indicator == null) return false;
        String ind = indicator.toLowerCase(Locale.ROOT);
    
        // ── NFP / Non-Farm Payrolls ──
        // ── NFP / Non-Farm Payrolls ──
        if (ind.contains("non-farm payrolls") || ind.contains("nfp") ||
              ind.contains("nonfarm") || ind.contains("payroll") ||
              ind.contains("non-farm employment change") ||    // ✅ minuscules
              ind.contains("nonfarm employment")) {
            return text.contains("nfp") || text.contains("non-farm") ||
                   text.contains("nonfarm") || text.contains("payroll") ||
                   text.contains("emploi");
        }
    
        // ── CPI / Inflation ──
        if (ind.contains("cpi") || ind.contains("inflation") || ind.contains("hicp") ||
            ind.contains("consumer price")) {
            return text.contains("cpi") || text.contains("inflation") ||
                   text.contains("hicp") || text.contains("consumer price") ||
                   text.contains("prix à la consommation");
        }
    
        // ── PCE / Core PCE ──
        if (ind.contains("pce") || ind.contains("personal consumption expenditure")) {
            return text.contains("pce") || text.contains("personal consumption") ||
                   text.contains("core pce") || text.contains("deflator") ||
                   text.contains("expenditure");
        }
    
        // ── PPI / Producer Price ──
        if (ind.contains("ppi") || ind.contains("producer price")) {
            return text.contains("ppi") || text.contains("producer price") ||
                   text.contains("wholesale price") ||
                   text.contains("prix à la production");
        }
    
        // ── GDP / Croissance ──
        if (ind.contains("gdp") || ind.contains("gross domestic") || ind.contains("pib")) {
            return text.contains("gdp") || text.contains("pib") ||
                   text.contains("gross domestic") || text.contains("croissance") ||
                   text.contains("economic growth") || text.contains("quarterly growth");
        }
    
        // ── Fed / FOMC / Taux US ──
        if (ind.contains("fomc") || ind.contains("federal reserve") ||
            ind.contains("fed rate") || ind.contains("interest rate decision")) {
            return text.contains("fed") || text.contains("fomc") ||
                   text.contains("federal reserve") || text.contains("powell") ||
                   text.contains("rate decision") || text.contains("interest rate") ||
                   text.contains("taux directeur") || text.contains("taux fed");
        }
    
        // ── Beige Book / Minutes Fed ──
        if (ind.contains("beige book") || ind.contains("fomc minutes") ||
            ind.contains("fed minutes") || ind.contains("minutes")) {
            return text.contains("beige book") || text.contains("minutes") ||
                   text.contains("fomc minutes") || text.contains("fed minutes") ||
                   text.contains("compte rendu fed");
        }
    
        // ── ISM / PMI Manufacturing & Services ──
        if (ind.contains("ism") || ind.contains("pmi") ||
            ind.contains("purchasing managers")) {
            return text.contains("ism") || text.contains("pmi") ||
                   text.contains("purchasing managers") || text.contains("manufacturing") ||
                   text.contains("services pmi") || text.contains("composite pmi") ||
                   text.contains("industrie") || text.contains("secteur services");
        }
    
        // ── Chicago PMI / Empire State / Philly Fed / NY Fed ──
        if (ind.contains("chicago pmi") || ind.contains("empire state") ||
            ind.contains("philly fed") || ind.contains("philadelphia") ||
            ind.contains("ny fed") || ind.contains("new york fed")) {
            return text.contains("chicago pmi") || text.contains("chicago") ||
                   text.contains("empire state") || text.contains("philly fed") ||
                   text.contains("philadelphia") || text.contains("ny fed") ||
                   text.contains("manufacturing index") || text.contains("regional fed");
        }
    
        // ── ADP Employment ──
        if (ind.contains("adp")) {
            return text.contains("adp") || text.contains("private payroll") ||
                   text.contains("private employment") ||
                   text.contains("national employment") ||
                   text.contains("adp employment") ||
                   text.contains("adp report");
        }
    
        // ── JOLTS / Job Openings ──
        if (ind.contains("jolts") || ind.contains("job openings") ||
            ind.contains("labor turnover")) {
            return text.contains("jolts") || text.contains("job openings") ||
                   text.contains("labor turnover") || text.contains("job turnover") ||
                   text.contains("offres d emploi") || text.contains("job vacancies");
        }
    
        // ── Jobless Claims / Initial / Continuing ──
        if (ind.contains("jobless") || ind.contains("initial claims") ||
            ind.contains("continuing claims") || ind.contains("unemployment claims") ||
            ind.contains("jobless claims")) {
            
            return text.contains("jobless") || 
                   text.contains("initial claims") ||
                   text.contains("continuing claims") ||
                   text.contains("weekly claims") ||
                   text.contains("unemployment claims") ||
                   text.contains("chômage") ||
                   text.contains("demandeurs d'emploi") ||
                   text.contains("demandeurs emploi") ||
                   text.contains("claims");
        }
    
        // ── Unemployment Rate ──
        if (ind.contains("unemployment rate") || ind.contains("taux de chômage")) {
            return text.contains("unemployment rate") || text.contains("jobless rate") ||
                   text.contains("taux de chômage") || text.contains("unemployment");
        }
    
        // ── Retail Sales ──
        if (ind.contains("retail sales") || ind.contains("retail")) {
            return text.contains("retail sales") || text.contains("retail") ||
                   text.contains("consumer spending") || text.contains("consumer sales") ||
                   text.contains("ventes au détail") || text.contains("dépenses consommateurs");
        }
    
        // ── Personal Income / Personal Spending ──
        if (ind.contains("personal income") || ind.contains("personal spending") ||
            ind.contains("personal consumption")) {
            return text.contains("personal income") || text.contains("personal spending") ||
                   text.contains("personal consumption") || text.contains("disposable income") ||
                   text.contains("revenu personnel") || text.contains("dépenses personnelles");
        }
    
        // ── Michigan Sentiment (Preliminary / Final) ──
        if (ind.contains("michigan") || ind.contains("consumer sentiment") ||
            ind.contains("consumer confidence") || ind.contains("sentiment prel") ||
            ind.contains("sentiment final") || ind.contains("preliminary") ||
            ind.contains("unc michigan")) {
            return text.contains("michigan") || text.contains("consumer sentiment") ||
                   text.contains("consumer confidence") || text.contains("sentiment prel") ||
                   text.contains("preliminary sentiment") || text.contains("final sentiment") ||
                   text.contains("confiance consommateurs") || text.contains("confiance");
        }
    
        // ── Durable Goods ──
        if (ind.contains("durable goods") || ind.contains("core durable")) {
            return text.contains("durable goods") || text.contains("core durable") ||
                   text.contains("durable orders") || text.contains("capital goods") ||
                   text.contains("biens durables") || text.contains("commandes industrielles");
        }
    
        // ── Industrial Production / Capacity Utilization ──
        if (ind.contains("industrial production") || ind.contains("capacity utilization") ||
            ind.contains("manufacturing output")) {
            return text.contains("industrial production") || text.contains("capacity utilization") ||
                   text.contains("manufacturing output") || text.contains("factory output") ||
                   text.contains("production industrielle") || text.contains("utilisation capacités");
        }
    
        // ── Housing Starts / Building Permits ──
        if (ind.contains("housing starts") || ind.contains("building permits")) {
            return text.contains("housing starts") || text.contains("building permits") ||
                   text.contains("new construction") || text.contains("residential") ||
                   text.contains("mises en chantier") || text.contains("permis de construire");
        }
    
        // ── New Home Sales / Existing Home Sales / Pending Home Sales ──
        if (ind.contains("home sales") || ind.contains("existing home") ||
            ind.contains("new home") || ind.contains("pending home") ||
            ind.contains("house sales")) {
            return text.contains("home sales") || text.contains("existing home") ||
                   text.contains("new home sales") || text.contains("pending home") ||
                   text.contains("house sales") || text.contains("housing market") ||
                   text.contains("ventes immobilières") || text.contains("immobilier");
        }
    
        // ── Trade Balance / Current Account ──
        if (ind.contains("trade balance") || ind.contains("current account") ||
            ind.contains("trade deficit") || ind.contains("trade surplus")) {
            return text.contains("trade balance") || text.contains("trade deficit") ||
                   text.contains("trade surplus") || text.contains("current account") ||
                   text.contains("balance commerciale") || text.contains("compte courant");
        }
    
        // ── EIA Crude Oil Inventories / Distillate / Gasoline ──
        if (ind.contains("crude oil") || ind.contains("eia") ||
            ind.contains("oil inventories") || ind.contains("distillate") ||
            ind.contains("gasoline") || ind.contains("petroleum")) {
            return text.contains("crude oil") || text.contains("eia") ||
                   text.contains("oil inventories") || text.contains("crude inventories") ||
                   text.contains("distillate") || text.contains("gasoline") ||
                   text.contains("petroleum") || text.contains("stockpiles") ||
                   text.contains("barrel") || text.contains("pétrole") ||
                   text.contains("stocks pétrole");
        }
    
        // ── OPEC ──
        if (ind.contains("opec") || ind.contains("opec+")) {
            return text.contains("opec") || text.contains("opec+") ||
                   text.contains("oil production") || text.contains("production cuts") ||
                   text.contains("production quota") || text.contains("barrel") ||
                   text.contains("réunion opec") || text.contains("quota pétrole");
        }
    
        // ── ECB / BCE ──
        if (ind.contains("ecb") || ind.contains("lagarde") ||
            ind.contains("european central bank")) {
            return text.contains("ecb") || text.contains("lagarde") ||
                   text.contains("bce") || text.contains("european central bank") ||
                   text.contains("eurozone rate") || text.contains("taux bce") ||
                   text.contains("taux zone euro");
        }
    
        // ── BOE / Bank of England ──
        if (ind.contains("boe") || ind.contains("bailey") ||
            ind.contains("bank of england") || ind.contains("boe gov")) {
            return text.contains("boe") || text.contains("bailey") ||
                   text.contains("boe gov") ||
                   text.contains("bank of england") || text.contains("uk rate") ||
                   text.contains("taux boe") || text.contains("monetary policy committee") ||
                   text.contains("mpc");
        }
    
        // ── BOJ / Bank of Japan ──
        if (ind.contains("boj") || ind.contains("ueda") ||
            ind.contains("bank of japan")) {
            return text.contains("boj") || text.contains("ueda") ||
                   text.contains("bank of japan") || text.contains("japan rate") ||
                   text.contains("taux boj") || text.contains("yield curve control") ||
                   text.contains("ycc");
        }
    
        // ── BOC / Bank of Canada ──
        if (ind.contains("boc") || ind.contains("macklem") ||
            ind.contains("bank of canada")) {
            return text.contains("boc") || text.contains("macklem") ||
                   text.contains("bank of canada") || text.contains("canada rate") ||
                   text.contains("taux boc") || text.contains("canadian rate");
        }
    
        // ── RBA / Reserve Bank of Australia ──
        if (ind.contains("rba") || ind.contains("bullock") ||
            ind.contains("reserve bank of australia")) {
            return text.contains("rba") || text.contains("bullock") ||
                   text.contains("reserve bank of australia") || text.contains("australia rate") ||
                   text.contains("taux rba") || text.contains("australian rate");
        }
    
        // ── IFO / ZEW (Allemagne) ──
        if (ind.contains("ifo") || ind.contains("zew") ||
            ind.contains("german business") || ind.contains("german sentiment")) {
            return text.contains("ifo") || text.contains("zew") ||
                   text.contains("german business") || text.contains("german sentiment") ||
                   text.contains("german confidence") || text.contains("ifo business climate") ||
                   text.contains("indicateur allemand");
        }
    
        // ── Tankan (Japon) ──
        if (ind.contains("tankan") || ind.contains("japan business")) {
            return text.contains("tankan") || text.contains("japan business") ||
                   text.contains("boj survey") || text.contains("japanese business") ||
                   text.contains("enquête tankan");
        }
    
        // ── Average Earnings / Wages UK ──
        if (ind.contains("average earnings") || ind.contains("wage growth") ||
            ind.contains("wages")) {
            return text.contains("average earnings") || text.contains("wage growth") ||
                   text.contains("wages") || text.contains("earnings") ||
                   text.contains("salaires") || text.contains("croissance salaires");
        }
    
        // ── Claimant Count UK ──
        if (ind.contains("claimant count")) {
            return text.contains("claimant count") || text.contains("claimant") ||
                   text.contains("uk jobless") || text.contains("uk unemployment") ||
                   text.contains("demandeurs emploi uk");
        }
    
        // ── Caixin PMI (Chine) ──
        if (ind.contains("caixin") || ind.contains("china pmi") ||
            ind.contains("chinese pmi")) {
            return text.contains("caixin") || text.contains("china pmi") ||
                   text.contains("chinese pmi") || text.contains("china manufacturing") ||
                   text.contains("pmi chine") || text.contains("chine industrie");
        }
    
        // ── Balance of Trade / Current Account (autres pays) ──
        if (ind.contains("trade") || ind.contains("balance of payments")) {
            return text.contains("trade") || text.contains("balance of payments") ||
                   text.contains("exports") || text.contains("imports") ||
                   text.contains("exportations") || text.contains("importations");
        }
        // ── Tariffs / Guerre Commerciale ──
        if (ind.contains("tariff") || ind.contains("trade war") ||
            ind.contains("trade deal") || ind.contains("sanctions") ||
            ind.contains("section 301") || ind.contains("section 232")) {
            return text.contains("tariff")        || text.contains("trade war")    ||
                   text.contains("trade deal")    || text.contains("sanctions")    ||
                   text.contains("embargo")       || text.contains("customs")      ||
                   text.contains("import tax")    || text.contains("customs duty") ||
                   text.contains("section 301")   || text.contains("section 232")  ||
                   text.contains("trade agreement");
        }

        // ── DXY / Dollar Index ──
        if (ind.contains("dxy") || ind.contains("dollar index") ||
            ind.contains("dollar strength") || ind.contains("dollar weakness")) {
            return text.contains("dxy")              || text.contains("dollar index")    ||
                   text.contains("dollar strength")  || text.contains("dollar weakness") ||
                   text.contains("us dollar")        || text.contains("usd index");
        }

        // ── Nominations Banques Centrales ──
        if (ind.contains("nominated") || ind.contains("appointed") ||
            ind.contains("nomination") || ind.contains("appointment") ||
            ind.contains("replace powell") || ind.contains("replace lagarde") ||
            ind.contains("fed chair") || ind.contains("ecb president") ||
            ind.contains("boj governor")) {
            return text.contains("nominated")      || text.contains("appointed")      ||
                   text.contains("nomination")     || text.contains("appointment")    ||
                   text.contains("replace powell") || text.contains("replace lagarde")||
                   text.contains("fed chair")      || text.contains("ecb president")  ||
                   text.contains("boj governor")   || text.contains("central bank chief");
        }

        // ── Données Chine / PBOC ──
        if (ind.contains("china") || ind.contains("pboc") ||
            ind.contains("yuan") || ind.contains("cny") ||
            ind.contains("renminbi") || ind.contains("caixin") ||
            ind.contains("chinese") || ind.contains("politburo")) {
            return text.contains("china")          || text.contains("pboc")          ||
                   text.contains("yuan")           || text.contains("cny")           ||
                   text.contains("renminbi")       || text.contains("caixin")        ||
                   text.contains("chinese")        || text.contains("politburo")     ||
                   text.contains("xi jinping")     || text.contains("npc")           ||
                   text.contains("china stimulus") || text.contains("evergrande");
        }

        // ── Révisions de données ──
        if (ind.contains("revised") || ind.contains("revision") ||
            ind.contains("upward revision") || ind.contains("downward revision") ||
            ind.contains("prior revised") || ind.contains("previous revised")) {
            return text.contains("revised")           || text.contains("revision")          ||
                   text.contains("revised to")        || text.contains("revised up")        ||
                   text.contains("revised down")      || text.contains("upward revision")   ||
                   text.contains("downward revision") || text.contains("prior revised")     ||
                   text.contains("previous revised");
        }

        // ── Treasury Auction / Debt Ceiling ──
        if (ind.contains("treasury auction") || ind.contains("debt ceiling") ||
            ind.contains("bid to cover") || ind.contains("budget deficit") ||
            ind.contains("yield spike") || ind.contains("bond selloff")) {
            return text.contains("treasury") || text.contains("auction") ||
                   text.contains("debt ceiling") || text.contains("bid to cover") ||
                   text.contains("budget deficit") || text.contains("yield") ||
                   text.contains("bond");
        }

        // ── Carry Trade / MOF Intervention Japon ──
        if (ind.contains("carry trade") || ind.contains("fx intervention") ||
            ind.contains("mof japan") || ind.contains("verbal intervention") ||
            ind.contains("watching closely") || ind.contains("sharp yen")) {
            return text.contains("carry trade") || text.contains("intervention") ||
                   text.contains("mof") || text.contains("yen") ||
                   text.contains("watching closely") || text.contains("excessive moves");
        }

        // ── Big Tech Earnings ──
        if (ind.contains("earnings") || ind.contains("profit warning") ||
            ind.contains("guidance") || ind.contains("eps beat") ||
            ind.contains("eps miss") || ind.contains("revenue")) {
            return text.contains("earnings") || text.contains("profit warning") ||
                   text.contains("guidance") || text.contains("eps") ||
                   text.contains("revenue") || text.contains("quarterly results") ||
                   text.contains("nvda") || text.contains("nvidia") ||
                   text.contains("aapl") || text.contains("msft") ||
                   text.contains("amzn") || text.contains("meta") ||
                   text.contains("alphabet") || text.contains("tesla");
        }

        // ── Bitcoin ETF / Halving / Regulatory ──
        if (ind.contains("bitcoin etf") || ind.contains("etf flows") ||
            ind.contains("halving") || ind.contains("sec crypto") ||
            ind.contains("crypto ban") || ind.contains("exchange hack") ||
            ind.contains("stablecoin") || ind.contains("ftx")) {
            return text.contains("bitcoin etf") || text.contains("etf") ||
                   text.contains("halving") || text.contains("sec") ||
                   text.contains("crypto ban") || text.contains("hack") ||
                   text.contains("stablecoin") || text.contains("ftx") ||
                   text.contains("ibit") || text.contains("fbtc") ||
                   text.contains("tether") || text.contains("usdt");
        }

        // ── Systemic Risk / Bank Run ──
        if (ind.contains("bank run") || ind.contains("systemic risk") ||
            ind.contains("bank collapse") || ind.contains("banking crisis") ||
            ind.contains("fdic") || ind.contains("bailout") ||
            ind.contains("contagion")) {
            return text.contains("bank run") || text.contains("systemic") ||
                   text.contains("bank collapse") || text.contains("banking crisis") ||
                   text.contains("fdic") || text.contains("bailout") ||
                   text.contains("contagion") || text.contains("svb") ||
                   text.contains("silicon valley bank") || text.contains("credit suisse");
        }

        // ── Iron Ore / Copper ──
        if (ind.contains("iron ore") || ind.contains("copper") ||
            ind.contains("commodity metals") || ind.contains("china steel")) {
            return text.contains("iron ore") || text.contains("copper") ||
                   text.contains("commodity") || text.contains("china steel") ||
                   text.contains("metals") || text.contains("infrastructure");
        }

        // ── Sovereign Debt / Spreads ──
        if (ind.contains("btp spread") || ind.contains("oat spread") ||
            ind.contains("sovereign spread") || ind.contains("cds spread") ||
            ind.contains("debt crisis") || ind.contains("sovereign debt")) {
            return text.contains("btp") || text.contains("oat") ||
                   text.contains("spread") || text.contains("sovereign") ||
                   text.contains("debt crisis") || text.contains("cds") ||
                   text.contains("italian bonds") || text.contains("french bonds");
        }

        // ── SPR / Baker Hughes / API ──
        if (ind.contains("spr") || ind.contains("strategic petroleum") ||
            ind.contains("baker hughes") || ind.contains("rig count") ||
            ind.contains("api crude") || ind.contains("api weekly")) {
            return text.contains("spr") || text.contains("strategic petroleum") ||
                   text.contains("baker hughes") || text.contains("rig count") ||
                   text.contains("api crude") || text.contains("api weekly") ||
                   text.contains("crude stock") || text.contains("petroleum reserve");
        }

        // ── MPC Vote / UK Budget ──
        if (ind.contains("mpc vote") || ind.contains("uk budget") ||
            ind.contains("autumn statement") || ind.contains("spring statement") ||
            ind.contains("uk cpi") || ind.contains("uk gdp")) {
            return text.contains("mpc vote") || text.contains("uk budget") ||
                   text.contains("autumn statement") || text.contains("spring statement") ||
                   text.contains("uk cpi") || text.contains("uk gdp") ||
                   text.contains("uk inflation") || text.contains("chancellor");
        }

        // ── Real Yields / Gold specific ──
        if (ind.contains("real yields") || ind.contains("real rates") ||
            ind.contains("pboc gold") || ind.contains("gold reserves") ||
            ind.contains("central bank gold") || ind.contains("gold demand")) {
            return text.contains("real yields") || text.contains("real rates") ||
                   text.contains("pboc gold") || text.contains("gold reserves") ||
                   text.contains("central bank gold") || text.contains("gold demand") ||
                   text.contains("tips") || text.contains("inflation linked");
        }

        return false;
    }  

         
    private static boolean isJoblessClaimsEvent(String indicatorLower, String textLower) {
        boolean isClaimsInCalendar = indicatorLower.contains("jobless") || 
                                     indicatorLower.contains("initial claims") ||
                                     indicatorLower.contains("unemployment claims");
    
        boolean isClaimsInNotification = textLower.contains("jobless") || 
                                         textLower.contains("initial claims") ||
                                         textLower.contains("claims") ||
                                         textLower.contains("chômage");
    
        return isClaimsInCalendar && isClaimsInNotification;
    }
    // ─────────────────────────────────────────────────────────────
    //  BREAKING NEWS GÉNÉRIQUE
    // ─────────────────────────────────────────────────────────────
    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 40;
        String lower = ((title != null ? title : "") + " " + (content != null ? content : "")).toLowerCase(Locale.ROOT);

        if (lower.contains("breaking"))                  score += 25;
        if (lower.contains("urgent") || lower.contains("alert"))    score += 20;
        if (lower.contains("fxhedgers") || lower.contains("deltaone")) score += 25;
        if (lower.contains("federal reserve") || lower.contains("fomc")) score += 20;
        if (content != null && content.matches(".*\\d+\\.\\d+%.*")) score += 15;

        return Math.min(100, score);
    }
        /**
     * Enrichit le contenu d'une notification avec les données du calendrier économique
     * si un événement correspondant est trouvé.
     * @param title Titre de la notification
     * @param content Contenu original
     * @param timestamp Timestamp de la notification (millisecondes)
     * @return Contenu enrichi (ou l'original si aucun match ou pas de données)
     */
     public static String enrichWithCalendar(String title, String content, long timestamp) {
        if (title == null || content == null) return content;
        
        EconomicCalendarAPI.CalendarEvent match = findMatchingEvent(title, content, timestamp); // ← corrigé
        if (match == null) return content;
        
        StringBuilder enriched = new StringBuilder(content);
        boolean hasActual   = match.actual   != null && !match.actual.equals("N/A")   && !match.actual.isEmpty();
        boolean hasForecast = match.forecast != null && !match.forecast.equals("N/A") && !match.forecast.isEmpty();
        
        if (hasActual && hasForecast) {
            enriched.append(" ACTUAL: ").append(match.actual)
                    .append(" FORECAST: ").append(match.forecast);
            Log.d(TAG, "Enrichi " + match.indicator + " | A=" + match.actual + " F=" + match.forecast);
        } else if (hasForecast) {
            enriched.append(" FORECAST: ").append(match.forecast);
            Log.d(TAG, "Consensus seulement pour " + match.indicator + " | F=" + match.forecast);
        } else if (hasActual) {
            enriched.append(" ACTUAL: ").append(match.actual);
            Log.d(TAG, "Actual seulement pour " + match.indicator + " | A=" + match.actual);
        }
        return enriched.toString();
    }
    // ─────────────────────────────────────────────────────────────
    //  UTILITAIRES SÉCURISÉS (inchangés)
    // ─────────────────────────────────────────────────────────────
    private static String createEventKey(String indicator, String timestamp) {
        if (indicator == null || timestamp == null) {
            return UUID.randomUUID().toString();
        }
        return indicator.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_") + "_" + timestamp;
    }

    private static long parseTimestamp(String timestamp) {
    if (timestamp == null) return System.currentTimeMillis();
    try {
        // ✅ Priorité 1 — Unix timestamp (cas le plus fréquent depuis FMP/ForexFactory)
        long ts = Long.parseLong(timestamp.trim());
        return (ts > 9999999999L) ? ts : ts * 1000L;
    } catch (Exception e) {
        try {
            // ✅ Priorité 2 — ISO avec timezone explicite (ex: 2026-06-05T08:30:00-04:00)
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            return sdf.parse(timestamp).getTime();
        } catch (Exception e2) {
            try {
                // ✅ Priorité 3 — ISO sans timezone → New York (DST automatique)
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
                return sdf.parse(timestamp).getTime();
            } catch (Exception e3) {
                return System.currentTimeMillis();
            }
        }
    }
    }
    
    private static void logToMain(String message) {
    // Toujours dans Logcat
    Log.d(TAG, "" + message);
    
    // Également dans l'interface si l'activité existe
    if (MainActivity.instance != null) {
        try {
            MainActivity.instance.addLog("" + message);
        } catch (Exception e) {
            Log.w(TAG, "Impossible d'ajouter le log à l'UI", e);
        }
    }
    }

    private static String generateFingerprint(String title, String content) {
        String t = (title != null) ? title : "";
        String c = (content != null) ? content : "";
        String combined = (t + " " + c).toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9\\s]", " ")
                            .replaceAll("\\s+", " ")
                            .trim();

        return combined.substring(0, Math.min(90, combined.length()));
    }

    private static boolean isRecentDuplicate(String title, String content) {
        String fingerprint = generateFingerprint(title, content);
        long now = System.currentTimeMillis();

        Long lastSeen = recentFingerprints.get(fingerprint);
        if (lastSeen != null && (now - lastSeen) < DUPLICATE_WINDOW_MS) {
            return true;
        }
        recentFingerprints.put(fingerprint, now);

        if (recentFingerprints.size() > 180) {
            long cleanupThreshold = now - (2 * 60 * 60 * 1000L); // 2 heures
            recentFingerprints.entrySet().removeIf(entry -> entry.getValue() < cleanupThreshold);
        }
        return false;
    }

public static void preloadCalendar() {
    try {
        List<EconomicCalendarAPI.CalendarEvent> events = EconomicCalendarAPI.fetchUpcomingEvents(72);
        if (events == null || events.isEmpty()) {
            logToMain("⚠️ Calendrier vide ou non disponible.");
            return;
        }

        // ✅ WATCHER — Détecter les nouveaux résultats publiés depuis le dernier chargement
        List<EconomicCalendarAPI.CalendarEvent> newlyPublished = new ArrayList<>();
        for (EconomicCalendarAPI.CalendarEvent event : events) {
            if (event == null) continue;
            boolean hasActual = event.actual != null
                    && !event.actual.equals("N/A")
                    && !event.actual.isEmpty();
            if (hasActual) {
                String key = createEventKey(event.indicator, event.timestamp);
                EconomicCalendarAPI.CalendarEvent previous = upcomingEvents.get(key);
                boolean isNew = (previous == null)
                        || (previous.actual == null
                        || previous.actual.equals("N/A")
                        || previous.actual.isEmpty());
                if (isNew) newlyPublished.add(event);
            }
        }

        upcomingEvents.clear();

        // ── Tri par timestamp croissant ──
        List<EconomicCalendarAPI.CalendarEvent> sortedEvents = new ArrayList<>(events);
        Collections.sort(sortedEvents, (a, b) -> {
            long tsA = parseTimestamp(a.timestamp);
            long tsB = parseTimestamp(b.timestamp);
            return Long.compare(tsA, tsB);
        });

        // ── Stockage dans la map interne ──
        for (EconomicCalendarAPI.CalendarEvent event : sortedEvents) {
            if (event == null) continue;
            String key = createEventKey(event.indicator, event.timestamp);
            upcomingEvents.put(key, event);
        }

        // ── Construction du rapport Telegram ──
        // ✅ Envoyer uniquement si le contenu a changé
        // ── Construction du rapport Telegram ──
        StringBuilder report = new StringBuilder();
        report.append("📅 *CALENDRIER ÉCONOMIQUE — PROCHAINS ÉVÉNEMENTS*\n");
        report.append("─────────────────────────────────────────\n");

        String lastDay = "";
        int totalAffiche = 0;

        for (EconomicCalendarAPI.CalendarEvent event : sortedEvents) {
    if (event == null || event.indicator == null || event.timestamp == null) continue;

    // ✅ Filtrer les jours fériés et événements sans impact
    String indUpper = event.indicator.toUpperCase(Locale.ROOT);
    if (indUpper.contains("BANK HOLIDAY") || indUpper.contains("PUBLIC HOLIDAY") ||
        indUpper.contains("MARKET HOLIDAY") || indUpper.contains("DAY OFF") ||
        indUpper.contains("NATIONAL HOLIDAY")) continue;
            String currentDay = formatEventDay(event.timestamp);
            if (!currentDay.equals(lastDay)) {
                report.append("\n📆 *").append(currentDay).append("*\n");
                lastDay = currentDay;
            }

            String imp = event.importance != null ? event.importance.toUpperCase(Locale.ROOT) : "";
            String impactIcon;
            if      (imp.equals("HIGH"))   impactIcon = "🔴";
            else if (imp.equals("MEDIUM")) impactIcon = "🟠";
            else                           impactIcon = "⚪";

            String pays = (event.country != null && !event.country.isEmpty()) ? event.country : "?";
            String time = formatEventTime(event.timestamp);

            report.append(impactIcon)
                  .append(" `").append(time).append("` ")
                  .append("[").append(pays).append("] ")
                  .append(event.indicator);

            boolean hasActual   = event.actual   != null && !event.actual.equals("N/A")   && !event.actual.isEmpty();
            boolean hasForecast = event.forecast != null && !event.forecast.equals("N/A") && !event.forecast.isEmpty();
            boolean hasPrevious = event.previous != null && !event.previous.equals("N/A") && !event.previous.isEmpty();

            if (hasActual && hasForecast) {
                report.append(" | Réel: `").append(event.actual)
                      .append("` Prévu: `").append(event.forecast).append("`");
                try {
                    double a = Double.parseDouble(event.actual.replaceAll("[^\\d.\\-]", ""));
                    double f = Double.parseDouble(event.forecast.replaceAll("[^\\d.\\-]", ""));
                    double diff = a - f;
                    if (Math.abs(diff) > 0.0) {
                        report.append(diff > 0 ? " 📈 SURPRISE HAUSSIÈRE" : " 📉 SURPRISE BAISSIÈRE");
                    }
                } catch (Exception ignored) {}
            } else if (hasForecast) {
                report.append(" | Prévu: `").append(event.forecast).append("`");
            } else if (hasActual) {
                report.append(" | Réel: `").append(event.actual).append("`");
            }

            if (hasPrevious) report.append(" Préc: `").append(event.previous).append("`");
            report.append("\n");
            totalAffiche++;
        }

        report.append("\n─────────────────────────────────────────\n");
        report.append("📊 *Total :* ").append(totalAffiche).append(" événements\n");
        report.append("🕒 *Mis à jour :* ").append(getMadaTimeNow()).append(" (Mada)");

        // ✅ Hash pour éviter les doublons Telegram
        String reportStr = report.toString();
        String newHash   = String.valueOf(reportStr.hashCode());

        if (!newHash.equals(lastCalendarHash)) {
            lastCalendarHash = newHash;
            sendCalendarToTelegram(reportStr);
            logToMain("📤 [CALENDRIER] Rapport envoyé — contenu modifié");
        } else {
            logToMain("⏭️ [CALENDRIER] Rapport inchangé — envoi ignoré");
        }

        // ✅ Analyse et envoi immédiat des nouveaux résultats publiés sans notification Android
        for (EconomicCalendarAPI.CalendarEvent event : newlyPublished) {
            analyzeAndSendCalendarResult(event);
        }
        // ✅ Log cohérent — affiche brut reçu ET stocké
        logToMain("✅ Calendrier chargé : " + upcomingEvents.size()
        + " stockés / " + events.size() + " reçus — "
        + newlyPublished.size() + " nouveaux résultats.");

    } catch (Exception e) {
        logToMain("⚠️ Échec préchargement calendrier : " + e.getMessage());
        Log.e(TAG, "Erreur preloadCalendar", e);
    }
}

// ── Envoi Telegram avec découpage automatique (limite 4000 chars) ──
private static void sendCalendarToTelegram(String fullMessage) {
    if (appContext == null) {
        Log.w(TAG, "appContext null — impossible d'envoyer le calendrier sur Telegram");
        return;
    }
    int maxLength = 4000;
    int start = 0;
    while (start < fullMessage.length()) {
        int end = Math.min(start + maxLength, fullMessage.length());
        if (end < fullMessage.length()) {
            int lastNewline = fullMessage.lastIndexOf('\n', end);
            if (lastNewline > start) end = lastNewline + 1;
        }
        String chunk = fullMessage.substring(start, end);
        NotificationService.sendTelegramSecure(chunk, appContext);
        start = end;
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
    }
}

// ── Helpers formatage date/heure Madagascar ──
private static String formatEventDay(String timestamp) {
    try {
        long ts = parseTimestamp(timestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE dd/MM", Locale.FRANCE);
        sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        return sdf.format(new Date(ts));
    } catch (Exception e) { return "?"; }
}

private static String formatEventTime(String timestamp) {
    try {
        long ts = parseTimestamp(timestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.FRANCE);
        sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        return sdf.format(new Date(ts));
    } catch (Exception e) { return "?"; }
}

private static String getMadaTimeNow() {
    try {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        return sdf.format(new Date());
    } catch (Exception e) { return "N/A"; }
}

public static void checkUpcomingAlerts() {
    if (appContext == null) return;
    try {
        long nowMs         = System.currentTimeMillis();
        long windowStartMs = nowMs + (25 * 60 * 1000L);
        long windowEndMs   = nowMs + (35 * 60 * 1000L);

        for (EconomicCalendarAPI.CalendarEvent ev : upcomingEvents.values()) {
            if (ev == null || ev.indicator == null || ev.timestamp == null) continue;
            if (!"HIGH".equalsIgnoreCase(ev.importance)) continue;

            long evTs = parseTimestamp(ev.timestamp);
            if (evTs < windowStartMs || evTs > windowEndMs) continue;

            String alertKey = ev.indicator + "_" + ev.timestamp;
            Long lastSent = lastAlertsSent.get(alertKey);
            if (lastSent != null && (nowMs - lastSent) < 20 * 60 * 1000L) continue;

            lastAlertsSent.put(alertKey, nowMs);

            long minutesRestantes = (evTs - nowMs) / (60 * 1000L);

            StringBuilder alert = new StringBuilder();
            alert.append("⏰ *ALERTE PRÉVENTIVE — DANS ")
                 .append(minutesRestantes).append(" MIN*\n");
            alert.append("─────────────────────────────────────────\n");
            alert.append("🔴 *").append(ev.indicator).append("*\n");
            alert.append("🌍 Pays : ")
                 .append(ev.country != null ? ev.country : "?").append("\n");
            alert.append("🕒 Heure : ")
                 .append(formatEventTime(ev.timestamp)).append(" (Mada)\n");

            if (ev.forecast != null && !ev.forecast.equals("N/A") 
                    && !ev.forecast.isEmpty())
                alert.append("📊 Consensus : ").append(ev.forecast).append("\n");

            if (ev.previous != null && !ev.previous.equals("N/A") 
                    && !ev.previous.isEmpty())
                alert.append("📌 Précédent : ").append(ev.previous).append("\n");

            List<String> assets = EconomicCalendarAPI.mapIndicatorToAssetsIntermarket(
                ev.indicator,
                ev.country != null ? ev.country : "United States");
            if (!assets.isEmpty())
                alert.append("🎯 Actifs : ")
                     .append(String.join(", ", assets)).append("\n");

            alert.append("─────────────────────────────────────────\n");
            alert.append("⚠️ *Préparez-vous à une forte volatilité.*");

            NotificationService.sendTelegramSecure(alert.toString(), appContext);
            logToMain("⏰ [ALERTE] " + ev.indicator + 
                      " dans " + minutesRestantes + " min");
        }
    } catch (Exception e) {
        Log.e(TAG, "Erreur checkUpcomingAlerts", e);
    }
}
    
    public static void cleanupOldFingerprints() {
        if (recentFingerprints == null || recentFingerprints.isEmpty()) return;
        
        long now = System.currentTimeMillis();
        // 2 heures converties explicitement en millisecondes (utilisation du suffixe L pour la sécurité)
        long cleanupThreshold = now - (2 * 60 * 60 * 1000L); 
        
        try {
            // removeIf est sûr ici grâce à l'utilisation d'une ConcurrentHashMap
            recentFingerprints.entrySet().removeIf(entry -> entry.getValue() < cleanupThreshold);
        } catch (Exception e) {
            Log.e("NotificationService", "Erreur lors du nettoyage des fingerprints", e);
        }
    }

    // ─────────────────────────────────────────────────────────────
//  WATCHER — Analyse résultats calendaires publiés sans news
// ─────────────────────────────────────────────────────────────
private static void analyzeAndSendCalendarResult(EconomicCalendarAPI.CalendarEvent event) {
    if (appContext == null || event == null) return;
    try {
        // ✅ Construction du texte enrichi
        String title   = event.indicator;
        String content = event.indicator
                + (event.country != null ? " " + event.country : "");

        if (event.actual   != null && !event.actual.equals("N/A"))
            content += " ACTUAL: "   + event.actual;
        if (event.forecast != null && !event.forecast.equals("N/A"))
            content += " FORECAST: " + event.forecast;
        if (event.previous != null && !event.previous.equals("N/A"))
            content += " PREVIOUS: " + event.previous;

        // ✅ Détection surprise haussière / baissière
        // Dans la méthode analyzeAndSendCalendarResult
// ... (code précédent d'assemblage du contenu)

// ✅ Construction d'une chaîne brute propre et standardisée pour le parseur
// On utilise un format naturel que EconomicAnalyzer sait décoder à l'aide de sa méthode traiterEvenementTextuel ou extraireChiffres
String analyseInput = "Indicator: " + event.indicator + " | Actual: " + event.actual + " | Forecast: " + event.forecast;

// ✅ Dérogation de l'analyse surprise à l'EconomicAnalyzer
EconomicAnalyzer.EvaluationResult eval = EconomicAnalyzer.analyserEvenement(
        event.indicator, 
        analyseInput
);

// ✅ Alignement strict avec les retours de votre classe EconomicAnalyzer
if (eval != null && eval.isParsed) {
    if ("HAUSSIER".equals(eval.marketImpact)) {
        content += " | Status: HIGHER THAN EXPECTED 🟢";
    } else if ("BAISSIER".equals(eval.marketImpact)) {
        content += " | Status: LOWER THAN EXPECTED 🔴";
    } else {
        content += " | Status: AS EXPECTED ⚪"; // Cas où actual == forecast
    }
} else {
    // Si l'analyseur n'a pas pu extraire de chiffres (ex: données textuelles, révisions sans consensus)
    content += " | Status: UNCHANGED/TEXTUAL ⚪"; 
}

// ✅ Récupération des actifs liés
List<String> assets = EconomicCalendarAPI.mapIndicatorToAssetsIntermarket(
        event.indicator,
        event.country != null ? event.country : "United States");

// ✅ Envoi vers Groq + Telegram sécurisé (S'exécutera toujours sans lever d'exception)
NotificationService.sendToGroqAndTelegram(
        "Calendrier Économique", title, content, assets, appContext);
        logToMain("📤 Résultat calendaire : "
                + event.indicator + " | " + event.actual + " vs " + event.forecast);

    } catch (Exception e) {
        Log.e(TAG, "Erreur analyzeAndSendCalendarResult : " + event.indicator, e);
    }
}
    
}
