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
}
