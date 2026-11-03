package com.zapiack.push.core

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.*

class FakeTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    val responses = ArrayDeque<HttpResponse>()
    var fail = false

    @Synchronized
    override fun send(request: HttpRequest): HttpResponse {
        requests += request
        if (fail) throw java.io.IOException("offline")
        return responses.removeFirstOrNull() ?: HttpResponse(200, """{"accepted":1,"ignored":0,"rejected":[]}""")
    }

    fun bodies() = requests.mapNotNull { it.body }.map { JSONObject(it) }
}

fun tempDir(): File = Files.createTempDirectory("zapiack-test").toFile()

val config = ZapiackConfig("pk_test_123", "https://api.example.test/v1", "install-1")

/** The data the service sends to an Android device for a push with a picture, buttons and tracking. */
val fcmData = mapOf(
    "orderId" to "ord_789",
    "zapiack_m" to "msg_1",
    "zapiack_d" to "del_1",
    "zapiack_l" to "myshop://orders/ord_789",
    "zapiack_i" to "https://cdn.example.test/p.jpg",
    "zapiack_e" to "order_opened",
    "zapiack_t" to """{"w":45,"s":true}""",
    "zapiack_a" to """[{"id":"track","title":"Track","event":"track_clicked","link":"myshop://orders/ord_789/track"}]""",
    "zapiack_title" to "Order shipped",
    "zapiack_body" to "On its way",
)

class PayloadTest {
    @Test fun readsTheServiceBlock() {
        val block = assertNotNull(PushBlock.fromData(fcmData))
        assertEquals("msg_1", block.messageId)
        assertEquals("del_1", block.deliveryId)
        assertEquals(45, block.windowMinutes)
        assertEquals(true, block.screens)
        assertEquals("track", block.actions.single().id)
    }

    @Test fun ignoresPushesThatAreNotOurs() {
        assertNull(PushBlock.fromData(mapOf("a" to "b")))
        assertNull(PushBlock.fromData(mapOf("zapiack_m" to "", "zapiack_d" to "")))
    }

    @Test fun survivesBrokenTrackingAndButtons() {
        val block = assertNotNull(PushBlock.fromData(mapOf("zapiack_m" to "m", "zapiack_d" to "d", "zapiack_t" to "{oops", "zapiack_a" to "nope")))
        assertNull(block.windowMinutes)
        assertTrue(block.actions.isEmpty())
    }

    @Test fun picturesMustBeHttps() {
        fun url(s: String?) = Processing.imageUrl(PushBlock("m", "d", image = s))
        assertNotNull(url("https://cdn.example.test/a.png"))
        assertNull(url("http://cdn.example.test/a.png"))
        assertNull(url("file:///etc/passwd"))
        assertNull(url("https://"))
        assertNull(url(null))
    }

    @Test fun scriptLinksAreNotHandedOver() {
        assertNull(Processing.link("javascript:alert(1)"))
        assertNull(Processing.link("file:///x"))
        assertEquals("myshop://orders/1", Processing.link("myshop://orders/1"))
        assertNull(Processing.link(null))
    }
}

class TapTest {
    private val at = Instant.parse("2026-11-01T10:00:00Z")
    private val block = PushBlock.fromData(fcmData)!!

    @Test fun bodyTapReportsClickedAndOpensTheWindow() {
        val r = assertNotNull(Processing.tap(block, null, at))
        assertEquals(EventType.Clicked, r.events.single().type)
        assertEquals(r.session.sessionId, r.events.single().sessionId)
        assertEquals("order_opened", r.events.single().name)
        assertEquals(45, r.session.windowMinutes)
        assertEquals("myshop://orders/ord_789", r.tap.link)
    }

    @Test fun buttonTapReportsTheActionAndItsOwnLink() {
        val r = assertNotNull(Processing.tap(block, "track", at))
        assertEquals(EventType.Action, r.events.single().type)
        assertEquals("track", r.events.single().actionId)
        assertEquals("myshop://orders/ord_789/track", r.tap.link)
        assertEquals("track_clicked", r.tap.event)
    }

