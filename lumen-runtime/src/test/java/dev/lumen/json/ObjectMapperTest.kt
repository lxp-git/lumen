package dev.lumen.json

import dev.lumen.json.annotation.JsonProperty
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectMapperTest {
  open class Base {
    @JvmField @JsonProperty(required = true) var nodeId: Int = 0
  }

  class Node : Base() {
    @JvmField @JsonProperty(required = true) var nodeName: String? = null
    @JvmField @JsonProperty var nodeValue: String? = null
    @JvmField @JsonProperty var children: List<Node>? = null
    @JvmField @JsonProperty var attributes: List<String>? = null
    @JvmField var notExported: String = "hidden"
  }

  @Test
  fun encodesNestedTreeTheSameOnEveryCall() {
    val mapper = ObjectMapper()
    val tree = node(1, "root", node(2, "a", node(3, "a1")), node(4, "b"))
    // The second pass is served from the field cache and must not differ.
    val first = mapper.convertValue(tree, JSONObject::class.java).toString()
    val second = ObjectMapper().convertValue(tree, JSONObject::class.java).toString()
    assertEquals(first, second)

    val json = JSONObject(first)
    assertEquals(1, json.getInt("nodeId"))
    val a = json.getJSONArray("children").getJSONObject(0)
    assertEquals("a", a.getString("nodeName"))
    assertEquals(3, a.getJSONArray("children").getJSONObject(0).getInt("nodeId"))
    assertEquals("id", json.getJSONArray("attributes").getString(0))
  }

  @Test
  fun keepsRequiredNullsAndDropsOptionalOnes() {
    val json = ObjectMapper().convertValue(Node(), JSONObject::class.java)
    assertTrue(json.has("nodeName"))
    assertSame(JSONObject.NULL, json.get("nodeName"))
    assertFalse(json.has("nodeValue"))
    assertFalse(json.has("notExported"))
    assertEquals(0, json.getInt("nodeId"))
  }

  private fun node(id: Int, name: String, vararg children: Node) = Node().apply {
    nodeId = id
    nodeName = name
    this.children = children.toList()
    attributes = listOf("id", "n$id")
  }
}
