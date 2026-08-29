package com.linkguard.app.security

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.linkguard.app.service.LinkNotificationService
import com.linkguard.app.ui.LinkInterceptActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ComponentSecurityContractTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun mergedManifestKeepsEveryExternalEntryPointAllowlistedOrPermissionProtected() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS,
        )

        val exportedActivities = packageInfo.activities.orEmpty()
            .filter { it.exported }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                LinkInterceptActivity::class.java.name,
                "${context.packageName}.ui.LauncherV124",
            ),
            exportedActivities,
        )

        val unprotectedServices = packageInfo.services.orEmpty()
            .filter { it.exported && it.permission.isNullOrBlank() }
            .map { it.name }
        assertTrue("Unprotected exported services: $unprotectedServices", unprotectedServices.isEmpty())

        val notificationListener = packageInfo.services.orEmpty()
            .single { it.name == LinkNotificationService::class.java.name }
        assertTrue(notificationListener.exported)
        assertEquals(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, notificationListener.permission)

        val unprotectedReceivers = packageInfo.receivers.orEmpty()
            .filter { it.exported && it.permission.isNullOrBlank() }
            .map { it.name }
        assertTrue("Unprotected exported receivers: $unprotectedReceivers", unprotectedReceivers.isEmpty())

        val exportedProviders = packageInfo.providers.orEmpty()
            .filter { it.exported }
            .map { it.name }
        assertTrue("Exported providers: $exportedProviders", exportedProviders.isEmpty())
    }
}
