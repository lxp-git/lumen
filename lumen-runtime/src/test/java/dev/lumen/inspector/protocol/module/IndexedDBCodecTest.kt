package dev.lumen.inspector.protocol.module

import dev.lumen.json.ObjectMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IndexedDBCodecTest {

  private val mapper = ObjectMapper()

  @Test
  fun databaseNamesPayload() {
    val json = mapper.convertValue(
      IndexedDB.DatabaseNamesResult(listOf("SharedPreferences", "DataStore", "MMKV")),
      JSONObject::class.java,
    )
    assertEquals(3, json.getJSONArray("databaseNames").length())
    assertEquals("SharedPreferences", json.getJSONArray("databaseNames").getString(0))
  }

  @Test
  fun dataEntryUsesPrimitiveRemoteObjects() {
    val entry = IndexedDB.DataEntry(
      key = IndexedDB.RemoteObject(type = "string", value = "theme", description = "theme"),
      primaryKey = IndexedDB.RemoteObject(type = "string", value = "theme", description = "theme"),
      value = IndexedDB.RemoteObject(type = "boolean", value = true, description = "true"),
    )
    val json = mapper.convertValue(
      IndexedDB.DataResult(listOf(entry), hasMore = false),
      JSONObject::class.java,
    )
    assertFalse(json.getBoolean("hasMore"))
    val row = json.getJSONArray("objectStoreDataEntries").getJSONObject(0)
    assertEquals("theme", row.getJSONObject("key").getString("value"))
    assertEquals(true, row.getJSONObject("value").getBoolean("value"))
    assertEquals("boolean", row.getJSONObject("value").getString("type"))
  }

  @Test
  fun numericValueSerializesAsJsonNumberNotArray() {
    val entry = IndexedDB.DataEntry(
      key = IndexedDB.RemoteObject(type = "string", value = "shown_count", description = "shown_count"),
      primaryKey = IndexedDB.RemoteObject(type = "string", value = "shown_count", description = "shown_count"),
      value = IndexedDB.RemoteObject(type = "number", value = 5, description = "5"),
    )
    val json = mapper.convertValue(
      IndexedDB.DataResult(listOf(entry), hasMore = false),
      JSONObject::class.java,
    )
    val value = json.getJSONArray("objectStoreDataEntries")
      .getJSONObject(0)
      .getJSONObject("value")
    assertEquals("number", value.getString("type"))
    assertEquals(5, value.getInt("value"))
    assertEquals("5", value.getString("description"))
  }

  @Test
  fun objectStoreKeyPathIsNullType() {
    val db = IndexedDB.DatabaseWithObjectStores(
      name = "SharedPreferences",
      version = 1.0,
      objectStores = listOf(
        IndexedDB.ObjectStore(
          name = "settings",
          keyPath = IndexedDB.KeyPath(type = "null"),
          autoIncrement = false,
          indexes = emptyList(),
        ),
      ),
    )
    val json = mapper.convertValue(IndexedDB.DatabaseResult(db), JSONObject::class.java)
    val store = json.getJSONObject("databaseWithObjectStores")
      .getJSONArray("objectStores")
      .getJSONObject(0)
    assertEquals("settings", store.getString("name"))
    assertEquals("null", store.getJSONObject("keyPath").getString("type"))
    assertEquals(0, store.getJSONArray("indexes").length())
  }
}
