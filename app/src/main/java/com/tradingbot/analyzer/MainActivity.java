package com.tradingbot.analyzer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    public static String CLAUDE_API_KEY   = "";
    public static String TELEGRAM_TOKEN   = "";
    public static String TELEGRAM_CHAT_ID = "";
    public static MainActivity instance;

    private TextView statusText, logText;
    private Switch botSwitch;
    private EditText apiKeyInput, telegramTokenInput, telegramChatIdInput;
    private ScrollView mainScrollView;
    private EventDatabase eventDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;
        
        // Initialisation de la base de données locale
        eventDb = new EventDatabase(this);

        // Liaison des composants avec le fichier XML activity_main
        mainScrollView = (ScrollView) ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        statusText          = findViewById(R.id.statusText);
        botSwitch           = findViewById(R.id.botSwitch);
        logText             = findViewById(R.id.logText);
        apiKeyInput         = findViewById(R.id.apiKeyInput);
        telegramTokenInput  = findViewById(R.id.telegramTokenInput);
        telegramChatIdInput = findViewById(R.id.telegramChatIdInput);
        
        Button saveBtn      = findViewById(R.id.saveBtn);
        Button permBtn      = findViewById(R.id.permBtn);
        Button testBtn      = findViewById(R.id.testBtn);

        // Chargement initial des configurations enregistrées
        loadSavedKeys();
        updateStatus();

        // 1. Bouton Sauvegarder
        saveBtn.setOnClickListener(v -> saveKeys());

        // 2. Bouton Demande de Permission
        permBtn.setOnClickListener(v -> {
            addLog("[SYSTEM] Redirection vers les autorisations Android.");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        // 3. Bouton Test de Transmission Telegram
        testBtn.setOnClickListener(v -> {
            addLog("🧪 [TEST] Déclenchement d'une simulation réseau...");
            try {
                NotificationService.sendTelegramSecure(
                    "🧪 **TEST DU CAPTEUR DE TRADING**\n" +
                    "Statut : Opérationnel\n" +
                    "Zone Temporelle : UTC+3 (Madagascar)\n" +
                    "Flux : Liaison montante et descendante OK."
                );
            } catch (Exception e) {
                Log.e(TAG, "Échec lors de l'envoi du test", e);
                addLog("❌ [TEST] Erreur lors de l'appel : " + e.getMessage());
            }
        });

        // 4. Interrupteur d'activation globale du Bot
        botSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked && !isPermissionGranted()) {
                Toast.makeText(this, "Active d'abord la permission notifications !", Toast.LENGTH_LONG).show();
                botSwitch.setChecked(false);
                return;
            }
            getPrefs().edit().putBoolean("bot_active", isChecked).apply();
            updateStatus();
            addLog(isChecked ? "🚀 MOTEUR MACRO ACTIVÉ (EN ÉCOUTE)" : "🛑 MOTEUR EN VEILLE (STANDBY)");
        });

        // Appliquer l'état sauvegardé du switch au démarrage
        botSwitch.setChecked(getPrefs().getBoolean("bot_active", false));
        addLog("📱 Terminal prêt pour l'acquisition.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        updateStatus();
    }

    private boolean isPermissionGranted() {
        Set<String> pkgs = NotificationManagerCompat.getEnabledListenerPackages(this);
        return pkgs.contains(getPackageName());
    }

    private void updateStatus() {
        boolean active = getPrefs().getBoolean("bot_active", false);
        boolean perm   = isPermissionGranted();
        if (!perm) {
            statusText.setText("⚠️ PERMISSION NOTIFICATIONS REQUISE");
            statusText.setTextColor(0xFFFF9800); // Orange
        } else if (active) {
            statusText.setText("🟢 BOT ACTIF — EN ÉCOUTE DES DRIVERS...");
            statusText.setTextColor(0xFF00FF00); // Vert
        } else {
            statusText.setText("🔴 BOT INACTIF — COUPE FLUX ENGAGÉ");
            statusText.setTextColor(0xFFFF0000); // Rouge
        }
    }

    private void saveKeys() {
        String k = apiKeyInput.getText().toString().trim();
        String t = telegramTokenInput.getText().toString().trim();
        String c = telegramChatIdInput.getText().toString().trim();
        if (k.isEmpty() || t.isEmpty() || c.isEmpty()) {
            Toast.makeText(this, "Remplis toutes les clés !", Toast.LENGTH_SHORT).show();
            return;
        }
        getPrefs().edit().putString("claude_key", k).putString("tg_token", t).putString("tg_chat_id", c).apply();
        CLAUDE_API_KEY = k; 
        TELEGRAM_TOKEN = t; 
        TELEGRAM_CHAT_ID = c;
        Toast.makeText(this, "✅ Clés sauvegardées !", Toast.LENGTH_SHORT).show();
        addLog("✅ Configuration des clés API mise à jour.");
    }

    private void loadSavedKeys() {
        SharedPreferences p = getPrefs();
        CLAUDE_API_KEY   = p.getString("claude_key", "");
        TELEGRAM_TOKEN   = p.getString("tg_token", "");
        TELEGRAM_CHAT_ID = p.getString("tg_chat_id", "");
        apiKeyInput.setText(CLAUDE_API_KEY);
        telegramTokenInput.setText(TELEGRAM_TOKEN);
        telegramChatIdInput.setText(TELEGRAM_CHAT_ID);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("TradingBot", MODE_PRIVATE);
    }

    /**
     * Imprime un message dans la console noire de l'application
     * et gère le défilement vers le bas automatique (Auto-Scroll)
     */
    public void addLog(String message) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String currentText = logText.getText().toString();
            
            // Nettoyage de l'état d'attente initial au démarrage
            if (currentText.contains("En attente de notifications...")) {
                currentText = "";
            }
            
            // Sécurité anti-saturation mémoire de l'affichage
            if (currentText.length() > 5000) {
                currentText = currentText.substring(0, 2000);
            }
            
            // Ajout du log à la suite
            logText.setText(currentText + "[" + ts + "] " + message + "\n");
            
            // Commande de défilement forcée sur le ScrollView
            if (mainScrollView != null) {
                mainScrollView.post(() -> mainScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (eventDb != null) {
            eventDb.close();
        }
    }
}
