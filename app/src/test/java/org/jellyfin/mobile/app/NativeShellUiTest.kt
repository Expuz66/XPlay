package org.jellyfin.mobile.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class NativeShellUiTest {
    @Test
    fun normalWebNavigationDoesNotAdvertiseMultiServerSelection() {
        val paths = listOf(
            Path.of("app/src/main/assets/native/nativeshell.js"),
            Path.of("src/main/assets/native/nativeshell.js"),
        )
        val source = Files.readString(paths.first { Files.exists(it) })

        assertFalse(source.contains("\"multiserver\""))
        assertTrue(source.contains("openServerSelection"))
    }
}
