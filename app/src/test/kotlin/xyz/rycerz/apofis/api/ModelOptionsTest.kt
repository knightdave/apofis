package xyz.rycerz.apofis.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `GET /api/model/options` (docs/hermes-api-notes.md): only `model`, `provider` and each provider's `slug` and `models`
 * are read. The bodies are hand-written and account-free, kept here rather than under fixtures/ (HermesApiTest serves
 * every file there).
 */
class ModelOptionsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HermesApi(server.url("/").toString(), "secret-key")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    // The documented shape with the keys a real response carries around the four that are read.
    private val minimal = """
        {"providers":[
          {"slug":"alpha","name":"Alpha","is_current":true,"is_user_defined":false,"models":["alpha-large","alpha-small"],
           "total_models":2,"source":"config","authenticated":true,"free_tier":false,"pricing_pending":false,
           "unavailable_models":[],"capabilities":{"alpha-large":{"fast":true,"reasoning":true,"can_disable_reasoning":false}}},
          {"slug":"beta","name":"Beta","models":["beta-1"],"authenticated":true}
        ],"model":"alpha-large","provider":"alpha"}
    """.trimIndent()

    private val expected = ModelOptions(
        "alpha-large", "alpha",
        listOf(ProviderModels("alpha", listOf("alpha-large", "alpha-small")), ProviderModels("beta", listOf("beta-1"))),
    )

    @Test
    fun minimalFixture() = runTest {
        respond(minimal)
        assertEquals(expected, api.modelOptions())
    }

    @Test
    fun emptyProvidersDropped() = runTest {
        val long = "x".repeat(257)
        respond(
            """{"providers":[{"slug":"empty","models":[]},{"slug":"nomodels"},{"slug":"a","models":["","$long","ok","${"y".repeat(256)}"]},""" +
                """{"slug":"","models":["m"]},{"slug":"$long","models":["m"]},{"models":["m"]},{"slug":"gone","models":["$long"]}],""" +
                """"model":"$long","provider":""}""",
        )
        assertEquals(ModelOptions(null, null, listOf(ProviderModels("a", listOf("ok", "y".repeat(256))))), api.modelOptions())
    }

    @Test
    fun usageNeverParsed() = runTest {
        // A usage object, a warning, an env var name, pricing and featured models: none can change or enter the result.
        respond(
            """{"providers":[{"slug":"alpha","models":["alpha-large","alpha-small"],"auth_type":"api_key","key_env":"ALPHA_API_KEY",""" +
                """"warning":"quota nearly used","usage":{"windows":[{"used":99,"limit":100,"account":"someone"}]},"pricing":{"x":[1,2]},""" +
                """"featured_models":[{"id":"z"}]},{"slug":"beta","models":["beta-1"],"usage":"text","limit":5}],""" +
                """"model":"alpha-large","provider":"alpha","usage":{"total":1},"limit":{"a":null},"warning":["w"]}""",
        )
        assertEquals(expected, api.modelOptions())
    }

    @Test
    fun queryParameter() = runTest {
        respond(minimal)
        api.modelOptions()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/model/options?include_unconfigured=false", request.target)
        assertEquals("Bearer secret-key", request.headers["Authorization"])
    }

    @Test
    fun unauthorized() = runTest {
        respond("""{"error":{"code":"invalid_api_key"}}""", 401)
        try {
            api.modelOptions()
            fail("expected Unauthorized")
        } catch (e: ApiError) {
            assertEquals(ApiError.Unauthorized, e)
        }
    }

    @Test
    fun malformedBodyIsProtocol() = runTest {
        respond("""{"providers":[{"slug":"a","models":[1,null]}]}""")
        try {
            api.modelOptions()
            fail("expected Protocol")
        } catch (e: ApiError) {
            assertEquals(ApiError.Protocol, e)
        }
    }
}
