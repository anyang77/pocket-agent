package dev.pocketagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubDeviceGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

data class GitHubOAuthUser(
    val login: String,
    val name: String,
)

data class GitHubRepository(
    val fullName: String,
    val name: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)

sealed interface GitHubDevicePollResult {
    data class Authorized(val accessToken: String) : GitHubDevicePollResult
    data object Pending : GitHubDevicePollResult
    data object SlowDown : GitHubDevicePollResult
    data object Expired : GitHubDevicePollResult
    data object Denied : GitHubDevicePollResult
}

class GitHubDeviceAuthClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    @Volatile
    private var activeCall: okhttp3.Call? = null

    suspend fun requestDeviceGrant(clientId: String): GitHubDeviceGrant =
        withContext(Dispatchers.IO) {
            require(clientId.isNotBlank()) { "GitHub OAuth Client ID 未配置" }
            val request = Request.Builder()
                .url("https://github.com/login/device/code")
                .header("Accept", "application/json")
                .post(
                    FormBody.Builder()
                        .add("client_id", clientId)
                        .add("scope", "repo read:org workflow")
                        .build(),
                )
                .build()
            executeJson(request).let { json ->
                parseDeviceGrant(json)
            }
        }

    suspend fun poll(clientId: String, deviceCode: String): GitHubDevicePollResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(
                    FormBody.Builder()
                        .add("client_id", clientId)
                        .add("device_code", deviceCode)
                        .add(
                            "grant_type",
                            "urn:ietf:params:oauth:grant-type:device_code",
                        )
                        .build(),
                )
                .build()
            parseDevicePoll(executeJson(request))
        }

    suspend fun currentUser(accessToken: String): GitHubOAuthUser =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            executeJson(request).let { json ->
                val login = json.optString("login")
                if (login.isBlank()) throw IOException("无法读取 GitHub 用户名")
                GitHubOAuthUser(
                    login = login,
                    name = json.optString("name").ifBlank { login },
                )
            }
        }

    suspend fun repositories(accessToken: String): List<GitHubRepository> =
        withContext(Dispatchers.IO) {
            val repositories = linkedMapOf<String, GitHubRepository>()
            for (page in 1..3) {
                val request = Request.Builder()
                    .url(
                        "https://api.github.com/user/repos" +
                            "?per_page=100&page=$page&sort=updated" +
                            "&affiliation=owner,collaborator,organization_member",
                    )
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                val array = executeArray(request)
                for (index in 0 until array.length()) {
                    val repo = array.optJSONObject(index) ?: continue
                    val fullName = repo.optString("full_name")
                    val cloneUrl = repo.optString("clone_url")
                    if (fullName.isBlank() || cloneUrl.isBlank()) continue
                    repositories[fullName] = GitHubRepository(
                        fullName = fullName,
                        name = repo.optString("name", fullName.substringAfterLast('/')),
                        cloneUrl = cloneUrl,
                        private = repo.optBoolean("private"),
                        defaultBranch = repo.optString("default_branch", "main"),
                        description = repo.optString("description")
                            .takeUnless { it.equals("null", ignoreCase = true) }
                            .orEmpty(),
                        updatedAt = repo.optString("updated_at"),
                    )
                }
                if (array.length() < 100) break
            }
            repositories.values.sortedBy { it.fullName.lowercase() }
        }

    private fun executeJson(request: Request): JSONObject {
        val call = httpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }
                    .getOrElse { throw IOException("GitHub 返回了无效响应") }
                if (!response.isSuccessful && json.optString("error").isBlank()) {
                    throw IOException(
                        json.optString("message").ifBlank {
                            "GitHub 请求失败 HTTP ${response.code}"
                        },
                    )
                }
                return json
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun executeArray(request: Request): JSONArray {
        val call = httpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(body).optString("message")
                    }.getOrNull().orEmpty()
                    throw IOException(
                        message.ifBlank { "GitHub 仓库列表请求失败 HTTP ${response.code}" },
                    )
                }
                return JSONArray(body)
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    fun cancel() {
        activeCall?.cancel()
    }

    internal fun parseDeviceGrant(json: JSONObject): GitHubDeviceGrant {
        val error = json.optString("error")
        if (error.isNotBlank()) {
            throw IOException(
                json.optString("error_description").ifBlank {
                    "GitHub 设备授权失败：$error"
                },
            )
        }
        return GitHubDeviceGrant(
            deviceCode = json.optString("device_code")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("GitHub 未返回 device_code"),
            userCode = json.optString("user_code")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("GitHub 未返回 user_code"),
            verificationUri = json.optString("verification_uri")
                .ifBlank { "https://github.com/login/device" },
            expiresInSeconds = json.optInt("expires_in", 900).coerceAtLeast(60),
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(5),
        )
    }

    internal fun parseDevicePoll(json: JSONObject): GitHubDevicePollResult {
        val token = json.optString("access_token")
        if (token.isNotBlank()) return GitHubDevicePollResult.Authorized(token)
        return when (json.optString("error")) {
            "authorization_pending" -> GitHubDevicePollResult.Pending
            "slow_down" -> GitHubDevicePollResult.SlowDown
            "expired_token" -> GitHubDevicePollResult.Expired
            "access_denied" -> GitHubDevicePollResult.Denied
            else -> throw IOException(
                json.optString("error_description").ifBlank {
                    "GitHub 授权失败：${json.optString("error", "unknown_error")}"
                },
            )
        }
    }
}
