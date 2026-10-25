package com.eirahoutmoss.nexyapi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StructureDao {
    @Query("SELECT * FROM structures ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<StructureEntity>>

    @Query("SELECT * FROM structures WHERE id = :id")
    fun observe(id: String): Flow<StructureEntity?>

    @Query("SELECT * FROM structures WHERE id = :id")
    suspend fun get(id: String): StructureEntity?

    @Query("SELECT COUNT(*) FROM structures")
    suspend fun count(): Int

    @Query("SELECT * FROM structures ORDER BY created_at ASC")
    suspend fun all(): List<StructureEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(s: StructureEntity)

    @Update
    suspend fun update(s: StructureEntity)

    @Query("DELETE FROM structures WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE structures SET photo_file = :photoFile, updated_at = :now WHERE id = :id")
    suspend fun setPhoto(id: String, photoFile: String?, now: Long)
}

/** Bir yapının modül başına son ölçümü ve ölçüm sayısı (ana ekran rozetleri ve karne için). */
data class ModuleLatest(
    val structureId: String,
    val moduleCode: String,
    val takenAt: Long,
    val summary: String,
    val count: Int,
)

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements WHERE structure_id = :structureId ORDER BY taken_at ASC, id ASC")
    suspend fun listForStructure(structureId: String): List<MeasurementEntity>

    @Query(
        """
        SELECT m.structure_id AS structureId, m.module_code AS moduleCode, m.taken_at AS takenAt,
               m.summary AS summary, c.cnt AS count
        FROM measurements m
        JOIN (SELECT structure_id, module_code, MAX(taken_at) AS mx, COUNT(*) AS cnt
              FROM measurements GROUP BY structure_id, module_code) c
          ON c.structure_id = m.structure_id AND c.module_code = m.module_code AND c.mx = m.taken_at
        """,
    )
    fun observeLatestPerModule(): Flow<List<ModuleLatest>>

    @Query(
        "SELECT * FROM measurements WHERE structure_id = :structureId AND module_code = :moduleCode " +
            "ORDER BY taken_at DESC, id DESC",
    )
    fun observeForModule(structureId: String, moduleCode: String): Flow<List<MeasurementEntity>>

    @Query(
        "SELECT DISTINCT subject FROM measurements WHERE structure_id = :structureId AND module_code = :moduleCode " +
            "AND subject IS NOT NULL AND subject != '' ORDER BY subject",
    )
    suspend fun subjects(structureId: String, moduleCode: String): List<String>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun get(id: String): MeasurementEntity?

    @Query("SELECT * FROM measurements ORDER BY taken_at ASC")
    suspend fun all(): List<MeasurementEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(m: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(r: ReportEntity)

    @Query("SELECT * FROM reports WHERE structure_id = :structureId ORDER BY generated_at DESC")
    fun observeForStructure(structureId: String): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE fingerprint = :fingerprint")
    suspend fun findByFingerprint(fingerprint: String): ReportEntity?

    /** Kısa parmak izi (ilk karakterler) ile arama. */
    @Query("SELECT * FROM reports WHERE fingerprint LIKE :prefix || '%' ORDER BY generated_at DESC LIMIT 5")
    suspend fun findByPrefix(prefix: String): List<ReportEntity>

    @Query("SELECT * FROM reports ORDER BY generated_at DESC")
    suspend fun all(): List<ReportEntity>
}
