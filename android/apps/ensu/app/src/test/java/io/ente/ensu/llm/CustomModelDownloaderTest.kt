package io.ente.ensu.llm

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CustomModelDownloaderTest {
    @Test
    fun convertsHuggingFaceBlobFileLinkToDirectDownload() {
        assertEquals(
            "https://huggingface.co/example/model-GGUF/resolve/main/model-Q4_K_M.gguf?download=true",
            CustomModelDownloader.normalizeHuggingFaceFileUrl(
                "https://huggingface.co/example/model-GGUF/blob/main/model-Q4_K_M.gguf"
            ),
        )
    }

    @Test
    fun keepsExactResolveFileLink() {
        assertEquals(
            "https://huggingface.co/example/model-GGUF/resolve/main/subfolder/model.gguf?download=true",
            CustomModelDownloader.normalizeHuggingFaceFileUrl(
                "https://huggingface.co/example/model-GGUF/resolve/main/subfolder/model.gguf?download=1"
            ),
        )
    }

    @Test
    fun rejectsRepositoryLinksAndNonGgufFiles() {
        assertInvalid("https://huggingface.co/example/model-GGUF")
        assertInvalid("https://huggingface.co/example/model-GGUF/tree/main")
        assertInvalid("https://huggingface.co/example/model-GGUF/blob/main/model.safetensors")
    }

    @Test
    fun rejectsShardedGgufFiles() {
        assertInvalid(
            "https://huggingface.co/example/model-GGUF/blob/main/model-00001-of-00003.gguf"
        )
    }

    private fun assertInvalid(url: String) {
        try {
            CustomModelDownloader.normalizeHuggingFaceFileUrl(url)
            fail("Expected the URL to be rejected: $url")
        } catch (_: IOException) {
            // Expected.
        }
    }
}
