package com.example.railwaymanager

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var libDir: File
    private lateinit var binDir: File
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var tvTerminal: TextView
    private lateinit var svTerminal: ScrollView
    private lateinit var etInput: EditText

    private var activeProcess: Process? = null
    private var processOutputStream: OutputStream? = null

    private var selectedZipFile: File? = null
    private val httpClient = OkHttpClient()
    private val graphqlUrl = "https://backboard.railway.com/graphql/v2"

    private val workspaceDir: File
        get() = File(filesDir, "deploy_workspace").apply { mkdirs() }

    private val selectZipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val tempFile = File(cacheDir, "project_upload.zip")
            contentResolver.openInputStream(it)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            selectedZipFile = tempFile
            findViewById<TextView>(R.id.tvFileStatus).text = "File siap: ${it.lastPathSegment}"
            findViewById<Button>(R.id.btnDeploy).isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestStoragePermissions()
        setupEnvironmentBinaries()

        drawerLayout = findViewById(R.id.drawerLayout)
        bottomNav = findViewById(R.id.bottomNavigation)
        tvTerminal = findViewById(R.id.tvTerminalOutput)
        svTerminal = findViewById(R.id.svTerminal)
        etInput = findViewById(R.id.etTerminalInput)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        setupBottomNavigation()
        setupDrawerNavigation()
        setupButtonActions()
        setupTerminalActions()

        checkStatus()
    }

    private fun setupEnvironmentBinaries() {
        libDir = File(applicationInfo.nativeLibraryDir)
        binDir = File(filesDir, "bin").apply { mkdirs() }

        // Buat wrapper script executable untuk setiap tools
        val tools = mapOf(
            "railway" to "librailway.so",
            "curl" to "libcurl.so",
            "jq" to "libjq.so",
            "busybox" to "libbusybox.so"
        )

        for ((name, soFile) in tools) {
            val targetSo = File(libDir, soFile)
            val wrapper = File(binDir, name)
            if (targetSo.exists() && (!wrapper.exists() || wrapper.length() == 0L)) {
                wrapper.writeText("#!/system/bin/sh\nexec \"${targetSo.absolutePath}\" \"$@\"\n")
                wrapper.setExecutable(true, false)
            }
        }

        // Siapkan symlink / wrapper busybox untuk utility umum: sh, grep, sed, ls, cat, find
        val busyboxSo = File(libDir, "libbusybox.so")
        if (busyboxSo.exists()) {
            val basicCommands = listOf("sh", "grep", "sed", "awk", "ls", "cat", "find", "mkdir", "rm", "cp", "mv")
            for (cmd in basicCommands) {
                val wrapper = File(binDir, cmd)
                if (!wrapper.exists()) {
                    wrapper.writeText("#!/system/bin/sh\nexec \"${busyboxSo.absolutePath}\" $cmd \"$@\"\n")
                    wrapper.setExecutable(true, false)
                }
            }
        }
    }

    private fun switchTab(tabId: Int) {
        val panelAuth = findViewById<View>(R.id.panelAuth)
        val panelDeploy = findViewById<View>(R.id.panelDeploy)
        val panelManage = findViewById<View>(R.id.panelManage)
        val panelTerminal = findViewById<View>(R.id.panelTerminal)

        panelAuth.visibility = View.GONE
        panelDeploy.visibility = View.GONE
        panelManage.visibility = View.GONE
        panelTerminal.visibility = View.GONE

        when (tabId) {
            R.id.nav_auth -> panelAuth.visibility = View.VISIBLE
            R.id.nav_deploy -> panelDeploy.visibility = View.VISIBLE
            R.id.nav_manage -> panelManage.visibility = View.VISIBLE
            R.id.nav_terminal -> panelTerminal.visibility = View.VISIBLE
        }
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
    }

    private fun setupDrawerNavigation() {
        val navView = findViewById<NavigationView>(R.id.navView)
        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.drawer_status -> checkStatus()
                R.id.drawer_login_browser -> runBrowserlessLogin()
                R.id.drawer_login_token -> showTokenInputDialog()
                R.id.drawer_terminal -> {
                    bottomNav.selectedItemId = R.id.nav_terminal
                    switchTab(R.id.nav_terminal)
                }
                R.id.drawer_clone -> showCloneRepoDialog()
                R.id.drawer_link -> fetchAndLinkProjects()
                R.id.drawer_unlink -> unlinkAndClean()
                R.id.drawer_region -> changeRegionDialog()
                R.id.drawer_domain -> manageDomainDialog()
                R.id.drawer_tcp -> createTcpProxyDialog()
                R.id.drawer_env -> manageEnvVarsDialog()
                R.id.drawer_logs -> runLogs()
            }
            true
        }
    }

    private fun setupButtonActions() {
        findViewById<Button>(R.id.btnLoginBrowserless).setOnClickListener { runBrowserlessLogin() }
        findViewById<Button>(R.id.btnLoginToken).setOnClickListener { showTokenInputDialog() }
        findViewById<Button>(R.id.btnLoginCli).setOnClickListener { runFastAuthLogin() }
        findViewById<Button>(R.id.btnCheckStatus).setOnClickListener { checkStatus() }

        findViewById<Button>(R.id.btnSelectFile).setOnClickListener { selectZipLauncher.launch("application/zip") }
        findViewById<Button>(R.id.btnDeploy).setOnClickListener { runDeploy() }
        findViewById<Button>(R.id.btnCloneRepo).setOnClickListener { showCloneRepoDialog() }
        findViewById<Button>(R.id.btnListAndLink).setOnClickListener { fetchAndLinkProjects() }
        findViewById<Button>(R.id.btnUnlinkClean).setOnClickListener { unlinkAndClean() }

        findViewById<Button>(R.id.btnChangeRegion).setOnClickListener { changeRegionDialog() }
        findViewById<Button>(R.id.btnHttpDomain).setOnClickListener { manageDomainDialog() }
        findViewById<Button>(R.id.btnTcpProxy).setOnClickListener { createTcpProxyDialog() }
        findViewById<Button>(R.id.btnEnvVars).setOnClickListener { manageEnvVarsDialog() }
        findViewById<Button>(R.id.btnLogs).setOnClickListener { runLogs() }
    }

    // --- TERMINAL ENGINE (TERMUX ENVIRONMENT SIMULATION) ---
    private fun appendTerminal(text: String) {
        runOnUiThread {
            val clean = text.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
            tvTerminal.append(clean)
            svTerminal.post { svTerminal.fullScroll(ScrollView.FOCUS_DOWN) }

            // Deteksi kode pairing otomatis
            val match = Regex("""\b([A-Z0-9]{4}-[A-Z0-9]{4})\b""").find(clean)
            if (match != null) {
                val code = match.value
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Railway Code", code))
                Toast.makeText(this, "Kode $code disalin ke clipboard!", Toast.LENGTH_LONG).show()
            }

            // Deteksi link URL resmi aktivasi
            if (clean.contains("railway.com/activate") || (clean.contains("activate") && clean.contains("http"))) {
                val urlMatch = Regex("""https?://[^\s"'<>]+""").find(clean)
                val targetUrl = urlMatch?.value ?: "https://railway.com/activate"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) {
                    CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(targetUrl))
                }
            }
        }
    }

    private fun configureProcessEnv(env: MutableMap<String, String>) {
        env["HOME"] = filesDir.absolutePath
        env["TMPDIR"] = cacheDir.absolutePath
        env["TERM"] = "xterm-256color"
        val existingPath = env["PATH"] ?: "/system/bin"
        env["PATH"] = "${binDir.absolutePath}:${libDir.absolutePath}:$existingPath"
        env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:/system/lib64"
    }

    private fun runTerminalCommand(commandString: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                appendTerminal("\nrailway:~$ $commandString\n")

                val pb = ProcessBuilder("/system/bin/sh", "-c", commandString)
                pb.directory(workspaceDir)
                configureProcessEnv(pb.environment())
                pb.redirectErrorStream(true)

                val process = pb.start()
                activeProcess = process
                processOutputStream = process.outputStream

                val input = process.inputStream
                val buffer = ByteArray(1024)
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    val chunk = String(buffer, 0, read)
                    appendTerminal(chunk)
                }

                val code = process.waitFor()
                appendTerminal("\n[Proses selesai dengan kode $code]\nrailway:~$ ")

                activeProcess = null
                processOutputStream = null
            } catch (e: Exception) {
                appendTerminal("\n[Exception] ${e.message}\nrailway:~$ ")
            }
        }
    }

    private fun setupTerminalActions() {
        findViewById<Button>(R.id.btnTermLogin).setOnClickListener { runTerminalCommand("railway login --browserless") }
        findViewById<Button>(R.id.btnTermWhoami).setOnClickListener { runTerminalCommand("railway whoami") }
        findViewById<Button>(R.id.btnTermStatus).setOnClickListener { runTerminalCommand("railway status") }
        findViewById<Button>(R.id.btnTermClear).setOnClickListener { tvTerminal.text = "railway:~$ " }

        findViewById<Button>(R.id.btnSendTerminal).setOnClickListener {
            val cmd = etInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                etInput.setText("")
                if (activeProcess != null && processOutputStream != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            processOutputStream?.write((cmd + "\n").toByteArray())
                            processOutputStream?.flush()
                        } catch (_: Exception) {}
                    }
                } else {
                    runTerminalCommand(cmd)
                }
            }
        }
    }

    // --- FITUR LOGIN & MANAJEMEN ---
    private fun runBrowserlessLogin() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "Menjalankan Railway CLI..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", "railway login --browserless")
                pb.directory(workspaceDir)
                configureProcessEnv(pb.environment())
                pb.redirectErrorStream(true)

                val process = pb.start()
                val reader = process.inputStream.bufferedReader()
                val outputLog = StringBuilder()
                var openedBrowser = false
                var extractedCode: String? = null

                while (true) {
                    val line = reader.readLine() ?: break
                    outputLog.append(line).append("\n")

                    val codeMatch = Regex("""\b([A-Z0-9]{4}-[A-Z0-9]{4})\b""").find(line)
                    if (codeMatch != null && extractedCode == null) {
                        extractedCode = codeMatch.value
                    }

                    withContext(Dispatchers.Main) {
                        tvStatus.text = outputLog.toString().trim()
                    }

                    if (!openedBrowser && (line.contains("railway.com") || line.contains("http"))) {
                        val urlMatch = Regex("""https?://[^\s"'<>]+""").find(line)
                        if (urlMatch != null && !urlMatch.value.contains("backboard.railway.com")) {
                            openedBrowser = true
                            withContext(Dispatchers.Main) {
                                if (extractedCode != null) {
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Railway Code", extractedCode))
                                    Toast.makeText(this@MainActivity, "Kode $extractedCode disalin!", Toast.LENGTH_LONG).show()
                                    tvStatus.text = "🔑 KODE ANDA: $extractedCode\n(Tersalin otomatis)\n\n$outputLog"
                                }
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlMatch.value)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                } catch (_: Exception) {
                                    CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(urlMatch.value))
                                }
                            }
                        }
                    }
                }

                val exit = process.waitFor()
                withContext(Dispatchers.Main) {
                    if (exit == 0) {
                        Toast.makeText(this@MainActivity, "Login Sukses!", Toast.LENGTH_SHORT).show()
                        checkStatus()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvStatus.text = "Error: ${e.message}" }
            }
        }
    }

    private fun runFastAuthLogin() {
        bottomNav.selectedItemId = R.id.nav_terminal
        switchTab(R.id.nav_terminal)
        runTerminalCommand("railway login")
    }

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = perms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
            }
        }
    }

    private fun getToken(): String? {
        val configFile = File(filesDir, ".railway/config.json")
        if (!configFile.exists()) return null
        return try {
            val json = JSONObject(configFile.readText())
            json.optJSONObject("user")?.optString("accessToken")
                ?: json.optJSONObject("user")?.optString("token")
        } catch (_: Exception) { null }
    }

    private suspend fun postGraphql(query: String, variables: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val token = getToken() ?: return@withContext JSONObject()
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }
        val req = Request.Builder()
            .url(graphqlUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(req).execute().use { res -> JSONObject(res.body?.string() ?: "{}") }
        } catch (_: Exception) { JSONObject() }
    }

    private fun checkStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "Memeriksa status akun & project..."
        runCliCommand("railway whoami") { code, out ->
            if (code == 0 && !out.contains("Unauthorized")) {
                runCliCommand("railway status") { _, statusOut ->
                    tvStatus.text = "🟢 Akun: ${out.trim()}\n\n📊 Status:\n${statusOut.trim()}"
                }
            } else {
                tvStatus.text = "🔴 Belum login ke Railway.\nSilakan gunakan salah satu metode login."
            }
        }
    }

    private fun runCliCommand(commandString: String, workDir: File = workspaceDir, onFinished: (Int, String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", commandString)
                pb.directory(workDir)
                configureProcessEnv(pb.environment())
                pb.redirectErrorStream(true)

                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                withContext(Dispatchers.Main) { onFinished(exitCode, output) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFinished(-1, e.message ?: "Error") }
            }
        }
    }

    private fun showTokenInputDialog() {
        val input = EditText(this).apply { hint = "Tempel Personal API Token di sini" }
        AlertDialog.Builder(this)
            .setTitle("Login via Token")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isNotEmpty()) {
                    val dir = File(filesDir, ".railway").apply { mkdirs() }
                    File(dir, "config.json").writeText("""{"user":{"accessToken":"$token"}}""")
                    Toast.makeText(this, "Token disimpan!", Toast.LENGTH_SHORT).show()
                    checkStatus()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCloneRepoDialog() {
        val input = EditText(this).apply { hint = "https://github.com/user/repo.git" }
        AlertDialog.Builder(this)
            .setTitle("Clone Repository GitHub")
            .setView(input)
            .setPositiveButton("Clone") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    runCliCommand("git clone $url", workDir = workspaceDir) { code, out ->
                        Toast.makeText(this, if (code == 0) "Repo ter-clone!" else "Gagal clone: $out", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun fetchAndLinkProjects() {
        lifecycleScope.launch {
            val q = """query { me { projects { edges { node { id name } } } workspaces { projects { edges { node { id name } } } } } }"""
            val res = postGraphql(q)
            val projects = mutableListOf<Pair<String, String>>()

            fun parseEdges(arr: org.json.JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val node = arr.getJSONObject(i).getJSONObject("node")
                    projects.add(Pair(node.getString("id"), node.getString("name")))
                }
            }

            parseEdges(res.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("projects")?.optJSONArray("edges"))
            val ws = res.optJSONObject("data")?.optJSONObject("me")?.optJSONArray("workspaces")
            if (ws != null) {
                for (i in 0 until ws.length()) {
                    parseEdges(ws.getJSONObject(i).optJSONObject("projects")?.optJSONArray("edges"))
                }
            }

            if (projects.isEmpty()) {
                Toast.makeText(this@MainActivity, "Tidak ada project ditemukan!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val names = projects.map { it.second }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Pilih Project untuk Dihubungkan")
                .setItems(names) { _, idx ->
                    val (pId, pName) = projects[idx]
                    runCliCommand("railway link --project $pId") { code, _ ->
                        Toast.makeText(this@MainActivity, if (code == 0) "Terhubung ke $pName!" else "Gagal link", Toast.LENGTH_SHORT).show()
                        checkStatus()
                    }
                }
                .show()
        }
    }

    private fun unlinkAndClean() {
        AlertDialog.Builder(this)
            .setTitle("Unlink & Reset Cache")
            .setMessage("Yakin ingin menghapus link dan cache project lokal (.railway)?")
            .setPositiveButton("Ya, Reset") { _, _ ->
                runCliCommand("railway unlink") { _, _ ->
                    File(workspaceDir, ".railway").deleteRecursively()
                    Toast.makeText(this, "Cache link dibersihkan total!", Toast.LENGTH_SHORT).show()
                    checkStatus()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runDeploy() {
        val zip = selectedZipFile ?: return
        val btnDeploy = findViewById<Button>(R.id.btnDeploy)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnDeploy.isEnabled = false
        tvStatus.text = "Mengekstrak file & deploy (railway up)..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                workspaceDir.deleteRecursively()
                workspaceDir.mkdirs()
                unzip(zip, workspaceDir)

                val pb = ProcessBuilder("/system/bin/sh", "-c", "railway up --detach")
                pb.directory(workspaceDir)
                configureProcessEnv(pb.environment())
                pb.redirectErrorStream(true)

                val process = pb.start()
                val out = process.inputStream.bufferedReader().readText()
                val code = process.waitFor()

                withContext(Dispatchers.Main) {
                    btnDeploy.isEnabled = true
                    if (code == 0) {
                        tvStatus.text = "✓ DEPLOY BERHASIL!\n$out"
                        Toast.makeText(this@MainActivity, "Deploy Sukses!", Toast.LENGTH_LONG).show()
                    } else {
                        tvStatus.text = "Deploy gagal:\n$out"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnDeploy.isEnabled = true
                    tvStatus.text = "Error deploy: ${e.message}"
                }
            }
        }
    }

    private fun changeRegionDialog() {
        lifecycleScope.launch {
            val q = """query { me { workspaces { id name preferredRegion } } }"""
            val res = postGraphql(q)
            val ws = res.optJSONObject("data")?.optJSONObject("me")?.optJSONArray("workspaces")?.optJSONObject(0)
            val wsId = ws?.optString("id") ?: ""
            val currReg = ws?.optString("preferredRegion") ?: "sin"

            val regions = arrayOf("Singapore (sin)", "US East / Virginia (iad)", "US West / Oregon (pdx)", "Europe / Netherlands (ams)")
            val regCodes = arrayOf("sin", "iad", "pdx", "ams")

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Region Aktif: $currReg")
                .setItems(regions) { _, idx ->
                    val target = regCodes[idx]
                    lifecycleScope.launch {
                        val mut = """
                            mutation workspaceUpdate(${'$'}id: String!, ${'$'}input: WorkspaceUpdateInput!) {
                              workspaceUpdate(id: ${'$'}id, input: ${'$'}input)
                            }
                        """.trimIndent()
                        val vars = JSONObject().apply {
                            put("id", wsId)
                            put("input", JSONObject().apply { put("preferredRegion", target) })
                        }
                        val updateRes = postGraphql(mut, vars)
                        val ok = updateRes.optJSONObject("data")?.optBoolean("workspaceUpdate") ?: false
                        Toast.makeText(this@MainActivity, if (ok) "Region diubah ke $target!" else "Gagal ganti region", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun manageDomainDialog() {
        val options = arrayOf("1. Generate Domain Baru", "2. Custom Subdomain & Port")
        AlertDialog.Builder(this)
            .setTitle("Kelola Domain & Port")
            .setItems(options) { _, which ->
                if (which == 0) {
                    runCliCommand("railway domain") { _, out ->
                        AlertDialog.Builder(this).setTitle("Hasil Generate").setMessage(out).setPositiveButton("OK", null).show()
                    }
                } else {
                    val input = EditText(this).apply { hint = "subdomain|port (contoh: myapp|8080)" }
                    AlertDialog.Builder(this)
                        .setTitle("Format: subdomain|port")
                        .setView(input)
                        .setPositiveButton("Set") { _, _ ->
                            val raw = input.text.toString().trim()
                            if (raw.contains("|")) {
                                val parts = raw.split("|")
                                val sub = parts[0]
                                val port = parts[1].toIntOrNull()
                                updateCustomDomain(sub, port)
                            }
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
            .show()
    }

    private fun updateCustomDomain(subdomain: String, port: Int?) {
        runCliCommand("railway status --json") { code, out ->
            if (code != 0) {
                Toast.makeText(this, "Link project terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@runCliCommand
            }
            try {
                val json = JSONObject(out)
                val envId = json.optJSONObject("environments")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""
                val srvId = json.optJSONObject("services")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""

                lifecycleScope.launch {
                    val mut = """
                        mutation createDomain(${'$'}input: ServiceDomainCreateInput!) {
                          serviceDomainCreate(input: ${'$'}input) { id domain targetPort }
                        }
                    """.trimIndent()
                    val vars = JSONObject().apply {
                        put("input", JSONObject().apply {
                            put("environmentId", envId)
                            put("serviceId", srvId)
                            if (port != null) put("targetPort", port)
                        })
                    }
                    val res = postGraphql(mut, vars)
                    val newDom = res.optJSONObject("data")?.optJSONObject("serviceDomainCreate")?.optString("domain")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Status Domain")
                        .setMessage(if (newDom != null) "Domain aktif:\nhttps://$newDom" else "Gagal: ${res.toString()}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createTcpProxyDialog() {
        val input = EditText(this).apply { hint = "Port container internal (misal: 8881 atau 22)" }
        AlertDialog.Builder(this)
            .setTitle("Buat TCP Proxy (Port Forward)")
            .setView(input)
            .setPositiveButton("Buat") { _, _ ->
                val p = input.text.toString().trim().toIntOrNull()
                if (p != null) {
                    runCliCommand("railway status --json") { code, out ->
                        if (code != 0) return@runCliCommand
                        val json = JSONObject(out)
                        val envId = json.optJSONObject("environments")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""
                        val srvId = json.optJSONObject("services")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""

                        lifecycleScope.launch {
                            val mut = """
                                mutation tcpProxyCreate(${'$'}input: TCPProxyCreateInput!) {
                                  tcpProxyCreate(input: ${'$'}input) { id domain proxyPort applicationPort }
                                }
                            """.trimIndent()
                            val vars = JSONObject().apply {
                                put("input", JSONObject().apply {
                                    put("environmentId", envId)
                                    put("serviceId", srvId)
                                    put("applicationPort", p)
                                })
                            }
                            val res = postGraphql(mut, vars)
                            val d = res.optJSONObject("data")?.optJSONObject("tcpProxyCreate")
                            val domain = d?.optString("domain")
                            val proxyPort = d?.optInt("proxyPort")

                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("TCP Proxy Terbuat")
                                .setMessage(if (domain != null) "Host: $domain\nPublic Port: $proxyPort\nInternal Port: $p" else "Gagal membuat TCP Proxy")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun manageEnvVarsDialog() {
        val options = arrayOf("1. Lihat Semua Variables", "2. Set Variable (KEY=VALUE)")
        AlertDialog.Builder(this)
            .setTitle("Environment Variables")
            .setItems(options) { _, which ->
                if (which == 0) {
                    runCliCommand("railway variables") { _, out ->
                        AlertDialog.Builder(this).setTitle("Variables").setMessage(out).setPositiveButton("OK", null).show()
                    }
                } else {
                    val input = EditText(this).apply { hint = "PORT=8080 atau TOKEN=abc" }
                    AlertDialog.Builder(this)
                        .setTitle("Set Variable")
                        .setView(input)
                        .setPositiveButton("Simpan") { _, _ ->
                            val v = input.text.toString().trim()
                            if (v.contains("=")) {
                                runCliCommand("railway variables --set '$v'") { code, out ->
                                    Toast.makeText(this, if (code == 0) "Variable tersimpan!" else "Gagal: $out", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
            .show()
    }

    private fun runLogs() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "Mengambil logs realtime..."
        runCliCommand("railway logs --lines 100") { _, out ->
            AlertDialog.Builder(this)
                .setTitle("Deployment Logs")
                .setMessage(if (out.isNotEmpty()) out else "Tidak ada log aktif.")
                .setPositiveButton("Tutup", null)
                .show()
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDirectory, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
