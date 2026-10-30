package org.jellyfin.mobile.app

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity

/** Builds only the Jellyfin web client's expected credential record for the active origin. */
fun buildWebSessionCredentials(server: ServerEntity, user: UserEntity): String? {
    val token = user.accessToken ?: return null
    return buildJsonObject {
        put("Servers", buildJsonArray {
            add(buildJsonObject {
                put("ManualAddress", server.hostname)
                put("UserId", user.userId.toString())
                put("AccessToken", token)
            })
        })
    }.toString()
}
