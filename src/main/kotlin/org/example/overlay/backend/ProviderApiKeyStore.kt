package org.example.overlay.backend

import org.example.overlay.platform.Dpapi
import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ProviderApiKeys(
    val inworld: String = "",
    val openRouter: String = "",
) {
    fun forProvider(provider: String): String = when (provider) {
        "INWORLD" -> inworld
        "OPEN_ROUTER" -> openRouter
        else -> ""
    }

    fun withProvider(provider: String, apiKey: String): ProviderApiKeys = when (provider) {
        "INWORLD" -> copy(inworld = apiKey)
        "OPEN_ROUTER" -> copy(openRouter = apiKey)
        else -> this
    }
}

/** BYOK-ключи хранятся отдельно от открытого settings.json и защищены Windows DPAPI. */
class ProviderApiKeyStore(private val file: Path) {

    fun load(): ProviderApiKeys {
        if (!Files.exists(file) || !Dpapi.isAvailable) return ProviderApiKeys()
        return try {
            val json = String(Dpapi.unprotect(Files.readAllBytes(file)), Charsets.UTF_8)
            MAPPER.readValue(json, ProviderApiKeys::class.java)
        } catch (error: Exception) {
            log.warn("Could not read saved API keys: {}", error.toString())
            ProviderApiKeys()
        }
    }

    fun save(keys: ProviderApiKeys) {
        if (!Dpapi.isAvailable) {
            log.warn("DPAPI unavailable — API keys were not saved")
            return
        }
        try {
            Files.createDirectories(file.parent)
            val encrypted = Dpapi.protect(MAPPER.writeValueAsBytes(keys))
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(temp, encrypted)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            log.warn("Could not save API keys: {}", error.toString())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ProviderApiKeyStore::class.java)
        val MAPPER: JsonMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    }
}
