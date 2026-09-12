package com.azhidkov.mystuff

import com.google.firebase.functions.FirebaseFunctions

enum class DeletionTarget { Account, Household }

data class DeletionPreview(
    val target: DeletionTarget,
    val deletesHousehold: Boolean,
    val householdName: String? = null,
    val memberCount: Int = 0,
    val itemCount: Int = 0,
) {
    val requiresHouseholdName: Boolean
        get() = deletesHousehold
}

interface DeletionGateway {
    fun previewAccount(onResult: (Result<DeletionPreview>) -> Unit)
    fun requestAccount(
        confirmationHouseholdName: String?,
        onResult: (Result<Unit>) -> Unit,
    )
    fun previewHousehold(
        householdId: String,
        onResult: (Result<DeletionPreview>) -> Unit,
    )
    fun requestHousehold(
        householdId: String,
        confirmationHouseholdName: String,
        onResult: (Result<Unit>) -> Unit,
    )
}

internal object NoDeletionGateway : DeletionGateway {
    private fun unavailable() = Result.failure<Nothing>(
        UnsupportedOperationException("Deletion is unavailable."),
    )

    override fun previewAccount(onResult: (Result<DeletionPreview>) -> Unit) {
        onResult(unavailable())
    }

    override fun requestAccount(
        confirmationHouseholdName: String?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        onResult(unavailable())
    }

    override fun previewHousehold(
        householdId: String,
        onResult: (Result<DeletionPreview>) -> Unit,
    ) {
        onResult(unavailable())
    }

    override fun requestHousehold(
        householdId: String,
        confirmationHouseholdName: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        onResult(unavailable())
    }
}

class FirebaseDeletionGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTION_REGION),
) : DeletionGateway {
    override fun previewAccount(onResult: (Result<DeletionPreview>) -> Unit) {
        callPreview(PREVIEW_ACCOUNT_FUNCTION, emptyMap(), DeletionTarget.Account, onResult)
    }

    override fun requestAccount(
        confirmationHouseholdName: String?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        callRequest(
            REQUEST_ACCOUNT_FUNCTION,
            confirmationHouseholdName?.let { mapOf(HOUSEHOLD_NAME to it) }.orEmpty(),
            onResult,
        )
    }

    override fun previewHousehold(
        householdId: String,
        onResult: (Result<DeletionPreview>) -> Unit,
    ) {
        callPreview(
            PREVIEW_HOUSEHOLD_FUNCTION,
            mapOf(HOUSEHOLD_ID to householdId),
            DeletionTarget.Household,
            onResult,
        )
    }

    override fun requestHousehold(
        householdId: String,
        confirmationHouseholdName: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        callRequest(
            REQUEST_HOUSEHOLD_FUNCTION,
            mapOf(
                HOUSEHOLD_ID to householdId,
                HOUSEHOLD_NAME to confirmationHouseholdName,
            ),
            onResult,
        )
    }

    private fun callPreview(
        functionName: String,
        data: Map<String, Any>,
        target: DeletionTarget,
        onResult: (Result<DeletionPreview>) -> Unit,
    ) {
        functions.getHttpsCallable(functionName).call(data).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                onResult(Result.failure(task.exception ?: deletionFailure()))
                return@addOnCompleteListener
            }
            onResult(runCatching {
                val response = task.result?.data as? Map<*, *> ?: throw deletionFailure()
                val householdName = response[HOUSEHOLD_NAME] as? String
                DeletionPreview(
                    target = target,
                    deletesHousehold = target == DeletionTarget.Household ||
                        response[DELETES_HOUSEHOLD] == true,
                    householdName = householdName,
                    memberCount = (response[MEMBER_COUNT] as? Number)?.toInt() ?: 0,
                    itemCount = (response[ITEM_COUNT] as? Number)?.toInt() ?: 0,
                )
            })
        }
    }

    private fun callRequest(
        functionName: String,
        data: Map<String, Any>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        functions.getHttpsCallable(functionName).call(data).addOnCompleteListener { task ->
            onResult(
                if (task.isSuccessful) Result.success(Unit)
                else Result.failure(task.exception ?: deletionFailure()),
            )
        }
    }
}

private fun deletionFailure() = IllegalStateException("Deletion returned an invalid response.")

private const val FUNCTION_REGION = "australia-southeast1"
private const val PREVIEW_ACCOUNT_FUNCTION = "previewAccountDeletion"
private const val REQUEST_ACCOUNT_FUNCTION = "requestAccountDeletion"
private const val PREVIEW_HOUSEHOLD_FUNCTION = "previewHouseholdDeletion"
private const val REQUEST_HOUSEHOLD_FUNCTION = "requestHouseholdDeletion"
private const val HOUSEHOLD_ID = "householdId"
private const val HOUSEHOLD_NAME = "householdName"
private const val DELETES_HOUSEHOLD = "deletesHousehold"
private const val MEMBER_COUNT = "memberCount"
private const val ITEM_COUNT = "itemCount"
