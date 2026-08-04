/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities.prefs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.collection.ArrayMap;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.model.CaptureList;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.SettingsBackup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsBackupHandler {
    private static final String TAG = "SettingsBackupHandler";
    private static final String EXPORT_FILE_NAME = "PCAPdroid_settings.json";
    private static final int PERSIST_MODE = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private final Fragment mFragment;
    private final Context mAppContext;
    private final ActivityResultLauncher<Intent> mExportLauncher;
    private final ActivityResultLauncher<Intent> mImportLauncher;
    private final ActivityResultLauncher<Intent> mFolderLauncher;
    private CaptureList mCaptureList;
    private List<CaptureList.Capture> mUnresolvedCaptures;
    private boolean mLicenseWarning;
    private int mCapturesRecovered;
    private int mCapturesRestored = -1;

    public SettingsBackupHandler(Fragment fragment) {
        mFragment = fragment;
        mAppContext = fragment.requireContext().getApplicationContext();
        mExportLauncher = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::exportResult);
        mImportLauncher = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::importResult);
        mFolderLauncher = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::folderResult);
    }

    public void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, EXPORT_FILE_NAME);

        Utils.launchFileDialog(mFragment.requireContext(), intent, mExportLauncher);
    }

    private void exportResult(final ActivityResult result) {
        if ((result.getResultCode() != Activity.RESULT_OK) || (result.getData() == null))
            return;

        Context context = mFragment.requireContext();
        String data = SettingsBackup.serialize(PreferenceManager.getDefaultSharedPreferences(context));

        try (OutputStream out = context.getContentResolver().openOutputStream(
                Objects.requireNonNull(result.getData().getData()), "rwt"))
        {
            try (OutputStreamWriter writer = new OutputStreamWriter(out)) {
                writer.write(data);
            }

            Utils.showToast(context, R.string.save_ok);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "exportResult: " + e.getMessage());
            Utils.showToastLong(context, R.string.export_failed);
        }
    }

    public void startImport() {
        Context context = mFragment.requireContext();

        if (CaptureService.isServiceActive()) {
            Log.w(TAG, "Stop the capture before importing the settings");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        Utils.launchFileDialog(context, intent, mImportLauncher);
    }

    private void importResult(final ActivityResult result) {
        if ((result.getResultCode() != Activity.RESULT_OK) || (result.getData() == null))
            return;

        Context context = mFragment.requireContext();
        SettingsBackup backup;

        try (InputStream in = context.getContentResolver().openInputStream(
                Objects.requireNonNull(result.getData().getData())))
        {
            try (Scanner s = new Scanner(in).useDelimiter("\\A")) {
                backup = SettingsBackup.fromJson(s.hasNext() ? s.next() : "");
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "importResult: " + e.getMessage());
            Utils.showToastLong(context, R.string.import_failed);
            return;
        }

        if (backup == null) {
            Utils.showToastLong(context, R.string.invalid_backup);
            return;
        }

        String date = Utils.formatEpochFull(context, backup.getCreationTime() / 1000);

        new AlertDialog.Builder(context)
                .setTitle(R.string.import_settings)
                .setMessage(mFragment.getString(R.string.import_settings_confirm, date))
                .setPositiveButton(R.string.import_action, (dialog, which) -> doImport(backup))
                .setNegativeButton(R.string.cancel_action, (dialog, which) -> {})
                .show();
    }

    private void doImport(SettingsBackup backup) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);

        String localLicense = prefs.getString(SettingsBackup.LICENSE_KEY, "");

        backup.apply(prefs);
        mLicenseWarning = checkImportedLicense(prefs, localLicense);

        importCaptureList(backup);
    }

    /* The license is bound to the installation id, which changes with the device and with the app
     * signature, so an imported license is usually not valid anymore. When it cannot be used, the
     * license which was already on this device is restored, as the import must not take away an
     * entitlement of this installation. The play build never keeps a license, as the purchases are
     * re-validated via the billing library.
     * Returns true if the user must be warned about the paid features. */
    @SuppressLint("ApplySharedPref")
    private boolean checkImportedLicense(SharedPreferences prefs, String localLicense) {
        // called after the import took place
        Billing billing = Billing.newInstance(mAppContext);

        String imported = billing.getLicense();
        if (imported.isEmpty() && localLicense.isEmpty())
            return false;

        boolean playStore = billing.isPlayStore();
        if (!playStore && billing.isValidLicense(imported))
            return false;

        boolean restore_needed = !playStore && billing.isValidLicense(localLicense);
        SharedPreferences.Editor editor = prefs.edit();

        if (restore_needed) {
            Log.i(TAG, "restoring the license of this installation");
            editor.putString(SettingsBackup.LICENSE_KEY, localLicense);
        } else {
            Log.i(TAG, "dropping the imported license (playStore=" + playStore + ")");
            editor.remove(SettingsBackup.LICENSE_KEY);
        }

        editor.commit();

        return !playStore && !restore_needed && !imported.isEmpty();
    }

    private record CaptureCheck(ArrayMap<CaptureList.Capture, String> recovered,
                                ArrayList<CaptureList.Capture> unresolved) {}

    private void importCaptureList(SettingsBackup backup) {
        mCaptureList = new CaptureList(mAppContext);

        List<CaptureList.Capture> imported = CaptureList.parseList(backup.getString(Prefs.PREF_CAPTURE_LIST));
        if ((imported == null) || imported.isEmpty()) {
            finishImport();
            return;
        }

        // nothing to combine, or no way to ask: merging cannot lose any capture
        if ((mCaptureList.size() == 0) || !mFragment.isAdded()) {
            applyCaptureList(imported, false);
            return;
        }

        new AlertDialog.Builder(mFragment.requireContext())
                .setTitle(R.string.capture_list)
                .setMessage(mFragment.getString(R.string.import_capture_list_confirm,
                        imported.size(), mCaptureList.size()))
                .setPositiveButton(R.string.merge_action, (dialog, which) -> applyCaptureList(imported, false))
                .setNeutralButton(R.string.replace_action, (dialog, which) -> applyCaptureList(imported, true))
                .setNegativeButton(R.string.keep_current_action, (dialog, which) -> finishImport())
                .setCancelable(false)
                .show();
    }

    private void applyCaptureList(List<CaptureList.Capture> imported, boolean replace) {
        List<CaptureList.Capture> added = replace ? mCaptureList.replace(imported)
                : mCaptureList.merge(imported);

        checkImportedCaptures(added);
    }

    private void checkImportedCaptures(List<CaptureList.Capture> imported) {
        if (imported.isEmpty()) {
            finishImport();
            return;
        }

        // resolving the URIs performs IO and IPC, keep it off the UI thread
        ArrayList<CaptureList.Capture> captures = new ArrayList<>(imported);
        Handler handler = new Handler(Looper.getMainLooper());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> {
            CaptureCheck check = checkCaptures(captures);
            handler.post(() -> onCapturesChecked(check));
        });
        executor.shutdown();
    }

    private CaptureCheck checkCaptures(List<CaptureList.Capture> captures) {
        ArrayMap<CaptureList.Capture, String> recovered = new ArrayMap<>();
        ArrayList<CaptureList.Capture> unresolved = new ArrayList<>();

        for (CaptureList.Capture c: captures) {
            boolean accessible;

            try {
                accessible = isAccessible(mAppContext, Uri.parse(c.uri));
            } catch (Exception e) {
                Log.w(TAG, "check failed for " + c.uri + ": " + e.getMessage());
                accessible = false;
            }

            if (!accessible)
                unresolved.add(c);
        }

        if (!unresolved.isEmpty())
            autoRecoverCaptures(unresolved, recovered);

        return new CaptureCheck(recovered, unresolved);
    }

    private static boolean isAccessible(Context ctx, Uri uri) {
        String path = Utils.uriToFilePath(ctx, uri);
        if ((path != null) && new File(path).canRead())
            return true;

        return Utils.isUriReadable(ctx, uri);
    }

    /* Try to resolve the captures without involving the user. A capture is only recovered when the
     * new URI provably points to the same file, so that this can safely run unattended. */
    private void autoRecoverCaptures(List<CaptureList.Capture> unresolved,
                                     Map<CaptureList.Capture, String> recovered) {
        List<Uri> trees = getPersistedTrees();
        Iterator<CaptureList.Capture> it = unresolved.iterator();

        while (it.hasNext()) {
            CaptureList.Capture c = it.next();
            Uri uri;

            try {
                uri = recoverCapture(c, trees);
            } catch (Exception e) {
                Log.w(TAG, "recover failed for " + c.uri + ": " + e.getMessage());
                uri = null;
            }

            if (uri != null) {
                recovered.put(c, uri.toString());
                it.remove();
            }
        }
    }

    private @Nullable Uri recoverCapture(CaptureList.Capture c, List<Uri> trees) {
        Uri oldUri = Uri.parse(c.uri);

        if (Utils.isExternalStorageDocument(oldUri)) {
            String docId = DocumentsContract.getDocumentId(oldUri);

            for (Uri treeUri: trees) {
                // a tree grant only covers its own subtree, a document outside it cannot be opened
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);

                if (Utils.isUriReadable(mAppContext, uri))
                    return uri;
            }
        } else if (Utils.isMediaStoreUri(oldUri)) {
            // the capture was written by getDownloadsUri, so it can only be in the downloads folder
            Uri uri = Utils.findDownloadsUri(mAppContext, c.name);

            if ((uri != null) && Utils.isUriReadable(mAppContext, uri))
                return uri;
        }

        return null;
    }

    private List<Uri> getPersistedTrees() {
        ArrayList<Uri> trees = new ArrayList<>();

        for (UriPermission perm: mAppContext.getContentResolver().getPersistedUriPermissions()) {
            Uri uri = perm.getUri();

            if (perm.isReadPermission() && Utils.isTreeUri(uri) && Utils.isExternalStorageDocument(uri))
                trees.add(uri);
        }

        return trees;
    }

    private void onCapturesChecked(CaptureCheck check) {
        mCapturesRecovered = check.recovered.size();

        if (mCapturesRecovered > 0) {
            Log.i(TAG, "auto-recovered " + mCapturesRecovered + " captures");
            mCaptureList.relocate(check.recovered, Collections.emptyList());
        }

        if (check.unresolved.isEmpty()) {
            finishImport();
            return;
        }

        mUnresolvedCaptures = check.unresolved;

        if (!mFragment.isAdded()) {
            skipCaptures();
            return;
        }

        new AlertDialog.Builder(mFragment.requireContext())
                .setTitle(R.string.capture_list)
                .setMessage(mFragment.getString(R.string.captures_not_accessible, check.unresolved.size()))
                .setPositiveButton(R.string.select_folder, (dialog, which) -> selectCapturesFolder())
                .setNegativeButton(R.string.skip_action, (dialog, which) -> skipCaptures())
                .setOnCancelListener(dialog -> skipCaptures())
                .show();
    }

    private void skipCaptures() {
        Log.i(TAG, "dropping " + mUnresolvedCaptures.size() + " inaccessible captures");
        mCaptureList.remove(mUnresolvedCaptures);
        mCapturesRestored = mCapturesRecovered;

        finishImport();
    }

    private void selectCapturesFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri initial = getParentDocumentUri(mUnresolvedCaptures.get(0).uri);
            if (initial == null)
                initial = Utils.getDownloadsFolderDocumentUri();

            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }

        if (!Utils.launchFileDialog(mFragment.requireContext(), intent, mFolderLauncher))
            skipCaptures();
    }

    private void folderResult(final ActivityResult result) {
        if (mUnresolvedCaptures == null) {
            // NOTE: the state is lost if the fragment is recreated while the picker is open;
            // the preferences are already imported, so just go on with the restart
            finishImport();
            return;
        }

        if ((result.getResultCode() != Activity.RESULT_OK) || (result.getData() == null)
                || (result.getData().getData() == null)) {
            skipCaptures();
            return;
        }

        Uri treeUri = result.getData().getData();

        try {
            mAppContext.getContentResolver().takePersistableUriPermission(treeUri, PERSIST_MODE);
        } catch (SecurityException e) {
            Log.w(TAG, "could not persist the permission for " + treeUri + ": " + e.getMessage());
        }

        relocateCaptures(treeUri);
        finishImport();
    }

    /* The stored URIs were granted to the previous installation, so they cannot be used anymore.
     * The tree-based form of the same documents, instead, is covered by the new folder grant. */
    private void relocateCaptures(Uri treeUri) {
        ArrayList<CaptureList.Capture> toDrop = new ArrayList<>(mUnresolvedCaptures);
        ArrayMap<CaptureList.Capture, String> newUris = findInFolder(mAppContext, treeUri, toDrop);
        toDrop.removeAll(newUris.keySet());

        Log.i(TAG, "relocated " + newUris.size() + " captures, dropped " + toDrop.size());
        mCaptureList.relocate(newUris, toDrop);
        mCapturesRestored = mCapturesRecovered + newUris.size();
    }

    /* Searches the given captures in the folder, matching them by file name.
     * Returns the captures which were found, mapped to their new tree-based URI. */
    private static ArrayMap<CaptureList.Capture, String> findInFolder(Context ctx, Uri treeUri,
                                                                         List<CaptureList.Capture> captures) {
        ArrayMap<CaptureList.Capture, String> resolved = new ArrayMap<>();

        Map<String, String> children = listChildren(ctx, treeUri);
        if (children.isEmpty())
            return resolved;

        for (CaptureList.Capture c: captures) {
            String docId = children.get(getFileName(c.uri));
            if (docId == null)
                docId = children.get(c.name);

            if (docId != null)
                resolved.put(c, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString());
        }

        return resolved;
    }

    // Maps the display name of the documents in the folder to their document id
    private static Map<String, String> listChildren(Context ctx, Uri treeUri) {
        HashMap<String, String> rv = new HashMap<>();
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME};

        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));

            try (Cursor cursor = ctx.getContentResolver().query(childrenUri, projection, null, null, null)) {
                while ((cursor != null) && cursor.moveToNext())
                    rv.put(cursor.getString(1), cursor.getString(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "listChildren: " + e.getMessage());
        }

        return rv;
    }

    private static @Nullable Uri getParentDocumentUri(String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            if (!Utils.isExternalStorageDocument(uri))
                return null;

            String docId = DocumentsContract.getDocumentId(uri);
            int sep = docId.lastIndexOf('/');
            if (sep < 0)
                return null;

            return DocumentsContract.buildDocumentUri(uri.getAuthority(), docId.substring(0, sep));
        } catch (Exception e) {
            return null;
        }
    }

    private static @NonNull String getFileName(String uriStr) {
        String path = (uriStr != null) ? Uri.parse(uriStr).getPath() : null;
        if (path == null)
            return "";

        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        return (sep >= 0) ? path.substring(sep + 1) : path;
    }

    private void finishImport() {
        if (!mFragment.isAdded()) {
            restartApp();
            return;
        }

        StringBuilder msg = new StringBuilder(mFragment.getString(R.string.settings_imported));

        if (mCapturesRestored >= 0) {
            int inaccessible = mCapturesRecovered + mUnresolvedCaptures.size();

            msg.append("\n\n").append(mFragment.getString(R.string.captures_restored, mCapturesRestored,
                    inaccessible - mCapturesRestored));
        }

        if (mLicenseWarning)
            msg.append("\n\n").append(mFragment.getString(R.string.imported_license_invalid));

        new AlertDialog.Builder(mFragment.requireContext())
                .setTitle(R.string.import_settings)
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (dialog, which) -> restartApp())
                .show();
    }

    // Many settings are cached in memory (e.g. the firewall rules, the billing state, the locale),
    // so the app is restarted to consistently apply the imported configuration
    private void restartApp() {
        if (mCaptureList != null)
            mCaptureList.saveNow();

        Intent intent = new Intent(mAppContext, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mAppContext.startActivity(intent);

        Runtime.getRuntime().exit(0);
    }
}
