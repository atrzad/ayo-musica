package io.github.atrzad.ayomusica.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.atrzad.ayomusica.BuildConfig
import io.github.atrzad.ayomusica.sync.AccountState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Conta e nuvem: sign in with Google, see the sync, send this phone's songs to the cloud, sign out. */
@Composable
fun AccountPage(
    account: AccountState,
    status: SyncStatus,
    localNotInCloud: Int,
    viewModel: MusicViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var server by remember(account.server) { mutableStateOf(account.server) }
    var showServer by remember { mutableStateOf(false) }
    var testToken by remember { mutableStateOf("") }

    fun google() {
        busy = true
        error = null
        scope.launch {
            error = signInWithGoogle(context, viewModel)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!account.signedIn) {
            Text("Entre com sua conta Google para ter a mesma biblioteca no celular e no computador, como no Spotify: " +
                "playlists, curtidas, letras e correções sincronizadas, suas músicas na nuvem para tocar de qualquer lugar " +
                "(e baixar para ouvir sem internet), continuar de onde parou em outro aparelho e controlar um pelo outro.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = ::google, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(if (busy) "Entrando…" else "Entrar com o Google")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { showServer = !showServer }) { Text(if (showServer) "Esconder servidor" else "Servidor…") }
            if (showServer) {
                OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Endereço do servidor") },
                    singleLine = true)
                OutlinedButton(onClick = { viewModel.setServer(server) }) { Text("Usar este servidor") }
                if (BuildConfig.DEBUG) {
                    OutlinedTextField(testToken, { testToken = it }, Modifier.fillMaxWidth(), label = { Text("Sessão de teste") },
                        singleLine = true)
                    OutlinedButton(onClick = {
                        scope.launch { error = viewModel.signInWithSession(testToken) }
                    }, enabled = testToken.isNotBlank()) { Text("Entrar com a sessão de teste") }
                }
            }
            return@Column
        }
        Text(account.name.ifBlank { account.email }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (account.name.isNotBlank()) Text(account.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Servidor: ${account.server}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        val last = if (status.lastAt > 0) "Última sincronização: " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.lastAt)) else "Ainda não sincronizou."
        Text(listOf(last, status.message).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.syncNow() }, Modifier.fillMaxWidth(), enabled = !status.running) { Text("Sincronizar agora") }
        Text(if (localNotInCloud == 0) "Todas as músicas deste celular já estão na nuvem."
            else "$localNotInCloud músicas deste celular ainda não estão na nuvem.", style = MaterialTheme.typography.bodyMedium)
        if (localNotInCloud > 0) OutlinedButton(onClick = { viewModel.uploadMissing() }, Modifier.fillMaxWidth()) {
            Text("Enviar todas para a nuvem")
        }
        Text("Na biblioteca, os filtros Tudo, Neste celular e Nuvem separam as músicas; no menu ⋮ de uma música da nuvem dá " +
            "para baixá-la e ouvir sem internet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        OutlinedButton(onClick = viewModel::signOut, Modifier.fillMaxWidth()) { Text("Sair da conta") }
    }
}

/** Google's sign-in sheet (Credential Manager); the token goes to our server. Returns an error message or null. */
private suspend fun signInWithGoogle(context: Context, viewModel: MusicViewModel): String? {
    val clientId = viewModel.googleClientId()
    if (clientId.isBlank()) return "O servidor ainda não tem o login do Google configurado (GOOGLE_WEB_CLIENT_ID)."
    return try {
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val result = CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            viewModel.signIn(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else "O Google não devolveu uma conta."
    } catch (_: GetCredentialCancellationException) {
        null
    } catch (error: Exception) {
        io.github.atrzad.ayomusica.util.AppLog.w("Conta", "login do Google", error)
        "Não deu para entrar com o Google: ${error.message ?: error.javaClass.simpleName}"
    }
}
