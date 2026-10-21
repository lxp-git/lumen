package dev.lumen.inspector.kv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DataStoreAccessTest {

  @Test
  fun declaredFieldsOrNullSkipsClassWhoseFieldTypeIsMissing() {
    val loader = HidingClassLoader(hidden = MissingDependency::class.java.name)
    val holder = Class.forName(HoldsMissingDependency::class.java.name, false, loader)

    // The failure that killed the scan thread: the class loads, its field type does not.
    assertThrows(NoClassDefFoundError::class.java) { holder.declaredFields }
    assertNull(DataStoreAccess.declaredFieldsOrNull(holder))
  }

  @Test
  fun declaredFieldsOrNullReturnsFieldsWhenTypesResolve() {
    val fields = DataStoreAccess.declaredFieldsOrNull(HoldsMissingDependency::class.java)

    assertEquals(listOf("dependency"), fields?.map { it.name })
  }

  /** Defines [HoldsMissingDependency] itself so its field type resolves here, where [hidden] is absent. */
  private class HidingClassLoader(
    private val hidden: String,
  ) : ClassLoader(DataStoreAccessTest::class.java.classLoader) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
      if (name == hidden) throw ClassNotFoundException(name)
      if (name != HoldsMissingDependency::class.java.name) return super.loadClass(name, resolve)
      synchronized(this) {
        findLoadedClass(name)?.let { return it }
        val resource = name.replace('.', '/') + ".class"
        val bytes = parent.getResourceAsStream(resource)!!.use { it.readBytes() }
        return defineClass(name, bytes, 0, bytes.size)
      }
    }
  }
}

class MissingDependency

class HoldsMissingDependency {
  @JvmField
  var dependency: MissingDependency? = null
}
