package com.tvstudio.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvstudio.adb.DadbTvShell
import com.tvstudio.core.BlockReason
import com.tvstudio.core.Debloater
import com.tvstudio.core.DebloatRefused
import com.tvstudio.core.DebloatReport
import com.tvstudio.core.ListParser
import com.tvstudio.core.ModelEntry
import com.tvstudio.core.ModelMatch
import com.tvstudio.core.ModelMatcher
import com.tvstudio.core.Network
import com.tvstudio.core.PortStatus
import com.tvstudio.core.RestoreReport
import com.tvstudio.core.RestoreStore
import com.tvstudio.core.ReviewBuilder
import com.tvstudio.core.ReviewItem
import com.tvstudio.core.TvAddress
import com.tvstudio.core.TvIdentity
import com.tvstudio.core.TvInspector
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

enum class Screen { SETUP, CONNECT, CHOOSE, REVIEW, RUNNING, FINISH }

data class UiState(
    val screen: Screen = Screen.SETUP,
    val busy: String? = null,
    val error: String? = null,
    val scanning: Boolean = false,
    val scanResults: List<String>? = null,
    val address: TvAddress? = null,
    val tv: TvIdentity? = null,
    val hasRestoreRecord: Boolean = false,
    val matches: List<ModelMatch> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<ModelEntry> = emptyList(),
    val reviewTitle: String = "",
    val reviewNotes: List<String> = emptyList(),
    val reviewItems: List<ReviewItem> = emptyList(),
    val checked: Set<String> = emptySet(),
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val skipped: Map<String, BlockReason> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val report: DebloatReport? = null,
    val restoreReport: RestoreReport? = null,
    val rebootSent: Boolean = false,
    val cameBack: List<String>? = null,
    val portStatus: PortStatus? = null,
)