    @Test fun unknownButtonsReportNothing() = assertNull(Processing.tap(block, "made-up", at))

    @Test fun defaultWindowMatchesTheOtherSdks() {
        val r = assertNotNull(Processing.tap(PushBlock("m", "d"), null, at))
        assertEquals(30, r.session.windowMinutes)
        assertFalse(r.session.screens)
    }

    @Test fun sessionClosesAfterItsWindow() {
        val s = assertNotNull(Processing.tap(block, null, at)).session
        assertTrue(s.isOpen(at.plusSeconds(45 * 60)))
        assertFalse(s.isOpen(at.plusSeconds(45 * 60 + 1)))
    }
}

class EventShapeTest {
    @Test fun encodesTheFieldsTheServiceExpects() {
        val json = SdkEvent(EventType.Custom, "d", sessionId = "s", name = "bought", properties = mapOf("total" to 12.5, "gift" to true)).toJson()
        assertEquals("custom", json.getString("type"))
        assertEquals("d", json.getString("deliveryId"))
        assertEquals(12.5, json.getJSONObject("properties").getDouble("total"))
        assertFalse(json.has("actionId"))
        Instant.parse(json.getString("at"))
    }

    @Test fun roundTripsThroughAFile() {
        val e = SdkEvent(EventType.Screen, "d", sessionId = "s", name = "/a", previousScreen = "/b")
        assertEquals(e, SdkEvent.fromJson(JSONObject(e.toJson().toString())))
    }
}

class QueueTest {
    @Test fun keepsOrderEvenInTheSameMillisecond() {
        val q = EventQueue(tempDir())
        val at = Instant.parse("2026-11-01T10:00:00Z")
        repeat(5) { q.add(SdkEvent(EventType.Custom, "d", name = "e$it", at = at), at) }
        assertEquals(listOf("e0", "e1", "e2", "e3", "e4"), q.pending().map { it.second.name })
    }

    @Test fun twoWritersDoNotLoseEachOthersEvents() {
        val dir = tempDir()
        val a = EventQueue(dir)
        val b = EventQueue(dir)
        val done = CountDownLatch(40)
        repeat(40) { n -> thread { (if (n % 2 == 0) a else b).add(SdkEvent(EventType.Received, "d$n")); done.countDown() } }
        done.await()
        assertEquals(40, a.count)
    }

    @Test fun flushSendsInBatchesAndEmptiesTheQueue() {
        val q = EventQueue(tempDir())
        repeat(250) { q.add(SdkEvent(EventType.Received, "d$it")) }
        val t = FakeTransport()
        assertTrue(q.flush(SdkApi(config, t)))
        assertEquals(3, t.requests.size)
        assertEquals(0, q.count)
        assertEquals(100, t.bodies()[0].getJSONArray("events").length())
    }

    @Test fun failureKeepsEventsForALaterTry() {
        val q = EventQueue(tempDir())
        q.add(SdkEvent(EventType.Received, "d"))
        val t = FakeTransport().apply { fail = true }
        assertFalse(q.flush(SdkApi(config, t)))
        assertEquals(1, q.count)
    }

    @Test fun busyAnswersAreRetriedButRefusalsAreDropped() {
        val q = EventQueue(tempDir())
        q.add(SdkEvent(EventType.Received, "d"))
        val t = FakeTransport()
        t.responses += HttpResponse(429, "{}")
        q.flush(SdkApi(config, t))
        assertEquals(1, q.count)
        t.responses += HttpResponse(401, "{}")
        q.flush(SdkApi(config, t))
        assertEquals(0, q.count)
    }

