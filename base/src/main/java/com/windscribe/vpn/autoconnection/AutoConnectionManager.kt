package com.windscribe.vpn.autoconnection

import androidx.fragment.app.DialogFragment
import com.windscribe.vpn.ServiceInteractor
import com.windscribe.vpn.Windscribe.Companion.appContext
import com.windscribe.vpn.alert.showErrorDialog
import com.windscribe.vpn.backend.Util
import com.windscribe.vpn.backend.VPNState
import com.windscribe.vpn.backend.utils.WindVpnController
import com.windscribe.vpn.commonutils.Ext.result
import com.windscribe.vpn.commonutils.WindUtilities
import com.windscribe.vpn.constants.PreferencesKeyConstants
import com.windscribe.vpn.localdatabase.tables.NetworkInfo
import com.windscribe.vpn.repository.CallResult
import com.windscribe.vpn.repository.ConnectionDataRepository
import com.windscribe.vpn.state.NetworkInfoListener
import com.windscribe.vpn.state.NetworkInfoManager
import com.windscribe.vpn.state.VPNConnectionStateManager
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.rx2.await
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Singleton

@Singleton
class AutoConnectionManager(
    private val scope: CoroutineScope,
    private val vpnConnectionStateManager: Lazy<VPNConnectionStateManager>,
    private val vpnController: Lazy<WindVpnController>,
    private val networkInfoManager: NetworkInfoManager,
    private val interactor: ServiceInteractor,
    private val connectionDataRepository: ConnectionDataRepository

) : NetworkInfoListener {

    private var continuation: CancellableContinuation<Boolean>? = null
    private val logger = LoggerFactory.getLogger("auto_connection_manager")
    var listOfProtocols = emptyList<ProtocolInformation>().toMutableList()
    private var manualProtocol: ProtocolInformation? = null
    private var lastKnownProtocolInformation: Pair<String, ProtocolInformation>? = null
    private var preferredProtocol: Pair<String, ProtocolInformation?>? = null
    private val _nextInLineProtocol = MutableStateFlow(listOfProtocols.firstOrNull())
    val nextInLineProtocol: StateFlow<ProtocolInformation?> = _nextInLineProtocol
    private val _connectedProtocol: MutableStateFlow<ProtocolInformation?> = MutableStateFlow(null)
    val connectedProtocol: StateFlow<ProtocolInformation?> = _connectedProtocol
    private var lastProtocolLog = String()

    init {
        networkInfoManager.addNetworkInfoListener(this@AutoConnectionManager)
        scope.launch {
            vpnConnectionStateManager.get().state.collectLatest { vpnState ->
                vpnState.protocolInformation?.let { protocolInformation ->
                    if (vpnState.status == VPNState.Status.Disconnected) {
                        listOfProtocols.firstOrNull {
                            it.protocol == protocolInformation.protocol && it.port == protocolInformation.port
                        }?.type = ProtocolConnectionStatus.Disconnected
                    }
                    if (vpnState.status == VPNState.Status.Connected) {
                        listOfProtocols.firstOrNull {
                            it.protocol == protocolInformation.protocol && it.port == protocolInformation.port
                        }?.let { it ->
                            listOfProtocols.firstOrNull { it.type == ProtocolConnectionStatus.Connected }?.type =
                                ProtocolConnectionStatus.Disconnected
                            it.type = ProtocolConnectionStatus.Connected
                        }
                    }
                }
            }
        }
    }

    override fun onNetworkInfoUpdate(networkInfo: NetworkInfo?, userReload: Boolean) {
        if (networkInfo?.networkName != preferredProtocol?.first || networkInfo?.protocol != preferredProtocol?.second?.protocol || networkInfo?.port != preferredProtocol?.second?.port) {
            reset()
        }
    }

    fun reset() {
        listOfProtocols.clear()
        listOfProtocols = reloadProtocols()
        logger.debug("$listOfProtocols")
    }

    fun stop() {
        isEnabled = false
        continuation?.let {
            logger.debug("Cancelling auto connect.")
            it.cancel()
            continuation = null
            dismissDialog()
        }
    }

    private fun connectionAttempt(
        attempt: Int = 0,
        protocolInformation: ProtocolInformation? = null
    ): VPNState {
        return runBlocking {
            val newConnectionId = UUID.randomUUID()
            vpnController.get().connect(
                connectionId = newConnectionId,
                protocolInformation = protocolInformation,
                attempt = attempt
            )
            vpnConnectionStateManager.get().state
                .first {
                    if (it.connectionId == newConnectionId) {
                        if (it.error?.error == VPNState.ErrorType.AuthenticationError) {
                            logger.debug("Updating user auth credentials.")
                            connectionDataRepository.update().result()
                            true
                        } else {
                            it.status != VPNState.Status.Connecting
                        }
                    } else {
                        false
                    }
                }
        }
    }

    suspend fun connectInForeground() = suspendCancellableCoroutine<Boolean> { it ->
        stop()
        this.continuation = it
        isEnabled = true
        if (isEnabled) {
            val connectionResult = connectionAttempt()
            if (connectionResult.status == VPNState.Status.Connected) {
                isEnabled = false
                stop()
            } else if (connectionResult.error?.showError == true) {
                connectionResult.error?.message?.let { message -> showErrorDialog(message = message) }
                isEnabled = false
                stop()
            }
        }
        if (WindUtilities.isOnline().not() && isEnabled) {
            logger.debug("No internet detected. existing.")
            stop()
        }
        if (isEnabled) {
            logger.debug("Engaging auto connect.")
            listOfProtocols.firstOrNull { it.protocol == interactor.preferenceHelper.selectedProtocol }?.type =
                ProtocolConnectionStatus.Failed
            engageAutomaticMode()
        }
    }

    suspend fun changeProtocolInForeground() = suspendCancellableCoroutine<Boolean> { it ->
        isEnabled = true
        this.continuation = it
        if (isEnabled) {
            if (WindUtilities.isOnline().not()) {
                logger.debug("No internet detected. existing.")
                stop()
            } else {
                logger.debug("Engaging auto connect.")
                listOfProtocols.firstOrNull { it.protocol == interactor.preferenceHelper.selectedProtocol }?.type =
                    ProtocolConnectionStatus.Connected
                engageConnectionChangeMode()
            }
        }
    }

    private fun reloadProtocols(): MutableList<ProtocolInformation> {
        var appSupportedProtocolOrder = if (listOfProtocols.size > 0) {
            listOfProtocols
        } else {
            Util.getAppSupportedProtocolList()
        }
        if (interactor.preferenceHelper.getResponseString(PreferencesKeyConstants.CONNECTION_MODE_KEY) != PreferencesKeyConstants.CONNECTION_MODE_AUTO) {
            setupManualProtocol(
                interactor.preferenceHelper.savedProtocol,
                appSupportedProtocolOrder
            )
        }
        networkInfoManager.networkInfo?.let {
            setupPreferredProtocol(it, appSupportedProtocolOrder)
        }
        manualProtocol?.let {
            appSupportedProtocolOrder = moveProtocolToTop(it, appSupportedProtocolOrder)
        }
        preferredProtocol?.second?.let {
            appSupportedProtocolOrder = moveProtocolToTop(it, appSupportedProtocolOrder)
        } ?: kotlin.run {
            lastKnownProtocolInformation?.let {
                appSupportedProtocolOrder = moveProtocolToTop(it.second, appSupportedProtocolOrder)
            }
        }
        appSupportedProtocolOrder.filter { it.type == ProtocolConnectionStatus.NextUp }
            .forEach { it.type = ProtocolConnectionStatus.Disconnected }
        appSupportedProtocolOrder[0].type = ProtocolConnectionStatus.NextUp
        val protocolLog =
            "Last known: ${lastKnownProtocolInformation ?: ""} Preferred: ${preferredProtocol ?: ""} Manual: ${manualProtocol ?: ""}"
        if (protocolLog != lastProtocolLog) {
            logger.debug(protocolLog)
        }
        lastProtocolLog = protocolLog
        scope.launch { _nextInLineProtocol.emit(appSupportedProtocolOrder[0]) }
        return appSupportedProtocolOrder
    }


    private fun moveProtocolToTop(
        protocolInformation: ProtocolInformation,
        appSupportedProtocolOrder: MutableList<ProtocolInformation>
    ): MutableList<ProtocolInformation> {
        val index =
            appSupportedProtocolOrder.indexOfFirst { it.protocol == protocolInformation.protocol }
        appSupportedProtocolOrder.removeAt(index)
        appSupportedProtocolOrder.add(0, protocolInformation)
        return appSupportedProtocolOrder
    }

    private fun setupManualProtocol(
        protocol: String,
        appSupportedProtocolOrder: List<ProtocolInformation>
    ) {
        val port: String = when (protocol) {
            PreferencesKeyConstants.PROTO_IKev2 -> interactor.preferenceHelper.iKEv2Port
            PreferencesKeyConstants.PROTO_UDP -> interactor.preferenceHelper.savedUDPPort
            PreferencesKeyConstants.PROTO_TCP -> interactor.preferenceHelper.savedTCPPort
            PreferencesKeyConstants.PROTO_STEALTH -> interactor.preferenceHelper.savedSTEALTHPort
            PreferencesKeyConstants.PROTO_WIRE_GUARD -> interactor.preferenceHelper.wireGuardPort
            else -> PreferencesKeyConstants.DEFAULT_IKEV2_PORT
        }
        manualProtocol = appSupportedProtocolOrder.firstOrNull {
            it.protocol == interactor.preferenceHelper.savedProtocol
        }
        manualProtocol?.port = port
    }

    private fun setupPreferredProtocol(
        networkInfo: NetworkInfo,
        appSupportedProtocolOrder: List<ProtocolInformation>
    ) {
        preferredProtocol = Pair(networkInfo.networkName, null)
        if (networkInfo.isPreferredOn) {
            val protocol = appSupportedProtocolOrder.firstOrNull {
                it.protocol == networkInfo.protocol
            }
            protocol?.port = networkInfo.port
            protocol?.let {
                preferredProtocol = Pair(networkInfo.networkName, protocol)
            }
        }
    }

    private fun engageAutomaticMode() {
        listOfProtocols.sortBy {
            it.type
        }
        val disconnectedProtocolList =
            listOfProtocols.filter { it.type == ProtocolConnectionStatus.Disconnected }
        if (disconnectedProtocolList.isNotEmpty()) {
            disconnectedProtocolList[0].type = ProtocolConnectionStatus.NextUp
            disconnectedProtocolList[0].autoConnectTimeLeft = 10
            showConnectionFailureDialog(
                listOfProtocols,
                retry = { engageAutomaticMode() })
        } else {
            logger.debug("Showing all protocol failed dialog.")
            lastKnownProtocolInformation = null
            reset()
            showAllProtocolFailedDialog()
        }
    }

    private fun engageConnectionChangeMode() {
        listOfProtocols.filter { it.type == ProtocolConnectionStatus.NextUp }
            .forEach { it.type = ProtocolConnectionStatus.Disconnected }
        val connectedProtocol =
            listOfProtocols.firstOrNull { it.type == ProtocolConnectionStatus.Connected }
        if (connectedProtocol != null) {
            val index =
                listOfProtocols.indexOfFirst { it.type == ProtocolConnectionStatus.Connected }
            if (index != -1) {
                listOfProtocols.removeAt(index)
                listOfProtocols.add(0, connectedProtocol)
            }
            showConnectionChangeDialog(
                listOfProtocols,
                retry = { engageAutomaticMode() })
        } else {
            logger.debug("Showing all protocol failed dialog.")
            showAllProtocolFailedDialog()
        }
    }

    private fun showAllProtocolFailedDialog() {
        val launched = appContext.applicationInterface.launchFragment(
            emptyList(),
            FragmentType.AllProtocolFailed,
            autoConnectionModeCallback = object : AutoConnectionModeCallback {
                override fun onCancel() {
                    logger.debug("Cancel clicked existing auto connect.")
                    stop()
                }

                override fun onSendLogClicked() {
                    logger.debug("Send log clicked.")
                    sendLog()
                }
            })
        if (launched.not()) {
            logger.debug("App is in background. existing auto connect.")
            stop()
        }
    }

    private fun sendLog() {
        if (isEnabled) {
            CoroutineScope(continuation!!.context).launch {
                when (val result = interactor.sendLog()) {
                    is CallResult.Error -> {
                        logger.debug("Error sending log ${result.errorMessage}")
                        dismissDialog()
                        stop()
                    }
                    is CallResult.Success -> {
                        dismissDialog()
                        contactSupport()
                    }
                }
            }
        }
    }

    private fun dismissDialog() {
        val dialog = appContext.activeActivity?.supportFragmentManager?.fragments?.firstOrNull {
            it is DialogFragment
        } as? DialogFragment
        dialog?.dismiss()
    }

    private fun contactSupport() {
        logger.debug("Showing contact support dialog.")
        val launched = appContext.applicationInterface.launchFragment(
            emptyList(),
            FragmentType.DebugLogSent,
            autoConnectionModeCallback = object : AutoConnectionModeCallback {
                override fun onCancel() {
                    logger.debug("Cancel clicked existing auto connect.")
                    stop()
                }

                override fun onContactSupportClick() {
                    stop()
                    logger.debug("On contact support clicked existing auto connect.")
                }
            })
        if (launched.not()) {
            logger.debug("App is in background. existing auto connect.")
            stop()
        }
    }

    private fun saveNetworkForFutureUse(protocolInformation: ProtocolInformation) {
        if (networkInfoManager.networkInfo?.isPreferredOn == true) {
            logger.debug("Preferred protocol for this network is already set. existing auto connect.")
            stop()
            return
        }
        val netWorkName = networkInfoManager.networkInfo?.networkName
        if (netWorkName != null) {
            lastKnownProtocolInformation = Pair(netWorkName, protocolInformation)
            logger.debug("Showing set as preferred protocol dialog.")
            val launched = appContext.applicationInterface.launchFragment(
                emptyList(),
                FragmentType.SetupAsPreferredProtocol,
                autoConnectionModeCallback = object : AutoConnectionModeCallback {
                    override fun onCancel() {
                        logger.debug("Cancel clicked existing auto connect.")
                        stop()
                    }

                    override fun onSetAsPreferredClicked() {
                        setProtocolAsPreferred(protocolInformation)
                    }
                }, protocolInformation
            )
            if (launched.not()) {
                logger.debug("App is in background. existing auto connect.")
                stop()
            }
        }
    }

    private var isEnabled: Boolean = false

    private fun setProtocolAsPreferred(protocolInformation: ProtocolInformation) {
        networkInfoManager.networkInfo?.let {
            it.protocol = protocolInformation.protocol
            it.port = protocolInformation.port
            it.isPreferredOn = true
            it.isAutoSecureOn = true
            if (isEnabled) {
                CoroutineScope(continuation!!.context).launch {
                    interactor.saveNetwork(it).await()
                    logger.debug("Saved ${protocolInformation.protocol}:${protocolInformation.port} for SSID: ${it.networkName}")
                }
            }
            stop()
        } ?: kotlin.run {
            stop()
        }
    }

    private fun showConnectionFailureDialog(
        protocolInformation: List<ProtocolInformation>,
        retry: () -> Unit
    ) {
        val launched = appContext.applicationInterface.launchFragment(
            protocolInformation,
            FragmentType.ConnectionFailure,
            autoConnectionModeCallback = object : AutoConnectionModeCallback {
                override fun onCancel() {
                    logger.debug("Cancel clicked existing auto connect.")
                    isEnabled = false
                }

                override fun onProtocolSelect(protocolInformation: ProtocolInformation) {
                    logger.debug("Next selected protocol: ${protocolInformation.protocol}:${protocolInformation.port}")
                    if (WindUtilities.isOnline().not()) {
                        logger.debug("No internet detected. existing.")
                        stop()
                    }
                    if (isEnabled) {
                        CoroutineScope(continuation!!.context).launch {
                            val connectionResult =
                                connectionAttempt(protocolInformation = protocolInformation)
                            listOfProtocols.firstOrNull {
                                it.type == ProtocolConnectionStatus.NextUp
                            }?.type = ProtocolConnectionStatus.Disconnected
                            if (connectionResult.status == VPNState.Status.Connected) {
                                listOfProtocols.firstOrNull { it.protocol == protocolInformation.protocol }?.type =
                                    ProtocolConnectionStatus.Connected
                                logger.debug("Successfully found a working protocol: ${protocolInformation.protocol}:${protocolInformation.port}")
                                if (networkInfoManager.networkInfo?.port != protocolInformation.port && networkInfoManager.networkInfo?.protocol != protocolInformation.protocol) {
                                    saveNetworkForFutureUse(protocolInformation)
                                }
                            } else if (connectionResult.error?.showError == true) {
                                showErrorDialog(connectionResult.error?.message ?: "")
                                stop()
                            } else {
                                listOfProtocols.firstOrNull { it.protocol == protocolInformation.protocol }?.type =
                                    ProtocolConnectionStatus.Failed
                                logger.debug("Auto connect failure: ${protocolInformation.protocol}:${protocolInformation.port} ${connectionResult.error?.message}")
                                retry()
                            }
                        }
                    }
                }
            })
        if (launched.not()) {
            logger.debug("App is in background. existing auto connect.")
            stop()
        }
    }

    private fun showConnectionChangeDialog(
        protocolInformation: List<ProtocolInformation>,
        retry: () -> Unit
    ) {
        val launched = appContext.applicationInterface.launchFragment(
            protocolInformation,
            FragmentType.ConnectionChange,
            autoConnectionModeCallback = object : AutoConnectionModeCallback {
                override fun onCancel() {
                    logger.debug("Cancel clicked existing auto connect.")
                    stop()
                }

                override fun onProtocolSelect(protocolInformation: ProtocolInformation) {
                    listOfProtocols.firstOrNull {
                        it.type == ProtocolConnectionStatus.Connected || it.type == ProtocolConnectionStatus.NextUp
                    }?.type = ProtocolConnectionStatus.Disconnected
                    logger.debug("User changed protocol: ${protocolInformation.protocol}:${protocolInformation.port}")

                    if (isEnabled) {
                        CoroutineScope(continuation!!.context).launch {
                            delay(300)
                            if (WindUtilities.isOnline().not()) {
                                logger.debug("No internet detected. existing.")
                                stop()
                            }
                            var connectionResult =
                                connectionAttempt(protocolInformation = protocolInformation)
                            if (connectionResult.error?.error == VPNState.ErrorType.AuthenticationError) {
                                connectionResult = connectionAttempt(1, protocolInformation)
                            }
                            if (connectionResult.status == VPNState.Status.Connected) {
                                listOfProtocols.firstOrNull { it.protocol == protocolInformation.protocol }?.type =
                                    ProtocolConnectionStatus.Connected
                                logger.debug("Successfully found a working protocol: ${protocolInformation.protocol}:${protocolInformation.port}")
                                if (networkInfoManager.networkInfo?.port != protocolInformation.port && networkInfoManager.networkInfo?.protocol != protocolInformation.protocol) {
                                    saveNetworkForFutureUse(protocolInformation)
                                }
                            } else if (connectionResult.error?.showError == true) {
                                connectionResult.error?.message?.let { showErrorDialog(it) }
                                stop()
                            } else {
                                listOfProtocols.firstOrNull { it.protocol == protocolInformation.protocol }?.type =
                                    ProtocolConnectionStatus.Failed
                                logger.debug("Protocol change failure: ${protocolInformation.protocol}:${protocolInformation.port} ${connectionResult.error?.message}")
                                retry()
                            }
                        }
                    }
                }
            })
        if (launched.not()) {
            logger.debug("App is in background. existing auto connect.")
            stop()
        }
    }

    fun setSelectedProtocol(protocolInformation: ProtocolInformation) {
        logger.debug("Trying to connect: $protocolInformation")
        scope.launch {
            interactor.preferenceHelper.selectedProtocol = protocolInformation.protocol
            interactor.preferenceHelper.selectedPort = protocolInformation.port
            interactor.preferenceHelper.selectedProtocolType = protocolInformation.type
            _connectedProtocol.emit(protocolInformation)
        }
    }
}