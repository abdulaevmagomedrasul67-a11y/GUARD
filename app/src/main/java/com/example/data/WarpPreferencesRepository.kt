package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.warp.WarpCrypto
import com.example.warp.WarpProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ali_guard_prefs")

data class StoredAppState(
    val warpAiEnabled: Boolean,
    val warpOtherEnabled: Boolean,
    val isDarkTheme: Boolean,
    val autoUnlockAiIp: Boolean,
    val profile: WarpProfile?
)

class WarpPreferencesRepository(private val context: Context) {

    private object Keys {
        val WARP_AI_ENABLED = booleanPreferencesKey("warp_ai_enabled")
        val WARP_OTHER_ENABLED = booleanPreferencesKey("warp_other_enabled")
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val AUTO_UNLOCK_AI_IP = booleanPreferencesKey("auto_unlock_ai_ip")

        val DEVICE_ID = stringPreferencesKey("warp_device_id")
        val ACCESS_TOKEN = stringPreferencesKey("warp_access_token")
        val PRIVATE_KEY = stringPreferencesKey("warp_private_key")
        val PUBLIC_KEY = stringPreferencesKey("warp_public_key")
        val PEER_PUBLIC_KEY = stringPreferencesKey("warp_peer_public_key")
        val CLIENT_ID_B64 = stringPreferencesKey("warp_client_id_b64")
        val INTERFACE_V4 = stringPreferencesKey("warp_interface_v4")
        val ENDPOINT_HOST_V4 = stringPreferencesKey("warp_endpoint_host_v4")
        val ENDPOINT_PORT = intPreferencesKey("warp_endpoint_port")
        val CREATED_AT = stringPreferencesKey("warp_created_at")
    }

    val stateFlow: Flow<StoredAppState> = context.dataStore.data.map { prefs ->
        val privKey = prefs[Keys.PRIVATE_KEY]
        val pubKey = prefs[Keys.PUBLIC_KEY]
        val peerPubKey = prefs[Keys.PEER_PUBLIC_KEY]
        val profile = if (!privKey.isNullOrBlank() && !pubKey.isNullOrBlank() && !peerPubKey.isNullOrBlank()) {
            val clientId = prefs[Keys.CLIENT_ID_B64].orEmpty()
            WarpProfile(
                deviceId = prefs[Keys.DEVICE_ID].orEmpty(),
                accessToken = prefs[Keys.ACCESS_TOKEN].orEmpty(),
                privateKey = privKey,
                publicKey = pubKey,
                peerPublicKey = peerPubKey,
                clientIdBase64 = clientId,
                reservedBytes = WarpCrypto.decodeReservedBytes(clientId),
                interfaceAddressV4 = prefs[Keys.INTERFACE_V4] ?: "172.16.0.2",
                endpointHostV4 = prefs[Keys.ENDPOINT_HOST_V4] ?: "162.159.192.1",
                endpointPort = prefs[Keys.ENDPOINT_PORT] ?: 2408,
                createdAtIso = prefs[Keys.CREATED_AT] ?: ""
            )
        } else {
            null
        }

        StoredAppState(
            warpAiEnabled = prefs[Keys.WARP_AI_ENABLED] ?: false,
            warpOtherEnabled = prefs[Keys.WARP_OTHER_ENABLED] ?: false,
            isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: true,
            autoUnlockAiIp = prefs[Keys.AUTO_UNLOCK_AI_IP] ?: true,
            profile = profile
        )
    }

    suspend fun saveProfile(profile: WarpProfile) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEVICE_ID] = profile.deviceId
            prefs[Keys.ACCESS_TOKEN] = profile.accessToken
            prefs[Keys.PRIVATE_KEY] = profile.privateKey
            prefs[Keys.PUBLIC_KEY] = profile.publicKey
            prefs[Keys.PEER_PUBLIC_KEY] = profile.peerPublicKey
            prefs[Keys.CLIENT_ID_B64] = profile.clientIdBase64
            prefs[Keys.INTERFACE_V4] = profile.interfaceAddressV4
            prefs[Keys.ENDPOINT_HOST_V4] = profile.endpointHostV4
            prefs[Keys.ENDPOINT_PORT] = profile.endpointPort
            prefs[Keys.CREATED_AT] = profile.createdAtIso
        }
    }

    suspend fun setModes(warpAi: Boolean, warpOther: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WARP_AI_ENABLED] = warpAi
            prefs[Keys.WARP_OTHER_ENABLED] = warpOther
        }
    }

    suspend fun setDarkTheme(isDark: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_DARK_THEME] = isDark
        }
    }

    suspend fun setAutoUnlockAiIp(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_UNLOCK_AI_IP] = enabled
        }
    }
}
