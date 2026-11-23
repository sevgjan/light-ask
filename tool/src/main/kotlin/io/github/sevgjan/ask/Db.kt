package io.github.sevgjan.ask

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One conversation. [model] is a [Model] name; it can change mid-chat. */
@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val model: String,
    val created: Long,
    val updated: Long,
    @ColumnInfo(defaultValue = "0") val costMicros: Long = 0,
    @ColumnInfo(defaultValue = "0") val costPartial: Boolean = false,
)

/**
 * One message. [images] are file names in Ask's pictures folder, one per line: photos the user attached, or pictures
 * the assistant made. [sources] are the web pages an answer used, "title\turl" per line.
 */
@Entity(
    tableName = "messages",
    indices = [Index("chatId")],
    foreignKeys = [ForeignKey(entity = Chat::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val text: String,
    val images: String = "",
    val sources: String = "",
    /** [Status] name. */
    val status: String = Status.DONE.name,
    /** Why it failed, or a short remark ("cut short"). */
    val note: String? = null,
    /** The [Model] that answered (assistant messages). */
    val model: String? = null,
    /** What a made picture shows, as the model put it. */
    val imagePrompt: String? = null,
    val created: Long,
) {
    val isUser get() = role == USER
    val imageList: List<String> get() = images.lines().filter { it.isNotBlank() }
    val sourceList: List<Pair<String, String>> get() = sources.lines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab < 0) null else line.substring(0, tab) to line.substring(tab + 1)
    }
    val state: Status get() = Status.entries.firstOrNull { it.name == status } ?: Status.DONE

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

enum class Status {
    /** Being written right now. */
    PENDING,
    DONE,
    /** Stopped by the user (or by Ask closing) partway; what was written so far is kept. */
    STOPPED,
    /** The request failed; [Message.note] says why. */
    ERROR,
    /** The model declined. */
    REFUSED,
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updated DESC")
    fun chats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun chat(id: Long): Flow<Chat?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chatNow(id: Long): Chat?

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun chatCount(): Int

    @Insert
    suspend fun insert(chat: Chat): Long

    @Update
    suspend fun update(chat: Chat)

    @Query("UPDATE chats SET updated = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE chats SET costMicros = costMicros + :micros, costPartial = costPartial OR :partial WHERE id = :id")
    suspend fun addCost(id: Long, micros: Long, partial: Boolean)

    @Query("UPDATE chats SET costPartial = 1 WHERE id IN (SELECT chatId FROM messages WHERE status = 'PENDING')")
    suspend fun markInterruptedCosts()

    @Query("UPDATE chats SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE chats SET model = :model WHERE id = :id")
    suspend fun setModel(id: Long, model: String)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    fun messages(chatId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    suspend fun messagesNow(chatId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: Long): Message?

    /** The newest message of each chat, for the chat list. */
    @Query("SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages GROUP BY chatId)")
    fun newest(): Flow<List<Message>>

    @Insert
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Transaction
    suspend fun finish(message: Message, cost: ChatCost) {
        update(message)
        addCost(message.chatId, cost.micros, cost.partial)
    }

    /** Removes a message and everything after it in its chat (to ask again, or to change a question). */
    @Query("DELETE FROM messages WHERE chatId = :chatId AND id >= :fromId")
    suspend fun deleteFrom(chatId: Long, fromId: Long)

    /** Replies that were still being written when Ask was closed. */
    @Query("UPDATE messages SET status = 'STOPPED' WHERE status = 'PENDING'")
    suspend fun stopPending()

    /** Every picture file still in use, to tidy away the rest. */
    @Query("SELECT images FROM messages WHERE images != ''")
    suspend fun allImages(): List<String>

    /** What the user has written, newest first, for the keyboard's vocabulary. */
    @Query("SELECT text FROM messages WHERE role = 'user' AND text != '' ORDER BY id DESC LIMIT :limit")
    suspend fun userTexts(limit: Int = 3000): List<String>
}

@Database(entities = [Chat::class, Message::class], version = 2, exportSchema = false)
abstract class AskDb : RoomDatabase() {
    abstract fun chats(): ChatDao

    companion object {
        val costMigration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN costMicros INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN costPartial INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE chats SET costPartial = 1 WHERE id IN (SELECT chatId FROM messages WHERE role = 'assistant')")
            }
        }
    }
}
