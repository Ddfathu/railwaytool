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
import android.view.inputmethod.InputMethodManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestStoragePermissions()
        libDir = File(applicationInfo.nativeLibraryDir)

        drawerLayout = findViewById(R.id.drawerLayout)
        bottomNav = findViewById(R.id.bottomNavigation)
        tvTerminal = findViewById(R.id.tvTerminalOutput)
        svTerminal = findViewById(R.id.svTerminal)
        etInput = findViewById(R.id.etTerminalInput)

        etInput.setOnClickListener {
            etInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT)
        }
        etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

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

    private fun switchTab(tabId: Int) {
        findViewById<View>(R.id.panelAuth).visibility = if (tabId == R.id.nav_auth) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelDeploy).visibility = if (tabId == R.id.nav_deploy) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelManage).visibility = if (tabId == R.id.nav_manage) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelTerminal).visibility = if (tabId == R.id.nav_terminal) View.VISIBLE else View.GONE
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
                R.id.drawer_login_browser -> executeCliBrowserlessLogin()
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
        findViewById<Button>(R.id.btnLoginBrowserless).setOnClickListener { executeCliBrowserlessLogin() }
        findViewById<Button>(R.id.btnLoginToken).setOnClickListener { showTokenInputDialog() }
        findViewById<Button>(R.id.btnLoginCli).setOnClickListener { executeCliBrowserlessLogin() }
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

    private fun appendTerminal(text: String) {
        runOnUiThread {
            val clean = text.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
            tvTerminal.append(clean)
            svTerminal.post { svTerminal.fullScroll(ScrollView.FOCUS_DOWN) }

            // Deteksi kode aktivasi (misal: ABCD-1234 atau 8 karakter alfanumerik)
            val codeMatch = Regex("""\b([A-Z0-9]{4}-[A-Z0-9]{4})\b""").find(clean)
            if (codeMatch != null) {
                val code = codeMatch.value
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Railway Code", code))
                Toast.makeText(this, "Kode $code disalin!", Toast.LENGTH_SHORT).show()
            }

            // Buka link aktivasi otomatis jika muncul URL aktivasi
            if (clean.contains("railway.com/activate") || clean.contains("activate")) {
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

    private fun resolveCommandToArgs(rawCommand: String): List<String> {
        val parts = rawCommand.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()

        val cmd = parts[0]
        val args = parts.drop(1)

        val railwayBin = File(libDir, "librailway.so")
        val curlBin = File(libDir, "libcurl.so")
        val jqBin = File(libDir, "libjq.so")
        val busyboxBin = File(libDir, "libbusybox.so")

        // Perintah sistem Android native langsung ke /system/bin
        val systemBin = File("/system/bin", cmd)
        if (cmd == "ping" && systemBin.exists()) {
            return listOf(systemBin.absolutePath) + args
        }

        return when (cmd) {
            "railway" -> listOf(railwayBin.absolutePath) + args
            "curl" -> listOf(curlBin.absolutePath) + args
            "jq" -> listOf(jqBin.absolutePath) + args
            "busybox" -> listOf(busyboxBin.absolutePath) + args
            else -> {
                if (systemBin.exists()) {
                    listOf(systemBin.absolutePath) + args
                } else if (busyboxBin.exists()) {
                    listOf(busyboxBin.absolutePath, cmd) + args
                } else {
                    listOf(cmd) + args
                }
            }
        }
    }

    private fun runTerminalCommand(commandString: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cmdArgs = resolveCommandToArgs(commandString)
                if (cmdArgs.isEmpty()) return@launch

                val exeFile = File(cmdArgs[0])
                if (!exeFile.exists() && cmdArgs[0].startsWith("/")) {
                    appendTerminal("\n[ERROR] Command '${cmdArgs[0]}' tidak ditemukan!\nrailway:~$ ")
                    return@launch
                }

                appendTerminal("\nrailway:~$ $commandString\n")

                val pb = ProcessBuilder(cmdArgs)
                pb.directory(workspaceDir)
                val env = pb.environment()
                env["HOME"] = filesDir.absolutePath
                env["TMPDIR"] = cacheDir.absolutePath
                env["TERM"] = "xterm-256color"
                env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:/system/lib64"
                
                // Set DNS nameserver default untuk biner libc
                env["RES_OPTIONS"] = "nameserver:8.8.8.8 nameserver:1.1.1.1"

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
        findViewById<Button>(R.id.btnTermLogin).setOnClickListener { executeCliBrowserlessLogin() }
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

    private fun executeCliBrowserlessLogin() {
        bottomNav.selectedItemId = R.id.nav_terminal
        switchTab(R.id.nav_terminal)
        // Gunakan login browserless CLI resmi
        runTerminalCommand("railway login --browserless")
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
        tvStatus.text = "Memeriksa status akun..."

        val token = getToken()
        if (token.isNullOrEmpty()) {
            tvStatus.text = "🔴 Belum login ke Railway.\nSilakan gunakan menu Login atau masukkan Token."
            return
        }

        lifecycleScope.launch {
            val q = """query { me { email name } }"""
            val res = postGraphql(q)
            val me = res.optJSONObject("data")?.optJSONObject("me")
            if (me != null) {
                val email = me.optString("email", "-")
                val name = me.optString("name", "User")
                tvStatus.text = "🟢 Terhubung ke Railway!\nAkun: $name ($email)"
            } else {
                tvStatus.text = "🔴 Token kadaluarsa atau tidak valid."
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
            .setPositiveButton("Buka Terminal") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    bottomNav.selectedItemId = R.id.nav_terminal
                    switchTab(R.id.nav_terminal)
                    runTerminalCommand("git clone $url")
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
                    bottomNav.selectedItemId = R.id.nav_terminal
                    switchTab(R.id.nav_terminal)
                    runTerminalCommand("railway link --project $pId")
                }
                .show()
        }
    }

    private fun unlinkAndClean() {
        AlertDialog.Builder(this)
            .setTitle("Unlink & Reset Cache")
            .setMessage("Yakin ingin menghapus link dan cache project lokal (.railway)?")
            .setPositiveButton("Ya, Reset") { _, _ ->
                bottomNav.selectedItemId = R.id.nav_terminal
                switchTab(R.id.nav_terminal)
                runTerminalCommand("railway unlink")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runDeploy() {
        val zip = selectedZipFile ?: return
        val btnDeploy = findViewById<Button>(R.id.btnDeploy)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnDeploy.isEnabled = false
        tvStatus.text = "Mengekstrak file & deploy..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                workspaceDir.deleteRecursively()
                workspaceDir.mkdirs()
                unzip(zip, workspaceDir)

                withContext(Dispatchers.Main) {
                    btnDeploy.isEnabled = true
                    bottomNav.selectedItemId = R.id.nav_terminal
                    switchTab(R.id.nav_terminal)
                    runTerminalCommand("railway up --detach")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnDeploy.isEnabled = true
                    tvStatus.text = "Error: ${e.message}"
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
                    bottomNav.selectedItemId = R.id.nav_terminal
                    switchTab(R.id.nav_terminal)
                    runTerminalCommand("railway domain")
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
        val railwayBin = File(libDir, "librailway.so")
        lifecycleScope.launch(Dispatchers.IO) {
            val pb = ProcessBuilder(railwayBin.absolutePath, "status", "--json")
            pb.directory(workspaceDir)
            pb.environment()["HOME"] = filesDir.absolutePath
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()

            if (code != 0) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Link project terlebih dahulu!", Toast.LENGTH_SHORT).show() }
                return@launch
            }

            try {
                val json = JSONObject(out)
                val envId = json.optJSONObject("environments")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""
                val srvId = json.optJSONObject("services")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""

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
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Status Domain")
                        .setMessage(if (newDom != null) "Domain aktif:\nhttps://$newDom" else "Gagal: ${res.toString()}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
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
                    val railwayBin = File(libDir, "librailway.so")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val pb = ProcessBuilder(railwayBin.absolutePath, "status", "--json")
                        pb.directory(workspaceDir)
                        pb.environment()["HOME"] = filesDir.absolutePath
                        val process = pb.start()
                        val out = process.inputStream.bufferedReader().readText()
                        val code = process.waitFor()
                        if (code != 0) return@launch

                        val json = JSONObject(out)
                        val envId = json.optJSONObject("environments")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""
                        val srvId = json.optJSONObject("services")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.getString("id") ?: ""

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

                        withContext(Dispatchers.Main) {
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
        bottomNav.selectedItemId = R.id.nav_terminal
        switchTab(R.id.nav_terminal)
        runTerminalCommand("railway variables")
    }

    private fun runLogs() {
        bottomNav.selectedItemId = R.id.nav_terminal
        switchTab(R.id.nav_terminal)
        runTerminalCommand("railway logs --lines 100")
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
