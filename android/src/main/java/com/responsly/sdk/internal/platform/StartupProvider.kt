package com.responsly.sdk.internal.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.responsly.sdk.Responsly

/**
 * Internal. Runs when the app's process starts, before any activity exists, and does one thing:
 * it lets the SDK watch the activity lifecycle. That is how a survey finds the activity to appear
 * above, however late `Responsly.initialize` is called and whatever context it is given.
 *
 * It holds no data and answers no queries.
 */
internal class StartupProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(Responsly::attach)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