/** Drives the flow. One operation runs at a time, and the screens show [UiState.busy] while it does. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val assets get() = getApplication<Application>().assets
    private val files get() = getApplication<Application>().filesDir
    private val store by lazy { RestoreStore(File(files, "restore")) }
    private val models by lazy {
        assets.open("lists/index.tsv").bufferedReader().use { ModelMatcher.parseIndex(it.readText()) }
    }

    private var shell: DadbTvShell? = null

    val notices: String
        get() = assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }

    fun goTo(screen: Screen) = _state.update { it.copy(screen = screen, error = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Returns true if the back press was used. The run screen can't be left mid-run. */
    fun back(): Boolean {
        val s = _state.value
        if (s.busy != null) return true
        val previous = when (s.screen) {
            Screen.SETUP -> return false
            Screen.CONNECT -> Screen.SETUP
            Screen.CHOOSE -> Screen.CONNECT
            Screen.REVIEW -> Screen.CHOOSE
            Screen.RUNNING, Screen.FINISH -> return true
        }
        goTo(previous)
        return true
    }

    fun scanNetwork() {
        val phone = phoneIp()
        if (phone == null) {
            _state.update { it.copy(error = "This phone doesn't seem to be on Wi-Fi. Connect it to the same network as the TV.") }
            return
        }
        _state.update { it.copy(scanning = true, scanResults = null, error = null) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val limit = Semaphore(48)
                Network.neighbours(phone).map { host ->
                    async {
                        limit.withPermit {
                            host.takeIf { Network.probe(it, TvAddress.ADB_PORT, 400) == PortStatus.OPEN }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            _state.update { it.copy(scanning = false, scanResults = found) }
        }
    }

    fun connect(text: String) {
        val address = TvAddress.parse(text)
        if (address == null) {
            _state.update { it.copy(error = "Enter an address like 192.168.1.50. You'll find it in the TV's network settings.") }
            return
        }
        work("Connecting. If the TV asks \"Allow USB debugging?\", tick Always allow and choose Allow.") {
            shell?.close()
            val connected = DadbTvShell.connect(address.host, address.port, files)
            shell = connected
            val tv = TvInspector(connected).identify()
            if (!tv.isTv) {
                connected.close()
                shell = null
                _state.update {
                    it.copy(error = "${tv.label} doesn't identify as an Android TV, so TVStudio won't change anything on it.")
                }
                return@work
            }
            _state.update {
                it.copy(
                    screen = Screen.CHOOSE,
                    address = address,
                    tv = tv,
                    matches = ModelMatcher.match(tv, models),
                    hasRestoreRecord = store.load(tv.stableKey) != null,
                )
            }
        }
    }

    fun search(query: String) = _state.update {
        it.copy(searchQuery = query, searchResults = ModelMatcher.search(query, models).take(30))
    }

    fun chooseModel(entry: ModelEntry) = work("Checking what's on this TV") {
        val text = assets.open("lists/${entry.slug}/debloat.txt").bufferedReader().use { it.readText() }
        val list = ListParser.parse(text)
        val inspector = TvInspector(requireShell())
        val items = ReviewBuilder.fromList(list, inspector.readStates(), inspector.readFacts())
        showReview("List for ${entry.tv}", list.headerNotes + listOfNotNull(entry.notes.ifBlank { null }), items)
    }

    fun scanTv() = work("Reading the apps on this TV") {
        val inspector = TvInspector(requireShell())
        val items = ReviewBuilder.fromScan(inspector.readStates(), inspector.readFacts())
        showReview("Apps found on this TV", emptyList(), items)
    }

    private fun showReview(title: String, notes: List<String>, items: List<ReviewItem>) = _state.update { state ->
        state.copy(
            screen = Screen.REVIEW,
            reviewTitle = title,
            reviewNotes = notes,
            reviewItems = items,
            checked = items.filter { it.preselected }.map { it.pkg }.toSet(),
        )
    }

    fun toggle(item: ReviewItem) {
        if (item.lockedBecause != null) return
        _state.update { s ->
            s.copy(checked = if (item.pkg in s.checked) s.checked - item.pkg else s.checked + item.pkg)
        }
    }

    fun apply() {
        val s = _state.value
        val tv = s.tv ?: return
        val selected = s.reviewItems.filter { it.pkg in s.checked && it.lockedBecause == null }.map { it.pkg }
        _state.update {
            it.copy(screen = Screen.RUNNING, progressDone = 0, progressTotal = 0, report = null, restoreReport = null, cameBack = null, portStatus = null, rebootSent = false)
        }
        work("Checking each app is safe to disable", onFailure = Screen.REVIEW) {
            val debloater = Debloater(requireShell(), store)
            val plan = debloater.preflight(selected, tv.sdk)
            _state.update {
                it.copy(skipped = plan.blocked, warnings = plan.warnings, progressTotal = plan.toDisable.size, busy = "Disabling apps. Keep this screen open.")
            }
            val report = debloater.apply(plan, tv.stableKey, tv.label) { done, total ->
                _state.update { it.copy(progressDone = done, progressTotal = total) }
            }
            _state.update {
                it.copy(screen = Screen.FINISH, report = report, hasRestoreRecord = store.load(tv.stableKey) != null)
            }
        }
    }

    fun saveAndReboot() = work("Saving changes on the TV. This takes about 15 seconds.") {
        val debloater = Debloater(requireShell(), store)
        debloater.flushToDisk()
        debloater.reboot()
        closeShell()
        _state.update { it.copy(rebootSent = true, cameBack = null) }
    }

    /** After a reboot: reconnect, then look for what the firmware switched back on. */
    fun recheck() = work("Reconnecting to the TV") {
        val shell = ensureConnected()
        val tv = _state.value.tv ?: return@work
        _state.update { it.copy(cameBack = Debloater(shell, store).cameBack(tv.stableKey)) }
    }

    fun restore() = work("Turning everything back on") {
        val shell = ensureConnected()
        val tv = _state.value.tv ?: return@work
        val report = Debloater(shell, store).restore(tv.stableKey)
        _state.update {
            it.copy(
                screen = Screen.FINISH,
                restoreReport = report,
                report = null,
                cameBack = null,
                hasRestoreRecord = store.load(tv.stableKey) != null,
            )
        }
    }

    /** Only a refused connection counts as closed, and an open connection of ours would hide the answer. */
    fun checkPortClosed() = work("Checking the TV's debugging port") {
        closeShell()
        val address = _state.value.address ?: return@work
        _state.update { it.copy(portStatus = Network.probe(address.host, address.port, 3000)) }
    }

    fun startOver() {
        closeShell()
        _state.value = UiState(screen = Screen.CONNECT)
    }

    private fun requireShell(): DadbTvShell = shell ?: throw DebloatRefused("Not connected to the TV. Go back and connect again.")

    private fun ensureConnected(): DadbTvShell {
        shell?.let { return it }
        val address = _state.value.address ?: throw DebloatRefused("Not connected to the TV. Go back and connect again.")
        return DadbTvShell.connect(address.host, address.port, files).also { shell = it }
    }

    private fun closeShell() {
        runCatching { shell?.close() }
        shell = null
    }

    private fun work(message: String, onFailure: Screen? = null, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = message, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                val text = when (e) {
                    is DebloatRefused -> e.message.orEmpty()
                    is com.tvstudio.core.BadListException -> e.message.orEmpty()
                    is com.tvstudio.core.CorruptRecordException -> e.message.orEmpty()
                    else -> DadbTvShell.describeFailure(e)
                }
                // After a failure the connection can't be trusted, so the next step reconnects.
                closeShell()
                _state.update { it.copy(error = text, screen = onFailure ?: it.screen) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    override fun onCleared() {
        closeShell()
    }

    private fun phoneIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
