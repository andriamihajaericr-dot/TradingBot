package com.tradingbot.analyzer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.content.Intent;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    public static String CLAUDE_API_KEY   = "";
    public static String TELEGRAM_TOKEN   = "";
    public static String TELEGRAM_CHAT_ID = "";
    public static MainActivity instance;

    private TextView statusText, logText;
    private Switch botSwitch;
    private EditText apiKeyInput, telegramTokenInput, telegramChatIdInput;
    private EconomicEventDetector eventDetector;
    private Timer calendarCheckTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        statusText          = findViewById(R.id.statusText);
        botSwitch           = findViewById(R.id.botSwitch);
        logText             = findViewById(R.id.logText);
        apiKeyInput         = findViewById(R.id.apiKeyInput);
        telegramTokenInput  = findViewById(R.id.telegramTokenInput);
        telegramChatIdInput = findViewById(R.id.telegramChatIdInput);
        Button saveBtn      = findViewById(R.id.saveBtn);
        Button permBtn      = findViewById(R.id.permBtn);
        Button testBtn      = findViewById(R.id.testBtn);

        loadSavedKeys();
        updateStatus();

        saveBtn.setOnClickListener(v -> saveKeys());

        permBtn.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        );

        testBtn.setOnClickListener(v -> {
            addLog("🧪 Test en cours...");
            new Thread(() -> NotificationService.processNotification(
                this, "Test",
                "BREAKING: Federal Reserve raises interest rates by 50bps in emergency meeting. Gold surges."
            )).start();
        });

        botSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked && !isPermissionGranted()) {
                Toast.makeText(this, "Active d'abord la permission notifications !", Toast.LENGTH_LONG).show();
                botSwitch.setChecked(false);
                return;
            }
            getPrefs().edit().putBoolean("bot_active", isChecked).apply();
            updateStatus();
            addLog(isChecked ? "✅ Bot démarré" : "⛔ Bot arrêté");
        });

        botSwitch.setChecked(getPrefs().getBoolean("bot_active", false));

        // Initialiser le détecteur
        eventDb = new EventDatabase(this);
        eventDetector = new EconomicEventDetector(eventDb);
        
        // Lancer vérification périodique du calendrier
        startCalendarMonitoring();
    }
     
    private void startCalendarMonitoring() {
        calendarCheckTimer = new Timer();
    
        // Vérifier événements à venir toutes les 30 minutes
        calendarCheckTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                eventDetector.checkUpcomingEvents();
            }
        }, 0, 30 * 60 * 1000); // 0 = immédiat, 30min intervalle
    
        // Vérifier événements récents toutes les 5 minutes
        calendarCheckTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                eventDetector.checkRecentEvents();
            }
        }, 60 * 1000, 5 * 60 * 1000); // 1min délai, 5min intervalle
    
        addLog("[MAIN] Monitoring calendrier démarré");
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
    
        if (calendarCheckTimer != null) {
            calendarCheckTimer.cancel();
        }
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
            statusText.setText("⚠️ Permission notifications requise");
        } else if (active) {
            statusText.setText("🟢 Bot actif — En écoute...");
        } else {
            statusText.setText("🔴 Bot inactif — Appuie sur le switch");
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
        
        getPrefs().edit()
                .putString("claude_key", k)
                .putString("tg_token", t)
                .putString("tg_chat_id", c)
                .apply();
        
        CLAUDE_API_KEY = k;
        TELEGRAM_TOKEN = t;
        TELEGRAM_CHAT_ID = c;
        
        Toast.makeText(this, "✅ Clés sauvegardées !", Toast.LENGTH_SHORT).show();
        addLog("✅ Clés API configurées");
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

    public void addLog(String message) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String cur = logText.getText().toString();
            logText.setText("[" + ts + "] " + message + "\n" + cur);
        });
    }
}
