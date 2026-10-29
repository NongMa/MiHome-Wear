package com.mijia.wear.vm

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mijia.wear.data.AuthStore
import com.mijia.wear.model.ActionDef
import com.mijia.wear.model.DeviceDetail
import com.mijia.wear.model.DeviceInfo
import com.mijia.wear.model.PropDef
import com.mijia.wear.model.SceneInfo
import com.mijia.wear.net.AuthExpiredException
import com.mijia.wear.net.MiApiException
import com.mijia.wear.net.MiCloudClient
import com.mijia.wear.net.QrLoginData
import com.mijia.wear.net.XiaomiAuth
import com.mijia.wear.repo.IconRepository
import com.mijia.wear.repo.MiRepository
import com.mijia.wear.repo.SpecRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用生命周期阶段。 */
enum class Stage { CHECKING, NEED_LOGIN, LOGGED_IN }

/**
 * 全应用唯一的状态中枢：登录态、设备/场景数据、属性值与操作提示。
 * 网络调用全部走 IO 单线程语义（仓库内部已切 Dispatchers.IO）。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 写属性/执行动作后，等待设备联动生效再回读真实状态的延迟（毫秒）。 */
        private const val SETTLE_DELAY_MS = 1000L
    }

    private val authStore = AuthStore(app)
    private val auth = XiaomiAuth(authStore)
    private val client = MiCloudClient(authStore, auth)
    private val specRepo = SpecRepository(app)
    val repo = MiRepository(client, specRepo)
    private val iconRepo = IconRepository(app, client)

    private val _stage = MutableStateFlow(Stage.CHECKING)
    val stage: StateFlow<Stage> = _stage

    private val _qr = MutableStateFlow<QrLoginData?>(null)
    val qr: StateFlow<QrLoginData?> = _qr

    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices

    /** 最近一次刷新被过滤掉的设备数（离线 + 路由器），用于空态提示。 */
    private val _hiddenCount = MutableStateFlow(0)
    val hiddenCount: StateFlow<Int> = _hiddenCount

    private val _powers = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val powers: StateFlow<Map<String, Boolean?>> = _powers

    /** model → 官方图标（米家 CDN），加载完成后用于替换 emoji 占位符。 */
    private val _icons = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val icons: StateFlow<Map<String, Bitmap>> = _icons

    private val _scenes = MutableStateFlow<List<SceneInfo>>(emptyList())
    val scenes: StateFlow<List<SceneInfo>> = _scenes

    private val _detail = MutableStateFlow<DeviceDetail?>(null)
    val detail: StateFlow<DeviceDetail?> = _detail

    private val _currentDevice = MutableStateFlow<DeviceInfo?>(null)
    val currentDevice: StateFlow<DeviceInfo?> = _currentDevice

    private val _propValues = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val propValues: StateFlow<Map<String, Any?>> = _propValues

    private val _speakers = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val speakers: StateFlow<List<DeviceInfo>> = _speakers

    private val _activeSpeaker = MutableStateFlow("")
    val activeSpeaker: StateFlow<String> = _activeSpeaker

    private var pollJob: Job? = null

    init {
        startup()
    }

    // ---------- 登录 ----------

    private fun startup() = viewModelScope.launch {
        _stage.value = Stage.CHECKING
        val ok = withContext(Dispatchers.IO) {
            runCatching { client.ensureAvailable() }.isSuccess
        }
        if (ok) {
            _stage.value = Stage.LOGGED_IN
            loadAll()
        } else {
            _stage.value = Stage.NEED_LOGIN
        }
    }

    /** 请求二维码；token 仍有效时直接进入主界面。 */
    fun beginLogin() = viewModelScope.launch {
        _loggingIn.value = true
        try {
            val data = withContext(Dispatchers.IO) { auth.beginQrLogin() }
            if (data == null) {
                _stage.value = Stage.LOGGED_IN
                loadAll()
            } else {
                _qr.value = data
                startPolling(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MiHomeAuth", "beginLogin 失败", e)
            _toast.value = "获取二维码失败：${e.message ?: "网络异常"}"
        } finally {
            _loggingIn.value = false
        }
    }

    private fun startPolling(data: QrLoginData) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { auth.completeQrLogin(data) }
                _qr.value = null
                _stage.value = Stage.LOGGED_IN
                _toast.value = "登录成功"
                loadAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MiHomeAuth", "扫码长轮询失败", e)
                _toast.value = "扫码超时或失败：${e.message ?: "请刷新二维码"}"
                _qr.value = null
            }
        }
    }

    fun logout() {
        pollJob?.cancel()
        withContextSafe { auth.logout() }
        _devices.value = emptyList()
        _hiddenCount.value = 0
        _scenes.value = emptyList()
        _powers.value = emptyMap()
        _qr.value = null
        _stage.value = Stage.NEED_LOGIN
    }

    private fun withContextSafe(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { runCatching(block) }
    }

    // ---------- 数据加载 ----------

    fun loadAll() {
        loadDevices()
        loadScenes()
    }

    fun loadDevices() = viewModelScope.launch {
        _loading.value = true
        try {
            val all = withContext(Dispatchers.IO) { repo.devices() }
            // 排障：把设备型号打出来，便于按 model 查 miot-spec 是否支持标准视频流
            all.forEach {
                android.util.Log.d("MiHomeWear", "device name=${it.name} model=${it.model} did=${it.did} online=${it.online}")
            }
            // 手表上只保留可控设备：路由器没有可用 spec，离线设备控制必然失败
            val list = all.filter { it.online && !it.isRouter }
            _hiddenCount.value = all.size - list.size
            _devices.value = list
            _speakers.value = list.filter { it.isSpeaker }
            // 官方图标异步补全：不阻塞列表展示，取到一个刷新一次状态
            loadIcons(list)
            if (_activeSpeaker.value.isEmpty()) {
                _activeSpeaker.value = repo.pickSpeaker(list)?.did ?: ""
            }
            // 开关状态另取：需要按 spec 批量查
            val power = withContext(Dispatchers.IO) { repo.powerStates(list) }
            _powers.value = power
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthExpiredException) {
            _stage.value = Stage.NEED_LOGIN
        } catch (e: Exception) {
            _toast.value = repo.describe(e)
        } finally {
            _loading.value = false
        }
    }

    /** 按 model 拉官方图标（CDN PNG），新取到的合并进 [icons]。 */
    private fun loadIcons(list: List<DeviceInfo>) {
        val models = list.map { it.model }
        if (models.isEmpty()) return
        viewModelScope.launch {
            val found = iconRepo.ensure(models)
            if (found.isNotEmpty()) _icons.value = _icons.value + found
        }
    }

    fun loadScenes() = viewModelScope.launch {
        try {
            val list = withContext(Dispatchers.IO) { repo.scenes() }
            _scenes.value = list
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthExpiredException) {
            _stage.value = Stage.NEED_LOGIN
        } catch (e: Exception) {
            _toast.value = repo.describe(e)
        }
    }

    fun clearToast() {
        _toast.value = null
    }

    /** 供界面层直接提示（如系统能力缺失）。 */
    fun showToast(message: String) {
        _toast.value = message
    }

    // ---------- 设备详情与控制 ----------

    /** 打开设备详情：按 did 查找设备 → 拉规格（已加载则复用结构） → 自动向云端拉取最新属性状态。 */
    fun openDevice(did: String) = viewModelScope.launch {
        val device = _devices.value.firstOrNull { it.did == did }
        if (device == null) {
            if (_devices.value.isNotEmpty()) _toast.value = "未找到设备"
            return@launch
        }
        val sameDevice = _currentDevice.value?.did == did && _detail.value != null
        _currentDevice.value = device

        try {
            val d = if (sameDevice) {
                _detail.value
            } else {
                _detail.value = null
                _propValues.value = emptyMap()
                withContext(Dispatchers.IO) { repo.deviceDetail(device) }
            }
            _detail.value = d
            if (d != null) {
                val readable = d.props.filter { it.readable }
                if (readable.isNotEmpty()) {
                    val values = withContext(Dispatchers.IO) {
                        repo.readProps(device.did, readable)
                    }
                    _propValues.value = values

                    // 同步刷新列表层的电源状态（若包含开关属性）
                    val spec = repo.spec(device)
                    val onProp = repo.findOnMethod(spec, device.did)
                    if (onProp != null && values.containsKey(onProp.name)) {
                        val state = values[onProp.name] as? Boolean
                        if (state != null) {
                            _powers.value = _powers.value + (device.did to state)
                        }
                    }
                }
            } else {
                _toast.value = "该设备无公开的功能规格，无法控制"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _toast.value = repo.describe(e)
        }
    }

    fun refreshProps() {
        val device = _currentDevice.value ?: return
        val detail = _detail.value ?: return
        viewModelScope.launch {
            val readable = detail.props.filter { it.readable }
            if (readable.isEmpty()) return@launch
            try {
                val values = withContext(Dispatchers.IO) { repo.readProps(device.did, readable) }
                _propValues.value = values

                val spec = repo.spec(device)
                val onProp = repo.findOnMethod(spec, device.did)
                if (onProp != null && values.containsKey(onProp.name)) {
                    val state = values[onProp.name] as? Boolean
                    if (state != null) {
                        _powers.value = _powers.value + (device.did to state)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toast.value = repo.describe(e)
            }
        }
    }

    /**
     * 写属性：先乐观更新本地值，失败回滚。
     *
     * 成功后延迟回读一次真实状态——设备常有联动副作用（如台灯改亮度会自动开灯），
     * 乐观值只反映「我设的目标」，不反映设备实际状态。
     */
    fun setProp(prop: PropDef, value: Any?) = viewModelScope.launch {
        val device = _currentDevice.value ?: return@launch
        val old = _propValues.value[prop.name]
        _propValues.value = _propValues.value + (prop.name to value)
        try {
            withContext(Dispatchers.IO) { repo.setProp(device.did, prop, value) }
            delay(SETTLE_DELAY_MS)
            refreshProps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _propValues.value = _propValues.value + (prop.name to old)
            _toast.value = repo.describe(e)
        }
    }

    fun runAction(actionName: String) = viewModelScope.launch {
        val device = _currentDevice.value ?: return@launch
        val detail = _detail.value ?: return@launch
        val action = detail.actions.firstOrNull { it.name == actionName } ?: return@launch
        try {
            withContext(Dispatchers.IO) { repo.runAction(device.did, action) }
            _toast.value = "已执行「${action.desc.ifEmpty { action.name }}」"
            // 动作同样可能引发状态联动（开关灯、调模式等），执行后回读
            delay(SETTLE_DELAY_MS)
            refreshProps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _toast.value = repo.describe(e)
        }
    }

    // ---------- 摄像头实时视频 ----------

    private val _streamUrl = MutableStateFlow<String?>(null)
    val streamUrl: StateFlow<String?> = _streamUrl

    private val _streamLoading = MutableStateFlow(false)
    val streamLoading: StateFlow<Boolean> = _streamLoading

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError

    /** 当前拉流会话使用的设备与 stop 动作，离开页面时用它收尾。 */
    private var streamSession: Pair<String, ActionDef?>? = null

    /**
     * 拉起摄像头实时视频：调 spec 里的 `start-rtsp-stream`（首选，ExoPlayer 原生支持）
     * 或 `start-hls-stream`，把返回的流地址交给播放器。
     */
    fun startCameraStream(did: String) = viewModelScope.launch {
        if (_streamLoading.value || _streamUrl.value != null) return@launch
        _streamLoading.value = true
        _streamError.value = null
        try {
            val device = _devices.value.firstOrNull { it.did == did }
                ?: throw MiApiException(-1, "未找到设备")
            val cached = if (_currentDevice.value?.did == did) _detail.value else null
            val detail = cached
                ?: withContext(Dispatchers.IO) { repo.deviceDetail(device) }
                ?: throw MiApiException(-1, "该设备没有公开规格")

            val start = detail.actions.firstOrNull { it.name == "start-rtsp-stream" }
                ?: detail.actions.firstOrNull { it.name == "start-hls-stream" }
                ?: throw MiApiException(-1, "该摄像头不支持标准视频流")
            val stop = detail.actions.firstOrNull {
                it.name == "stop-stream" && it.serviceType == start.serviceType
            }

            // in 按 inPis 位置顺序取值：入参多为分辨率/编码枚举，挑设备支持的最优项
            val inValues = start.inPis.map { piid ->
                pickActionInput(detail.props.firstOrNull { it.siid == start.siid && it.piid == piid })
            }
            val out = repo.runActionOut(did, start, inValues)

            // out 按 outPis 位置顺序取值，里面应有 stream-address
            val url = (0 until out.length())
                .mapNotNull { out.opt(it) as? String }
                .firstOrNull { it.startsWith("rtsp://") || it.startsWith("http") }
            if (url.isNullOrEmpty()) {
                throw MiApiException(-1, "摄像头未返回视频流地址")
            }
            streamSession = did to stop
            _streamUrl.value = url
            android.util.Log.d("MiHomeWear", "拉流成功 action=${start.name} siid=${start.siid} aiid=${start.aiid} url=$url")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _streamError.value = repo.describe(e)
            android.util.Log.e("MiHomeWear", "拉流失败", e)
        } finally {
            _streamLoading.value = false
        }
    }

    /** 关闭视频页：释放播放地址并通知摄像头停止推流。 */
    fun stopCameraStream() {
        val session = streamSession
        streamSession = null
        _streamUrl.value = null
        _streamError.value = null
        _streamLoading.value = false
        val (did, stop) = session ?: return
        if (stop == null) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.runAction(did, stop) } }
        }
    }

    /** 拉流入参取值：优先 720p，其次 1080p，再退化到设备支持的第一项。 */
    private fun pickActionInput(prop: PropDef?): Any? {
        val items = prop?.valueList
        if (items.isNullOrEmpty()) return 0
        return (items.firstOrNull { it.description.contains("720") }
            ?: items.firstOrNull { it.description.contains("1080") }
            ?: items.first()).value
    }

    /** 列表页的开关：同步更新本地电源态。 */
    fun togglePower(device: DeviceInfo, state: Boolean) = viewModelScope.launch {
        val old = _powers.value[device.did]
        _powers.value = _powers.value + (device.did to state)
        try {
            withContext(Dispatchers.IO) { repo.setPower(device, state) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _powers.value = _powers.value + (device.did to old)
            _toast.value = repo.describe(e)
        }
    }

    // ---------- 场景 ----------

    fun runScene(scene: SceneInfo) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { repo.runScene(scene) }
            _toast.value = "已执行「${scene.name}」"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _toast.value = repo.describe(e)
        }
    }

    // ---------- 小爱 ----------

    fun cycleSpeaker() {
        val list = _speakers.value
        if (list.size <= 1) return
        val idx = list.indexOfFirst { it.did == _activeSpeaker.value }
        _activeSpeaker.value = list[(idx + 1) % list.size].did
    }

    fun sendVoice(text: String) = viewModelScope.launch {
        val list = _speakers.value
        val speaker = repo.pickSpeaker(list, _activeSpeaker.value)
        if (speaker == null) {
            _toast.value = "没有在线的小爱音箱"
            return@launch
        }
        try {
            withContext(Dispatchers.IO) { repo.sendTextDirective(speaker, text) }
            _toast.value = "已发送给「${speaker.name}」"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _toast.value = repo.describe(e)
        }
    }
}
