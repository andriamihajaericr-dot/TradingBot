package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import android.util.Log;
import android.content.Context;
import java.util.Collections; // ✅ ajouter si absent
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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
    // AJOUT (nouvelle classe + méthode, séparée de validateAgainstRealMarket)

/** 🧪 Résultat du croisement texte + technique (conviction, volatilité, cassures de niveaux) */
public static class CroisementTechniqueResult {
    public final List<String> anomalies = new ArrayList<>();
    public boolean estValide() { return anomalies.isEmpty(); }
    public String resume() {
        return anomalies.isEmpty() ? "" : "⚠️ Croisement technique : " + String.join(" | ", anomalies);
    }
}

private static final java.util.regex.Pattern PATTERN_CONVICTION = java.util.regex.Pattern.compile(
    "CONVICTION\\s*:.*?(\\d{1,3})\\s*%", java.util.regex.Pattern.CASE_INSENSITIVE);

/**
 * 🎯 Croise le rapport avec les données techniques déjà calculées par TradingViewFetcher
 * (conviction déclarée vs ampleur réelle du mouvement, "choc confirmé" vs volatilité/cassures réelles).
 * Utilise volatilityPercent, variance et les cassures de niveaux DÉJÀ présentes dans TVMarketData —
 * aucune nouvelle source de données requise.
 */
public static CroisementTechniqueResult verifierCroisementTechnique(
        String reportText, Map<String, TradingViewFetcher.TVMarketData> livePrices) {

    CroisementTechniqueResult result = new CroisementTechniqueResult();
    if (reportText == null || livePrices == null || livePrices.isEmpty()) return result;

    // 1️⃣ Extraire la conviction déclarée
    int convictionDeclaree = -1;
    java.util.regex.Matcher mConv = PATTERN_CONVICTION.matcher(reportText);
    if (mConv.find()) {
        try { convictionDeclaree = Integer.parseInt(mConv.group(1)); } catch (NumberFormatException ignored) {}
    }

    // 2️⃣ Choc réel confirmé déclaré dans le texte (réutilise le même vocabulaire que validerCoherenceRapport)
    boolean chocConfirmeDeclare = false;
    String reportLower = reportLower0(reportText);
    String[] motsChoc = {
        "choc d'offre", "frappe confirmée", "frappe militaire", "bombardement", "attaque confirmée",
        "embargo appliqué", "coupure des exportations", "raffinerie touchée", "raffinerie frappée"
    };
    for (String mot : motsChoc) {
        if (reportLower.contains(mot)) { chocConfirmeDeclare = true; break; }
    }

    for (String ligneBrute : reportText.split("\n")) {
        String ligne = ligneBrute.trim();
        if (!ligne.startsWith("•")) continue;
        String ligneUpper = ligne.toUpperCase(java.util.Locale.ROOT);

        for (Map.Entry<String, TradingViewFetcher.TVMarketData> entry : livePrices.entrySet()) {
            String asset = entry.getKey();
            if (!ligneUpper.contains(asset.toUpperCase(java.util.Locale.ROOT))) continue;
            TradingViewFetcher.TVMarketData data = entry.getValue();
            if (data == null) continue;

            // 3️⃣ Conviction élevée mais mouvement réel quasi nul
            if (convictionDeclaree >= 70 && Math.abs(data.changePercent) < 0.10
                    && (ligne.contains("🟢") || ligne.contains("🔴"))) {
                result.anomalies.add(String.format(java.util.Locale.US,
                    "%s : conviction déclarée %d%% mais mouvement réel quasi nul (%+.2f%%) — décalage ampleur/conviction",
                    asset, convictionDeclaree, data.changePercent));
            }

            // 4️⃣ "Choc confirmé" déclaré mais aucune trace technique réelle (pas de cassure de niveau, volatilité normale)
            boolean aucuneCassure = !data.brokeAbovePDH && !data.brokeBelowPDL
                    && !data.brokeAboveP4HH && !data.brokeBelowP4HL;
            if (chocConfirmeDeclare && aucuneCassure && data.volatilityPercent < 1.0) {
                result.anomalies.add(String.format(java.util.Locale.US,
                    "%s : 'choc confirmé' déclaré dans le rapport mais aucune cassure de niveau ni volatilité élevée détectée " +
                    "(range jour %.2f%%) — le marché ne corrobore pas l'ampleur du choc décrit",
                    asset, data.volatilityPercent));
            }
            break;
        }
    }

    // ✅ Même déduplication défensive que validateAgainstRealMarket
    List<String> anomaliesDedupliquees = new ArrayList<>(new java.util.LinkedHashSet<>(result.anomalies));
    result.anomalies.clear();
    result.anomalies.addAll(anomaliesDedupliquees);
    return result;
    }
    private static final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>(256);

    // ============================================================
    // 🎯 FIABILITÉ DES SOURCES — Prédiction (🟢/🔴) vs Marché RÉEL
    // ============================================================
    private static final String RELIABILITY_PREFS = "SourceReliabilityPrefs";
    private static final Map<String, Integer> sourceHits  = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sourceTotal = new ConcurrentHashMap<>();

    /**
     * 🔄 Recharge les scores de fiabilité par source depuis le stockage local
     */
    public static void hydrateSourceReliability(Context context) {
        if (context == null) return;
        try {
            android.content.SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(RELIABILITY_PREFS, Context.MODE_PRIVATE);
            Map<String, ?> all = prefs.getAll();
            sourceHits.clear();
            sourceTotal.clear();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (!(e.getValue() instanceof Integer)) continue;
                String key = e.getKey();
                int val = (Integer) e.getValue();
                if (key.endsWith("_hits"))  sourceHits.put(key.substring(0, key.length() - 5), val);
                if (key.endsWith("_total")) sourceTotal.put(key.substring(0, key.length() - 6), val);
            }
            Log.d(TAG, "📦 [FIABILITÉ SOURCES] " + sourceTotal.size() + " sources restaurées.");
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Échec du rechargement de la fiabilité des sources", e);
        }
    }

private static final String[] MOTS_PAYS_NON_US = {
    "norvège", "norvégien", "norway", "norwegian", "suède", "suédois", "sweden",
    "suisse", "swiss", "australie", "australien", "australia", "canada", "canadien",
    "nouvelle-zélande", "new zealand", "corée du sud", "south korea", "inde", "india"
};

/**
 * 🎯 Détecte un vecteur HAWKISH_US/DOVISH_US appliqué à une donnée d'un pays hors Fed/ECB/BoJ/BoE
 * (ex: Norvège) — trou dans la taxonomie des banques centrales, mislabeling quasi certain.
 */
public static String verifierPaysHorsTaxonomieBanqueCentrale(String reportText) {
    if (reportText == null) return null;
    String reportUpper = reportText.toUpperCase(java.util.Locale.ROOT);
    boolean estVecteurUS = reportUpper.contains("VECTEUR CIBLE : HAWKISH_US") || reportUpper.contains("VECTEUR CIBLE: HAWKISH_US")
            || reportUpper.contains("VECTEUR CIBLE : DOVISH_US") || reportUpper.contains("VECTEUR CIBLE: DOVISH_US");
    if (!estVecteurUS) return null;

    String reportLower = reportLower0(reportText);
    for (String pays : MOTS_PAYS_NON_US) {
        if (reportLower.contains(pays)) {
            return "VECTEUR CIBLE HAWKISH_US/DOVISH_US déclaré, mais la donnée concerne un pays hors taxonomie " +
                   "(Fed/ECB/BoJ/BoE) : '" + pays + "' — probable mislabeling, aucun canal de transmission direct vers la Fed.";
        }
    }
    return null;
}
private static final String[] MOTS_CONDITIONNEL_NON_CONFIRME = {
    "pourrait indiquer", "pourrait renforcer", "pourrait entraîner", "pourrait suggérer", "semble indiquer"
};
private static final String[] MOTS_CONFIRMATION_REELLE = {
    "confirmé", "confirmée", "frappe confirmée", "riposte militaire", "embargo appliqué", "bloqué"
};

/**
 * 🎯 Détecte l'usage du raisonnement "dollar fort" (Étape 1a, choc confirmé) sur un événement
 * explicitement conditionnel/non confirmé (Étape 1b) — mélange de deux cas incompatibles.
 */
