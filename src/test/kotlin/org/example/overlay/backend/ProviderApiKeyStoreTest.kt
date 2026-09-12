package org.example.overlay.backend

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProviderApiKeyStoreTest {

    @Test
    fun `round trips encrypted provider keys`() {
        val file = createTempDirectory("provider-keys").resolve("keys.bin")
        val store = ProviderApiKeyStore(file)
        val keys = ProviderApiKeys(inworld = "inworld-secret", openRouter = "openrouter-secret")

        store.save(keys)

        assertEquals(keys, store.load())
        val encrypted = String(Files.readAllBytes(file), Charsets.UTF_8)
        assertFalse("inworld-secret" in encrypted)
        assertFalse("openrouter-secret" in encrypted)
    }
}
