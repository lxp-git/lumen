package dev.lumen.inspector.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.util.Locale

/**
 * Lists the app's SQLite databases ([Context.databaseList], which includes Room) and runs
 * statements against them. Every call opens and closes its own connection so Lumen never
 * holds a database open behind the app's back.
 */
class SqliteDatabaseDriver(private val context: Context) {

  /** Database files, sorted, with `-journal` / `-wal` / … shadows of a listed file removed. */
  fun getDatabaseFiles(): List<File> =
    tidyDatabaseList(context.databaseList().map(context::getDatabasePath).sorted())

  @Throws(SQLiteException::class)
  fun getTableNames(databaseFile: File): List<String> = openDatabase(databaseFile).use { db ->
    db.rawQuery("SELECT name FROM sqlite_master WHERE type IN (?, ?)", arrayOf("table", "view"))
      .use { cursor ->
        val names = ArrayList<String>()
        while (cursor.moveToNext()) names.add(cursor.getString(0))
        names
      }
  }

  @Throws(SQLiteException::class)
  fun <T> executeSQL(databaseFile: File, query: String, handler: ExecuteResultHandler<T>): T =
    openDatabase(databaseFile).use { db ->
      when (firstKeyword(query)) {
        "UPDATE", "DELETE" ->
          handler.handleUpdateDelete(db.compileStatement(query).executeUpdateDelete())
        "INSERT", "REPLACE" -> handler.handleInsert(db.compileStatement(query).executeInsert())
        "SELECT", "PRAGMA", "EXPLAIN", "WITH", "VALUES" ->
          db.rawQuery(query, null).use(handler::handleSelect)
        else -> {
          db.execSQL(query)
          handler.handleRawQuery()
        }
      }
    }

  interface ExecuteResultHandler<RESULT> {
    @Throws(SQLiteException::class)
    fun handleRawQuery(): RESULT

    @Throws(SQLiteException::class)
    fun handleSelect(result: Cursor): RESULT

    @Throws(SQLiteException::class)
    fun handleInsert(insertedId: Long): RESULT

    @Throws(SQLiteException::class)
    fun handleUpdateDelete(count: Int): RESULT
  }

  internal companion object {
    private val SHADOW_SUFFIXES = listOf("-journal", "-shm", "-uid", "-wal")
    private val LEADING_COMMENTS = Regex("""^(\s+|--[^\n]*\n?|/\*.*?\*/)*""", RegexOption.DOT_MATCHES_ALL)

    /** First keyword, past whitespace (including newlines) and SQL comments. */
    fun firstKeyword(query: String): String =
      query.replaceFirst(LEADING_COMMENTS, "").takeWhile { it.isLetter() }.uppercase(Locale.ROOT)

    /** Drops a file only when it shadows another listed file that lacks the suffix. */
    fun tidyDatabaseList(files: List<File>): List<File> {
      val all = files.toSet()
      return files.filter { file ->
        val path = file.path
        val suffix = SHADOW_SUFFIXES.firstOrNull(path::endsWith) ?: return@filter true
        File(path.removeSuffix(suffix)) !in all
      }
    }

    private fun openDatabase(file: File): SQLiteDatabase {
      var flags = SQLiteDatabase.OPEN_READWRITE
      // A -wal sidecar means the app opened it in WAL mode; match it or the open fails / blocks.
      if (File(file.parent, file.name + "-wal").exists()) {
        flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
      }
      return SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
    }
  }
}
