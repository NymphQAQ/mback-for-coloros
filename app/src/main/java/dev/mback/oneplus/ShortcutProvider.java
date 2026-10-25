package dev.mback.oneplus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;

/** Read-only custom icons, restricted to MBack and SystemUI. */
public final class ShortcutProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String name : packages) if ("com.android.systemui".equals(name)) return;
        throw new SecurityException("Only MBack and SystemUI can access shortcuts");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2
                || !"icons".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("Unknown icon");
        String key = uri.getLastPathSegment();
        if (!ShortcutIcons.validKey(key)) throw new FileNotFoundException("Invalid icon");
        return ParcelFileDescriptor.open(ShortcutIcons.file(getContext(), key), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        throw new UnsupportedOperationException("Read-only icon provider");
    }

    @Override public String getType(Uri uri) { checkCaller(); return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        checkCaller(); return null;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
