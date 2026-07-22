package codes.dreaming.cloudmedia.network

internal fun immichAssetTypeForMimeTypes(mimeTypes: List<String>): String? {
  val requested = mimeTypes.filter { it.isNotBlank() && it != "*/*" }
  if (requested.isEmpty()) return null
  return when {
    requested.all { it.startsWith("image/") } -> "IMAGE"
    requested.all { it.startsWith("video/") } -> "VIDEO"
    else -> null
  }
}
