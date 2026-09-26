package dev.pocket.agent.core.provider.jvm

import dev.pocket.agent.core.provider.HttpEngine
import dev.pocket.agent.core.provider.HttpException
import dev.pocket.agent.core.provider.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 纯 JVM 的 HttpEngine 实现（`java.net` 在 Android 上同样可用）。
 *
 * 关键点：
 * - 关闭「透明 gzip」猜测，显式 `Accept-Encoding: identity`，否则读流会抛 zlib 错。
 * - 协程取消时立刻 `disconnect()`，让阻塞中的 `readLine()` 抛出而不是干等超时。
 */
class UrlConnectionHttpEngine(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpEngine {

    override fun streamLines(req: HttpRequest): Flow<String> = flow {
        val conn = open(req)
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { runCatching { conn.disconnect() } }
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                throw HttpException(status, conn.readErrorBody(), req.url)
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    emit(line)
                }
            }
        } catch (e: IOException) {
            if (job?.isCancelled == true) throw CancellationException("HTTP stream cancelled")
            throw e
        } finally {
            handle?.dispose()
            runCatching { conn.disconnect() }
        }
    }.flowOn(dispatcher)

    override suspend fun execute(req: HttpRequest): String = withContext(dispatcher) {
        val conn = open(req)
        try {
            val status = conn.responseCode
            val body = if (status in 200..299) conn.readBody() else conn.readErrorBody()
            if (status !in 200..299) throw HttpException(status, body, req.url)
            body
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(req: HttpRequest): HttpURLConnection {
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = req.method
            connectTimeout = req.connectTimeoutMs
            readTimeout = req.readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in req.headers) setRequestProperty(k, v)
            if (req.body != null) doOutput = true
        }
        val body = req.body
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }

    private fun HttpURLConnection.readBody(): String =
        inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun HttpURLConnection.readErrorBody(): String =
        runCatching { errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }
            .getOrNull()
            .orEmpty()
}
