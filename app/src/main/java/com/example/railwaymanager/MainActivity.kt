package com.example.railwaymanager

import android.Manifest
import android.app.AlertDialog
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
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var railwayBin: File
    private lateinit var drawerLayout: DrawerLayout
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
        setupRailwayBinary()

        drawerLayout = findViewById(R.id.drawerLayout)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        setupBottomNavigation()
        setupDrawerNavigation()
        setupButtonActions()

        checkStatus()
    }

    private fun setupRailwayBinary() {
        // Ambil biner langsung dari folder native library sistem Android
        railwayBin = File(applicationInfo.nativeLibraryDir, "librailway.so")
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        val panelAuth = findViewById<View>(R.id.panelAuth)
        val panelDeploy = findViewById<View>(R.id.panelDeploy)
        val panelManage = findViewById<View>(R.id.panelManage)

        bottomNav.setOnItemSelectedListener { item ->
            panelAuth.visibility = View.GONE
            panelDeploy.visibility = View.GONE
            panelManage.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_auth -> {
                    panelAuth.visibility = View.VISIBLE
                    true
                }
                R.id.nav_deploy -> {
                    panelDeploy.visibility = View.VISIBLE
                    true
                }
                R.id.nav_manage -> {
                    panelManage.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDrawerNavigation() {
        val navView = findViewById<NavigationView>(R.id.navView)
        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.drawer_status -> checkStatus()
                R.id.drawer_login_browser -> runBrowserLogin()
                R.id.drawer_login_token -> showTokenInputDialog()
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
        findViewById<Button>(R.id.btnLoginBrowserless).setOnClickListener { runBrowserLogin() }
        findViewById<Button>(R.id.btnLoginToken).setOnClickListener { showTokenInputDialog() }
        findViewById<Button>(R.id.btnLoginCli).setOnClickListener { runBrowserLogin() }
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

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = permissions.filter {
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

    private fun runCliCommand(vararg args: String, workDir: File = workspaceDir, onFinished: (Int, String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!railwayBin.exists()) {
                    withContext(Dispatchers.Main) {
                        onFinished(-1, "Binary librailway.so tidak ditemukan di ${railwayBin.absolutePath}")
                    }
                    return@launch
                }

                val pb = ProcessBuilder(railwayBin.absolutePath, *args)
                pb.directory(workDir)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["TMPDIR"] = cacheDir.absolutePath
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
        runCliCommand("whoami") { code, out ->
            if (code == 0 && !out.contains("Unauthorized")) {
                runCliCommand("status") { _, statusOut ->
                    tvStatus.text = "🟢 Akun: ${out.trim()}\n\n📊 Status:\n${statusOut.trim()}"
                }
            } else {
                tvStatus.text = "🔴 Belum login ke Railway.\nSilakan gunakan menu Login."
            }
        }
    }

    private fun runBrowserLogin() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "Membuat sesi login CLI..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(railwayBin.absolutePath, "login", "--browserless")
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["TMPDIR"] = cacheDir.absolutePath
                pb.redirectErrorStream(true)

                val process = pb.start()
                val reader = process.inputStream.bufferedReader()
                var authUrl: String? = null
                var pairingCode: String? = null
                val logBuilder = StringBuilder()

                while (true) {
                    val line = reader.readLine() ?: break
                    logBuilder.append(line).append("\n")

                    // Ambil pairing code format XXXX-XXXX
                    val codeMatch = """\b([A-Z0-9]{4}-[A-Z0-9]{4})\b""".toRegex().find(line)
                    if (codeMatch != null && pairingCode == null) {
                        pairingCode = codeMatch.value
                    }

                    // Ambil link browser resmi (jangan ambil backboard.railway.com)
                    if (line.contains("railway.com") && !line.contains("backboard.railway.com")) {
                        val match = """https?://[^\s"'<>]+""".toRegex().find(line)
                        if (match != null && authUrl == null) {
                            authUrl = match.value
                        }
                    }

                    // Tampilkan terminal realtime
                    withContext(Dispatchers.Main) {
                        tvStatus.text = logBuilder.toString().trim()
                    }

                    if (authUrl != null && pairingCode != null) {
                        break
                    }
                }

                // Fallback URL jika CLI hanya memberikan kode
                val targetUrl = authUrl ?: "https://railway.com/activate"

                withContext(Dispatchers.Main) {
                    if (pairingCode != null) {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Railway Code", pairingCode))
                        Toast.makeText(this@MainActivity, "Kode $pairingCode telah disalin!", Toast.LENGTH_LONG).show()
                        tvStatus.text = "🔑 KODE ANDA: $pairingCode\n(Tersalin ke clipboard)\n\nSilakan paste di browser!"
                    } else {
                        tvStatus.text = "Membuka browser otorisasi..."
                    }
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(targetUrl))
                    }
                }

                val exitCode = process.waitFor()
                withContext(Dispatchers.Main) {
                    if (exitCode == 0) {
                        Toast.makeText(this@MainActivity, "Login Sukses!", Toast.LENGTH_SHORT).show()
                        checkStatus()
                    } else {
                        tvStatus.text = "Sesi CLI selesai."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvStatus.text = "Error: ${e.message}" }
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
                    runCliCommand("git", "clone", url, workDir = workspaceDir) { code, out ->
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
                    runCliCommand("link", "--project", pId) { code, _ ->
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
                runCliCommand("unlink") { _, _ ->
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

                val pb = ProcessBuilder(railwayBin.absolutePath, "up", "--detach")
                pb.directory(workspaceDir)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["TMPDIR"] = cacheDir.absolutePath
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
                    runCliCommand("domain") { _, out ->
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
        runCliCommand("status", "--json") { code, out ->
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
                    runCliCommand("status", "--json") { code, out ->
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
                    runCliCommand("variables") { _, out ->
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
                                runCliCommand("variables", "--set", v) { code, out ->
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
        runCliCommand("logs", "--lines", "100") { _, out ->
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
