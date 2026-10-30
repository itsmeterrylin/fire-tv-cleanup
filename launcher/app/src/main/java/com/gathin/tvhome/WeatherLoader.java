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
import java.util.Locale;

/**
 * Current conditions plus today's high and low, for the greeting line and the screensaver.
 * Location is resolved once (from config.xml if set, else from the network's approximate location) and
 * cached; the forecast itself is Open-Meteo, which needs no key. Runs off the main thread and fails
 * silently: no weather is better than a crash.
 */
final class WeatherLoader {

    private static final String TAG = "Weather";
    private static final String PREFS = "weather";
    private static final int TIMEOUT_MS = 8000;

    static final class Forecast {
        final int code;
        final int temp;
        final int high;
        final int low;
        final boolean fahrenheit;

        Forecast(int code, int temp, int high, int low, boolean fahrenheit) {
            this.code = code;
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.fahrenheit = fahrenheit;
        }

        String degrees(int value) {
            return String.format(Locale.getDefault(), "%d°", value);
        }
    }

    interface Callback {
        void onForecast(Forecast forecast);
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

                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code"
                                + "&daily=temperature_2m_max,temperature_2m_min"
                                + "&forecast_days=1&timezone=auto&temperature_unit=%s",
                        lat, lon, fahrenheit ? "fahrenheit" : "celsius");
                JSONObject root = getJson(url);
                JSONObject current = root.getJSONObject("current");
                JSONObject daily = root.getJSONObject("daily");
                final Forecast forecast = new Forecast(
                        current.optInt("weather_code", -1),
                        (int) Math.round(current.getDouble("temperature_2m")),
                        (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                        (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                        fahrenheit);
                new Handler(Looper.getMainLooper()).post(() -> cb.onForecast(forecast));
            } catch (Exception e) {
                Log.w(TAG, "weather unavailable: " + e);
            }
        }, "weather").start();
    }

    /** Drawable for an Open-Meteo weather code. */
    static int iconRes(int code) {
        if (code == 0) return R.drawable.ic_wx_sun;
        if (code == 1 || code == 2) return R.drawable.ic_wx_partly;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if (code >= 51 && code <= 67) return R.drawable.ic_wx_rain;
        if (code >= 71 && code <= 77) return R.drawable.ic_wx_snow;
        if (code >= 80 && code <= 82) return R.drawable.ic_wx_rain;
        if (code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        return R.drawable.ic_wx_cloud;
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
