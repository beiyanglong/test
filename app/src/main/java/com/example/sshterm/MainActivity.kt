package com.example.sshterm

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Esc { NONE, ESC, CSI, OSC, SKIP }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null

    private val screenBuf = StringBuilder()
    private var esc = Esc.NONE
    private var cr = false

    private lateinit var form: View
    private lateinit var term: View
    private lateinit var status: TextView
    private lateinit var screen: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        form = findViewById(R.id.form)
        term = findViewById(R.id.term)
        status = findViewById(R.id.status)
        screen = findViewById(R.id.screen)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)

        val host = findViewById<EditText>(R.id.host)
        val port = findViewById<EditText>(R.id.port)
        val user = findViewById<EditText>(R.id.user)
        val pass = findViewById<EditText>(R.id.pass)
        val prefs = getSharedPreferences("ssh", MODE_PRIVATE)
        host.setText(prefs.getString("host", ""))
        port.setText(prefs.getString("port", "22"))
        user.setText(prefs.getString("user", ""))

        findViewById<Button>(R.id.connect).setOnClickListener {
            prefs.edit().putString("host", host.text.toString())
                .putString("port", port.text.toString())
                .putString("user", user.text.toString()).apply()
            connect(
                host.text.toString().trim(),
                port.text.toString().toIntOrNull() ?: 22,
                user.text.toString().trim(),
                pass.text.toString()
            )
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                send(input.text.toString() + "\r")
                input.setText("")
                true
            } else false
        }

        // 辅助按键：标签 -> 发送序列；Tab 会先带上已输入的文字以触发远端补全
        val keys = findViewById<LinearLayout>(R.id.keys)
        listOf(
            "Tab" to "\t", "Esc" to "\u001b", "^C" to "\u0003", "^D" to "\u0004", "^Z" to "\u001a",
            "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C"
        ).forEach { (label, seq) ->
            keys.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    if (label == "Tab") {
                        send(input.text.toString() + seq)
                        input.setText("")
                    } else send(seq)
                }
            })
        }
        keys.addView(Button(this).apply {
            text = "断开"
            setOnClickListener { disconnect() }
        })
    }

    private fun connect(host: String, port: Int, user: String, pass: String) {
        if (host.isEmpty() || user.isEmpty()) {
            status.text = "请填写主机和用户名"
            return
        }
        status.setTextColor(0xFFCCCCCC.toInt())
        status.text = "连接中…"
        io.execute {
            try {
                val s = JSch().getSession(user, host, port)
                s.setPassword(pass)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                s.setServerAliveInterval(30000)
                s.connect(10000)
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm")
                ch.setPtySize(80, 24, 0, 0)
                val inp = ch.inputStream
                out = ch.outputStream
                ch.connect(10000)
                session = s
                channel = ch
                ui.post { showTerminal() }
                readLoop(inp)
            } catch (e: Exception) {
                ui.post {
                    status.setTextColor(0xFFFF6B6B.toInt())
                    status.text = "连接失败：${e.message}"
                }
            }
        }
    }

    private fun readLoop(inp: InputStream) {
        try {
            val reader = inp.reader(Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val chunk = String(buf, 0, n)
                ui.post { feed(chunk); render() }
            }
        } catch (_: Exception) {
        }
        ui.post {
            feed("\n[连接已关闭]\n"); render()
            closeQuietly()
            term.visibility = View.GONE
            form.visibility = View.VISIBLE
            status.setTextColor(0xFFCCCCCC.toInt())
            status.text = "连接已关闭"
        }
    }

    private fun showTerminal() {
        screenBuf.setLength(0)
        esc = Esc.NONE
        cr = false
        render()
        form.visibility = View.GONE
        term.visibility = View.VISIBLE
        input.requestFocus()
    }

    private fun send(s: String) {
        val o = out ?: return
        io.execute {
            try {
                o.write(s.toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun disconnect() = io.execute { closeQuietly() }

    private fun closeQuietly() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
        out = null
    }

    private fun render() {
        screen.text = screenBuf
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 极简终端解析：去掉 ANSI 转义序列，处理回车/退格，不做光标定位。 */
    private fun feed(s: String) {
        for (c in s) {
            if (cr && c != '\r') {
                cr = false
                if (c != '\n') eraseLine()
            }
            when (esc) {
                Esc.NONE -> when (c) {
                    '\u001b' -> esc = Esc.ESC
                    '\r' -> cr = true
                    '\b' -> if (screenBuf.isNotEmpty() && screenBuf.last() != '\n')
                        screenBuf.setLength(screenBuf.length - 1)
                    '\n', '\t' -> screenBuf.append(c)
                    else -> if (c >= ' ') screenBuf.append(c)
                }
                Esc.ESC -> esc = when (c) {
                    '[' -> Esc.CSI
                    ']' -> Esc.OSC
                    '(', ')' -> Esc.SKIP
                    else -> Esc.NONE
                }
                Esc.CSI -> if (c in '@'..'~') esc = Esc.NONE
                Esc.OSC -> when (c) {
                    '\u0007' -> esc = Esc.NONE
                    '\u001b' -> esc = Esc.ESC
                }
                Esc.SKIP -> esc = Esc.NONE
            }
        }
        if (screenBuf.length > 100_000) screenBuf.delete(0, 20_000)
    }

    private fun eraseLine() {
        val i = screenBuf.lastIndexOf("\n")
        screenBuf.setLength(i + 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.execute { closeQuietly() }
        io.shutdown()
    }
}
