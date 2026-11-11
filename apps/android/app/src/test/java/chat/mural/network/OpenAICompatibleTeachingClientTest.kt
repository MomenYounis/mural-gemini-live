package chat.mural.network

import chat.mural.core.LanguageRegistry
import chat.mural.core.TeachingPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenAICompatibleTeachingClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun openRouterAndSovereignEgSendBearerAndParseStructuredPostSessionJson() = runBlocking {
        for (provider in listOf(TeachingProvider.OPENROUTER, TeachingProvider.SOVEREIGN_EG)) {
            val client = OpenAICompatibleTeachingClient(
                provider = provider,
                readCredential = { "sk-test-credential-1234567890" },
                readModel = { "deepseek/deepseek-chat-v3-0324" },
                client = OkHttpClient(),
                baseUrl = server.url("/v1/"),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "```json\n{\"topic\":\"Daily Routine\",\"mistakes\":[{\"user_said\":\"I go yesterday\",\"correction\":\"I went yesterday\",\"arabic_meaning\":\"ذهبت بالأمس\",\"explanation\":\"Use past simple.\"}]}\n```"
                          }
                        }
                      ],
                      "usage": {"prompt_tokens": 25, "completion_tokens": 40}
                    }
                    """.trimIndent()
                )
            )

            val english = LanguageRegistry.get("en")!!
            val result = client.respond(
                instructions = TeachingPolicy.postSessionAnalysis(english),
                input = "Learner: I go yesterday",
                schema = TeachingPolicy.postSessionSchema(),
                search = false,
                purpose = HelperPurpose.ASSESSMENT,
            )

            val recorded = server.takeRequest()
            assertEquals("/v1/chat/completions", recorded.path)
            assertEquals("Bearer sk-test-credential-1234567890", recorded.getHeader("Authorization"))
            assertTrue(result.text.startsWith("{"))
            assertTrue(result.text.contains("\"Daily Routine\""))
            assertEquals(25, result.usage.input)
            assertEquals(40, result.usage.output)
        }
    }

    @Test
    fun fetchesAndMergesLiveModelsFromEndpoint() = runBlocking {
        val client = OpenAICompatibleTeachingClient(
            provider = TeachingProvider.SOVEREIGN_EG,
            readCredential = { "sk-sovereign-test-key-123456" },
            readModel = { "gemini-3.5-flash" },
            client = OkHttpClient(),
            baseUrl = server.url("/v1/"),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [
                    {"id": "custom-sovereign-model-1", "name": "Custom Sovereign Model 1"}
                  ]
                }
                """.trimIndent()
            )
        )

        val models = client.fetchAvailableModels()
        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sk-sovereign-test-key-123456", recorded.getHeader("Authorization"))
        assertTrue(models.any { it.id == "custom-sovereign-model-1" })
        assertTrue(models.any { it.id == "gemini-3.5-flash" })
    }
}
