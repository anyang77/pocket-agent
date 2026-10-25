package dev.pocketagent.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GitHubDeviceAuthClientTest {
    private val client = GitHubDeviceAuthClient()

    @Test
    fun parsesDeviceGrant() {
        val grant = client.parseDeviceGrant(
            JSONObject(
                """
                {
                  "device_code":"device-value",
                  "user_code":"ABCD-1234",
                  "verification_uri":"https://github.com/login/device",
                  "expires_in":900,
                  "interval":5
                }
                """.trimIndent(),
            ),
        )

        assertEquals("device-value", grant.deviceCode)
        assertEquals("ABCD-1234", grant.userCode)
        assertEquals(5, grant.intervalSeconds)
    }

    @Test
    fun mapsPendingAndSlowDown() {
        assertSame(
            GitHubDevicePollResult.Pending,
            client.parseDevicePoll(JSONObject("""{"error":"authorization_pending"}""")),
        )
        assertSame(
            GitHubDevicePollResult.SlowDown,
            client.parseDevicePoll(JSONObject("""{"error":"slow_down"}""")),
        )
    }

    @Test
    fun parsesAccessToken() {
        val result = client.parseDevicePoll(
            JSONObject("""{"access_token":"gho_example","token_type":"bearer"}"""),
        )

        assertEquals(
            GitHubDevicePollResult.Authorized("gho_example"),
            result,
        )
    }
}
