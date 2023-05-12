package com.neoslax.emailsmtpchecker

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.neoslax.emailsmtpchecker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.hla.ResolverApi
import org.minidns.record.MX
import org.minidns.record.Record
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val logEventsFlow = MutableSharedFlow<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupListeners()
    }

    private fun setupListeners() {
        logEventsFlow
            .onEach(::updateLogTextView)
            .launchIn(lifecycleScope)

        binding.buttonAddUser.setOnClickListener {
            binding.editTextUsername.text.toString()
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let {
                    getCheckerFlow(it)
                        .onStart {
                            binding.textViewUsers.text = ""
                        }
                        .catch { throwable ->
                            logEventsFlow.emit(throwable.message.toString())
                        }
                        .launchIn(lifecycleScope)
                }
        }
    }

    private fun updateLogTextView(log: String) {
        val logBuilder = StringBuilder()
        val currentText = binding.textViewUsers.text.toString()
        logBuilder.appendLine(currentText)
        logBuilder.appendLine(log)
        println(logBuilder)
        binding.textViewUsers.text = logBuilder
    }

    private fun getCheckerFlow(email: String): Flow<Unit> = flow {
        val checkingResult = verifyEmail(email)
        logEventsFlow.emit(if (checkingResult) "email exist" else "email not exist")
    }


    private suspend fun verifyEmail(email: String): Boolean = withContext(Dispatchers.IO) {

        val domain = email.substringAfterLast("@")
        val mxRecords = getMxInAndroid(domain)
        logEventsFlow.emit("Check email = $email, mxRecords got = $mxRecords")
        if (mxRecords.isEmpty()) {
            return@withContext false
        }
        val socket = Socket()
        socket.connect(InetSocketAddress(mxRecords[0], 25), 10_000)
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()
        val reader = BufferedReader(InputStreamReader(inputStream))
        val response =
            reader.readLine() ?: return@withContext false // Check if SMTP server responded
        if (!response.startsWith("220")) {
            return@withContext false
        }
        outputStream.write("HELO $domain\r\n".toByteArray())
        logEventsFlow.emit("> HELO $domain")
        val heloResponse =
            reader.readLine() ?: return@withContext false // Check if SMTP server responded
        logEventsFlow.emit(heloResponse)
        if (!heloResponse.startsWith("250")) {
            return@withContext false
        }
        outputStream.write("MAIL FROM:<test@example.com>\r\n".toByteArray())
        logEventsFlow.emit("> MAIL FROM:<test@example.com>")
        val mailFromResponse =
            reader.readLine() ?: return@withContext false // Check if SMTP server responded
        logEventsFlow.emit(mailFromResponse)
        if (!mailFromResponse.startsWith("250")) {
            return@withContext false
        }
        outputStream.write("RCPT TO:<$email>\r\n".toByteArray())
        logEventsFlow.emit("> RCPT TO:<$email>")
        val rcptFromResponse =
            reader.readLine() ?: return@withContext false // Check if SMTP server responded
        logEventsFlow.emit(rcptFromResponse)
        if (!rcptFromResponse.startsWith("250")) {
            return@withContext false
        }

        outputStream.write("QUIT\r\n".toByteArray())
        logEventsFlow.emit("> QUIT")
        socket.close()
        return@withContext true
    }

    private fun getMxInAndroid(domain: String): List<String> {
        val question = Question(domain, Record.TYPE.MX)
        val resultMx = ResolverApi.INSTANCE.resolve<MX>(question)
        if (resultMx.responseCode == DnsMessage.RESPONSE_CODE.NO_ERROR) {
            return resultMx.answers.map { it.target.toString() }
        }
        throw RuntimeException("DNS NOT FOUND")
    }
}