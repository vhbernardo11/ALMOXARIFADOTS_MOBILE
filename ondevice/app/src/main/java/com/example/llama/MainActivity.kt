package com.example.llama

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var messagesView: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var statusText: TextView
    private lateinit var assistantButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private val currentMessages = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter

    private lateinit var chatRepository: ChatRepository
    private val sessions = mutableListOf<ChatSession>()
    private lateinit var currentSessionId: String

    private var engine: InferenceEngine? = null
    private var generationJob: Job? = null
    private var modelReady = false
    private var busy = false
    private var downloading = false
    private var tts: TextToSpeech? = null

    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importModel(uri)
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val alternatives = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            alternatives?.firstOrNull()?.let { recognized ->
                input.setText(recognized)
                input.setSelection(recognized.length)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#080A0F")
        window.navigationBarColor = Color.parseColor("#080A0F")

        chatRepository = ChatRepository(preferences)
        restoreSessions()
        buildInterface()

        tts = TextToSpeech(this, this)
        initializeLocalEngine()
    }

    private fun restoreSessions() {
        sessions.clear()
        sessions.addAll(chatRepository.loadSessions())
        if (sessions.isEmpty()) {
            sessions.add(createSession(0))
        }
        val restoredId = chatRepository.currentId()
        currentSessionId = sessions.firstOrNull { it.id == restoredId }?.id ?: sessions.first().id
        activateSession(currentSessionId, reloadModel = false)
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleArea.addView(TextView(this).apply {
            text = "Vitão Assistente"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        })
        statusText = TextView(this).apply {
            text = "Preparando inteligência local…"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 12f
        }
        titleArea.addView(statusText)
        header.addView(titleArea)
        header.addView(smallButton("⋮", "Mais opções") { showMoreMenu() })
        root.addView(header)

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(6))
        }
        tools.addView(smallButton("☰", "Histórico") { showHistory() })
        tools.addView(smallButton("＋", "Nova conversa") { startNewChat(currentAssistantIndex()) })
        assistantButton = smallButton("🎭 Assistente geral", "Escolher assistente") {
            chooseAssistant()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            }
        }
        tools.addView(assistantButton)
        tools.addView(smallButton("🧠", "Gerenciar modelo local") { showModelMenu() })
        root.addView(tools)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        progressText = TextView(this).apply {
            setTextColor(Color.parseColor("#A7B0C0"))
            textSize = 12f
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(5)
        ))
        root.addView(progressText)

        messagesView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
        }
        messageAdapter = MessageAdapter(currentMessages) { message -> showMessageActions(message) }
        messagesView.adapter = messageAdapter
        root.addView(messagesView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(7), 0, 0)
        }
        composer.addView(smallButton("🎙", "Falar") { startVoiceInput() })

        input = EditText(this).apply {
            hint = "Escreva uma mensagem…"
            setHintTextColor(Color.parseColor("#7C879A"))
            setTextColor(Color.WHITE)
            textSize = 16f
            minLines = 1
            maxLines = 5
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground("#141A24", "#2D3748", 18)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }
        composer.addView(input)

        sendButton = smallButton("➤", "Enviar") { sendOrStop() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        }
        composer.addView(sendButton)
        root.addView(composer)

        setContentView(root)
        updateAssistantButton()
        refreshMessages()
    }

    private fun smallButton(label: String, description: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            contentDescription = description
            textSize = 14f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(42)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { action() }
        }

    private fun roundedBackground(fill: String, stroke: String, radius: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun initializeLocalEngine() {
        lifecycleScope.launch {
            setBusyState(true, "Inicializando motor local…")
            try {
                val localEngine = withContext(Dispatchers.Default) {
                    AiChat.getInferenceEngine(applicationContext)
                }
                engine = localEngine
                awaitEngineStable(localEngine)

                val model = selectedModelFile()
                if (model != null && model.exists()) {
                    loadModelForCurrentChat(model)
                } else {
                    setBusyState(false, "Modelo local ainda não instalado")
                    showModelSetupDialog()
                }
            } catch (error: Exception) {
                setBusyState(false, "Falha ao iniciar o motor local")
                showError("Não foi possível iniciar a inteligência neste aparelho.", error)
            }
        }
    }

    private suspend fun awaitEngineStable(localEngine: InferenceEngine) {
        repeat(600) {
            when (val state = localEngine.state.value) {
                is InferenceEngine.State.Initialized,
                is InferenceEngine.State.ModelReady,
                is InferenceEngine.State.Error -> return
                else -> {
                    delay(50)
                    if (it == 599) error("Tempo esgotado ao inicializar: ${state.javaClass.simpleName}")
                }
            }
        }
    }

    private fun selectedModelFile(): File? {
        val savedPath = preferences.getString(KEY_MODEL_PATH, null)
        return savedPath?.let(::File)?.takeIf { it.exists() }
            ?: File(modelsDirectory(), DEFAULT_MODEL_FILENAME).takeIf { it.exists() }
    }

    private fun modelsDirectory(): File = File(filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private fun showModelSetupDialog() {
        if (isFinishing || downloading) return
        AlertDialog.Builder(this)
            .setTitle("Instalar inteligência local")
            .setMessage(
                "O aplicativo precisa baixar uma vez o modelo Qwen3 0.6B, com cerca de 429 MB. " +
                    "Depois disso, as conversas funcionam somente no aparelho e podem ser usadas sem internet."
            )
            .setPositiveButton("Baixar modelo") { _, _ -> downloadDefaultModel() }
            .setNeutralButton("Importar GGUF") { _, _ -> modelPicker.launch(arrayOf("*/*")) }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun showModelMenu() {
        val model = selectedModelFile()
        val options = mutableListOf<String>()
        if (model != null) {
            options += "Informações do modelo"
            options += "Recarregar modelo"
        }
        options += "Baixar modelo recomendado"
        options += "Importar outro arquivo GGUF"
        if (model != null) options += "Excluir modelo deste aparelho"

        AlertDialog.Builder(this)
            .setTitle("Inteligência local")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Informações do modelo" -> showModelInfo(model!!)
                    "Recarregar modelo" -> reloadCurrentModel()
                    "Baixar modelo recomendado" -> downloadDefaultModel()
                    "Importar outro arquivo GGUF" -> modelPicker.launch(arrayOf("*/*"))
                    "Excluir modelo deste aparelho" -> confirmDeleteModel(model!!)
                }
            }
            .show()
    }

    private fun showModelInfo(model: File) {
        AlertDialog.Builder(this)
            .setTitle("Modelo instalado")
            .setMessage(
                "Arquivo: ${model.name}\n" +
                    "Tamanho: ${formatBytes(model.length())}\n" +
                    "Local: armazenamento privado do aplicativo\n\n" +
                    "As mensagens são processadas neste aparelho. A internet só é usada para baixar o modelo."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDeleteModel(model: File) {
        AlertDialog.Builder(this)
            .setTitle("Excluir modelo?")
            .setMessage("O chat deixará de responder até que outro modelo seja instalado.")
            .setPositiveButton("Excluir") { _, _ ->
                generationJob?.cancel()
                lifecycleScope.launch {
                    modelReady = false
                    try {
                        withContext(Dispatchers.Default) { unloadModelIfNeeded() }
                    } catch (_: Exception) {
                    }
                    model.delete()
                    preferences.edit().remove(KEY_MODEL_PATH).apply()
                    setBusyState(false, "Modelo removido")
                    showModelSetupDialog()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun downloadDefaultModel() {
        if (downloading) return
        if (filesDir.usableSpace < MINIMUM_FREE_STORAGE) {
            AlertDialog.Builder(this)
                .setTitle("Espaço insuficiente")
                .setMessage("Libere pelo menos 700 MB de armazenamento e tente novamente.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        lifecycleScope.launch {
            downloading = true
            modelReady = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            showProgress("Preparando download…", 0)
            setBusyState(true, "Baixando inteligência local…")

            val target = File(modelsDirectory(), DEFAULT_MODEL_FILENAME)
            val partial = File(modelsDirectory(), "$DEFAULT_MODEL_FILENAME.part")
            try {
                withContext(Dispatchers.IO) {
                    partial.delete()
                    val connection = (URL(DEFAULT_MODEL_URL).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "VitaoAssistenteLocal/2.0 Android")
                    }
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        error("Servidor respondeu ${connection.responseCode}")
                    }
                    val total = connection.contentLengthLong
                    connection.inputStream.use { source ->
                        FileOutputStream(partial).use { destination ->
                            val buffer = ByteArray(1024 * 256)
                            var downloaded = 0L
                            var lastUpdate = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                destination.write(buffer, 0, read)
                                downloaded += read
                                if (downloaded - lastUpdate >= 1024 * 1024) {
                                    lastUpdate = downloaded
                                    val percent = if (total > 0) {
                                        ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                    } else 0
                                    withContext(Dispatchers.Main) {
                                        showProgress(
                                            "Baixando: ${formatBytes(downloaded)}" +
                                                if (total > 0) " de ${formatBytes(total)}" else "",
                                            percent
                                        )
                                    }
                                }
                            }
                        }
                    }
                    connection.disconnect()
                    validateGguf(partial)
                    target.delete()
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                }
                preferences.edit().putString(KEY_MODEL_PATH, target.absolutePath).apply()
                hideProgress()
                loadModelForCurrentChat(target)
                Toast.makeText(this@MainActivity, "Modelo instalado. Agora o chat funciona offline.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                partial.delete()
                hideProgress()
                setBusyState(false, "Download não concluído")
                showError("Não foi possível baixar o modelo. Verifique a internet e o espaço livre.", error)
            } finally {
                downloading = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            setBusyState(true, "Validando arquivo GGUF…")
            showProgress("Copiando modelo para o armazenamento privado…", 0)
            val target = File(modelsDirectory(), "modelo-importado.gguf")
            val partial = File(modelsDirectory(), "modelo-importado.gguf.part")
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        GgufMetadataReader.create().readStructuredMetadata(inputStream)
                    } ?: error("Não foi possível abrir o arquivo")

                    partial.delete()
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(1024 * 256)
                            var copied = 0L
                            while (true) {
                                val read = inputStream.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (copied % (8L * 1024 * 1024) < buffer.size) {
                                    withContext(Dispatchers.Main) {
                                        showProgress("Copiados ${formatBytes(copied)}", 0)
                                    }
                                }
                            }
                        }
                    } ?: error("Falha ao copiar o arquivo")
                    validateGguf(partial)
                    target.delete()
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                }
                preferences.edit().putString(KEY_MODEL_PATH, target.absolutePath).apply()
                hideProgress()
                loadModelForCurrentChat(target)
                Toast.makeText(this@MainActivity, "Modelo importado com sucesso.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                partial.delete()
                hideProgress()
                setBusyState(false, "Arquivo GGUF inválido ou incompatível")
                showError("Não foi possível importar esse modelo.", error)
            }
        }
    }

    private fun validateGguf(file: File) {
        require(file.length() > 1_000_000) { "Arquivo pequeno demais" }
        FileInputStream(file).use { stream ->
            GgufMetadataReader.create().readStructuredMetadata(stream)
        }
    }

    private fun reloadCurrentModel() {
        selectedModelFile()?.let { model ->
            lifecycleScope.launch { loadModelForCurrentChat(model) }
        } ?: showModelSetupDialog()
    }

    private suspend fun loadModelForCurrentChat(model: File) {
        val localEngine = engine ?: error("Motor ainda não inicializado")
        generationJob?.cancelAndJoin()
        modelReady = false
        setBusyState(true, "Carregando ${model.name}…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            withContext(Dispatchers.Default) {
                awaitEngineStable(localEngine)
                unloadModelIfNeeded()
                localEngine.loadModel(model.absolutePath)
                localEngine.setSystemPrompt(buildSystemPrompt())
            }
            modelReady = true
            setBusyState(false, "100% local • ${AssistantPresets.all[currentAssistantIndex()].name}")
            refreshMessages()
        } catch (error: Exception) {
            modelReady = false
            setBusyState(false, "Modelo incompatível ou memória insuficiente")
            showError("O modelo não pôde ser carregado neste aparelho.", error)
        } finally {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private suspend fun unloadModelIfNeeded() {
        val localEngine = engine ?: return
        repeat(300) {
            when (localEngine.state.value) {
                is InferenceEngine.State.Initialized -> return
                is InferenceEngine.State.ModelReady,
                is InferenceEngine.State.Error -> {
                    localEngine.cleanUp()
                    return
                }
                else -> delay(50)
            }
        }
        error("O motor local não ficou disponível para recarga")
    }

    private fun buildSystemPrompt(): String {
        val preset = AssistantPresets.all[currentAssistantIndex()]
        val previousContext = currentMessages
            .filter { it.content.isNotBlank() }
            .takeLast(10)
            .joinToString("\n") { message ->
                (if (message.isUser) "Usuário" else "Assistente") + ": " + message.content.take(900)
            }
            .take(6_000)

        return buildString {
            append(preset.systemPrompt)
            append("\n\nVocê está executando totalmente dentro de um celular, sem acesso automático à internet. ")
            append("Não finja ter pesquisado notícias, preços, leis ou informações atuais. ")
            append("Não revele raciocínio interno; forneça apenas a resposta útil.\n/no_think")
            if (previousContext.isNotBlank()) {
                append("\n\nContexto de conversa salvo anteriormente:\n")
                append(previousContext)
            }
        }
    }

    private fun sendOrStop() {
        val activeJob = generationJob
        if (activeJob?.isActive == true) {
            activeJob.cancel()
            return
        }
        if (busy || downloading) return
        if (!modelReady) {
            showModelSetupDialog()
            return
        }
        val userText = input.text.toString().trim()
        if (userText.isEmpty()) return
        input.text.clear()

        val userMessage = Message(content = userText, isUser = true)
        val assistantMessage = Message(content = "", isUser = false)
        currentMessages.add(userMessage)
        currentMessages.add(assistantMessage)
        messageAdapter.notifyItemRangeInserted(currentMessages.size - 2, 2)
        scrollToEnd()
        saveCurrentSession()

        generationJob = lifecycleScope.launch {
            setGenerating(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                val localEngine = engine ?: error("Motor indisponível")
                localEngine.sendUserPrompt("$userText\n/no_think", RESPONSE_TOKEN_LIMIT)
                    .collect { token ->
                        assistantMessage.content += token
                        messageAdapter.notifyItemChanged(currentMessages.lastIndex)
                        scrollToEnd()
                    }
                assistantMessage.content = cleanResponse(assistantMessage.content)
                if (assistantMessage.content.isBlank()) {
                    assistantMessage.content = "Não consegui gerar uma resposta. Tente reformular a pergunta."
                }
            } catch (_: CancellationException) {
                assistantMessage.content = cleanResponse(assistantMessage.content)
                if (assistantMessage.content.isBlank()) assistantMessage.content = "[Resposta interrompida]"
            } catch (error: Exception) {
                assistantMessage.content = "Erro ao gerar resposta: ${error.message ?: "falha desconhecida"}"
            } finally {
                messageAdapter.notifyItemChanged(currentMessages.lastIndex)
                saveCurrentSession()
                setGenerating(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun cleanResponse(raw: String): String = raw
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace("<|im_end|>", "")
        .replace("<|endoftext|>", "")
        .trim()

    private fun setGenerating(generating: Boolean) {
        busy = generating
        input.isEnabled = !generating
        sendButton.text = if (generating) "■" else "➤"
        sendButton.contentDescription = if (generating) "Interromper resposta" else "Enviar"
        statusText.text = if (generating) "Pensando somente neste aparelho…" else {
            "100% local • ${AssistantPresets.all[currentAssistantIndex()].name}"
        }
    }

    private fun chooseAssistant() {
        if (busy) return
        val labels = AssistantPresets.all.map { "${it.icon} ${it.name}\n${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Escolher assistência")
            .setSingleChoiceItems(labels, currentAssistantIndex()) { dialog, selected ->
                dialog.dismiss()
                if (selected != currentAssistantIndex()) startNewChat(selected)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startNewChat(assistantIndex: Int) {
        if (busy) return
        saveCurrentSession()
        val session = createSession(assistantIndex)
        sessions.add(0, session)
        currentSessionId = session.id
        currentMessages.clear()
        currentMessages.addAll(session.messages.map { it.copy() })
        updateAssistantButton()
        refreshMessages()
        chatRepository.saveSessions(sessions, currentSessionId)
        selectedModelFile()?.let { model ->
            lifecycleScope.launch { loadModelForCurrentChat(model) }
        }
    }

    private fun createSession(assistantIndex: Int): ChatSession {
        val preset = AssistantPresets.all[assistantIndex.coerceIn(0, AssistantPresets.all.lastIndex)]
        return ChatSession(
            assistantIndex = assistantIndex,
            messages = mutableListOf(Message(content = preset.welcome, isUser = false))
        )
    }

    private fun showHistory() {
        if (busy) return
        saveCurrentSession()
        val ordered = sessions.sortedByDescending { it.updatedAt }
        val labels = ordered.map { session ->
            val preset = AssistantPresets.all[session.assistantIndex]
            "${preset.icon} ${session.title}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Conversas salvas neste aparelho")
            .setItems(labels) { _, position -> activateSession(ordered[position].id, reloadModel = true) }
            .setNeutralButton("Nova") { _, _ -> startNewChat(currentAssistantIndex()) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun activateSession(id: String, reloadModel: Boolean) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        if (::currentSessionId.isInitialized) saveCurrentSession()
        currentSessionId = session.id
        currentMessages.clear()
        currentMessages.addAll(session.messages.map { it.copy() })
        updateAssistantButton()
        if (::messageAdapter.isInitialized) refreshMessages()
        chatRepository.saveSessions(sessions, currentSessionId)
        if (reloadModel) {
            selectedModelFile()?.let { model ->
                lifecycleScope.launch { loadModelForCurrentChat(model) }
            }
        }
    }

    private fun currentAssistantIndex(): Int =
        sessions.firstOrNull { it.id == currentSessionId }?.assistantIndex ?: 0

    private fun saveCurrentSession() {
        if (!::currentSessionId.isInitialized) return
        val session = sessions.firstOrNull { it.id == currentSessionId } ?: return
        session.messages = currentMessages.map { it.copy() }.toMutableList()
        session.title = currentMessages.firstOrNull { it.isUser }?.content
            ?.replace("\n", " ")
            ?.take(45)
            ?.ifBlank { "Nova conversa" }
            ?: "Nova conversa"
        session.updatedAt = System.currentTimeMillis()
        chatRepository.saveSessions(sessions, currentSessionId)
    }

    private fun updateAssistantButton() {
        if (!::assistantButton.isInitialized) return
        val preset = AssistantPresets.all[currentAssistantIndex()]
        assistantButton.text = "${preset.icon} ${preset.name}"
    }

    private fun refreshMessages() {
        if (!::messageAdapter.isInitialized) return
        messageAdapter.notifyDataSetChanged()
        scrollToEnd()
    }

    private fun scrollToEnd() {
        if (currentMessages.isNotEmpty()) {
            messagesView.post { messagesView.scrollToPosition(currentMessages.lastIndex) }
        }
    }

    private fun showMessageActions(message: Message) {
        val actions = mutableListOf("Copiar", "Compartilhar")
        if (!message.isUser) actions += "Ouvir resposta"
        AlertDialog.Builder(this)
            .setTitle(if (message.isUser) "Mensagem" else "Resposta")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Copiar" -> copyText(message.content)
                    "Compartilhar" -> shareText(message.content)
                    "Ouvir resposta" -> speak(message.content)
                }
            }
            .show()
    }

    private fun showMoreMenu() {
        val options = arrayOf(
            "Compartilhar conversa",
            "Ouvir última resposta",
            "Apagar conversa atual",
            "Gerenciar inteligência local",
            "Privacidade e limitações"
        )
        AlertDialog.Builder(this)
            .setTitle("Opções")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareCurrentChat()
                    1 -> currentMessages.lastOrNull { !it.isUser }?.let { speak(it.content) }
                    2 -> confirmClearChat()
                    3 -> showModelMenu()
                    4 -> showPrivacyInfo()
                }
            }
            .show()
    }

    private fun shareCurrentChat() {
        val text = currentMessages.joinToString("\n\n") {
            (if (it.isUser) "Você" else "Assistente") + ": " + it.content
        }
        shareText(text)
    }

    private fun confirmClearChat() {
        AlertDialog.Builder(this)
            .setTitle("Apagar esta conversa?")
            .setMessage("As mensagens serão removidas somente deste aparelho.")
            .setPositiveButton("Apagar") { _, _ ->
                val session = sessions.firstOrNull { it.id == currentSessionId }
                val assistant = session?.assistantIndex ?: 0
                sessions.removeAll { it.id == currentSessionId }
                val replacement = createSession(assistant)
                sessions.add(0, replacement)
                currentSessionId = replacement.id
                currentMessages.clear()
                currentMessages.addAll(replacement.messages)
                refreshMessages()
                chatRepository.saveSessions(sessions, currentSessionId)
                selectedModelFile()?.let { model -> lifecycleScope.launch { loadModelForCurrentChat(model) } }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPrivacyInfo() {
        AlertDialog.Builder(this)
            .setTitle("Privacidade e realidade")
            .setMessage(
                "• O modelo roda no processador deste Android.\n" +
                    "• Conversas e modelo ficam no armazenamento privado do aplicativo.\n" +
                    "• Depois do download, o chat funciona sem internet.\n" +
                    "• O modelo local é pequeno e pode errar mais que serviços grandes na nuvem.\n" +
                    "• Ele não pesquisa notícias, preços, leis ou fatos atuais sozinho.\n" +
                    "• Não use respostas automáticas como diagnóstico médico, decisão jurídica ou orientação financeira definitiva."
            )
            .setPositiveButton("Entendi", null)
            .show()
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale sua mensagem")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Reconhecimento de voz não disponível.", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Resposta", text))
        Toast.makeText(this, "Texto copiado.", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Compartilhar"
            )
        )
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
            tts?.setSpeechRate(0.95f)
        }
    }

    private fun setBusyState(isBusy: Boolean, status: String) {
        busy = isBusy
        statusText.text = status
        input.isEnabled = !isBusy
        sendButton.isEnabled = !isBusy || generationJob?.isActive == true
    }

    private fun showProgress(label: String, percent: Int) {
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.isIndeterminate = percent <= 0
        if (percent > 0) progressBar.progress = percent
        progressText.text = label
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
    }

    private fun showError(message: String, error: Exception) {
        AlertDialog.Builder(this)
            .setTitle("Não deu certo")
            .setMessage("$message\n\nDetalhe técnico: ${error.message ?: error.javaClass.simpleName}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes bytes"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        saveCurrentSession()
        super.onStop()
    }

    override fun onDestroy() {
        generationJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        engine?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCES_NAME = "vitao_assistente_local"
        private const val KEY_MODEL_PATH = "selected_model_path"
        private const val DEFAULT_MODEL_FILENAME = "Qwen3-0.6B-Q4_0.gguf"
        private const val DEFAULT_MODEL_URL =
            "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_0.gguf?download=true"
        private const val RESPONSE_TOKEN_LIMIT = 512
        private const val MINIMUM_FREE_STORAGE = 700L * 1024 * 1024
    }
}
