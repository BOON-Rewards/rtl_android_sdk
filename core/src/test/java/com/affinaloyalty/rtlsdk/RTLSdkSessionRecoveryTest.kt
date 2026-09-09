package com.affinaloyalty.rtlsdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RTLSdkSessionRecoveryTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val sdk = RTLSdk.getInstance()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        sdk.logout()
        sdkField("listener").set(sdk, null)
        sdkField("webviewIsReady").setBoolean(sdk, false)
        sdkField("isExperienceLoading").setBoolean(sdk, false)
        Dispatchers.resetMain()
    }

    @Test
    fun `session recovery coalesces provider requests and stops when token is unavailable`() =
        runTest(mainDispatcher) {
            val listener = RecordingListener(token = null)
            sdkField("listener").set(sdk, listener)
            sdkField("webviewIsReady").setBoolean(sdk, true)

            sdk.handleSessionExpired()
            sdk.handleSessionExpired()
            runCurrent()

            assertEquals(1, listener.tokenRequestCount)
            assertEquals(listOf(true, false), listener.loadingStates)
            assertEquals(0, listener.readyCount)
            assertNull(sdkField("activeAttempt").get(sdk))
        }

    @Test
    fun `session recovery contains token provider failures and remains retryable`() =
        runTest(mainDispatcher) {
            val listener = RecordingListener(
                token = null,
                failure = IllegalStateException("Token service unavailable")
            )
            sdkField("listener").set(sdk, listener)
            sdkField("webviewIsReady").setBoolean(sdk, true)

            sdk.handleSessionExpired()
            runCurrent()

            assertEquals(1, listener.tokenRequestCount)
            assertEquals(listOf(true, false), listener.loadingStates)
            assertEquals(0, listener.readyCount)
            assertNull(sdkField("activeAttempt").get(sdk))
        }

    @Test
    fun `ready and authentication failure finish loading through separate outcomes`() {
        val listener = RecordingListener(token = "unused")
        sdkField("listener").set(sdk, listener)

        sdkField("isExperienceLoading").setBoolean(sdk, true)
        sdk.handleAppReady()
        assertEquals(listOf(false), listener.loadingStates)
        assertEquals(1, listener.readyCount)

        listener.loadingStates.clear()
        sdkField("isExperienceLoading").setBoolean(sdk, true)
        sdk.handleAuthFailure()
        assertEquals(listOf(false), listener.loadingStates)
        assertEquals(1, listener.readyCount)
    }

    @Test
    fun `new presentation wins even if old provider ignores cancellation`() = runTest(mainDispatcher) {
        val listener = DelayedListener()
        sdkField("listener").set(sdk, listener)
        val first = async { sdk.presentExperience() }
        runCurrent()
        val second = async { sdk.presentExperience() }
        runCurrent()
        assertEquals("request_cancelled", first.await().errorCode)

        listener.tokens[0].complete("stale-token")
        runCurrent()
        org.junit.Assert.assertFalse(second.isCompleted)
        listener.tokens[1].complete(null)
        runCurrent()
        assertEquals("token_unavailable", second.await().errorCode)
        assertNull(sdkField("activeAttempt").get(sdk))
    }

    @Test
    fun `logout completes token acquisition and ignores its late result`() = runTest(mainDispatcher) {
        val listener = DelayedListener()
        sdkField("listener").set(sdk, listener)
        val presentation = async { sdk.presentExperience() }
        runCurrent()
        sdk.logout()
        runCurrent()
        assertEquals("request_cancelled", presentation.await().errorCode)
        val loadingAtLogout = listener.loadingStates.toList()
        listener.tokens.single().complete("late-token")
        runCurrent()
        assertEquals(loadingAtLogout, listener.loadingStates)
        assertNull(sdkField("activeAttempt").get(sdk))
    }

    @Test
    fun `caller cancellation cannot clear the replacement attempt`() = runTest(mainDispatcher) {
        val listener = DelayedListener()
        sdkField("listener").set(sdk, listener)
        val first = async { sdk.presentExperience() }
        runCurrent()
        first.cancel()
        val second = async { sdk.presentExperience() }
        runCurrent()
        listener.tokens[0].complete("stale-token")
        runCurrent()
        org.junit.Assert.assertFalse(second.isCompleted)
        listener.tokens[1].complete(null)
        runCurrent()
        assertEquals("token_unavailable", second.await().errorCode)
    }

    private class DelayedListener : RTLSdkListener {
        val tokens = mutableListOf<CompletableDeferred<String?>>()
        val loadingStates = mutableListOf<Boolean>()
        override suspend fun provideAuthToken(): String? {
            val token = CompletableDeferred<String?>()
            tokens.add(token)
            return withContext(NonCancellable) { token.await() }
        }
        override fun onLoadingStateChanged(isLoading: Boolean) { loadingStates.add(isLoading) }
    }

    private fun sdkField(name: String) = RTLSdk::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }

    private class RecordingListener(
        private val token: String?,
        private val failure: Exception? = null
    ) : RTLSdkListener {
        var tokenRequestCount = 0
        val loadingStates = mutableListOf<Boolean>()
        var readyCount = 0
        override suspend fun provideAuthToken(): String? {
            tokenRequestCount += 1
            failure?.let { throw it }
            return token
        }

        override fun onLoadingStateChanged(isLoading: Boolean) {
            loadingStates += isLoading
        }

        override fun onReady() {
            readyCount += 1
        }
    }
}
