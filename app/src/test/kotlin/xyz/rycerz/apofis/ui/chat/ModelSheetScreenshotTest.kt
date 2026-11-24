package xyz.rycerz.apofis.ui.chat

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.api.ModelOptions
import xyz.rycerz.apofis.api.ProviderModels
import xyz.rycerz.apofis.ui.windowShot

/** The model sheet (a current choice checked) and the reasoning sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ModelSheetScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val options = ModelOptions(
        "alpha-large", "alpha",
        listOf(ProviderModels("alpha", listOf("alpha-large", "alpha-small")), ProviderModels("beta", listOf("beta-1", "beta-1-mini"))),
    )

    @Test
    fun model_sheet() = rule.windowShot("model_sheet", hasText("beta-1-mini")) {
        ModelSheet(ChoiceSheet.Model(options), ChatModel("alpha-small", "alpha"), onPick = { _, _ -> }, onRetry = {}, onDismiss = {})
    }

    @Test
    fun reasoning_sheet() = rule.windowShot("reasoning_sheet", hasText("High")) {
        ReasoningSheet(ChatModel(reasoning = "medium"), onPick = {}, onDismiss = {})
    }
}
