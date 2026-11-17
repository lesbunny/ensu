package io.ente.ensu.llm

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class CustomModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (downloadedBytes.toFloat() / it).coerceIn(0f, 1f)
        }
}

internal object CustomModelDownloader {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()

    suspend fun download(
        context: Context,
        input: String,
        onProgress: (CustomModelDownloadProgress) -> Unit,
    ): CustomModel {
        val downloadUrl = normalizeHuggingFaceFileUrl(input)
        val source = downloadUrl.toHttpUrlOrNull()
            ?: throw IOException("That Hugging Face link is not valid.")
        val filename = source.pathSegments.last()
        val name = filename.removeSuffix(".gguf").ifBlank { "Custom GGUF model" }
        val id = sha256(downloadUrl)
        val directory = File(context.applicationContext.noBackupFilesDir, "models/custom")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create the private model storage folder.")
        }
        val destination = File(directory, "$id.gguf")
        if (destination.isFile && isGguf(destination)) {
            return CustomModel(id, name, destination.absolutePath, downloadUrl)
        }

        val temporary = File(directory, "$id.gguf.part")
        temporary.delete()
        val request =
            Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Ensu-Android")
                .build()
        val call = client.newCall(request)

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
                temporary.delete()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        temporary.delete()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Model download failed: ${error.message}", error)
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!it.isSuccessful) {
                                    val reason =
                                        when (it.code) {
                                            401, 403 ->
                                                "This file is private or gated. Ensu can download public Hugging Face files only."
                                            404 -> "The selected GGUF file was not found."
                                            else -> "Hugging Face returned HTTP ${it.code}."
                                        }
                                    throw IOException(reason)
                                }
                                val body = it.body ?: throw IOException("The download was empty.")
                                val total = body.contentLength().takeIf { size -> size > 0 }
                                if (total != null) {
                                    val available = StatFs(directory.absolutePath).availableBytes
                                    if (available < total + MINIMUM_FREE_SPACE_BYTES) {
                                        throw IOException("Not enough free storage for this model.")
                                    }
                                }

                                var downloaded = 0L
                                var lastReportedPercent = -1
                                body.byteStream().use { inputStream ->
                                    FileOutputStream(temporary).use { outputStream ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                                        while (true) {
                                            if (!continuation.isActive) {
                                                throw IOException("Download cancelled.")
                                            }
                                            val count = inputStream.read(buffer)
                                            if (count < 0) break
                                            outputStream.write(buffer, 0, count)
                                            downloaded += count
                                            val percent =
                                                total?.let {
                                                    ((downloaded * 100) / it).toInt().coerceIn(0, 100)
                                                }
                                            if (percent == null || percent != lastReportedPercent) {
                                                lastReportedPercent = percent ?: -1
                                                onProgress(CustomModelDownloadProgress(downloaded, total))
                                            }
                                        }
                                        outputStream.fd.sync()
                                    }
                                }

                                if (total != null && downloaded != total) {
                                    throw IOException(
                                        "The download was incomplete ($downloaded of $total bytes)."
                                    )
                                }
                                if (!isGguf(temporary)) {
                                    throw IOException(
                                        "That file is not a valid GGUF model. Choose a direct .gguf file."
                                    )
                                }
                                if (destination.exists() && !destination.delete()) {
                                    throw IOException("Unable to replace the existing model file.")
                                }
                                if (!temporary.renameTo(destination)) {
                                    throw IOException("Unable to save the downloaded model.")
                                }
                                onProgress(CustomModelDownloadProgress(downloaded, total ?: downloaded))
                                if (continuation.isActive) {
                                    continuation.resume(
                                        CustomModel(id, name, destination.absolutePath, downloadUrl)
                                    )
                                } else {
                                    destination.delete()
                                }
                            }
                        } catch (error: Exception) {
                            temporary.delete()
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            )
        }
    }

    internal fun normalizeHuggingFaceFileUrl(input: String): String {
        val raw = input.trim()
        val parsed = raw.toHttpUrlOrNull()
            ?: throw IOException("Enter a valid Hugging Face file link.")
        if (parsed.scheme != "https" || parsed.host != "huggingface.co") {
            throw IOException("Use an HTTPS link from huggingface.co.")
        }
        val segments = parsed.pathSegments
        if (
            segments.size < 5 ||
                segments[2] !in setOf("blob", "resolve") ||
                !segments.last().endsWith(".gguf", ignoreCase = true)
        ) {
            throw IOException(
                "Paste the link to one exact .gguf file, not the model repository page."
            )
        }
        if (Regex("-[0-9]{5}-of-[0-9]{5}\\.gguf$", RegexOption.IGNORE_CASE)
                .containsMatchIn(segments.last())
        ) {
            throw IOException("Multi-part GGUF models are not supported yet. Choose a single-file GGUF.")
        }

        val clean = raw.substringBefore('#').substringBefore('?')
        val resolved = clean.replaceFirst("/blob/", "/resolve/")
        return "$resolved?download=true"
    }

    private fun isGguf(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            }
        }.getOrDefault(false)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private const val MINIMUM_FREE_SPACE_BYTES = 16L * 1024L * 1024L
}
