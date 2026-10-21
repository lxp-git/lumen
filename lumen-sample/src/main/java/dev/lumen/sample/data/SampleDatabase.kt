package dev.lumen.sample.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "favorites")
data class Favorite(
  @PrimaryKey val id: Int,
  val name: String,
  val types: String,
  val addedAt: Long,
)

@Dao
interface FavoriteDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(favorites: List<Favorite>)

  @Query("SELECT COUNT(*) FROM favorites")
  suspend fun count(): Int

  @Query("SELECT * FROM favorites ORDER BY addedAt DESC LIMIT :limit")
  suspend fun latest(limit: Int): List<Favorite>
}

/** Lives at databases/sample.db, so it shows up in `lumen.py db list`. */
@Database(entities = [Favorite::class], version = 1, exportSchema = false)
abstract class SampleDatabase : RoomDatabase() {
  abstract fun favorites(): FavoriteDao
}