    @Test fun eventsOlderThanTheServiceAcceptsAreDiscarded() {
        val q = EventQueue(tempDir())
        val old = Instant.now().minusSeconds(7 * 24 * 3600)
        q.add(SdkEvent(EventType.Received, "old", at = old), old)
        val t = FakeTransport()
        q.flush(SdkApi(config, t))
        assertEquals(0, t.requests.size)
        assertEquals(0, q.count)
    }

    @Test fun theQueueIsBounded() {
        val q = EventQueue(tempDir())
        repeat(EventQueue.MAX_EVENTS + 20) { q.add(SdkEvent(EventType.Received, "d$it")) }
        assertEquals(EventQueue.MAX_EVENTS, q.count)
        assertEquals("d20", q.pending().first().second.deliveryId)
    }

    @Test fun unreadableFilesAreRemoved() {
        val dir = tempDir()
        val q = EventQueue(dir)
        File(dir, "000-bad.json").writeText("not json")
        assertEquals(0, q.count)
        assertEquals(0, dir.listFiles()!!.size)
    }
}

class ApiTest {
    @Test fun requestsCarryTheKeyAndGoToTheSdkPaths() {
        val t = FakeTransport()
        SdkApi(config, t).register(DeviceRegistration("install-1", "abc"))
        val r = t.requests.single()
        assertEquals("https://api.example.test/v1/push/sdk/devices", r.url)
        assertEquals("PUT", r.method)
        assertEquals("pk_test_123", r.headers["X-Zapiack-App-Key"])
    }

    @Test fun userLinkIsAbsentSetOrNull() {
        assertFalse(DeviceRegistration("i", "t").toJson().has("externalUserId"))
        assertEquals("u1", DeviceRegistration("i", "t", UserLink.Set("u1")).toJson().getString("externalUserId"))
        assertTrue(DeviceRegistration("i", "t", UserLink.Clear).toJson().isNull("externalUserId"))
        assertTrue(DeviceRegistration("i", "t", UserLink.Clear).toJson().has("externalUserId"))
    }

    @Test fun failedRegistrationThrows() {
        val t = FakeTransport().apply { responses += HttpResponse(400, "{}") }
        assertEquals(400, assertFailsWith<RegistrationFailed> { SdkApi(config, t).register(DeviceRegistration("i", "t")) }.status)
    }
}

class ClientTest {
    private lateinit var t: FakeTransport
    private lateinit var storage: MemoryStorage
    private var clock = Instant.parse("2026-11-01T10:00:00Z")
    private lateinit var client: PushClient
    private val block = PushBlock.fromData(fcmData)!!

    @BeforeTest fun setUp() {
        t = FakeTransport()
        storage = MemoryStorage()
        client = PushClient(config, storage, EventQueue(tempDir()), t) { clock }
    }

    @Test fun registersTheTokenWithTheRightPlatform() {
        client.deviceToken = "tok"
        client.register("granted", "2.0")
        val body = t.bodies().single()
        assertEquals("tok", body.getString("token"))
        assertEquals("android", body.getString("platform"))
        assertEquals("2.0", body.getString("appVersion"))
    }

    @Test fun nothingIsSentWithoutAToken() {
        client.register(null, null)
        assertEquals(0, t.requests.size)
    }

    @Test fun userChangeIsSentOnceAndSignOutIsExplicit() {
        client.deviceToken = "tok"
        client.login("user_1")
        client.register(null, null)
        assertEquals("user_1", t.bodies()[0].getString("externalUserId"))
        client.register(null, null)
        assertFalse(t.bodies()[1].has("externalUserId"))
        client.logout()
        client.register(null, null)
        assertTrue(t.bodies()[2].isNull("externalUserId"))
    }

    @Test fun userChangeSurvivesAFailedRegistration() {
        client.deviceToken = "tok"
        client.login("user_1")
        t.responses += HttpResponse(500, "{}")
        runCatching { client.register(null, null) }
        client.register(null, null)
        assertEquals("user_1", t.bodies()[1].getString("externalUserId"))
    }

