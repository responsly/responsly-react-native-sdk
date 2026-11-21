package com.responsly.sdk.internal.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import com.responsly.sdk.internal.Logger
import java.util.Locale

/**
 * File inputs of a survey page (photo and file upload questions).
 *
 * A dialog cannot receive an activity result, and the SDK must not ask the host activity to
 * forward one. So the system document picker is started from [FileChooserActivity], a transparent
 * activity that exists only for the time the picker is open. Main thread only.
 */
internal object FileChooserBroker {
    class Request(
        val owner: Any,
        val intent: Intent,
        val callback: ValueCallback<Array<Uri>>,
        val grants: UploadGrants,
    )

    var current: Request? = null
        private set

    /** Returns false (and leaves [callback] untouched) when the picker cannot be opened. */
    fun open(
        activity: Activity,
        owner: Any,
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
        grants: UploadGrants,
        logger: Logger,
    ): Boolean {
        // One picker at a time: a newer request cancels the one before it.
        current?.callback?.onReceiveValue(null)
        val request = Request(owner, pickerIntent(params), callback, grants)
        current = request
        return try {
            activity.startActivity(Intent(activity, FileChooserActivity::class.java))
            true
        } catch (e: Exception) {
            current = null
            logger.error("Cannot open the file picker; is FileChooserActivity missing from the merged manifest?", e)
            false
        }
    }

    /** Delivers the result of [request]; a stale request is ignored. */
    fun complete(request: Request, uris: Array<Uri>?) {
        if (current !== request) return
        current = null
        request.callback.onReceiveValue(uris)
    }

    /** The survey went away while the picker was open. */
    fun cancel(owner: Any) {
        val request = current ?: return
        if (request.owner === owner) complete(request, null)
    }

    /**
     * `ACTION_OPEN_DOCUMENT` rather than `GET_CONTENT`: its grants can be persisted, which keeps
     * the files readable after [FileChooserActivity] has finished (see [UploadGrants]).
     */
    private fun pickerIntent(params: WebChromeClient.FileChooserParams): Intent {
        val types = mimeTypes(params.acceptTypes)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = if (types.size == 1) types[0] else "*/*"
        if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        return intent
    }

    /** The `accept` attribute holds MIME types and file extensions; the picker wants MIME types. */
    private fun mimeTypes(accept: Array<String>?): List<String> {
        val result = LinkedHashSet<String>()
        for (entry in accept.orEmpty()) {
            for (part in entry.split(',')) {
                val token = part.trim().lowercase(Locale.ROOT)
                when {
                    token.isEmpty() -> Unit
                    token.startsWith(".") ->
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(token.substring(1))?.let(result::add)
                    token.contains('/') -> result.add(token)
                }
            }
        }
        return result.toList()
    }
}

/**
 * Read access to picked documents. A grant that arrives with an activity result ends when that
 * activity finishes, long before the page uploads the file, so the grant is persisted and released
 * when the survey closes. Grants left over by a killed process are released at the next launch.
 */
internal class UploadGrants(private val context: Context) {
    private val taken = ArrayList<Uri>()

    /** Reads the picked documents from a picker result and keeps them readable. */
    fun accept(data: Intent?): Array<Uri>? {
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
        }
        if (uris.isEmpty()) data?.data?.let(uris::add)
        uris.retainAll(::isPickedDocument)
        if (uris.isEmpty()) return null

        val resolver = context.contentResolver
        // A grant the app itself already persisted is not ours to release later.
        val existing = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        for (uri in uris) {
            if (uri in existing) continue
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                taken.add(uri)
            } catch (e: Exception) {
                // Not persistable; the upload works only while the temporary grant lasts.
            }
        }
        remember(context, taken)
        return uris.toTypedArray()
    }

    /**
     * A document from a provider, never a file path, and never one of the app's own providers:
     * the document picker has no reason to return those, and the page would upload the app's data.
     */
    private fun isPickedDocument(uri: Uri): Boolean {
        if (uri.scheme?.lowercase(Locale.ROOT) != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority?.takeIf { it.isNotEmpty() } ?: return false
        val provider = try {
            @Suppress("DEPRECATION")
            context.packageManager.resolveContentProvider(authority, 0)
        } catch (e: Exception) {
            null
        }
        return provider?.packageName != context.packageName
    }

    fun releaseAll() {
        if (taken.isEmpty()) return
        release(context, taken)
        taken.clear()
        remember(context, taken)
    }

    companion object {
        private const val PREFERENCES = "com.responsly.sdk.internal"
        private const val KEY_GRANTS = "upload_grants"

        /** Releases grants a previous process could not release. */
        fun releaseStale(context: Context) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val stale = preferences.getStringSet(KEY_GRANTS, null).orEmpty()
            if (stale.isEmpty()) return
            release(context, stale.map(Uri::parse))
            preferences.edit().remove(KEY_GRANTS).apply()
        }

        private fun release(context: Context, uris: List<Uri>) {
            for (uri in uris) {
                try {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Already gone.
                }
            }
        }

        private fun remember(context: Context, uris: List<Uri>) {
            val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            if (uris.isEmpty()) {
                editor.remove(KEY_GRANTS)
            } else {
                editor.putStringSet(KEY_GRANTS, uris.map { it.toString() }.toSet())
            }
            editor.apply()
        }
    }
}

/**
 * Internal. Opens the system document picker for a survey's file input and hands the result back
 * to the WebView. Transparent, never started by anything but the SDK.
 */
internal class FileChooserActivity : Activity() {
    private var request: FileChooserBroker.Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = FileChooserBroker.current
        this.request = request
        if (request == null) {
            // Recreated in a new process: there is no WebView left to answer.
            finish()
            return
        }
        // Recreated while the picker is open: its result is still on the way.
        if (savedInstanceState != null) return
        try {
            startActivityForResult(request.intent, REQUEST_CODE)
        } catch (e: Exception) {
            FileChooserBroker.complete(request, null)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        request?.let { request ->
            val uris = if (resultCode == RESULT_OK) request.grants.accept(data) else null
            FileChooserBroker.complete(request, uris)
        }
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST_CODE = 0x5253
    }
}
