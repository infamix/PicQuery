package me.grey.picquery.data.dao

import androidx.room.*
import me.grey.picquery.data.model.Embedding

@Dao
interface EmbeddingDao {

    companion object {
        private const val tableName = "embedding"
    }

    @Query("SELECT * FROM $tableName")
    fun getAll(): List<Embedding>

    @Query("SELECT * FROM $tableName LIMIT :limit OFFSET :offset")
    fun getEmbeddingsPaginated(limit: Int, offset: Int): List<Embedding>

    @Query("SELECT COUNT(*) FROM $tableName")
    fun getTotalCount(): Long

    @Query("SELECT * FROM $tableName WHERE photo_id IN (:photoIds)")
    fun getAllByPhotoIds(photoIds: LongArray): List<Embedding>

    @Query(
        "SELECT * FROM $tableName WHERE album_id IS (:albumId)"
    )
    fun getAllByAlbumId(albumId: Long): List<Embedding>

    /**
     * Lightweight projection used to compute the "resume point" of an
     * interrupted indexing job: returns only the ids of photos that
     * already have an embedding in the given album, without loading
     * any embedding BLOB into memory.
     *
     * NOTE: query-only addition, the DB schema is untouched.
     */
    @Query(
        "SELECT photo_id FROM $tableName WHERE album_id IS (:albumId)"
    )
    fun getPhotoIdsByAlbumId(albumId: Long): List<Long>

    @Query(
        "SELECT * FROM $tableName WHERE album_id IN (:albumIds)"
    )
    fun getByAlbumIdList(albumIds: List<Long>): List<Embedding>

    @Query(
        "SELECT * FROM $tableName WHERE album_id IN (:albumIds) LIMIT :limit OFFSET :offset"
    )
    fun getByAlbumIdList(albumIds: List<Long>, limit: Int, offset: Int): List<Embedding>

    @Query(
        "DELETE FROM $tableName WHERE album_id=(:albumId)"
    )
    fun removeByAlbumId(albumId: Long): Unit

    @Upsert
    fun upsertAll(embeddings: List<Embedding>)

    @Delete
    fun delete(embedding: Embedding)

    @Delete
    fun deleteAll(embeddings: List<Embedding>)
}
