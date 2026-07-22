package codes.dreaming.cloudmedia.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImmichSearchFilterTest {
  @Test
  fun `image mime types use Immich image filter`() {
    assertEquals("IMAGE", immichAssetTypeForMimeTypes(listOf("image/*", "image/jpeg")))
  }

  @Test
  fun `video mime types use Immich video filter`() {
    assertEquals("VIDEO", immichAssetTypeForMimeTypes(listOf("video/*", "video/mp4")))
  }

  @Test
  fun `mixed and unrestricted requests do not constrain asset type`() {
    assertNull(immichAssetTypeForMimeTypes(listOf("image/*", "video/*")))
    assertNull(immichAssetTypeForMimeTypes(listOf("*/*")))
    assertNull(immichAssetTypeForMimeTypes(emptyList()))
  }
}
