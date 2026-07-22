package codes.dreaming.cloudmedia.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpiringLruCacheTest {
  @Test
  fun `entry expires at ttl`() {
    var now = 100L
    val cache = ExpiringLruCache<String, String>(2, 50) { now }
    cache.put("query", "result")

    assertEquals("result", cache.get("query"))
    now = 150L
    assertNull(cache.get("query"))
  }

  @Test
  fun `least recently used entry is evicted`() {
    val cache = ExpiringLruCache<String, String>(2, 1_000) { 0L }
    cache.put("first", "1")
    cache.put("second", "2")
    cache.get("first")
    cache.put("third", "3")

    assertEquals("1", cache.get("first"))
    assertNull(cache.get("second"))
    assertEquals("3", cache.get("third"))
  }

  @Test
  fun `clear removes every entry`() {
    val cache = ExpiringLruCache<String, String>(2, 1_000) { 0L }
    cache.put("first", "1")
    cache.put("second", "2")

    cache.clear()

    assertNull(cache.get("first"))
    assertNull(cache.get("second"))
  }
}
