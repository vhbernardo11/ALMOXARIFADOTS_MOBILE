package com.example.llama

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter(
    private val messages: List<Message>,
    private val onLongPress: (Message) -> Unit
) : RecyclerView.Adapter<MessageAdapter.MessageHolder>() {

    class MessageHolder(
        val container: FrameLayout,
        val bubble: TextView
    ) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        val context = parent.context
        val container = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val bubble = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(11), dp(14), dp(11))
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.84f).toInt()
            setTextIsSelectable(true)
            lineSpacingExtra = dp(2).toFloat()
        }
        container.addView(bubble)
        return MessageHolder(container, bubble)
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        val message = messages[position]
        holder.bubble.text = message.content
        holder.bubble.background = bubbleDrawable(
            if (message.isUser) Color.parseColor("#2563EB") else Color.parseColor("#1B2230")
        )
        holder.bubble.setOnLongClickListener {
            onLongPress(message)
            true
        }
        val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (message.isUser) Gravity.END else Gravity.START
        holder.bubble.layoutParams = params
        holder.bubble.contentDescription = if (message.isUser) {
            "Mensagem enviada: ${message.content}"
        } else {
            "Resposta do assistente: ${message.content}"
        }
    }

    override fun getItemCount(): Int = messages.size

    private fun bubbleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(color)
        setStroke(dp(1), Color.parseColor("#2D3748"))
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
