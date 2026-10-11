package com.example.railwaymanager

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
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
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var railwayBin: File
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

        setupRailwayBinary()

        val btnLoginBrowserless = findViewById<Button>(R.id.btnLoginBrowserless)
        val btnLoginToken = findViewById<Button>(R.id.btnLoginToken)
        val btnLoginCli = findViewById<Button>(R.id.btnLoginCli)
        val btnCloneRepo = findViewById<Button>(R.id.btnCloneRepo)
        val btnListAndLink = findViewById<Button>(R.id.btnListAndLink)
        val btnUnlinkClean = findViewById<Button>(R.id.btnUnlinkClean)
        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        val btnDeploy = findViewById<Button>(R.id.btnDeploy)
        val btnChangeRegion = findViewById<Button>(R.id.btnChangeRegion)
        val btnHttpDomain = findViewById<Button>(R.id.btnHttpDomain)
        val btnTcpProxy = findViewById<Button>(R.id.btnTcpProxy)
        val btnEnvVars = findViewById<Button>(R.id.btnEnvVars)
        val btnLogs = findViewById<Button>(R.id.btnLogs)

        checkStatus()

        // [2] 3 Mode Login
        btnLoginBrowserless.setOnClickListener { runBrowserLogin() }
        btnLoginToken.setOnClickListener { showTokenInputDialog() }
        btnLoginCli.setOnClickListener { runBrowserLogin() }

        // [3] Clone Repo
        btnCloneRepo.setOnClickListener { showCloneRepoDialog() }

        // [4] List Project & Link
        btnListAndLink.setOnClickListener { fetchAndLinkProjects() }

        // [5] Unlink & Reset Cache
        btnUnlinkClean.setOnClickListener { unlinkAndClean() }

        // [6] File Picker & Deploy
        btnSelectFile.setOnClickListener { selectZipLauncher.launch("application/zip") }
        btnDeploy.setOnClickListener { runDeploy() }

        // [7] Ubah Region
        btnChangeRegion.setOnClickListener { changeRegionDialog() }

        // [8] Kelola Public Domain & Target Port
        btnHttpDomain.setOnClickListener { manageDomainDialog() }

        // [9] Buat TCP Proxy
        btnTcpProxy.setOnClickListener { createTcpProxyDialog() }

        // [10] Variables
        btnEnvVars.setOnClickListener { manageEnvVarsDialog() }

        // [11] Logs
        btnLogs.setOnClickListener { runLogs() }
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
                tvStatus.text = "🔴 Belum login ke Railway."
            }
        }
    }

    // [2] Browser Login
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

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains("railway.com") || line.contains("http")) {
                        val match = """https?://[^\s]+""".toRegex().find(line)
                        if (match != null) {
                            authUrl = match.value
                            break
                        }
                    }
                }

                if (authUrl != null) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Buka browser untuk klik Authorize..."
                        CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(authUrl))
                    }
                    val exitCode = process.waitFor()
                    withContext(Dispatchers.Main) {
                        if (exitCode == 0) {
                            Toast.makeText(this@MainActivity, "Login Sukses!", Toast.LENGTH_SHORT).show()
                            checkStatus()
                        } else {
                            tvStatus.text = "Otorisasi dibatalkan."
                        }
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

    // [3] Clone Repo
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

    // [4] List Project & Link
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

    // [5] Unlink & Reset Cache
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

    // [6] Deploy
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

    // [7] Ubah Region
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

    // [8] Kelola Public Domain & Target Port
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

    // [9] Buat TCP Proxy
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

    // [10] Variables
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

    // [11] Logs
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

    // Ekstraksi otomatis dari railway.gz jika binary utuh belum ada
    private fun setupRailwayBinary() {
        railwayBin = File(filesDir, "railway")
        if (!railwayBin.exists() || railwayBin.length() == 0L) {
            try {
                if (assets.list("")?.contains("railway.gz") == true) {
                    assets.open("railway.gz").use { input ->
                        GZIPInputStream(input).use { gzipIn ->
                            FileOutputStream(railwayBin).use { output ->
                                gzipIn.copyTo(output)
                            }
                        }
                    }
                } else if (assets.list("")?.contains("railway") == true) {
                    assets.open("railway").use { input ->
                        FileOutputStream(railwayBin).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                railwayBin.setExecutable(true, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            railwayBin.setExecutable(true, false)
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
