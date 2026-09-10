package com.sanoha.pocketmp3;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 42;
    private static final int NOTIFICATION_PERMISSION = 43;

    private Button addMusic, playButton, prevButton, nextButton;
    private TextView songTitle, songStatus, currentTime, totalTime, emptyQueue;
    private SeekBar seekBar;
    private ListView queueList;
    private ArrayAdapter<String> adapter;
    private final ArrayList<Uri> uris = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private PlaybackService service;
    private boolean bound;
    private boolean userSeeking;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.PlayerBinder) binder).getService();
            bound = true;
            if (!uris.isEmpty()) {
                service.setQueue(uris, names);
            } else if (service.getQueueSize() > 0) {
                uris.addAll(service.getQueueUris());
                names.addAll(service.getQueueNames());
                adapter.notifyDataSetChanged();
            }
            refreshUi();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 350);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(11, 13, 18));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 18));
        buildUi();

        addMusic.setOnClickListener(v -> chooseAudio());
        playButton.setOnClickListener(v -> { if (bound) service.toggle(); });
        prevButton.setOnClickListener(v -> { if (bound) service.previous(); });
        nextButton.setOnClickListener(v -> { if (bound) service.next(); });
        queueList.setOnItemClickListener((parent, view, position, id) -> {
            if (bound) {
                service.setQueue(uris, names);
                service.playIndex(position);
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && bound && service.getDuration() > 0) {
                    currentTime.setText(formatTime((long) service.getDuration() * progress / 1000));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (bound && service.getDuration() > 0) {
                    service.seekTo((int) ((long) service.getDuration() * bar.getProgress() / 1000));
                }
                userSeeking = false;
            }
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private void buildUi() {
        int bg = Color.rgb(11, 13, 18);
        int panel = Color.rgb(21, 25, 34);
        int text = Color.rgb(246, 247, 251);
        int muted = Color.rgb(152, 162, 179);
        int accent = Color.rgb(155, 140, 255);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(12));
        root.setBackgroundColor(bg);
        setContentView(root);

        TextView appName = label("PocketMP3", 26, text, true);
        root.addView(appName, lp(-1, -2, 0, 0, 0, 8));

        TextView sub = label("Offline music player", 13, muted, false);
        root.addView(sub, lp(-1, -2, 0, 0, 0, 16));

        addMusic = new Button(this);
        addMusic.setText("Add music");
        addMusic.setTextColor(Color.WHITE);
        addMusic.setTextSize(15);
        addMusic.setAllCaps(false);
        addMusic.setBackground(roundRect(accent, 18));
        root.addView(addMusic, lp(-1, dp(52), 0, 0, 0, 16));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(18), dp(22), dp(18), dp(18));
        card.setBackground(roundRect(panel, 24));
        root.addView(card, lp(-1, -2, 0, 0, 0, 14));

        TextView disc = label("♫", 64, accent, true);
        disc.setGravity(Gravity.CENTER);
        card.addView(disc, lp(-1, dp(96), 0, 0, 0, 4));

        songTitle = label("Nothing playing", 20, text, true);
        songTitle.setGravity(Gravity.CENTER);
        songTitle.setSingleLine(true);
        songTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(songTitle, lp(-1, -2, 0, 0, 0, 4));

        songStatus = label("Choose music from your phone", 13, muted, false);
        songStatus.setGravity(Gravity.CENTER);
        card.addView(songStatus, lp(-1, -2, 0, 0, 0, 10));

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        card.addView(seekBar, lp(-1, -2, 0, 0, 0, 2));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        currentTime = label("0:00", 12, muted, false);
        totalTime = label("0:00", 12, muted, false);
        times.addView(currentTime, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(totalTime, new LinearLayout.LayoutParams(-2, -2));
        card.addView(times, lp(-1, -2, 0, 0, 0, 8));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        prevButton = controlButton("◀◀", panel, text, 58, 54);
        playButton = controlButton("▶", accent, Color.WHITE, 72, 68);
        nextButton = controlButton("▶▶", panel, text, 58, 54);
        controls.addView(prevButton, lp(dp(58), dp(54), 0, 0, 14, 0));
        controls.addView(playButton, lp(dp(72), dp(68), 0, 0, 14, 0));
        controls.addView(nextButton, lp(dp(58), dp(54), 0, 0, 0, 0));
        card.addView(controls, lp(-1, -2, 0, 0, 0, 0));

        TextView q = label("QUEUE", 12, muted, true);
        q.setLetterSpacing(0.14f);
        root.addView(q, lp(-1, -2, 0, 6, 0, 8));

        emptyQueue = label("Tap “Add music” and choose one or more audio files.", 14, muted, false);
        root.addView(emptyQueue, lp(-1, -2, 0, 0, 0, 6));

        queueList = new ListView(this);
        queueList.setDividerHeight(1);
        queueList.setBackgroundColor(bg);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(text);
                v.setTextSize(15f);
                v.setPadding(dp(8), dp(14), dp(8), dp(14));
                return v;
            }
        };
        queueList.setAdapter(adapter);
        root.addView(queueList, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private TextView label(String s, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button controlButton(String s, int bg, int color, int w, int h) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(color);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(roundRect(bg, 22));
        return b;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStart() {
        super.onStart();
        Intent i = new Intent(this, PlaybackService.class);
        startService(i);
        bindService(i, connection, BIND_AUTO_CREATE);
        handler.post(ticker);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(ticker);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onStop();
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null) return;

        ArrayList<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }

        for (Uri uri : picked) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            if (!uris.contains(uri)) {
                uris.add(uri);
                names.add(displayName(uri));
            }
        }

        adapter.notifyDataSetChanged();
        emptyQueue.setVisibility(names.isEmpty() ? View.VISIBLE : View.GONE);
        if (bound) {
            service.setQueue(uris, names);
            if (service.getCurrentIndex() < 0 && !uris.isEmpty()) service.playIndex(0);
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return c.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "Audio track" : last;
    }

    private void refreshUi() {
        if (!bound || service == null) return;
        songTitle.setText(service.getCurrentName());
        boolean playing = service.isPlaying();
        playButton.setText(playing ? "Ⅱ" : "▶");
        songStatus.setText(service.getCurrentIndex() >= 0 ? (playing ? "Playing" : "Paused") : "Choose music from your phone");

        int duration = service.getDuration();
        int position = service.getPosition();
        if (!userSeeking) seekBar.setProgress(duration > 0 ? (int) ((long) position * 1000 / duration) : 0);
        currentTime.setText(formatTime(position));
        totalTime.setText(formatTime(duration));
        emptyQueue.setVisibility(names.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
