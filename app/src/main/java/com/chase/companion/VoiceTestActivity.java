package com.chase.companion;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceTestActivity extends Activity {
    private static final int MODEL = 201, INDEX = 202;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private TextView report, files;
    private EditText sentence;
    private Spinner voices;
    private Button generate, playback, modelButton, indexButton;
    private TextToSpeech tts;
    private MediaPlayer player;
    private final List<Voice> offline = new ArrayList<>();
    private String currentId = "";
    private long synthesisStarted;
    private File directory, sample;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        directory = new File(getFilesDir(), "roxy_voice");
        sample = new File(directory, "baseline.wav");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);
        TextView title = new TextView(this);
        title.setText("Roxy voice — local preparation test");
        title.setTextSize(22);
        root.addView(title);
        TextView notice = new TextView(this);
        notice.setText("Stage 1 only: import files and test ordinary local TTS. RVC conversion is NOT implemented here; the sample will NOT use Roxy's model. No model execution, microphone capture, or upload code. Original files remain untouched.");
        root.addView(notice);
        files = new TextView(this); root.addView(files);
        modelButton = button(root, "Import RoxanneWolf.pth", () -> choose(MODEL));
        indexButton = button(root, "Import matching .index", () -> choose(INDEX));
        voices = new Spinner(this); root.addView(voices);
        sentence = new EditText(this);
        sentence.setText("This is a local speech test for the companion.");
        sentence.setMaxLines(3); root.addView(sentence);
        generate = button(root, "Generate baseline audio (not Roxy)", this::synthesize);
        generate.setEnabled(false);
        playback = button(root, "Play baseline audio", this::playSample);
        playback.setEnabled(false);
        button(root, "Stop audio", () -> { cancelSynthesis(); releasePlayer(); });
        report = new TextView(this); report.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            append("Could not create app-private storage."); return;
        }
        showFiles();
        append("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "; Android API " + Build.VERSION.SDK_INT);
        append("File import checks extension, size and SHA-256 only. It does not validate RVC architecture, index matching, or inference compatibility.");
        tts = new TextToSpeech(getApplicationContext(), result -> runOnUiThread(() -> initializeTts(result)));
    }

    private Button button(LinearLayout root, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); root.addView(b); return b;
    }
    private void append(String message) {
        if (!closed) report.append(message + "\n\n");
    }
    private void showFiles() {
        File model = new File(directory, "roxy.pth"), index = new File(directory, "roxy.index");
        files.setText("Private model copy: " + (model.isFile() ? model.length() + " bytes" : "not imported") +
            "\nPrivate index copy: " + (index.isFile() ? index.length() + " bytes" : "not imported"));
    }
    private void choose(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        try { startActivityForResult(intent, request); }
        catch (RuntimeException error) { append("File picker unavailable: " + error.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != MODEL && request != INDEX) || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException error) { append("Cannot read selected filename: " + error.getMessage()); return; }
        String extension = request == MODEL ? ".pth" : ".index";
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(extension)) { append("Select a file ending in " + extension); return; }
        String selectedName = name;
        modelButton.setEnabled(false); indexButton.setEnabled(false);
        append("Copying " + name + " into private storage; the original is not modified.");
        worker.execute(() -> {
            File temporary = new File(directory, UUID.randomUUID() + ".part");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0; byte[] buffer = new byte[65536];
                try (InputStream input = getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new IOException("Cannot open selected file");
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                        total += count;
                        if (total > 1024L * 1024 * 1024) throw new IOException("Import limit is 1 GiB per file");
                        output.write(buffer, 0, count); digest.update(buffer, 0, count);
                    }
                }
                if (total == 0) throw new IOException("Selected file is empty");
                File destination = new File(directory, request == MODEL ? "roxy.pth" : "roxy.index");
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
                String message = "Imported " + selectedName + " (" + total + " bytes).\nSHA-256: " + hash + "\nCopied, not executed or validated as an RVC model.";
                runOnUiThread(() -> { if (!closed) { showFiles(); append(message); } });
            } catch (Exception error) { runOnUiThread(() -> append("Import failed: " + error.getMessage())); }
            finally {
                temporary.delete();
                runOnUiThread(() -> { if (!closed) { modelButton.setEnabled(true); indexButton.setEnabled(true); } });
            }
        });
    }

    private void initializeTts(int result) {
        if (closed || tts == null) return;
        if (result != TextToSpeech.SUCCESS) { append("TTS engine initialization failed."); return; }
        try {
            Set<Voice> available = tts.getVoices();
            if (available != null) for (Voice voice : available) {
                Set<String> features = voice.getFeatures();
                if (!voice.isNetworkConnectionRequired() && "en".equals(voice.getLocale().getLanguage()) &&
                    (features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) offline.add(voice);
            }
            offline.sort(Comparator.comparing(Voice::getName));
            List<String> labels = new ArrayList<>();
            for (Voice voice : offline) labels.add(voice.getName() + " (" + voice.getLocale() + ")");
            if (labels.isEmpty()) { append("No installed English voice reports offline support. Install offline English voice data in your TTS engine, then reopen this test. No network voice will be substituted."); return; }
            voices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { runOnUiThread(() -> complete(id, null)); }
                @Override public void onError(String id) { runOnUiThread(() -> complete(id, "TTS synthesis failed")); }
                @Override public void onStop(String id, boolean interrupted) { runOnUiThread(() -> complete(id, "TTS synthesis stopped")); }
            });
            generate.setEnabled(true);
            append("TTS engine: " + tts.getDefaultEngine() + ". Voices shown report no network requirement. Verify with airplane mode; the app does not enforce networking policy inside a third-party TTS engine.");
        } catch (RuntimeException error) { append("TTS setup failed: " + error.getMessage()); }
    }
    private void synthesize() {
        String text = sentence.getText().toString().trim();
        if (text.isEmpty() || text.length() > 500) { append("Enter 1–500 characters for the short baseline test."); return; }
        int selected = voices.getSelectedItemPosition();
        if (tts == null || selected < 0 || selected >= offline.size()) return;
        cancelSynthesis(); releasePlayer(); playback.setEnabled(false);
        try {
            if (tts.setVoice(offline.get(selected)) != TextToSpeech.SUCCESS) { append("Could not select the offline voice."); return; }
            if (sample.exists() && !sample.delete()) { append("Cannot replace previous baseline audio."); return; }
            currentId = UUID.randomUUID().toString();
            synthesisStarted = SystemClock.elapsedRealtime();
            generate.setEnabled(false);
            if (tts.synthesizeToFile(text, new Bundle(), sample, currentId) != TextToSpeech.SUCCESS) complete(currentId, "TTS rejected synthesis request");
        } catch (RuntimeException error) { complete(currentId, error.getMessage()); }
    }
    private void complete(String id, String error) {
        if (closed || !id.equals(currentId) || currentId.isEmpty()) return;
        currentId = ""; generate.setEnabled(!offline.isEmpty());
        if (error != null) { append(error); return; }
        try (RandomAccessFile wav = new RandomAccessFile(sample, "r")) {
            byte[] header = new byte[12]; wav.readFully(header);
            if (sample.length() <= 44 || header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' ||
                header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') throw new IOException("Engine output is not a nonempty RIFF/WAVE file");
            playback.setEnabled(true);
            append("Baseline audio generated in " + (SystemClock.elapsedRealtime() - synthesisStarted) + " ms; " + sample.length() + " bytes. This measures ordinary TTS only, NOT RVC conversion.");
        } catch (IOException failure) { append("Audio validation failed: " + failure.getMessage()); }
    }
    private void playSample() {
        releasePlayer();
        try {
            MediaPlayer next = new MediaPlayer(); player = next;
            next.setDataSource(sample.getAbsolutePath());
            next.setOnPreparedListener(p -> { if (!closed && player == p) p.start(); });
            next.setOnCompletionListener(p -> { if (player == p) releasePlayer(); });
            next.setOnErrorListener((p, what, extra) -> { append("Playback failed: " + what + " / " + extra); if (player == p) releasePlayer(); return true; });
            next.prepareAsync();
        } catch (Exception error) { append("Playback failed: " + error.getMessage()); releasePlayer(); }
    }
    private void releasePlayer() { if (player != null) { player.release(); player = null; } }
    private void cancelSynthesis() {
        currentId = ""; if (tts != null) tts.stop();
        if (generate != null) generate.setEnabled(!offline.isEmpty());
    }
    @Override protected void onPause() { cancelSynthesis(); releasePlayer(); super.onPause(); }
    @Override protected void onDestroy() {
        closed = true; currentId = ""; worker.shutdownNow(); releasePlayer();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
