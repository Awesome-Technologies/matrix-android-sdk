/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.features.identityserver

import android.content.Context
import android.net.Uri
import org.matrix.androidsdk.HomeServerConnectionConfig
import org.matrix.androidsdk.MXSession
import org.matrix.androidsdk.core.callback.ApiCallback
import org.matrix.androidsdk.core.callback.SimpleApiCallback
import org.matrix.androidsdk.core.model.MatrixError
import org.matrix.androidsdk.features.terms.TermsNotSignedException
import org.matrix.androidsdk.listeners.MXEventListener
import org.matrix.androidsdk.rest.client.IdentityAuthRestClient
import org.matrix.androidsdk.rest.client.IdentityPingRestClient
import org.matrix.androidsdk.rest.client.ThirdPidRestClient
import org.matrix.androidsdk.rest.model.identityserver.HashDetailResponse
import org.matrix.androidsdk.rest.model.identityserver.IdentityServerRegisterResponse
import org.matrix.androidsdk.rest.model.openid.RequestOpenIdTokenResponse
import org.matrix.androidsdk.rest.model.sync.AccountDataElement
import java.security.InvalidParameterException
import javax.net.ssl.HttpsURLConnection

class IdentityServerManager(val mxSession: MXSession,
                            context: Context) {

    interface IdentityServerManagerListener {
        fun onIdentityServerChange()
    }

    private val listeners = HashSet<IdentityServerManagerListener>()

    fun addListener(listener: IdentityServerManagerListener) = synchronized(listeners) { listeners.add(listener) }
    fun removeListener(listener: IdentityServerManagerListener) = synchronized(listeners) { listeners.remove(listener) }


    init {
        localSetIdentityServerUrl(getIdentityServerUrl())

        // TODO Release the listener somewhere?
        mxSession.dataHandler.addListener(object : MXEventListener() {
            override fun onAccountDataUpdated(accountDataElement: AccountDataElement) {
                if (accountDataElement.type == AccountDataElement.ACCOUNT_DATA_TYPE_IDENTITY_SERVER) {
                    // The identity server has been updated
                    val accountDataIdentityServer =
                            mxSession.dataHandler.store.getAccountDataElement(AccountDataElement.ACCOUNT_DATA_TYPE_IDENTITY_SERVER)

                    accountDataIdentityServer?.content?.let {
                        localSetIdentityServerUrl(it[AccountDataElement.ACCOUNT_DATA_KEY_IDENTITY_SERVER_BASE_URL] as String?)
                    }
                }
            }

            override fun onStoreReady() {
                localSetIdentityServerUrl(getIdentityServerUrl())
            }
        })
    }

    private val identityServerTokensStore = IdentityServerTokensStore(context)

    private var identityPath: String? = getIdentityServerUrl()

    // Rest clients
    private var identityAuthRestClient: IdentityAuthRestClient? = null
    private var thirdPidRestClient: ThirdPidRestClient? = null

    /**
     * Return the identity server url, either from AccountData if it has been set, or from the local storage
     */
    fun getIdentityServerUrl(): String? {
        val accountDataIdentityServer =
                mxSession.dataHandler.store.getAccountDataElement(AccountDataElement.ACCOUNT_DATA_TYPE_IDENTITY_SERVER)

        accountDataIdentityServer?.content?.let {
            return it[AccountDataElement.ACCOUNT_DATA_KEY_IDENTITY_SERVER_BASE_URL] as String?
        }

        // Default: use local storage
        return mxSession.homeServerConfig.identityServerUri?.toString()
    }

    fun getIdentityServerUri(): Uri? {
        return getIdentityServerUrl()?.let { Uri.parse(it) }
    }

    /**
     * Disconnect an identity server
     */
    fun disconnect(callback: ApiCallback<Void?>) {
        setIdentityServerUrl(null, callback)
    }

    /**
     * Update the identity server Url.
     * @param newUrl   the new identity server url. Can be null (or empty) to disconnect the identity server and do not use an
     *                 identity server anymore
     * @param callback callback called when account data has been updated successfully
     */
    fun setIdentityServerUrl(newUrl: String?, callback: ApiCallback<Void?>) {
        var newIdentityServer = newUrl
        if (!newIdentityServer.isNullOrBlank() && !newIdentityServer.startsWith("http")) {
            newIdentityServer = "https://$newIdentityServer"
        }

        if (identityPath == newIdentityServer) {
            // No change
            callback.onSuccess(null)
            return
        }

        if (newIdentityServer.isNullOrBlank()) {
            // User want to remove the identity server
            disconnectPreviousIdentityServer(null, callback)
        } else {
            val uri = Uri.parse(newIdentityServer)
            val hsConfig = try {
                // Check that this is a valid Identity server
                HomeServerConnectionConfig.Builder()
                        .withHomeServerUri(uri)
                        .withIdentityServerUri(uri)
                        .build()
            } catch (e: Exception) {
                callback.onUnexpectedError(InvalidParameterException("Invalid url"))
                return
            }

            IdentityPingRestClient(hsConfig).ping(object : ApiCallback<Void> {
                override fun onSuccess(info: Void?) {
                    // Ok, this is an identity server
                    disconnectPreviousIdentityServer(newIdentityServer, callback)
                }

                override fun onUnexpectedError(e: Exception?) {
                    callback.onUnexpectedError(InvalidParameterException("Invalid identity server"))
                }

                override fun onNetworkError(e: Exception?) {
                    callback.onUnexpectedError(InvalidParameterException("Invalid identity server"))
                }

                override fun onMatrixError(e: MatrixError?) {
                    callback.onUnexpectedError(InvalidParameterException("Invalid identity server"))
                }
            })
        }
    }

    private fun disconnectPreviousIdentityServer(newUrl: String?, callback: ApiCallback<Void?>) {
        if (identityAuthRestClient == null) {
            // No previous identity server, go to next step
            updateAccountData(newUrl, callback)
        } else {
            // Disconnect old identity server first
            val storedToken = identityServerTokensStore
                    .getToken(mxSession.myUserId, identityPath ?: "")

            if (storedToken.isNullOrBlank()) {
                // Previous identity server was not logged in, go to next step
                updateAccountData(newUrl, callback)
            } else {
                identityAuthRestClient?.logout(storedToken, object : ApiCallback<Unit> {
                    override fun onUnexpectedError(e: java.lang.Exception?) {
                        // Ignore any error
                        onSuccess(Unit)
                    }

                    override fun onNetworkError(e: java.lang.Exception?) {
                        // Ignore any error
                        onSuccess(Unit)
                    }

                    override fun onMatrixError(e: MatrixError?) {
                        // Ignore any error
                        onSuccess(Unit)
                    }

                    override fun onSuccess(info: Unit) {
                        identityServerTokensStore
                                .resetToken(mxSession.myUserId, identityPath ?: "")

                        updateAccountData(newUrl, callback)
                    }
                })
            }
        }
    }

    private fun updateAccountData(newUrl: String?, callback: ApiCallback<Void?>) {
        // Update AccountData
        val updatedIdentityServerDict = mapOf(AccountDataElement.ACCOUNT_DATA_KEY_IDENTITY_SERVER_BASE_URL to newUrl)

        mxSession.accountDataRestClient.setAccountData(mxSession.myUserId,
                AccountDataElement.ACCOUNT_DATA_TYPE_IDENTITY_SERVER,
                updatedIdentityServerDict,
                object : SimpleApiCallback<Void?>(callback) {
                    override fun onSuccess(info: Void?) {
                        // Note that this code will also be executed by onAccountDataUpdated(), but we do not want to wait for it
                        localSetIdentityServerUrl(newUrl)
                        callback.onSuccess(null)
                    }
                }
        )
    }

    private fun localSetIdentityServerUrl(newUrl: String?) {
        identityPath = newUrl

        if (newUrl.isNullOrBlank()) {
            identityAuthRestClient = null
            thirdPidRestClient = null
        } else {
            val alteredHsConfig = HomeServerConnectionConfig.Builder(mxSession.homeServerConfig)
                    .withIdentityServerUri(Uri.parse(newUrl))
                    .build()

            identityAuthRestClient = IdentityAuthRestClient(alteredHsConfig)
            thirdPidRestClient = ThirdPidRestClient(alteredHsConfig)
        }

        synchronized(listeners) { listeners.forEach { it.onIdentityServerChange() } }
    }

    /**
     * Get the token from the store, or request one
     */
    private fun getToken(callback: ApiCallback<String>) {
        val storeToken = identityServerTokensStore
                .getToken(mxSession.myUserId, identityPath ?: "")

        if (storeToken.isNullOrEmpty().not()) {
            callback.onSuccess(storeToken)
        } else {
            // Request a new token
            mxSession.openIdToken(object : SimpleApiCallback<RequestOpenIdTokenResponse>(callback) {
                override fun onSuccess(info: RequestOpenIdTokenResponse) {
                    requestIdentityServerToken(info, callback)
                }

            })
        }

/*

        identityAuthRestClient?.setAccessToken(storeToken)

        // Check validity
        identityAuthRestClient?.checkAccount(storeToken, object : ApiCallback<Unit> {
            override fun onSuccess(info: Unit?) {
            }

            override fun onUnexpectedError(e: Exception?) {
            }

            override fun onNetworkError(e: Exception?) {
            }

            override fun onMatrixError(e: MatrixError?) {
            }

        })
*/
    }

    private fun requestIdentityServerToken(requestOpenIdTokenResponse: RequestOpenIdTokenResponse, callback: ApiCallback<String>) {
        identityAuthRestClient?.register(requestOpenIdTokenResponse, object : SimpleApiCallback<IdentityServerRegisterResponse>(callback) {
            override fun onSuccess(info: IdentityServerRegisterResponse) {
                // Store the token for next time
                identityServerTokensStore.setToken(mxSession.myUserId, identityPath!!, info.identityServerAccessToken)

                callback.onSuccess(info.identityServerAccessToken)
            }

            override fun onMatrixError(e: MatrixError) {
                // Handle 404
                if (e.mStatus == 404) {
                    callback.onUnexpectedError(IdentityServerV2ApiNotAvailable())
                } else {
                    super.onMatrixError(e)
                }
            }
        })
    }

    fun lookup3Pids(addresses: List<String>, mediums: List<String>, callback: ApiCallback<List<String>>) {
        val client = thirdPidRestClient
        if (client == null) {
            callback.onUnexpectedError(IdentityServerNotConfiguredException())
        } else {
            getToken(object : SimpleApiCallback<String>(callback) {
                override fun onSuccess(info: String) {
                    client.setAccessToken(info)

                    // We need the look up param
                    client.getLookupParam(object : SimpleApiCallback<HashDetailResponse>(callback) {
                        override fun onSuccess(info: HashDetailResponse) {
                            client.lookup3PidsV2(info, addresses, mediums, callback)
                        }

                        override fun onMatrixError(e: MatrixError) {
                            if (e.mStatus == HttpsURLConnection.HTTP_UNAUTHORIZED /* 401 */) {
                                // 401 -> Renew token
                                // Reset the token we know and start again
                                identityServerTokensStore
                                        .resetToken(mxSession.myUserId, identityPath ?: "")
                                lookup3Pids(addresses, mediums, callback)
                            } else if (e.mStatus == HttpsURLConnection.HTTP_FORBIDDEN /* 403 */
                                    && e.errcode == MatrixError.TERMS_NOT_SIGNED) {
                                callback.onUnexpectedError(TermsNotSignedException(info))
                            } else {
                                super.onMatrixError(e)
                            }
                        }
                    })
                }

                override fun onUnexpectedError(e: Exception) {
                    if (e is IdentityServerV2ApiNotAvailable) {
                        // Fallback to v1 API
                        client.lookup3Pids(addresses, mediums, callback)
                    } else {
                        super.onUnexpectedError(e)
                    }
                }
            })
        }
    }



    fun submitValidationToken(medium: String, token: String, clientSecret: String, sid: String, callback: ApiCallback<Boolean>) {
        val client = thirdPidRestClient
        if (client == null) {
            callback.onUnexpectedError(IdentityServerNotConfiguredException())
        } else {
            getToken(object : SimpleApiCallback<String>(callback) {
                override fun onSuccess(info: String) {
                    client.setAccessToken(info)
                    client.submitValidationToken(medium, token, clientSecret, sid, object : SimpleApiCallback<Boolean>(callback) {
                        override fun onSuccess(info: Boolean) {
                            callback.onSuccess(info)
                        }

                        override fun onMatrixError(e: MatrixError) {
                            if (e.mStatus == HttpsURLConnection.HTTP_UNAUTHORIZED /* 401 */) {
                                // 401 -> Renew token
                                // Reset the token we know and start again
                                identityServerTokensStore
                                        .resetToken(mxSession.myUserId, identityPath ?: "")
                                submitValidationToken(medium, token, clientSecret, sid, callback)
                            } else if (e.mStatus == HttpsURLConnection.HTTP_FORBIDDEN /* 403 */
                                    && e.errcode == MatrixError.TERMS_NOT_SIGNED) {
                                callback.onUnexpectedError(TermsNotSignedException(info))
                            } else {
                                super.onMatrixError(e)
                            }
                        }
                    })
                }

                override fun onUnexpectedError(e: Exception) {
                    if (e is IdentityServerV2ApiNotAvailable) {
                        // Fallback to v1 API
                        client.submitValidationTokenLegacy(medium, token, clientSecret, sid, callback)
                    } else {
                        super.onUnexpectedError(e)
                    }
                }
            })
        }
    }
}
