package asia.guojuice.yezigotify

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.FtsOptions
import androidx.room3.Fts5
import androidx.room3.PrimaryKey

@Fts5(
    contentEntity = MessageEntity::class,
    tokenizer = FtsOptions.TOKENIZER_TRIGRAM
)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,

    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "message") val message: String
)