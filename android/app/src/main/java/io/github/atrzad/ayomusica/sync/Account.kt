package io.github.atrzad.ayomusica.sync

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Who is signed in, on which server, and this device's id. Kept in the app's private preferences. */
data class AccountState(
    val server: String = DEFAULT_SERVER,
    val token: String = "",
    val email: String = "",
    val name: String = "",
    val picture: String = "",
) {
    val signedIn: Boolean get() = token.isNotBlank()

    companion object {
        const val DEFAULT_SERVER = "https://ayo-musica.tail9ff58.ts.net"
    }
}

object Account {
    private var prefs: android.content.SharedPreferences? = null
    private val state = MutableStateFlow(AccountState())
    val current: StateFlow<AccountState> = state
    var deviceId: String = ""
        private set
    val deviceName: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)
        prefs = store
        deviceId = store.getString("device", null) ?: UUID.randomUUID().toString().also { store.edit().putString("device", it).apply() }
        state.value = AccountState(
            server = store.getString("server", null) ?: AccountState.DEFAULT_SERVER,
            token = store.getString("token", "")!!, email = store.getString("email", "")!!,
            name = store.getString("name", "")!!, picture = store.getString("picture", "")!!,
        )
    }

    val server: String get() = state.value.server.trimEnd('/')
    val token: String get() = state.value.token

    fun setServer(url: String) = save(state.value.copy(server = url.trim().trimEnd('/').ifBlank { AccountState.DEFAULT_SERVER }))

    fun signIn(token: String, email: String, name: String, picture: String) =
        save(state.value.copy(token = token, email = email, name = name, picture = picture))

    fun signOut() = save(state.value.copy(token = "", email = "", name = "", picture = ""))

    /** Is this address one of the server's (so it gets the session header)? */
    fun owns(url: String): Boolean = state.value.signedIn && url.startsWith(server)

    private fun save(value: AccountState) {
        state.value = value
        prefs?.edit()?.putString("server", value.server)?.putString("token", value.token)?.putString("email", value.email)
            ?.putString("name", value.name)?.putString("picture", value.picture)?.apply()
    }
}
