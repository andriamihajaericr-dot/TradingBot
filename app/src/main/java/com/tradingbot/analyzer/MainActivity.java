package com.tradingbot.analyzer;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    public static String GROQ_API_KEY     = "";
    public static String MACRO_API_KEY    = "";
    public static String TELEGRAM_TOKEN   = "";
    public static String TELEGRAM_CHAT_ID = "";
    public static MainActivity instance;

    private TextView statusText, logText;
    private Switch botSwitch;
    private EditText apiKeyInput, macroApiKeyInput, telegramTokenInput, telegramChatIdInput;
    private ScrollView mainScrollView;
    private EventDatabase eventDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        eventDb = EventDatabase.getInstance(this);

        // Liaison des composants
        mainScrollView      = findViewById(R.id.mainScrollView);
        statusText          = findViewById(R.id.statusText);
        botSwitch           = findViewById(R.id.botSwitch);
        logText             = findViewById(R.id.logText);
        apiKeyInput         = findViewById(R.id.apiKeyInput);
        macroApiKeyInput    = findViewById(R.id.macroApiKeyInput);
        telegramTokenInput  = findViewById(R.id.telegramTokenInput);
        telegramChatIdInput = findViewById(R.id.telegramChatIdInput);

        Button saveBtn = findViewById(R.id.saveBtn);
        Button permBtn = findViewById(R.id.permBtn);
        Button testBtn = findViewById(R.id.testBtn);
        Button exportBtn = findViewById(R.id.exportBtn);   // Pour exporter la base
        Button importBtn = findViewById(R.id.importBtn);

        loadSavedKeys();
        updateStatus();

        saveBtn.setOnClickListener(v -> saveKeys());
        permBtn.setOnClickListener(v -> {
            addLog("[SYSTEM] Redirection vers les autorisations Android.");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });
        testBtn.setOnClickListener(v -> {
            addLog("🧪 [TEST] Déclenchement d'une simulation réseau...");
            try {
                NotificationService.sendTelegramSecure(
                    "🧪 **TEST DU CAPTEUR DE TRADING**\n" +
                    "Statut : Opérationnel\n" +
                    "Zone Temporelle : UTC+3 (Madagascar)\n" +
                    "Flux : Liaison montante et descendante OK.",
                    this
                );
            } catch (Exception e) {
                Log.e(TAG, "Échec lors de l'envoi du test", e);
                addLog("❌ [TEST] Erreur lors de l'appel : " + e.getMessage());
            }
        });
        exportBtn.setOnClickListener(v -> exportDatabaseToStorage());
        importBtn.setOnClickListener(v -> importDatabaseFromStorage());

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

        botSwitch.setChecked(getPrefs().getBoolean("bot_active", false));
        addLog("📱 Terminal prêt pour l'acquisition.");
        eventDb.diagnostiquerTableEvents();
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
            statusText.setTextColor(0xFFFF9800);
        } else if (active) {
            statusText.setText("🟢 BOT ACTIF — EN ÉCOUTE DES DRIVERS...");
            statusText.setTextColor(0xFF00FF00);
        } else {
            statusText.setText("🔴 BOT INACTIF — COUPE FLUX ENGAGÉ");
            statusText.setTextColor(0xFFFF0000);
        }
    }

    private void saveKeys() {
        String k = apiKeyInput.getText().toString().trim();
        String m = macroApiKeyInput.getText().toString().trim();
        String t = telegramTokenInput.getText().toString().trim();
        String c = telegramChatIdInput.getText().toString().trim();

        if (k.isEmpty() || m.isEmpty() || t.isEmpty() || c.isEmpty()) {
            Toast.makeText(this, "Remplis toutes les clés !", Toast.LENGTH_SHORT).show();
            return;
        }

        getPrefs().edit()
            .putString("groq_key", k)
            .putString("macro_api_key", m)
            .putString("tg_token", t)
            .putString("tg_chat_id", c)
            .apply();

        GROQ_API_KEY   = k;
        MACRO_API_KEY  = m;
        TELEGRAM_TOKEN = t;
        TELEGRAM_CHAT_ID = c;

        Toast.makeText(this, "✅ Clés sauvegardées !", Toast.LENGTH_SHORT).show();
        addLog("✅ Configuration des clés API mise à jour.");
    }

    private void loadSavedKeys() {
        SharedPreferences p = getPrefs();
        GROQ_API_KEY     = p.getString("groq_key", "");
        MACRO_API_KEY    = p.getString("macro_api_key", "");
        TELEGRAM_TOKEN   = p.getString("tg_token", "");
        TELEGRAM_CHAT_ID = p.getString("tg_chat_id", "");

        apiKeyInput.setText(GROQ_API_KEY);
        macroApiKeyInput.setText(MACRO_API_KEY);
        telegramTokenInput.setText(TELEGRAM_TOKEN);
        telegramChatIdInput.setText(TELEGRAM_CHAT_ID);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("TradingBot", MODE_PRIVATE);
    }

    public void addLog(String message) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String currentText = logText.getText().toString();
            if (currentText.contains("En attente de notifications...")) {
                currentText = "";
            }
            if (currentText.length() > 5000) {
                currentText = currentText.substring(0, 2000);
            }
            logText.setText(currentText + "[" + ts + "] " + message + "\n");
            if (mainScrollView != null) {
                mainScrollView.post(() -> mainScrollView.fullScroll(View.FOCUS_DOWN));
            }
            writeLogToFile(message);
        });
    }

    private void writeLogToFile(String message) {
        try {
            File logFile = new File(getExternalFilesDir(null), "Fonda_IOF_bot_logs.txt");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String line = "[" + timestamp + "] " + message + "\n";
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(line);
            fw.close();
        } catch (IOException e) {
            Log.e(TAG, "Erreur écriture log fichier", e);
        }
    }

    private void exportLogs() {
        File logFile = new File(getExternalFilesDir(null), "Fonda_IOF_bot_logs.txt");
        if (!logFile.exists()) {
            Toast.makeText(this, "Aucun log trouvé", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
            this,
            getPackageName() + ".fileprovider",
            logFile
        ));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Partager les logs"));
    }

    private void importDatabaseFromStorage() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1002);
                return;
            }
        }

        File importDir = new File(Environment.getExternalStorageDirectory(), "Documents/TradingBotBackup");
        File[] backups = importDir.listFiles((dir, name) -> name.endsWith(".db"));

        if (backups == null || backups.length == 0) {
            Toast.makeText(this, "Aucune sauvegarde trouvée dans Documents/TradingBotBackup", Toast.LENGTH_LONG).show();
            return;
        }

        Arrays.sort(backups, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        File latestBackup = backups[0];

        try {
            File currentDb = getDatabasePath("trading_bot.db");
            if (eventDb != null) {
                eventDb.close();
                eventDb = null;
            }
            FileInputStream fis = new FileInputStream(latestBackup);
            FileOutputStream fos = new FileOutputStream(currentDb);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
            fos.close();
            fis.close();

            eventDb = EventDatabase.getInstance(this);
            Toast.makeText(this, "Base restaurée depuis " + latestBackup.getName(), Toast.LENGTH_LONG).show();
            addLog("✅ Base de données importée avec succès.");
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de l'importation", e);
            Toast.makeText(this, "Échec de l'importation : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportDatabaseToStorage() {
        try {
            File dbFile = getDatabasePath("trading_bot.db");
            if (dbFile == null || !dbFile.exists()) {
                Toast.makeText(this, "Aucune base", Toast.LENGTH_SHORT).show();
                return;
            }

            File exportDir = new File(getExternalFilesDir(null), "TradingBotBackup");
            if (!exportDir.exists()) exportDir.mkdirs();

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File backupFile = new File(exportDir, "trading_bot_backup_" + timestamp + ".db");

            FileInputStream fis = new FileInputStream(dbFile);
            FileOutputStream fos = new FileOutputStream(backupFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.close();
            fis.close();

            Toast.makeText(this, "Base sauvegardée dans " + backupFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            addLog("✅ Base exportée : " + backupFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "Erreur exportation base", e);
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1002 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            importDatabaseFromStorage();
        } else if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            exportDatabaseToStorage();
        } else {
            Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ne pas fermer la base ici – partagée avec le service
    }
}
