package io.github.luyzfernando08.sleeptimer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * When the running timer ends, for the cleanyourtv launcher's status pill next to its clock: one row with "end",
 * in wall-clock millis, or 0 with no timer. Read only; it says nothing else about the TV.
 */
public class StateProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"end"});
        cursor.addRow(new Object[]{Timer.end(getContext())});
        return cursor;
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
