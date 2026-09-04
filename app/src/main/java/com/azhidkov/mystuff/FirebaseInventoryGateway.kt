package com.azhidkov.mystuff

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import java.time.Instant
import java.util.UUID

class FirebaseInventoryGateway internal constructor(
    private val store: InventoryDocumentStore,
    private val photoStore: InventoryPhotoStore,
    private val attachmentGateway: ItemAttachmentGateway,
    private val uploadFailureRegistry: AttachmentUploadFailureRegistry =
        processAttachmentUploadFailures,
    private val itemMoveService: ItemMoveService? = null,
    private val itemDeletionService: ItemDeletionService? = null,
) : InventoryGateway {
    constructor() : this(
        store = FirestoreInventoryDocumentStore(),
        photoStore = firebaseInventoryPhotoStore(),
        attachmentGateway = FirebaseItemAttachmentGateway(),
    )

    override fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription = store.observeItems(household.id) { result ->
        onResult(
            result.mapCatching { documents ->
                Inventory.from(
                    household = household,
                    items = documents.map { it.toItem(household.id) },
                )
            },
        )
    }

    override fun newItemId(householdId: String): String = store.newItemId(householdId)

    override fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photos: List<ItemPhoto>,
        onResult: (Result<Item>) -> Unit,
    ) {
        createItemWithPhotos(
            householdId = householdId,
            parentItemId = parentItemId,
            creator = creator,
            details = details,
            photos = photos,
            onResult = onResult,
        )
    }

    private fun createItemWithPhotos(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photos: List<ItemPhoto>,
        onResult: (Result<Item>) -> Unit,
    ) {
        details.validationFailure()?.let { error ->
            onResult(Result.failure(IllegalArgumentException(error)))
            return
        }
        val itemId = newItemId(householdId)
        val photoPlans = photos.map { photo ->
            AttachmentPhotoPlan(
                photo = photo,
                photoPlan = newPhotoPlan(householdId, itemId),
            )
        }
        val firstPhotoPlan = photoPlans.firstOrNull()
        createItemDocument(
            householdId = householdId,
            parentItemId = parentItemId,
            creator = creator,
            itemId = itemId,
            details = details,
            photoAttachmentId = null,
            photoLocations = null,
        ) { result ->
            val item = result.getOrNull()
            if (
                item == null ||
                firstPhotoPlan == null
            ) {
                onResult(result)
                return@createItemDocument
            }
            createPhotoAttachments(
                householdId = householdId,
                item = item,
                photoPlans = photoPlans,
                onResult = onResult,
            ) { createdPlans ->
                projectCreatedPhoto(
                    householdId = householdId,
                    item = item,
                    photoPlan = createdPlans.firstOrNull()?.photoPlan
                        ?: requireNotNull(firstPhotoPlan).photoPlan,
                    updater = creator,
                    photo = requireNotNull(firstPhotoPlan).photo,
                    creationOrder = createdPlans.firstOrNull()?.creationOrder,
                    additionalPhotos = createdPlans.drop(1),
                    onResult = onResult,
                )
            }
        }
    }

    private fun createPhotoAttachments(
        householdId: String,
        item: Item,
        photoPlans: List<AttachmentPhotoPlan>,
        onResult: (Result<Item>) -> Unit,
        onCreated: (List<AttachmentPhotoPlan>) -> Unit,
    ) {
        val gateway = requireNotNull(attachmentGateway)

        fun createNext(
            index: Int,
            created: List<AttachmentPhotoPlan>,
        ) {
            if (index == photoPlans.size) {
                onCreated(created)
                return
            }
            val plan = photoPlans[index]
            gateway.createInOrder(
                household = householdFor(householdId),
                item = item,
                attachmentId = requireNotNull(plan.photoPlan.attachmentId),
                creationOrder = index.toLong(),
                contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
                displayUrl = requireNotNull(plan.photoPlan.revision).locations.full,
            ) { result ->
                result.onSuccess {
                    createNext(index + 1, created + plan.copy(creationOrder = index.toLong()))
                }.onFailure { failure ->
                    deletePhotoAttachments(householdId, item, created) {
                        onResult(Result.failure(failure))
                    }
                }
            }
        }

        createNext(0, emptyList())
    }

    private fun deletePhotoAttachments(
        householdId: String,
        item: Item,
        photoPlans: List<AttachmentPhotoPlan>,
        onComplete: () -> Unit,
    ) {
        if (photoPlans.isEmpty()) {
            onComplete()
            return
        }
        val gateway = attachmentGateway
        fun deleteNext(index: Int) {
            if (index == photoPlans.size) {
                onComplete()
                return
            }
            val plan = photoPlans[index]
            gateway.delete(
                household = householdFor(householdId),
                item = item,
                attachment = projectedAttachment(
                    item,
                    requireNotNull(plan.photoPlan.attachmentId),
                ),
            ) { deleteNext(index + 1) }
        }
        deleteNext(0)
    }

    override fun updateItemWithAttachments(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        details: ItemDetails,
        additionalPhotos: List<ItemPhoto>,
        existingAttachments: List<ItemAttachment>,
        attachmentToDelete: ItemAttachment?,
        onResult: (Result<Item>) -> Unit,
    ) {
        details.validationFailure()?.let { error ->
            onResult(Result.failure(IllegalArgumentException(error)))
            return
        }

        val additionalPlans = additionalPhotos.map { photo ->
            AttachmentPhotoPlan(
                photo = photo,
                photoPlan = newPhotoPlan(householdId, item.id),
            )
        }
        val firstNewPhotoPlan = additionalPlans.firstOrNull()
        val promotedAttachment = attachmentToDelete
            ?.takeIf { it.id == item.photoAttachmentId }
            ?.let { deleted ->
                existingAttachments
                    .filterNot { it.id == deleted.id }
                    .minWithOrNull(
                        compareBy<ItemAttachment>(
                            { it.creationOrder ?: Long.MAX_VALUE },
                            ItemAttachment::createdAt,
                        ),
                    )
            }
        val promotedPlan = promotedAttachment?.let { promoted ->
            val revision = photoStore.newAttachmentRevision(householdId, item.id, promoted.id)
            ItemPhotoUpdatePlan(
                attachmentId = promoted.id,
                full = promoted.displayUrl,
                thumbnail = revision.locations.thumbnail,
                revision = null,
            )
        }
        val projectedNewPlan = when {
            attachmentToDelete != null && firstNewPhotoPlan != null -> firstNewPhotoPlan
            item.photoAttachmentId == null -> firstNewPhotoPlan
            else -> null
        }
        val projectedPlan = projectedNewPlan?.photoPlan ?: promotedPlan
        val plansToCreate = additionalPlans
        val creationOrderStart = existingAttachments
            .takeIf { it.isNotEmpty() && it.all { attachment -> attachment.creationOrder != null } }
            ?.maxOf { requireNotNull(it.creationOrder) }
            ?.plus(1)
        val projected = item.copy(
            name = details.name,
            description = details.description,
            tags = details.tags,
            webUrl = details.webUrl,
            photoAttachmentId = projectedPlan?.attachmentId
                ?: if (projectedPlan == null && attachmentToDelete == null) {
                    item.photoAttachmentId
                } else {
                    null
                },
            photoUrl = projectedPlan?.full
                ?: if (projectedPlan == null && attachmentToDelete == null) {
                    item.photoUrl
                } else {
                    null
                },
            photoThumbnailUrl = projectedPlan?.thumbnail
                ?: if (projectedPlan == null && attachmentToDelete == null) {
                    item.photoThumbnailUrl
                } else {
                    null
                },
        )
        val data = mapOf(
            NAME to projected.name,
            PHOTO_ATTACHMENT_ID to projected.photoAttachmentId,
            PHOTO_URL to projected.photoUrl,
            PHOTO_THUMBNAIL_URL to projected.photoThumbnailUrl,
            DESCRIPTION to projected.description,
            TAGS to projected.tags,
            WEB_URL to projected.webUrl,
            UPDATED_AT to store.serverTimestamp,
            UPDATED_BY_ID to updater.id,
            UPDATED_BY_DISPLAY_NAME to updater.attributionDisplayName(),
        )
        createAdditionalPhotoAttachments(
            householdId = householdId,
            item = item,
            plans = plansToCreate,
            creationOrderStart = creationOrderStart,
            onFailure = { failure, created ->
                deletePhotoAttachments(householdId, item, created) {
                    onResult(Result.failure(failure))
                }
            },
        ) { created ->
            store.updateItem(householdId, item.id, data) { result ->
                result.onFailure { failure ->
                    deletePhotoAttachments(householdId, item, created) {
                        onResult(Result.failure(failure))
                    }
                }.onSuccess {
                    uploadCreatedPhotosAndComplete(
                        householdId = householdId,
                        result = Result.success(projected),
                        item = item,
                        updater = updater,
                        firstPhotoPlan = created.firstOrNull {
                            it.photoPlan.attachmentId == projectedNewPlan?.photoPlan?.attachmentId
                        },
                        additionalPhotos = created.filterNot {
                            it.photoPlan.attachmentId == projectedNewPlan?.photoPlan?.attachmentId
                        },
                        onResult = { saved ->
                            val shouldDeleteOldAttachment = attachmentToDelete != null
                            if (!shouldDeleteOldAttachment) {
                                onResult(saved)
                                return@uploadCreatedPhotosAndComplete
                            }
                            requireNotNull(attachmentGateway).delete(
                                household = householdFor(householdId),
                                item = item,
                                attachment = requireNotNull(attachmentToDelete),
                            ) { deleteResult ->
                                deleteResult.onSuccess {
                                    if (projected.photoAttachmentId == null) {
                                        deleteStoredPhoto(item)
                                    }
                                    onResult(saved)
                                }.onFailure { onResult(Result.failure(it)) }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun moveItem(
        householdId: String,
        item: Item,
        newParentItemId: String,
        updater: AuthenticatedIdentity,
        onResult: (Result<Item>) -> Unit,
    ) {
        // The callable derives attribution from the authenticated Firebase user;
        // updater remains part of the shared gateway contract for client writes.
        if (item.parentItemId == null || item.id == newParentItemId) {
            onResult(Result.failure(InvalidItemMoveException("The Item move is invalid.")))
            return
        }
        val moved = item.copy(parentItemId = newParentItemId)
        (itemMoveService ?: FirebaseItemMoveService()).move(
            householdId = householdId,
            itemId = item.id,
            newParentItemId = newParentItemId,
            onResult = { result ->
                onResult(result.map { moved })
            },
        )
    }

    override fun deleteItem(
        householdId: String,
        item: Item,
        onResult: (Result<Unit>) -> Unit,
    ) {
        (itemDeletionService ?: FirebaseItemDeletionService()).delete(
            householdId = householdId,
            itemId = item.id,
            onResult = onResult,
        )
    }

    override fun reorderItems(
        householdId: String,
        parentItemId: String,
        orderedItems: List<Item>,
        updater: AuthenticatedIdentity,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (
            orderedItems.isEmpty() ||
            orderedItems.distinctBy(Item::id).size != orderedItems.size ||
            orderedItems.any { it.parentItemId != parentItemId }
        ) {
            onResult(Result.failure(IllegalArgumentException("The Item order is invalid.")))
            return
        }
        store.reorderItems(
            householdId = householdId,
            orderedItemIds = orderedItems.map(Item::id),
            updater = updater,
            onResult = onResult,
        )
    }

    override fun designateItemPhoto(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        attachment: ItemAttachment,
        onResult: (Result<Item>) -> Unit,
    ) {
        if (item.parentItemId == null || attachment.itemId != item.id) {
            onResult(Result.failure(IllegalArgumentException("The Item Photo designation is invalid.")))
            return
        }
        val revision = photoStore.newAttachmentRevision(householdId, item.id, attachment.id)
        val updated = item.copy(
            photoAttachmentId = attachment.id,
            photoUrl = attachment.displayUrl,
            photoThumbnailUrl = revision.locations.thumbnail,
        )
        store.updateItem(
            householdId = householdId,
            itemId = item.id,
            data = photoProjectionData(updated, updater),
        ) { result ->
            result.onFailure { onResult(Result.failure(it)) }
                .onSuccess {
                    if (item.photoAttachmentId != attachment.id) {
                        runCatching {
                            photoStore.deleteInBackground(
                                StoredItemPhotoLocations(
                                    full = null,
                                    thumbnail = item.photoThumbnailUrl,
                                ),
                            )
                        }
                    }
                    runCatching {
                        photoStore.generateAttachmentThumbnailInBackground(
                            revision = revision,
                            sourceLocation = attachment.displayUrl,
                        )
                    }
                    onResult(Result.success(updated))
                }
        }
    }

    override fun deleteItemAttachment(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        attachment: ItemAttachment,
        remainingAttachments: List<ItemAttachment>,
        onResult: (Result<Item>) -> Unit,
    ) {
        if (item.parentItemId == null || attachment.itemId != item.id) {
            onResult(Result.failure(IllegalArgumentException("The Item Attachment deletion is invalid.")))
            return
        }
        val deletingItemPhoto = item.photoAttachmentId == attachment.id
        val promoted = if (deletingItemPhoto) {
            remainingAttachments.minWithOrNull(
                compareBy<ItemAttachment>({ it.creationOrder ?: Long.MAX_VALUE }, ItemAttachment::createdAt),
            )
        } else {
            null
        }
        val updated = if (deletingItemPhoto) {
            item.copy(
                photoAttachmentId = promoted?.id,
                photoUrl = promoted?.displayUrl,
                photoThumbnailUrl = promoted?.let {
                    photoStore.newAttachmentRevision(householdId, item.id, it.id).locations.thumbnail
                },
            )
        } else {
            item
        }

        fun removeRecordAndFiles() {
            attachmentGateway.delete(
                household = householdFor(householdId),
                item = item,
                attachment = attachment,
            ) { result ->
                result.onFailure { onResult(Result.failure(it)) }
                    .onSuccess {
                        runCatching {
                            photoStore.deleteInBackground(
                                StoredItemPhotoLocations(
                                    full = attachment.displayUrl,
                                    thumbnail = item.photoThumbnailUrl.takeIf { deletingItemPhoto },
                                ),
                            )
                        }
                        promoted?.let { next ->
                            runCatching {
                                photoStore.generateAttachmentThumbnailInBackground(
                                    revision = photoStore.newAttachmentRevision(
                                        householdId,
                                        item.id,
                                        next.id,
                                    ),
                                    sourceLocation = next.displayUrl,
                                )
                            }
                        }
                        onResult(Result.success(updated))
                    }
            }
        }

        if (!deletingItemPhoto) {
            removeRecordAndFiles()
            return
        }
        store.updateItem(
            householdId = householdId,
            itemId = item.id,
            data = photoProjectionData(updated, updater),
        ) { result ->
            result.onFailure { onResult(Result.failure(it)) }
                .onSuccess { removeRecordAndFiles() }
        }
    }

    private fun createAdditionalPhotoAttachments(
        householdId: String,
        item: Item,
        plans: List<AttachmentPhotoPlan>,
        creationOrderStart: Long?,
        onFailure: (Throwable, List<AttachmentPhotoPlan>) -> Unit,
        onCreated: (List<AttachmentPhotoPlan>) -> Unit,
    ) {
        val gateway = requireNotNull(attachmentGateway)

        fun createNext(index: Int, created: List<AttachmentPhotoPlan>) {
            if (index == plans.size) {
                onCreated(created)
                return
            }
            val plan = plans[index]
            val create = if (creationOrderStart == null) {
                { callback: (Result<ItemAttachment>) -> Unit ->
                    gateway.create(
                        household = householdFor(householdId),
                        item = item,
                        attachmentId = requireNotNull(plan.photoPlan.attachmentId),
                        contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
                        displayUrl = requireNotNull(plan.photoPlan.revision).locations.full,
                        onResult = callback,
                    )
                }
            } else {
                { callback: (Result<ItemAttachment>) -> Unit ->
                    gateway.createInOrder(
                        household = householdFor(householdId),
                        item = item,
                        attachmentId = requireNotNull(plan.photoPlan.attachmentId),
                        creationOrder = creationOrderStart + index,
                        contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
                        displayUrl = requireNotNull(plan.photoPlan.revision).locations.full,
                        onResult = callback,
                    )
                }
            }
            create { result ->
                result.onSuccess {
                    createNext(
                        index + 1,
                        created + plan.copy(
                            creationOrder = creationOrderStart?.plus(index),
                        ),
                    )
                }.onFailure { failure -> onFailure(failure, created) }
            }
        }

        createNext(0, emptyList())
    }

    private fun newPhotoPlan(householdId: String, itemId: String): ItemPhotoUpdatePlan {
        val attachmentId = attachmentGateway.newAttachmentId(householdId, itemId)
        val revision = photoStore.newAttachmentRevision(householdId, itemId, attachmentId)
        return ItemPhotoUpdatePlan(
            attachmentId = attachmentId,
            full = revision.locations.full,
            thumbnail = revision.locations.thumbnail,
            revision = revision,
        )
    }

    private fun projectCreatedPhoto(
        householdId: String,
        item: Item,
        photoPlan: ItemPhotoUpdatePlan,
        updater: AuthenticatedIdentity,
        photo: ItemPhoto,
        creationOrder: Long? = null,
        additionalPhotos: List<AttachmentPhotoPlan> = emptyList(),
        onResult: (Result<Item>) -> Unit,
    ) {
        val projected = item.copy(
            photoAttachmentId = photoPlan.attachmentId,
            photoUrl = photoPlan.full,
            photoThumbnailUrl = photoPlan.thumbnail,
        )
        store.updateItem(
            householdId = householdId,
            itemId = item.id,
            data = mapOf(
                PHOTO_ATTACHMENT_ID to projected.photoAttachmentId,
                PHOTO_URL to projected.photoUrl,
                PHOTO_THUMBNAIL_URL to projected.photoThumbnailUrl,
                UPDATED_AT to store.serverTimestamp,
                UPDATED_BY_ID to updater.id,
                UPDATED_BY_DISPLAY_NAME to updater.attributionDisplayName(),
            ),
        ) { result ->
            result.onSuccess {
                uploadCreatedPhotosAndComplete(
                    householdId = householdId,
                    result = Result.success(projected),
                    item = item,
                    updater = updater,
                    firstPhotoPlan = AttachmentPhotoPlan(photo, photoPlan, creationOrder),
                    additionalPhotos = additionalPhotos,
                    onResult = onResult,
                )
            }.onFailure { failure ->
                deletePhotoAttachments(
                    householdId = householdId,
                    item = item,
                    photoPlans = listOf(AttachmentPhotoPlan(photo, photoPlan)) + additionalPhotos,
                ) {
                    onResult(Result.failure(failure))
                }
            }
        }
    }

    private fun uploadCreatedPhotosAndComplete(
        householdId: String,
        result: Result<Item>,
        item: Item,
        updater: AuthenticatedIdentity,
        firstPhotoPlan: AttachmentPhotoPlan?,
        additionalPhotos: List<AttachmentPhotoPlan>,
        onResult: (Result<Item>) -> Unit,
    ) {
        try {
            firstPhotoPlan?.let { plan ->
                enqueueAttachmentUpload(
                    householdId = householdId,
                    item = item,
                    updater = updater,
                    plan = plan,
                    projected = plan === firstPhotoPlan,
                    uploadThumbnail = true,
                )
            }
            additionalPhotos.forEach { plan ->
                enqueueAttachmentUpload(
                    householdId = householdId,
                    item = item,
                    updater = updater,
                    plan = plan,
                    projected = false,
                    uploadThumbnail = false,
                )
            }
        } finally {
            onResult(result)
        }
    }

    private fun enqueueAttachmentUpload(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        plan: AttachmentPhotoPlan,
        projected: Boolean,
        uploadThumbnail: Boolean,
    ) {
        val revision = plan.photoPlan.revision ?: return
        val attachmentId = requireNotNull(plan.photoPlan.attachmentId)

        val failure = AttachmentUploadFailure(
            id = attachmentId,
            householdId = householdId,
            itemId = item.id,
            attachmentId = attachmentId,
            originatingMemberId = updater.id,
            displayStoragePath = revision.fullStoragePath,
            thumbnailStoragePath = revision.thumbnailStoragePath,
        )
        val sourceUris = if (uploadThumbnail) {
            listOf(plan.photo.uri, plan.photo.thumbnailUri)
        } else {
            listOf(plan.photo.uri)
        }
        uploadFailureRegistry.prepare(
            failure = failure,
            sourceUris = sourceUris,
            retry = {
                republishAttachmentUpload(
                    householdId = householdId,
                    item = item,
                    updater = updater,
                    plan = plan,
                    projected = projected,
                    uploadThumbnail = uploadThumbnail,
                    failure = failure,
                )
            },
        )
        runCatching {
            if (uploadThumbnail) {
                photoStore.uploadAttachmentInBackground(revision, plan.photo, failure)
            } else {
                photoStore.uploadDisplayInBackground(revision, plan.photo, failure)
            }
        }.onFailure { uploadFailureRegistry.markFailed(failure, it) }
    }

    private fun republishAttachmentUpload(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        plan: AttachmentPhotoPlan,
        projected: Boolean,
        uploadThumbnail: Boolean,
        failure: AttachmentUploadFailure,
    ) {
        val gateway = requireNotNull(attachmentGateway)
        val attachmentId = requireNotNull(plan.photoPlan.attachmentId)
        val create: ((Result<ItemAttachment>) -> Unit) -> Unit = { callback ->
            if (plan.creationOrder == null) {
                gateway.create(
                    household = householdFor(householdId),
                    item = item,
                    attachmentId = attachmentId,
                    contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
                    displayUrl = requireNotNull(plan.photoPlan.full),
                    onResult = callback,
                )
            } else {
                gateway.createInOrder(
                    household = householdFor(householdId),
                    item = item,
                    attachmentId = attachmentId,
                    creationOrder = plan.creationOrder,
                    contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
                    displayUrl = requireNotNull(plan.photoPlan.full),
                    onResult = callback,
                )
            }
        }
        create { result ->
            result.onFailure { uploadFailureRegistry.markFailed(failure, it) }
                .onSuccess {
                    if (!projected) {
                        enqueueAttachmentUpload(
                            householdId,
                            item,
                            updater,
                            plan,
                            projected = false,
                            uploadThumbnail = uploadThumbnail,
                        )
                        return@onSuccess
                    }
                    val restored = item.copy(
                        photoAttachmentId = attachmentId,
                        photoUrl = plan.photoPlan.full,
                        photoThumbnailUrl = plan.photoPlan.thumbnail,
                    )
                    store.updateItem(
                        householdId = householdId,
                        itemId = item.id,
                        data = photoProjectionData(restored, updater),
                    ) { projectionResult ->
                        projectionResult.onFailure { failureCause ->
                            gateway.delete(
                                household = householdFor(householdId),
                                item = item,
                                attachment = projectedAttachment(restored, attachmentId),
                            ) { uploadFailureRegistry.markFailed(failure, failureCause) }
                        }.onSuccess {
                            enqueueAttachmentUpload(
                                householdId,
                                restored,
                                updater,
                                plan,
                                projected = true,
                                uploadThumbnail = uploadThumbnail,
                            )
                        }
                    }
                }
        }
    }

    private fun discardPhotoAttachment(
        householdId: String,
        item: Item,
        photoPlan: ItemPhotoUpdatePlan,
        onComplete: () -> Unit,
    ) {
        val attachmentId = photoPlan.attachmentId
        if (attachmentId == null) {
            onComplete()
            return
        }
        attachmentGateway.delete(
            household = householdFor(householdId),
            item = item,
            attachment = projectedAttachment(item, attachmentId),
        ) { onComplete() }
    }

    private fun deleteStoredPhoto(item: Item) {
        photoStore.deleteInBackground(
            StoredItemPhotoLocations(
                full = item.photoUrl,
                thumbnail = item.photoThumbnailUrl,
            ),
        )
    }

    private fun photoProjectionData(
        item: Item,
        updater: AuthenticatedIdentity,
    ): Map<String, Any?> = mapOf(
        PHOTO_ATTACHMENT_ID to item.photoAttachmentId,
        PHOTO_URL to item.photoUrl,
        PHOTO_THUMBNAIL_URL to item.photoThumbnailUrl,
        UPDATED_AT to store.serverTimestamp,
        UPDATED_BY_ID to updater.id,
        UPDATED_BY_DISPLAY_NAME to updater.attributionDisplayName(),
    )

    private fun householdFor(householdId: String): Household = Household(
        id = householdId,
        ownerMemberId = "",
        rootItem = Item(
            id = householdId,
            name = "",
            parentItemId = null,
            photoUrl = null,
            description = null,
            tags = emptyList(),
        ),
    )

    private fun projectedAttachment(item: Item, attachmentId: String) = ItemAttachment(
        id = attachmentId,
        itemId = item.id,
        createdAt = Instant.EPOCH,
        contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
        displayUrl = item.photoUrl.orEmpty(),
    )

    private fun createItemDocument(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        itemId: String,
        details: ItemDetails,
        photoAttachmentId: String?,
        photoLocations: ItemPhotoLocations?,
        onResult: (Result<Item>) -> Unit,
    ) {
        val item = Item(
            id = itemId,
            name = details.name,
            parentItemId = parentItemId,
            photoAttachmentId = photoAttachmentId,
            photoUrl = photoLocations?.full,
            description = details.description,
            tags = details.tags,
            photoThumbnailUrl = photoLocations?.thumbnail,
            webUrl = details.webUrl,
            displayOrder = Instant.now().toEpochMilli(),
        )
        val displayName = creator.attributionDisplayName()
        val data = mapOf(
            HOUSEHOLD_ID to householdId,
            NAME to item.name,
            PARENT_ITEM_ID to parentItemId,
            PHOTO_ATTACHMENT_ID to photoAttachmentId,
            PHOTO_URL to photoLocations?.full,
            PHOTO_THUMBNAIL_URL to photoLocations?.thumbnail,
            DESCRIPTION to item.description,
            TAGS to item.tags,
            WEB_URL to item.webUrl,
            DISPLAY_ORDER to item.displayOrder,
            CREATED_AT to store.serverTimestamp,
            UPDATED_AT to store.serverTimestamp,
            CREATED_BY_ID to creator.id,
            CREATED_BY_DISPLAY_NAME to displayName,
            UPDATED_BY_ID to creator.id,
            UPDATED_BY_DISPLAY_NAME to displayName,
        )
        store.createItem(householdId, itemId, data) { result ->
            onResult(result.map { item })
        }
    }
}

private data class ItemPhotoUpdatePlan(
    val attachmentId: String?,
    val full: String?,
    val thumbnail: String?,
    val revision: ItemPhotoRevision?,
)

internal fun interface ItemMoveService {
    fun move(
        householdId: String,
        itemId: String,
        newParentItemId: String,
        onResult: (Result<Unit>) -> Unit,
    )
}

internal fun interface ItemDeletionService {
    fun delete(
        householdId: String,
        itemId: String,
        onResult: (Result<Unit>) -> Unit,
    )
}

private class FirebaseItemDeletionService(
    private val functions: FirebaseFunctions =
        FirebaseFunctions.getInstance(ITEM_DELETION_FUNCTION_REGION),
) : ItemDeletionService {
    override fun delete(
        householdId: String,
        itemId: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        functions
            .getHttpsCallable(ITEM_DELETION_FUNCTION_NAME)
            .call(mapOf("householdId" to householdId, "itemId" to itemId))
            .addOnCompleteListener { task ->
                val result = if (task.isSuccessful) {
                    runCatching {
                        val response = task.result?.data as? Map<*, *>
                            ?: error("Deletion returned an invalid response.")
                        check(response["itemId"] == itemId)
                    }
                } else {
                    Result.failure(task.exception ?: IllegalStateException("Deletion failed."))
                }
                onResult(result.map { Unit })
            }
    }
}

private class FirebaseItemMoveService(
    private val functions: FirebaseFunctions =
        FirebaseFunctions.getInstance(ITEM_MOVE_FUNCTION_REGION),
) : ItemMoveService {
    override fun move(
        householdId: String,
        itemId: String,
        newParentItemId: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        functions
            .getHttpsCallable(ITEM_MOVE_FUNCTION_NAME)
            .call(
                mapOf(
                    "householdId" to householdId,
                    "itemId" to itemId,
                    "newParentItemId" to newParentItemId,
                ),
            )
            .addOnCompleteListener { task ->
                val result = if (task.isSuccessful) {
                    runCatching {
                        val response = task.result?.data as? Map<*, *>
                            ?: error("Move returned an invalid response.")
                        check(response["itemId"] == itemId)
                        check(response["parentItemId"] == newParentItemId)
                    }
                } else {
                    Result.failure(task.exception ?: IllegalStateException("Move failed."))
                }
                onResult(result.map { Unit })
            }
    }
}

private data class AttachmentPhotoPlan(
    val photo: ItemPhoto,
    val photoPlan: ItemPhotoUpdatePlan,
    val creationOrder: Long? = null,
)

internal interface InventoryPhotoStore {
    /** Returns the unique display-upload work for a stored location, when this store can track it. */
    fun displayUploadWorkName(location: String): String? = null

    fun newAttachmentId(householdId: String, itemId: String): String = UUID.randomUUID().toString()

    fun newAttachmentRevision(
        householdId: String,
        itemId: String,
        attachmentId: String,
    ): ItemPhotoRevision

    fun uploadAttachmentInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
        failure: AttachmentUploadFailure? = null,
    )

    fun uploadDisplayInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
    ) = uploadAttachmentInBackground(revision, photo)

    fun uploadDisplayInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
        failure: AttachmentUploadFailure?,
    ) = uploadDisplayInBackground(revision, photo)

    fun uploadThumbnailInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
    )

    fun generateAttachmentThumbnailInBackground(
        revision: ItemPhotoRevision,
        sourceLocation: String,
    ) = Unit

    fun deleteInBackground(locations: StoredItemPhotoLocations)
}

internal fun firebaseInventoryPhotoStore(): InventoryPhotoStore {
    val storage = FirebaseStorage.getInstance()
    return BackgroundInventoryPhotoStore(
        bucketUrl = storage.reference.toString(),
        queue = WorkManagerPhotoTransferQueue(FirebaseApp.getInstance().applicationContext),
    )
}

internal data class ItemPhotoRevision(
    val locations: ItemPhotoLocations,
    val fullStoragePath: String,
    val thumbnailStoragePath: String,
)

internal data class StoredItemPhotoLocations(
    val full: String?,
    val thumbnail: String?,
) {
    fun presentLocations(): List<String> = listOfNotNull(full, thumbnail)
}

internal data class InventoryItemDocument(
    val id: String,
    val data: Map<String, Any?>,
) {
    fun toItem(expectedHouseholdId: String): Item {
        if (data.inventoryString(HOUSEHOLD_ID) != expectedHouseholdId) {
            throw InvalidInventoryException()
        }
        return Item(
            id = id,
            name = data.inventoryString(NAME),
            parentItemId = data.inventoryNullableString(PARENT_ITEM_ID),
            photoAttachmentId = data.inventoryNullableString(PHOTO_ATTACHMENT_ID),
            photoUrl = data.inventoryNullableString(PHOTO_URL),
            description = data.inventoryNullableString(DESCRIPTION),
            tags = data[TAGS]
                ?.let { rawTags ->
                    (rawTags as? List<*>)
                        ?.map { it as? String ?: throw InvalidInventoryException() }
                }
                ?: throw InvalidInventoryException(),
            photoThumbnailUrl = data.inventoryNullableString(PHOTO_THUMBNAIL_URL),
            webUrl = data.inventoryNullableString(WEB_URL),
            displayOrder = data.inventoryNullableLong(DISPLAY_ORDER),
        )
    }
}

internal interface InventoryDocumentStore {
    val serverTimestamp: Any

    fun observeItems(
        householdId: String,
        onResult: (Result<List<InventoryItemDocument>>) -> Unit,
    ): InventorySubscription

    fun newItemId(householdId: String): String

    fun createItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    )

    fun updateItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    )

    fun reorderItems(
        householdId: String,
        orderedItemIds: List<String>,
        updater: AuthenticatedIdentity,
        onResult: (Result<Unit>) -> Unit,
    ) = onResult(Result.failure(UnsupportedOperationException("Item reordering is unavailable.")))
}