public static String verifierChocDollarSurNonConfirme(String reportText) {
    if (reportText == null) return null;
    String reportLower = reportLower0(reportText);

    boolean invoqueRaisonnementDollarFort = reportLower.contains("demande de dollars")
            || reportLower.contains("renforcer le dollar") || reportLower.contains("dollar renforc")
            || reportLower.contains("renforce le dollar");

    boolean texteConditionnel = false;
    for (String mot : MOTS_CONDITIONNEL_NON_CONFIRME) {
        if (reportLower.contains(mot)) { texteConditionnel = true; break; }
    }
    boolean confirmationReelle = false;
    for (String mot : MOTS_CONFIRMATION_REELLE) {
        if (reportLower.contains(mot)) { confirmationReelle = true; break; }
    }

    if (invoqueRaisonnementDollarFort && texteConditionnel && !confirmationReelle) {
        return "Le rapport applique le raisonnement 'dollar fort' (Étape 1a, choc confirmé) sur un événement " +
               "explicitement conditionnel ('pourrait...') — l'Étape 1a exige un choc RÉEL CONFIRMÉ, pas une probabilité.";
    }
    return null;
}

    private static String normaliserSource(String source) {
        if (source == null) return "inconnu";
        return source.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static void persistSourceScore(Context context, String source, boolean correct) {
        String key = normaliserSource(source);
        sourceTotal.merge(key, 1, Integer::sum);
        sourceHits.merge(key, correct ? 1 : 0, Integer::sum);

        Context targetContext = (context != null) ? context : appContext;
        if (targetContext == null) return;
        try {
            targetContext.getApplicationContext().getSharedPreferences(RELIABILITY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(key + "_hits",  sourceHits.getOrDefault(key, 0))
                    .putInt(key + "_total", sourceTotal.getOrDefault(key, 0))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Échec sauvegarde score fiabilité pour " + key, e);
        }
    }

    /**
     * 📊 Taux de fiabilité historique d'une source (0-100), -1 si échantillon insuffisant (<3)
     */
    public static int getSourceReliability(String source) {
        String key = normaliserSource(source);
        int total = sourceTotal.getOrDefault(key, 0);
        if (total < 3) return -1;
        int hits = sourceHits.getOrDefault(key, 0);
        return (int) Math.round((hits * 100.0) / total);
    }

    /** 🧪 Résultat de la validation d'un rapport contre les prix réels */
    public static class MarketValidationResult {
        public int checked = 0;
        public int matched = 0;
        public final List<String> contradictions = new ArrayList<>();

        public String warningLine() {
            if (contradictions.isEmpty()) return "";
            return "⚠️ *CONTRADICTION MARCHÉ RÉEL* : " + String.join(" | ", contradictions);
        }
    }

    /**
     * 🎯 Compare chaque signal directionnel (🟢/🔴) du rapport IA au mouvement RÉEL
     * du marché (cache TradingView) et met à jour le score de fiabilité de la source.
     * Ex : rapport dit "GOLD 🟢" mais GOLD est réellement à -0.35% → contradiction, source pénalisée.
     */
    public static MarketValidationResult validateAgainstRealMarket(
            Context context, String source, String reportText,
            Map<String, TradingViewFetcher.TVMarketData> livePrices) {

        MarketValidationResult result = new MarketValidationResult();
        if (reportText == null || livePrices == null || livePrices.isEmpty()) return result;

        for (String line : reportText.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("•")) continue;
            boolean predictedBullish = trimmed.contains("🟢");
            boolean predictedBearish = trimmed.contains("🔴");
            if (!predictedBullish && !predictedBearish) continue;

            String upperLine = trimmed.toUpperCase(java.util.Locale.ROOT);
            for (Map.Entry<String, TradingViewFetcher.TVMarketData> entry : livePrices.entrySet()) {
                String asset = entry.getKey();
                if (!upperLine.contains(asset.toUpperCase(java.util.Locale.ROOT))) continue;
                TradingViewFetcher.TVMarketData data = entry.getValue();
                if (data == null || data.changePercent == 0.0) continue;

                boolean realBullish = data.changePercent > 0;
                boolean predictionCorrect = (predictedBullish == realBullish);

                result.checked++;
                if (predictionCorrect) {
                    result.matched++;
                } else {
                    result.contradictions.add(String.format(java.util.Locale.US,
                            "%s prévu %s / réel %+.2f%%", asset,
                            predictedBullish ? "🟢" : "🔴", data.changePercent));
                }
                persistSourceScore(context, source, predictionCorrect);
                break;
            }
        }
    // ✅ Déduplication défensive : évite les entrées identiques (ex: clés dupliquées/différemment castées
    // dans livePrices pour le même actif sous-jacent)
    List<String> contradictionsDedupliquees = new ArrayList<>(new java.util.LinkedHashSet<>(result.contradictions));
    result.contradictions.clear();
    result.contradictions.addAll(contradictionsDedupliquees);

    return result;
    
    }

    // ============================================================
    // 🎯 COHÉRENCE INTERNE DU RAPPORT — 6 actifs, texte/emoji, corrélation dollar
    // ============================================================
    private static final String[] SIX_ACTIFS_OBLIGATOIRES = {
        "NASDAQ", "SP500", "GOLD", "USOIL", "USDJPY", "GBPUSD"
    };

    private static final String[] MOTS_HAUSSE = {
        "hausse", "augmentation", "monter", "monte", "renforce", "renforcé",
        "s'envole", "grimpe", "progresse", "rebond", "dépasse",
        "accrue", "accru", "demande accrue" // ✅ "demande accrue pour X" = X monte
    };
       
    private static final String[] MOTS_BAISSE = {
        "baisse", "diminution", "chute", "recule", "affaiblit", "affaibli",
        "perte de valeur", "pèse sur", "s'effondre", "en dessous"
    };
    // ✅ Pattern refuge : toujours haussier par définition, indépendant des mots hausse/baisse littéraux
    private static final String[] MOTS_REFUGE = {
       "refuge classique", "valeur refuge", "safe haven", "actif refuge", "refuge sûr", "en tant que refuge"
    };

   private static final java.util.regex.Pattern PATTERN_ACTUAL_VS_FORECAST = java.util.regex.Pattern.compile(
        "(-?\\d+[.,]\\d+|-?\\d+)\\s*(?:\\(FORECAST\\s*(-?\\d+[.,]\\d+|-?\\d+)|\\(PREVISION[S]?\\s*(-?\\d+[.,]\\d+|-?\\d+))",
        java.util.regex.Pattern.CASE_INSENSITIVE);
    
    private static final String[] MOTS_QUALIFIENT_DEPASSEMENT = {"dépassé", "dépasse", "au-dessus", "au dessus", "supérieur"};
    private static final String[] MOTS_QUALIFIENT_MANQUE = {"en dessous", "inférieur", "manqué", "raté", "sous les"};
    
    /**
     * 🎯 Vérifie la cohérence entre le verdict textuel ("a dépassé les prévisions") et les chiffres
     * réellement cités dans le texte (actual vs forecast), pour éviter une lecture inversée (ex: 44 < 44.2
     * qualifié à tort de "dépassement").
     */
    public static List<String> verifierActualVsForecast(String texte) {
        List<String> anomalies = new ArrayList<>();
        if (texte == null) return anomalies;
    
        java.util.regex.Matcher m = PATTERN_ACTUAL_VS_FORECAST.matcher(texte);
        while (m.find()) {
            try {
                double actual = Double.parseDouble(m.group(1).replace(",", "."));
                String forecastStr = m.group(2) != null ? m.group(2) : m.group(3);
                if (forecastStr == null) continue;
                double forecast = Double.parseDouble(forecastStr.replace(",", "."));
    
                String texteLower = texte.toLowerCase(java.util.Locale.ROOT);
                boolean ditDepassement = false, ditManque = false;
                for (String mot : MOTS_QUALIFIENT_DEPASSEMENT) if (texteLower.contains(mot)) { ditDepassement = true; break; }
                for (String mot : MOTS_QUALIFIENT_MANQUE) if (texteLower.contains(mot)) { ditManque = true; break; }
    
                if (ditDepassement && actual < forecast) {
                    anomalies.add(String.format(java.util.Locale.US,
                        "Texte dit 'dépassé les prévisions' mais Actual %.2f < Forecast %.2f (c'est un MANQUÉ, pas un dépassement)",
                        actual, forecast));
                }
                if (ditManque && actual > forecast) {
                    anomalies.add(String.format(java.util.Locale.US,
                        "Texte dit 'en dessous des prévisions' mais Actual %.2f > Forecast %.2f (c'est un DÉPASSEMENT, pas un manqué)",
                        actual, forecast));
                }
            } catch (NumberFormatException ignored) {}
        }
        return anomalies;
    }
    
    /** 🧪 Résultat complet de la validation de cohérence d'un rapport */
    public static class CoherenceRapportResult {
        public final List<String> actifsManquants = new ArrayList<>();
        public final List<String> contradictionsTexteEmoji = new ArrayList<>();
        public final List<String> contradictionsCorrelation = new ArrayList<>();
        public final List<String> autoReferenceFlux = new ArrayList<>();
    
        public boolean estValide() {
            return actifsManquants.isEmpty() && contradictionsTexteEmoji.isEmpty()
                    && contradictionsCorrelation.isEmpty() && autoReferenceFlux.isEmpty();
        }
    
        public String resume() {
            StringBuilder sb = new StringBuilder();
            if (!actifsManquants.isEmpty())
                sb.append("⚠️ Actifs manquants: ").append(String.join(", ", actifsManquants)).append(". ");
            if (!contradictionsTexteEmoji.isEmpty())
                sb.append("⚠️ Texte/emoji: ").append(String.join(" | ", contradictionsTexteEmoji)).append(". ");
            if (!contradictionsCorrelation.isEmpty())
                sb.append("⚠️ Corrélation dollar: ").append(String.join(" | ", contradictionsCorrelation)).append(". ");
            if (!autoReferenceFlux.isEmpty())
                sb.append("⚠️ Flux auto-référencé: ").append(String.join(" | ", autoReferenceFlux)).append(". ");
            return sb.toString().trim();
        }
    }
     private static String reportLower0(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }
    /**
     * 🎯 Validation complète : 6 actifs obligatoires, contradiction texte/emoji ligne par ligne,
     * et cohérence de corrélation GOLD (inverse USD) / USDJPY (direct USD) / GBPUSD (inverse USD).
     */
    public static CoherenceRapportResult validerCoherenceRapport(String reportText) {
        CoherenceRapportResult result = new CoherenceRapportResult();
        if (reportText == null || reportText.isEmpty()) return result;
    
        String reportUpper = reportText.toUpperCase(java.util.Locale.ROOT);
    
       // 1️⃣ Présence des 6 actifs obligatoires — SAUF omission légitime (banque centrale étrangère sans choc global)
        boolean neutraliteLegitime = false;
        for (String vecteur : VECTEURS_BANQUE_ETRANGERE) {
            if (reportUpper.contains("VECTEUR CIBLE : " + vecteur) || reportUpper.contains("VECTEUR CIBLE: " + vecteur)) {
                String reportLowerCheck = reportLower0(reportText);
                boolean chocGlobal = false;
                for (String mot : MOTS_CHOC_GLOBAL_EXPLICITE) {
                    if (reportLowerCheck.contains(mot)) { chocGlobal = true; break; }
                }
                neutraliteLegitime = !chocGlobal;
                break;
            }
        }
         boolean aucunImpactDeclare = reportUpper.contains("AUCUN IMPACT SIGNIFICATIF");

        // ✅ Cas transitoire (avant intégration du correctif 1bis, ou modèle qui n'a pas suivi la consigne) :
        // section totalement vide (0 ligne "•" avec emoji) ET le texte explique déjà l'absence d'impact
        // ("aucun impact", "pas de choc", "n'est pas confirmé") → traiter comme légitime aussi, pas comme un bug.
        boolean sectionTotalementVide = !reportUpper.matches("(?s).*•[^\\n]*(🟢|🔴).*");
        boolean texteExpliqueAbsenceImpact = reportLower0(reportText).contains("aucun impact")
                || reportLower0(reportText).contains("pas de choc") || reportLower0(reportText).contains("n'est pas confirmé");

        if (!(aucunImpactDeclare || (sectionTotalementVide && texteExpliqueAbsenceImpact))) {
            for (String actif : SIX_ACTIFS_OBLIGATOIRES) {
                boolean estActifExempteBanqueEtrangere = neutraliteLegitime &&
                        (actif.equals("NASDAQ") || actif.equals("SP500") || actif.equals("GOLD") || actif.equals("USOIL"));
                if (!reportUpper.contains(actif) && !estActifExempteBanqueEtrangere) {
                    result.actifsManquants.add(actif);
                }
            }
        }
    
        // 2️⃣ Contradiction texte/emoji, ligne par ligne + capture direction par actif
        Map<String, String> directionParActif = new java.util.LinkedHashMap<>();
        for (String ligneBrute : reportText.split("\n")) {
            String ligne = ligneBrute.trim();
            if (!ligne.startsWith("•")) continue;
    
            String actifDetecte = null;
            String ligneUpper = ligne.toUpperCase(java.util.Locale.ROOT);
            for (String actif : SIX_ACTIFS_OBLIGATOIRES) {
                if (ligneUpper.contains(actif)) { actifDetecte = actif; break; }
            }
            if (actifDetecte == null) continue;
    
            boolean emojiHausse = ligne.contains("🟢");
            boolean emojiBaisse = ligne.contains("🔴");
            if (!emojiHausse && !emojiBaisse) continue;
            directionParActif.put(actifDetecte, emojiHausse ? "🟢" : "🔴");
    
            String ligneLower = ligne.toLowerCase(java.util.Locale.ROOT);
            boolean texteHausse = false, texteBaisse = false;
            for (String mot : MOTS_HAUSSE) if (ligneLower.contains(mot)) { texteHausse = true; break; }
            for (String mot : MOTS_BAISSE) if (ligneLower.contains(mot)) { texteBaisse = true; break; }
    
            if (emojiHausse && texteBaisse && !texteHausse) {
                result.contradictionsTexteEmoji.add(actifDetecte + " : emoji 🟢 mais texte annonce une baisse");
            }
            if (emojiBaisse && texteHausse && !texteBaisse) {
                result.contradictionsTexteEmoji.add(actifDetecte + " : emoji 🔴 mais texte annonce une hausse");
            }
        }
        // AJOUT (dans validerCoherenceRapport, juste après la boucle des lignes • qui remplit directionParActif)

        // Vérification fine GBPUSD : "livre/pound se renforce" doit être 🟢, jamais 🔴 (même si "baisse du dollar" apparaît dans la même phrase)
        for (String ligneBrute : reportText.split("\n")) {
            String ligne = ligneBrute.trim();
            if (!ligne.toUpperCase(java.util.Locale.ROOT).contains("GBPUSD")) continue;
            String ligneLower = ligne.toLowerCase(java.util.Locale.ROOT);
            boolean livreSeRenforce = (ligneLower.contains("livre") || ligneLower.contains("pound") || ligneLower.contains("sterling"))
                    && (ligneLower.contains("renforce") || ligneLower.contains("hausse") || ligneLower.contains("monte"));
            boolean livreBaisse = (ligneLower.contains("livre") || ligneLower.contains("pound") || ligneLower.contains("sterling"))
                    && (ligneLower.contains("affaiblit") || ligneLower.contains("baisse de la livre") || ligneLower.contains("chute"));
            if (livreSeRenforce && ligne.contains("🔴")) {
                result.contradictionsTexteEmoji.add("GBPUSD : texte dit 'la livre se renforce' mais emoji 🔴 (devrait être 🟢)");
            }
            if (livreBaisse && ligne.contains("🟢")) {
                result.contradictionsTexteEmoji.add("GBPUSD : texte dit 'la livre s'affaiblit' mais emoji 🟢 (devrait être 🔴)");
            }
        }
          
        for (String ligneBrute : reportText.split("\n")) {
            String ligne = ligneBrute.trim();
            if (!ligne.startsWith("•")) continue;
            String ligneLowerRefuge = ligne.toLowerCase(java.util.Locale.ROOT);
            boolean invoqueRefuge = false;
            for (String mot : MOTS_REFUGE) {
                if (ligneLowerRefuge.contains(mot)) { invoqueRefuge = true; break; }
            }
            if (!invoqueRefuge) continue;
        
            String ligneUpperRefuge = ligne.toUpperCase(java.util.Locale.ROOT);
            boolean estUSDJPY = ligneUpperRefuge.contains("USDJPY");
        
            if (estUSDJPY) {
                // ✅ Exception USDJPY : le refuge est le YEN (devise de base) — un yen refuge qui se renforce
                // fait BAISSER la paire USDJPY (moins de yens par dollar) → refuge implique 🔴, pas 🟢.
                if (ligne.contains("🟢")) {
                    result.contradictionsTexteEmoji.add(
                        "USDJPY : texte invoque 'refuge classique' (yen) mais emoji 🟢 — un yen refuge qui se renforce " +
                        "fait BAISSER la paire USDJPY (🔴 attendu, pas 🟢)");
                }
            } else {
                // GOLD, GBPUSD : l'actif coté EST le refuge → refuge implique 🟢, logique inchangée
                if (ligne.contains("🔴")) {
                    String actifDeLaLigne = "actif";
                    for (String actif : SIX_ACTIFS_OBLIGATOIRES) {
                        if (ligneUpperRefuge.contains(actif)) { actifDeLaLigne = actif; break; }
                    }
                    result.contradictionsTexteEmoji.add(actifDeLaLigne +
                        " : texte invoque 'refuge classique/valeur refuge' mais emoji 🔴 — un refuge est par nature haussier (🟢 attendu)");
                }
            }
        }

    // Pattern "pas d'impact direct" mais emoji directionnel quand même — devrait être NEUTRE/omis (Contrainte #1)
    String[] motsAucunImpact = {"pas d'impact direct", "aucun impact direct", "n'a pas d'impact", "sans impact direct"};
    for (String ligneBrute : reportText.split("\n")) {
        String ligne = ligneBrute.trim();
        if (!ligne.startsWith("•")) continue;
        String ligneLowerImpact = ligne.toLowerCase(java.util.Locale.ROOT);
        boolean ditAucunImpact = false;
        for (String mot : motsAucunImpact) {
            if (ligneLowerImpact.contains(mot)) { ditAucunImpact = true; break; }
        }
        if (ditAucunImpact && (ligne.contains("🟢") || ligne.contains("🔴"))) {
            String actifDeLaLigne = "actif";
            String ligneUpperImpact = ligne.toUpperCase(java.util.Locale.ROOT);
            for (String actif : SIX_ACTIFS_OBLIGATOIRES) {
                if (ligneUpperImpact.contains(actif)) { actifDeLaLigne = actif; break; }
            }
            result.contradictionsTexteEmoji.add(actifDeLaLigne +
                " : texte dit 'pas d'impact direct' mais un emoji directionnel est quand même affiché (devrait être NEUTRE/omis, Contrainte #1)");
        }
    }
        // 3️⃣ Corrélation GOLD (inverse USD) / USDJPY (direct USD) / GBPUSD (inverse USD)
        String goldDir   = directionParActif.get("GOLD");
        String usdjpyDir = directionParActif.get("USDJPY");
        String gbpusdDir = directionParActif.get("GBPUSD");
    
       
        // ✅ Détection indépendante du choc réel confirmé — basée sur le CONTENU de la news (FAIT MARQUANT),
        // pas sur ce que le modèle a choisi d'écrire comme mécanisme. Reprend/élargit les mots-clés de
        // chocReelConfirme (NotificationService) pour que le validateur ne dépende jamais de la bonne
        // volonté du modèle à nommer explicitement "dollar fort".
        // APRÈS
String[] motsChocOffreReelElargis = {
    "usd renforc", "dollar fort", "flight-to-cash", "dollar renforc",
    "renforcer le dollar", "renforce le dollar", "renforcement du dollar", "dollar se renforce",
    "raffinerie touchée", "raffinerie frappée", "attaque confirmée", "drone attack", "drones",
    "frappe confirmée", "frappe militaire", "bombardement", "incendie", "perte de production",
    "production perdue", "infrastructure pétrolière touchée", "infrastructure gazière touchée",
    "embargo appliqué", "coupure des exportations", "arrêt de la production", "riposte militaire"
};
        boolean chocDollarExplicite = false;
        for (String mot : motsChocOffreReelElargis) {
            if (reportLower0(reportText).contains(mot)) { chocDollarExplicite = true; break; }
        }
    
        // GOLD et USDJPY doivent normalement être opposés (l'un inverse-USD, l'autre direct-USD)
       if (goldDir != null && usdjpyDir != null) {
            boolean memesSens = goldDir.equals(usdjpyDir);
            if (memesSens) {
                result.contradictionsCorrelation.add(String.format(
                    "GOLD %s et USDJPY %s dans le même sens (%s) — " +
                    "GOLD (inverse USD) et USDJPY (direct USD) devraient TOUJOURS être opposés, quel que soit le régime dollar",
                    goldDir, usdjpyDir, chocDollarExplicite ? "régime dollar fort détecté" : "régime refuge/sans choc dollar"));
            }
        }
    
        boolean regimeGeo = reportUpper.contains("CRISE GÉOPOLITIQUE") || reportUpper.contains("VECTEUR CIBLE : GÉO");
        // ✅ Élargi : la Contrainte #6 couvre "risk-off/risk-on général", pas seulement le GÉO
        boolean regimeRiskGeneral = regimeGeo || reportUpper.contains("RISK-OFF") || reportUpper.contains("RISK-ON");
        // ✅ Un driver Fed pur (HAWKISH_US/DOVISH_US) est TOUJOURS un régime DOLLAR — même sans mot-clé pétrole/géo dans le texte
        boolean regimeDollarFort = chocDollarExplicite
                || reportUpper.contains("VECTEUR CIBLE : HAWKISH_US") || reportUpper.contains("VECTEUR CIBLE: HAWKISH_US");
        boolean regimeDollarFaible = reportUpper.contains("VECTEUR CIBLE : DOVISH_US") || reportUpper.contains("VECTEUR CIBLE: DOVISH_US");
    
        if (usdjpyDir != null && gbpusdDir != null) {
            if (regimeDollarFort) {
                // Dollar fort (Fed hawkish, ou choc d'offre GÉO confirmé) → USDJPY🟢 / GBPUSD🔴 obligatoire
                boolean usdjpyCoherent = "🟢".equals(usdjpyDir);
                boolean gbpusdCoherent = "🔴".equals(gbpusdDir);
                if (!usdjpyCoherent || !gbpusdCoherent) {
                    result.contradictionsCorrelation.add(
                        "Régime DOLLAR FORT (Fed hawkish ou choc d'offre GÉO confirmé) : USDJPY/GBPUSD devraient être INVERSES " +
                        "(attendu USDJPY🟢 + GBPUSD🔴, obtenu USDJPY" + usdjpyDir + " + GBPUSD" + gbpusdDir + ")");
                }
            } else if (regimeDollarFaible) {
                // Dollar faible (Fed dovish) → USDJPY🔴 / GBPUSD🟢 obligatoire (sens inverse du cas précédent)
                boolean usdjpyCoherent = "🔴".equals(usdjpyDir);
                boolean gbpusdCoherent = "🟢".equals(gbpusdDir);
                if (!usdjpyCoherent || !gbpusdCoherent) {
                    result.contradictionsCorrelation.add(
                        "Régime DOLLAR FAIBLE (Fed dovish) : USDJPY/GBPUSD devraient être INVERSES dans l'autre sens " +
                        "(attendu USDJPY🔴 + GBPUSD🟢, obtenu USDJPY" + usdjpyDir + " + GBPUSD" + gbpusdDir + ")");
                }
            } else if (regimeRiskGeneral) {
                // Régime RISK pur (GÉO sans choc d'offre confirmé, ou risk-off/risk-on général type TARIFS) → MÊME direction
                if (!usdjpyDir.equals(gbpusdDir)) {
                    result.contradictionsCorrelation.add(
                        "Régime RISK (GÉO/risk-off/risk-on général) sans choc dollar : USDJPY/GBPUSD devraient être dans le MÊME sens " +
                        "(obtenu USDJPY" + usdjpyDir + " + GBPUSD" + gbpusdDir + " — divergence non justifiée par BoJ/BoE isolé)");
                }
            }
        }    
        
        // 3️⃣bis Corrélation USOIL / choc d'offre confirmé — un choc d'offre doit faire monter USOIL, pas baisser
        String usoilDir = directionParActif.get("USOIL");
        boolean chocOffreMentionne = chocDollarExplicite; // ✅ Réutilise la même détection élargie (point 1) — un seul vocabulaire, cohérent partout
        if (chocOffreMentionne && "🔴".equals(usoilDir)) {
            result.contradictionsCorrelation.add(
                "USOIL🔴 alors qu'un 'choc d'offre confirmé' est mentionné dans le rapport — " +
                "un choc d'offre (frappe/blocage) doit normalement faire MONTER le prix du pétrole (USOIL🟢), pas baisser");
        }
        // AJOUT corrigé (à insérer entre la ligne 534 et 536)

    // ✅ Invariant matriciel : en régime GÉO pur (escalade, même non confirmée), USOIL doit rester 🟢
    // (prime de tension), sauf désescalade explicite.
    boolean regimeGeoPurSansChoc = regimeGeo && !chocDollarExplicite;
    boolean deseacaladeExplicite = reportLower0(reportText).contains("désescalade") || reportLower0(reportText).contains("accord confirmé")
            || reportLower0(reportText).contains("cessez-le-feu") || reportLower0(reportText).contains("trêve confirmée");
    if (regimeGeoPurSansChoc && "🔴".equals(usoilDir) && !deseacaladeExplicite) {
        result.contradictionsCorrelation.add(
            "USOIL🔴 en régime GÉO (escalade, choc non confirmé) sans désescalade explicite — " +
            "la matrice attend USOIL🟢 (prime de tension) dans ce cas, obtenu 🔴");
    }

    // ✅ Auto-contradiction : FAIT MARQUANT dit "escalade" mais une ligne d'actif dit "désactivée/apaisée"
    boolean faitMarquantEscalade = reportLower0(reportText).contains("escalade");
    String[] motsDeseacaladeLigne = {"désactivée", "désactivé", "apaisée", "apaisé", "neutralisée", "neutralisé", "levée"};
    for (String ligneBrute : reportText.split("\n")) {
        String ligne = ligneBrute.trim();
        if (!ligne.startsWith("•")) continue;
        String ligneLower = ligne.toLowerCase(java.util.Locale.ROOT);
        boolean ligneDeseacalade = false;
        for (String mot : motsDeseacaladeLigne) {
            if (ligneLower.contains(mot)) { ligneDeseacalade = true; break; }
        }
        if (faitMarquantEscalade && ligneDeseacalade) {
            String actifDeLaLigne = "actif";
            String ligneUpper = ligne.toUpperCase(java.util.Locale.ROOT);
            for (String actif : SIX_ACTIFS_OBLIGATOIRES) {
                if (ligneUpper.contains(actif)) { actifDeLaLigne = actif; break; }
            }
            result.contradictionsTexteEmoji.add(actifDeLaLigne +
                " : FAIT MARQUANT dit 'escalade' mais cette ligne invoque une désactivation/apaisement — contradiction interne");
        }
    }
    
        // 3️⃣ter Règle par défaut USDJPY en régime RISK-OFF pur (sans choc dollar) : le yen doit se renforcer (USDJPY🔴)
        boolean regimeRiskOffPur = (reportUpper.contains("RISK-OFF") || regimeGeo) && !chocDollarExplicite;
        boolean driverBojMentionne = reportUpper.contains("BOJ") || reportUpper.contains("BANQUE DU JAPON");
        if (regimeRiskOffPur && "🟢".equals(usdjpyDir) && !driverBojMentionne) {
            result.contradictionsCorrelation.add(
                "USDJPY🟢 en régime RISK-OFF pur sans choc dollar ni driver BoJ explicite — " +
                "le yen devrait se renforcer comme refuge classique (USDJPY🔴 attendu)");
        }
    
        // 4️⃣ Flux qui se justifie par lui-même au lieu du contenu de la news
        String reportLower = reportText.toLowerCase(java.util.Locale.ROOT);
        String[] motsAutoReference = {
            "cohérent avec le flux précédent", "cohérent avec le driver dominant",
            "maintenu, car", "flux précédent", "conformément au régime précédent"
        };
        for (String motCle : motsAutoReference) {
            if (reportLower.contains(motCle)) {
                result.autoReferenceFlux.add(
                    "Le FLUX DOMINANT se justifie par lui-même/le régime précédent au lieu du contenu de la news actuelle (phrase : \"" + motCle + "\")");
                break; // évite les doublons si plusieurs motifs matchent la même phrase
            }
        }
        // ✅ Détection robuste avec mots intercalés (ex: "cohérente avec le flux RISK-OFF précédent")
        java.util.regex.Pattern patternAutoRefSouple = java.util.regex.Pattern.compile(
            "coh[ée]rent[e]?\\s+avec\\s+le\\s+flux\\s+[\\wÀ-ÿ-]*\\s*précédent", java.util.regex.Pattern.CASE_INSENSITIVE);
        if (patternAutoRefSouple.matcher(reportText).find() && result.autoReferenceFlux.isEmpty()) {
            result.autoReferenceFlux.add(
                "Le FAIT MARQUANT lui-même justifie la news par le flux précédent au lieu d'un mécanisme propre " +
                "(ex: 'cohérente avec le flux RISK-OFF précédent')");
        }
    
        return result;
    }
  
    private static final String[] MOTS_GEO_LEGITIMES = {
        "iran", "israel", "hormuz", "ormuz", "ukrain", "russ" /* couvre russe/russie/russia */,
        "guerre", "war", "frappe", "missile", "drone", "militair", "military", "sanction", "embargo",
        "otan", "nato", "hezbollah", "houthi", "conflit", "invasion", "cessez-le-feu", "trêve", "treve",
        "chine" /* tensions Taïwan/mer de Chine */, "taiwan", "corée du nord", "north korea"
    };
    
    /**
     * 🎯 Vérifie que le VECTEUR CIBLE : GÉO déclaré est justifié par un vrai contenu géopolitique
     * dans le FAIT MARQUANT — évite le mislabeling (ex: décision de compliance interne d'une banque
     * classée à tort comme GÉO).
     */
    public static String verifierVecteurGeoPertinent(String reportText) {
        if (reportText == null) return null;
        String reportUpper = reportText.toUpperCase(java.util.Locale.ROOT);
        if (!reportUpper.contains("VECTEUR CIBLE : GÉO") && !reportUpper.contains("VECTEUR CIBLE: GÉO")) {
            return null; // pas de vecteur GÉO déclaré, rien à vérifier
        }
    
        String reportLower = reportLower0(reportText);
        for (String mot : MOTS_GEO_LEGITIMES) {
            if (reportLower.contains(mot)) return null; // légitime, un vrai mot-clé géopolitique est présent
        }
    
        return "VECTEUR CIBLE : GÉO déclaré mais aucun mot-clé géopolitique réel détecté dans le texte " +
               "(pays, conflit, militaire, sanction...) — probable mislabeling d'une news non-géopolitique (ex: compliance interne).";
    }
   private static final String[] VECTEURS_BANQUE_ETRANGERE = {
        "HAWKISH_ECB", "DOVISH_ECB", "HAWKISH_BOJ", "DOVISH_BOJ", "HAWKISH_BOE", "DOVISH_BOE"
    };
    private static final String[] ACTIFS_US_INTERDITS_ETRANGER = { "NASDAQ", "SP500", "GOLD", "USOIL" };
    private static final String[] MOTS_CHOC_GLOBAL_EXPLICITE = {
        "choc global", "crise systémique", "contagion", "récession mondiale confirmée"
    };
    
    /**
     * 🎯 Vérifie la RÈGLE ABSOLUE : NASDAQ/SP500/GOLD/USOIL doivent être NEUTRES (absents) sur un driver
     * de banque centrale étrangère (ECB/BoJ/BoE), sauf choc global explicitement mentionné.
     */
    public static List<String> verifierNeutraliteActifsUSSurBanqueEtrangere(String reportText) {
        List<String> violations = new ArrayList<>();
        if (reportText == null) return violations;
    
        String reportUpper = reportText.toUpperCase(java.util.Locale.ROOT);
        boolean estBanqueEtrangere = false;
        for (String vecteur : VECTEURS_BANQUE_ETRANGERE) {
            if (reportUpper.contains("VECTEUR CIBLE : " + vecteur) || reportUpper.contains("VECTEUR CIBLE: " + vecteur)) {
                estBanqueEtrangere = true;
                break;
            }
        }
        if (!estBanqueEtrangere) return violations;
    
        String reportLower = reportLower0(reportText);
        for (String mot : MOTS_CHOC_GLOBAL_EXPLICITE) {
            if (reportLower.contains(mot)) return violations; // choc global explicite : exception valide
        }
    
        for (String actif : ACTIFS_US_INTERDITS_ETRANGER) {
            // On cherche une ligne "• emoji ACTIF :" pour cet actif précis
            if (reportUpper.matches("(?s).*•[^\\n]*" + actif + "[^\\n]*(🟢|🔴).*")) {
                violations.add(actif + " listé sur un driver de banque centrale étrangère — devrait être NEUTRE/absent " +
                        "(RÈGLE ABSOLUE) sauf choc global explicite");
            }
        }
        return violations;
    }
    
    /**
     * 🎯 Détecte une contamination causale : un driver de banque centrale étrangère qui justifie
     * un impact via un mécanisme "la Fed" (aucun canal de transmission direct plausible).
     */
    public static String verifierContaminationCausaleFed(String reportText) {
        if (reportText == null) return null;
        String reportUpper = reportText.toUpperCase(java.util.Locale.ROOT);
    
        boolean estBanqueEtrangere = false;
        for (String vecteur : VECTEURS_BANQUE_ETRANGERE) {
            if (reportUpper.contains("VECTEUR CIBLE : " + vecteur) || reportUpper.contains("VECTEUR CIBLE: " + vecteur)) {
                estBanqueEtrangere = true;
                break;
            }
        }
        if (!estBanqueEtrangere) return null;
    
        String reportLower = reportLower0(reportText);
        if (reportLower.contains("position de la fed") || reportLower.contains("politique de la fed")
                || reportLower.contains("la fed pour") || reportLower.contains("renforce la fed")) {
            return "Driver de banque centrale ÉTRANGÈRE (ECB/BoJ/BoE) mais la justification invoque un mécanisme " +
                   "'la Fed' — aucun canal de transmission causal direct plausible entre une donnée étrangère isolée " +
                   "et la politique de la Fed.";
        }
        return null;
    }

     /**
     * 🎯 Détecte les justifications quasi identiques copiées entre plusieurs actifs
     * (viole la RÈGLE JUSTIFICATION existante : chaque actif doit avoir un mécanisme causal propre).
     * Basé sur un score de similarité simple (mots communs / mots totaux) entre les segments de justification.
     */
    public static List<String> verifierJustificationsDupliquees(String reportText) {
        List<String> duplications = new ArrayList<>();
        if (reportText == null) return duplications;
    
        Map<String, String> justifParActif = new java.util.LinkedHashMap<>();
        for (String ligneBrute : reportText.split("\n")) {
            String ligne = ligneBrute.trim();
            if (!ligne.startsWith("•")) continue;
            int pipeIdx = ligne.indexOf('|');
            if (pipeIdx < 0) continue;
            String justif = ligne.substring(pipeIdx + 1).trim();
    
            String ligneUpper = ligne.toUpperCase(java.util.Locale.ROOT);
            for (String actif : SIX_ACTIFS_OBLIGATOIRES) {
                if (ligneUpper.contains(actif)) {
                    justifParActif.put(actif, justif);
                    break;
                }
            }
        }
    
        List<String> actifs = new ArrayList<>(justifParActif.keySet());
        for (int i = 0; i < actifs.size(); i++) {
            for (int j = i + 1; j < actifs.size(); j++) {
                String a = justifParActif.get(actifs.get(i)).toLowerCase(java.util.Locale.ROOT);
                String b = justifParActif.get(actifs.get(j)).toLowerCase(java.util.Locale.ROOT);
                double similarite = similariteMots(a, b);
                if (similarite >= 0.70) { // 70%+ de mots communs = quasi copié-collé
                    duplications.add(actifs.get(i) + " et " + actifs.get(j) +
                            " ont des justifications quasi identiques (similarité " +
                            Math.round(similarite * 100) + "%) — mécanisme non différencié par actif");
                }
            }
        }
        return duplications;
    }

/**
 * 🎯 Vérifie la cohérence entre VECTEUR CIBLE (HAWKISH_US/DOVISH_US) et le sens réel de la surprise
 * chiffrée (actual très inférieur/supérieur au forecast) — évite un NFP raté classé HAWKISH par erreur.
 */
public static String verifierCoherenceVecteurSurprise(String reportText) {
    if (reportText == null) return null;
    String reportUpper = reportText.toUpperCase(java.util.Locale.ROOT);
    boolean estHawkishUS = reportUpper.contains("VECTEUR CIBLE : HAWKISH_US") || reportUpper.contains("VECTEUR CIBLE: HAWKISH_US");
    boolean estDovishUS  = reportUpper.contains("VECTEUR CIBLE : DOVISH_US")  || reportUpper.contains("VECTEUR CIBLE: DOVISH_US");
    if (!estHawkishUS && !estDovishUS) return null;

    String reportLower = reportLower0(reportText);
    boolean texteDitDecevant = reportLower.contains("décevant") || reportLower.contains("pire que le consensus")
            || reportLower.contains("inférieur") || reportLower.contains("manqué") || reportLower.contains("gelé")
            || reportLower.contains("sur la touche");
    boolean texteDitSolide = reportLower.contains("solide") || reportLower.contains("dépassé les attentes")
            || reportLower.contains("supérieur au consensus") || reportLower.contains("robuste");

    if (estHawkishUS && texteDitDecevant && !texteDitSolide) {
        return "VECTEUR CIBLE: HAWKISH_US déclaré, mais le texte décrit une donnée DÉCEVANTE/manquée (Fed 'sur la touche') " +
               "— une donnée faible est typiquement DOVISH_US, pas HAWKISH_US. Vecteur probablement inversé.";
    }
    if (estDovishUS && texteDitSolide && !texteDitDecevant) {
        return "VECTEUR CIBLE: DOVISH_US déclaré, mais le texte décrit une donnée SOLIDE/robuste " +
               "— une donnée forte est typiquement HAWKISH_US, pas DOVISH_US. Vecteur probablement inversé.";
    }
    return null;
 }
    
    private static double similariteMots(String a, String b) {
        java.util.Set<String> motsA = new java.util.HashSet<>(java.util.Arrays.asList(a.split("\\s+")));
        java.util.Set<String> motsB = new java.util.HashSet<>(java.util.Arrays.asList(b.split("\\s+")));
        if (motsA.isEmpty() || motsB.isEmpty()) return 0.0;
        java.util.Set<String> intersection = new java.util.HashSet<>(motsA);
        intersection.retainAll(motsB);
        java.util.Set<String> union = new java.util.HashSet<>(motsA);
        union.addAll(motsB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    private static final String[] MOTS_CLES_ACTIFS_LEGITIMES = {
        // Fed / USD / taux
        "fed", "fomc", "powell", "warsh", "taux d'intérêt", "taux directeur", "cpi", "pce", "nfp", "gdp", "chômage",
        // Géopolitique énergie/marché réellement transmissible
        "hormuz", "ormuz", "iran", "israel", "hezbollah", "houthi", "opep", "opec", "pétrole", "gaz naturel",
        "guerre", "frappe", "missile", "drone", "sanction", "embargo",
        // Banques centrales majeures
        "ecb", "bce", "boj", "banque du japon", "boe", "bank of england",
        // Data macro classique
        "pmi", "ism", "retail sales", "eia", "stimulus", "tarif douanier", "tariff",
        // Marchés/entreprises à impact large
        "nasdaq", "s&p", "sp500", "wall street", "bitcoin", "crypto", "ia", "intelligence artificielle",
        "nvidia", "apple", "microsoft", "spacex", "ipo"
    };
    
    /**
     * 🎯 Filtre de matérialité : rejette les news qui n'ont aucun lien plausible avec les 6 actifs
     * (ex : appel humanitaire suite à un séisme, sans lien économique/financier direct).
     * Retourne true si la news mérite une analyse fondamentale, false si elle doit être ignorée.
     */
    public static boolean estMaterielPourMarche(String title, String body) {
        if (title == null) title = "";
        if (body == null) body = "";
        String texte = (title + " " + body).toLowerCase(java.util.Locale.ROOT);
    
        for (String mot : MOTS_CLES_ACTIFS_LEGITIMES) {
            if (texte.contains(mot)) return true;
        }
    
        // ⚠️ Mots signalant explicitement un contexte humanitaire/catastrophe SANS lien marché direct
        String[] motsHorsSujet = {
            "programme alimentaire", "world food programme", "aide humanitaire", "assistance alimentaire",
            "tremblement de terre", "séisme", "catastrophe naturelle", "ong", "réfugiés", "famine"
        };
        for (String mot : motsHorsSujet) {
            if (texte.contains(mot)) return false; // Rejet explicite même si un mot légitime apparaît ailleurs par hasard
        }
    
        // Ni mot-clé légitime ni mot-clé hors-sujet détecté : par prudence, on laisse passer mais avec confiance dégradée
        return false;
    }
    // 🔄 ANTI-AMNÉSIE : Rechargement de la RAM depuis SQLite au démarrage du service
    // 🔄 ANTI-AMNÉSIE : Rechargement de la RAM depuis SQLite au démarrage du service
    public static void hydrateFromDatabase(Context context) {
        if (context == null) return;
        
        // 🌍 Étape A : Restaurer le régime géopolitique de guerre
        hydrateWarRegime(context);
        hydrateSourceReliability(context);
        
        try {
            android.database.sqlite.SQLiteDatabase db = EventDatabase.getInstance(context).getReadableDatabase();
            long clearWindow = System.currentTimeMillis() - 6 * 60 * 60 * 1000L; // Fenêtre de rétention de 6 heures
            
            android.database.Cursor cursor = db.query(EventDatabase.TABLE_EVENTS, 
                    new String[]{"title", "unix_timestamp"}, "unix_timestamp >= ?", 
                    new String[]{String.valueOf(clearWindow)}, null, null, null);
            
            if (cursor != null) {
                recentFingerprints.clear(); // Évite les cumuls d'instances obsolètes
                while (cursor.moveToNext()) {
                    String titleKey = cursor.getString(0);
                    long timestampVal = cursor.getLong(1);
                    if (titleKey != null) {
                        recentFingerprints.put(titleKey, timestampVal);
                    }
                }
                cursor.close();
            }
            Log.d(TAG, "🤖 [RAM HYDRATION] " + recentFingerprints.size() + " empreintes restaurées avec succès.");
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Échec de l'hydratation de la RAM au démarrage", e);
        }
    }
    // À ajouter au niveau des variables globales de la classe :
    private static long lastSuccessfulFetchTime = 0;
    private static final long COOLDOWN_MS = 15 * 60 * 1000; // 15 minutes de sécurité anti-429
    private static final long DUPLICATE_WINDOW_MS = 30 * 60 * 1000L; // 30 minutes
    private static final String TAG = "EventValidator";
    // ✅ Hash du dernier rapport envoyé — évite les doublons
    private static String lastCalendarHash = "";
    private static final Map<String, Long> lastAlertsSent = new ConcurrentHashMap<>();
    private static Context appContext = null;

    // ✅ AJOUT ARBITRAGE : Configuration Persistante du Régime de Guerre avec Sécurité TTL & Multi-Thread
    private static final String PREFS_NAME = "BotWarRegimePrefs";
    private static final String KEY_WAR_ACTIVE = "war_regime_active";
    private static final String KEY_WAR_TIMESTAMP = "war_activation_timestamp";
    private static final String KEY_WAR_FIRST_TRIGGER = "war_first_trigger_timestamp"; // ✅ Nouveau : début réel du choc
    
    private static volatile boolean isWarRegimeActive = false;
    private static volatile long lastWarShockTimestamp = 0;
    private static volatile long firstWarShockTimestamp = 0; // ✅ Ne se réinitialise QUE quand le régime repasse à false
    private static final long WAR_REGIME_TTL_MS = 36 * 60 * 60 * 1000L; // 36 Heures de validité automatique avant extinction
    
    // ✅ Seuils de classification de la durée du choc géopolitique
    private static final long SEUIL_SURSAUT_MS = 60 * 60 * 1000L;          // < 1h  : réaction algo/headline
    private static final long SEUIL_CHOC_ACTIF_MS = 24 * 60 * 60 * 1000L;  // 1h-24h : choc confirmé toujours actif
    // >= 24h : TENDANCE_INSTALLÉE

    /**
     * 🔄 Hydrate le régime de guerre depuis le stockage local (SharedPreferences) au réveil du bot
     */
   public static void hydrateWarRegime(Context context) {
        if (context == null) return;
        try {
            android.content.SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            synchronized (EventValidator.class) {
                isWarRegimeActive = prefs.getBoolean(KEY_WAR_ACTIVE, false);
                lastWarShockTimestamp = prefs.getLong(KEY_WAR_TIMESTAMP, 0);
                firstWarShockTimestamp = prefs.getLong(KEY_WAR_FIRST_TRIGGER, 0); // ✅
            }
            Log.d(TAG, "📦 [HYDRATATION GÉOPOLITIQUE] Régime de guerre restauré depuis le disque : " + isWarRegimeActive);
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Échec du rechargement du régime de guerre", e);
        }
    }
    
    /** 🕒 Classification de la phase du choc géopolitique, basée sur le PREMIER déclenchement confirmé */
    public static final String PHASE_SURSAUT = "SURSAUT";
    public static final String PHASE_CHOC_ACTIF = "CHOC_ACTIF";
    public static final String PHASE_TENDANCE_INSTALLEE = "TENDANCE_INSTALLEE";
    public static final String PHASE_INACTIF = "INACTIF";
    
    public static synchronized String getPhaseChocGeo(Context context) {
        if (!isWarRegimeActive(context) || firstWarShockTimestamp == 0) {
            return PHASE_INACTIF;
        }
        long dureeEcoulee = System.currentTimeMillis() - firstWarShockTimestamp;
        if (dureeEcoulee < SEUIL_SURSAUT_MS) return PHASE_SURSAUT;
        if (dureeEcoulee < SEUIL_CHOC_ACTIF_MS) return PHASE_CHOC_ACTIF;
        return PHASE_TENDANCE_INSTALLEE;
    }
    
    /** 📝 Texte prêt à injecter dans le prompt, avec la durée exacte en heures/minutes */
    public static synchronized String getPhaseChocGeoTexte(Context context) {
        String phase = getPhaseChocGeo(context);
        if (phase.equals(PHASE_INACTIF)) return "";
    
        long dureeMin = (System.currentTimeMillis() - firstWarShockTimestamp) / 60000L;
        long heures = dureeMin / 60;
        long minutes = dureeMin % 60;
        String dureeStr = heures > 0 ? heures + "h" + String.format(java.util.Locale.US, "%02d", minutes) : minutes + "min";
    
        switch (phase) {
            case PHASE_SURSAUT:
                return "⏱️ Choc géopolitique confirmé depuis " + dureeStr + " (phase SURSAUT) — réaction encore possiblement " +
                       "algorithmique/émotionnelle, risque de retournement ('sell the news') plus élevé, nuancer la conviction.";
            case PHASE_CHOC_ACTIF:
                return "⏱️ Choc géopolitique confirmé depuis " + dureeStr + " (phase CHOC_ACTIF) — la réaction initiale " +
                       "s'est stabilisée, traiter comme un régime confirmé mais pas encore une tendance de fond.";
            case PHASE_TENDANCE_INSTALLEE:
                return "⏱️ Choc géopolitique confirmé depuis " + dureeStr + " (phase TENDANCE_INSTALLÉE) — traiter comme " +
                       "une tendance de fond installée, pas un sursaut ponctuel.";
            default:
                return "";
        }
    }
 
    private static final String[] MOTS_TENDANCE_INSTALLEE = {"tendance installée", "tendance de fond", "installé depuis", "durable"};
    private static final String[] MOTS_SURSAUT = {"sursaut", "réaction immédiate", "réaction épidermique", "à chaud"};
    
    /**
     * 🎯 Vérifie que le rapport généré ne contredit pas la phase réelle calculée en Java
     * (ex: rapport parle de "tendance installée" alors que le choc dure depuis 12 minutes).
     * Retourne une anomalie (String) ou null si cohérent / non applicable.
     */
    public static synchronized String verifierPhaseChocGeo(Context context, String reportText) {
        String phase = getPhaseChocGeo(context);
        if (phase.equals(PHASE_INACTIF) || reportText == null) return null;
    
        String texteLower = reportText.toLowerCase(java.util.Locale.ROOT);
    
        if (phase.equals(PHASE_SURSAUT)) {
            for (String mot : MOTS_TENDANCE_INSTALLEE) {
                if (texteLower.contains(mot)) {
                    return "Rapport évoque une 'tendance installée' alors que Java calcule un SURSAUT (< 1h depuis le premier choc confirmé)";
                }
            }
        } else if (phase.equals(PHASE_TENDANCE_INSTALLEE)) {
            for (String mot : MOTS_SURSAUT) {
                if (texteLower.contains(mot)) {
                    return "Rapport évoque un 'sursaut/réaction immédiate' alors que Java calcule une TENDANCE_INSTALLÉE (≥24h)";
                }
            }
        }
        return null;
    }
    /**
     * Modifie l'état du régime de guerre (Sauvegarde persistante instantanée et Thread-Safe)
     */
   public static synchronized void setWarRegime(Context context, boolean active) {
        long maintenant = System.currentTimeMillis();
    
        if (active) {
            // ✅ On ne fixe le PREMIER déclenchement que s'il n'y en a pas déjà un en cours
            // (c-à-d régime inactif jusqu'ici, ou compteur jamais initialisé)
            if (!isWarRegimeActive || firstWarShockTimestamp == 0) {
                firstWarShockTimestamp = maintenant;
            }
            lastWarShockTimestamp = maintenant; // ✅ Toujours rafraîchi (comportement TTL inchangé)
        } else {
            lastWarShockTimestamp = 0;
            firstWarShockTimestamp = 0; // ✅ Reset uniquement à la vraie désactivation (ceasefire/TTL)
        }
        isWarRegimeActive = active;
    
        Context targetContext = (context != null) ? context : appContext;
        if (targetContext != null) {
            try {
                targetContext.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                       .edit()
                       .putBoolean(KEY_WAR_ACTIVE, isWarRegimeActive)
                       .putLong(KEY_WAR_TIMESTAMP, lastWarShockTimestamp)
                       .putLong(KEY_WAR_FIRST_TRIGGER, firstWarShockTimestamp) // ✅
                       .apply();
            } catch (Exception e) {
                Log.e(TAG, "⚠️ Échec de sauvegarde persistante du régime de guerre", e);
            }
        }
    }

    /**
     * ✅ Récupère l'état du régime de guerre avec évaluation et extinction automatique (TTL 36h)
     */
    public static synchronized boolean isWarRegimeActive(Context context) {
        if (isWarRegimeActive) {
            long tempsEcoule = System.currentTimeMillis() - lastWarShockTimestamp;
            if (tempsEcoule > WAR_REGIME_TTL_MS) {
                Log.w(TAG, "⏱️ [TTL EXPIRED] Aucune mise à jour géopolitique majeure depuis 36h. Désactivation automatique du régime de guerre.");
                
                isWarRegimeActive = false;
                lastWarShockTimestamp = 0;

                Context targetContext = (context != null) ? context : appContext;
                if (targetContext != null) {
                    try {
                        targetContext.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                               .edit()
                               .putBoolean(KEY_WAR_ACTIVE, false)
                               .putLong(KEY_WAR_TIMESTAMP, 0)
                               .apply();
                    } catch (Exception e) {
                        Log.e(TAG, "⚠️ Échec du nettoyage de l'expiration TTL sur le stockage", e);
                    }
                }
            }
        }
        return isWarRegimeActive;
    }

    /**
     * Surcharge du Getter pour les appels externes ne disposant pas d'un objet Context immédiat
     */
    public static boolean isWarRegimeActive() {
        return isWarRegimeActive(appContext);
    }

    // 🔹 PRÉCOMPILATION REGEX (Pour les performances du flux d'actualités)
    //private static final long WAR_REGIME_DURATION_MS = 12 * 60 * 60 * 1000L; // Verrou de 12 heures
    // 🔹 PRÉCOMPILATION REGEX (Pour les performances du flux d'actualités)
    private static final Pattern PERCENT_PATTERN = Pattern.compile("\\d+(\\.\\d+)?%");
    private static final Pattern BREAKING_PATTERN = Pattern.compile("\\b(breaking)\\b");
    private static final Pattern URGENT_ALERT_PATTERN = Pattern.compile("\\b(urgent|alert)\\b");
    
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
        public int     confidence     =0;
        public String  forecast       = "N/A";
        public String  previous       = "N/A";
        public String  actual         = "N/A";
        public boolean assetsEnriched = false;
        public String  reason         = "";
        public String  geoContext     = "";  // Description de la zone géopolitique détectée
        
        // Nouveaux champs pour l'inertie macro
        // APRÈS
        public boolean isInertiaBlock = false;
        public String  lastEventSummary = "";
        public boolean isCalendarIntercept = false; // ✅ Bypass explicite du seuil de poids dans NotificationService
        public String detectedTypeForInertia = "";
        public ValidationResult() {}

        public ValidationResult(boolean isConfirmed, int confidence, String reason) {
            this.isConfirmed = isConfirmed;
            this.confidence  = confidence;
            this.reason      = reason;        }
    }

    /**
     * Méthode principale de validation et d'enrichissement de la matrice d'actifs
     */
    /**
     * Méthode principale de validation et d'enrichissement de la matrice d'actifs
     */
    public static ValidationResult validate(Context context, String title, String content, long timestamp, List<String> detectedAssets) {
    ValidationResult result = new ValidationResult();

    if (title == null) title = "";
    if (content == null) content = "";
    if (detectedAssets == null) detectedAssets = new ArrayList<>();

    // ✅ ÉTAPE 0 : Sécurité de Source (Anti-Bruit Fichiers / Chrome)
    String testUpper = (title + " " + content).toUpperCase(Locale.ROOT);
    if (testUpper.contains(".JAVA") || testUpper.contains("PUBLIC CLASS") || testUpper.contains("IMPORT ANDROID.") || title.endsWith(".db")) {
        result.isConfirmed = false;
        result.reason = "Bruit système : Code source ou fichier local détecté";
        return result;
    }

    String combined = (title + " " + content).toLowerCase(Locale.ROOT);
    String upperCombined = testUpper; 
    String rawExtracted = upperCombined.replace(" :", ":").trim(); 

    // ✅ ÉTAPE 1 : Gestion de crise et désescalade événementielle
    // ✅ ÉTAPE 1 : Gestion de crise et désescalade événementielle
    GeoAssessment geo = assessGeopoliticalEvent(combined, upperCombined);
    
    if (geo != null && geo.confidence >= 60) {
        if (combined.contains("ceasefire") || combined.contains("cessez-le-feu") || combined.contains("peace") || combined.contains("pourparlers")) { 
            if (isWarRegimeActive(context)) { // ✅ Lecture sécurisée avec vérification du TTL
                setWarRegime(context, false); // 🕊️ Désactivation immédiate persistante
                logToMain("🕊️ [ARBITRAGE] Fin du Régime de Guerre détectée par assessGeopoliticalEvent.");
            }
            // Pas de return : on laisse couler pour capter le rebond macro économique
        } 
        else {
            setWarRegime(context, true); // 🚨 Activation / Reconduction persistante (remet à zéro le compteur des 36h)
            result.isConfirmed = true;
            result.confidence  = geo.confidence;
            result.reason      = "🚨 EXCEPTION ABSOLUE : RÉGIME DE GUERRE ACTIVÉ / RECONDUIT";
            result.geoContext  = geo.contextLabel;
    
            if (geo.impactedAssets != null) {
                for (String asset : geo.impactedAssets) {
                    if (asset != null && !detectedAssets.contains(asset)) {
                        detectedAssets.add(asset);
                    }
                }
            }
            result.assetsEnriched = !detectedAssets.isEmpty();
            logToMain("🌍 Géo prioritaire [" + geo.contextLabel + "] — Verrouillage de la matrice.");
            return result;
        }
    }

    // ✅ ÉTAPE 2 : Filtrage / Dérogation Macro sous Régime de Guerre
    if (isWarRegimeActive(context)) { // ✅ Lecture sécurisée avec vérification du TTL
        if (upperCombined.contains("DOVISH") || upperCombined.contains("HAWKISH") ||
            upperCombined.contains("FED") || upperCombined.contains("FOMC") || 
            upperCombined.contains("BANQUE CENTRALE") || upperCombined.contains("CENTRAL BANK") ||
            upperCombined.contains("CPI") || upperCombined.contains("PCE") || 
            upperCombined.contains("PPI") || upperCombined.contains("INFLATION") ||
            upperCombined.contains("RATE STATEMENT") || upperCombined.contains("INTEREST RATE")) {
            
            if (!detectedAssets.contains("USDJPY")) {
                detectedAssets.add("USDJPY");
            }
            result.geoContext = "[⚠️ ARBITRAGE MACRO EN GUERRE]"; 
            logToMain("⚠️ [ARBITRAGE] Flux macro (Inflation/Bancaire) autorisé à circuler malgré le Régime de Guerre.");
        }
    }

    // ── ⚡ INTERCEPTION & DÉROGATION ABSOLUE : CALENDRIER FINANCIALJUICE & MACRO CORE ──
    // CORRECTION : On accepte avec ou sans deux-points, ou si ce sont les Jobless Claims (indicateur US par défaut)
    if ((rawExtracted.contains("ACTUAL") && (rawExtracted.contains("FORECAST") || rawExtracted.contains("PREVIOUS"))) || 
        rawExtracted.contains("JOBLESS CLAIMS") || rawExtracted.contains("INITIAL CLAIMS")) {
        
        boolean assetsEnrichedInThisBlock = false;
    
        // ── BLOC 1 : RANG SUPRÊME / MACRO US ──
        // CORRECTION : Si le texte parle de JOBLESS ou CLAIMS, c'est obligatoirement de la macro US, même si "US" n'est pas écrit !
        if (rawExtracted.contains("US ") || rawExtracted.contains("USA ") || rawExtracted.contains("UNITED STATES") || 
            rawExtracted.contains("FOMC") || rawExtracted.contains("FED ") || rawExtracted.contains("POWELL") ||
            rawExtracted.contains("NFP") || rawExtracted.contains("PAYROLL") || rawExtracted.contains("TREASURY") ||
            rawExtracted.contains("USD") || rawExtracted.contains("DOLLAR") ||
            rawExtracted.contains("JOBLESS") || rawExtracted.contains("CLAIMS")) { 
            
            if (rawExtracted.contains("CPI") || rawExtracted.contains("PCE") || rawExtracted.contains("INFLATION") || 
                rawExtracted.contains("PPI") || rawExtracted.contains("RATE DECISION") || rawExtracted.contains("INTEREST RATE") ||
                rawExtracted.contains("TAUX") || rawExtracted.contains("PAYROLL") || rawExtracted.contains("NFP") || 
                rawExtracted.contains("UNEMPLOYMENT") || rawExtracted.contains("CHÔMAGE") || rawExtracted.contains("JOBLESS") || 
                rawExtracted.contains("CLAIMS") || rawExtracted.contains("GDP") || rawExtracted.contains("PIB") || 
                rawExtracted.contains("ISM") || rawExtracted.contains("PMI") || rawExtracted.contains("ADP") || 
                rawExtracted.contains("JOLTS") || rawExtracted.contains("RETAIL") || rawExtracted.contains("VENTES AU DÉTAIL") || 
                rawExtracted.contains("MICHIGAN") || rawExtracted.contains("CONSUMER CONFIDENCE") || 
                rawExtracted.contains("DURABLE GOODS") || rawExtracted.contains("INDUSTRIAL PRODUCTION") || 
                rawExtracted.contains("HOUSING") || rawExtracted.contains("BEIGE BOOK") || rawExtracted.contains("MINUTES")) {
                
                String[] usAssets = {
                    "GOLD", "NASDAQ", "SP500", "USOIL",
                    "USDJPY", "GBPUSD"
                };
                
                for(String a : usAssets) {
                    if (!detectedAssets.contains(a)) { 
                        detectedAssets.add(a); 
                        assetsEnrichedInThisBlock = true; 
                    }
                }
            }
        }
    
        // ── BLOC 3 : ROYAUME-UNI 🇬🇧 ──
        if (rawExtracted.contains("UK ") || rawExtracted.contains("UNITED KINGDOM") || rawExtracted.contains("BRITAIN") || 
            rawExtracted.contains("BOE") || rawExtracted.contains("BAILEY") || rawExtracted.contains("MPC")) {
            
            if (rawExtracted.contains("CPI") || rawExtracted.contains("INFLATION") || rawExtracted.contains("GDP") || 
                rawExtracted.contains("PIB") || rawExtracted.contains("PMI") || rawExtracted.contains("EARNINGS") || 
                rawExtracted.contains("WAGES") || rawExtracted.contains("SALAIRES") || rawExtracted.contains("UNEMPLOYMENT") || 
                rawExtracted.contains("CLAIMANT") || rawExtracted.contains("RATE") || rawExtracted.contains("BUDGET")) {
                if (!detectedAssets.contains("GBPUSD")) {
                    detectedAssets.add("GBPUSD");
                    assetsEnrichedInThisBlock = true;
                }
            }
        }
        
      
        // ── BLOC 6 : JAPON 🇯🇵 ──
        if (rawExtracted.contains("JAPAN") || rawExtracted.contains("JAPANESE") || rawExtracted.contains("TOKYO") || 
            rawExtracted.contains("BOJ") || rawExtracted.contains("UEDA") || rawExtracted.contains("MOF")) {
            
            if (rawExtracted.contains("CPI") || rawExtracted.contains("INFLATION") || rawExtracted.contains("RATE") || 
                rawExtracted.contains("TAUX") || rawExtracted.contains("YCC") || rawExtracted.contains("YIELD CURVE") || 
                rawExtracted.contains("TANKAN") || rawExtracted.contains("INTERVENTION") || rawExtracted.contains("YEN")) {
                if (!detectedAssets.contains("USDJPY")) { detectedAssets.add("USDJPY"); assetsEnrichedInThisBlock = true; }
            }
        }
        
        // ── BLOC 7 : SÉCURITÉ MATIÈRES PREMIÈRES & CRYPTO ──
        // ── BLOC 7 : SÉCURITÉ MATIÈRES PREMIÈRES ──
        if (rawExtracted.contains("EIA") || rawExtracted.contains("API") || rawExtracted.contains("OPEC") ||
            rawExtracted.contains("CRUDE") || rawExtracted.contains("OIL INVENTORIES") || rawExtracted.contains("NATURAL GAS") ||
            rawExtracted.contains("PÉTROLE") || rawExtracted.contains("STOCKS")) {
            if (!detectedAssets.contains("USOIL")) { detectedAssets.add("USOIL"); assetsEnrichedInThisBlock = true; }
        }
            
        // ── BLOC 8 : SÉCURITÉ RENDEMENTS & EARNINGS CORPORATE ──
        // ── BLOC 8 : SÉCURITÉ RENDEMENTS & EARNINGS CORPORATE ──
        if (rawExtracted.contains("REAL YIELDS") || rawExtracted.contains("REAL RATES") ||
            rawExtracted.contains("GOLD RESERVES") || rawExtracted.contains("TIPS")) {
            if (!detectedAssets.contains("GOLD")) { detectedAssets.add("GOLD"); assetsEnrichedInThisBlock = true; }
        }
        if (rawExtracted.contains("EARNINGS") || rawExtracted.contains("PROFIT WARNING") || rawExtracted.contains("GUIDANCE") || 
            rawExtracted.contains("EPS ") || rawExtracted.contains("REVENUE") || rawExtracted.contains("NVDA") || 
            rawExtracted.contains("AAPL") || rawExtracted.contains("MSFT") || rawExtracted.contains("AMZN")) {
            if (!detectedAssets.contains("NASDAQ")) { detectedAssets.add("NASDAQ"); assetsEnrichedInThisBlock = true; }
            if (!detectedAssets.contains("SP500")) { detectedAssets.add("SP500"); assetsEnrichedInThisBlock = true; }
        }
    
        // APRÈS
        // Validation finale forcée à 100% pour la macro pour bypasser l'Étape 8
        // APRÈS
        if ((assetsEnrichedInThisBlock || !detectedAssets.isEmpty()) && !isRecentDuplicate(title, content)) {
            result.confidence = 100;
            result.isConfirmed = true;
            result.isCalendarIntercept = true; // ✅ AJOUT : permet à NotificationService de bypasser son propre seuil
            result.reason = "Interception Complète Calendrier Macro (" + detectedAssets.toString() + ")";
            result.assetsEnriched = true; // ✅ CORRIGÉ (Du singulier d'après ta classe ValidationResult)
            Log.d("EventValidator", "🟢 [MACRO PRODUCTION INTERCEPT] Intégrité 100% validée pour : " + detectedAssets);
            return result;
        }
    }

    // ── ÉTAPE 3 : Anti-Doublons ─────────────
    if (isRecentDuplicate(title, content)) {
        result.confidence  = 0;
        result.isConfirmed = false;
        result.reason      = "Doublon récent détecté (30min)";
        result.assetsEnriched = !detectedAssets.isEmpty();
        logToMain("🔄 Doublon identifié (Enrichissement préservé)");
        return result;
    }

    // ── ÉTAPE 4 : Inertie Macro (Sécurisée contre les NPE) ─────
    var economyDetector = EconomicEventDetector.detectEvent(title, content);
    String detectedType = (economyDetector != null) ? economyDetector.eventType : "UNKNOWN";
    
    EventDatabase db = (context != null) ? EventDatabase.getInstance(context) : null;
    if ("FED-WARSH-SIGNAL".equals(detectedType)) {
        Log.i(TAG, "⚡ Signal Warsh détecté, normalisation vers FED-MONETARY-POLICY pour le routage.");
        detectedType = "FED-MONETARY-POLICY";
    }
    if (!detectedType.equals("UNKNOWN") && !detectedType.startsWith("GEO") && db != null) {
        try {
            long currentSeconds = timestamp / 1000;
            if (db.isDriverActiveRecently(detectedType, currentSeconds)) {
                result.isConfirmed = false;
                result.isInertiaBlock = true;
                result.detectedTypeForInertia = detectedType; // 🛡️ valeur réelle utilisée pour le blocage
                result.reason = "Driver déjà actif récemment (Inertie Macro)";
                result.assetsEnriched = !detectedAssets.isEmpty();
                result.lastEventSummary = db.getLastEventByType(detectedType);
                logToMain("⏳ Driver " + detectedType + " déjà actif — envoi d'un rappel");
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur inertie macro", e);
        }
    }

    // ── ÉTAPE 5 : Filtre anti-rumeur absolu ───────────────────────────
    if (containsRumorMarkers(combined)) {
        result.confidence = 0;
        result.isConfirmed = false;
        result.reason = "Rejeté — Marqueur de rumeur ou non-confirmé détecté";
        String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
        logToMain("❌ Rumeur/Non-confirmé rejeté – " + shortTitle + "…");
        return result;
    }

    // ── ÉTAPE 6 : Filtre éditorial ───────────────────────────────────
    if (containsEditorialContent(combined)) {
        result.confidence = 0;
        result.isConfirmed = false;
        result.reason = "Bruit macroéconomique (Opinion/Éditorial pur)";
        String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
        logToMain("❌ Rejeté – Contenu éditorial – " + shortTitle + "…");
        return result;
    }

    // ── ÉTAPE 7 : Sécurité Géopolitique Fallback ──
    if (geo != null && geo.confidence >= 60) {
        result.isConfirmed = true;
        result.confidence = geo.confidence;
        result.reason = "Événement géopolitique confirmé (Fallback)";
        result.geoContext = geo.contextLabel;
        if (geo.impactedAssets != null) {
            for (String asset : geo.impactedAssets) {
                if (asset != null && !detectedAssets.contains(asset)) {
                    detectedAssets.add(asset);
                }
            }
        }
        result.assetsEnriched = !detectedAssets.isEmpty();
        String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(40, title.length())) : "?";
        logToMain("🌍 Géo confirmé [" + geo.contextLabel + "] " + geo.confidence + "% – " + shortTitle + "…");
        return result;
    }

    // ── ÉTAPE 8 : Breaking News générique Fallback ──
    result.confidence = calculateBreakingNewsConfidence(title, content);
    result.reason = "Breaking News (Flux Interbancaire)";
    if (result.confidence < 70) {
        result.confidence = 0;
        result.isConfirmed = false;
        String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
        logToMain("📉 Confiance Breaking insuffisante (" + result.confidence + "%) – Rejeté : " + shortTitle + "…");
    } else {
        result.isConfirmed = true;
        result.assetsEnriched = !detectedAssets.isEmpty();
        String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
        logToMain("⚡ [BREAKING CONFIRMED] " + result.confidence + "% pour : " + shortTitle + "…");
    }

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
                geo.impactedAssets.addAll(Arrays.asList("USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "GBPUSD"));
            } else if (hasFactualAction) {
                geo.contextLabel = "Moyen-Orient - Action Militaire";
                score += 35;
                geo.impactedAssets.addAll(Arrays.asList("USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "GBPUSD"));
            } else if (isTrumpIran) {
                geo.contextLabel = "Moyen-Orient - Déclaration Trump/Iran";
                score += 18;   
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY", "NASDAQ", "SP500"));
            } else {
                geo.contextLabel = "Moyen-Orient / Pétrole";
                score += 22;
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY"));
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
           geo.impactedAssets.addAll(Arrays.asList("GBPUSD", "USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500"));
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
            geo.impactedAssets.addAll(Arrays.asList("USDJPY", "NASDAQ", "SP500", "GOLD", "USOIL")); 
            score += 20;
            geoZoneFound = true;
        }

        // 🔹 Exigence renforcée : une action militaire sans source crédible ni zone géographique précise ne dépasse pas 60
        if (!geoZoneFound && !hasCredibleSource && hasFactualAction) {
            score = Math.min(score, 55);
        }
        if (!geoZoneFound && !hasFactualAction) {
            score = Math.max(0, score - 25);
        }
        
        // ✅ Blindage final : assure que la confiance reste strictement entre 0 et 100
        geo.confidence = Math.max(0, Math.min(100, score));
       
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
     // ─────────────────────────────────────────────────────────────
    //  BREAKING NEWS GÉNÉRIQUE (Optimisé)
    // ─────────────────────────────────────────────────────────────
    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 40;
        
        // Sécurisation contre les NullPointerExceptions
        String safeTitle = title != null ? title : "";
        String safeContent = content != null ? content : "";
        
        // Tout en minuscules pour la vérification de la casse
        String lower = (safeTitle + " " + safeContent).toLowerCase(Locale.ROOT);

        // 🔹 Sources interbancaires et financières de confiance (prime unifiée)
        if (lower.contains("fxhedgers") || lower.contains("deltaone") || 
            lower.contains("reuters") || lower.contains("bloomberg") ||
            lower.contains("financial times") || lower.contains("wsj") ||
            lower.contains("wall street journal") || lower.contains("cnbc") ||
            lower.contains("financialjuice") || lower.contains("zerohedge")) {
            score += 30;
        }

        // 🔹 Mots déclencheurs d'alerte (Utilisation des regex sécurisées anti-faux-positifs)
        if (BREAKING_PATTERN.matcher(lower).find())     score += 25;
        if (URGENT_ALERT_PATTERN.matcher(lower).find()) score += 20;

        // 🔹 Mots-clés institutionnels
        if (lower.contains("federal reserve") || lower.contains("fomc")) score += 20;

        // 🔹 Chiffre avec pourcentage → donnée économique concrète
        if (PERCENT_PATTERN.matcher(lower).find()) {
            score += 20;
        }

        // 🔹 Format calendaire (Interception FinancialJuice)
        if (lower.contains("actual:") && lower.contains("forecast:")) {
            score += 25;
        }

        // Borner le résultat de façon sécurisée entre 0 et 100
        return Math.max(0, Math.min(100, score));
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
    // ─── COMPOSANTS DU MOTEUR DE CLÉ UNIQUE V3 ──────────────────────────────────
private static final String SEPARATEUR = "::";

private static final Map<String, String> SEMANTIC_MAP = new java.util.LinkedHashMap<>();
static {
    SEMANTIC_MAP.put("personal consumption expenditures", "pce");
    SEMANTIC_MAP.put("gross domestic product", "gdp");
    SEMANTIC_MAP.put("consumer price index", "cpi");
    SEMANTIC_MAP.put("producer price index", "ppi");
    SEMANTIC_MAP.put("purchasing managers index", "pmi");
    SEMANTIC_MAP.put("initial jobless claims", "ijc");
    SEMANTIC_MAP.put("unemployment rate", "unemprate");
    SEMANTIC_MAP.put("non farm payrolls", "nfp");
    SEMANTIC_MAP.put("nonfarm payrolls", "nfp");
    SEMANTIC_MAP.put("industrial production", "indprod");
    SEMANTIC_MAP.put("capacity utilization", "caputil");
    SEMANTIC_MAP.put("building permits", "buildperm");
    SEMANTIC_MAP.put("consumer confidence", "confsent");
    SEMANTIC_MAP.put("michigan sentiment", "michsent");
    SEMANTIC_MAP.put("ism manufacturing", "ismmfg");
    SEMANTIC_MAP.put("ism services", "ismsrv");
    SEMANTIC_MAP.put("fomc minutes", "fomcmin");
    SEMANTIC_MAP.put("trade balance", "tradebal");
    SEMANTIC_MAP.put("current account", "curracct");
    SEMANTIC_MAP.put("retail sales", "retail");
    SEMANTIC_MAP.put("housing starts", "houstarts");
    SEMANTIC_MAP.put("durable goods", "durables");
    SEMANTIC_MAP.put("beige book", "beige");
    SEMANTIC_MAP.put("philly fed", "philly");
    SEMANTIC_MAP.put("empire state", "empire");
    SEMANTIC_MAP.put("chicago pmi", "chicago");
}

private static final Pattern MATURITY_PATTERN =
        Pattern.compile("\\b(\\d+)\\s*(?:-?\\s*years?|-?\\s*yr|-?\\s*y)\\b", Pattern.CASE_INSENSITIVE);

public static String createEventKey(String source, String currency, String rawIndicator, String rawTimestamp) {
    String src = (source != null) ? source.trim().toLowerCase(Locale.ROOT) : "unknown";
    String cur = (currency != null) ? currency.trim().toUpperCase(Locale.ROOT) : "GLOBAL";

    String timestampSec = parseTimestampToSeconds(rawTimestamp);

    String indicatorSig = "unknown_indicator";
    if (rawIndicator != null && !rawIndicator.isEmpty()) {
        String processed = rawIndicator.toLowerCase(Locale.ROOT).trim();

        Matcher matcher = MATURITY_PATTERN.matcher(processed);
        processed = matcher.replaceAll("$1y");

        for (Map.Entry<String, String> entry : SEMANTIC_MAP.entrySet()) {
            if (processed.contains(entry.getKey())) {
                processed = processed.replace(entry.getKey(), entry.getValue());
            }
        }

        processed = processed.replaceAll("\\b(m/m|mom)\\b", "mom")
                             .replaceAll("\\b(y/y|yoy)\\b", "yoy")
                             .replaceAll("\\b(q/q|qoq)\\b", "qoq")
                             .replaceAll("\\b(w/w|wow)\\b", "wow");

        indicatorSig = Arrays.stream(processed.split("[\\s\\(\\)\\[\\]\\-,\\+\\&\\/]+"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .filter(token -> !token.matches("^(of|in|the|for|index)$"))
                .sorted()
                .collect(Collectors.joining("-"));
    }

    return src + SEPARATEUR + cur + SEPARATEUR + indicatorSig + SEPARATEUR + timestampSec;
}

private static String parseTimestampToSeconds(String rawTimestamp) {
    if (rawTimestamp == null || rawTimestamp.isEmpty()) return "0";
    String trimmed = rawTimestamp.trim();

    if (trimmed.matches("\\d+")) {
        try {
            long ts = Long.parseLong(trimmed);
            return String.valueOf(ts > 99999999999L ? ts / 1000 : ts);
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    try {
        return String.valueOf(java.time.OffsetDateTime.parse(trimmed).toEpochSecond());
    } catch (java.time.format.DateTimeParseException e1) {
        try {
            return String.valueOf(java.time.ZonedDateTime.parse(trimmed).toEpochSecond());
        } catch (java.time.format.DateTimeParseException e2) {
            try {
                return String.valueOf(java.time.LocalDateTime.parse(trimmed).toInstant(java.time.ZoneOffset.UTC).getEpochSecond());
            } catch (java.time.format.DateTimeParseException e3) {
                return trimmed.replaceAll("[^a-zA-Z0-9]", "_");
            }
        }
    }
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
    // 🟢 MODIFICATION : Vérification du Cooldown avant de solliciter le serveur
    long now = System.currentTimeMillis();
    if (now - lastSuccessfulFetchTime < COOLDOWN_MS) {
        logToMain("⏭️ [CALENDRIER] Requête ignorée (Protection anti-429). Cache conservé.");
        return;
    }

    try {
        List<EconomicCalendarAPI.CalendarEvent> events = EconomicCalendarAPI.fetchUpcomingEvents(168);
        if (events == null || events.isEmpty()) {
            logToMain("⚠️ Calendrier vide ou non disponible.");
            return;
        }

        // 🟢 MODIFICATION : Le réseau a répondu avec succès, on valide le timestamp
        lastSuccessfulFetchTime = System.currentTimeMillis();

        // ✅ WATCHER — Détecter les nouveaux résultats publiés depuis le dernier chargement
        List<EconomicCalendarAPI.CalendarEvent> newlyPublished = new ArrayList<>();
        for (EconomicCalendarAPI.CalendarEvent event : events) {
            if (event == null) continue;
            boolean hasActual = event.actual != null
                    && !event.actual.equals("N/A")
                    && !event.actual.isEmpty();
            if (hasActual) {
                // 🟢 MODIFICATION ICI : Utilisation de la clé V3 à 4 paramètres
                String key = createEventKey("api", event.country, event.indicator, event.timestamp);
                
                EconomicCalendarAPI.CalendarEvent previous = upcomingEvents.get(key);
                boolean isNew = (previous == null)
                        || (previous.actual == null
                        || previous.actual.equals("N/A")
                        || previous.actual.isEmpty());
                if (isNew) newlyPublished.add(event);
            }
        }

        // On vide la mémoire pour la rafraîchir
        upcomingEvents.clear();

        // ── Tri par timestamp croissant ──
        List<EconomicCalendarAPI.CalendarEvent> sortedEvents = new ArrayList<>(events);
        Collections.sort(sortedEvents, (a, b) -> {
            long tsA = parseTimestamp(a.timestamp);
            long tsB = parseTimestamp(b.timestamp);
            return Long.compare(tsA, tsB);
        });

        // ── BOUCLE 1 : Enrichissement via la DB locale & Remplissage de la Map ──
        for (EconomicCalendarAPI.CalendarEvent event : sortedEvents) {
            if (event == null || event.indicator == null || event.timestamp == null) continue;
        
            // 🟢 CORRECTIF : Injecter l'Actual de la DB si absent du flux Internet
            if (event.actual == null || event.actual.equals("N/A") || event.actual.isEmpty()) {
                String actualEnDB = EconomicCalendarAPI.getActualValueFromDB(appContext, event.indicator, event.timestamp);
                if (actualEnDB != null && !actualEnDB.equals("N/A") && !actualEnDB.isEmpty()) {
                    event.actual = actualEnDB; 
                }
            }
        
            // ✅ Filtrer les jours fériés globaux du stockage interne
            String indUpper = event.indicator.toUpperCase(Locale.ROOT);
            if (indUpper.contains("HOLIDAY") || indUpper.contains("DAY OFF") || indUpper.contains("ELECTION DAY")) {
                continue;
            }
        
            // ✅ Sauvegarde dans la Map interne (Rétabli)
            // 🟢 MODIFICATION ICI : Utilisation de la clé V3 à 4 paramètres
            String key = createEventKey("api", event.country, event.indicator, event.timestamp);
            
            upcomingEvents.put(key, event);
        }

        // ✅ Persistance en DB (Placée hors de la boucle pour préserver le CPU et le disque)
        if (appContext != null) {
            EconomicCalendarAPI.persistCalendarEventsToDB(appContext, sortedEvents);
        }

        // ── BOUCLE 2 : Construction du rapport Telegram ──
        StringBuilder report = new StringBuilder();
        report.append("📅 *CALENDRIER ÉCONOMIQUE — PROCHAINS ÉVÉNEMENTS*\n");
        report.append("─────────────────────────────────────────\n");

        String lastDay = "";
        int totalAffiche = 0;

        for (EconomicCalendarAPI.CalendarEvent event : sortedEvents) {
            if (event == null || event.indicator == null || event.timestamp == null) continue;

            // ✅ Filtrer les variantes de jours fériés pour l'affichage du rapport
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
        
        if (totalAffiche == 0) {
            logToMain("⏭️ [CALENDRIER] Aucun événement à venir — envoi ignoré.");
            return; // Ne calcule pas le hash et n'envoie rien
        }
        

        report.append("\n─────────────────────────────────────────\n");
        report.append("📊 *Total :* ").append(totalAffiche).append(" événements\n");

        // 🟢 MODIFICATION : On calcule le hash UNIQUEMENT sur les données stables (sans l'heure)
        String coreContent = report.toString();
        String newHash     = String.valueOf(coreContent.hashCode());

        // 🟢 MODIFICATION : On injecte l'horloge APRÈS le calcul du hash pour l'affichage visuel
        report.append("🕒 *Mis à jour :* ").append(getMadaTimeNow()).append(" (Mada)");
        String reportStr = report.toString();
        boolean hasNewResults = !newlyPublished.isEmpty();

if (!newHash.equals(lastCalendarHash) || hasNewResults) {
    lastCalendarHash = newHash;
    sendCalendarToTelegram(reportStr);
    if (hasNewResults) {
        logToMain("📤 [CALENDRIER] Rapport envoyé — nouveaux résultats publiés ("
            + newlyPublished.size() + ")");
    } else {
        logToMain("📤 [CALENDRIER] Rapport envoyé — contenu modifié");
    }
} else {
    logToMain("⏭️ [CALENDRIER] Rapport inchangé — envoi ignoré");
}

for (EconomicCalendarAPI.CalendarEvent event : newlyPublished) {
            analyzeAndSendCalendarResult(event);
        }
        
        // ✅ Affichage des logs de synchronisation précis
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

        String analyseInput = "Indicator: " + event.indicator + " | Actual: " + event.actual + " | Forecast: " + event.forecast;
        
        // ✅ Dérogation de l'analyse surprise à l'EconomicAnalyzer
        // Étape : Calcul de la déviation avec votre classe EconomicAnalyzer
        EconomicAnalyzer.EvaluationResult eval = EconomicAnalyzer.analyserEvenement(event.indicator, "Actual: " + event.actual + " | Forecast: " + event.forecast);
        
        // Étape : Construction du message dynamique
        String statusFleche = "⚪";
        if (eval.deviation > 0) statusFleche = "🟢 ↑"; // Haussier
        else if (eval.deviation < 0) statusFleche = "🔴 ↓"; // Baissier
        
        // Résultat final à intégrer dans le message Telegram
        String ligneCalendrier = String.format("%s | Prévu: %s | Réel: %s %s", 
                                               event.indicator, 
                                               event.forecast, 
                                               event.actual, 
                                               statusFleche);
        
        // ✅ Alignement strict avec les retours de votre classe EconomicAnalyzer
        if (eval != null && eval.isParsed) {
           if (eval.deviation > 0) {
            content += " | Status: HIGHER THAN EXPECTED 🟢";
           } else if (eval.deviation < 0) {
            content += " | Status: LOWER THAN EXPECTED 🔴";
           } else {
            content += " | Status: AS EXPECTED ⚪";
           } 
        }
        
        // ✅ Récupération des actifs liés
        List<String> assets = EconomicCalendarAPI.mapIndicatorToAssetsIntermarket(
                event.indicator,
                event.country != null ? event.country : "United States");
        
        // ✅ Envoi vers Groq + Telegram sécurisé (S'exécutera toujours sans lever d'exception)
        // ✅ Envoi vers Groq + Telegram sécurisé (S'exécutera toujours sans lever d'exception)
            NotificationService.sendToGroqAndTelegram(
                    "Calendrier Économique", title, content, assets, appContext);
            
            // 🔄 CORRECTIF : Génération et affichage du bloc compact professionnel (Option 2)
            String logCompact = genererLogCompact(event, eval);
            logToMain(logCompact);
            } catch (Exception e) {
                Log.e(TAG, "Erreur analyzeAndSendCalendarResult : " + event.indicator, e);
            }
        }
   
    // ==========================================
    // 📊 REFORMATAGE MACRO ERGONOMIQUE (OPTION 2)
    // ==========================================
    public static String genererLogCompact(EconomicCalendarAPI.CalendarEvent event, EconomicAnalyzer.EvaluationResult eval) {
        if (event == null) return "[MACRO] Événement null";

        StringBuilder sb = new StringBuilder();
        String statusIcon = "⚪";
        if (eval != null && eval.isParsed) {
            if (eval.deviation > 0) statusIcon = "🟢";
            if (eval.deviation < 0) statusIcon = "🔴";
        }
        
        String currency = (eval != null && eval.currency != null) ? eval.currency : "USD";
        String impactText = (eval != null && eval.marketImpact != null) ? eval.marketImpact.toUpperCase() : "NEUTRE";
        String deviationStr = "0.0%";
        
        if (eval != null && eval.isParsed) {
            deviationStr = (eval.deviation > 0 ? "+" : "") + String.format("%.2f", eval.deviation) + "%";
        }

        sb.append(String.format("[MACRO] %s %s • %s\n", statusIcon, currency, event.indicator));
        sb.append("─────────────────────────────────────────────\n");
        sb.append(String.format("• RÉSULTAT : %s (Prévu: %s | Écart: %s)\n", 
                (event.actual != null ? event.actual : "N/A"), 
                (event.forecast != null ? event.forecast : "N/A"), 
                deviationStr));
        sb.append(String.format("• BIAIS    : %s\n", impactText));
        sb.append("─────────────────────────────────────────────\n");
        sb.append("🎯 MATRICE DE TRADING :\n");
        
        String paysId = (event.country != null && !event.country.isEmpty()) ? event.country : "United States";
        List<String> assets = EconomicCalendarAPI.mapIndicatorToAssetsIntermarket(event.indicator, paysId);
        
        if (assets == null || assets.isEmpty()) {
            sb.append("  🔹 Aucun actif directement corrélé détecté.\n");
        } else {
            for (String asset : assets) {
                double dev = (eval != null) ? eval.deviation : 0.0;
                String signal = calculerSignalAsset(asset, currency, dev);
                sb.append(String.format("  🔹 %-8s ➔  %s\n", asset, signal));
            }
        }
        return sb.toString().trim();
    }

    private static String calculerSignalAsset(String asset, String currency, double deviation) {
        if (deviation == 0.0) return "WAIT  ⚪";
        boolean isPositiveShock = deviation > 0;
        String assetUpper = asset.toUpperCase(java.util.Locale.ROOT);
        String currencyUpper = currency.toUpperCase(java.util.Locale.ROOT);
        
        if (assetUpper.contains("10Y")) {
            if (currencyUpper.equals("USD")) return isPositiveShock ? "BUY  📈" : "SELL 📉";
            return "WAIT  ⚪";
        }
        if (assetUpper.contains("500") || assetUpper.contains("SPX") || assetUpper.contains("NAS") || assetUpper.contains("100")) {
            return isPositiveShock ? "SELL 📉" : "BUY  📈";
        }
        if (assetUpper.contains("GOLD") || assetUpper.startsWith("XAU")) {
            if (currencyUpper.equals("USD")) return isPositiveShock ? "SELL 📉" : "BUY  📈";
            return "WAIT  ⚪";
        }
        if (assetUpper.contains("OIL") || assetUpper.contains("PETROLE") || assetUpper.contains("WTI")) {
            return isPositiveShock ? "BUY  📈" : "SELL 📉";
        }
       if (assetUpper.length() == 6) {
            String baseCurrency = assetUpper.substring(0, 3);
            String quoteCurrency = assetUpper.substring(3, 6);
            if (baseCurrency.equals(currencyUpper)) return isPositiveShock ? "BUY  📈" : "SELL 📉";
            if (quoteCurrency.equals(currencyUpper)) return isPositiveShock ? "SELL 📉" : "BUY  📈";
        }
        return "WAIT  ⚪";
    }
    
}
