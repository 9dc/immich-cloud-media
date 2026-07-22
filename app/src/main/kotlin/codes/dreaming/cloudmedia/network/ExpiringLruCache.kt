package codes.dreaming.cloudmedia.network

internal class ExpiringLruCache<K, V>(
  private val maxEntries: Int,
  private val ttlMillis: Long,
  private val clock: () -> Long = System::currentTimeMillis
) {
  private data class Entry<V>(val value: V, val storedAt: Long)

  private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)

  @Synchronized
  fun get(key: K): V? {
    val entry = entries[key] ?: return null
    if (clock() - entry.storedAt >= ttlMillis) {
      entries.remove(key)
      return null
    }
    return entry.value
  }

  @Synchronized
  fun put(key: K, value: V) {
    entries[key] = Entry(value, clock())
    if (entries.size > maxEntries) {
      entries.remove(entries.entries.first().key)
    }
  }

  @Synchronized
  fun clear() {
    entries.clear()
  }
}
