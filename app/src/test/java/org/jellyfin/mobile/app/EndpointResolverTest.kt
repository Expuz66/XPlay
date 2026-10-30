package org.jellyfin.mobile.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EndpointResolverTest {
    @Test
    fun localHealthyIsSelectedFirst() = runBlocking {
        val resolver = resolver(local = true, remote = true)

        assertEquals("http://local", resolver.select()?.url)
    }

    @Test
    fun localWinsAgainAfterRemoteWasPreviouslySelected() = runBlocking {
        var localHealthy = false
        val resolver = EndpointResolver(
            EndpointProbe { endpoint -> if (endpoint.isLocal) localHealthy else true },
            "http://local",
            "https://remote",
        )

        assertEquals("https://remote", resolver.select()?.url)
        localHealthy = true
        assertEquals("http://local", resolver.select()?.url)
    }

    @Test
    fun remoteIsSelectedWhenLocalIsUnavailable() = runBlocking {
        val resolver = resolver(local = false, remote = true)

        assertEquals("https://remote", resolver.select()?.url)
    }

    @Test
    fun noHealthyEndpointReturnsNull() = runBlocking {
        val resolver = resolver(local = false, remote = false)

        assertNull(resolver.select())
    }

    @Test
    fun alternateEndpointIsCheckedWithoutChangingConfiguredOrder() = runBlocking {
        val resolver = resolver(local = true, remote = true)

        assertEquals("https://remote", resolver.alternate("http://local")?.url)
    }

    @Test
    fun remoteHttpEndpointIsRejected() {
        val resolver = EndpointResolver(EndpointProbe { true }, "http://local", "http://remote")

        assertTrue(resolver.configuredEndpoints().none { !it.isLocal })
    }

    @Test
    fun localCanBeSelectedAfterItRecovers() = runBlocking {
        var localHealthy = false
        val resolver = EndpointResolver(
            EndpointProbe { endpoint -> endpoint.isLocal && localHealthy || !endpoint.isLocal },
            "http://local",
            "https://remote",
        )

        assertEquals("https://remote", resolver.select()?.url)
        localHealthy = true
        assertEquals("http://local", resolver.select()?.url)
    }

    private fun resolver(local: Boolean, remote: Boolean): EndpointResolver {
        val probe = EndpointProbe { endpoint ->
            if (endpoint.isLocal) local else remote
        }
        return EndpointResolver(probe, "http://local", "https://remote")
    }
}