private class FirestoreInventoryDocumentStore(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : InventoryDocumentStore {
    override val serverTimestamp: Any
        get() = FieldValue.serverTimestamp()

    override fun observeItems(
        householdId: String,
        onResult: (Result<List<InventoryItemDocument>>) -> Unit,
    ): InventorySubscription {
        val registration = items(householdId).addSnapshotListener { snapshot, failure ->
            if (failure != null) {
                onResult(Result.failure(failure))
            } else {
                onResult(
                    runCatching {
                        requireNotNull(snapshot).documents.map { document ->
                            InventoryItemDocument(document.id, document.data ?: emptyMap())
                        }
                    },
                )
            }
        }
        return InventorySubscription(registration::remove)
    }

    override fun newItemId(householdId: String): String = items(householdId).document().id

    override fun createItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        items(householdId).document(itemId).set(data)
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun updateItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        items(householdId).document(itemId).update(data)
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun reorderItems(
        householdId: String,
        orderedItemIds: List<String>,
        updater: AuthenticatedIdentity,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val batch = firestore.batch()
        orderedItemIds.forEachIndexed { index, itemId ->
            batch.update(
                items(householdId).document(itemId),
                mapOf(
                    DISPLAY_ORDER to index.toLong(),
                    UPDATED_AT to serverTimestamp,
                    UPDATED_BY_ID to updater.id,
                    UPDATED_BY_DISPLAY_NAME to updater.attributionDisplayName(),
                ),
            )
        }
        batch.commit()
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    private fun items(householdId: String) = firestore
        .collection(HOUSEHOLDS)
        .document(householdId)
        .collection(ITEMS)
}

private fun Map<String, Any?>.inventoryString(key: String): String =
    this[key] as? String ?: throw InvalidInventoryException()

private fun Map<String, Any?>.inventoryNullableString(key: String): String? {
    val value = this[key]
    if (value != null && value !is String) throw InvalidInventoryException()
    return value
}

private fun Map<String, Any?>.inventoryNullableLong(key: String): Long? {
    val value = this[key] ?: return null
    return value as? Long ?: throw InvalidInventoryException()
}

internal fun AuthenticatedIdentity.attributionDisplayName(): String =
    displayName?.takeIf(String::isNotBlank)
        ?: email?.takeIf(String::isNotBlank)
        ?: "Household Member"

private const val HOUSEHOLDS = "households"
private const val ITEMS = "items"
private const val HOUSEHOLD_ID = "householdId"
private const val NAME = "name"
private const val PARENT_ITEM_ID = "parentItemId"
private const val PHOTO_ATTACHMENT_ID = "photoAttachmentId"
private const val PHOTO_URL = "photoUrl"
private const val PHOTO_THUMBNAIL_URL = "photoThumbnailUrl"
private const val DESCRIPTION = "description"
private const val TAGS = "tags"
private const val WEB_URL = "webUrl"
private const val DISPLAY_ORDER = "displayOrder"
private const val CREATED_AT = "createdAt"
private const val UPDATED_AT = "updatedAt"
private const val CREATED_BY_ID = "createdById"
private const val CREATED_BY_DISPLAY_NAME = "createdByDisplayName"
private const val UPDATED_BY_ID = "updatedById"
private const val UPDATED_BY_DISPLAY_NAME = "updatedByDisplayName"
private const val ITEM_MOVE_FUNCTION_REGION = "australia-southeast1"
private const val ITEM_MOVE_FUNCTION_NAME = "moveInventoryItem"
private const val ITEM_DELETION_FUNCTION_REGION = "australia-southeast1"
private const val ITEM_DELETION_FUNCTION_NAME = "deleteInventoryItem"
