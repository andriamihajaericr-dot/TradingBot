package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EventValidator {

    private static Map<String, EconomicCalendarAPI.CalendarEvent> upcomingEvents =
        new ConcurrentHashMap<>();

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
        public String  geoContext     = "";   // Description de la zone géopolitique détectée

        public ValidationResult() {}

        public ValidationResult(boolean isConfirmed, int confidence, String reason) {
            this.isConfirmed = isConfirmed;
            this.confidence  = confidence;
            this.reason      = reason;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  POINT D'ENTRÉE PRINCIPAL
    // ─────────────────────────────────────────────────────────────

    public static ValidationResult validate(
            String title,
            String content,
            long timestamp,
            List<String> detectedAssets
    ) {
        ValidationResult result = new ValidationResult();

        if (title    == null) title    = "";
        if (content  == null) content  = "";
        if (detectedAssets == null) detectedAssets = new ArrayList<>();

        String combined = (title + " " + content).toLowerCase();

        // ── ÉTAPE 1 : Filtre anti-rumeur absolu (avant tout autre traitement) ──────────
        // Si le texte contient des marqueurs de non-confirmation, rejet immédiat.
        // Un événement géo réel n'a pas besoin d'être "rumored" ou "alleged".
        if (containsRumorMarkers(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Rejeté — Marqueur de rumeur ou non-confirmé détecté";
            logToMain("[VALIDATOR] ❌ Rumeur/Non-confirmé — rejeté avant analyse");
            return result;
        }

        // ── ÉTAPE 2 : Filtre éditorial et opinion pure ───────────────────────────────
        // CORRECTION : mots resserrés pour ne pas bloquer des news géo légitimes.
        // "party", "republican", "democrat" retirés (bloquaient des news Fed policy et géo).
        if (containsEditorialContent(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Bruit macroéconomique (Opinion/Éditorial pur)";
            logToMain("[VALIDATOR] ❌ Rejeté — Contenu éditorial/opinion pur");
            return result;
        }

        // ── ÉTAPE 3 : Validation calendrier économique (données chiffrées) ────────────
        EconomicCalendarAPI.CalendarEvent match = findMatchingEvent(title, content, timestamp);

        if (match != null) {
            result.isConfirmed = true;
            result.confidence  = 98; // Niveau institutionnel — donnée confirmée par calendrier
            result.forecast    = match.forecast != null ? match.forecast : "N/A";
            result.previous    = match.previous != null ? match.previous : "N/A";
            result.actual      = match.actual   != null ? match.actual   : "N/A";
            result.reason      = "Confirmé par calendrier économique global";

            if (match.affectedAssets != null) {
                for (String asset : match.affectedAssets) {
                    if (asset != null && !detectedAssets.contains(asset)) {
                        detectedAssets.add(asset);
                        result.assetsEnriched = true;
                    }
                }
            }

            logToMain("[VALIDATOR] ✓ Calendrier confirmé : " +
                      (match.indicator != null ? match.indicator : "Inconnu"));
            return result;
        }

        // ── ÉTAPE 4 : Détection géopolitique (hors calendrier, impact direct marché) ──
        GeoAssessment geo = assessGeopoliticalEvent(combined);

        if (geo.confidence >= 65) {
            result.isConfirmed = true;
            result.confidence  = geo.confidence;
            result.reason      = "Événement géopolitique confirmé (Impact direct marché)";
            result.geoContext  = geo.contextLabel;

            // Enrichissement des actifs impactés par la zone géo détectée
            for (String asset : geo.impactedAssets) {
                if (!detectedAssets.contains(asset)) {
                    detectedAssets.add(asset);
                    result.assetsEnriched = true;
                }
            }

            logToMain("[VALIDATOR] 🌍 Géo confirmé [" + geo.contextLabel + "] " +
                      geo.confidence + "% — Actifs : " + geo.impactedAssets);
            return result;
        }

        // ── ÉTAPE 5 : Breaking News générique (flux interbancaire) ───────────────────
        result.confidence = calculateBreakingNewsConfidence(title, content);
        result.reason     = "Breaking News (Flux Interbancaire)";

        if (result.confidence < 65) {
            result.confidence  = 0;
            result.isConfirmed = false;
            logToMain("[VALIDATOR] ❌ Confiance insuffisante : " + result.confidence + "%");
        } else {
            result.isConfirmed = true;
            logToMain("[VALIDATOR] ⚡ Breaking News retenu : " + result.confidence + "%");
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────
    //  FILTRE ANTI-RUMEUR (ÉTAPE 1)
    //  Rejette tout texte contenant un marqueur de non-confirmation.
    //  Un fait réel (drone tiré, sanction annoncée) n'est jamais
    //  qualifié de "rumored" ou "allegedly" dans un flux institutionnel.
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    //  FILTRE ÉDITORIAL (ÉTAPE 2) — version resserrée
    //  Ne bloque plus "party", "republican", "democrat" qui
    //  peuvent apparaître dans des news légitimes sur la Fed.
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    //  ÉVALUATION GÉOPOLITIQUE (ÉTAPE 4)
    //
    //  Grille de scoring en 3 dimensions :
    //  A) Source crédible identifiée          → +25
    //  B) Action militaire/géo factualisée    → +30
    //  C) Zone géographique à impact marché   → +20
    //  D) Entité précise (nombre, lieu)       → +15
    //  E) Confirmation officielle citée       → +10
    //
    //  Chaque zone géo mappe vers des actifs précis.
    // ─────────────────────────────────────────────────────────────

    private static class GeoAssessment {
        int          confidence    = 0;
        String       contextLabel  = "";
        List<String> impactedAssets = new ArrayList<>();
    }

    private static GeoAssessment assessGeopoliticalEvent(String text) {
        GeoAssessment geo = new GeoAssessment();
        int score = 0;

        // ── A. Source crédible (score source) ────────────────────
        boolean hasCredibleSource =
            text.contains("reuters")         ||
            text.contains("bloomberg")       ||
            text.contains("associated press")||
            text.contains(" ap ")            ||
            text.contains("idf")             || // Israel Defense Forces (source officielle)
            text.contains("pentagon")        ||
            text.contains("nato")            ||
            text.contains("white house")     ||
            text.contains("ministry of")     ||
            text.contains("times of israel") ||
            text.contains("al jazeera")      ||
            text.contains("bbc")             ||
            text.contains("cnn")             ||
            text.contains("axios")           ||
            text.contains("haaretz")         ||
            text.contains("fxhedgers")       ||
            text.contains("deltaone");

        if (hasCredibleSource) score += 25;

        // ── B. Action militaire/géo concrète (verbe factuel passé) ──
        boolean hasFactualAction =
            text.contains("fired")        ||
            text.contains("launched")     ||
            text.contains("struck")       ||
            text.contains("hit")          ||
            text.contains("attacked")     ||
            text.contains("bombed")       ||
            text.contains("intercepted")  ||
            text.contains("shot down")    ||
            text.contains("killed")       ||
            text.contains("destroyed")    ||
            text.contains("sanctions")    ||
            text.contains("sanctioned")   ||
            text.contains("embargo")      ||
            text.contains("blockade")     ||
            text.contains("ceasefire")    ||
            text.contains("escalation")   ||
            text.contains("mobilization") ||
            text.contains("invasion")     ||
            text.contains("airstrike")    ||
            text.contains("air strike")   ||
            text.contains("missile")      ||
            text.contains("drone")        ||
            text.contains("explosion")    ||
            text.contains("troops")       ||
            text.contains("forces")       ||
            text.contains("naval")        ||
            text.contains("warship")      ||
            text.contains("conflict")     ||
            text.contains("offensive");

        if (hasFactualAction) score += 30;

        // ── C. Zone géographique à impact marché direct ──────────
        // Chaque zone a des actifs précis impactés
        boolean geoZoneFound = false;

        // Zone 1 : Moyen-Orient (Israël, Iran, Golfe, Détroit d'Ormuz)
        // Impact : USOIL critique (routes pétrolières), GOLD refuge, USDJPY refuge
        boolean isMoyenOrient =
            text.contains("israel")       ||
            text.contains("iran")         ||
            text.contains("gaza")         ||
            text.contains("lebanon")      ||
            text.contains("hezbollah")    ||
            text.contains("hamas")        ||
            text.contains("houthi")       ||
            text.contains("yemen")        ||
            text.contains("red sea")      ||
            text.contains("strait of hormuz") ||
            text.contains("persian gulf") ||
            text.contains("saudi")        ||
            text.contains("riyadh")       ||
            text.contains("tel aviv")     ||
            text.contains("jerusalem")    ||
            text.contains("beirut")       ||
            text.contains("tehran")       ||
            text.contains("middle east");

        if (isMoyenOrient) {
            geo.contextLabel = "Moyen-Orient / Pétrole";
            geo.impactedAssets.addAll(Arrays.asList(
                "USOIL",   // Impact maximal — routes pétrolières directement menacées
                "GOLD",    // Refuge universel
                "USDJPY",  // Yen refuge (graphique baisse)
                "NASDAQ",  // Risk-off actions
                "SP500",   // Risk-off actions
                "BITCOIN", // Actif spéculatif fuit
                "EURUSD",  // Euro sous pression risk-off
                "AUDUSD"   // Devise risk-on pénalisée
            ));
            score += 20;
            geoZoneFound = true;
        }

        // Zone 2 : Europe de l'Est (Ukraine, Russie, OTAN)
        // Impact : EURUSD, GBPUSD (Europe exposée), USOIL (Russie = fournisseur)
        boolean isEuropeEst =
            text.contains("ukraine")      ||
            text.contains("russia")       ||
            text.contains("moscow")       ||
            text.contains("kyiv")         ||
            text.contains("kiev")         ||
            text.contains("kremlin")      ||
            text.contains("nato")         ||
            text.contains("putin")        ||
            text.contains("zelensky")     ||
            text.contains("donbas")       ||
            text.contains("crimea")       ||
            text.contains("kharkiv")      ||
            text.contains("odessa")       ||
            text.contains("nord stream")  ||
            text.contains("black sea");

        if (isEuropeEst && !geoZoneFound) {
            geo.contextLabel = "Europe de l'Est / OTAN";
            geo.impactedAssets.addAll(Arrays.asList(
                "EURUSD",  // Euro exposé directement
                "GBPUSD",  // Livre exposée (OTAN)
                "USOIL",   // Russie = producteur majeur, sanctions = choc offre
                "GOLD",    // Refuge
                "USDJPY",  // Yen refuge
                "NASDAQ",  // Risk-off
                "SP500",   // Risk-off
                "BITCOIN"  // Actif spéculatif fuit
            ));
            score += 20;
            geoZoneFound = true;
        } else if (isEuropeEst) {
            // Co-occurrence Moyen-Orient + Europe de l'Est → enrichir les actifs
            if (!geo.impactedAssets.contains("EURUSD"))  geo.impactedAssets.add("EURUSD");
            if (!geo.impactedAssets.contains("GBPUSD"))  geo.impactedAssets.add("GBPUSD");
        }

        // Zone 3 : Asie-Pacifique (Chine, Taïwan, Corée du Nord, Mer de Chine)
        // Impact : AUDUSD (Chine = partenaire AUS), indices tech (TSMC/NASDAQ)
        boolean isAsiePacifique =
            text.contains("china")        ||
            text.contains("taiwan")       ||
            text.contains("beijing")      ||
            text.contains("south china sea") ||
            text.contains("north korea")  ||
            text.contains("pyongyang")    ||
            text.contains("kim jong")     ||
            text.contains("xi jinping")   ||
            text.contains("pla ")         || // People's Liberation Army
            text.contains("tsmc")         ||
            text.contains("semiconductor")||
            text.contains("strait of taiwan");

        if (isAsiePacifique && !geoZoneFound) {
            geo.contextLabel = "Asie-Pacifique / Chine";
            geo.impactedAssets.addAll(Arrays.asList(
                "AUDUSD",  // AUD corrélé à la Chine (partenaire commercial majeur)
                "USDJPY",  // Japon en première ligne géographique
                "NASDAQ",  // TSMC/semi-conducteurs = 30% du NASDAQ
                "SP500",   // Risk-off
                "GOLD",    // Refuge
                "BITCOIN", // Actif spéculatif fuit
                "USOIL"    // Demande Chine impactée
            ));
            score += 20;
            geoZoneFound = true;
        } else if (isAsiePacifique) {
            if (!geo.impactedAssets.contains("AUDUSD")) geo.impactedAssets.add("AUDUSD");
            if (!geo.impactedAssets.contains("NASDAQ")) geo.impactedAssets.add("NASDAQ");
        }

        // Zone 4 : Amérique Latine / Canada / Mexique (tarifs, commerce)
        boolean isAmeriqueLatine =
            text.contains("mexico")       ||
            text.contains("tariff")       ||
            text.contains("trade war")    ||
            text.contains("canada sanctions") ||
            text.contains("opec")         ||
            text.contains("venezuela")    ||
            text.contains("colombia");

        if (isAmeriqueLatine && !geoZoneFound) {
            geo.contextLabel = "Commerce / OPEC / Amériques";
            geo.impactedAssets.addAll(Arrays.asList(
                "USDCAD",  // CAD directement lié aux relations US-Canada
                "USOIL",   // OPEC / Venezuela = producteurs
                "NASDAQ",  // Tarifs tech
                "SP500"
            ));
            score += 15;
            geoZoneFound = true;
        }

        // Zone 5 : Afrique / autres zones (impact plus limité, score réduit)
        boolean isAutresZones =
            text.contains("africa")       ||
            text.contains("sudan")        ||
            text.contains("ethiopia")     ||
            text.contains("coup")         ||
            text.contains("junta")        ||
            text.contains("civil war");

        if (isAutresZones && !geoZoneFound) {
            geo.contextLabel = "Géopolitique Émergent";
            geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL"));
            score += 10;
            geoZoneFound = true;
        }

        // Si aucune zone détectée mais qu'on a des actions militaires → score partiel
        if (!geoZoneFound && hasFactualAction) {
            geo.contextLabel = "Événement Géo Non Régionalisé";
            geo.impactedAssets.addAll(Arrays.asList("GOLD", "USDJPY"));
            score += 5;
        }

        // ── D. Entité précise (nombre, lieu précis) ──────────────
        boolean hasPreciseEntity =
            text.matches(".*\\d+\\s*(drone|missile|rocket|soldier|ship|warship|bomb).*") ||
            text.matches(".*\\d+\\s*(km|miles|kilometers).*")                            ||
            text.contains("confirmed dead")   ||
            text.contains("confirmed killed") ||
            text.contains("confirmed hit");

        if (hasPreciseEntity) score += 15;

        // ── E. Confirmation officielle citée ─────────────────────
        boolean hasOfficialConfirmation =
            text.contains("confirmed")       ||
            text.contains("official said")   ||
            text.contains("officials said")  ||
            text.contains("spokesman said")  ||
            text.contains("statement said")  ||
            text.contains("announced")       ||
            text.contains("idf confirmed")   ||
            text.contains("pentagon confirmed");

        if (hasOfficialConfirmation) score += 10;

        // ── Pondération finale ────────────────────────────────────
        // Sans zone géo ET sans action factualisée → pas un événement géo actionnable
        if (!geoZoneFound && !hasFactualAction) score = 0;

        geo.confidence = Math.min(100, score);
        return geo;
    }

    // ─────────────────────────────────────────────────────────────
    //  CALENDRIER ÉCONOMIQUE (ÉTAPE 3)
    // ─────────────────────────────────────────────────────────────

    private static EconomicCalendarAPI.CalendarEvent findMatchingEvent(
            String title, String content, long timestamp) {

        String combined = (title + " " + content).toLowerCase();
        long window = 10 * 60 * 1000; // Fenêtre stricte ±10 minutes

        for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents.values()) {
            if (event == null || event.timestamp == null || event.indicator == null) continue;

            long eventTime = parseTimestamp(event.timestamp);

            if (Math.abs(eventTime - timestamp) < window) {
                String indicator = event.indicator.toLowerCase();
                String country   = event.country != null ? event.country : "";

                if (combined.contains(indicator) ||
                    matchesIndicatorKeywords(combined, indicator, country)) {
                    return event;
                }
            }
        }
        return null;
    }

    private static boolean matchesIndicatorKeywords(String text, String indicator, String country) {
        if (text == null || indicator == null) return false;
        if (indicator.contains("nfp") || indicator.contains("non-farm")) {
            return text.contains("nfp") || text.contains("non-farm") || text.contains("payroll");
        }
        if (indicator.contains("cpi") || indicator.contains("inflation")) {
            return text.contains("cpi") || text.contains("inflation") || text.contains("pce");
        }
        if (indicator.contains("gdp") || indicator.contains("growth")) {
            return text.contains("gdp") || text.contains("gross domestic");
        }
        if (indicator.contains("fed") || indicator.contains("fomc") || indicator.contains("rate")) {
            return text.contains("fed") || text.contains("rate") ||
                   text.contains("fomc") || text.contains("powell");
        }
        if (indicator.contains("ism") || indicator.contains("pmi")) {
            return text.contains("ism") || text.contains("pmi") || text.contains("manufacturing");
        }
        if (indicator.contains("retail")) {
            return text.contains("retail") || text.contains("consumer spending");
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    //  BREAKING NEWS GÉNÉRIQUE (ÉTAPE 5)
    // ─────────────────────────────────────────────────────────────

    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 40;
        String lower = ((title != null ? title : "") + " " +
                        (content != null ? content : "")).toLowerCase();

        if (lower.contains("breaking"))                              score += 25;
        if (lower.contains("urgent") || lower.contains("alert"))    score += 20;
        if (lower.contains("fxhedgers") || lower.contains("deltaone")) score += 25;
        if (lower.contains("federal reserve") || lower.contains("fomc")) score += 20;
        if (content != null && content.matches(".*\\d+\\.\\d+%.*")) score += 15;

        return Math.min(100, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  UTILITAIRES
    // ─────────────────────────────────────────────────────────────

    private static String createEventKey(String indicator, String timestamp) {
        if (indicator == null || timestamp == null) {
            return UUID.randomUUID().toString();
        }
        return indicator.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + timestamp;
    }

    private static long parseTimestamp(String timestamp) {
        if (timestamp == null) return System.currentTimeMillis();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return sdf.parse(timestamp).getTime();
        } catch (Exception e) {
            try {
                return Long.parseLong(timestamp) * 1000;
            } catch (Exception e2) {
                return System.currentTimeMillis();
            }
        }
    }

    /** Raccourci thread-safe pour logger dans MainActivity sans crash si l'activité est détruite. */
    private static void logToMain(String message) {
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog(message);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  PRÉCHARGEMENT DU CALENDRIER
    // ─────────────────────────────────────────────────────────────

    public static void preloadCalendar() {
        try {
            List<EconomicCalendarAPI.CalendarEvent> events =
                EconomicCalendarAPI.fetchUpcomingEvents(24);
            if (events == null) return;
            upcomingEvents.clear();

            for (EconomicCalendarAPI.CalendarEvent event : events) {
                if (event == null) continue;
                String key = createEventKey(event.indicator, event.timestamp);
                upcomingEvents.put(key, event);
            }
        } catch (Exception e) {
            logToMain("[VALIDATOR] ⚠️ Échec préchargement calendrier : " + e.getMessage());
        }
    }
}
