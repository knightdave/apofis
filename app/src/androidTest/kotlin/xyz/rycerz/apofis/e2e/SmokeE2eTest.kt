package xyz.rycerz.apofis.e2e

import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.HermesApi

/**
 * Smoke test for tools/emulator/run-e2e.sh: connect to a real Hermes server and leave the Connect screen.
 * Instrumentation arguments: apofisUrl, apofisKey. The chat screen is not on this branch yet, so no message is sent.
 */
@RunWith(AndroidJUnit4::class)
class SmokeE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    private fun arg(name: String) = requireNotNull(args.getString(name)?.takeIf(String::isNotEmpty)) { "Instrumentation argument $name is missing" }

    private fun nodes(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            if (match(node)) found += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }

    /** Visible texts outside text fields, for failure messages (field contents may hold the key). */
    private fun screenTexts() = nodes { it.className != "android.widget.EditText" && !it.text.isNullOrEmpty() }.joinToString(" | ") { it.text }

    @Test
    fun connectLeavesConnectScreen() {
        val url = arg("apofisUrl")
        val key = arg("apofisKey")
        val pkg = context.packageName
        context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))

        val title = context.getString(R.string.connect_title)
        assertNotNull("Connect screen did not appear", device.wait(Until.findObject(By.text(title)), TIMEOUT))
        // Accessibility actions instead of UiObject2.setText/click: UiObject2 logs the text it sets (the key) to
        // logcat, and actions work even when the keyboard covers the button.
        val fields = nodes { it.className == "android.widget.EditText" } // screen order: server URL, then API key
        assertTrue("Expected the URL and key fields, found ${fields.size}", fields.size == 2)
        for ((field, text) in fields.zip(listOf(url, key))) {
            val set = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            assertTrue("Could not fill a field", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set))
        }
        device.waitForIdle()
        val connect = context.getString(R.string.connect_button)
        val button = nodes { it.isClickable && (it.text?.toString() == connect || nodesText(it) == connect) }.firstOrNull()
        assertNotNull("No Connect button: ${screenTexts()}", button)
        assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))

        // Connected: the chat placeholder (and its Settings button) replaces the Connect screen.
        val settings = device.wait(Until.findObject(By.desc(context.getString(R.string.settings_title))), TIMEOUT)
        assertNotNull("Still on the Connect screen after connecting: ${screenTexts()}; direct probe: ${probe(url, key)}", settings)
        assertTrue(device.wait(Until.gone(By.text(title)), TIMEOUT))

        // Runs as the shell user, so the file survives the app's uninstall; run-e2e.sh pulls it.
        val screencap = instrumentation.uiAutomation.executeShellCommand("screencap -p $SCREENSHOT_DIR/smoke-connected.png")
        ParcelFileDescriptor.AutoCloseInputStream(screencap).use { it.readBytes() } // waits for screencap to finish
    }

    /** Repeats the Connect screen's check in this process, to name the cause when the app only shows a message. */
    private fun probe(url: String, key: String): String = try {
        runBlocking { HermesApi(url, key).capabilities() }
        "ok"
    } catch (e: Exception) {
        "$e caused by ${e.cause}" // ApiError and OkHttp messages name the host, never the key
    }

    /** Compose merges a button's label into the button node, but older versions keep it on a child. */
    private fun nodesText(node: AccessibilityNodeInfo): String? =
        (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.text?.toString() }

    companion object {
        const val TIMEOUT = 30_000L
        const val SCREENSHOT_DIR = "/data/local/tmp/apofis-e2e"
    }
}
