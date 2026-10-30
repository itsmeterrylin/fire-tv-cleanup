package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Current conditions for the greeting line. Location is resolved once (from config.xml if set, else
 * from the network's approximate location) and cached; the forecast itself is Open-Meteo, which needs
 * no key. Everything runs off the main thread and fails silently: no weather is better than a crash.
 */
final class WeatherLoader {

    private static final String TAG = "Weather";
    private static final String PREFS = "weather";
    private static final int TIMEOUT_MS = 8000;

    interface Callback {
        void onWeather(int weatherCode, int temp, boolean fahrenheit);
    }

    private WeatherLoader() {
    }

    static void load(Context ctx, Callback cb) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                double lat, lon;
                boolean fahrenheit;
                if (p.contains("lat")) {
                    lat = Double.longBitsToDouble(p.getLong("lat", 0));
                    lon = Double.longBitsToDouble(p.getLong("lon", 0));
                    fahrenheit = p.getBoolean("fahrenheit", false);
                } else {
                    String cfgLat = app.getString(R.string.weather_latitude).trim();
                    String cfgLon = app.getString(R.string.weather_longitude).trim();
                    String units = app.getString(R.string.weather_units).trim();
                    if (!cfgLat.isEmpty() && !cfgLon.isEmpty()) {
                        lat = Double.parseDouble(cfgLat);
                        lon = Double.parseDouble(cfgLon);
                        fahrenheit = "f".equalsIgnoreCase(units) || "fahrenheit".equalsIgnoreCase(units);
                    } else {
                        JSONObject loc = getJson("https://get.geojs.io/v1/ip/geo.json");
                        lat = loc.getDouble("latitude");
                        lon = loc.getDouble("longitude");
                        String country = loc.optString("country_code", "");
                        fahrenheit = "auto".equalsIgnoreCase(units)
                                ? "US".equals(country) || "LR".equals(country) || "MM".equals(country)
                                : "f".equalsIgnoreCase(units);
                    }
                    p.edit()
                            .putLong("lat", Double.doubleToRawLongBits(lat))
                            .putLong("lon", Double.doubleToRawLongBits(lon))
                            .putBoolean("fahrenheit", fahrenheit)
                            .apply();
                }

                String url = String.format(java.util.Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code&timezone=auto&temperature_unit=%s",
                        lat, lon, fahrenheit ? "fahrenheit" : "celsius");
                JSONObject current = getJson(url).getJSONObject("current");
                final int code = current.optInt("weather_code", -1);
                final int temp = (int) Math.round(current.getDouble("temperature_2m"));
                final boolean f = fahrenheit;
                new Handler(Looper.getMainLooper()).post(() -> cb.onWeather(code, temp, f));
            } catch (Exception e) {
                Log.w(TAG, "weather unavailable: " + e);
            }
        }, "weather").start();
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return new JSONObject(sb.toString());
            }
        } finally {
            c.disconnect();
        }
    }
}
