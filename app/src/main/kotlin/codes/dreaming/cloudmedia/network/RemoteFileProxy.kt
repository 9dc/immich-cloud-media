package codes.dreaming.cloudmedia.network

import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A seekable file descriptor backed by authenticated HTTP range requests. */
internal class RemoteFileProxy(
  private val storageManager: StorageManager,
  private val url: HttpUrl,
  private val knownSize: Long?,
  private val cancellationSignal: CancellationSignal?
) {
  private val worker = HandlerThread("immich-range-${url.pathSegments.dropLast(1).lastOrNull() ?: "media"}")
  private val released = AtomicBoolean(false)
  private val activeCall = AtomicReference<Call?>()

  fun open(): ParcelFileDescriptor {
    worker.start()
    val callback = Callback()
    cancellationSignal?.setOnCancelListener(callback::cancel)
    return try {
      storageManager.openProxyFileDescriptor(
        ParcelFileDescriptor.MODE_READ_ONLY,
        callback,
        Handler(worker.looper)
      )
    } catch (e: Exception) {
      callback.cancel()
      throw e
    }
  }

  private inner class Callback : ProxyFileDescriptorCallback() {
    @Volatile
    private var resolvedSize = knownSize?.takeIf { it > 0 }

    override fun onGetSize(): Long = try {
      resolvedSize ?: discoverSize().also { resolvedSize = it }
    } catch (e: Exception) {
      throw ioError("onGetSize", e)
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
      if (released.get() || cancellationSignal?.isCanceled == true) {
        throw ErrnoException("onRead", OsConstants.ECANCELED)
      }
      val totalSize = onGetSize()
      if (offset >= totalSize || size <= 0) return 0
      val requestedSize = minOf(size.toLong(), totalSize - offset).toInt()
      val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=$offset-${offset + requestedSize - 1}")
        .build()
      return try {
        execute(request) { response ->
          if (response.code != 206 && !response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
          }
          val input = response.body?.byteStream() ?: throw IOException("Empty response body")
          if (response.code == 200 && offset > 0) skipFully(input, offset)
          var readTotal = 0
          while (readTotal < requestedSize) {
            val read = input.read(data, readTotal, requestedSize - readTotal)
            if (read < 0) break
            readTotal += read
          }
          readTotal
        }
      } catch (e: Exception) {
        throw ioError("onRead", e)
      }
    }

    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
      throw ErrnoException("onWrite", OsConstants.EBADF)

    override fun onFsync() = Unit

    override fun onRelease() {
      cancel()
    }

    fun cancel() {
      if (!released.compareAndSet(false, true)) return
      activeCall.getAndSet(null)?.cancel()
      cancellationSignal?.setOnCancelListener(null)
      worker.quitSafely()
    }

    private fun discoverSize(): Long {
      val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-0")
        .build()
      return execute(request) { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val contentRange = response.header("Content-Range")
        val rangeSize = contentRange?.substringAfterLast('/')?.toLongOrNull()
        val contentLength = response.body?.contentLength()?.takeIf { it >= 0 }
        rangeSize ?: contentLength ?: throw IOException("Server did not return media size")
      }
    }

    private fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T {
      val call = ApiClient.getClient().newCall(request)
      if (!activeCall.compareAndSet(null, call)) throw IOException("Concurrent proxy request")
      return try {
        call.execute().use(block)
      } finally {
        activeCall.compareAndSet(call, null)
      }
    }

    private fun skipFully(input: java.io.InputStream, byteCount: Long) {
      var remaining = byteCount
      while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) {
          remaining -= skipped
        } else if (input.read() >= 0) {
          remaining--
        } else {
          throw IOException("Unexpected end of media")
        }
      }
    }

    private fun ioError(operation: String, cause: Exception): ErrnoException {
      if (cause is ErrnoException) return cause
      return ErrnoException(operation, OsConstants.EIO, cause)
    }
  }
}
