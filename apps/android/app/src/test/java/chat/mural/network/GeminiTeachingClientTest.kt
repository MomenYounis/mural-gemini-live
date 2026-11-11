package chat.mural.network

import chat.mural.core.ProviderFailureKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiTeachingClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: GeminiTeachingClient
    private var credential: String? = "owner-fake-gemini-test-only"

    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = GeminiTeachingClient({ credential }, OkHttpClient(), server.url("/v1beta/"))
    }
    @After fun teardown() { server.shutdown() }

    private fun response(text: String = "مرحبًا café 你好", finish: String? = "STOP", tokens: Boolean = true): String = buildJsonObject {
        put("candidates", buildJsonArray { add(buildJsonObject {
            put("content", buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) }) })
            if (finish != null) put("finishReason", finish)
        }) })
        if (tokens) put("usageMetadata", buildJsonObject { put("promptTokenCount", 12); put("candidatesTokenCount", 7); put("thoughtsTokenCount", 3) })
    }.toString()

    @Test fun sendsOnlyGoogleContractWithHeaderCredentialAndPreservesUnicodeUsage() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        val result = api.respond("teaching policy", "hello")
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", request.path)
        assertEquals(credential, request.getHeader("x-goog-api-key"))
        assertNull(request.getHeader("Authorization")); assertFalse(request.requestUrl!!.query.orEmpty().contains("key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("teaching policy", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val turn = body["contents"]!!.jsonArray.single().jsonObject
        assertEquals("user", turn["role"]!!.jsonPrimitive.content)
        assertEquals("hello", turn["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(body["store"]); assertNull(body["input"]); assertNull(body["tools"])
        assertEquals("مرحبًا café 你好", result.text); assertEquals(APIUsage(12, 10, 0), result.usage)
    }

    @Test fun credentialsAreReadForEveryRequestAndDeletionStopsNewRequests() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        api.respond("p", "q"); assertEquals(credential, server.takeRequest().getHeader("x-goog-api-key"))
        credential = "replacement-fake-test-only"
        server.enqueue(MockResponse().setBody(response()))
        api.respond("p", "q"); assertEquals(credential, server.takeRequest().getHeader("x-goog-api-key"))
        credential = null
        try { api.respond("p", "q"); fail("deleted credential accepted") } catch (_: GeminiException.MissingKey) { }
        assertEquals(2, server.requestCount)
    }

    @Test fun controlCharactersAndWhitespaceCredentialsNeverReachHttpOrDiagnostics() = runBlocking {
        for (invalid in listOf("fake\u0000secret", "fake\nsecret", "fake secret", "", "é-secret")) {
            credential = invalid
            try { api.respond("p", "q"); fail("invalid credential accepted") }
            catch (error: GeminiException.MissingKey) { assertFalse(error.toString().contains("secret")) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun neverSendsGroundedSearchWhenNativeSuggestionsAndBudgetAreUnsupported() = runBlocking {
        try { api.respond("p", "q", search = true); fail("unsupported search sent") }
        catch (error: GeminiException.SearchUnavailable) { assertFalse(error.message.orEmpty().contains("OpenAI")) }
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsRedirectEvenWhenInjectedClientFollowsRedirects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
        try { api.respond("p", "q"); fail("followed redirect") }
        catch (error: GeminiException.Http) { assertEquals(307, error.status) }
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsNonLoopbackTestOriginsAndUnsafeModelNames() {
        for (url in listOf("https://example.com/", "http://127.0.0.1/?key=secret", "http://owner@localhost/")) {
            try { GeminiTeachingClient({ credential }, OkHttpClient(), url.toHttpUrl()); fail("unsafe origin accepted") }
            catch (_: IllegalArgumentException) { }
        }
        for (model in listOf("../other", "gemini-3.8-flash:streamGenerateContent", "https://example.com", "gpt-6-luna")) {
            try { GeminiTeachingClient({ credential }, OkHttpClient(), server.url("/"), model); fail("unsafe model accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun requestAndResponsePayloadsAreBounded() = runBlocking {
        try { api.respond("x".repeat(262_145), "q"); fail("oversized request accepted") }
        catch (_: GeminiException.InvalidResponse) { }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
        try { api.respond("p", "q"); fail("oversized response accepted") } catch (_: GeminiException.InvalidResponse) { }
        server.enqueue(MockResponse().setBody(response("x".repeat(65_537))))
        try { api.respond("p", "q"); fail("oversized text accepted") } catch (_: GeminiException.InvalidResponse) { }
    }

    @Test fun googleErrorsHaveBoundedCodesAndNeverRevealRawBodyOrSecret() = runBlocking {
        for ((status, body) in listOf(
            400 to """{"error":{"status":"INVALID_ARGUMENT","message":"owner-fake-gemini-test-only private text","details":[{"reason":"API_KEY_INVALID"}]}}""",
            429 to """{"error":{"status":"RESOURCE_EXHAUSTED","message":"private billing data"}}""",
            503 to """{"error":{"status":"owner-fake-gemini-test-only","message":"private"}}""",
            401 to "x".repeat(16_385))) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            try { api.respond("p", "q"); fail("provider error accepted") }
            catch (error: GeminiException.Http) {
                assertFalse(error.toString().contains("private")); assertFalse(error.toString().contains("owner-fake"))
                assertFalse(error.code.orEmpty().contains("owner-fake"))
                assertNull(error.cause)
                assertEquals(when (status) { 400, 401 -> ProviderFailureKind.authentication; 429 -> ProviderFailureKind.quota; else -> ProviderFailureKind.unavailable }, error.kind)
            }
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun rejectsMalformedBlockedRefusedAndIncompleteResponsesWithoutRetry() = runBlocking {
        val bodies = listOf("not json", "[]", "{}", response("partial", "MAX_TOKENS"), response("partial", null),
            response("unsafe", "SAFETY"), """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"private thought"}]}}]}""")
        for (body in bodies) {
            server.enqueue(MockResponse().setBody(body))
            try { api.respond("p", "q"); fail("incomplete/refused result accepted") } catch (_: GeminiException) { }
        }
        assertEquals(bodies.size, server.requestCount)
    }

    @Test fun structuredAssessmentUsesCurrentRawSchemaAndValidatesBeforeReturningEvidence() = runBlocking {
        val schema = Json.parseToJsonElement("""{"type":"object","required":["level","quote"],"additionalProperties":false,"properties":{"level":{"type":"integer","minimum":0,"maximum":5},"quote":{"type":"string"}}}""").jsonObject
        server.enqueue(MockResponse().setBody(response("""{"level":2,"quote":"I am café"}""")))
        assertTrue(api.respond("assessment", "exact evidence", schema).text.contains("I am café"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val format = body["generationConfig"]!!.jsonObject["responseFormat"]!!.jsonObject["text"]!!.jsonObject
        assertEquals(JsonPrimitive("APPLICATION_JSON"), format["mimeType"]); assertEquals(schema, format["schema"])
        assertNull(body["text"]); assertNull(body["generationConfig"]!!.jsonObject["responseSchema"])
        for (text in listOf("not json", """{"level":6,"quote":"I am"}""", """{"level":1.5,"quote":"I am"}""", """{"level":2}""", """{"level":2,"quote":"I am","extra":"x"}""")) {
            server.enqueue(MockResponse().setBody(response(text)))
            try { api.respond("p", "q", schema); fail("invalid evidence schema accepted") } catch (_: GeminiException.InvalidResponse) { }
        }
    }

    @Test fun structuredAssessmentValidatesNestedWordsEnumsAndBounds() = runBlocking {
        val schema = Json.parseToJsonElement("""{"type":"object","required":["words"],"properties":{"words":{"type":"array","maxItems":1,"items":{"type":"object","required":["kind","confidence"],"properties":{"kind":{"type":"string","enum":["retrieved"]},"confidence":{"type":"number","minimum":0,"maximum":1}}}}}}""").jsonObject
        for (text in listOf("""{"words":[{"kind":"invented","confidence":0.5}]}""", """{"words":[{"kind":"retrieved","confidence":2}]}""", """{"words":[{"kind":"retrieved","confidence":1},{"kind":"retrieved","confidence":1}]}""")) {
            server.enqueue(MockResponse().setBody(response(text)))
            try { api.respond("p", "q", schema); fail("invalid nested assessment accepted") } catch (_: GeminiException.InvalidResponse) { }
        }
    }

    @Test fun assessmentStreamsSplitUnicodeJsonButReturnsOnlyCompletedValidatedEvidence() = runBlocking {
        val schema = Json.parseToJsonElement("""{"type":"object","required":["level","quote"],"additionalProperties":false,"properties":{"level":{"type":"integer","minimum":0,"maximum":5},"quote":{"type":"string"}}}""").jsonObject
        val text = """{"level":2,"quote":"مرحبًا café 你好"}"""
        val split = text.indexOf("你好")
        val body = "data: ${response(text.take(split), null, false)}\r\n\r\n" +
            "data: ${response(text.drop(split), null, false)}\n\n" +
            "data: ${response("", "STOP", false)}\n\n" +
            "data: {\"usageMetadata\":{\"promptTokenCount\":21,\"candidatesTokenCount\":18,\"thoughtsTokenCount\":5}}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setChunkedBody(body, 1))
        val result = api.respond("assessment policy", "exact evidence", schema, purpose = HelperPurpose.ASSESSMENT)
        assertEquals(text, result.text)
        assertEquals(APIUsage(21, 23), result.usage)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals(credential, request.getHeader("x-goog-api-key"))
        assertNull(request.getHeader("Authorization"))
        val config = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject
        val format = config["responseFormat"]!!.jsonObject["text"]!!.jsonObject
        assertEquals(JsonPrimitive("APPLICATION_JSON"), format["mimeType"])
        assertEquals(schema, format["schema"])
        assertEquals(JsonPrimitive(6_144), config["maxOutputTokens"])
        assertEquals(JsonPrimitive("LOW"), config["thinkingConfig"]!!.jsonObject["thinkingLevel"])
        assertEquals(1, server.requestCount)
    }

    @Test fun assessmentNeverReturnsPartialMalformedOrSchemaInvalidStreamEvidence() = runBlocking {
        val schema = Json.parseToJsonElement("""{"type":"object","required":["level"],"additionalProperties":false,"properties":{"level":{"type":"integer","minimum":0,"maximum":5}}}""").jsonObject
        val cases = listOf(
            "data: ${response("""{"level":2}""", null)}\n\n" to false,
            "data: ${response("""{"level":2}""", "MAX_TOKENS")}\n\n" to false,
            "data: ${response("""{"level":""", "STOP")}\n\n" to true,
            "data: ${response("""{"level":6}""", "STOP")}\n\n" to true,
            "data: ${response("{}", "STOP")}\n\n" to true,
            "data: not-json\n\n" to true,
        )
        for ((body, invalid) in cases) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            try {
                api.respond("policy", "evidence", schema, purpose = HelperPurpose.ASSESSMENT)
                fail("partial or invalid assessment returned")
            } catch (error: GeminiException) {
                assertTrue(if (invalid) error is GeminiException.InvalidResponse else error is GeminiException.Incomplete)
            }
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response("""{"level":2}""")))
        try {
            api.respond("policy", "evidence", schema, purpose = HelperPurpose.ASSESSMENT)
            fail("non-SSE assessment returned")
        } catch (_: GeminiException.InvalidResponse) { }
        assertEquals(cases.size + 1, server.requestCount)
    }

    @Test fun onlyAssessmentGetsTheLongerTransportBudgetAndStreamingEndpoint() = runBlocking {
        val budgets = mutableListOf<Pair<Int, Long>>()
        val local = GeminiTeachingClient({ credential }, OkHttpClient.Builder()
            .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                budgets += chain.readTimeoutMillis() to chain.call().timeout().timeoutNanos()
                chain.proceed(chain.request())
            }.build(), server.url("/v1beta/"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: ${response()}\n\n"))
        local.respond("policy", "evidence", purpose = HelperPurpose.ASSESSMENT)
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse", server.takeRequest().path)
        server.enqueue(MockResponse().setBody(response()))
        local.respond("policy", "evidence", purpose = HelperPurpose.LOOKUP)
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", server.takeRequest().path)
        assertEquals(listOf(90_000 to TimeUnit.SECONDS.toNanos(95), 45_000 to TimeUnit.SECONDS.toNanos(60)), budgets)
    }

    @Test fun assessmentCanWaitForDelayedSseWithoutExtendingOtherHelperTimeouts() = runBlocking {
        val local = GeminiTeachingClient({ credential }, OkHttpClient.Builder()
            .readTimeout(100, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build(), server.url("/v1beta/"))
        val schema = Json.parseToJsonElement("""{"type":"object","required":["level"],"properties":{"level":{"type":"integer"}}}""").jsonObject
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: ${response("""{"level":2}""")}\n\n").setHeadersDelay(1, TimeUnit.SECONDS))
        val result = withTimeout(5_000) {
            local.respond("policy", "evidence", schema, purpose = HelperPurpose.ASSESSMENT)
        }
        assertEquals("""{"level":2}""", result.text)
        server.enqueue(MockResponse().setBody(response()).setHeadersDelay(1, TimeUnit.SECONDS))
        try {
            local.respond("policy", "evidence", purpose = HelperPurpose.LOOKUP)
            fail("ordinary helper ignored its read timeout")
        } catch (_: GeminiException.Unavailable) { }
        assertEquals(2, server.requestCount)
    }

    @Test fun assessmentKeepsSanitizedHttpErrorsAndDoesNotRetryOrFollowRedirects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"status":"UNAVAILABLE","message":"private evidence"}}"""))
        try {
            api.respond("policy", "evidence", purpose = HelperPurpose.ASSESSMENT)
            fail("HTTP failure accepted")
        } catch (error: GeminiException.Http) {
            assertEquals(503, error.status); assertEquals("UNAVAILABLE", error.code)
            assertFalse(error.toString().contains("private")); assertNull(error.cause)
        }
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
        try {
            api.respond("policy", "evidence", purpose = HelperPurpose.ASSESSMENT)
            fail("assessment redirect followed")
        } catch (error: GeminiException.Http) { assertEquals(307, error.status) }
        assertEquals(2, server.requestCount)
    }

    @Test fun cancelledAssessmentCannotReturnALateCompletedResponse() = runBlocking {
        val admitted = java.util.concurrent.atomic.AtomicBoolean()
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.atomic.AtomicBoolean()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                admitted.set(true)
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: ${response()}\n\n")
            }
        }
        val job = launch {
            api.respond("policy", "evidence", purpose = HelperPurpose.ASSESSMENT)
            returned.set(true)
        }
        try {
            withTimeout(5_000) { while (!admitted.get()) delay(10) }
            withTimeout(1_500) { job.cancelAndJoin() }
        } finally { release.countDown() }
        assertTrue(job.isCancelled)
        assertFalse(returned.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun meaningStreamEmitsUnicodeBeforeCompletionAndKeepsFinalUsage() = runBlocking {
        val body = "data: ${response("مرحبًا", null, false)}\r\n\r\n" +
            "data: ${response(" café 你好", null, false)}\n\n" + "data: ${response("", "STOP")}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setChunkedBody(body, 1))
        val seen = mutableListOf<String>()
        val result = api.streamMeaning("meaning policy", "hello") { seen += it }
        assertEquals(listOf("مرحبًا", "مرحبًا café 你好"), seen)
        assertEquals("مرحبًا café 你好", result.text); assertEquals(APIUsage(12, 10), result.usage)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertNull(Json.parseToJsonElement(request.body.readUtf8()).jsonObject["stream"])
    }

    @Test fun meaningStreamAcceptsMultilineDataAndFinalUsageAfterStop() = runBlocking {
        val body = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]},\n" +
            "data: \"finishReason\":\"STOP\"}]}\n\n" +
            "data: {\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":4}}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val result = api.streamMeaning("p", "q") {}
        assertEquals(APIUsage(9, 4), result.usage)
    }

    @Test fun refusesUnfinishedMalformedOversizedOrLateStreamText() = runBlocking {
        val bodies = listOf("data: ${response("partial", null)}\n\n", "data: [DONE]\n\n", "data: not-json\n\n",
            "data: " + "x".repeat(65_537), "data: ${response()}\n\ndata: ${response("late", null)}\n\n",
            "data: ${response("partial", "MAX_TOKENS")}\n\n")
        for (body in bodies) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            try { api.streamMeaning("p", "q") {}; fail("invalid stream accepted") } catch (_: GeminiException) { }
        }
        assertEquals(bodies.size, server.requestCount)
    }

    @Test fun meaningStreamRejectsAnOrdinaryJsonContentType() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response()))
        try { api.streamMeaning("p", "q") {}; fail("non SSE stream accepted") } catch (_: GeminiException.InvalidResponse) { }
    }

    @Test fun cancellationClosesStalledMeaningOrdinaryAndAssessmentResponsesWithoutRetry() = runBlocking {
        // Install throttling after the request is received; enqueued throttling also stalls uploads.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val stream = request.path.orEmpty().contains("streamGenerateContent")
                return MockResponse().setHeader("Content-Type", if (stream) "text/event-stream" else "application/json")
                    .setBody(if (stream) "data: ${response()}\n\n" else response()).throttleBody(1, 1, TimeUnit.SECONDS)
            }
        }
        for (purpose in listOf(null, HelperPurpose.MEANING, HelperPurpose.ASSESSMENT)) {
            val bodyStarted = java.util.concurrent.atomic.AtomicBoolean()
            val local = GeminiTeachingClient({ credential }, OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun responseBodyStart(call: okhttp3.Call) { bodyStarted.set(true) }
            }).build(), server.url("/v1beta/"))
            val job = launch {
                if (purpose == HelperPurpose.MEANING) local.streamMeaning("p", "q") {}
                else local.respond("p", "q", purpose = purpose)
            }
            // Synchronize with the body reader, rather than cancelling during request upload.
            withTimeout(5_000) { while (!bodyStarted.get()) delay(10) }
            withTimeout(1_500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun partialUsageMetadataRetainsEarlierKnownCountersWithoutCountingChunksTwice() {
        val decoder = GeminiTeachingResponse()
        decoder.accept(Json.parseToJsonElement(response("hello", null)).jsonObject)
        decoder.accept(Json.parseToJsonElement("""{"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"candidatesTokenCount":9}}""").jsonObject)
        assertEquals(APIUsage(12, 12), decoder.result(null).usage)
        assertEquals(ProviderFailureKind.rateLimit, GeminiException.Http(429, "RATE_LIMIT_EXCEEDED").kind)
        assertEquals(ProviderFailureKind.quota, GeminiException.Http(429, "QUOTA_EXCEEDED").kind)
    }

    @Test fun decodingRetainsOnlyActualSafeGroundingAndNeverThoughtsOrInventedLinks() {
        val decoder = GeminiTeachingResponse()
        decoder.accept(Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private notes"},{"text":"A sourced reply"}]},"finishReason":"STOP","groundingMetadata":{"webSearchQueries":["actual query","actual query"],"groundingChunks":[{"web":{"uri":"http://bad.example","title":"bad"}},{"web":{"uri":"https://owner@example.com","title":"bad"}},{"web":{"uri":"javascript:alert(1)","title":"bad"}},{"web":{"uri":"https://example.com/news","title":"Source"}},{"web":{"uri":"https://example.com/news","title":"Duplicate"}}]}}]}""").jsonObject)
        val result = decoder.result(null)
        assertEquals("A sourced reply", result.text); assertEquals(1, result.sources.size)
        assertEquals("Source", result.sources.single().title); assertEquals("https://example.com/news", result.sources.single().url)
        assertEquals(1, result.usage.searches)
    }
}
