package com.localvoicetv

import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Resolves variables before dispatch, preserving JSON structure and extra value types. */
internal class CommandParameters(private val variables: Map<String, String>) {
    companion object {
        // Escape both braces: Android's regex engine rejects a bare closing brace.
        private val PLACEHOLDER = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([A-Za-z]+))?\\}|\\$(param[1-9][0-9]*)(?![A-Za-z0-9_])")
    }

    // One pass: replacement text is never interpreted as another template.
    fun text(template: String): String = PLACEHOLDER.replace(template) { match ->
        val name = match.groupValues[1].ifEmpty { match.groupValues[3] }
        val value = requireNotNull(variables[name]) { "缺少跳转参数：$name" }
        when (val encoding = match.groupValues[2]) {
            "", "raw" -> value
            "uri" -> Uri.encode(value)
            else -> throw IllegalArgumentException("不支持的参数编码：$encoding")
        }
    }

    fun extra(value: Any): Any = when (value) {
        is String -> legacyString(value)
        is Int, is Long, is Boolean -> value
        is Float -> value.also { require(it.isFinite()) { "浮点参数必须是有限数值" } }
        is Double -> value.also { require(it.isFinite()) { "浮点参数必须是有限数值" } }
        is JSONObject -> typedExtra(value)
        else -> throw IllegalArgumentException("不支持的 extra 值，请使用带 type 和 value 的参数声明")
    }

    /** Existing extras such as value="{\"keyword\":\"$param1\"}" remain compatible. */
    private fun legacyString(template: String): String {
        val trimmed = template.trim()
        val parsed = try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> null
            }
        } catch (_: JSONException) {
            null // A non-JSON string can legitimately start with a brace.
        }
        return if (parsed != null) json(parsed).toString() else text(template)
    }

    private fun json(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { output ->
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                output.put(key, json(value.get(key)))
            }
        }
        is JSONArray -> JSONArray().also { output ->
            for (i in 0 until value.length()) output.put(json(value.get(i)))
        }
        is String -> text(value)
        else -> value
    }

    private fun scalar(value: Any): String = when (value) {
        is String -> text(value)
        is Number, is Boolean -> value.toString()
        else -> throw IllegalArgumentException("value 必须是字符串、数字或布尔值")
    }

    private fun int(value: Any): Int = requireNotNull(scalar(value).trim().toIntOrNull()) { "不是有效的 Int 整数" }
    private fun long(value: Any): Long = requireNotNull(scalar(value).trim().toLongOrNull()) { "不是有效的 Long 整数" }
    private fun boolean(value: Any): Boolean = when (scalar(value).trim()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("布尔参数只能是 true 或 false")
    }

    private fun typedExtra(descriptor: JSONObject): Any {
        val type = descriptor.optString("type")
        require(descriptor.has("value")) { "类型参数必须提供 value" }
        val value = descriptor.get("value")
        fun array(): JSONArray = requireNotNull(value as? JSONArray) { "$type 的 value 必须是数组" }
        return when (type) {
            "string" -> scalar(value)
            "int" -> int(value)
            "long" -> long(value)
            "float" -> requireNotNull(scalar(value).trim().toFloatOrNull()?.takeIf { it.isFinite() }) {
                "不是有效的 Float 数值"
            }
            "double" -> requireNotNull(scalar(value).trim().toDoubleOrNull()?.takeIf { it.isFinite() }) {
                "不是有效的 Double 数值"
            }
            "boolean" -> boolean(value)
            "stringArray" -> array().let { a -> Array(a.length()) { scalar(a.get(it)) } }
            "intArray" -> array().let { a -> IntArray(a.length()) { int(a.get(it)) } }
            "longArray" -> array().let { a -> LongArray(a.length()) { long(a.get(it)) } }
            "booleanArray" -> array().let { a -> BooleanArray(a.length()) { boolean(a.get(it)) } }
            "json" -> {
                require(value is JSONObject || value is JSONArray) { "json 的 value 必须是 JSON 对象或数组" }
                json(value).toString()
            }
            else -> throw IllegalArgumentException("不支持的 extra 类型：$type")
        }
    }
}
