package com.credman.cmwallet.data.repository

import android.os.Build
import android.util.Log
import com.credman.cmwallet.data.model.CredentialItem
import com.credman.cmwallet.data.source.CredentialDatabaseDataSource
import com.credman.cmwallet.data.source.TestCredentialsDataSource
import com.credman.cmwallet.decodeBase64
import com.credman.cmwallet.decodeBase64UrlNoPadding
import com.credman.cmwallet.mdoc.MDoc
import com.credman.cmwallet.openid4vci.OpenId4VCI
import com.credman.cmwallet.openid4vci.data.CredentialConfigurationMDoc
import com.credman.cmwallet.openid4vci.data.CredentialConfigurationUnknownFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.ExperimentalEncodingApi

class CredentialRepository {
    //val json = Json { classDiscriminatorMode = ClassDiscriminatorMode.NONE }

    var privAppsJson = "{}"
        private set

    var openId4VCITestRequestJson = "{}"

    private val testCredentialsDataSource = TestCredentialsDataSource()
    private val credentialDatabaseDataSource = CredentialDatabaseDataSource()

    private fun combinedCredentials(): Flow<List<CredentialItem>> = flow {
        emitAll(
            combine(
                testCredentialsDataSource.credentials,
                credentialDatabaseDataSource.credentials
            ) { list1, list2 ->
                list1 + list2
            })
    }

    val credentials: Flow<List<CredentialItem>> = combinedCredentials()

    val credentialRegistryDatabase: Flow<ByteArray> = flow {
        emitAll(combinedCredentials().map { credentials ->
            Log.i("CredentialRepository", "Updating flow with ${credentials.size}")
            createRegistryDatabase(credentials)
        })
    }

    fun addCredentialsFromJson(credentialJson: String) {
        testCredentialsDataSource.initWithJson(credentialJson)
    }

    fun getCredential(id: String): CredentialItem? {
        return testCredentialsDataSource.getCredential(id)
            ?: credentialDatabaseDataSource.getCredential(id)
    }

    fun deleteCredential(id: String) {
        credentialDatabaseDataSource.deleteCredential(id)
    }

    fun setPrivAppsJson(appsJson: String) {
        privAppsJson = appsJson
    }

    suspend fun issueCredential(requestJson: String) {
        val openId4VCI = OpenId4VCI(requestJson)

    }

    class RegistryIcon(
        val iconValue: ByteArray,
        var iconOffset: Int = 0
    )

    /**
     * Credential Registry has the following format:
     *
     * |---------------------------------------|
     * |--- (Int) offset of credential json ---|
     * |--------- (Byte Array) Icon 1 ---------|
     * |--------- (Byte Array) Icon 2 ---------|
     * |------------- More Icons... -----------|
     * |----------- Credential Json -----------|  // See assets/paymentcreds.json as an example
     * |---------------------------------------|
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun createRegistryDatabase(items: List<CredentialItem>): ByteArray {
        val out = ByteArrayOutputStream()

        val iconMap: Map<String, RegistryIcon> = items.associate {
            Pair(
                it.id,
                RegistryIcon(it.displayData.icon?.decodeBase64() ?: ByteArray(0))
            )
        }
        // Write the offset to the json
        val jsonOffset = 4 + iconMap.values.sumOf { it.iconValue.size }
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(jsonOffset)
        out.write(buffer.array())

        // Write the icons
        var currIconOffset = 4
        iconMap.values.forEach {
            it.iconOffset = currIconOffset
            out.write(it.iconValue)
            currIconOffset += it.iconValue.size
        }

        val mdocCredentials = JSONObject()
        items.forEach { item ->
            when (item.config) {
                is CredentialConfigurationMDoc -> {
                    val credJson = JSONObject()
                    credJson.put(ID, item.id)
                    credJson.put(TITLE, item.displayData.title)
                    credJson.putOpt(SUBTITLE, item.displayData.subtitle)
                    val iconJson = JSONObject().apply {
                        put(START, iconMap[item.id]!!.iconOffset)
                        put(LENGTH, iconMap[item.id]!!.iconValue.size)
                    }
                    credJson.put(ICON, iconJson)
                    val mdoc = MDoc(item.credentials.first().credential.decodeBase64UrlNoPadding())
                    if (mdoc.issuerSignedNamespaces.isNotEmpty()) {
                        val namespacesJson = JSONObject()
                        mdoc.issuerSignedNamespaces.forEach { (namespace, elements) ->
                            val namespaceJson = JSONObject()
                            elements.forEach { (element, value) ->
                                val namespaceDataJson = JSONObject()

                                namespaceDataJson.putOpt(VALUE, value)
                                val displayName = item.config.claims?.get(namespace)
                                    ?.get(element)?.display?.first()?.name!!
                                namespaceDataJson.put(DISPLAY, displayName)
//                                namespaceDataJson.putOpt(
//                                    DISPLAY_VALUE,
//                                    namespaceData.value.displayValue
//                                )
                                namespaceJson.put(element, namespaceDataJson)
                            }
                            namespacesJson.put(namespace, namespaceJson)
                        }
                        credJson.put(NAMESPACES, namespacesJson)
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        mdocCredentials.append(item.config.doctype, credJson)
                    } else {
                        when (val current = mdocCredentials.opt(item.config.doctype)) {
                            is JSONArray -> {
                                mdocCredentials.put(item.config.doctype, current.put(credJson))
                            }

                            null -> {
                                mdocCredentials.put(
                                    item.config.doctype,
                                    JSONArray().put(credJson)
                                )
                            }

                            else -> throw IllegalStateException(
                                "Unexpected namespaced data that's" +
                                        " not a JSONArray. Instead it is ${current::class.java}"
                            )
                        }
                    }
                }

                is CredentialConfigurationUnknownFormat -> TODO()
            }
        }
        val registryCredentials = JSONObject()
        registryCredentials.put("mso_mdoc", mdocCredentials)
        val registryJson = JSONObject()
        registryJson.put(CREDENTIALS, registryCredentials)
        Log.d(TAG, "Credential to be registered: ${registryJson.toString(2)}")
        out.write(registryJson.toString().toByteArray())
        return out.toByteArray()
    }

    companion object {
        const val TAG = "CredentialRepository"

        // Wasm database json keys
        const val CREDENTIALS = "credentials"
        const val ID = "id"
        const val TITLE = "title"
        const val SUBTITLE = "subtitle"
        const val ICON = "icon"
        const val START = "start"
        const val LENGTH = "length"
        const val NAMESPACES = "namespaces"
        const val VALUE = "value"
        const val DISPLAY = "display"
        const val DISPLAY_VALUE = "display_value"
    }
}