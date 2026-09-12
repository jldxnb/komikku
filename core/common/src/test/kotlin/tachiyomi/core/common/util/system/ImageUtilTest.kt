package tachiyomi.core.common.util.system

import android.content.res.Resources
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.InputStream

class ImageUtilTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun stubAndroidResources() {
            // ImageUtil reads the display size when it is initialized, which isn't available here.
            mockkStatic(Resources::class)
            every { Resources.getSystem() } returns mockk(relaxed = true)
        }
    }

    /**
     * Opening a stream to sniff the image type costs a full scan of the entry inside an archive, so
     * the file name must be enough to recognize these.
     */
    private val failIfSniffed: () -> InputStream = {
        error("image type should have been detected from the file name")
    }

    @Test
    fun `Recognizes image extensions regardless of case`() {
        listOf(
            "001.jpg", "001.JPG", "001.Jpg",
            "002.jpeg", "002.JPEG", "002.Jpeg",
            "003.png", "003.PNG",
            "004.webp", "004.AVIF", "004.gif",
        ).forEach { name ->
            ImageUtil.isImage(name, failIfSniffed) shouldBe true
        }
    }

    @Test
    fun `Recognizes the special cbi extension`() {
        ImageUtil.isImage("chapter.cbi", failIfSniffed) shouldBe true
        ImageUtil.isImage("chapter.CBI", failIfSniffed) shouldBe true
    }

    @Test
    fun `Rejects other extensions`() {
        listOf("001.txt", "001.cbz", "001.jpg.txt", "001").forEach { name ->
            ImageUtil.isImage(name) shouldBe false
        }
    }

    @Test
    fun `Still sniffs names it cannot recognize`() {
        shouldThrow<IllegalStateException> {
            ImageUtil.isImage("001.bin") { throw IllegalStateException("sniffed") }
        }
    }
}
