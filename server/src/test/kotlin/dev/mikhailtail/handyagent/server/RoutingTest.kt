package dev.mikhailtail.handyagent.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 0 的契约测试。
 *
 * 这些断言的价值不在于"代码能跑"，而在于**把 cc-haha 前端的期望固化成可回归的契约**：
 * 字段名一旦对不上，前端不会报编译错，只会在运行时静默降级或白屏 —— 那种问题在
 * 设备上极难定位。所以字段名在这里逐字钉死。
 */
class RoutingTest {

    private fun tempStaticRoot(): File {
        val dir = Files.createTempDirectory("h5").toFile()
        File(dir, "index.html").writeText("<!doctype html><title>handy</title>")
        File(dir, "assets").mkdirs()
        File(dir, "assets/app.js").writeText("console.log('hi')")
        return dir
    }

    @Test
    fun `health returns ok`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/health")

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("\"status\":\"ok\""), res.bodyAsText())
    }

    /** 字段名对齐 `src/server/api/status.ts` 的 handleHealthCheck()。 */
    @Test
    fun `api status carries status version uptime`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/api/status")

        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"status\":\"ok\""), body)
        assertTrue(body.contains("\"version\""), body)
        assertTrue(body.contains("\"uptime\""), body)
    }

    /** 字段名对齐 handleUser()：{ configDir, projects }。 */
    @Test
    fun `api status user carries configDir and projects`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/api/status/user")

        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"configDir\""), body)
        assertTrue(body.contains("\"projects\""), body)
    }

    @Test
    fun `api status usage carries token counters`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/api/status/usage")

        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("totalInputTokens"), body)
        assertTrue(body.contains("totalOutputTokens"), body)
        assertTrue(body.contains("totalCost"), body)
    }

    /**
     * `/api/sessions` 顶层是**对象**不是数组（会话在 `sessions` 键下），
     * 且带 `total` 与 `index` —— 这条是拿活的 cc-haha 服务实测出来的，不是照类型定义猜的。
     */
    @Test
    fun `api sessions returns an object with sessions total and index`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/api/sessions")

        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.trimStart().startsWith("{"), "顶层应是对象，实际：${body.take(120)}")
        assertTrue(body.contains("\"sessions\""), body.take(200))
        assertTrue(body.contains("\"total\""), body.take(200))
        assertTrue(body.contains("\"index\""), body.take(200))
    }

    @Test
    fun `root serves the frontend index`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/")

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("handy"), res.bodyAsText())
    }

    @Test
    fun `static assets are served from disk`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/assets/app.js")

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("console.log"), res.bodyAsText())
    }

    /**
     * SPA 兜底：前端用 history 路由，`/settings` 这类深链接在服务端没有对应文件。
     * 必须回落到 index.html 交给前端解析，否则刷新页面就是 404。
     */
    @Test
    fun `unknown path falls back to index for client side routing`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/settings")

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("handy"), res.bodyAsText())
    }

    /**
     * `/api` 下未实现的路径必须返回 JSON 空对象，**绝不能掉进 SPA 兜底拿到 HTML**。
     *
     * 这条是踩出来的：前端把所有 `/api` 响应一律按 JSON 解析，拿到 HTML 就报
     * `The server response could not be parsed as JSON` 并整屏进错误页 —— 而那个
     * 提示指不出是哪个端点。真机表现与"服务没起来"几乎一样，定位成本极高。
     */
    @Test
    fun `unimplemented api path degrades to empty json object`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.get("/api/models")

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("{}", res.bodyAsText().trim())
    }

    /** 同一道兜底要覆盖写方法，否则前端 POST 一个未实现端点又会拿到 HTML。 */
    @Test
    fun `unimplemented api post also degrades to json`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        val res = client.post("/api/diagnostics/events") {
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("{}", res.bodyAsText().trim())
    }

    /** 已实现的具体路由不能被兜底抢走。 */
    @Test
    fun `concrete api routes still win over the fallback`() = testApplication {
        application { handyModule(tempStaticRoot(), java.io.File("D:/cc-haha/projects")) }

        assertEquals(HttpStatusCode.OK, client.get("/api/status").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }
}
