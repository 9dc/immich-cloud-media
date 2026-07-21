package codes.dreaming.cloudmedia.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class DeletedQueryResult(
  val ids: List<String>,
  val nextPageToken: String?
)

internal class MediaSnapshotDatabase(context: Context) :
  SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE assets (
        id TEXT PRIMARY KEY NOT NULL,
        mime_type TEXT NOT NULL,
        date_taken INTEGER NOT NULL,
        width INTEGER NOT NULL,
        height INTEGER NOT NULL,
        size_bytes INTEGER NOT NULL,
        duration INTEGER NOT NULL,
        favorite INTEGER NOT NULL,
        orientation INTEGER NOT NULL,
        is_image INTEGER NOT NULL,
        original_filename TEXT,
        sync_generation INTEGER NOT NULL,
        signature TEXT NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      """
      CREATE TABLE deleted_assets (
        id TEXT PRIMARY KEY NOT NULL,
        sync_generation INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX assets_generation_idx ON assets(sync_generation)")
    db.execSQL("CREATE INDEX assets_date_idx ON assets(date_taken DESC, id DESC)")
    db.execSQL("CREATE INDEX deleted_generation_idx ON deleted_assets(sync_generation)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("DROP TABLE IF EXISTS assets")
    db.execSQL("DROP TABLE IF EXISTS deleted_assets")
    onCreate(db)
  }

  fun maxGeneration(): Long = readableDatabase.rawQuery(
    "SELECT MAX(generation) FROM (" +
      "SELECT MAX(sync_generation) AS generation FROM assets " +
      "UNION ALL SELECT MAX(sync_generation) AS generation FROM deleted_assets)",
    null
  ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

  /** Replaces the remote snapshot and assigns [newGeneration] only to changed rows. */
  fun replaceSnapshot(remoteAssets: Collection<ImmichAsset>, newGeneration: Long): Boolean {
    val remoteById = remoteAssets.associateBy { it.id }
    val previous = mutableMapOf<String, Pair<String, Long>>()
    readableDatabase.query(
      "assets",
      arrayOf("id", "signature", "sync_generation"),
      null,
      null,
      null,
      null,
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        previous[cursor.getString(0)] = cursor.getString(1) to cursor.getLong(2)
      }
    }

    val deletedIds = previous.keys - remoteById.keys
    val changed = deletedIds.isNotEmpty() || remoteById.any { (id, asset) ->
      previous[id]?.first != asset.signature()
    }
    if (!changed) return false

    val db = writableDatabase
    db.beginTransaction()
    try {
      for ((id, asset) in remoteById) {
        val signature = asset.signature()
        val old = previous[id]
        val rowGeneration = if (old?.first == signature) old.second else newGeneration
        db.insertWithOnConflict(
          "assets",
          null,
          asset.toContentValues(rowGeneration, signature),
          SQLiteDatabase.CONFLICT_REPLACE
        )
        db.delete("deleted_assets", "id = ?", arrayOf(id))
      }

      for (id in deletedIds) {
        db.delete("assets", "id = ?", arrayOf(id))
        db.insertWithOnConflict(
          "deleted_assets",
          null,
          ContentValues().apply {
            put("id", id)
            put("sync_generation", newGeneration)
          },
          SQLiteDatabase.CONFLICT_REPLACE
        )
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    return true
  }

  fun queryAssets(
    syncGeneration: Long,
    pageSize: Int,
    pageToken: String?,
    mimeTypes: List<String> = emptyList()
  ): QueryResult {
    val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
    val (upperGeneration, offset) = parseJournalPageToken(pageToken)
    val selection = mutableListOf("sync_generation > ?", "sync_generation <= ?")
    val selectionArgs = mutableListOf(syncGeneration.toString(), upperGeneration.toString())
    val requestedTypes = mimeTypes.filter { it.isNotBlank() && it != "*/*" }.distinct()
    if (requestedTypes.isNotEmpty()) {
      val mimeClauses = requestedTypes.map { mimeType ->
        if (mimeType.endsWith("/*")) {
          selectionArgs += mimeType.removeSuffix("*") + "%"
          "mime_type LIKE ?"
        } else {
          selectionArgs += mimeType
          "mime_type = ?"
        }
      }
      selection += mimeClauses.joinToString(" OR ", prefix = "(", postfix = ")")
    }
    val assets = mutableListOf<ImmichAsset>()
    readableDatabase.query(
      "assets",
      ASSET_COLUMNS,
      selection.joinToString(" AND "),
      selectionArgs.toTypedArray(),
      null,
      null,
      "date_taken DESC, id DESC",
      "$offset,${size + 1}"
    ).use { cursor ->
      while (cursor.moveToNext()) assets += cursor.toAsset()
    }
    val hasMore = assets.size > size
    if (hasMore) assets.removeAt(assets.lastIndex)
    return QueryResult(
      assets,
      if (hasMore) "$upperGeneration:${offset + size}" else null
    )
  }

  fun getAsset(id: String): ImmichAsset? = readableDatabase.query(
    "assets",
    ASSET_COLUMNS,
    "id = ?",
    arrayOf(id),
    null,
    null,
    null,
    "1"
  ).use { cursor -> if (cursor.moveToFirst()) cursor.toAsset() else null }

  fun queryDeleted(syncGeneration: Long, pageToken: String?): DeletedQueryResult {
    val (upperGeneration, offset) = parseJournalPageToken(pageToken)
    val ids = mutableListOf<String>()
    readableDatabase.query(
      "deleted_assets",
      arrayOf("id"),
      "sync_generation > ? AND sync_generation <= ?",
      arrayOf(syncGeneration.toString(), upperGeneration.toString()),
      null,
      null,
      "sync_generation ASC, id ASC",
      "$offset,${MAX_PAGE_SIZE + 1}"
    ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
    val hasMore = ids.size > MAX_PAGE_SIZE
    if (hasMore) ids.removeAt(ids.lastIndex)
    return DeletedQueryResult(
      ids,
      if (hasMore) "$upperGeneration:${offset + MAX_PAGE_SIZE}" else null
    )
  }

  private fun parseJournalPageToken(pageToken: String?): Pair<Long, Int> {
    if (pageToken != null) {
      val parts = pageToken.split(':', limit = 2)
      if (parts.size == 2) {
        val generation = parts[0].toLongOrNull()
        val offset = parts[1].toIntOrNull()
        if (generation != null && generation >= 0 && offset != null && offset >= 0) {
          return generation to offset
        }
      }
    }
    return maxGeneration() to 0
  }

  fun clear() {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("assets", null, null)
      db.delete("deleted_assets", null, null)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  private fun ImmichAsset.signature(): String = listOf(
    mimeType,
    dateTakenMillis,
    width,
    height,
    sizeBytes,
    durationMillis,
    isFavorite,
    orientation,
    isImage,
    originalFileName.orEmpty()
  ).joinToString("\u001f")

  private fun ImmichAsset.toContentValues(generation: Long, signature: String) = ContentValues().apply {
    put("id", id)
    put("mime_type", mimeType)
    put("date_taken", dateTakenMillis)
    put("width", width)
    put("height", height)
    put("size_bytes", sizeBytes)
    put("duration", durationMillis)
    put("favorite", if (isFavorite) 1 else 0)
    put("orientation", orientation)
    put("is_image", if (isImage) 1 else 0)
    put("original_filename", originalFileName)
    put("sync_generation", generation)
    put("signature", signature)
  }

  private fun android.database.Cursor.toAsset() = ImmichAsset(
    id = getString(0),
    mimeType = getString(1),
    dateTakenMillis = getLong(2),
    width = getInt(3),
    height = getInt(4),
    sizeBytes = getLong(5),
    durationMillis = getLong(6),
    isFavorite = getInt(7) != 0,
    orientation = getInt(8),
    isImage = getInt(9) != 0,
    originalFileName = if (isNull(10)) null else getString(10),
    syncGeneration = getLong(11)
  )

  companion object {
    private const val DATABASE_NAME = "immich_media_snapshot.db"
    private const val DATABASE_VERSION = 1
    private const val MAX_PAGE_SIZE = 1000
    private val ASSET_COLUMNS = arrayOf(
      "id",
      "mime_type",
      "date_taken",
      "width",
      "height",
      "size_bytes",
      "duration",
      "favorite",
      "orientation",
      "is_image",
      "original_filename",
      "sync_generation"
    )
  }
}
