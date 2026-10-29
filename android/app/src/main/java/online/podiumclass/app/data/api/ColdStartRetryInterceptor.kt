package online.podiumclass.app.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Survives a cold-started API.
 *
 * The deployed backend runs on a plan that scales to zero: after a few idle
 * minutes the instance is gone, and the next request has to wait for a fresh
 * process (boot, migrations, gunicorn) before any byte comes back. That takes
 * tens of seconds, while the client's own timeouts are 15 -- so the first
 * request after a quiet period always failed with "failed to connect", even
 * though the server was fine and the retry a second later succeeded.
 *
 * This retries once, with a much longer budget, but only for failures that
 * prove the request never reached the server:
 *
 *   * [ConnectException] -- the TCP handshake itself was refused or dropped;
 *   * a connect-phase [SocketTimeoutException];
 *   * [UnknownHostException] -- transient DNS while the edge wakes.
 *
 * A read timeout, a 5xx or a cancelled call is NOT retried: in those cases the
 * request may already have been processed, and silently sending it twice would
 * duplicate a created class or a chat message.
 */
class ColdStartRetryInterceptor(
    private val retryDelayMillis: Long = 2_000,
    private val coldConnectTimeoutSeconds: Int = 45,
    private val coldReadTimeoutSeconds: Int = 60,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (failure: IOException) {
            if (!isColdStartFailure(failure)) throw failure

            // Give the instance a moment to finish booting before asking again.
            Thread.sleep(retryDelayMillis)

            return chain
                .withConnectTimeout(coldConnectTimeoutSeconds, TimeUnit.SECONDS)
                .withReadTimeout(coldReadTimeoutSeconds, TimeUnit.SECONDS)
                .proceed(chain.request())
        }
    }

    private fun isColdStartFailure(failure: IOException): Boolean = when (failure) {
        is ConnectException -> true
        is UnknownHostException -> true
        // SocketTimeoutException covers both connect and read phases; only the
        // former is safe to replay, and OkHttp names it "connect timed out".
        is SocketTimeoutException ->
            failure.message?.contains("connect", ignoreCase = true) == true
        else -> false
    }
}
