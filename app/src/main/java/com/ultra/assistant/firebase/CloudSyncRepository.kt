package com.ultra.assistant.firebase

import android.content.Context
import androidx.annotation.Keep
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import com.ultra.assistant.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

@Keep
@IgnoreExtraProperties
data class SyncedMemory(
    @com.google.firebase.firestore.DocumentId val id: String = "",
    val userId: String = "",
    val category: String = "CONTEXT",
    val title: String = "",
    val content: String = "",
    val encrypted: Boolean = false,
    val expiresAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SyncedAutomation(
    @com.google.firebase.firestore.DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val description: String = "",
    val triggerType: String = "MANUAL_WORKFLOW",
    val actionTool: String = "open_app",
    val actionArgsJson: String = "{}",
    val enabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SyncedAuditLog(
    @com.google.firebase.firestore.DocumentId val id: String = "",
    val userId: String = "",
    val toolId: String = "",
    val riskLevel: String = "LOW",
    val status: String = "SUCCESS",
    val summary: String = "",
    val verified: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

class CloudSyncRepository(
    context: Context
) {
    private val appContext = context.applicationContext
    private val db: FirebaseFirestore? by lazy {
        runCatching {
            if (isFirebaseInitialized(appContext)) {
                FirebaseFirestore.getInstance(appContext.getString(R.string.firestore_database_id))
            } else {
                null
            }
        }.getOrNull()
    }
    private val auth: FirebaseAuth? by lazy {
        runCatching {
            if (isFirebaseInitialized(appContext)) Firebase.auth else null
        }.getOrNull()
    }

    private fun requireUserId(): String =
        auth?.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun sanitizeDocId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128).ifBlank { "item_${System.currentTimeMillis()}" }

    fun observeMemories(userId: String): Flow<List<SyncedMemory>> = flow {
        val firestore = db
        if (firestore == null || auth == null) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$userId/memories"
        emitAll(
            firestore.collection("users").document(userId).collection("memories")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SyncedMemory::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertMemory(
        docId: String,
        category: String,
        title: String,
        content: String,
        encrypted: Boolean,
        expiresAtMillis: Long
    ): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val safeId = sanitizeDocId(docId)
        val uid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Unauthenticated"))
        val path = "users/$uid/memories/$safeId"
        return try {
            val safeCategory = if (category in setOf("PREFERENCE", "CONTEXT", "CONVERSATION", "KNOWLEDGE")) category else "CONTEXT"
            val safeTitle = title.trim().take(120).ifBlank { "Memory" }
            val safeContent = content.trim().take(2000).ifBlank { "Empty" }
            val safeExpires = expiresAtMillis.coerceAtLeast(0L)
            val docRef = firestore.collection("users").document(uid).collection("memories").document(safeId)
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "category" to safeCategory,
                        "title" to safeTitle,
                        "content" to safeContent,
                        "encrypted" to encrypted,
                        "expiresAtMillis" to safeExpires,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "category" to safeCategory,
                        "title" to safeTitle,
                        "content" to safeContent,
                        "encrypted" to encrypted,
                        "expiresAtMillis" to safeExpires,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteMemory(docId: String): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val safeId = sanitizeDocId(docId)
        val uid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Unauthenticated"))
        val path = "users/$uid/memories/$safeId"
        return try {
            firestore.collection("users").document(uid).collection("memories").document(safeId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    fun observeAutomations(userId: String): Flow<List<SyncedAutomation>> = flow {
        val firestore = db
        if (firestore == null || auth == null) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$userId/automations"
        emitAll(
            firestore.collection("users").document(userId).collection("automations")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SyncedAutomation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertAutomation(
        docId: String,
        name: String,
        description: String,
        triggerType: String,
        actionTool: String,
        actionArgsJson: String,
        enabled: Boolean
    ): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val safeId = sanitizeDocId(docId)
        val uid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Unauthenticated"))
        val path = "users/$uid/automations/$safeId"
        return try {
            val safeTrigger = if (triggerType in setOf("VOICE_PHRASE", "SCHEDULED_REMINDER", "MANUAL_WORKFLOW")) triggerType else "MANUAL_WORKFLOW"
            val docRef = firestore.collection("users").document(uid).collection("automations").document(safeId)
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "name" to name.trim().take(100).ifBlank { "Rule" },
                        "description" to description.trim().take(500).ifBlank { "Automation" },
                        "triggerType" to safeTrigger,
                        "actionTool" to actionTool.trim().take(64).ifBlank { "open_app" },
                        "actionArgsJson" to actionArgsJson.take(1000),
                        "enabled" to enabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "name" to name.trim().take(100).ifBlank { "Rule" },
                        "description" to description.trim().take(500).ifBlank { "Automation" },
                        "triggerType" to safeTrigger,
                        "actionTool" to actionTool.trim().take(64).ifBlank { "open_app" },
                        "actionArgsJson" to actionArgsJson.take(1000),
                        "enabled" to enabled,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteAutomation(docId: String): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val safeId = sanitizeDocId(docId)
        val uid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Unauthenticated"))
        val path = "users/$uid/automations/$safeId"
        return try {
            firestore.collection("users").document(uid).collection("automations").document(safeId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun appendAuditLog(
        docId: String,
        toolId: String,
        riskLevel: String,
        status: String,
        summary: String,
        verified: Boolean
    ): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val safeId = sanitizeDocId(docId)
        val uid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Unauthenticated"))
        val path = "users/$uid/audit_logs/$safeId"
        return try {
            val safeRisk = if (riskLevel in setOf("LOW", "MEDIUM", "HIGH")) riskLevel else "LOW"
            val safeStatus = if (status in setOf("SUCCESS", "FAILED", "DENIED", "AWAITING_CONFIRMATION")) status else "SUCCESS"
            firestore.collection("users").document(uid).collection("audit_logs").document(safeId)
                .set(
                    mapOf(
                        "userId" to uid,
                        "toolId" to toolId.trim().take(64).ifBlank { "unknown" },
                        "riskLevel" to safeRisk,
                        "status" to safeStatus,
                        "summary" to summary.trim().take(500).ifBlank { "Action executed" },
                        "verified" to verified,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }
}
