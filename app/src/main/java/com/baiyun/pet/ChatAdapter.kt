package com.baiyun.pet

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * 聊天气泡里的消息列表适配器。
 *
 * 只有一种 item 布局（[R.layout.item_chat_message]），靠代码切换左右与配色：
 * - 用户消息：整行靠右，隐藏小云朵，气泡蓝色；
 * - 白云消息：整行靠左，显示小云朵，气泡灰色。
 *
 * 直接持有外部传入的消息列表（引用），所以服务里 append 之后再 notifyItemInserted 即可。
 */
class ChatAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view.findViewById(R.id.rowMessage)
        val avatar: ImageView = view.findViewById(R.id.ivAvatar)
        val content: TextView = view.findViewById(R.id.tvMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.content.text = message.text

        if (message.isUser) {
            // 用户：靠右、蓝色、不要头像
            holder.row.gravity = Gravity.END
            holder.avatar.isVisible = false
            holder.content.setBackgroundResource(R.drawable.bg_msg_user)
        } else {
            // 白云：靠左、灰色、带小云朵
            holder.row.gravity = Gravity.START
            holder.avatar.isVisible = true
            holder.content.setBackgroundResource(R.drawable.bg_msg_pet)
        }
    }

    override fun getItemCount(): Int = messages.size
}
