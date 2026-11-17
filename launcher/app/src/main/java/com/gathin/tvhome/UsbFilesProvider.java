package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Hands Just Player a USB file it can't open by path: subtitles beside a film (Android's media index has no link
 * for .srt files) and videos the index hasn't reached yet. Read-only, only files on removable drives under
 * /storage (never the TV's own storage), only video and subtitle files, and not exported: Just Player gets a
 * one-off read grant for the exact files of what you press OK on.
 */
public class UsbFilesProvider extends ContentProvider {

    static final String AUTHORITY = "com.gathin.tvhome.usbfiles";

    static Uri uriFor(String path) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(path).build();
    }

    /**
     * A subtitle link whose name carries a language ("Film.srt" as ".../Film.en.srt"), which is how Just Player
     * learns a subtitle's language. The provider maps it back to the real file (see fileOf).
     */
    static Uri uriFor(String path, String lang) {
        if (lang == null) return uriFor(path);
        int dot = path.lastIndexOf('.');
        return uriFor(path.substring(0, dot) + "." + lang + path.substring(dot));
    }

    /** "…/Film.en.srt" that doesn't exist -> "…/Film.srt" (a link made by uriFor(path, lang)). Subtitles only. */
    private static final java.util.regex.Pattern ADDED_LANG =
            java.util.regex.Pattern.compile("(?i)\\.[a-z]{2,3}(\\.(srt|ass|ssa|vtt))$");

    @Override
    public boolean onCreate() {
        return true;
    }

    private static File fileOf(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) throw new FileNotFoundException();
        if (!new File(path).exists()) path = ADDED_LANG.matcher(path).replaceFirst("$1");
        File f;
        try {
            f = new File(path).getCanonicalFile();
        } catch (IOException e) {
            throw new FileNotFoundException(path);
        }
        String p = f.getPath();
        boolean onUsb = p.startsWith("/storage/") && !p.startsWith("/storage/emulated/") && !p.startsWith("/storage/self/");
        boolean allowed = UsbLibrary.hasExt(f.getName(), UsbLibrary.SUB_EXT) || UsbLibrary.hasExt(f.getName(), UsbLibrary.VIDEO_EXT);
        if (!onUsb || !allowed || !f.isFile()) throw new FileNotFoundException(p);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".srt")) return "application/x-subrip";
        if (name.endsWith(".vtt")) return "text/vtt";
        if (name.endsWith(".ass") || name.endsWith(".ssa")) return "text/x-ssa";
        String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(new File(path).getName()));
        String type = ext != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT)) : null;
        return type != null ? type : "video/*";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileOf(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
