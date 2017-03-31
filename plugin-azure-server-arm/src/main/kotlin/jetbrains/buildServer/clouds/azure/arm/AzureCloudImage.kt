/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.clouds.azure.arm

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.clouds.CloudInstanceUserData
import jetbrains.buildServer.clouds.InstanceStatus
import jetbrains.buildServer.clouds.QuotaException
import jetbrains.buildServer.clouds.azure.AzureUtils
import jetbrains.buildServer.clouds.azure.arm.connector.AzureApiConnector
import jetbrains.buildServer.clouds.base.AbstractCloudImage
import jetbrains.buildServer.clouds.base.connector.AbstractInstance
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking

/**
 * Azure cloud image.
 */
class AzureCloudImage constructor(private val myImageDetails: AzureCloudImageDetails,
                                  private val myApiConnector: AzureApiConnector)
    : AbstractCloudImage<AzureCloudInstance, AzureCloudImageDetails>(myImageDetails.sourceId, myImageDetails.sourceId) {

    private val METADATA_CONTENT_MD5 = "contentMD5"

    override fun getImageDetails(): AzureCloudImageDetails {
        return myImageDetails
    }

    override fun createInstanceFromReal(realInstance: AbstractInstance): AzureCloudInstance {
        return AzureCloudInstance(this, realInstance.name).apply {
            properties = realInstance.properties
        }
    }

    override fun canStartNewInstance(): Boolean {
        return activeInstances.size < myImageDetails.maxInstances
    }

    override fun startNewInstance(userData: CloudInstanceUserData): AzureCloudInstance {
        if (!canStartNewInstance()) {
            throw QuotaException("Unable to start more instances. Limit reached")
        }

        val instance = if (!myImageDetails.behaviour.isDeleteAfterStop) tryToStartStoppedInstance() else null
        return instance ?: startInstance(userData)
    }

    /**
     * Creates a new virtual machine.
     *
     * @param userData info about server.
     * @return created instance.
     */
    private fun startInstance(userData: CloudInstanceUserData): AzureCloudInstance {
        val name = getInstanceName()
        val instance = AzureCloudInstance(this, name)
        instance.status = InstanceStatus.SCHEDULED_TO_START
        val data = AzureUtils.setVmNameForTag(userData, name)

        async(CommonPool) {
            val metadata = myApiConnector.getVhdMetadataAsync(imageDetails.imageUrl).await() ?: emptyMap()

            instance.properties[AzureConstants.TAG_PROFILE] = userData.profileId
            instance.properties[AzureConstants.TAG_SOURCE] = imageDetails.sourceId
            instance.properties[AzureConstants.TAG_IMAGE_HASH] = metadata[METADATA_CONTENT_MD5] ?: ""

            try {
                myApiConnector.createVmAsync(instance, data).await()
                LOG.info("Virtual machine $name has been successfully created")
            } catch (e: Throwable) {
                LOG.warnAndDebugDetails(e.message, e)

                instance.status = InstanceStatus.ERROR
                instance.updateErrors(TypedCloudErrorInfo.fromException(e))

                LOG.info("Removing allocated resources for virtual machine " + name)
                try {
                    myApiConnector.deleteVmAsync(instance).await()
                    LOG.info("Allocated resources for virtual machine $name have been removed")
                } catch (e: Throwable) {
                    val message = "Failed to delete allocated resources for virtual machine $name: ${e.message}"
                    LOG.warnAndDebugDetails(message, e)
                }
            }
        }

        addInstance(instance)

        return instance
    }

    /**
     * Tries to find and start stopped instance.
     *
     * @return instance if it found.
     */
    private fun tryToStartStoppedInstance(): AzureCloudInstance? = runBlocking {
        val instances = stoppedInstances
        if (instances.isNotEmpty()) {
            val metadata = myApiConnector.getVhdMetadataAsync(imageDetails.imageUrl).await()
            val validInstances = instances.filter {
                metadata != null && metadata[METADATA_CONTENT_MD5] == it.properties[AzureConstants.TAG_IMAGE_HASH]
            }

            val invalidInstances = instances - validInstances
            val instance = validInstances.firstOrNull()

            instance?.status = InstanceStatus.SCHEDULED_TO_START

            async(CommonPool) {
                invalidInstances.forEach {
                    try {
                        LOG.info("Removing outdated virtual machine ${it.name}")
                        myApiConnector.deleteVmAsync(it)
                    } catch (e: Throwable) {
                        LOG.warnAndDebugDetails(e.message, e)
                        it.status = InstanceStatus.ERROR
                        it.updateErrors(TypedCloudErrorInfo.fromException(e))
                    }
                }

                instance?.let {
                    try {
                        myApiConnector.startVmAsync(it).await()
                        LOG.info(String.format("Virtual machine %s has been successfully started", it.name))
                    } catch (e: Throwable) {
                        LOG.warnAndDebugDetails(e.message, e)
                        it.status = InstanceStatus.ERROR
                        it.updateErrors(TypedCloudErrorInfo.fromException(e))
                    }
                }
            }

            return@runBlocking instance
        }

        null
    }

    override fun restartInstance(instance: AzureCloudInstance) {
        instance.status = InstanceStatus.RESTARTING

        async(CommonPool) {
            try {
                myApiConnector.restartVmAsync(instance).await()
                LOG.info(String.format("Virtual machine %s has been successfully restarted", instance.name))
            } catch (e: Throwable) {
                LOG.warnAndDebugDetails(e.message, e)
                instance.status = InstanceStatus.ERROR
                instance.updateErrors(TypedCloudErrorInfo.fromException(e))
            }
        }
    }

    override fun terminateInstance(instance: AzureCloudInstance) {
        instance.status = InstanceStatus.SCHEDULED_TO_STOP

        async(CommonPool) {
            try {
                val metadata = myApiConnector.getVhdMetadataAsync(imageDetails.imageUrl).await()
                val sameVhdImage = metadata != null && metadata[METADATA_CONTENT_MD5] ==
                        instance.properties[AzureConstants.TAG_IMAGE_HASH]

                if (myImageDetails.behaviour.isDeleteAfterStop || !sameVhdImage) {
                    myApiConnector.deleteVmAsync(instance).await()
                } else {
                    myApiConnector.stopVmAsync(instance).await()
                }

                instance.status = InstanceStatus.STOPPED

                if (myImageDetails.behaviour.isDeleteAfterStop) {
                    removeInstance(instance.instanceId)
                }

                LOG.info(String.format("Virtual machine %s has been successfully stopped", instance.name))
            } catch (e: Throwable) {
                LOG.warnAndDebugDetails(e.message, e)
                instance.status = InstanceStatus.ERROR
                instance.updateErrors(TypedCloudErrorInfo.fromException(e))
            }
        }
    }

    override fun getAgentPoolId(): Int? {
        return myImageDetails.agentPoolId
    }

    private fun getInstanceName(): String {
        val keys = instances.map { it.instanceId.toLowerCase() }
        val sourceName = myImageDetails.sourceId.toLowerCase()
        var i: Int = 1

        while (keys.contains(sourceName + i)) i++

        return sourceName + i
    }

    /**
     * Returns active instances.
     *
     * @return instances.
     */
    private val activeInstances: List<AzureCloudInstance>
        get() = instances.filter { instance -> instance.status.isStartingOrStarted }

    /**
     * Returns stopped instances.
     *
     * @return instances.
     */
    private val stoppedInstances: List<AzureCloudInstance>
        get() = instances.filter { instance -> instance.status == InstanceStatus.STOPPED }

    companion object {
        private val LOG = Logger.getInstance(AzureCloudImage::class.java.name)
    }
}