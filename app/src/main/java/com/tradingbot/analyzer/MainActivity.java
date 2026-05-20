package com.tradingbot.analyzer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // Harmonisation des clés institutionnelles d'accès
    public static String CLAUDE_API_KEY   = ""; // Fait office de jeton d'accès pour l'API Groq Gateway
    public static String TELEGRAM_TOKEN   = "";
    public static String TELEGRAM_CHAT_ID = "";
    public static MainActivity instance;

    private TextView statusText, logText;
    private Switch botSwitch;
    private EditText apiKeyInput, telegramTokenInput, telegramChatIdInput;
    private EventDatabase eventDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;
        eventDb = new EventDatabase(this);

        statusText          = findViewById(R.id.statusText);
        botSwitch           = findViewById(R.id.botSwitch);
        logText             = findViewById(R.id.logText);
        apiKeyInput         = findViewById(R.id.apiKeyInput);
        telegramTokenInput  = findViewById(R.id.telegramTokenInput);
        telegramChatIdInput = findViewById(R.id.telegramChatIdInput);
        
        Button saveBtn      = findViewById(R.id.saveBtn);
        Button permBtn      = findViewById(R.id.permBtn);
        Button testBtn      = findViewById(R.id.testBtn);
        
        // Ajout d'un bouton de maintenance dans votre layout R.layout.activity_main
        // Si l'ID n'existe pas dans votre ancien fichier XML, il suffit de le lier ou de le commenter.
        Button clearDbBtn   = findViewById(R.id.clearDbBtn); 

        loadSavedKeys();
        checkNotificationPermission();

        botSwitch.setChecked(getPrefs().getBoolean("bot_active", false));
        updateStatusText(botSwitch.isChecked());

        botSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getPrefs().edit().putBoolean("bot_active", isChecked).apply();
            updateStatusText(isChecked);
            addLog(isChecked ? "🚀 MOTEUR MACROÉCONOMIQUE ACTIVÉ" : "🛑 MOTEUR EN VEILLE");
        });

        saveBtn.setOnClickListener(v -> saveApiKeys());

        permBtn.setOnClickListener(v -> {
            addLog("[SYSTEM] Demande d'accès aux notifications système.");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        testBtn.setOnClickListener(v -> {
            addLog("[TEST] Déclenchement d'une simulation de flux synthétique...");
            NotificationService.sendTelegramSecure("🧪 **TEST LIAISON INFRASTRUCTURE**\nStatut : Opérationnel.\nModèle : Llama-3.3-70B\nDestination : Flux Sécurisé.");
        });

        if (clearDbBtn != null) {
            clearDbBtn.setOnClickListener(v -> {
                eventDb.cleanOldEvents(); // Purge immédiate forcée des enregistrements obsolètes
                SystemMonitor.resetDailyCounters();
                addLog("🧹 MAINTENANCE : Base de données locale et compteurs réinitialisés.");
                Toast.makeText(MainActivity.this, "Base de données nettoyée", Toast.LENGTH_SHORT).show();
            });
        }

        addLog("📱 Interface Terminal Connectée.");
    }

    private void updateStatusText(boolean active) {
        if (active) {
            statusText.setText("STATUT : BOT OPÉRATIONNEL (RUNNING)");
            statusText.setTextColor(0xFF00FF00); // Vert Finance
        } else {
            statusText.setText("STATUT : ENGINE ARRÊTÉ (STANDBY)");
            statusText.setTextColor(0xFFFF0000); // Rouge Alerte
        }
    }

    private void checkNotificationPermission() {
        Set<String> packages = NotificationManagerCompat.getEnabledListenerPackages(this);
        if (!packages.contains(getPackageName())) {
            addLog("⚠️ ERREUR CRITIQUE : Autorisation d'accès aux notifications manquante.");
        } else {
            addLog("✅ Autorisation d'accès aux notifications confirmée.");
        }
    }

    private void saveApiKeys() {
        String k = apiKeyInput.getText().toString().trim();
        String t = telegramTokenInput.getText().toString().trim();
        String c = telegramChatIdInput.getText().toString().trim();
        
        if (k.isEmpty() || t.isEmpty() || c.isEmpty()) {
            Toast.makeText(this, "Remplis toutes les clés d'accès !", Toast.LENGTH_SHORT).show();
            return;
        }
        
        getPrefs().edit()
            .putString("claude_key", k)
            .putString("tg_token", t)
            .putString("tg_chat_id", c)
            .apply();
            
        CLAUDE_API_KEY = k; 
        TELEGRAM_TOKEN = t; 
        TELEGRAM_CHAT_ID = c;
        
        Toast.makeText(this, "Configuration Sauvegardée !", Toast.LENGTH_SHORT).show();
        addLog("✅ Variables de connexion mises à jour.");
    }

    private void loadSavedKeys() {
        SharedPreferences p = getPrefs();
        String storedKey = p.getString("claude_key", "");
        
        // Fallback rétrocompatible au cas où vous utiliseriez une clé brute dans votre code d'origine
        CLAUDE_API_KEY   = storedKey.isEmpty() ? "" : storedKey;
        TELEGRAM_TOKEN   = p.getString("tg_token", "");
        TELEGRAM_CHAT_ID = p.getString("tg_chat_id", "");
        
        apiKeyInput.setText(CLAUDE_API_KEY);
        telegramTokenInput.setText(TELEGRAM_TOKEN);
        telegramChatIdInput.setText(TELEGRAM_CHAT_ID);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("TradingBot", MODE_PRIVATE);
    }

    public void addLog(String message) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logText.append("[" + ts + "] " + message + "\n");
            
            // Auto-scroll automatique de l'interface pour suivre le flux en temps réel
            int scrollAmount = logText.getLayout() != null ? 
                logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight() : 0;
            if (scrollAmount > 0) logText.scrollTo(0, scrollAmount);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (eventDb != null) eventDb.close();
    }
}
