package com.vtubephone.sensing;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

public final class MotionSensor implements SensorEventListener {
    private static final int[] TYPES = { Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GAME_ROTATION_VECTOR };
    private static final int CAPACITY = 8192, STRIDE = 6, HEADER = 22;
    private final SensorManager manager;
    private final Sensor[] sensors = new Sensor[TYPES.length];
    private final boolean[] registered = new boolean[TYPES.length];
    private final long[] firstNs = new long[TYPES.length], lastNs = new long[TYPES.length], counts = new long[TYPES.length];
    private final double[] ring = new double[CAPACITY * STRIDE];
    private final Object lock = new Object();
    private HandlerThread thread;
    private int read, size;
    private long dropped;
    private boolean running;

    public MotionSensor(Context activity) {
        manager = (SensorManager) activity.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        if (manager != null) for (int i = 0; i < TYPES.length; i++) sensors[i] = manager.getDefaultSensor(TYPES[i]);
    }
    public synchronized void start() {
        if (thread != null || manager == null) return;
        thread = new HandlerThread("VtubePhoneMotion"); thread.start();
        synchronized (lock) {
            read = size = 0; dropped = 0; running = true;
            for (int i = 0; i < TYPES.length; i++) { firstNs[i] = lastNs[i] = counts[i] = 0; registered[i] = false; }
        }
        Handler handler = new Handler(thread.getLooper());
        for (int i = 0; i < TYPES.length; i++) if (sensors[i] != null) {
            boolean ok;
            try { ok = manager.registerListener(this, sensors[i], SensorManager.SENSOR_DELAY_FASTEST, handler); }
            catch (SecurityException e) { ok = false; }
            synchronized (lock) { registered[i] = ok; }
        }
    }
    public synchronized void stop() {
        synchronized (lock) { running = false; size = 0; }
        if (manager != null) manager.unregisterListener(this);
        if (thread != null) { thread.quitSafely(); thread = null; }
    }
    @Override public void onSensorChanged(SensorEvent event) {
        synchronized (lock) {
            if (!running) return;
            int type = event.sensor.getType();
            for (int i = 0; i < TYPES.length; i++) if (TYPES[i] == type) {
                if (counts[i] == 0) firstNs[i] = event.timestamp;
                lastNs[i] = event.timestamp; counts[i]++; break;
            }
            if (size == CAPACITY) { read = (read + 1) % CAPACITY; size--; dropped++; }
            int at = ((read + size) % CAPACITY) * STRIDE;
            ring[at] = event.timestamp; ring[at + 1] = type;
            ring[at + 2] = event.values[0]; ring[at + 3] = event.values[1]; ring[at + 4] = event.values[2];
            ring[at + 5] = type == Sensor.TYPE_GAME_ROTATION_VECTOR ?
                (event.values.length > 3 ? event.values[3] : Math.sqrt(Math.max(0, 1 - event.values[0] * event.values[0] - event.values[1] * event.values[1] - event.values[2] * event.values[2]))) : 0;
            size++;
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // One JNI call/frame. Header: elapsedRealtimeNs, dropped, then five (type, present, registered, Hz) tuples.
    // Remaining doubles: (timestampNs, type, x, y, z, w). Timestamps retain integer-ns precision up to 104 days uptime.
    public double[] drain() {
        synchronized (lock) {
            double[] result = new double[HEADER + size * STRIDE];
            result[0] = SystemClock.elapsedRealtimeNanos(); result[1] = dropped;
            for (int i = 0; i < TYPES.length; i++) {
                int at = 2 + i * 4; result[at] = TYPES[i]; result[at + 1] = sensors[i] != null ? 1 : 0;
                result[at + 2] = registered[i] ? 1 : 0;
                result[at + 3] = counts[i] > 1 && lastNs[i] > firstNs[i] ? (counts[i] - 1) * 1.0e9 / (lastNs[i] - firstNs[i]) : 0;
            }
            for (int i = 0; i < size; i++) System.arraycopy(ring, ((read + i) % CAPACITY) * STRIDE, result, HEADER + i * STRIDE, STRIDE);
            read = (read + size) % CAPACITY; size = 0;
            return result;
        }
    }
}
