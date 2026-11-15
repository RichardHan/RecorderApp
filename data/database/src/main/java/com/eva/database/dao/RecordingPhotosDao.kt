package com.eva.database.dao

import androidx.room3.*
import com.eva.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecordingPhotosDao {
	@Query("SELECT * FROM recording_photos WHERE owner=:owner ORDER BY startMs, createdAt")
	abstract fun observe(owner: String): Flow<List<RecordingPhotoEntity>>
	@Query("SELECT * FROM recording_photos WHERE recordingId=:id ORDER BY startMs, createdAt")
	abstract fun observeRecording(id: Long): Flow<List<RecordingPhotoEntity>>
	@Query("SELECT * FROM recording_photos")
	abstract fun observeAll(): Flow<List<RecordingPhotoEntity>>
	@Query("SELECT * FROM recording_photos")
	abstract suspend fun all(): List<RecordingPhotoEntity>
	@Query("SELECT COUNT(*) FROM recording_photos WHERE owner=:owner OR recordingId=:id")
	abstract suspend fun count(owner: String, id: Long?): Int
	@Insert abstract suspend fun insert(photo: RecordingPhotoEntity)
	@Query("DELETE FROM recording_photos WHERE id=:id") abstract suspend fun delete(id: String)
	@Query("UPDATE recording_photos SET caption=:caption WHERE id=:id") abstract suspend fun caption(id: String, caption: String)
	@Insert abstract suspend fun session(session: PhotoSessionEntity)
	@Query("SELECT * FROM photo_sessions WHERE owner=:owner") abstract suspend fun session(owner: String): PhotoSessionEntity?
	@Query("UPDATE recording_photos SET recordingId=:id WHERE owner=:owner") abstract suspend fun bindPhotos(owner: String, id: Long)
	@Query("UPDATE photo_sessions SET recordingId=:id WHERE owner=:owner") abstract suspend fun bindOwner(owner: String, id: Long)
	@Query("DELETE FROM recording_photos WHERE owner=:owner") abstract suspend fun deleteOwnerPhotos(owner: String)
	@Query("DELETE FROM photo_sessions WHERE owner=:owner") abstract suspend fun deleteOwner(owner: String)
	@Query("DELETE FROM recording_photos WHERE recordingId IS NULL") abstract suspend fun deleteDraftPhotos()
	@Query("DELETE FROM photo_sessions WHERE recordingId IS NULL") abstract suspend fun deleteDraftOwners()
	@Transaction open suspend fun discardDrafts() { deleteDraftPhotos(); deleteDraftOwners() }
	@Transaction open suspend fun bind(owner: String, id: Long) { bindOwner(owner, id); bindPhotos(owner, id) }
	@Transaction open suspend fun discard(owner: String) { deleteOwnerPhotos(owner); deleteOwner(owner) }
}
