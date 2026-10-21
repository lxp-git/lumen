package dev.lumen.inspector.database

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SqliteDatabaseDriverTest {
  @Test
  fun dropsShadowFilesOfListedDatabases() {
    val files = listOf("app.db", "app.db-journal", "app.db-wal", "app.db-shm", "orphan.db-wal")
      .map(::File)
    assertEquals(
      listOf("app.db", "orphan.db-wal"),
      SqliteDatabaseDriver.tidyDatabaseList(files).map { it.path },
    )
  }

  @Test
  fun routesByFirstKeywordPastWhitespaceAndComments() {
    assertEquals("SELECT", SqliteDatabaseDriver.firstKeyword("select\n*\nfrom t"))
    assertEquals("WITH", SqliteDatabaseDriver.firstKeyword("  WITH x AS (SELECT 1) SELECT * FROM x"))
    assertEquals("UPDATE", SqliteDatabaseDriver.firstKeyword("-- bump\nupdate t set a = 1"))
    assertEquals("DELETE", SqliteDatabaseDriver.firstKeyword("/* old */ DELETE FROM t"))
    assertEquals("PRAGMA", SqliteDatabaseDriver.firstKeyword("PRAGMA(table_info)"))
  }
}