    @Test fun tagsAreKeptAndMerged() {
        client.deviceToken = "tok"
        client.addTags(mapOf("plan" to "pro", "age" to 3))
        client.addTags(mapOf("plan" to "team"))
        client.removeTags(listOf("age"))
        client.register(null, null)
        assertEquals("""{"plan":"team"}""", t.bodies().single().getJSONObject("tags").toString())
    }

    @Test fun tapOpensAWindowAndScreensAreCreditedInsideIt() {
        assertNotNull(client.handleTap(block))
        client.screen("/orders")
        client.screen("/orders/ord_789")
        val events = client.queue.pending().map { it.second }
        assertEquals(listOf(EventType.Clicked, EventType.Screen, EventType.Screen), events.map { it.type })
        assertNull(events[1].previousScreen)
        assertEquals("/orders", events[2].previousScreen)
        assertEquals(events[0].sessionId, events[1].sessionId)
    }

    @Test fun nothingIsCreditedAfterTheWindowCloses() {
        client.handleTap(block)
        clock = clock.plusSeconds(46 * 60)
        client.screen("/late")
        assertFalse(client.track("late_goal"))
        assertEquals(1, client.queue.count)
    }

    @Test fun screensAreSkippedWhenThePushDidNotAskForThem() {
        client.handleTap(PushBlock("m", "d"))
        client.screen("/x")
        assertTrue(client.track("goal"))
        assertEquals(listOf(EventType.Clicked, EventType.Custom), client.queue.pending().map { it.second.type })
    }

    @Test fun theLatestTapWins() {
        client.handleTap(block)
        client.handleTap(PushBlock("m2", "del_2", screens = true))
        client.track("bought")
        assertEquals("del_2", client.queue.pending().last().second.deliveryId)
    }

    @Test fun arrivalIsQueuedWithoutASession() {
        client.received(block)
        val e = client.queue.pending().single().second
        assertEquals(EventType.Received, e.type)
        assertNull(e.sessionId)
    }

    @Test fun tracksNothingWithoutATap() {
        assertFalse(client.track("goal"))
        client.screen("/x")
        assertEquals(0, client.queue.count)
    }
}

class StorageTest {
    @Test fun installationIdIsMadeOnceAndKept() {
        val s = MemoryStorage()
        assertEquals(SharedState.installationId(s), SharedState.installationId(s))
    }

    @Test fun aServiceOnlyLoadsWhatTheAppSaved() {
        val s = MemoryStorage()
        assertNull(SharedState.load(s))
        SharedState.save(s, "sk_wrong", SharedState.DEFAULT_BASE_URL)
        assertNull(SharedState.load(s), "a secret key must never be accepted")
        SharedState.save(s, "pk_ok", SharedState.DEFAULT_BASE_URL)
        assertEquals("pk_ok", SharedState.load(s)?.appKey)
    }

    @Test
    fun `reads a custom look, keeping only what is safe to draw`() {
        val base = mapOf("zapiack_m" to "m", "zapiack_d" to "d")
        val block = assertNotNull(PushBlock.fromData(base + ("zapiack_s" to "{\"c\":\"#7A1F13\",\"t\":\"https://cdn.shop.test/a.jpg\",\"e\":\"shine\"}")))
        assertEquals(PushBlock.Style("#7A1F13", "https://cdn.shop.test/a.jpg", true), block.style)

        // A colour that is not #RRGGBB, a thumbnail that is not https and an unknown effect are each dropped, not trusted.
        val dirty = assertNotNull(PushBlock.fromData(base + ("zapiack_s" to "{\"c\":\"red\",\"t\":\"http://x.test/a.png\",\"e\":\"spin\"}")))
        assertNull(dirty.style)
        assertNull(PushBlock.fromData(base)?.style)
        assertNull(PushBlock.fromData(base + ("zapiack_s" to "{oops"))?.style)
    }
}
