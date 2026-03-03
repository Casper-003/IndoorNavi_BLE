package com.example.indoornavi

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

// ================= 2. 类型转换器 (TypeConverter) =================
// 借用你之前在 CSV 里写的极其精妙的字符串解析逻辑
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
// 这两个扩展函数负责在“内存模型(ReferencePoint)”和“硬盘模型(Entity)”之间自动翻译
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
// ================= 3. 数据访问接口 (DAO) =================
// ================= 3. 数据访问接口 (DAO) =================
@Dao
interface FingerprintDao {
    @Query("SELECT * FROM fingerprints")
    fun getAllFingerprintsStream(): Flow<List<ReferencePointEntity>>

    // 🌟 终极杀招：直接删掉 suspend 关键字和所有的返回值！
    // 因为 ViewModel 已经在后台线程调用了它们，直接用同步函数是最安全、最不会引发编译冲突的写法！

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFingerprint(point: ReferencePointEntity)

    @Delete
    fun deleteFingerprint(point: ReferencePointEntity)

    @Query("DELETE FROM fingerprints")
    fun clearAll()
}
// ================= 4. 数据库引擎单例 =================
@Database(entities = [ReferencePointEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fingerprintDao(): FingerprintDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "indoor_navi_database" // 存放在手机内部沙盒的数据库文件名
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}