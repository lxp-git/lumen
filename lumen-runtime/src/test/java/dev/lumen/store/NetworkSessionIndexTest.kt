package dev.lumen.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NetworkSessionIndexTest {
  @get:Rule
  val temp = TemporaryFolder()

  // As Android's insertion-ordered JSONObject writes lines: the id leads.
  private fun line(id: String, extra: String) = """{"id":"$id",$extra}""" + "\n"

  private fun merged(file: File, index: NetworkSessionIndex, id: String) =
    NetworkSessionIndex.Reader(file).use { it.merged(index.offsetsOf(id)!!) }

  @Test
  fun foldsEachRecordsLinesInOrder() {
    val file = temp.newFile("session-1-2.jsonl")
    file.writeText(
      line("1.0", "\"status\":200,\"url\":\"a\"") +
        line("1.1", "\"url\":\"b\"") +
        line("1.0", "\"finishedAt\":9,\"url\":\"a\""),
    )
    val index = NetworkSessionIndex(file)

    index.refresh()

    assertEquals(listOf("1.0", "1.1"), index.ids())
    val first = merged(file, index, "1.0")!!
    assertEquals(200, first.getInt("status"))
    assertEquals(9, first.getInt("finishedAt"))
    assertEquals("b", merged(file, index, "1.1")!!.getString("url"))
  }

  @Test
  fun aLineStillBeingWrittenIsIndexedOnceComplete() {
    val file = temp.newFile("session-1-2.jsonl")
    val whole = line("1.0", "\"url\":\"a\"")
    file.writeText(whole + "{\"id\":\"1.1\",\"ur")
    val index = NetworkSessionIndex(file)

    index.refresh()
    assertEquals(listOf("1.0"), index.ids())

    file.appendText("l\":\"b\"}\n" + line("1.0", "\"status\":204"))
    index.refresh()

    assertEquals(listOf("1.0", "1.1"), index.ids())
    assertArrayEquals(longArrayOf(whole.length.toLong()), index.offsetsOf("1.1"))
    assertEquals(204, merged(file, index, "1.0")!!.getInt("status"))
  }

  @Test
  fun aFileThatStartedOverIsReindexed() {
    val file = temp.newFile("session-1-2.jsonl")
    file.writeText(line("1.0", "\"url\":\"a\"") + line("1.1", "\"url\":\"b\""))
    val index = NetworkSessionIndex(file)
    index.refresh()

    file.writeText(line("1.7", "\"url\":\"c\""))
    index.refresh()

    assertEquals(listOf("1.7"), index.ids())
    assertNull(index.offsetsOf("1.0"))
  }

  @Test
  fun aNewGenerationDropsOffsetsEvenWhenTheFileGrewBack() {
    val file = temp.newFile("session-1-2.jsonl")
    file.writeText(line("1.0", "\"url\":\"a\""))
    val index = NetworkSessionIndex(file)
    index.refresh(generation = 0)

    file.writeText(line("1.5", "\"url\":\"a-much-longer-url-than-before\""))
    index.refresh(generation = 1)

    assertEquals(listOf("1.5"), index.ids())
  }

  @Test
  fun aRecordWithManyLinesKeepsEveryOffset() {
    val file = temp.newFile("session-1-2.jsonl")
    file.writeText((0 until 5_000).joinToString("") { line("1.0", "\"frames\":$it") })
    val index = NetworkSessionIndex(file)

    index.refresh()

    val offsets = index.offsetsOf("1.0")!!
    assertEquals(5_000, offsets.size)
    assertEquals(4_999, merged(file, index, "1.0")!!.getInt("frames"))
  }

  @Test
  fun idsAnywhereInTheLineAndMissingFilesAreHandled() {
    val file = temp.newFile("session-1-2.jsonl")
    file.writeText("{\"url\":\"x\",\"id\":\"1.3\"}\nnot json\n\n")
    val index = NetworkSessionIndex(file)
    index.refresh()
    assertEquals(listOf("1.3"), index.ids())

    val missing = NetworkSessionIndex(File(temp.root, "absent.jsonl"))
    missing.refresh()
    assertEquals(0, missing.size())
  }
}
