package software.amazon.location.auth

import android.content.Context
import aws.sdk.kotlin.services.cognitoidentity.model.Credentials
import aws.sdk.kotlin.services.location.LocationClient
import aws.smithy.kotlin.runtime.time.Instant
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import aws.smithy.kotlin.runtime.time.fromEpochMilliseconds
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.verify
import junit.framework.TestCase.assertFalse
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import software.amazon.location.auth.utils.AwsRegions
import software.amazon.location.auth.utils.Constants.ACCESS_KEY_ID
import software.amazon.location.auth.utils.Constants.API_KEY
import software.amazon.location.auth.utils.Constants.EXPIRATION
import software.amazon.location.auth.utils.Constants.IDENTITY_POOL_ID
import software.amazon.location.auth.utils.Constants.METHOD
import software.amazon.location.auth.utils.Constants.REGION
import software.amazon.location.auth.utils.Constants.SECRET_KEY
import software.amazon.location.auth.utils.Constants.SESSION_TOKEN
import software.amazon.location.auth.utils.Constants.TEST_IDENTITY_POOL_ID

private const val TEST_API_KEY = "dummyApiKey"

class LocationCredentialsProviderTest {

    private lateinit var context: Context
    private lateinit var locationClient: LocationClient
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        locationClient = mockk(relaxed = true)
        mockkConstructor(EncryptedSharedPreferences::class)
        mockkConstructor(CognitoCredentialsProvider::class)

