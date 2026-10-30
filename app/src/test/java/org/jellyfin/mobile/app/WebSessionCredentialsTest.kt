package org.jellyfin.mobile.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class WebSessionCredentialsTest {
    @Test
    fun persistedCredentialsCanRestoreTheWebSessionForAnotherOrigin() {
        val userId = UUID.randomUUID()
        val json = buildWebSessionCredentials(
            ServerEntity("https://remote.example"),
            UserEntity(7, userId, "persisted-token"),
        )
        val server = Json.parseToJsonElement(json!!).jsonObject["Servers"]!!.jsonArray[0].jsonObject

        assertEquals(userId.toString(), server["UserId"]!!.jsonPrimitive.content)
        assertEquals("persisted-token", server["AccessToken"]!!.jsonPrimitive.content)
        assertEquals("https://remote.example", server["ManualAddress"]!!.jsonPrimitive.content)
        assertNull(server["Id"])
    }

    @Test
    fun missingTokenDoesNotCreateWebSessionCredentials() {
        assertNull(buildWebSessionCredentials(ServerEntity("https://remote.example"), UserEntity(7, UUID.randomUUID(), null)))
    }
}
