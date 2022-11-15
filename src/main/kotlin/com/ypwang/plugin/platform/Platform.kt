package com.ypwang.plugin.platform

import com.goide.sdk.GoSdkService
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.ypwang.plugin.fetchProcessOutput
import com.ypwang.plugin.getLatestReleaseMeta
import com.ypwang.plugin.model.GithubRelease
import com.ypwang.plugin.model.RunProcessResult
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Paths

fun platformFactory(project: Project): Platform =
    when {
        SystemInfo.isWindows -> {
            val goRoot = GoSdkService.getInstance(project).getSdk(null).sdkRoot
            if (goRoot != null && WslPath.isWslUncPath(goRoot.path))
                WSL(goRoot.path)
            else
                Windows()
        }
        SystemInfo.isLinux -> Linux()
        SystemInfo.isMac -> Mac()
        else -> throw Exception("Unknown system type: ${SystemInfo.OS_NAME}")
    }

abstract class Platform {
    companion object {
        const val LinterName = "golangci-lint"
    }

    protected abstract fun os(): String
    protected abstract fun suffix(): String
    protected abstract fun tempPath(): String
    protected abstract fun decompress(compressed: String, targetFile: String, to: String, setFraction: (Double) -> Unit, cancelled: () -> Boolean)
    abstract fun buildCommand(params: List<String>, runningDir: String?, env: Map<String, String>): String
    abstract fun linterName(): String
    abstract fun defaultPath(): String

    open fun runProcess(params: List<String>, runningDir: String?, env: Map<String, String>, encoding: Charset = Charset.defaultCharset()): RunProcessResult =
        fetchProcessOutput(
            ProcessBuilder(params).apply {
                val curEnv = this.environment()
                env.forEach { kv -> curEnv[kv.key] = kv.value }
                if (runningDir != null)
                    this.directory(File(runningDir))
            }.start(),
            encoding
        )

    private fun arch(): String = System.getProperty("os.arch").let {
        when (it) {
            "x86" -> "386"
            "amd64", "x86_64" -> "amd64"
            "aarch64" -> "arm64"
            else -> throw Exception("Unknown system arch: $it")
        }
    }

    fun getPlatformSpecificBinName(meta: GithubRelease): String = "${LinterName}-${meta.name.substring(1)}-${os()}-${arch()}.${suffix()}"
    fun fetchLatestGoLinter(destDir: String, setText: (String) -> Unit, setFraction: (Double) -> Unit, cancelled: () -> Boolean): String {
        HttpClientBuilder.create().disableContentCompression().build().use { httpClient ->
            setText("Getting latest release meta")
            val latest = getLatestReleaseMeta(httpClient)
            setFraction(0.2)

            if (cancelled())
                return ""

            // "golangci-lint-1.23.3-darwin-amd64.tar.gz"
            val binaryFileName = getPlatformSpecificBinName(latest)
            val asset = latest.assets.single { it.name == binaryFileName }
            // "/tmp/golangci-lint-1.23.3-darwin-amd64.tar.gz"
            val tmp = Paths.get(tempPath(), binaryFileName).toString()

            setText("Downloading $binaryFileName")
            httpClient.execute(HttpGet(asset.browserDownloadUrl)).use { response ->
                copy(response.entity.content, tmp, asset.size.toLong(), { f -> setFraction(0.2 + 0.6 * f) }, cancelled)
            }

            val toFile = Paths.get(destDir, linterName()).toString()
            setText("Decompressing to $toFile")
            decompress(tmp, linterName(), toFile, { f -> setFraction(0.8 + 0.2 * f) }, cancelled)
            File(tmp).delete()

            if (File(toFile).let { !it.canExecute() && !it.setExecutable(true) }) {
                throw Exception("Permission denied to execute $toFile")
            }
            return toFile
        }
    }

    open fun canExecute(path: String): Boolean = File(path).canExecute()
    open fun canWrite(path: String): Boolean = File(path).canWrite()
    fun parentFolder(path: String): String = File(path).parent

    // nio should be more efficient, but let's show some progress to make programmer happy
    fun copy(input: InputStream, to: String, totalSize: Long, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
        FileOutputStream(to).use { fos ->
            var sum = 0.0
            var len: Int
            val data = ByteArray(20 * 1024)

            while (!cancelled()) {
                len = input.read(data)
                if (len == -1)
                    break
                fos.write(data, 0, len)
                sum += len
                setFraction(minOf(sum / totalSize, 1.0))
            }
        }
    }
}
