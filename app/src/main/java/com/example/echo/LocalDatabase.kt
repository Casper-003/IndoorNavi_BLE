package com.example.echo

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ================= 1. 数据库表结构 (Entity) =================
// 专门用来存在硬盘里的格式，把 Point 和 Map 拍平，数据库更好消化
@Entity(tableName = "fingerprints")
data class ReferencePointEntity(
    @PrimaryKey val id: String,
    val x: Double,
    val y: Double,
    val fingerprintData: String // 把指纹 Map 转成 "mac1=rssi1;mac2=rssi2" 的长字符串存进去
)

// 🌟 新增：障碍物实体 (阻挡 A* 导航寻路的墙体或禁区)
@Entity(tableName = "obstacles")
data class ObstacleEntity(
    @PrimaryKey val id: String,
    val x: Double,
    val y: Double
)

// ================= 2. 类型转换器 (TypeConverter) =================
class Converters {
    @TypeConverter
    fun fromFingerprintMap(map: Map<String, Int>): String {
        return map.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    @TypeConverter
    fun toFingerprintMap(data: String): Map<String, Int> {
        if (data.isBlank()) return emptyMap()
        return data.split(";").mapNotNull {
            val pair = it.split("=")
            if (pair.size == 2) pair[0] to (pair[1].toIntOrNull() ?: -100) else null
        }.toMap()
    }
}

// ================= 数据模型映射 (Mapping) =================
fun ReferencePoint.toEntity(): ReferencePointEntity {
    return ReferencePointEntity(
        id = this.id,
        x = this.coordinate.x,
        y = this.coordinate.y,
        fingerprintData = Converters().fromFingerprintMap(this.fingerprint)
    )
}

fun ReferencePointEntity.toDomainModel(): ReferencePoint {
    return ReferencePoint(
        id = this.id,
        coordinate = Point(this.x, this.y),
        fingerprint = Converters().toFingerprintMap(this.fingerprintData)
    )
}

// ================= 3. 数据访问接口 (DAO) =================
@Dao
interface FingerprintDao {
    @Query("SELECT * FROM fingerprints")
    fun getAllFingerprintsStream(): Flow<List<ReferencePointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFingerprint(point: ReferencePointEntity)

    @Delete
    fun deleteFingerprint(point: ReferencePointEntity)

    @Query("DELETE FROM fingerprints")
    fun clearAll()
}

// 🌟 新增：障碍物数据访问接口
@Dao
interface ObstacleDao {
    @Query("SELECT * FROM obstacles")
    fun getAllObstaclesStream(): Flow<List<ObstacleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertObstacle(obstacle: ObstacleEntity)

    @Delete
    fun deleteObstacle(obstacle: ObstacleEntity)

    @Query("DELETE FROM obstacles")
    fun clearAllObstacles()
}

// ================= 4. 数据库引擎单例 =================
// 🌟 更新：加入 ObstacleEntity，提升 version 至 2
@Database(entities = [ReferencePointEntity::class, ObstacleEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun obstacleDao(): ObstacleDao // 🌟 暴露新的 DAO

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "indoor_navi_database" // 存放在手机内部沙盒的数据库文件名
                )
                    .fallbackToDestructiveMigration() // 🌟 升级版本时防止崩溃，开发期非常实用
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}