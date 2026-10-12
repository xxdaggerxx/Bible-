package com.biblestudy.app.data

import android.content.Context
import com.biblestudy.app.platform.AndroidAppContext
import okio.Path.Companion.toOkioPath
import okio.source

/** The data classes made straight from an Android [Context] (as the Android tests do). */
fun UserDb(context: Context, name: String = UserDb.NAME) = UserDb(AndroidAppContext(context), name)

fun BibleRepository(context: Context, version: BibleVersion) = BibleRepository(AndroidAppContext(context), version)

fun StudyRepository(context: Context) = StudyRepository(AndroidAppContext(context))

fun SyncEngine(user: UserDb, store: SyncStore, device: String, imagesDir: java.io.File) =
    SyncEngine(user, store, device, imagesDir.toOkioPath())

fun BookIntros.get(context: Context, book: Int) = get(AndroidAppContext(context), book)

fun Commentaries.chapter(context: Context, id: String, book: Int, chapter: Int) = chapter(AndroidAppContext(context), id, book, chapter)

fun BibleImport.parseOsis(input: java.io.InputStream) = parseOsis(input.source())

fun BibleImport.save(
    context: Context, parsed: BibleImport.Parsed, code: String, name: String, copyright: String,
    kjvBooks: List<com.biblestudy.app.model.BookInfo>, study: (com.biblestudy.app.platform.SqlDb, BibleImport.Parsed?) -> Unit = { _, _ -> },
) = save(AndroidAppContext(context), parsed, code, name, copyright, kjvBooks, study)
