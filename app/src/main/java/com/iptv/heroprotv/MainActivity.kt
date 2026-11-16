package com.iptv.heroprotv

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.heroprotv.data.HeroListMeta
import com.iptv.heroprotv.data.IptvRepository
import com.iptv.heroprotv.data.StorageManager
import com.iptv.heroprotv.databinding.ActivityMainBinding
import com.iptv.heroprotv.model.*
import com.iptv.heroprotv.parser.PlaylistParser
import com.iptv.heroprotv.ui.ChannelAdapter
import com.iptv.heroprotv.ui.EpisodeAdapter
import com.iptv.heroprotv.ui.ProfileAdapter
import com.iptv.heroprotv.ui.SeasonAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: StorageManager
    private lateinit var repository: IptvRepository

    private var exoPlayer: ExoPlayer? = null
    private var profiles: MutableList<UserProfile> = mutableListOf()
    private var activeProfileId: String? = null

    private var currentPlaylist: PlaylistData? = null
    private var currentContent: ContentType = ContentType.LIVE
    private var currentCategory: String = ""
    private var currentSearchQuery: String = ""
    private var isVideoFullscreen: Boolean = false

    private val seriesInfoCache = mutableMapOf<String, Map<String, List<EpisodeItem>>>()
    private var currentSeriesId: String? = null
    private var currentSeriesName: String = ""
    private var currentSeasonNum: String? = null

    private var bulkJob: Job? = null
    private var lastOkPressTime: Long = 0L

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var profileAdapter: ProfileAdapter
    private lateinit var seasonAdapter: SeasonAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private val importBulkTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleBulkTxtUri(uri)
        }
    }

    private val exportProfilesTxtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            writeProfilesToUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()

        storage = StorageManager(this)
        repository = IptvRepository(storage)

        setupRecyclerViews()
        setupPlayer()
        setupListeners()
        setupBackNavigation()

        initAppState()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupRecyclerViews() {
        channelAdapter = ChannelAdapter { item, _ ->
            if (item.type == ContentType.SERIES) {
                openSeasonsPanel(item)
            } else {
                playChannelItem(item)
            }
        }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter

        profileAdapter = ProfileAdapter(
            onActivate = { profile -> activateProfile(profile.id) },
            onDelete = { profile -> deleteProfile(profile.id) }
        )
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = profileAdapter

        seasonAdapter = SeasonAdapter { seasonNum ->
            selectSeason(seasonNum)
        }
        binding.seasonsList.layoutManager = LinearLayoutManager(this)
        binding.seasonsList.adapter = seasonAdapter

        episodeAdapter = EpisodeAdapter { ep ->
            val season = currentSeasonNum ?: "1"
            playEpisode(ep, season)
        }
        binding.episodesList.layoutManager = LinearLayoutManager(this)
        binding.episodesList.adapter = episodeAdapter
    }

    private fun setupPlayer() {
        val dataSourceFactory = OkHttpDataSource.Factory(repository.okHttpClient)
            .setUserAgent("IPTVHeroProTV/1.0 (Android)")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                2_500,
                5_000
            )
            .build()

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                binding.playerView.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                binding.statusOverlay.visibility = View.VISIBLE
                                binding.statusOverlay.text = "Carregando buffer: ${binding.currentChannel.text}"
                            }
                            Player.STATE_READY -> {
                                if (player.playWhenReady) {
                                    binding.statusOverlay.text = "Reproduzindo: ${binding.currentChannel.text}"
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            }
                            Player.STATE_ENDED -> {
                                binding.statusOverlay.text = "Reprodução finalizada."
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                            Player.STATE_IDLE -> {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.statusOverlay.visibility = View.VISIBLE
                        binding.statusOverlay.text = "❌ Erro na reprodução: ${error.errorCodeName}"
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                })
            }
    }

    private fun setupListeners() {
        binding.btnHeaderFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnBottomFullscreen.setOnClickListener { toggleFullscreen() }

        binding.proxyIndicator.setOnClickListener { switchTopTab("proxy") }

        binding.userMenuBtn.setOnClickListener { toggleUserMenu() }
        binding.userMenuBackdrop.setOnClickListener { closeProfileMenu() }

        binding.btnMenuHero.setOnClickListener {
            closeProfileMenu()
            loadHeroList()
        }
        binding.btnMenuExport.setOnClickListener {
            exportProfilesTxt()
        }
        binding.btnMenuBulk.setOnClickListener {
            importBulkTxtLauncher.launch(arrayOf("text/plain", "*/*"))
        }
        binding.btnMenuClear.setOnClickListener {
            deleteAllProfiles()
        }

        binding.tabBtnHero.setOnClickListener { switchTopTab("hero") }
        binding.tabBtnXtream.setOnClickListener { switchTopTab("xtream") }
        binding.tabBtnProxy.setOnClickListener { switchTopTab("proxy") }

        binding.btnLoadHero.setOnClickListener { loadHeroList() }
        binding.btnCheckHero.setOnClickListener { checkHeroListUpdate() }

        binding.btnConnectXtream.setOnClickListener { loadXtreamFromInputs() }

        binding.btnSaveProxy.setOnClickListener { saveProxy() }
        binding.btnTestProxy.setOnClickListener { testProxy() }
        binding.btnClearProxy.setOnClickListener { clearProxy() }

        binding.contentTabLive.setOnClickListener { switchContent(ContentType.LIVE) }
        binding.contentTabVod.setOnClickListener { switchContent(ContentType.VOD) }
        binding.contentTabSeries.setOnClickListener { switchContent(ContentType.SERIES) }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility =
                    if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                renderContentList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.searchInput.setText("")
            currentSearchQuery = ""
            binding.btnClearSearch.visibility = View.GONE
            renderContentList()
        }

        binding.categoryFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = parent?.getItemAtPosition(position)?.toString() ?: ""
                currentCategory = if (selected == "Todas as Categorias" || selected == "Todas") "" else selected
                renderContentList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.seasonsBackBtn.setOnClickListener { closeSeasonsPanel() }

        binding.bulkCancelBtn.setOnClickListener {
            bulkJob?.cancel()
            appendBulkLog("⏹️ Cancelado.")
        }
        binding.bulkCloseBtn.setOnClickListener {
            binding.bulkModal.visibility = View.GONE
            binding.bulkLog.text = ""
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.seasonsPanel.visibility == View.VISIBLE -> closeSeasonsPanel()
                    isVideoFullscreen -> exitFullscreen()
                    binding.bulkModal.visibility == View.VISIBLE -> {
                        if (binding.bulkCloseBtn.visibility == View.VISIBLE) {
                            binding.bulkModal.visibility = View.GONE
                        } else {
                            bulkJob?.cancel()
                        }
                    }
                    binding.userMenuBackdrop.visibility == View.VISIBLE -> closeProfileMenu()
                    else -> {
                        if (channelAdapter.itemCount > 0) {
                            binding.channelList.requestFocus()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_F -> {
                    if (currentFocus !is android.widget.EditText) {
                        toggleFullscreen()
                        return true
                    }
                }
                KeyEvent.KEYCODE_PROG_RED,
                KeyEvent.KEYCODE_PROG_GREEN,
                KeyEvent.KEYCODE_PROG_YELLOW,
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    toggleFullscreen()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    exoPlayer?.let { p ->
                        if (p.isPlaying) p.pause() else p.play()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (isVideoFullscreen) {
                        val now = System.currentTimeMillis()
                        if (now - lastOkPressTime < 350L) {
                            exitFullscreen()
                            lastOkPressTime = 0L
                            return true
                        }
                        lastOkPressTime = now
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun initAppState() {
        val savedProxy = storage.getProxyUrl()
        binding.proxyUrl.setText(savedProxy)
        updateProxyIndicator()

        binding.statusOverlay.text = "Carregando perfis..."
        profiles = storage.loadProfiles()
        activeProfileId = storage.getActiveProfileId()

        renderProfileList()
        updateCurrentProfileUI()
        populateCategories()
        binding.statusOverlay.text = "Pronto."
        binding.btnLoadHero.requestFocus()

        lifecycleScope.launch {
            delay(5000)
            val prev = storage.getHeroListMeta() ?: return@launch
            try {
                val text = repository.fetchRawText(repository.heroUrlWithBust())
                val hash = PlaylistParser.sha256Hex(text)
                if (hash != prev.hash) {
                    binding.heroCounter.text = "🆕 Hero Lista desatualizada — clique em CARREGAR"
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun switchTopTab(tab: String) {
        val yellow = ContextCompat.getColor(this, R.color.marvel_yellow)
        val dim = ContextCompat.getColor(this, R.color.text_dim)
        val transparent = android.graphics.Color.TRANSPARENT

        binding.tabTextHero.setTextColor(if (tab == "hero") yellow else dim)
        binding.tabIndicatorHero.setBackgroundColor(if (tab == "hero") yellow else transparent)
        binding.panelHero.visibility = if (tab == "hero") View.VISIBLE else View.GONE

        binding.tabTextXtream.setTextColor(if (tab == "xtream") yellow else dim)
        binding.tabIndicatorXtream.setBackgroundColor(if (tab == "xtream") yellow else transparent)
        binding.panelXtream.visibility = if (tab == "xtream") View.VISIBLE else View.GONE

        binding.tabTextProxy.setTextColor(if (tab == "proxy") yellow else dim)
        binding.tabIndicatorProxy.setBackgroundColor(if (tab == "proxy") yellow else transparent)
        binding.panelProxy.visibility = if (tab == "proxy") View.VISIBLE else View.GONE
    }

    private fun switchContent(type: ContentType) {
        currentContent = type
        val yellow = ContextCompat.getColor(this, R.color.marvel_yellow)
        val paper = ContextCompat.getColor(this, R.color.paper)
        val transparent = android.graphics.Color.TRANSPARENT

        binding.contentTextLive.setTextColor(if (type == ContentType.LIVE) yellow else paper)
        binding.contentIndLive.setBackgroundColor(if (type == ContentType.LIVE) yellow else transparent)

        binding.contentTextVod.setTextColor(if (type == ContentType.VOD) yellow else paper)
        binding.contentIndVod.setBackgroundColor(if (type == ContentType.VOD) yellow else transparent)

        binding.contentTextSeries.setTextColor(if (type == ContentType.SERIES) yellow else paper)
        binding.contentIndSeries.setBackgroundColor(if (type == ContentType.SERIES) yellow else transparent)

        binding.searchInput.hint = when (type) {
            ContentType.LIVE -> "Buscar canais ao vivo..."
            ContentType.VOD -> "Buscar filmes..."
            ContentType.SERIES -> "Buscar séries..."
            else -> "Buscar..."
        }

        populateCategories()
        renderContentList()
    }

    private fun toggleFullscreen() {
        if (isVideoFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isVideoFullscreen = true
        binding.headerBar.visibility = View.GONE
        binding.topTabsBar.visibility = View.GONE
        binding.dividerTopTabs.visibility = View.GONE
        binding.tabPanelsContainer.visibility = View.GONE
        binding.dividerPanels.visibility = View.GONE
        binding.sidebar.visibility = View.GONE
        binding.sidebarBorder.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.statusOverlay.visibility = View.GONE
        binding.videoArea.setPadding(0, 0, 0, 0)
        binding.videoContainer.setPadding(0, 0, 0, 0)

        binding.exitFsHint.visibility = View.VISIBLE
        lifecycleScope.launch {
            delay(3000)
            binding.exitFsHint.visibility = View.GONE
        }
    }

    private fun exitFullscreen() {
        isVideoFullscreen = false
        binding.headerBar.visibility = View.VISIBLE
        binding.topTabsBar.visibility = View.VISIBLE
        binding.dividerTopTabs.visibility = View.VISIBLE
        binding.tabPanelsContainer.visibility = View.VISIBLE
        binding.dividerPanels.visibility = View.VISIBLE
        binding.sidebar.visibility = View.VISIBLE
        binding.sidebarBorder.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        binding.statusOverlay.visibility = View.VISIBLE
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp6 = (6 * resources.displayMetrics.density).toInt()
        binding.videoArea.setPadding(dp8, dp8, dp8, dp8)
        binding.videoContainer.setPadding(dp6, dp6, dp6, dp6)
        binding.exitFsHint.visibility = View.GONE
    }

    private fun updateProxyIndicator() {
        val current = storage.getProxyUrl()
        if (current.isNotEmpty()) {
            binding.proxyIndicator.text = "PROXY: ON"
            binding.proxyIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.green_valid))
        } else {
            binding.proxyIndicator.text = "PROXY: OFF"
            binding.proxyIndicator.setBackgroundResource(R.drawable.bg_danger_button)
        }
    }

    private fun saveProxy() {
        val raw = binding.proxyUrl.text.toString().trim()
        val normalized = storage.setProxyUrl(raw)
        if (raw.isNotEmpty() && normalized.isEmpty()) {
            showAlert("Aviso", "URL inválida. Use http(s)://.../")
            return
        }
        binding.proxyUrl.setText(normalized)
        updateProxyIndicator()
        binding.statusOverlay.text = if (normalized.isNotEmpty()) "Proxy salvo." else "Proxy removido."
    }

    private fun clearProxy() {
        storage.clearProxyUrl()
        binding.proxyUrl.setText("")
        updateProxyIndicator()
        binding.statusOverlay.text = "Proxy removido."
    }

    private fun testProxy() {
        val raw = binding.proxyUrl.text.toString().trim()
        if (raw.isEmpty()) {
            showAlert("Aviso", "Insira URL.")
            return
        }
        binding.statusOverlay.text = "Testando proxy..."
        lifecycleScope.launch {
            try {
                val origin = repository.testProxy(raw)
                binding.statusOverlay.text = "✅ Proxy OK! IP: $origin"
            } catch (e: Exception) {
                binding.statusOverlay.text = "❌ Falha: ${e.message}"
            }
        }
    }

    private fun toggleUserMenu() {
        if (binding.userMenuBackdrop.visibility == View.VISIBLE) {
            closeProfileMenu()
        } else {
            renderProfileList()
            binding.userMenuBackdrop.visibility = View.VISIBLE
        }
    }

    private fun closeProfileMenu() {
        binding.userMenuBackdrop.visibility = View.GONE
    }

    private fun renderProfileList() {
        binding.profileCount.text = "${profiles.size} perfil${if (profiles.size != 1) "s" else ""}"
        binding.emptyProfilesText.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        profileAdapter.submitList(profiles, activeProfileId)
    }

    private fun updateCurrentProfileUI() {
        val active = profiles.find { it.id == activeProfileId }
        binding.currentProfileName.text = active?.hero ?: "Nenhum perfil"
        binding.currentAvatar.text = if (active == null) "👤" else if (active.universe == "marvel") "🦸" else "🦇"
        binding.activeConns.text = "${active?.activeConnections ?: 0} CONEXÕES"
    }

    private fun activateProfile(id: String) {
        val profile = profiles.find { it.id == id } ?: return
        activeProfileId = id
        profile.lastUsed = System.currentTimeMillis()
        storage.saveProfiles(profiles, activeProfileId)
        closeProfileMenu()
        updateCurrentProfileUI()

        if (profile.sourceType == "xtream" && !profile.server.isNullOrBlank() && !profile.user.isNullOrBlank() && !profile.pass.isNullOrBlank()) {
            binding.statusOverlay.text = "Reconectando ${profile.hero}..."
            lifecycleScope.launch {
                try {
                    executeXtreamConnect(profile.server, profile.user, profile.pass, profile.id)
                } catch (e: Exception) {
                    binding.statusOverlay.text = "Erro: ${e.message}"
                }
            }
        } else if (!profile.server.isNullOrBlank()) {
            binding.statusOverlay.text = "Carregando lista ${profile.hero}..."
            lifecycleScope.launch {
                try {
                    val pl = repository.loadM3uOrTxtPlaylist(profile.server)
                    currentPlaylist = pl
                    switchContent(ContentType.LIVE)
                    binding.statusOverlay.text = "Perfil ${profile.hero} ativado."
                } catch (e: Exception) {
                    binding.statusOverlay.text = "Erro: ${e.message}"
                }
            }
        } else {
            binding.statusOverlay.text = "Perfil ${profile.hero} ativado."
        }
    }

    private fun deleteProfile(id: String) {
        showConfirm("Confirmação", "Remover este perfil?") {
            profiles.removeAll { it.id == id }
            if (activeProfileId == id) {
                activeProfileId = profiles.firstOrNull()?.id
            }
            storage.saveProfiles(profiles, activeProfileId)
            renderProfileList()
            updateCurrentProfileUI()
        }
    }

    private fun deleteAllProfiles() {
        showConfirm("Confirmação", "Remover TODOS os ${profiles.size} perfis?") {
            profiles.clear()
            activeProfileId = null
            storage.saveProfiles(profiles, activeProfileId)
            renderProfileList()
            updateCurrentProfileUI()
        }
    }

    private fun exportProfilesTxt() {
        if (profiles.isEmpty()) {
            showAlert("Aviso", "Sem perfis.")
            return
        }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        exportProfilesTxtLauncher.launch("iptv_hero_profiles_$dateStr.txt")
    }

    private fun writeProfilesToUri(uri: Uri) {
        try {
            val lines = mutableListOf("# IPTV Hero Pro Profiles")
            profiles.forEach { p ->
                lines.add(
                    listOf(
                        p.sourceType,
                        p.sourceLabel,
                        p.server ?: "",
                        p.user ?: "",
                        p.pass ?: "",
                        p.validity ?: "",
                        p.maxConnections?.toString() ?: "",
                        p.hero,
                        p.contentCounts.live.toString(),
                        p.contentCounts.vod.toString(),
                        p.contentCounts.series.toString()
                    ).joinToString("|")
                )
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                    writer.write(lines.joinToString("\n"))
                }
            }
            binding.statusOverlay.text = "✅ Perfis exportados com sucesso!"
        } catch (e: Exception) {
            showAlert("Erro", "Falha ao exportar: ${e.message}")
        }
    }

    private fun loadHeroList() {
        binding.heroCounter.text = "Verificando atualizações..."
        lifecycleScope.launch {
            val text = try {
                repository.fetchRawText(repository.heroUrlWithBust())
            } catch (e: Exception) {
                binding.heroCounter.text = ""
                showAlert("Erro", "Falha ao baixar Hero Lista: ${e.message}")
                return@launch
            }

            val hash = PlaylistParser.sha256Hex(text)
            val prev = storage.getHeroListMeta()
            val isFirstEver = prev == null
            val isNew = prev == null || prev.hash != hash

            val urls = PlaylistParser.parseHeroUrls(text)
            if (urls.isEmpty()) {
                binding.heroCounter.text = ""
                showAlert("Aviso", "Nenhuma lista válida na Hero Lista.")
                return@launch
            }

            if (!isNew && prev != null) {
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(prev.checkedAt))
                binding.heroCounter.text = "✅ Sem alterações ($dateStr)"
                showConfirm(
                    "📡 HERO LISTA",
                    "A lista não mudou desde $dateStr.\n\nDeseja reprocessar mesmo assim?"
                ) {
                    startBulkImport(urls, hash)
                }
            } else {
                binding.heroCounter.text = "${urls.size} listas${if (!isFirstEver) " 🆕" else ""}"
                val prompt = if (isFirstEver) {
                    "Processar ${urls.size} listas da Hero Lista? (primeira vez)"
                } else {
                    "Processar ${urls.size} listas? (atualizada)"
                }
                showConfirm("Confirmação", prompt) {
                    startBulkImport(urls, hash)
                }
            }
        }
    }

    private fun checkHeroListUpdate() {
        binding.heroCounter.text = "Verificando..."
        lifecycleScope.launch {
            try {
                val text = repository.fetchRawText(repository.heroUrlWithBust())
                val hash = PlaylistParser.sha256Hex(text)
                val prev = storage.getHeroListMeta()
                val urls = PlaylistParser.parseHeroUrls(text)
                if (prev == null) {
                    binding.heroCounter.text = "📡 Sem versão anterior. ${urls.size} listas disponíveis."
                } else if (hash == prev.hash) {
                    val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(prev.checkedAt))
                    binding.heroCounter.text = "✅ Atualizada ($dateStr)"
                } else {
                    binding.heroCounter.text = "🆕 Nova versão disponível! (${urls.size} listas)"
                }
            } catch (e: Exception) {
                binding.heroCounter.text = "❌ ${e.message}"
            }
        }
    }

    private fun handleBulkTxtUri(uri: Uri) {
        try {
            val lines = mutableListOf<String>()
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { seq ->
                    seq.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .take(100_000)
                        .forEach { lines.add(it) }
                }
            }
            if (lines.isEmpty()) {
                showAlert("Aviso", "Arquivo vazio.")
                return
            }
            showConfirm("Confirmação", "Processar ${lines.size} listas?") {
                startBulkImport(lines, null)
            }
        } catch (e: Exception) {
            showAlert("Erro", "Falha ao ler arquivo: ${e.message}")
        }
    }

    private fun startBulkImport(lists: List<String>, heroHash: String?) {
        if (heroHash != null) {
            storage.setHeroListMeta(HeroListMeta(heroHash, System.currentTimeMillis(), lists.size))
        }
        binding.bulkModal.visibility = View.VISIBLE
        binding.bulkCancelBtn.visibility = View.VISIBLE
        binding.bulkCloseBtn.visibility = View.GONE
        binding.bulkLog.text = ""
        binding.bulkProgress.progress = 0

        bulkJob?.cancel()
        bulkJob = lifecycleScope.launch {
            var success = 0
            var failed = 0
            val total = lists.size
            val concurrency = 5

            for (i in lists.indices step concurrency) {
                if (!isActive) break
                val chunk = lists.subList(i, minOf(i + concurrency, total))
                binding.bulkModalMsg.text = "${i + 1}/$total: ${lists[i].take(55)}..."
                binding.bulkProgress.progress = (((i + 1).toFloat() / total) * 100).toInt()

                val results = chunk.mapIndexed { offset, line ->
                    async {
                        val idx = i + offset + 1
                        val res = repository.processOneBulkLine(line) { newProfile ->
                            synchronized(profiles) {
                                profiles.add(newProfile)
                                activeProfileId = newProfile.id
                            }
                        }
                        Pair(idx, res)
                    }
                }.awaitAll()

                for ((idx, r) in results) {
                    if (r.ok) {
                        success++
                        appendBulkLog("✅ [$idx] ${r.hero} (${r.type}) - ${r.count}")
                    } else {
                        failed++
                        appendBulkLog("⚠️ [$idx] ${r.error}")
                    }
                }
                storage.saveProfiles(profiles, activeProfileId)
            }

            renderProfileList()
            updateCurrentProfileUI()
            binding.bulkProgress.progress = 100
            binding.bulkModalMsg.text = "Concluído! ✅ $success | ❌ $failed"
            binding.bulkCancelBtn.visibility = View.GONE
            binding.bulkCloseBtn.visibility = View.VISIBLE
            binding.bulkCloseBtn.text = "FECHAR ($success CRIADOS)"
            binding.bulkCloseBtn.requestFocus()
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR")).format(Date())
            binding.heroCounter.text = "✅ Concluído ($timeStr)"
        }
    }

    private fun appendBulkLog(line: String) {
        val current = binding.bulkLog.text.toString()
        val updated = if (current.isEmpty()) line else "$current\n$line"
        val trimmed = updated.lines().takeLast(120).joinToString("\n")
        binding.bulkLog.text = trimmed
        binding.bulkLogScroll.post {
            binding.bulkLogScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun loadXtreamFromInputs() {
        val server = binding.xcServer.text.toString().trim().removeSuffix("/")
        val user = binding.xcUser.text.toString().trim()
        val pass = binding.xcPass.text.toString().trim()

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showAlert("Aviso", "Preencha servidor, usuário e senha.")
            return
        }

        lifecycleScope.launch {
            try {
                executeXtreamConnect(server, user, pass, null)
            } catch (e: Exception) {
                binding.statusOverlay.text = "Erro: ${e.message}"
                showAlert("Falha", e.message ?: "Erro desconhecido")
            }
        }
    }

    private suspend fun executeXtreamConnect(
        server: String,
        user: String,
        pass: String,
        existingProfileId: String?
    ) {
        binding.statusOverlay.text = "Autenticando..."
        val result = repository.loadXtreamFull(server, user, pass)
        currentPlaylist = result.playlist
        seriesInfoCache.clear()

        val counts = ContentCounts(
            live = result.playlist.live.size,
            vod = result.playlist.vod.size,
            series = result.playlist.series.size
        )

        if (existingProfileId != null) {
            val existing = profiles.find { it.id == existingProfileId }
            if (existing != null) {
                val updated = existing.copy(
                    validity = result.validity,
                    maxConnections = result.maxConnections,
                    activeConnections = result.activeConnections,
                    contentCounts = counts,
                    lastUsed = System.currentTimeMillis()
                )
                val index = profiles.indexOf(existing)
                if (index >= 0) profiles[index] = updated
            }
        } else {
            val newProfile = repository.buildNewProfile(
                sourceType = "xtream",
                sourceLabel = server,
                server = server,
                user = user,
                pass = pass,
                validity = result.validity,
                connections = result.maxConnections,
                contentCounts = counts,
                bulkOrigin = false
            ).copy(activeConnections = result.activeConnections)
            profiles.add(newProfile)
            activeProfileId = newProfile.id
        }

        storage.saveProfiles(profiles, activeProfileId)
        renderProfileList()
        updateCurrentProfileUI()

        binding.statusOverlay.text = "Xtream | Live:${counts.live} VOD:${counts.vod} Séries:${counts.series}"
        switchContent(ContentType.LIVE)
    }

    private fun populateCategories() {
        val items = when (currentContent) {
            ContentType.LIVE -> currentPlaylist?.live ?: emptyList()
            ContentType.VOD -> currentPlaylist?.vod ?: emptyList()
            ContentType.SERIES -> currentPlaylist?.series ?: emptyList()
            else -> emptyList()
        }
        val categories = mutableListOf("Todas as Categorias")
        categories.addAll(items.map { it.group }.distinct().sorted())

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.categoryFilter.adapter = adapter
        currentCategory = ""
    }

    private fun renderContentList() {
        val pl = currentPlaylist ?: run {
            channelAdapter.submitList(emptyList())
            binding.emptyListText.visibility = View.VISIBLE
            binding.emptyListText.text = "Carregue uma lista para começar"
            return
        }

        val baseList = when (currentContent) {
            ContentType.LIVE -> pl.live
            ContentType.VOD -> pl.vod
            ContentType.SERIES -> pl.series
            else -> emptyList()
        }

        val queryLower = currentSearchQuery.lowercase(Locale.getDefault())
        val filtered = baseList.filter { item ->
            val matchesCat = currentCategory.isEmpty() || item.group == currentCategory
            val matchesSearch = queryLower.isEmpty() ||
                item.name.lowercase(Locale.getDefault()).contains(queryLower) ||
                item.group.lowercase(Locale.getDefault()).contains(queryLower)
            matchesCat && matchesSearch
        }

        channelAdapter.submitList(filtered)
        if (filtered.isEmpty()) {
            binding.emptyListText.visibility = View.VISIBLE
            binding.emptyListText.text = if (queryLower.isNotEmpty()) {
                "Nenhum resultado para \"$currentSearchQuery\"."
            } else {
                "Nenhum item."
            }
        } else {
            binding.emptyListText.visibility = View.GONE
        }
    }

    private fun openSeasonsPanel(seriesItem: ChannelItem) {
        val sId = seriesItem.id ?: return
        val pl = currentPlaylist
        if (pl == null || pl.sourceType != "xtream" || pl.server == null || pl.user == null || pl.pass == null) {
            showAlert("Aviso", "Séries só funcionam com perfil Xtream")
            return
        }

        currentSeriesId = sId
        currentSeriesName = seriesItem.name
        currentSeasonNum = null

        binding.seasonsPanel.visibility = View.VISIBLE
        binding.seasonsTitle.text = seriesItem.name
        binding.episodesColTitle.text = "EPISÓDIOS"
        binding.seasonsStatusText.visibility = View.VISIBLE
        binding.seasonsStatusText.text = "Carregando temporadas..."
        seasonAdapter.submitSeasons(emptyMap(), null)
        episodeAdapter.submitEpisodes(emptyList(), "1")

        lifecycleScope.launch {
            try {
                val cached = seriesInfoCache[sId]
                val bySeason = cached ?: repository.loadSeriesInfo(pl.server, pl.user, pl.pass, sId).also {
                    seriesInfoCache[sId] = it
                }

                if (bySeason.isEmpty()) {
                    binding.seasonsStatusText.text = "Nenhuma temporada encontrada"
                    return@launch
                }

                binding.seasonsStatusText.visibility = View.GONE
                val firstSeason = bySeason.keys.first()
                seasonAdapter.submitSeasons(bySeason, firstSeason)
                selectSeason(firstSeason)
                binding.seasonsList.post {
                    binding.seasonsList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            } catch (e: Exception) {
                binding.seasonsStatusText.visibility = View.VISIBLE
                binding.seasonsStatusText.text = "Erro: ${e.message}"
            }
        }
    }

    private fun closeSeasonsPanel() {
        binding.seasonsPanel.visibility = View.GONE
        binding.channelList.requestFocus()
    }

    private fun selectSeason(seasonNum: String) {
        currentSeasonNum = seasonNum
        seasonAdapter.setSelectedSeason(seasonNum)
        val eps = seriesInfoCache[currentSeriesId]?.get(seasonNum) ?: emptyList()
        binding.episodesColTitle.text = "EPISÓDIOS — TEMPORADA $seasonNum (${eps.size})"
        episodeAdapter.submitEpisodes(eps, seasonNum)
        if (eps.isEmpty()) {
            binding.seasonsStatusText.visibility = View.VISIBLE
            binding.seasonsStatusText.text = "Nenhum episódio nesta temporada"
        } else {
            binding.seasonsStatusText.visibility = View.GONE
        }
    }

    private fun playEpisode(ep: EpisodeItem, seasonNum: String) {
        val pl = currentPlaylist ?: return
        val server = pl.server ?: return
        val user = URLEncoder.encode(pl.user ?: "", "UTF-8")
        val pass = URLEncoder.encode(pl.pass ?: "", "UTF-8")
        val ext = ep.containerExtension.ifBlank { "mp4" }
        val url = "$server/series/$user/$pass/${ep.id}.$ext"

        val epTitle = "$currentSeriesName — T${seasonNum}E${ep.episodeNum.padStart(2, '0')} — ${ep.title}"
        val episodeChannel = ChannelItem(
            name = epTitle,
            group = "Série",
            url = url,
            type = ContentType.SERIES_EPISODE,
            id = ep.id
        )
        closeSeasonsPanel()
        playChannelItem(episodeChannel)
    }

    private fun playChannelItem(item: ChannelItem) {
        val rawUrl = item.url
        if (rawUrl.isNullOrBlank()) {
            binding.statusOverlay.text = "URL indisponível"
            return
        }

        binding.currentChannel.text = item.name
        binding.statusOverlay.visibility = View.VISIBLE
        binding.statusOverlay.text = "Carregando: ${item.name}"

        val finalUrl = storage.applyProxy(rawUrl)

        val active = profiles.find { it.id == activeProfileId }
        if (active != null) {
            val max = active.maxConnections ?: 99
            active.activeConnections = minOf(max, active.activeConnections + 1)
            storage.saveProfiles(profiles, activeProfileId)
            updateCurrentProfileUI()
            renderProfileList()
        }

        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            val mediaItem = MediaItem.fromUri(Uri.parse(finalUrl))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showConfirm(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onConfirm() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        bulkJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
