package com.vtubephone.haptics;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Method;

public final class HapticEngine {
    private final Vibrator vibrator;
    private final Map<String, VibrationEffect> cache = new HashMap<>();
    private final AudioAttributes audioAttributes;
    private Object vibrationAttributes;
    private Method vibrateWithAttributes;
    private long lastVibrateNs;
    private String lastStatus = "Not played";

    public HapticEngine(Context activity) {
        Context context = activity.getApplicationContext();
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Class<?> builderClass = Class.forName("android.os.VibrationAttributes$Builder");
                Object builder = builderClass.getConstructor().newInstance();
                int media = Class.forName("android.os.VibrationAttributes").getField("USAGE_MEDIA").getInt(null);
                builderClass.getMethod("setUsage", int.class).invoke(builder, media);
                vibrationAttributes = builderClass.getMethod("build").invoke(builder);
                vibrateWithAttributes = Vibrator.class.getMethod("vibrate", VibrationEffect.class, Class.forName("android.os.VibrationAttributes"));
            } catch (ReflectiveOperationException e) {
                vibrationAttributes = null; vibrateWithAttributes = null;
                Log.w("VtubePhone", "Media attributes unavailable; using game audio attributes", e);
            }
        }
    }

    private Object vibratorCall(String name) throws ReflectiveOperationException {
        return Vibrator.class.getMethod(name).invoke(vibrator);
    }

    public String getCapabilities() throws Exception {
        JSONObject caps = new JSONObject();
        caps.put("sdkInt", Build.VERSION.SDK_INT);
        caps.put("usage", vibrationAttributes != null ? "VibrationAttributes.USAGE_MEDIA" : "AudioAttributes.USAGE_GAME");
        caps.put("manufacturer", Build.MANUFACTURER); caps.put("model", Build.MODEL);
        caps.put("hasVibrator", vibrator != null && vibrator.hasVibrator());
        caps.put("hasAmplitudeControl", vibrator != null && vibrator.hasAmplitudeControl());
        JSONArray primitives = new JSONArray();
        for (int id = 1; id <= 8; id++) {
            boolean introduced = (id != 2 && id != 3 && id != 8) || Build.VERSION.SDK_INT >= 31;
            boolean supported = vibrator != null && Build.VERSION.SDK_INT >= 30 && introduced && vibrator.arePrimitivesSupported(id)[0];
            int duration = supported && Build.VERSION.SDK_INT >= 31 ? vibrator.getPrimitiveDurations(id)[0] : 0;
            primitives.put(new JSONObject().put("id", id).put("supported", supported).put("duration", duration));
        }
        caps.put("primitives", primitives);
        JSONObject envelope = new JSONObject().put("supported", false);
        if (vibrator != null && Build.VERSION.SDK_INT >= 36) {
            try {
                boolean supported = (Boolean) vibratorCall("areEnvelopeEffectsSupported");
                Object info = vibratorCall("getEnvelopeEffectInfo");
                if (supported && info != null) {
                    Class<?> infoClass = Class.forName("android.os.vibrator.VibratorEnvelopeEffectInfo");
                    envelope.put("maxSize", infoClass.getMethod("getMaxSize").invoke(info));
                    envelope.put("minControlPointMs", infoClass.getMethod("getMinControlPointDurationMillis").invoke(info));
                    envelope.put("maxControlPointMs", infoClass.getMethod("getMaxControlPointDurationMillis").invoke(info));
                    envelope.put("maxDuration", infoClass.getMethod("getMaxDurationMillis").invoke(info));
                    envelope.put("supported", true);
                }
            } catch (ReflectiveOperationException e) { caps.put("diagnostics", "Envelope probe failed: " + e); }
        }
        caps.put("envelope", envelope);
        for (String[] field : new String[][] {{"resonantFrequency", "getResonantFrequency"}, {"qFactor", "getQFactor"}}) {
            double value = -1;
            if (vibrator != null && Build.VERSION.SDK_INT >= 34) {
                try { value = ((Number) vibratorCall(field[1])).doubleValue(); }
                catch (ReflectiveOperationException ignored) { /* Optional hardware metadata. */ }
            }
            caps.put(field[0], Double.isNaN(value) || Double.isInfinite(value) ? -1 : value);
        }
        String report = caps.toString(); Log.i("VtubePhone", report); return report;
    }

    public String prepare(String key, String commandJson) throws Exception {
        JSONObject command = new JSONObject(commandJson);
        String requested = command.getString("tier");
        VibrationEffect effect;
        try { effect = build(command); lastStatus = requested; }
        catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException e) {
            effect = waveform(command); lastStatus = "Fallback " + (vibrator.hasAmplitudeControl() ? "C" : "D") + ": " + e;
            Log.w("VtubePhone", lastStatus);
        }
        cache.put(key, effect); return lastStatus;
    }

    private VibrationEffect build(JSONObject command) throws Exception {
        String tier = command.getString("tier");
        if (tier.equals("A")) {
            if (Build.VERSION.SDK_INT < 36 || !(Boolean) vibratorCall("areEnvelopeEffectsSupported"))
                throw new IllegalStateException("Envelope unavailable");
            Class<?> type = Class.forName("android.os.VibrationEffect$BasicEnvelopeBuilder");
            Object builder = type.getConstructor().newInstance();
            JSONArray points = command.getJSONArray("points");
            type.getMethod("setInitialSharpness", float.class).invoke(builder, (float) points.getJSONObject(0).getDouble("sharpness"));
            for (int i = 1; i < points.length(); i++) {
                JSONObject p = points.getJSONObject(i);
                type.getMethod("addControlPoint", float.class, float.class, long.class).invoke(builder,
                    (float) p.getDouble("intensity"), (float) p.getDouble("sharpness"), p.getLong("durationMs"));
            }
            return (VibrationEffect) type.getMethod("build").invoke(builder);
        }
        if (tier.equals("B")) {
            if (Build.VERSION.SDK_INT < 30) throw new IllegalStateException("Composition unavailable");
            VibrationEffect.Composition composition = VibrationEffect.startComposition();
            JSONArray primitives = command.getJSONArray("primitives");
            for (int i = 0; i < primitives.length(); i++) {
                JSONObject p = primitives.getJSONObject(i); int id = p.getInt("id");
                if (!vibrator.arePrimitivesSupported(id)[0]) throw new IllegalStateException("Unsupported primitive " + id);
                composition.addPrimitive(id, (float) p.getDouble("scale"), p.getInt("delayMs"));
            }
            return composition.compose();
        }
        return waveform(command);
    }

    private long[] longs(JSONArray array) throws Exception {
        long[] values = new long[array.length()];
        for (int i = 0; i < values.length; i++) values[i] = array.getLong(i);
        return values;
    }
    private VibrationEffect waveform(JSONObject command) throws Exception {
        if (!command.getString("tier").equals("D") && vibrator.hasAmplitudeControl()) {
            JSONArray array = command.getJSONArray("amplitudes"); int[] amplitudes = new int[array.length()];
            boolean nonzero = false;
            for (int i = 0; i < amplitudes.length; i++) { amplitudes[i] = array.getInt(i); nonzero |= amplitudes[i] > 0; }
            if (!nonzero) return null;
            return VibrationEffect.createWaveform(longs(command.getJSONArray("timings")), amplitudes, -1);
        }
        long[] timings = longs(command.getJSONArray("onOffTimings"));
        boolean nonzero = false;
        for (int i = 1; i < timings.length; i += 2) nonzero |= timings[i] > 0;
        return nonzero ? VibrationEffect.createWaveform(timings, -1) : null;
    }

    public boolean play(String key) throws Exception {
        long start = System.nanoTime();
        lastVibrateNs = 0;
        if (!cache.containsKey(key)) throw new IllegalArgumentException("Unprepared key " + key);
        boolean played = vibrate(cache.get(key));
        if (played) lastVibrateNs = System.nanoTime() - start;
        return played;
    }
    public boolean playOnce(String commandJson) throws Exception {
        String key = "__once";
        prepare(key, commandJson);
        try { return play(key); } finally { cache.remove(key); }
    }
    private boolean vibrate(VibrationEffect effect) throws Exception {
        if (effect == null || vibrator == null) return false;
        if (vibrationAttributes != null) {
            vibrateWithAttributes.invoke(vibrator, effect, vibrationAttributes);
        } else { vibrator.vibrate(effect, audioAttributes); }
        return true;
    }
    public long getLastVibrateNs() { return lastVibrateNs; }
    public String getLastStatus() { return lastStatus; }
    public void cancel() { if (vibrator != null) vibrator.cancel(); }
    public void clearCache() { cache.clear(); }
}
