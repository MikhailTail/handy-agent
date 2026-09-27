package dev.mikhailtail.handyagent.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 全模块共用的 JSON 取值助手。
 *
 * **必须共用**：同包下多个文件各自定义同名的 `private` 顶层扩展，Kotlin 在解析调用点时会
 * 看到多个候选而报 `None of the following functions can be called`，且错误信息只列函数签名、
 * 不指出真正的冲突源，很难看出是重复定义导致的。集中放这里就没有这个问题。
 */

/** 取字符串值；不是字符串（数字/布尔/对象）时返回 null，不抛。 */
fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

/** 取对象值，类型不符时返回 null。 */
fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

/** 取数组值，类型不符时返回 null。 */
fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray
