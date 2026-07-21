package codes.dreaming.cloudmedia.network

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ImmichRepo"
private const val SYNC_PREFS = "immich_cloud_sync"

data class ImmichAsset(
  val id: String,
  val mimeType: String,
  val dateTakenMillis: Long,
  val width: Int,
  val height: Int,
  val sizeBytes: Long,
  val durationMillis: Long,
  val isFavorite: Boolean,
  val orientation: Int,
  val isImage: Boolean,
  val originalFileName: String? = null,
  val syncGeneration: Long = 0
)

data class ImmichAlbum(
  val id: String,
  val displayName: String,
  val mediaCount: Int,
  val coverAssetId: String?,
  val dateTakenMillis: Long
)

data class ImmichPerson(
  val id: String,
  val name: String,
  val coverAssetId: String?
)

data class QueryResult(
  val assets: List<ImmichAsset>,
  val nextPageToken: String?
)

object ImmichRepository {
  private lateinit var appContext: Context
  private lateinit var syncPrefs: SharedPreferences
  private lateinit var snapshotDatabase: MediaSnapshotDatabase
  @Volatile
  private var syncGeneration: Long = 0
  private var initialized = false
  private val refreshInProgress = AtomicBoolean(false)

  private var cachedPeople: List<ImmichPerson>? = null
  private var peopleCacheTime: Long = 0
  private const val PEOPLE_CACHE_TTL_MS = 5 * 60 * 1000L

  // Local MediaStore lookup for deduplication: (displayName, sizeBytes) -> MediaStore URI
  @Volatile
  private var localMediaLookup: Map<Pair<String, Long>, Uri>? = null
  @Volatile
  private var localMediaLookupTime: Long = 0
  private val localMediaRefreshInProgress = AtomicBoolean(false)
  private const val LOCAL_MEDIA_CACHE_TTL_MS = 2 * 60 * 1000L

  private const val CURRENT_COLLECTION_VERSION = "immich-cloud-v8"
  private const val API_PAGE_SIZE = 1000

