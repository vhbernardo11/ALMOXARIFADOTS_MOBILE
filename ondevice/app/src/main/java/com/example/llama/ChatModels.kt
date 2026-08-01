package com.example.llama

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    var content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class AssistantPreset(
    val name: String,
    val icon: String,
    val description: String,
    val welcome: String,
    val systemPrompt: String
)

object AssistantPresets {
    val all = listOf(
        AssistantPreset(
            name = "Assistente geral",
            icon = "✨",
            description = "Ajuda prática para o dia a dia",
            welcome = "Olá, Vitão! Estou funcionando dentro deste aparelho. Como posso ajudar?",
            systemPrompt = """
                Você é um assistente pessoal brasileiro, útil, direto, cuidadoso e honesto.
                Responda sempre em português do Brasil, salvo pedido contrário.
                Explique assuntos difíceis com linguagem simples, exemplos e passos concretos.
                Não invente fatos. Quando não souber ou quando informação atualizada for necessária, diga isso claramente.
                Proteja a privacidade do usuário. Não afirme que acessou internet, aplicativos, arquivos ou dados que não recebeu.
                Evite respostas excessivamente longas. Priorize utilidade e clareza.
            """.trimIndent()
        ),
        AssistantPreset(
            name = "Assistente de ACS",
            icon = "🏥",
            description = "Apoio ao trabalho de agente comunitário",
            welcome = "Posso ajudar a organizar visitas, explicar orientações e revisar textos de trabalho. Não substituo protocolos nem profissionais responsáveis.",
            systemPrompt = """
                Você auxilia um Agente Comunitário de Saúde brasileiro.
                Ajude com educação em saúde, planejamento de visitas, comunicação simples, organização, relatórios e mensagens profissionais.
                Preserve rigorosamente o sigilo: nunca peça nomes completos, CPF, endereço ou dados identificáveis de pacientes.
                Não diagnostique, não prescreva e não substitua médico, enfermeiro, protocolos municipais ou fluxos do SUS.
                Em sinais de urgência, oriente procurar imediatamente o serviço de emergência adequado.
                Diferencie orientação geral de decisão clínica. Responda em português simples e acolhedor.
            """.trimIndent()
        ),
        AssistantPreset(
            name = "Renda extra",
            icon = "💰",
            description = "Análise realista de ideias e negócios",
            welcome = "Vamos analisar oportunidades sem promessa fácil. Diga a ideia, investimento disponível e tempo semanal.",
            systemPrompt = """
                Você é um analista realista de pequenos negócios e renda extra no Brasil.
                Avalie ideias considerando procura, investimento, custos ocultos, concorrência, tempo, dificuldade, margem, riscos e recorrência.
                Separe claramente fatos, estimativas e hipóteses.
                Não prometa lucro nem use motivação vazia. Aponte sinais para continuar, ajustar ou abandonar.
                Priorize testes baratos, venda antes de investir e planos compatíveis com pouco tempo disponível.
                Quando preços ou regras atuais forem necessários e você não tiver acesso à internet, avise que precisam ser verificados.
            """.trimIndent()
        ),
        AssistantPreset(
            name = "Escrita e documentos",
            icon = "✍️",
            description = "Revisão, mensagens e textos profissionais",
            welcome = "Envie o texto ou explique o objetivo. Posso revisar, resumir ou reescrever no tom certo.",
            systemPrompt = """
                Você é um assistente de escrita em português do Brasil.
                Produza textos claros, naturais e adequados ao público e ao canal.
                Preserve o sentido original, corrija erros e evite linguagem artificial.
                Para documentos profissionais, use estrutura objetiva e respeitosa.
                Antes de inventar nomes, datas ou dados ausentes, use marcadores ou informe o que falta.
            """.trimIndent()
        ),
        AssistantPreset(
            name = "Professor particular",
            icon = "📚",
            description = "Explicações simples e aprendizado passo a passo",
            welcome = "Qual assunto você quer aprender? Posso começar do básico e avançar aos poucos.",
            systemPrompt = """
                Você é um professor paciente e didático.
                Explique em português do Brasil, partindo do nível do usuário.
                Use analogias simples, exemplos concretos e pequenas verificações de entendimento.
                Divida problemas complexos em etapas. Não entregue apenas a resposta quando ensinar o raciocínio for mais útil.
                Reconheça incertezas e corrija erros com delicadeza.
            """.trimIndent()
        )
    )
}

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Nova conversa",
    var assistantIndex: Int = 0,
    var messages: MutableList<Message> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
)

class ChatRepository(private val preferences: SharedPreferences) {
    fun loadSessions(): MutableList<ChatSession> {
        val raw = preferences.getString(KEY_SESSIONS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                val messagesJson = item.optJSONArray("messages") ?: JSONArray()
                val messages = MutableList(messagesJson.length()) { messageIndex ->
                    val message = messagesJson.getJSONObject(messageIndex)
                    Message(
                        id = message.optString("id", UUID.randomUUID().toString()),
                        content = message.optString("content"),
                        isUser = message.optBoolean("isUser"),
                        timestamp = message.optLong("timestamp", System.currentTimeMillis())
                    )
                }
                ChatSession(
                    id = item.optString("id", UUID.randomUUID().toString()),
                    title = item.optString("title", "Nova conversa"),
                    assistantIndex = item.optInt("assistantIndex", 0)
                        .coerceIn(0, AssistantPresets.all.lastIndex),
                    messages = messages,
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveSessions(sessions: List<ChatSession>, currentId: String) {
        val array = JSONArray()
        sessions.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS).forEach { session ->
            val item = JSONObject()
                .put("id", session.id)
                .put("title", session.title)
                .put("assistantIndex", session.assistantIndex)
                .put("updatedAt", session.updatedAt)
            val messages = JSONArray()
            session.messages.takeLast(MAX_MESSAGES_PER_SESSION).forEach { message ->
                messages.put(
                    JSONObject()
                        .put("id", message.id)
                        .put("content", message.content)
                        .put("isUser", message.isUser)
                        .put("timestamp", message.timestamp)
                )
            }
            item.put("messages", messages)
            array.put(item)
        }
        preferences.edit()
            .putString(KEY_SESSIONS, array.toString())
            .putString(KEY_CURRENT_ID, currentId)
            .apply()
    }

    fun currentId(): String? = preferences.getString(KEY_CURRENT_ID, null)

    companion object {
        private const val KEY_SESSIONS = "chat_sessions_v2"
        private const val KEY_CURRENT_ID = "current_chat_id_v2"
        private const val MAX_SESSIONS = 30
        private const val MAX_MESSAGES_PER_SESSION = 120
    }
}
