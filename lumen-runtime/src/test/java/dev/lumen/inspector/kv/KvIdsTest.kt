package dev.lumen.inspector.kv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KvIdsTest {

  @Test
  fun sharedPreferencesOriginIsRawName() {
    val ref = KvIds.StoreRef(KvIds.Kind.SHARED_PREFERENCES, "settings")
    assertEquals("settings", ref.origin)
    assertEquals(KvIds.DB_SHARED_PREFERENCES, ref.databaseName)
    assertEquals(ref, KvIds.parseOrigin("settings"))
  }

  @Test
  fun dataStoreAndMmkvOriginsArePrefixed() {
    val ds = KvIds.StoreRef(KvIds.Kind.DATASTORE, "user")
    assertEquals("datastore:user", ds.origin)
    assertEquals(ds, KvIds.parseOrigin("datastore:user"))

    val mmkv = KvIds.StoreRef(KvIds.Kind.MMKV, "mmkv.default")
    assertEquals("mmkv:mmkv.default", mmkv.origin)
    assertEquals(mmkv, KvIds.parseOrigin("mmkv:mmkv.default"))
  }

  @Test
  fun urlLikeOriginsAreNotPrefsFiles() {
    assertNull(KvIds.parseOrigin(""))
    assertNull(KvIds.parseOrigin("lumen-default"))
    assertNull(KvIds.parseOrigin("lumen://dev.lumen.sample"))
    assertNull(KvIds.parseOrigin("https://example.com"))
    assertNull(KvIds.parseOrigin("datastore:"))
    assertNull(KvIds.parseOrigin("mmkv:"))
  }

  @Test
  fun kindForDatabase() {
    assertEquals(KvIds.Kind.SHARED_PREFERENCES, KvIds.kindForDatabase("SharedPreferences"))
    assertEquals(KvIds.Kind.DATASTORE, KvIds.kindForDatabase("DataStore"))
    assertEquals(KvIds.Kind.MMKV, KvIds.kindForDatabase("MMKV"))
    assertNull(KvIds.kindForDatabase("WebSQL"))
  }

  @Test
  fun combinedOriginIsNotAStore() {
    assertTrue(KvIds.isCombinedOrigin(null))
    assertTrue(KvIds.isCombinedOrigin(""))
    assertTrue(KvIds.isCombinedOrigin("lumen-default"))
    assertTrue(KvIds.isCombinedOrigin("lumen://dev.lumen.sample"))
    assertFalse(KvIds.isCombinedOrigin("settings"))
    assertFalse(KvIds.isCombinedOrigin("datastore:user"))
    assertFalse(KvIds.isCombinedOrigin("mmkv:default"))
    assertFalse(KvIds.isCombinedOrigin("datastore:"))
  }

  @Test
  fun combinedKeyMatchesLocalStorageRows() {
    assertEquals(
      "SharedPreferences / lumen_sample / theme",
      KvIds.combinedKey(KvIds.Kind.SHARED_PREFERENCES, "lumen_sample", "theme"),
    )
    val ref = KvIds.StoreRef(KvIds.Kind.DATASTORE, "user")
    assertEquals("DataStore / user / ", KvIds.combinedPrefix(ref))
  }
}