        every { anyConstructed<EncryptedSharedPreferences>().initEncryptedSharedPreferences() } just runs
        every { anyConstructed<EncryptedSharedPreferences>().put(any(), any<String>()) } just runs
        every { anyConstructed<EncryptedSharedPreferences>().get(REGION) } returns "us-east-1"
        every { anyConstructed<EncryptedSharedPreferences>().clear() } just runs
    }

    @Test
    fun `constructor with Cognito initializes correctly`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "api"
        val provider = LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        assertNotNull(provider)
    }

    @Test
    fun `constructor with API key initializes correctly`() {
        val provider = LocationCredentialsProvider(context, TEST_API_KEY)
        assertNotNull(provider)
    }

    @Test
    fun `constructor with cached credentials for Cognito initializes correctly`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "cognito"
        every { anyConstructed<EncryptedSharedPreferences>().get(ACCESS_KEY_ID) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SECRET_KEY) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SESSION_TOKEN) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(EXPIRATION) } returns "11111"
        every { anyConstructed<EncryptedSharedPreferences>().get(IDENTITY_POOL_ID) } returns TEST_IDENTITY_POOL_ID
        val provider = LocationCredentialsProvider(context)
        assertNotNull(provider)
    }

    @Test
    fun `constructor with cached credentials for API key initializes correctly`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "apiKey"
        every { anyConstructed<EncryptedSharedPreferences>().get(API_KEY) } returns TEST_API_KEY
        val provider = LocationCredentialsProvider(context)
        assertNotNull(provider)
    }

    @Test
    fun `getCredentialsProvider returns cognito provider successfully`(){
        every { anyConstructed<EncryptedSharedPreferences>().get(ACCESS_KEY_ID) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SECRET_KEY) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SESSION_TOKEN) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(EXPIRATION) } returns "11111"
        val provider = LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        coroutineScope.launch {
            provider.verifyAndRefreshCredentials()
            assertNotNull(provider.getCredentialsProvider())
        }
    }

    @Test
    fun `getApiKeyProvider returns api key provider successfully`() {
        val provider = LocationCredentialsProvider(context, TEST_API_KEY)
        assertNotNull(provider.getApiKeyProvider())
    }
    @Test
    fun `isCredentialsValid returns true when credentials are valid`() {
        val expirationTime = Instant.fromEpochMilliseconds(Instant.now().epochMilliseconds + 10000) // 10 seconds in the future
        val mockCredentials = Credentials.invoke {
            expiration = expirationTime
            secretKey = "test"
            accessKeyId = "test"
            sessionToken = "test"
        }
        every { anyConstructed<CognitoCredentialsProvider>().getCachedCredentials() } returns mockCredentials
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "cognito"
        every { anyConstructed<EncryptedSharedPreferences>().get(ACCESS_KEY_ID) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SECRET_KEY) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SESSION_TOKEN) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(EXPIRATION) } returns "11111"
        every { anyConstructed<EncryptedSharedPreferences>().get(IDENTITY_POOL_ID) } returns TEST_IDENTITY_POOL_ID
        val provider = LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        coroutineScope.launch {
            provider.refresh()
            val result = provider.isCredentialsValid()
            assertTrue(result)
        }
    }

    @Test
    fun `isCredentialsValid returns false when credentials are expired`() {
        val expirationTime = Instant.fromEpochMilliseconds(Instant.now().epochMilliseconds - 10000) // 10 seconds in the past
        val mockCredentials = mockk<Credentials> {
            every { expiration } returns expirationTime
        }
        every { anyConstructed<CognitoCredentialsProvider>().getCachedCredentials() } returns mockCredentials
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "cognito"
        every { anyConstructed<EncryptedSharedPreferences>().get(ACCESS_KEY_ID) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SECRET_KEY) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SESSION_TOKEN) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(EXPIRATION) } returns "11111"
        every { anyConstructed<EncryptedSharedPreferences>().get(IDENTITY_POOL_ID) } returns TEST_IDENTITY_POOL_ID
        val provider = LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        coroutineScope.launch {
            provider.refresh()
            val result = provider.isCredentialsValid()
            assertFalse(result)
        }
    }
    @Test
    fun `clear successfully clears cognito credentials`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "cognito"
        every { anyConstructed<EncryptedSharedPreferences>().get(ACCESS_KEY_ID) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SECRET_KEY) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(SESSION_TOKEN) } returns "test"
        every { anyConstructed<EncryptedSharedPreferences>().get(EXPIRATION) } returns "11111"
        every { anyConstructed<EncryptedSharedPreferences>().get(IDENTITY_POOL_ID) } returns TEST_IDENTITY_POOL_ID
        val provider = LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        coroutineScope.launch {
            provider.verifyAndRefreshCredentials()
            provider.clear()
        }
    }

    @Test
    fun `check credentials`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "cognito"
        every { anyConstructed<EncryptedSharedPreferences>().get(IDENTITY_POOL_ID) } returns TEST_IDENTITY_POOL_ID
        val provider = LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        coroutineScope.launch {
            provider.verifyAndRefreshCredentials()
        }
    }

    @Test
    fun `constructor with cached cognito credentials throws exception on missing data`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "cognito"
        every { anyConstructed<EncryptedSharedPreferences>().get(IDENTITY_POOL_ID) } returns null // Simulate missing data
        assertFailsWith<Exception> { LocationCredentialsProvider(context) }
    }

    @Test
    fun `constructor with cached API key credentials throws exception on missing data`() {
        every { anyConstructed<EncryptedSharedPreferences>().get(METHOD) } returns "apiKey"
        every { anyConstructed<EncryptedSharedPreferences>().get(API_KEY) } returns null // Simulate missing data
        assertFailsWith<Exception> { LocationCredentialsProvider(context) }
    }

    @Test
    fun `verify SecurePreferences interactions for cognito initialization`() {
        LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        verify(exactly = 1) { anyConstructed<EncryptedSharedPreferences>().put(METHOD, "cognito") }
        verify(exactly = 1) { anyConstructed<EncryptedSharedPreferences>().put(IDENTITY_POOL_ID,
            TEST_IDENTITY_POOL_ID
        ) }
        verify(exactly = 1) { anyConstructed<EncryptedSharedPreferences>().put(REGION, AwsRegions.US_EAST_1.regionName) }
    }

    @Test
    fun `getCredentialsProvider throws if Cognito provider not initialized`() {
        val provider = LocationCredentialsProvider(context, "apiKey")
        assertFailsWith<Exception> { provider.getCredentialsProvider() }
    }

    @Test
    fun `getApiKeyProvider throws if API key provider not initialized`() {
        val provider = LocationCredentialsProvider(context, TEST_IDENTITY_POOL_ID, AwsRegions.US_EAST_1)
        assertFailsWith<Exception> { provider.getApiKeyProvider() }
    }

    @Test
    fun `refresh throws if Cognito provider not initialized`() {
        val provider = LocationCredentialsProvider(context, "apiKey")
        coroutineScope.launch {
            provider.verifyAndRefreshCredentials()
            assertFailsWith<Exception> { provider.refresh() }
        }
    }

    @Test
    fun `clear throws if Cognito provider not initialized`() {
        val provider = LocationCredentialsProvider(context, "apiKey")
        assertFailsWith<Exception> { provider.clear() }
    }
}
