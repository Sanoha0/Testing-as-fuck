package com.sanoha.pocketmp3;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.*;
import java.util.*;

public class PlaybackService extends Service implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {
    public static final String ACTION_TOGGLE = "com.sanoha.pocketmp3.TOGGLE";
    public static final String ACTION_NEXT = "com.sanoha.pocketmp3.NEXT";
    public static final String ACTION_PREV = "com.sanoha.pocketmp3.PREV";
    public static final String ACTION_STOP = "com.sanoha.pocketmp3.STOP";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "pocketmp3_playback";

    private final IBinder binder = new PlayerBinder();
    private final ArrayList<Uri> uris = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private MediaPlayer player;
    private int currentIndex = -1;
    private boolean foreground;
    private AudioManager audioManager;
    private MediaSession mediaSession;

    public class PlayerBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        mediaSession = new MediaSession(this, "PocketMP3");
        mediaSession.setActive(true);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, filter);
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE.equals(action)) toggle();
            else if (ACTION_NEXT.equals(action)) next();
            else if (ACTION_PREV.equals(action)) previous();
            else if (ACTION_STOP.equals(action)) {
                pause();
                stopForeground(true);
                foreground = false;
                stopSelf();
            }
        }
        return START_STICKY;
    }

    public void setQueue(List<Uri> newUris, List<String> newNames) {
        uris.clear();
        names.clear();
        uris.addAll(newUris);
        names.addAll(newNames);
        if (currentIndex >= uris.size()) currentIndex = -1;
    }

    public int getQueueSize() { return uris.size(); }
    public ArrayList<Uri> getQueueUris() { return new ArrayList<>(uris); }
    public ArrayList<String> getQueueNames() { return new ArrayList<>(names); }
    public int getCurrentIndex() { return currentIndex; }
    public String getCurrentName() {
        return currentIndex >= 0 && currentIndex < names.size() ? names.get(currentIndex) : "Nothing playing";
    }
    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public int getDuration() {
        try { return player != null ? player.getDuration() : 0; }
        catch (IllegalStateException e) { return 0; }
    }
    public int getPosition() {
        try { return player != null ? player.getCurrentPosition() : 0; }
        catch (IllegalStateException e) { return 0; }
    }

    public void playIndex(int index) {
        if (index < 0 || index >= uris.size()) return;
        currentIndex = index;
        releasePlayer();
        try {
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(this, uris.get(index));
            player.setOnCompletionListener(this);
            player.prepare();
            player.start();
            updateForegroundNotification();
        } catch (Exception e) {
            releasePlayer();
        }
    }

    public void toggle() {
        if (player == null) {
            if (!uris.isEmpty()) playIndex(currentIndex >= 0 ? currentIndex : 0);
        } else if (player.isPlaying()) {
            pause();
        } else {
            try {
                player.start();
                updateForegroundNotification();
            } catch (IllegalStateException ignored) {}
        }
    }

    public void pause() {
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {}
        updateForegroundNotification();
    }

    public void next() {
        if (uris.isEmpty()) return;
        int next = currentIndex < 0 ? 0 : (currentIndex + 1) % uris.size();
        playIndex(next);
    }

    public void previous() {
        if (uris.isEmpty()) return;
        if (player != null && getPosition() > 5000) {
            seekTo(0);
            return;
        }
        int prev = currentIndex <= 0 ? uris.size() - 1 : currentIndex - 1;
        playIndex(prev);
    }

    public void seekTo(int positionMs) {
        if (player != null) {
            try { player.seekTo(Math.max(0, Math.min(positionMs, player.getDuration()))); }
            catch (IllegalStateException ignored) {}
        }
    }

    @Override public void onCompletion(MediaPlayer mp) { next(); }

    @Override public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            pause();
        }
    }

    private void updateForegroundNotification() {
        if (currentIndex < 0) return;

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent prev = serviceAction(ACTION_PREV, 11);
        PendingIntent toggle = serviceAction(ACTION_TOGGLE, 12);
        PendingIntent next = serviceAction(ACTION_NEXT, 13);
        PendingIntent stop = serviceAction(ACTION_STOP, 14);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getCurrentName())
                .setContentText(isPlaying() ? "Playing" : "Paused")
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", prev).build())
                .addAction(new Notification.Action.Builder(isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying() ? "Pause" : "Play", toggle).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", next).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
                .setStyle(new Notification.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                        .setMediaSession(mediaSession.getSessionToken()));

        Notification notification = b.build();
        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        } else {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
        }
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Playback controls for PocketMP3");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        releasePlayer();
        if (audioManager != null) audioManager.abandonAudioFocus(this);
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }
}