  fun initialize(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      appContext = context.applicationContext
      syncPrefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
      snapshotDatabase = MediaSnapshotDatabase(appContext)
      ApiClient.initialize(appContext)

      val storedId = syncPrefs.getString("media_collection_id", null)
      if (storedId == null || !storedId.startsWith(CURRENT_COLLECTION_VERSION)) {
        snapshotDatabase.clear()
        syncPrefs.edit()
          .putString("media_collection_id", newCollectionId())
          .putLong("sync_generation", 0)
          .putBoolean("snapshot_initialized", false)
          .apply()
      }
      syncGeneration = maxOf(
        syncPrefs.getLong("sync_generation", 0),
        snapshotDatabase.maxGeneration()
      )
      initialized = true
    }
  }

  val isConfigured: Boolean get() = ApiClient.isLoggedIn

  fun getMediaCollectionId(): String =
    syncPrefs.getString("media_collection_id", null) ?: newCollectionId().also {
      syncPrefs.edit().putString("media_collection_id", it).apply()
    }

  fun getLastSyncGeneration(): Long {
    return syncGeneration
  }

  fun getAccountName(): String = ApiClient.accountName ?: ApiClient.serverUrl ?: "Immich"

  fun requestRefresh(onChanged: (() -> Unit)? = null) {
    if (!isConfigured || !refreshInProgress.compareAndSet(false, true)) return
    val collectionId = getMediaCollectionId()
    Thread({
      try {
        if (refreshSnapshot(collectionId)) onChanged?.invoke()
      } catch (e: Exception) {
        Log.e(TAG, "Background media refresh failed", e)
      } finally {
        refreshInProgress.set(false)
      }
    }, "immich-media-refresh").start()
  }

  @Synchronized
  fun refreshSnapshot(expectedCollectionId: String? = null): Boolean {
    if (!isConfigured) return false
    val remoteAssets = fetchAllVisibleAssets()
    if (expectedCollectionId != null && expectedCollectionId != getMediaCollectionId()) {
      Log.d(TAG, "Discarding refresh for an old account collection")
      return false
    }
    val nextGeneration = syncGeneration + 1
    val changed = snapshotDatabase.replaceSnapshot(remoteAssets, nextGeneration)
    syncPrefs.edit().putBoolean("snapshot_initialized", true).apply()
    if (changed) {
      syncGeneration = nextGeneration
      syncPrefs.edit().putLong("sync_generation", syncGeneration).apply()
      Log.d(TAG, "Stored ${remoteAssets.size} assets at generation $syncGeneration")
    }
    return changed
  }

  @Synchronized
  fun resetForAccountChange() {
    snapshotDatabase.clear()
    syncGeneration = 0
    cachedPeople = null
    localMediaLookup = null
    syncPrefs.edit()
      .putLong("sync_generation", 0)
      .putBoolean("snapshot_initialized", false)
      .putString("media_collection_id", newCollectionId())
      .apply()
  }

  internal fun queryDeletedAssets(syncGeneration: Long, pageToken: String?): DeletedQueryResult =
    snapshotDatabase.queryDeleted(syncGeneration, pageToken)

  private fun newCollectionId(): String = "$CURRENT_COLLECTION_VERSION-${UUID.randomUUID()}"

  fun queryAllAssets(
    syncGeneration: Long? = null,
    pageSize: Int = 1000,
    pageToken: String? = null,
    mimeTypes: List<String> = emptyList()
  ): QueryResult {
    Log.d(TAG, "queryAllAssets: pageSize=$pageSize, pageToken=$pageToken")
    ensureInitialSnapshot()
    return snapshotDatabase.queryAssets(syncGeneration ?: 0, pageSize, pageToken, mimeTypes)
  }

  @Synchronized
  private fun ensureInitialSnapshot() {
    if (!isConfigured || syncPrefs.getBoolean("snapshot_initialized", false)) return
    try {
      refreshSnapshot()
    } catch (e: Exception) {
      Log.e(TAG, "Initial media refresh failed", e)
    }
  }

  fun queryAlbumAssets(
    albumId: String,
    pageSize: Int = 1000,
    pageToken: String? = null
  ): QueryResult {
    Log.d(TAG, "queryAlbumAssets: albumId=$albumId")
    return try {
      searchMetadata(
        page = pageToken?.toIntOrNull() ?: 1,
        pageSize = pageSize,
        albumId = albumId
      )
    } catch (e: Exception) {
      Log.e(TAG, "queryAlbumAssets error", e)
      QueryResult(emptyList(), null)
    }
  }

  fun queryAlbums(): List<ImmichAlbum> {
    return try {
      queryAlbumsStrict()
    } catch (e: Exception) {
      Log.e(TAG, "queryAlbums error", e)
      emptyList()
    }
  }

  fun queryPeople(): List<ImmichPerson> {
    val now = System.currentTimeMillis()
    cachedPeople?.let { cached ->
      if (now - peopleCacheTime < PEOPLE_CACHE_TTL_MS) return cached
    }
    return try {
      val url = ApiClient.buildUrl("/people") ?: return emptyList()
      val request = Request.Builder().url(url).get().build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        response.close()
        return emptyList()
      }
      val body = response.body?.string() ?: "{}"
      response.close()
      val json = JSONObject(body)
      val arr = json.optJSONArray("people") ?: return emptyList()
      val people = mutableListOf<ImmichPerson>()
      for (i in 0 until arr.length()) {
        val p = arr.getJSONObject(i)
        val name = p.optString("name", "")
        if (name.isBlank()) continue
        val personId = p.getString("id")
        people.add(ImmichPerson(id = personId, name = name, coverAssetId = "person:$personId"))
      }
      cachedPeople = people
      peopleCacheTime = now
      people
    } catch (e: Exception) {
      Log.e(TAG, "queryPeople error", e)
      emptyList()
    }
  }

  fun queryPersonAssets(
    personId: String,
    pageSize: Int = 1000,
    pageToken: String? = null
  ): QueryResult {
    return try {
      val page = pageToken?.toIntOrNull() ?: 1
      val requestSize = pageSize.coerceIn(1, API_PAGE_SIZE)
      val url = ApiClient.buildUrl("/search/metadata") ?: return QueryResult(emptyList(), null)
      val body = JSONObject().apply {
        put("personIds", JSONArray().put(personId))
        put("page", page)
        put("size", requestSize)
        put("withExif", true)
      }
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        response.close()
        return QueryResult(emptyList(), null)
      }
      val responseBody = response.body?.string() ?: "{}"
      response.close()
      val result = JSONObject(responseBody)
      val assetsObj = result.optJSONObject("assets") ?: return QueryResult(emptyList(), null)
      val items = assetsObj.optJSONArray("items") ?: return QueryResult(emptyList(), null)
      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }
      val nextToken = parseNextPage(assetsObj)
      QueryResult(assets, nextToken)
    } catch (e: Exception) {
      Log.e(TAG, "queryPersonAssets error", e)
      QueryResult(emptyList(), null)
    }
  }

  fun searchAssets(
    query: String,
    pageSize: Int = 100,
    pageToken: String? = null
  ): QueryResult {
    return try {
      val page = pageToken?.toIntOrNull() ?: 1
      val requestSize = pageSize.coerceIn(1, API_PAGE_SIZE)
      val url = ApiClient.buildUrl("/search/smart") ?: return QueryResult(emptyList(), null)
      val body = JSONObject().apply {
        put("query", query)
        put("page", page)
        put("size", requestSize)
        put("withExif", true)
      }
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        response.close()
        return QueryResult(emptyList(), null)
      }
      val responseBody = response.body?.string() ?: "{}"
      response.close()
      val result = JSONObject(responseBody)
      val assetsObj = result.optJSONObject("assets") ?: return QueryResult(emptyList(), null)
      val items = assetsObj.optJSONArray("items") ?: return QueryResult(emptyList(), null)
      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }
      val nextToken = parseNextPage(assetsObj)
      QueryResult(assets, nextToken)
    } catch (e: Exception) {
      Log.e(TAG, "searchAssets error", e)
      QueryResult(emptyList(), null)
    }
  }

  fun openMedia(assetId: String, cancellationSignal: CancellationSignal? = null): ParcelFileDescriptor? {
    if (assetId.startsWith("person:")) {
      val personId = assetId.removePrefix("person:")
      val url = ApiClient.buildUrl("/people/$personId/thumbnail") ?: return null
      return downloadToTempFile(Request.Builder().url(url).get().build(), "person_$personId", cancellationSignal)
    }
    val url = ApiClient.buildUrl("/assets/$assetId/original") ?: return null
    val knownSize = snapshotDatabase.getAsset(assetId)?.sizeBytes?.takeIf { it > 1 }
    return openRemoteFile(url, knownSize, cancellationSignal)
  }

  fun openVideoPlayback(
    assetId: String,
    cancellationSignal: CancellationSignal? = null
  ): ParcelFileDescriptor? {
    val url = ApiClient.buildUrl("/assets/$assetId/video/playback") ?: return null
    return openRemoteFile(url, null, cancellationSignal)
  }

  fun isVideo(assetId: String): Boolean = snapshotDatabase.getAsset(assetId)?.isImage == false

  private fun openRemoteFile(
    url: okhttp3.HttpUrl,
    knownSize: Long?,
    cancellationSignal: CancellationSignal?
  ): ParcelFileDescriptor? {
    return try {
      cancellationSignal?.throwIfCanceled()
      val storageManager = appContext.getSystemService(StorageManager::class.java)
      RemoteFileProxy(storageManager, url, knownSize, cancellationSignal).open()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open seekable remote media: $url", e)
      null
    }
  }

  fun openPreview(
    assetId: String,
    size: Point,
    cancellationSignal: CancellationSignal? = null
  ): ParcelFileDescriptor? {
    if (assetId.startsWith("person:")) {
      val personId = assetId.removePrefix("person:")
      val url = ApiClient.buildUrl("/people/$personId/thumbnail") ?: return null
      return downloadToTempFile(Request.Builder().url(url).get().build(), "person_$personId", cancellationSignal)
    }
    val sizeParam = if (size.x <= 250 && size.y <= 250) "thumbnail" else "preview"
    val url = ApiClient.buildUrl("/assets/$assetId/thumbnail") ?: return null
    val urlWithParams = url.newBuilder().addQueryParameter("size", sizeParam).build()
    return downloadToTempFile(
      Request.Builder().url(urlWithParams).get().build(),
      "preview_${assetId}_${snapshotDatabase.getAsset(assetId)?.syncGeneration ?: 0}_$sizeParam",
      cancellationSignal
    )
  }

  private fun downloadToTempFile(
    request: Request,
    prefix: String,
    cancellationSignal: CancellationSignal? = null
  ): ParcelFileDescriptor? {
    val cacheDir = File(appContext.cacheDir, "immich_previews").apply { mkdirs() }
    val safeName = prefix.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val cacheFile = File(cacheDir, safeName)
    if (cacheFile.isFile && cacheFile.length() > 0) {
      return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    val call = ApiClient.getClient().newCall(request)
    var pendingFile: File? = null
    return try {
      cancellationSignal?.throwIfCanceled()
      cancellationSignal?.setOnCancelListener { call.cancel() }
      val response = call.execute()
      if (!response.isSuccessful) {
        Log.e(TAG, "Download failed: ${response.code}")
        response.close()
        return null
      }
      val tempFile = File.createTempFile("pending_", ".media", cacheDir)
      pendingFile = tempFile
      response.body?.byteStream()?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output, 65536) }
      }
      response.close()
      if (tempFile.length() <= 0) throw IOException("Downloaded an empty preview")
      if (!tempFile.renameTo(cacheFile)) {
        if (!cacheFile.exists()) throw IOException("Unable to store preview cache entry")
        tempFile.delete()
      }
      pendingFile = null
      trimPreviewCache(cacheDir)
      ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (e: Exception) {
      Log.e(TAG, "downloadToTempFile error", e)
      null
    } finally {
      pendingFile?.delete()
      cancellationSignal?.setOnCancelListener(null)
    }
  }

  private fun trimPreviewCache(cacheDir: File) {
    val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
      ?: return
    var retainedBytes = 0L
    for (file in files) {
      retainedBytes += file.length()
      if (retainedBytes > MAX_PREVIEW_CACHE_BYTES) file.delete()
    }
  }

  private fun assetFromApiJson(a: JSONObject): ImmichAsset {
    val id = a.getString("id")
    val type = a.optString("type", "IMAGE")
    val isImage = type == "IMAGE"
    val createdAt = a.optString("fileCreatedAt", a.optString("createdAt", ""))
    val originalMimeType = a.optString("originalMimeType", "")
    val originalFileName = a.optString("originalFileName", "").let {
      if (it.isNotBlank() && it != "null") it else null
    }
    val exifInfo = a.optJSONObject("exifInfo")
    val fileSize = a.optLong("fileSizeInByte", exifInfo?.optLong("fileSizeInByte", 1) ?: 1L)
    val orientation = parseOrientation(exifInfo?.optString("orientation", "0"))
    val width = a.optInt("width", exifInfo?.optInt("exifImageWidth", 0) ?: 0)
    val height = a.optInt("height", exifInfo?.optInt("exifImageHeight", 0) ?: 0)
    val durationMillis = when (val duration = a.opt("duration")) {
      is Number -> duration.toLong()
      is String -> parseDuration(duration)
      else -> 0L
    }

    val mimeType = when {
      originalMimeType.isNotBlank() && originalMimeType != "null" -> originalMimeType
      originalFileName != null -> URLConnection.guessContentTypeFromName(originalFileName)
        ?: if (isImage) "image/jpeg" else "video/mp4"
      isImage -> "image/jpeg"
      else -> "video/mp4"
    }

    return ImmichAsset(
      id = id,
      mimeType = mimeType,
      dateTakenMillis = parseIso8601(createdAt),
      width = width, height = height,
      sizeBytes = if (fileSize > 0) fileSize else 1L,
      durationMillis = durationMillis,
      isFavorite = a.optBoolean("isFavorite", false),
      orientation = orientation,
      isImage = isImage,
      originalFileName = originalFileName
    )
  }

  private fun fetchAllVisibleAssets(): List<ImmichAsset> {
    val assetsById = linkedMapOf<String, ImmichAsset>()
    fetchAllSearchPages().forEach { assetsById[it.id] = it }

    // Assets shared by another user can be visible only through a shared album.
    // Immich 3 no longer embeds assets in GET /albums/{id}, so use metadata search.
    try {
      for (album in queryAlbumsStrict()) {
        fetchAllSearchPages(album.id).forEach { assetsById[it.id] = it }
      }
    } catch (e: Exception) {
      // Keep the owner's main library usable for older restricted API keys.
      Log.w(TAG, "Shared-album scan was skipped", e)
    }
    return assetsById.values.toList()
  }

  private fun fetchAllSearchPages(albumId: String? = null): List<ImmichAsset> {
    val assets = mutableListOf<ImmichAsset>()
    var page = 1
    do {
      val result = searchMetadata(page, API_PAGE_SIZE, albumId)
      assets += result.assets
      page = result.nextPageToken?.toIntOrNull() ?: 0
    } while (page > 0)
    return assets
  }

  private fun searchMetadata(page: Int, pageSize: Int, albumId: String? = null): QueryResult {
    val url = ApiClient.buildUrl("/search/metadata")
      ?: throw IOException("Immich server is not configured")
    val body = JSONObject().apply {
      put("page", page.coerceAtLeast(1))
      put("size", pageSize.coerceIn(1, API_PAGE_SIZE))
      put("order", "desc")
      put("withExif", true)
      if (albumId != null) put("albumIds", JSONArray().put(albumId))
    }
    val request = Request.Builder()
      .url(url)
      .post(body.toString().toRequestBody("application/json".toMediaType()))
      .build()
    ApiClient.getClient().newCall(request).execute().use { response ->
      val responseBody = response.body?.string() ?: "{}"
      if (!response.isSuccessful) {
        throw IOException("Immich metadata search failed with HTTP ${response.code}: ${responseBody.take(200)}")
      }
      val assetsObject = JSONObject(responseBody).optJSONObject("assets")
        ?: throw IOException("Immich metadata response did not contain assets")
      val items = assetsObject.optJSONArray("items") ?: JSONArray()
      val assets = ArrayList<ImmichAsset>(items.length())
      for (i in 0 until items.length()) assets += assetFromApiJson(items.getJSONObject(i))
      return QueryResult(assets, parseNextPage(assetsObject))
    }
  }

  private fun queryAlbumsStrict(): List<ImmichAlbum> {
    val url = ApiClient.buildUrl("/albums") ?: throw IOException("Immich server is not configured")
    val request = Request.Builder().url(url).get().build()
    ApiClient.getClient().newCall(request).execute().use { response ->
      val body = response.body?.string() ?: "[]"
      if (!response.isSuccessful) {
        throw IOException("Immich albums request failed with HTTP ${response.code}: ${body.take(200)}")
      }
      return parseAlbums(JSONArray(body))
    }
  }

  private fun parseAlbums(arr: JSONArray): List<ImmichAlbum> {
    val albums = mutableListOf<ImmichAlbum>()
    for (i in 0 until arr.length()) {
      val obj = arr.getJSONObject(i)
      val assetCount = obj.optInt("assetCount", 0)
      if (assetCount == 0) continue
      val thumbId = obj.optString("albumThumbnailAssetId", "")
      albums += ImmichAlbum(
        id = obj.getString("id"),
        displayName = obj.getString("albumName"),
        coverAssetId = if (thumbId.isNotEmpty() && thumbId != "null") thumbId else null,
        dateTakenMillis = parseIso8601(obj.optString("updatedAt", "")),
        mediaCount = assetCount
      )
    }
    return albums
  }

  private fun parseNextPage(assetsObject: JSONObject): String? {
    val value = assetsObject.opt("nextPage") ?: return null
    return when (value) {
      JSONObject.NULL -> null
      is Number -> value.toInt().takeIf { it > 0 }?.toString()
      is String -> value.takeIf { it.isNotBlank() && it != "null" && it != "0" }
      else -> null
    }
  }

  fun findLocalMediaStoreUri(asset: ImmichAsset): Uri? {
    val fileName = asset.originalFileName ?: return null
    requestLocalMediaLookupRefresh()
    return localMediaLookup?.get(Pair(fileName, asset.sizeBytes))
  }

  private fun requestLocalMediaLookupRefresh() {
    val now = System.currentTimeMillis()
    if (localMediaLookup != null && now - localMediaLookupTime < LOCAL_MEDIA_CACHE_TTL_MS) return
    if (!localMediaRefreshInProgress.compareAndSet(false, true)) return
    Thread({
      try {
        val lookup = buildLocalMediaLookup()
        localMediaLookup = lookup
        localMediaLookupTime = System.currentTimeMillis()
        Log.d(TAG, "Built local media lookup: ${lookup.size} entries")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to build local media lookup", e)
      } finally {
        localMediaRefreshInProgress.set(false)
      }
    }, "immich-local-media-lookup").start()
  }

  private fun buildLocalMediaLookup(): Map<Pair<String, Long>, Uri> {
    val result = mutableMapOf<Pair<String, Long>, Uri>()
    val resolver = appContext.contentResolver

    val projection = arrayOf(
      MediaStore.MediaColumns._ID,
      MediaStore.MediaColumns.DISPLAY_NAME,
      MediaStore.MediaColumns.SIZE
    )

    val collections = arrayOf(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )

    for (collection in collections) {
      try {
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
          val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
          val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

          while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val size = cursor.getLong(sizeCol)
            if (size <= 0) continue
            val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
            result[Pair(name, size)] = uri
          }
        }
      } catch (e: SecurityException) {
        Log.w(TAG, "No permission to query MediaStore: ${e.message}")
        return emptyMap()
      }
    }
    return result
  }

  private fun parseDuration(duration: String): Long {
    if (duration.isBlank() || duration == "0:00:00.00000") return 0
    return try {
      val parts = duration.split(":")
      if (parts.size == 3) {
        val h = parts[0].toLong()
        val m = parts[1].toLong()
        val s = parts[2].toDouble()
        ((h * 3600 + m * 60 + s) * 1000).toLong()
      } else 0
    } catch (_: Exception) { 0 }
  }

  private fun parseOrientation(value: String?): Int {
    return when (value?.trim()?.toIntOrNull()) {
      1, 0, null -> 0
      3, 180 -> 180
      6, 90 -> 90
      8, 270 -> 270
      else -> 0
    }
  }

  private fun parseIso8601(dateStr: String): Long {
    return try {
      java.time.Instant.parse(dateStr).toEpochMilli()
    } catch (_: Exception) {
      try {
        java.time.LocalDateTime.parse(dateStr)
          .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
      } catch (_: Exception) {
        0L
      }
    }
  }

  private const val MAX_PREVIEW_CACHE_BYTES = 256L * 1024 * 1024
}
