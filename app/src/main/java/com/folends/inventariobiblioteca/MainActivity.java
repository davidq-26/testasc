package com.folends.inventariobiblioteca;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 42;
    private static final int REQ_EXPORT = 77;

    private final String[] areaIds = {"jefatura-reserva", "consulta-tesis", "primer-nivel"};
    private final String[] areaNames = {
            "Jefatura de Biblioteca y Colección de Reserva",
            "Consulta y Tesis",
            "Primer Nivel"
    };

    private String activeAreaId = areaIds[0];
    private String activeAreaName = areaNames[0];
    private int activeBattery = 1;
    private int activeShelf = 1;
    private int activeTray = 1;
    private String librarian = "";
    private boolean noDuplicates = true;

    private final List<Record> records = new ArrayList<>();
    private LinearLayout root;
    private LinearLayout areaList;
    private EditText librarianInput;
    private TextView sessionLabel;
    private TextView selectedLabel;
    private TextView statsLabel;
    private GridLayout mapGrid;
    private LinearLayout trayList;
    private LinearLayout recordList;
    private TextView scannerLocation;
    private TextView scannerStatus;
    private FrameLayout scannerOverlay;
    private PreviewView previewView;
    private ScanFrameView scanFrameView;
    private EditText manualInput;

    private ProcessCameraProvider cameraProvider;
    private BarcodeScanner scanner;
    private ExecutorService cameraExecutor;
    private ToneGenerator tone;
    private long lastScanTime = 0L;
    private String lastScanCode = "";
    private Uri pendingExportUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.parseColor("#0F172A"));
        getWindow().setNavigationBarColor(Color.parseColor("#0F172A"));

        cameraExecutor = Executors.newSingleThreadExecutor();
        scanner = BarcodeScanning.getClient();
        tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);

        buildUi();
        loadState();
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (scanner != null) scanner.close();
        if (tone != null) tone.release();
    }

    private void buildUi() {
        FrameLayout appFrame = new FrameLayout(this);
        setContentView(appFrame);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#F4F7FB"));
        appFrame.addView(scroll, matchParent());

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(root, matchParentWrap());

        LinearLayout header = card();
        header.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView title = text("Inventario físico de biblioteca", 22, true, "#0F172A");
        TextView subtitle = text("Captura por área, batería, estante y charola · APK Android con cámara nativa", 13, false, "#64748B");
        header.addView(title);
        header.addView(subtitle);
        root.addView(header, marginBottom(matchWidthWrap(), 12));

        LinearLayout librarianCard = card();
        librarianCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        librarianCard.addView(label("Nombre del bibliotecario"));
        librarianInput = new EditText(this);
        librarianInput.setSingleLine(true);
        librarianInput.setHint("Nombre completo");
        librarianInput.setTextSize(16);
        librarianInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        librarianInput.setBackground(makeRoundBg("#FFFFFF", "#CBD5E1", 14));
        librarianInput.setPadding(dp(12), 0, dp(12), 0);
        librarianCard.addView(librarianInput, height(matchWidth(), 48));
        sessionLabel = text("", 12, true, "#64748B");
        sessionLabel.setPadding(0, dp(10), 0, 0);
        librarianCard.addView(sessionLabel);
        root.addView(librarianCard, marginBottom(matchWidthWrap(), 12));

        LinearLayout areasCard = card();
        areasCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        areasCard.addView(label("Áreas de biblioteca"));
        areaList = new LinearLayout(this);
        areaList.setOrientation(LinearLayout.VERTICAL);
        areasCard.addView(areaList);
        root.addView(areasCard, marginBottom(matchWidthWrap(), 12));

        LinearLayout mapCard = card();
        mapCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        mapCard.addView(text("Croquis operativo", 18, true, "#0F172A"));
        mapCard.addView(text("Batería 1: estantes 1–8. Batería 2: estantes 1–5. Toca un estante para capturar sus charolas.", 13, false, "#64748B"));
        mapGrid = new GridLayout(this);
        mapGrid.setColumnCount(3);
        mapGrid.setPadding(0, dp(12), 0, 0);
        mapCard.addView(mapGrid);
        root.addView(mapCard, marginBottom(matchWidthWrap(), 12));

        LinearLayout captureCard = card();
        captureCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        selectedLabel = text("", 16, true, "#0F172A");
        captureCard.addView(selectedLabel);
        Button scanButton = primaryButton("📷 Escanear código");
        scanButton.setOnClickListener(v -> openScannerOverlay());
        captureCard.addView(scanButton, marginTop(height(matchWidth(), 52), 12));
        trayList = new LinearLayout(this);
        trayList.setOrientation(LinearLayout.VERTICAL);
        captureCard.addView(trayList, marginTop(matchWidthWrap(), 12));
        root.addView(captureCard, marginBottom(matchWidthWrap(), 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button exportBtn = successButton("✅ Cerrar inventario / exportar CSV Excel");
        exportBtn.setOnClickListener(v -> exportCsv());
        actions.addView(exportBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions, marginBottom(matchWidthWrap(), 12));

        LinearLayout recordsCard = card();
        recordsCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        recordsCard.addView(text("Listado de códigos capturados", 18, true, "#0F172A"));
        statsLabel = text("", 13, true, "#64748B");
        recordsCard.addView(statsLabel);
        recordList = new LinearLayout(this);
        recordList.setOrientation(LinearLayout.VERTICAL);
        recordsCard.addView(recordList, marginTop(matchWidthWrap(), 10));
        root.addView(recordsCard, matchWidthWrap());

        buildScannerOverlay(appFrame);
    }

    private void buildScannerOverlay(FrameLayout appFrame) {
        scannerOverlay = new FrameLayout(this);
        scannerOverlay.setBackgroundColor(Color.parseColor("#DD020617"));
        scannerOverlay.setVisibility(View.GONE);
        appFrame.addView(scannerOverlay, matchParent());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(makeRoundBg("#0F172A", "#334155", 24));
        panel.setPadding(0, 0, 0, dp(12));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.setMargins(dp(12), dp(22), dp(12), 0);
        scannerOverlay.addView(panel, panelLp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(12), dp(14));
        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView scannerTitle = text("Escáner de códigos", 20, true, "#FFFFFF");
        scannerLocation = text("", 13, true, "#CBD5E1");
        textBlock.addView(scannerTitle);
        textBlock.addView(scannerLocation);
        top.addView(textBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = new ImageButton(this);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setBackground(makeRoundBg("#1E293B", "#64748B", 999));
        close.setColorFilter(Color.WHITE);
        close.setOnClickListener(v -> closeScannerOverlay());
        top.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
        panel.addView(top);

        FrameLayout cameraBox = new FrameLayout(this);
        cameraBox.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraBox.addView(previewView, matchParent());
        scanFrameView = new ScanFrameView(this);
        cameraBox.addView(scanFrameView, matchParent());
        panel.addView(cameraBox, height(matchWidth(), dp(390)));

        scannerStatus = text("Presiona Iniciar lector para leer sin salir de la app.", 13, true, "#E2E8F0");
        scannerStatus.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.addView(scannerStatus);

        LinearLayout scannerButtons = new LinearLayout(this);
        scannerButtons.setOrientation(LinearLayout.HORIZONTAL);
        scannerButtons.setPadding(dp(16), 0, dp(16), dp(10));
        Button start = primaryButton("📷 Iniciar lector");
        start.setOnClickListener(v -> startCamera());
        Button stop = darkButton("⏸️ Detener");
        stop.setOnClickListener(v -> stopCamera());
        scannerButtons.addView(start, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(52), 1);
        stopLp.setMargins(dp(10), 0, 0, 0);
        scannerButtons.addView(stop, stopLp);
        panel.addView(scannerButtons);

        LinearLayout manual = new LinearLayout(this);
        manual.setOrientation(LinearLayout.HORIZONTAL);
        manual.setPadding(dp(16), 0, dp(16), 0);
        manualInput = new EditText(this);
        manualInput.setHint("Captura manual");
        manualInput.setSingleLine(true);
        manualInput.setTextSize(16);
        manualInput.setTextColor(Color.WHITE);
        manualInput.setHintTextColor(Color.parseColor("#94A3B8"));
        manualInput.setBackground(makeRoundBg("#1E293B", "#64748B", 14));
        manualInput.setPadding(dp(12), 0, dp(12), 0);
        manualInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                String code = manualInput.getText().toString().trim();
                if (!code.isEmpty()) {
                    addRecord(code);
                    manualInput.setText("");
                }
                return true;
            }
            return false;
        });
        Button register = darkButton("Registrar");
        register.setOnClickListener(v -> {
            String code = manualInput.getText().toString().trim();
            if (!code.isEmpty()) {
                addRecord(code);
                manualInput.setText("");
            } else {
                beepError();
            }
        });
        manual.addView(manualInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams regLp = new LinearLayout.LayoutParams(dp(118), dp(48));
        regLp.setMargins(dp(8), 0, 0, 0);
        manual.addView(register, regLp);
        panel.addView(manual);
    }

    private void render() {
        librarian = librarianInput.getText().toString().trim();
        sessionLabel.setText("Área activa: " + activeAreaName + "\nFecha/hora automática: " + nowDisplay());
        selectedLabel.setText("Escaneando: " + activeAreaName + " · Batería " + activeBattery + " · Estante " + activeShelf + " · Charola " + activeTray);
        statsLabel.setText(records.size() + " código(s) capturado(s). Total de estantes: 13 · Total de charolas: 78.");
        renderAreas();
        renderMap();
        renderTrays();
        renderRecords();
        updateScannerLocation();
        saveState();
    }

    private void renderAreas() {
        areaList.removeAllViews();
        for (int i = 0; i < areaIds.length; i++) {
            String id = areaIds[i];
            String name = areaNames[i];
            int count = countRecords(id, 0, 0, 0);
            Button b = areaButton(name + "\n" + count + " códigos", id.equals(activeAreaId));
            final int idx = i;
            b.setOnClickListener(v -> {
                activeAreaId = areaIds[idx];
                activeAreaName = areaNames[idx];
                activeBattery = 1;
                activeShelf = 1;
                activeTray = 1;
                render();
            });
            areaList.addView(b, marginBottom(height(matchWidth(), 70), 8));
        }
    }

    private void renderMap() {
        mapGrid.removeAllViews();
        addMapColumn("Batería 1\nE1–E4", 1, new int[]{1, 2, 3, 4});
        addMapColumn("Batería 1\nE5–E8", 1, new int[]{8, 7, 6, 5});
        addMapColumn("Batería 2\nE1–E5", 2, new int[]{1, 2, 3, 4, 5});
    }

    private void addMapColumn(String title, int battery, int[] shelves) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), 0, dp(4), 0);
        TextView t = text(title, 12, true, battery == 2 ? "#0F766E" : "#DC2626");
        t.setGravity(Gravity.CENTER);
        col.addView(t, height(matchWidth(), 44));
        for (int shelf : shelves) {
            boolean active = activeBattery == battery && activeShelf == shelf;
            int count = countRecords(activeAreaId, battery, shelf, 0);
            Button btn = mapButton("B" + battery + "\nE" + shelf + (count > 0 ? "\n" + count : ""), active, battery == 2);
            btn.setOnClickListener(v -> {
                activeBattery = battery;
                activeShelf = shelf;
                activeTray = 1;
                render();
            });
            col.addView(btn, marginBottom(height(matchWidth(), 62), 4));
        }
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        mapGrid.addView(col, lp);
    }

    private void renderTrays() {
        trayList.removeAllViews();
        for (int tray = 1; tray <= 6; tray++) {
            int count = countRecords(activeAreaId, activeBattery, activeShelf, tray);
            boolean open = activeTray == tray;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(makeRoundBg(open ? "#FFF7CC" : "#F8FAFC", open ? "#111827" : "#CBD5E1", 16));
            box.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView head = text("▤  Charola " + tray + "     " + count + " códigos", 16, true, open ? "#991B1B" : "#0F172A");
            final int trayNumber = tray;
            head.setOnClickListener(v -> {
                activeTray = trayNumber;
                render();
            });
            box.addView(head);

            if (open) {
                EditText input = new EditText(this);
                input.setHint("Escanear código en charola " + tray);
                input.setSingleLine(true);
                input.setTextSize(16);
                input.setBackground(makeRoundBg("#FFFFFF", "#111827", 14));
                input.setPadding(dp(12), 0, dp(12), 0);
                input.setOnEditorActionListener((v, actionId, event) -> {
                    String code = input.getText().toString().trim();
                    if (!code.isEmpty()) {
                        addRecord(code);
                        input.setText("");
                        return true;
                    }
                    return false;
                });
                input.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                        String code = input.getText().toString().trim();
                        if (!code.isEmpty()) {
                            addRecord(code);
                            input.setText("");
                        }
                        return true;
                    }
                    return false;
                });
                box.addView(input, marginTop(height(matchWidth(), 48), 10));

                LinearLayout codes = new LinearLayout(this);
                codes.setOrientation(LinearLayout.VERTICAL);
                for (Record r : records) {
                    if (r.areaId.equals(activeAreaId) && r.battery == activeBattery && r.shelf == activeShelf && r.tray == tray) {
                        codes.addView(recordChip(r));
                    }
                }
                if (codes.getChildCount() == 0) {
                    codes.addView(text("Sin códigos", 13, true, "#64748B"));
                }
                box.addView(codes, marginTop(matchWidthWrap(), 8));
            }
            trayList.addView(box, marginBottom(matchWidthWrap(), 8));
        }
    }

    private View recordChip(Record r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(8), dp(6));
        row.setBackground(makeRoundBg("#FFFFFF", "#E2E8F0", 12));
        TextView code = text(r.code + "   " + r.time, 13, true, "#0F172A");
        row.addView(code, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button x = smallDangerButton("×");
        x.setOnClickListener(v -> {
            records.remove(r);
            render();
        });
        row.addView(x, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams lp = marginBottom(matchWidthWrap(), 6);
        row.setLayoutParams(lp);
        return row;
    }

    private void renderRecords() {
        recordList.removeAllViews();
        if (records.isEmpty()) {
            recordList.addView(text("Sin registros capturados.", 13, true, "#64748B"));
            return;
        }
        for (int i = records.size() - 1; i >= 0; i--) {
            Record r = records.get(i);
            TextView row = text(r.areaName + " · B" + r.battery + " · E" + r.shelf + " · C" + r.tray + "\n" + r.code + " · " + r.date + " " + r.time + " · " + r.librarian, 12, true, "#0F172A");
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(makeRoundBg("#FFFFFF", "#E2E8F0", 12));
            recordList.addView(row, marginBottom(matchWidthWrap(), 6));
        }
    }

    private void openScannerOverlay() {
        if (librarianInput.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Captura primero el nombre del bibliotecario", Toast.LENGTH_SHORT).show();
            return;
        }
        scannerOverlay.setVisibility(View.VISIBLE);
        updateScannerLocation();
        scannerStatus.setText("Presiona Iniciar lector para leer sin salir de la app.");
    }

    private void closeScannerOverlay() {
        stopCamera();
        scannerOverlay.setVisibility(View.GONE);
    }

    private void updateScannerLocation() {
        if (scannerLocation != null) {
            scannerLocation.setText(activeAreaName + " · Batería " + activeBattery + " · Estante " + activeShelf + " · Charola " + activeTray);
        }
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        bindCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                bindCamera();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Permiso de cámara requerido")
                        .setMessage("Para escanear sin salir de la app, permite la cámara en Android.")
                        .setPositiveButton("Abrir ajustes", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            }
        }
    }

    private void bindCamera() {
        scannerStatus.setText("Iniciando cámara...");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.bindToLifecycle(this, selector, preview, analysis);
                scannerStatus.setText("Cámara activa. Coloca el código dentro del recuadro.");
                beepOk();
            } catch (Exception e) {
                scannerStatus.setText("No se pudo iniciar la cámara: " + e.getMessage());
                beepError();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(ImageProxy imageProxy) {
        try {
            android.media.Image mediaImage = imageProxy.getImage();
            if (mediaImage == null) {
                imageProxy.close();
                return;
            }
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            scanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty()) {
                            Barcode barcode = barcodes.get(0);
                            String raw = barcode.getRawValue();
                            if (raw != null && !raw.trim().isEmpty()) {
                                handleDetectedCode(raw.trim());
                            }
                        }
                    })
                    .addOnCompleteListener(task -> imageProxy.close());
        } catch (Exception e) {
            imageProxy.close();
        }
    }

    private void handleDetectedCode(String code) {
        long now = System.currentTimeMillis();
        if (code.equals(lastScanCode) && now - lastScanTime < 1800) return;
        lastScanCode = code;
        lastScanTime = now;
        runOnUiThread(() -> addRecord(code));
    }

    private void addRecord(String code) {
        librarian = librarianInput.getText().toString().trim();
        if (librarian.isEmpty()) {
            Toast.makeText(this, "Captura primero el nombre del bibliotecario", Toast.LENGTH_SHORT).show();
            beepError();
            return;
        }
        if (noDuplicates && existsCode(code)) {
            scannerStatus.setText("Código duplicado omitido: " + code);
            beepError();
            return;
        }
        Record r = new Record();
        r.id = records.size() + 1;
        r.areaId = activeAreaId;
        r.areaName = activeAreaName;
        r.librarian = librarian;
        r.date = today();
        r.time = timeNow();
        r.battery = activeBattery;
        r.shelf = activeShelf;
        r.tray = activeTray;
        r.code = code;
        records.add(r);
        scannerStatus.setText("Registrado: " + code);
        beepOk();
        render();
    }

    private boolean existsCode(String code) {
        for (Record r : records) {
            if (r.code.equals(code)) return true;
        }
        return false;
    }

    private void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (scannerStatus != null) scannerStatus.setText("Cámara detenida.");
    }

    private void exportCsv() {
        if (records.isEmpty()) {
            Toast.makeText(this, "No hay registros para exportar", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "inventario_biblioteca_" + today() + ".csv");
        startActivityForResult(intent, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null) {
            pendingExportUri = data.getData();
            writeCsv(pendingExportUri);
        }
    }

    private void writeCsv(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            StringBuilder sb = new StringBuilder();
            sb.append("ID,Área,Bibliotecario,Fecha,Hora,Batería,Estante,Charola,Código de barras\n");
            for (Record r : records) {
                sb.append(r.id).append(',')
                        .append(csv(r.areaName)).append(',')
                        .append(csv(r.librarian)).append(',')
                        .append(csv(r.date)).append(',')
                        .append(csv(r.time)).append(',')
                        .append(r.battery).append(',')
                        .append(r.shelf).append(',')
                        .append(r.tray).append(',')
                        .append(csv(r.code)).append('\n');
            }
            out.write(("\uFEFF" + sb).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Archivo exportado para Excel", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error al exportar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String csv(String value) {
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    private int countRecords(String areaId, int battery, int shelf, int tray) {
        int c = 0;
        for (Record r : records) {
            if (!r.areaId.equals(areaId)) continue;
            if (battery != 0 && r.battery != battery) continue;
            if (shelf != 0 && r.shelf != shelf) continue;
            if (tray != 0 && r.tray != tray) continue;
            c++;
        }
        return c;
    }

    private void saveState() {
        SharedPreferences sp = getSharedPreferences("state", MODE_PRIVATE);
        sp.edit()
                .putString("librarian", librarianInput.getText().toString())
                .putString("activeAreaId", activeAreaId)
                .putString("activeAreaName", activeAreaName)
                .putInt("activeBattery", activeBattery)
                .putInt("activeShelf", activeShelf)
                .putInt("activeTray", activeTray)
                .putString("records", serializeRecords())
                .apply();
    }

    private void loadState() {
        SharedPreferences sp = getSharedPreferences("state", MODE_PRIVATE);
        librarianInput.setText(sp.getString("librarian", ""));
        activeAreaId = sp.getString("activeAreaId", activeAreaId);
        activeAreaName = sp.getString("activeAreaName", activeAreaName);
        activeBattery = sp.getInt("activeBattery", activeBattery);
        activeShelf = sp.getInt("activeShelf", activeShelf);
        activeTray = sp.getInt("activeTray", activeTray);
        deserializeRecords(sp.getString("records", ""));
    }

    private String serializeRecords() {
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            sb.append(r.id).append('|')
                    .append(enc(r.areaId)).append('|')
                    .append(enc(r.areaName)).append('|')
                    .append(enc(r.librarian)).append('|')
                    .append(enc(r.date)).append('|')
                    .append(enc(r.time)).append('|')
                    .append(r.battery).append('|')
                    .append(r.shelf).append('|')
                    .append(r.tray).append('|')
                    .append(enc(r.code)).append('\n');
        }
        return sb.toString();
    }

    private void deserializeRecords(String data) {
        records.clear();
        if (data == null || data.trim().isEmpty()) return;
        String[] lines = data.split("\\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 10) continue;
            try {
                Record r = new Record();
                r.id = Integer.parseInt(p[0]);
                r.areaId = dec(p[1]);
                r.areaName = dec(p[2]);
                r.librarian = dec(p[3]);
                r.date = dec(p[4]);
                r.time = dec(p[5]);
                r.battery = Integer.parseInt(p[6]);
                r.shelf = Integer.parseInt(p[7]);
                r.tray = Integer.parseInt(p[8]);
                r.code = dec(p[9]);
                records.add(r);
            } catch (Exception ignored) { }
        }
    }

    private String enc(String value) {
        return Base64.encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private String dec(String value) {
        return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    private void beepOk() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
    }

    private void beepError() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_NACK, 180);
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    private String timeNow() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private String nowDisplay() {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private TextView text(String s, int sp, boolean bold, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setLineSpacing(dp(2), 1f);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(Locale.ROOT), 12, true, "#64748B");
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(makeRoundBg("#FFFFFF", "#D8E1EE", 24));
        return l;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setBackground(makeRoundBg("#2563EB", "#2563EB", 999));
        return b;
    }

    private Button successButton(String text) {
        Button b = primaryButton(text);
        b.setBackground(makeRoundBg("#0F766E", "#0F766E", 999));
        return b;
    }

    private Button darkButton(String text) {
        Button b = primaryButton(text);
        b.setBackground(makeRoundBg("#334155", "#64748B", 999));
        return b;
    }

    private Button smallDangerButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.parseColor("#DC2626"));
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setBackground(makeRoundBg("#FEE2E2", "#FCA5A5", 999));
        return b;
    }

    private Button areaButton(String text, boolean active) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setTextSize(13);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setTextColor(Color.parseColor(active ? "#0F172A" : "#334155"));
        b.setBackground(makeRoundBg(active ? "#EAF1FF" : "#F8FAFC", active ? "#2563EB" : "#CBD5E1", 16));
        return b;
    }

    private Button mapButton(String text, boolean active, boolean b2) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(12);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setTextColor(Color.parseColor(active ? "#FFFFFF" : (b2 ? "#0F766E" : "#991B1B")));
        btn.setBackground(makeRoundBg(active ? "#0F172A" : (b2 ? "#DDFCF4" : "#FEE2E2"), b2 ? "#0F766E" : "#DC2626", 12));
        return btn;
    }

    private android.graphics.drawable.GradientDrawable makeRoundBg(String fill, String stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor(fill));
        gd.setStroke(dp(1), Color.parseColor(stroke));
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchParentWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams height(LinearLayout.LayoutParams base, int h) {
        base.height = h;
        return base;
    }

    private LinearLayout.LayoutParams marginBottom(LinearLayout.LayoutParams lp, int bottomDp) {
        lp.setMargins(0, 0, 0, dp(bottomDp));
        return lp;
    }

    private LinearLayout.LayoutParams marginTop(LinearLayout.LayoutParams lp, int topDp) {
        lp.setMargins(0, dp(topDp), 0, 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static class Record {
        int id;
        String areaId;
        String areaName;
        String librarian;
        String date;
        String time;
        int battery;
        int shelf;
        int tray;
        String code;
    }

    public static class ScanFrameView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float yOffset = 0;
        private boolean down = true;

        public ScanFrameView(android.content.Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6);
            paint.setColor(Color.parseColor("#FACC15"));
            dim.setStyle(Paint.Style.FILL);
            dim.setColor(Color.parseColor("#66000000"));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(5);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(Color.parseColor("#22C55E"));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            RectF frame = new RectF(w * .16f, h * .25f, w * .84f, h * .72f);
            canvas.drawRect(0, 0, w, frame.top, dim);
            canvas.drawRect(0, frame.bottom, w, h, dim);
            canvas.drawRect(0, frame.top, frame.left, frame.bottom, dim);
            canvas.drawRect(frame.right, frame.top, w, frame.bottom, dim);
            canvas.drawRoundRect(frame, 22, 22, paint);
            float lineY = frame.top + (frame.height() * yOffset);
            canvas.drawLine(frame.left + 18, lineY, frame.right - 18, lineY, line);
            if (down) {
                yOffset += .018f;
                if (yOffset >= .92f) down = false;
            } else {
                yOffset -= .018f;
                if (yOffset <= .08f) down = true;
            }
            postInvalidateDelayed(16);
        }
    }
}
