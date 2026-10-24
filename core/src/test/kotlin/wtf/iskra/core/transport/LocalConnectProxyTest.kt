package wtf.iskra.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Порт `LocalConnectProxyTests`: отказы как текст, и то, что `CONNECT` с секретом действительно несёт TLS до сервера. */
class LocalConnectProxyTest {
    private val expected = "Basic " + Base64.getEncoder().encodeToString("iskra:s3cret".toByteArray())
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = closers.reversed().forEach { runCatching { it() } }

    @Test
    fun connectWithTheSecretIsLetThrough() {
        val head = "CONNECT matrix-client.matrix.org:443 HTTP/1.1\r\nHost: matrix-client.matrix.org:443\r\nproxy-authorization: $expected"
        assertEquals(LocalConnectProxy.Answer.Connect("matrix-client.matrix.org", 443), LocalConnectProxy.answer(head, expected))
    }

    @Test
    fun anythingButConnectIs405() {
        val answer = LocalConnectProxy.answer("GET http://matrix.org/ HTTP/1.1\r\nProxy-Authorization: $expected", expected)
        assertTrue((answer as LocalConnectProxy.Answer.Refuse).response.startsWith("HTTP/1.1 405"))
    }

    /** Без секрета — `407`: в loopback-порт стучится что угодно на телефоне. */
    @Test
    fun noSecretOrTheWrongOneIs407() {
        for (head in listOf(
            "CONNECT matrix.org:443 HTTP/1.1",
            "CONNECT matrix.org:443 HTTP/1.1\r\nProxy-Authorization: Basic aXNrcmE6d3Jvbmc=",
        )) {
            val answer = LocalConnectProxy.answer(head, expected)
            assertTrue((answer as LocalConnectProxy.Answer.Refuse).response.startsWith("HTTP/1.1 407"), head)
        }
    }

    @Test
    fun anAuthorityWithoutAPortIs400() {
        val answer = LocalConnectProxy.answer("CONNECT matrix.org HTTP/1.1\r\nProxy-Authorization: $expected", expected)
        assertTrue((answer as LocalConnectProxy.Answer.Refuse).response.startsWith("HTTP/1.1 400"))
    }

    @Test
    fun authoritiesSplitIncludingIpv6() {
        assertEquals("matrix.org" to 443, LocalConnectProxy.splitAuthority("matrix.org:443"))
        assertEquals("::1" to 8448, LocalConnectProxy.splitAuthority("[::1]:8448"))
        assertEquals(null, LocalConnectProxy.splitAuthority("matrix.org"))
        assertEquals(null, LocalConnectProxy.splitAuthority(":443"))
        assertEquals(null, LocalConnectProxy.splitAuthority("matrix.org:0"))
    }

    /** Свой запрос приложения идёт через листенер с секретом, и [DirectPipe] видит соединение. */
    @Test
    fun theAppsOwnClientGoesThroughTheListener() = runBlocking<Unit> {
        val server = TlsServer().also { closers += { it.close() } }
        server.server.enqueue(MockResponse().setBody("ok"))
        val pipe = DirectPipe()
        val proxy = LocalConnectProxy(pipe)
        proxy.start()
        closers += { runBlocking { proxy.stop() } }
        val body = withContext(Dispatchers.IO) {
            proxy.httpClient(server.trustingClient).newCall(Request.Builder().url("${server.url}/x").build()).execute().use { it.body!!.string() }
        }
        assertEquals("ok", body)
        assertEquals(1, pipe.ports.size)
        assertEquals(1, proxy.carried.get())
    }

    /** Чужой без секрета до сервера не доходит. */
    @Test
    fun aStrangerWithoutTheSecretGetsNowhere() = runBlocking<Unit> {
        val server = TlsServer().also { closers += { it.close() } }
        val pipe = DirectPipe()
        val proxy = LocalConnectProxy(pipe)
        val port = proxy.start()
        closers += { runBlocking { proxy.stop() } }
        val stranger = server.trustingClient.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))).build()
        assertFailsWith<IOException> {
            withContext(Dispatchers.IO) { stranger.newCall(Request.Builder().url("${server.url}/x").build()).execute().close() }
        }
        assertEquals(0, pipe.ports.size)
        assertTrue(proxy.refused.get() >= 1)
    }

    /** Не слушающий прокси — клиент без выхода наружу, а не клиент, который идёт напрямую. */
    @Test
    fun aClientOfAStoppedProxyGoesNowhere() = runBlocking<Unit> {
        val server = TlsServer().also { closers += { it.close() } }
        val proxy = LocalConnectProxy(DirectPipe())
        assertFailsWith<IOException> {
            withContext(Dispatchers.IO) {
                proxy.httpClient(server.trustingClient).newCall(Request.Builder().url("${server.url}/x").build()).execute().close()
            }
        }
        assertEquals(0, server.server.requestCount)
    }
}
