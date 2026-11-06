package com.hajala.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves Studio files from private storage to other apps (e.g. to open a PDF in a viewer).
 * Not exported: access only through temporary Uri grants given by the app.
 * Uri form: content://com.hajala.app.studiofiles/f/&lt;item id&gt;
 */
public final class StudioFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.hajala.app.studiofiles";

    static Uri uriFor(StudioStore.Item it) {
        return Uri.parse("content://" + AUTHORITY + "/f/" + it.id);
    }

    private StudioStore.Item resolve(Uri uri) {
        if (getContext() == null) return null;
        String id = uri.getLastPathSegment();
        if (id == null) return null;
        return new StudioStore(getContext()).item(id);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        StudioStore.Item it = resolve(uri);
        if (it == null || getContext() == null) throw new FileNotFoundException("unknown item");
        File f = new StudioStore(getContext()).fileOf(it);
        if (!f.exists()) throw new FileNotFoundException("missing file");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        StudioStore.Item it = resolve(uri);
        String[] cols = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        if (it == null) return c;
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = it.name;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = it.size;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        StudioStore.Item it = resolve(uri);
        return it == null || it.mime.isEmpty() ? "application/octet-stream" : it.mime;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
